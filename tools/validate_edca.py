"""运行 EDCA 综合性能验收，并从逐事件日志独立复算统计。

仅使用 Python 标准库。先在仓库根目录执行 mvn -o clean package，
再执行 python tools/validate_edca.py。生成结果不依赖终端的日志截断。
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict, deque
import csv
from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
ACS = ("VO", "VI", "BE", "BK")
DIRECTIONS = ("ALL", "DL", "UL")
CW_MAX = {"VO": 7, "VI": 15, "BE": 1023, "BK": 1023}
DEFAULT_SEEDS = (11, 29, 47, 71, 101)
DEFAULT_LOADS = (1000, 5000, 10000)
PAYLOAD_BYTES = 1200


def ns(value: str) -> int:
    """日志至多保留三位小数微秒，使用整数纳秒做全部时序验证。"""
    whole, _, fractional = value.partition(".")
    if len(fractional) > 3:
        raise AssertionError(f"Unexpected clock precision {value}")
    return int(whole) * 1000 + int(fractional.ljust(3, "0") or "0")


def fields(line: str) -> dict[str, str]:
    return dict(token.split("=", 1) for token in line.split())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


@dataclass
class PacketTrace:
    id: int
    generated: int
    ac: str
    direction: str
    size: int
    sta: int
    sfu: int
    enqueued: int | None = None
    hol: int | None = None
    attempts: int = 0
    last_start: int | None = None
    last_kind: str = ""
    data_end: int | None = None
    ack_start: int | None = None
    ack_end: int | None = None
    delivered: int | None = None
    terminal: str = ""
    collision_attempts: int = 0
    virtual_collisions: int = 0
    retransmissions: int = 0
    timeouts: int = 0

    @property
    def queue_key(self) -> tuple[int, int, str]:
        return self.sfu, self.sta if self.direction == "UL" else 0, self.ac


@dataclass
class Occupancy:
    kind: str
    start: int
    ac: str
    direction: str
    packet_id: int
    txop: bool = False
    last_ack: int | None = None
    frames_started: int = 1
    collision_ends: dict[int, int | None] = field(default_factory=dict)


class TraceAnalysis:
    def __init__(self, sfu_count: int, duration_us: int, *, data_duration_ns: int = 64_000):
        self.sfu_count = sfu_count
        self.duration_ns = duration_us * 1000
        self.data_duration_ns = data_duration_ns
        self.packets: dict[int, PacketTrace] = {}
        self.queues: dict[tuple[int, int, str], deque[int]] = defaultdict(deque)
        self.channels: dict[int, Occupancy] = {}
        self.intervals: list[tuple[int, int, int]] = []
        self.occupied_ns: Counter[tuple[int, str, str]] = Counter()
        self.txop_counts: Counter[tuple[str, str]] = Counter()
        self.txop_ns: Counter[tuple[str, str]] = Counter()
        self.txop_packets = 0
        self.collision_groups = 0
        self.summary: dict[str, dict[str, str]] = {}
        self.last_time = 0
        self.log_lines = 0
        self.generation_digest = hashlib.sha256()

    def consume(self, line: str) -> None:
        self.log_lines += 1
        row = fields(line)
        if "summary" in row:
            # 同名的逐设备旧统计仅用于报告中的口径说明，不覆盖新计算。
            if row["summary"] in ("WIFI_COLLISION", "WIFI_RETRY", "WIFI_TXOP"):
                self.summary[row["summary"]] = row
            return
        if "delivered_packets" in row:
            self.summary["RUN"] = row
            return
        time = ns(row["t_us"])
        require(time >= self.last_time, "Simulation clock moved backwards")
        self.last_time = time
        event = row["event"]
        if event == "ARRIVAL" and row["node"] in ("MFU", "STA"):
            self._generate(row, time)
            return
        if event == "WIFI_COLLISION_END":
            self._end_collision(int(row["sfu_id"]), time)
            return
        packet = self.packets[int(row["packet_id"])]
        require(row.get("type", packet.ac) == packet.ac, "Packet AC changed")
        if "bytes" in row:
            require(int(row["bytes"]) == packet.size, "Packet size changed")

        if event == "SFU_ENQUEUE":
            self._enqueue(packet, row, time)
        elif event == "WIFI_TX_START":
            self._normal_start(packet, time)
        elif event == "WIFI_TXOP_START":
            occupied = self.channels[packet.sfu]
            require(occupied.kind == "normal" and occupied.start == time, "Invalid TXOP start")
            occupied.txop = True
        elif event == "WIFI_TXOP_END":
            self._end_txop(packet, row, time)
        elif event == "WIFI_COLLISION_START":
            self._collision_start(packet, time)
        elif event == "WIFI_MAC_ATTEMPT":
            self._attempt(packet, row, time)
        elif event == "WIFI_DATA_END":
            require(packet.last_kind == "normal", "Collided DATA was decoded")
            require(packet.data_end is None, "Duplicate DATA reception")
            require(time == packet.last_start + self.data_duration_ns, "Benchmark DATA PPDU duration mismatch")
            packet.data_end = time
        elif event == "WIFI_SIFS_START":
            require(packet.last_kind == "normal" and packet.data_end == time, "Invalid SIFS")
        elif event == "WIFI_ACK_START":
            require(packet.last_kind == "normal" and packet.data_end is not None, "ACK for collided DATA")
            require(time == packet.data_end + 16000, "SIFS duration mismatch")
            packet.ack_start = time
        elif event == "WIFI_ACK_END":
            self._ack_end(packet, time)
        elif event == "WIFI_COLLISION_DATA_END":
            require(packet.last_kind == "collision", "Collision end without a collision")
            require(time == packet.last_start + self.data_duration_ns, "Collided DATA PPDU duration mismatch")
            packet.data_end = time
            self.channels[packet.sfu].collision_ends[packet.id] = time
        elif event == "WIFI_ACK_TIMEOUT":
            require(packet.last_kind == "collision" and packet.data_end is not None, "Invalid ACK timeout")
            require(time == packet.data_end + 53000, "ACK timeout duration mismatch")
            packet.timeouts += 1
        elif event == "EDCA_VIRTUAL_COLLISION":
            packet.virtual_collisions += 1
            self._cw_update(packet, row)
            winner = self.packets[int(row["winner_packet_id"])]
            require(winner.queue_key[:2] == packet.queue_key[:2], "Virtual collision crossed devices")
            require(ACS.index(winner.ac) < ACS.index(packet.ac), "Virtual collision priority reversed")
        elif event == "WIFI_MAC_RETRY":
            self._cw_update(packet, row)
            require(int(row["retry_count"]) == packet.attempts, "Retry count skipped or duplicated")
        elif event == "WIFI_MAC_SUCCESS":
            require(packet.ack_end == time, "Success was counted before ACK completion")
            self._terminal(packet, "success", time)
        elif event == "WIFI_RETRY_LIMIT_DROP":
            require(packet.last_kind == "collision", "Retry-limit drop without a collision")
            require(int(row["retry_count"]) == 3, "Retry limit mismatch")
            self._terminal(packet, "drop", time)
        elif event == "DELIVERED":
            require(packet.delivered is None, "Packet delivered more than once")
            require(packet.last_kind == "normal", "Collision counted as delivery")
            require(packet.data_end is not None, "Delivery before DATA end")
            packet.delivered = time
        elif event == "FIBER_TX_START" and packet.direction == "UL":
            require(packet.last_kind == "normal" and packet.data_end == time, "Failed DATA forwarded to fiber")
        elif event == "WIFI_TX_END":
            require(packet.ack_end == time, "Service ended before ACK")
            require(ns(row["wifi_tx_start_us"]) == packet.last_start, "Logged DATA start mismatch")
            require(ns(row["wifi_queue_wait_us"]) == packet.last_start - packet.enqueued,
                    "Logged queue wait differs from enqueue to final DATA start")

    def _generate(self, row: dict[str, str], time: int) -> None:
        packet_id = int(row["packet_id"])
        require(packet_id not in self.packets, "Packet generated more than once")
        direction = row.get("direction", "DL")
        sta = int(row["source_sta_id"] if direction == "UL" else row["destination_sta_id"])
        sfu = (sta - 1) // 4 + 1
        self.packets[packet_id] = PacketTrace(packet_id, time, row["type"], direction,
                                              int(row["bytes"]), sta, sfu)
        identity = f"{packet_id},{time},{row['type']},{direction},{row['bytes']},{sta},{sfu}\n"
        self.generation_digest.update(identity.encode("ascii"))

    def _enqueue(self, packet: PacketTrace, row: dict[str, str], time: int) -> None:
        require(packet.enqueued is None, "Packet enqueued more than once")
        require(int(row["sfu_id"]) == packet.sfu, "STA reached the wrong SFU")
        packet.enqueued = time
        queue = self.queues[packet.queue_key]
        if not queue:
            packet.hol = time
        queue.append(packet.id)
        require(int(row["queue_depth"]) == len(queue), "Queue depth mismatch")

    def _normal_start(self, packet: PacketTrace, time: int) -> None:
        occupied = self.channels.get(packet.sfu)
        if occupied is None:
            self.channels[packet.sfu] = Occupancy("normal", time, packet.ac, packet.direction, packet.id)
        else:
            require(occupied.kind == "normal" and occupied.txop, "Unrelated overlapping normal transmissions")
            first = self.packets[occupied.packet_id]
            require(first.queue_key == packet.queue_key, "TXOP changed sender or AC")
            require(occupied.last_ack is not None and time == occupied.last_ack + 16000,
                    "TXOP continuation did not follow SIFS")
            occupied.frames_started += 1
        packet.last_kind = "normal"
        packet.last_start = time
        packet.data_end = packet.ack_start = packet.ack_end = None

    def _collision_start(self, packet: PacketTrace, time: int) -> None:
        occupied = self.channels.get(packet.sfu)
        if occupied is None:
            occupied = Occupancy("collision", time, packet.ac, packet.direction, packet.id)
            self.channels[packet.sfu] = occupied
            self.collision_groups += 1
        require(occupied.kind == "collision" and occupied.start == time, "Collision overlapped an occupied channel")
        require(packet.id not in occupied.collision_ends, "Duplicate collided DATA")
        other_senders = {self.packets[pid].queue_key[:2] for pid in occupied.collision_ends}
        require(packet.queue_key[:2] not in other_senders, "One device sent twice in a collision")
        occupied.collision_ends[packet.id] = None
        packet.collision_attempts += 1
        packet.last_kind = "collision"
        packet.last_start = time
        packet.data_end = packet.ack_start = packet.ack_end = None

    def _attempt(self, packet: PacketTrace, row: dict[str, str], time: int) -> None:
        require(packet.hol is not None and packet.hol <= time, "Non-head packet sent")
        require(self.queues[packet.queue_key][0] == packet.id, "Same-class FIFO violated")
        require(packet.last_start == time, "Attempt did not match DATA start")
        retry = int(row["retry_count"])
        require(retry == packet.attempts, "Attempt counter skipped or duplicated")
        packet.attempts += 1
        packet.retransmissions += int(retry > 0)

    def _ack_end(self, packet: PacketTrace, time: int) -> None:
        require(packet.last_kind == "normal" and packet.ack_start is not None, "ACK for failed DATA")
        require(time == packet.ack_start + 28000, "ACK duration mismatch")
        packet.ack_end = time
        occupied = self.channels[packet.sfu]
        require(occupied.kind == "normal", "ACK on a collision channel")
        occupied.last_ack = time
        if not occupied.txop:
            self._close_normal(packet.sfu, time)

    def _end_txop(self, packet: PacketTrace, row: dict[str, str], time: int) -> None:
        occupied = self.channels[packet.sfu]
        require(occupied.txop and occupied.last_ack == time, "TXOP release before final ACK")
        duration = ns(row["duration_us"])
        require(duration == time - occupied.start and duration <= ns(row["limit_us"]), "TXOP exceeded budget")
        require(occupied.frames_started == int(row["packet_count"]), "TXOP counted the wrong number of DATA frames")
        self.txop_counts[packet.ac, packet.direction] += 1
        self.txop_ns[packet.ac, packet.direction] += duration
        self.txop_packets += int(row["packet_count"])
        self._close_normal(packet.sfu, time)

    def _close_normal(self, sfu: int, time: int) -> None:
        occupied = self.channels.pop(sfu)
        self.intervals.append((occupied.start, time, sfu))
        self.occupied_ns[sfu, occupied.ac, occupied.direction] += time - occupied.start

    def _end_collision(self, sfu: int, time: int) -> None:
        occupied = self.channels.pop(sfu)
        ends = occupied.collision_ends
        require(occupied.kind == "collision" and len(ends) >= 2, "Collision contained fewer than two devices")
        require(all(end is not None for end in ends.values()) and max(ends.values()) == time,
                "Channel released before longest collided DATA")
        self.intervals.append((occupied.start, time, sfu))
        # 每个时间片按仍在发射的报文等分碰撞忙时，AC 占用相加等于物理忙时。
        previous = occupied.start
        for endpoint in sorted(set(ends.values())):
            active = [self.packets[pid] for pid, end in ends.items() if end >= endpoint]
            for packet in active:
                self.occupied_ns[sfu, packet.ac, packet.direction] += (endpoint - previous) / len(active)
            previous = endpoint

    def _terminal(self, packet: PacketTrace, outcome: str, time: int) -> None:
        require(not packet.terminal, "Packet reached a terminal outcome more than once")
        packet.terminal = outcome
        queue = self.queues[packet.queue_key]
        require(queue.popleft() == packet.id, "FIFO terminal order mismatch")
        if queue:
            following = self.packets[queue[0]]
            require(following.hol is None, "Queue head activated twice")
            following.hol = time

    @staticmethod
    def _cw_update(packet: PacketTrace, row: dict[str, str]) -> None:
        before = int(row["cw_before"])
        after = int(row["cw_after"])
        require(after == min(2 * before + 1, CW_MAX[packet.ac]), "CW growth mismatch")
        require(0 <= int(row["backoff"]) <= after, "Backoff outside CW")

    def finish(self) -> dict[str, int | float | bool]:
        require(not self.channels, "Channel still occupied at simulation end")
        require(all(not queue for queue in self.queues.values()), "Queued packets remain after drain")
        require(all(packet.enqueued is not None for packet in self.packets.values()), "Generated packet never enqueued")
        successes = [packet for packet in self.packets.values() if packet.terminal == "success"]
        drops = [packet for packet in self.packets.values() if packet.terminal == "drop"]
        require(len(successes) + len(drops) == len(self.packets), "Packet conservation failed")
        require(all(packet.delivered is not None for packet in successes), "Successful packet not delivered")
        require(all(packet.delivered is None for packet in drops), "Dropped packet also delivered")
        run = self.summary["RUN"]
        require(len(successes) == int(run["delivered_packets"]), "Global delivery count mismatch")
        require(sum(packet.size for packet in successes) == int(run["delivered_bytes"]), "Global delivered bytes mismatch")
        require(ns(run["simulation_end_us"]) == self.last_time, "Final clock differs from final event")
        busy_union = interval_union(self.intervals)
        require(busy_union == ns(run["wifi_busy_us"]), "Global busy union mismatch")
        require(busy_union + ns(run["wifi_idle_us"]) == self.last_time, "Busy and idle windows do not cover the run")
        busy_sum = sum(end - start for start, end, _ in self.intervals)
        require(abs(sum(self.occupied_ns.values()) - busy_sum) < 0.01, "AC air-time allocation mismatch")
        retry = self.summary["WIFI_RETRY"]
        require(sum(packet.retransmissions for packet in self.packets.values()) == int(retry["retransmissions"]),
                "Retry summary mismatch")
        require(sum(packet.timeouts for packet in self.packets.values()) == int(retry["ack_timeouts"]),
                "Timeout summary mismatch")
        require(len(drops) == int(retry["retry_limit_drops"]), "Drop summary mismatch")
        collision = self.summary.get("WIFI_COLLISION", {"collision_events": "0", "dropped_packets": "0", "dropped_bytes": "0"})
        require(self.collision_groups == int(collision["collision_events"]), "Collision group summary mismatch")
        require(len(drops) == int(collision["dropped_packets"]), "Collision drop count mismatch")
        require(sum(packet.size for packet in drops) == int(collision["dropped_bytes"]), "Collision dropped bytes mismatch")
        txop = self.summary.get("WIFI_TXOP", {"txops": "0", "total_duration_us": "0", "transmitted_packets": "0"})
        require(sum(self.txop_counts.values()) == int(txop["txops"]), "TXOP count mismatch")
        require(sum(self.txop_ns.values()) == ns(txop["total_duration_us"]), "TXOP duration mismatch")
        require(self.txop_packets == int(txop["transmitted_packets"]), "TXOP packet total mismatch")
        return {
            "verified": True,
            "generated_packets": len(self.packets),
            "delivered_packets": len(successes),
            "dropped_packets": len(drops),
            "collision_groups": self.collision_groups,
            "retransmissions": int(retry["retransmissions"]),
            "busy_union_us": busy_union / 1000,
            "summed_channel_busy_us": busy_sum / 1000,
            "simulation_end_us": self.last_time / 1000,
            "log_lines": self.log_lines,
            "generated_sequence_sha256": self.generation_digest.hexdigest(),
        }

    def metric_rows(self, metadata: dict[str, int | str]) -> list[dict]:
        rows = []
        for direction in DIRECTIONS:
            for ac in ACS:
                packets = [packet for packet in self.packets.values()
                           if packet.ac == ac and (direction == "ALL" or packet.direction == direction)]
                successes = [packet for packet in packets if packet.terminal == "success"]
                dropped = sum(packet.terminal == "drop" for packet in packets)
                delivered_bytes = sum(packet.size for packet in successes)
                window_bytes = sum(packet.size for packet in successes if packet.delivered < self.duration_ns)
                occupied = sum(duration for (sfu, category, flow), duration in self.occupied_ns.items()
                               if category == ac and (direction == "ALL" or flow == direction))
                txops = sum(count for (category, flow), count in self.txop_counts.items()
                            if category == ac and (direction == "ALL" or flow == direction))
                txop_duration = sum(duration for (category, flow), duration in self.txop_ns.items()
                                    if category == ac and (direction == "ALL" or flow == direction))
                queue = [packet.hol - packet.enqueued for packet in successes]
                access = [packet.last_start - packet.hol for packet in successes]
                e2e = [packet.delivered - packet.generated for packet in successes]
                rows.append(dict(metadata, direction=direction, ac=ac,
                                 generated_packets=len(packets), delivered_packets=len(successes), dropped_packets=dropped,
                                 delivered_bytes=delivered_bytes,
                                 avg_queue_delay_us=statistics.fmean(queue) / 1000 if queue else None,
                                 avg_access_delay_us=statistics.fmean(access) / 1000 if access else None,
                                 avg_enqueue_to_data_us=(statistics.fmean(queue) + statistics.fmean(access)) / 1000 if queue else None,
                                 avg_e2e_delay_us=statistics.fmean(e2e) / 1000 if e2e else None,
                                 throughput_mbps=delivered_bytes * 8000 / self.last_time,
                                 offered_window_throughput_mbps=window_bytes * 8000 / self.duration_ns,
                                 physical_collision_attempts=sum(packet.collision_attempts for packet in packets),
                                 virtual_collisions=sum(packet.virtual_collisions for packet in packets),
                                 retransmissions=sum(packet.retransmissions for packet in packets),
                                 ack_timeouts=sum(packet.timeouts for packet in packets),
                                 loss_rate=dropped / len(packets) if packets else 0,
                                 txops=txops, txop_duration_us=txop_duration / 1000,
                                 occupied_us=occupied / 1000,
                                 channel_utilization=occupied / (self.sfu_count * self.last_time),
                                 simulation_end_us=self.last_time / 1000))
        return rows


def interval_union(intervals: list[tuple[int, int, int]]) -> int:
    total = 0
    previous_end = 0
    for start, end, _ in sorted(intervals):
        total += max(0, end - max(start, previous_end))
        previous_end = max(previous_end, end)
    return total


def scenario(total_pps: float, seed: int, profile: str, duration_us: int, sfus: int = 1,
             *, bandwidth_mhz: int = 160) -> str:
    """负载是每台 SFU 的输入。每类业务各占四分之一，上下行各占一半。"""
    settings = ["traffic.mode=poisson", "traffic.direction=BOTH", f"traffic.duration.us={duration_us}",
                f"sfu.count={sfus}", f"sta.count={4 * sfus}", "fiber.delay.us=100", "wifi.model=he-su",
                f"wifi.phy.bandwidth.mhz={bandwidth_mhz}", "wifi.phy.mcs=11", "wifi.phy.nss=2", "wifi.phy.gi.us=0.8",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac", f"wifi.mac.edca.seed={seed}",
                "wifi.mac.retry.max=3"]
    for ac in ACS:
        limit = {"VO": 1504, "VI": 3008}.get(ac, 0) if profile == "txop" else 0
        settings.append(f"wifi.mac.edca.{ac}.txop.limit.us={limit}")
    for sfu in range(1, sfus + 1):
        downlink = f"traffic.sfu.{sfu}"
        settings.append(f"{downlink}.seed={seed * 1009 + sfu}")
        for ac in ACS:
            settings += [f"{downlink}.class.{ac}.rate.pps={total_pps / 8:.9f}",
                         f"{downlink}.class.{ac}.packet.bytes={PAYLOAD_BYTES}"]
        for sta in range((sfu - 1) * 4 + 1, sfu * 4 + 1):
            uplink = f"traffic.sta.{sta}"
            settings += [f"sta.{sta}.sfu.id={sfu}", f"{uplink}.seed={seed * 1013 + sta}"]
            for ac in ACS:
                settings += [f"{uplink}.class.{ac}.rate.pps={total_pps / 32:.9f}",
                             f"{uplink}.class.{ac}.packet.bytes={PAYLOAD_BYTES}"]
    return "\n".join(settings) + "\n"


def run(jar: Path, config: str, sfus: int, duration_us: int,
        *, data_duration_ns: int = 64_000) -> tuple[TraceAnalysis, dict]:
    analysis = TraceAnalysis(sfus, duration_us, data_duration_ns=data_duration_ns)
    digest = hashlib.sha256()
    with tempfile.TemporaryDirectory(prefix="fttr-edca-") as directory:
        path = Path(directory) / "scenario.properties"
        path.write_text(config, encoding="utf-8")
        with tempfile.TemporaryFile() as stderr:
            process = subprocess.Popen(["java", "-jar", str(jar), str(path)], cwd=ROOT,
                                       stdout=subprocess.PIPE, stderr=stderr)
            try:
                for raw_line in process.stdout:
                    digest.update(raw_line)
                    analysis.consume(raw_line.decode("utf-8").strip())
                code = process.wait()
                if code:
                    stderr.seek(0)
                    raise RuntimeError(stderr.read().decode("utf-8", errors="replace"))
            except BaseException:
                process.kill()
                process.wait()
                raise
    verification = analysis.finish()
    verification["trace_sha256"] = digest.hexdigest()
    verification["config_sha256"] = hashlib.sha256(config.encode("utf-8")).hexdigest()
    return analysis, verification


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def aggregate(rows: list[dict], keys=("profile", "offered_pps_per_sfu", "sfus", "duration_us", "direction", "ac")) -> list[dict]:
    numeric = [key for key in rows[0] if key not in keys + ("seed",)]
    groups = defaultdict(list)
    for row in rows:
        groups[tuple(row[key] for key in keys)].append(row)
    result = []
    for key, group in groups.items():
        item = dict(zip(keys, key), repetitions=len(group))
        for metric in numeric:
            values = [row[metric] for row in group if row[metric] is not None]
            item[metric + "_mean"] = statistics.fmean(values) if values else None
            item[metric + "_sd"] = statistics.stdev(values) if len(values) > 1 else 0.0 if values else None
        result.append(item)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/edca-acceptance")
    parser.add_argument("--loads", nargs="+", type=int, default=DEFAULT_LOADS)
    parser.add_argument("--seeds", nargs="+", type=int, default=DEFAULT_SEEDS)
    parser.add_argument("--duration-us", type=int, default=1_000_000)
    parser.add_argument("--skip-multi-sfu", action="store_true")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR first with mvn -o clean package")
    if args.duration_us <= 0 or min(args.loads) <= 0:
        parser.error("Duration and offered rates must be positive")
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    verified = []
    replay_hash = None
    replay_config = None
    generated_sequences = {}
    for profile in ("single", "txop"):
        for load in args.loads:
            for seed in args.seeds:
                metadata = dict(profile=profile, offered_pps_per_sfu=load, sfus=1,
                                duration_us=args.duration_us, seed=seed)
                config = scenario(load, seed, profile, args.duration_us)
                analysis, result = run(args.jar, config, 1, args.duration_us)
                rows.extend(analysis.metric_rows(metadata))
                verified.append(dict(metadata, **result))
                pair = load, seed
                if pair in generated_sequences:
                    require(generated_sequences[pair] == result["generated_sequence_sha256"],
                            "TXOP profile changed the generated traffic sequence")
                else:
                    generated_sequences[pair] = result["generated_sequence_sha256"]
                if replay_hash is None:
                    replay_hash, replay_config = result["trace_sha256"], config
                print(f"{profile} {load} pps seed {seed}: {result['delivered_packets']} delivered, "
                      f"{result['dropped_packets']} dropped", flush=True)
    _, replay = run(args.jar, replay_config, 1, args.duration_us)
    require(replay["trace_sha256"] == replay_hash, "Fixed-seed full trace replay differs")
    if not args.skip_multi_sfu:
        metadata = dict(profile="txop", offered_pps_per_sfu=max(args.loads), sfus=2,
                        duration_us=args.duration_us, seed=args.seeds[0])
        config = scenario(max(args.loads), args.seeds[0], "txop", args.duration_us, 2)
        analysis, result = run(args.jar, config, 2, args.duration_us)
        rows.extend(analysis.metric_rows(metadata))
        verified.append(dict(metadata, **result))
        print(f"Two SFUs: {result['delivered_packets']} delivered, {result['dropped_packets']} dropped", flush=True)
    write_csv(args.output / "runs.csv", rows)
    write_csv(args.output / "aggregate.csv", aggregate(rows))
    document = dict(
        all_runs_verified=True,
        fixed_seed_replay_identical=True,
        paired_traffic_sequences_identical=True,
        script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        python_version=sys.version,
        jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        java_version=subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip(),
        runs=verified,
        replay_trace_sha256=replay_hash,
        parameters=dict(loads=args.loads, seeds=args.seeds, duration_us=args.duration_us,
                        packet_bytes=PAYLOAD_BYTES, stations_per_sfu=4,
                        traffic="per-AC independent Poisson, half DL and half UL, equal AC rates"),
        definitions=dict(
            latency_population="Successfully delivered packets only, average over each run then mean/sample SD over seeds",
            queue_delay="SFU wireless enqueue to first becoming head of its actual sender and AC FIFO",
            access_delay="FIFO head activation to final successful DATA start, including collision airtime and ACK timeout",
            throughput="Delivered payload bits divided by 0-to-final-event drain window",
            offered_window_throughput="Payload bits delivered before generation window ends, divided by generation window",
            channel_utilization="Per-AC attributed reserved channel time divided by SFU count times final event time",
            collision_attribution="Each collision time segment is divided equally among still transmitting frames",
            physical_collision_attempts="Per-AC DATA attempts that collided, including repeated attempts",
            txops="Recorded positive-limit opportunities only, zero-limit single exchanges excluded",
        ),
    )
    (args.output / "verification.json").write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {len(rows)} per-run AC rows and verification data to {args.output}")


if __name__ == "__main__":
    main()
