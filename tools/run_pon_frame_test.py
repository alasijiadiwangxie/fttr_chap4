"""运行 PON 上行帧专项用例，校核实际仿真轨迹并导出 CSV。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
PAYLOAD_BYTES = 1200
RATE_MBPS = 2500
FRAME_PERIOD_US = 125
PROPAGATION_US = Decimal("10.025")
ARRIVALS_US = [0] * 33 + [250, 260]
EVENT_COLUMNS = (
    "frame_id", "packet_id", "sfu_id", "enqueue_time_ns", "grant_time_ns",
    "tx_start_ns", "tx_end_ns", "mfu_arrival_ns",
)
FRAME_COLUMNS = (
    "frame_id", "frame_start_ns", "frame_end_ns", "allocated_packets",
    "busy_ns", "idle_ns", "utilization",
)


def scenario() -> str:
    settings = [
        "# Deterministic manual arrivals. No random seed is used.",
        "# Packets 1-33 enter at 0 us. Packets 34 and 35 enter at 250 and 260 us.",
        "traffic.mode=manual",
        "traffic.direction=UL",
        "sfu.count=2",
        "sta.count=2",
        "sta.1.sfu.id=1",
        "sta.2.sfu.id=2",
        "# Zero Wi-Fi delay isolates optical timing while preserving the STA to SFU path.",
        "wifi.model=fixed",
        "wifi.delay.us=0",
        "wifi.mac.access=fixed-priority",
        "fiber.uplink.mode=pon-framed",
        f"fiber.uplink.rate.mbps={RATE_MBPS}",
        f"fiber.uplink.propagation.us={PROPAGATION_US}",
        f"fiber.uplink.frame.period.us={FRAME_PERIOD_US}",
        f"packet.bytes={PAYLOAD_BYTES}",
        "packet.arrival.us=" + ",".join(map(str, ARRIVALS_US)),
    ]
    # 奇数编号经 SFU 2，偶数编号经 SFU 1。首个请求可检验同刻仲裁不依赖 SFU ID 排序。
    settings.extend(f"packet.{packet_id}.sta.id=2" for packet_id in range(1, len(ARRIVALS_US) + 1, 2))
    return "\n".join(settings) + "\n"


def simulate(jar: Path, configuration: Path) -> str:
    result = subprocess.run(
        ["java", "-jar", str(jar), str(configuration)], capture_output=True, encoding="utf-8", check=False
    )
    if result.returncode != 0:
        raise RuntimeError(f"Simulation exited with code {result.returncode}: {result.stderr.strip()}")
    return result.stdout


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def ns(time_us: str | Decimal) -> int:
    ticks = Decimal(time_us) * 1000
    require(ticks == int(ticks), f"Timestamp is outside the integer nanosecond grid: {time_us} us")
    return int(ticks)


def packet_events(trace: list[dict[str, str]], action: str, node: str) -> dict[int, dict[str, str]]:
    events = {}
    for row in trace:
        if row.get("event") == action and row.get("node") == node:
            packet_id = int(row["packet_id"])
            require(packet_id not in events, f"Duplicate {node} {action} for packet {packet_id}")
            events[packet_id] = row
    return events


def check_ratio(actual: str, numerator: int, denominator: int, label: str) -> None:
    expected = Decimal(numerator) / denominator
    require(abs(Decimal(actual) - expected) <= Decimal("0.0000000005"), f"Incorrect utilization for {label}")


def check_trace(log: str) -> tuple[list[dict], list[dict]]:
    trace = [dict(token.split("=", 1) for token in line.split()) for line in log.splitlines() if line.strip()]
    enqueued = packet_events(trace, "PON_UPLINK_ENQUEUE", "SFU")
    grants = packet_events(trace, "PON_UPLINK_GRANT", "MFU")
    starts = packet_events(trace, "FIBER_TX_START", "SFU")
    ends = packet_events(trace, "FIBER_TX_END", "SFU")
    delivered = packet_events(trace, "DELIVERED", "MFU")
    packet_ids = list(range(1, len(ARRIVALS_US) + 1))
    require(list(enqueued) == packet_ids, "Optical enqueue order does not match the deterministic fixture")
    require(all(list(events) == packet_ids for events in (grants, starts, ends, delivered)),
            "Missing packets or FCFS order changed between enqueue, grant, transmission and MFU delivery")

    period_ns = FRAME_PERIOD_US * 1000
    propagation_ns = ns(PROPAGATION_US)
    serialization_ns = (PAYLOAD_BYTES * 8000 + RATE_MBPS - 1) // RATE_MBPS
    event_rows = []
    previous_tx_end = previous_rx_end = 0
    for packet_id in packet_ids:
        enqueue, grant, start, end, arrival = (
            events[packet_id] for events in (enqueued, grants, starts, ends, delivered)
        )
        enqueue_ns, start_ns, end_ns, arrival_ns = (ns(row["t_us"]) for row in (enqueue, start, end, arrival))
        frame_id = int(grant["frame_id"])
        grant_ns = int(grant["grant_time_ns"])
        expected_sfu = 2 if packet_id % 2 else 1
        require(all(int(row["sfu_id"]) == expected_sfu for row in (enqueue, grant, start, end, arrival)),
                f"SFU route changed for packet {packet_id}")
        require(all(row["direction"] == "UL" and int(row["source_sta_id"]) == expected_sfu
                    and row["destination_mfu_id"] == "1" and int(row["bytes"]) == PAYLOAD_BYTES
                    for row in (enqueue, start, end, arrival)), f"Packet metadata changed for packet {packet_id}")
        require(enqueue_ns == ARRIVALS_US[packet_id - 1] * 1000 == int(grant["enqueue_time_ns"]),
                f"Incorrect optical enqueue time for packet {packet_id}")
        require(enqueue_ns < grant_ns == frame_id * period_ns == ns(grant["t_us"]),
                f"Ineligible or off-boundary grant for packet {packet_id}")
        require(grant_ns <= start_ns < end_ns <= grant_ns + period_ns,
                f"Slot is outside its grant frame for packet {packet_id}")
        require(start_ns == int(grant["tx_start_ns"]) and end_ns == int(grant["tx_end_ns"]),
                f"Transmission does not match its grant for packet {packet_id}")
        require(end_ns - start_ns == serialization_ns and start_ns >= previous_tx_end,
                f"Incorrect serialization or overlapping optical transmission for packet {packet_id}")
        require(arrival_ns == end_ns + propagation_ns and start_ns + propagation_ns >= previous_rx_end,
                f"Incorrect propagation or overlapping reception at MFU for packet {packet_id}")
        require(ns(arrival["e2e_delay_us"]) == arrival_ns - enqueue_ns, f"Incorrect E2E delay for packet {packet_id}")
        event_rows.append(dict(frame_id=frame_id, packet_id=packet_id, sfu_id=expected_sfu,
                               enqueue_time_ns=enqueue_ns, grant_time_ns=grant_ns, tx_start_ns=start_ns,
                               tx_end_ns=end_ns, mfu_arrival_ns=arrival_ns))
        previous_tx_end, previous_rx_end = end_ns, arrival_ns

    frame_rows = []
    expected_counts = (0, 32, 1, 2)
    frames = [row for row in trace if row.get("summary") == "PON_UPLINK_FRAME"]
    require(len(frames) == len(expected_counts), "Incorrect number of frame rows")
    require(event_rows[32]["frame_id"] == 2, "The 33rd packet must carry over to frame 2")
    require(all(row["frame_id"] == 3 for row in event_rows[33:]),
            "Packets arriving at 250 us and 260 us must wait for frame 3")
    for frame_id, (frame, expected_count) in enumerate(zip(frames, expected_counts)):
        actual = {key: int(frame[key]) for key in FRAME_COLUMNS[:-1]}
        slots = [row for row in event_rows if row["frame_id"] == frame_id]
        expected_start = frame_id * period_ns
        expected_busy = expected_count * serialization_ns
        expected = dict(frame_id=frame_id, frame_start_ns=expected_start, frame_end_ns=expected_start + period_ns,
                        allocated_packets=expected_count, busy_ns=expected_busy, idle_ns=period_ns - expected_busy)
        require(len(slots) == expected_count and actual == expected, f"Frame {frame_id} summary does not match slots")
        for slot_id, slot in enumerate(slots):
            require(slot["tx_start_ns"] == expected_start + slot_id * serialization_ns,
                    f"Unexpected gap between FCFS slots in frame {frame_id}")
        check_ratio(frame["utilization"], expected_busy, period_ns, f"frame {frame_id}")
        frame_rows.append(dict(actual, utilization=frame["utilization"]))

    totals = [row for row in trace if row.get("summary") == "PON_UPLINK_FRAMES"]
    require(len(totals) == 1, "Expected one framed uplink summary")
    total = totals[0]
    window_ns = len(frame_rows) * period_ns
    busy_ns = sum(row["busy_ns"] for row in frame_rows)
    counts = dict(frames=len(frame_rows), frame_period_ns=period_ns, window_ns=window_ns,
                  allocated_packets=len(event_rows), busy_ns=busy_ns, idle_ns=window_ns - busy_ns)
    require(all(int(total[key]) == value for key, value in counts.items()), "Inconsistent framed uplink totals")
    check_ratio(total["utilization"], busy_ns, window_ns, "all observed frames")
    wait_ns = sum(row["grant_time_ns"] - row["enqueue_time_ns"] for row in event_rows)
    mean_wait_us = Decimal(wait_ns) / (1000 * len(event_rows))
    require(abs(Decimal(total["avg_grant_wait_us"]) - mean_wait_us) <= Decimal("0.0000005"),
            "Frame grant waiting time does not match packet trace")
    return event_rows, frame_rows


def write_csv(path: Path, columns: tuple[str, ...], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-frame")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR with mvn -o clean package")
    args.output.mkdir(parents=True, exist_ok=True)
    config = args.output / "test-config.properties"
    config.write_bytes(scenario().encode("utf-8"))
    jar = args.jar.resolve()
    log = simulate(jar, config.resolve())
    require(log == simulate(jar, config.resolve()), "Identical inputs produced different full event traces")
    packets, frames = check_trace(log)
    write_csv(args.output / "event-trace.csv", EVENT_COLUMNS, packets)
    write_csv(args.output / "frame-summary.csv", FRAME_COLUMNS, frames)
    print(f"Verified {len(packets)} packets and {len(frames)} frames; generated CSV under {args.output}")


if __name__ == "__main__":
    main()
