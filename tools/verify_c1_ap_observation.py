"""Replay Java C1 cases, verify observation invariants and export both CSV traces."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from decimal import Decimal
from itertools import groupby
from math import isclose
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv


RESULTS = ROOT / "results/c1-ap-observation"
ACS = ("VO", "VI", "BE", "BK")
OBSERVATION_EVENT = "WIFI_AP_OBSERVATION"
QUEUE_EVENT = "WIFI_AP_QUEUE_EVENT"
PPDU_EVENT = "WIFI_AP_PPDU_OBSERVED"
ENABLED_KEY = "wifi.ap.observation.enabled"
PERIOD_KEY = "wifi.ap.observation.period.us"
ALPHA_KEY = "wifi.ap.observation.ewma.alpha"


def experiment_properties(modes: tuple[str, ...], ampdu: bool,
                          packets: list[tuple[int, str, str, int]]) -> dict[str, str]:
    """Each packet explicitly specifies (generation in μs, AC, direction, STA)."""
    values = {
        "traffic.mode": "manual",
        "traffic.direction": "BOTH" if any(direction == "UL" for _, _, direction, _ in packets) else "DL",
        "sfu.count": str(len(modes)),
        "sta.count": str(len(modes)),
        "fiber.delay.us": "10",
        "packet.arrival.us": ",".join(str(when) for when, _, _, _ in packets),
        "packet.bytes": "1500",
        "wifi.model": "he-su",
        "wifi.mac.access": "edca-multi-ac",
        "wifi.mac.exchange": "data-sifs-ack",
        "wifi.mac.sifs.us": "16",
        "wifi.mac.ack.rate.mbps": "24",
        "wifi.mac.edca.seed": "101",
        "wifi.mac.edca.BE.aifsn": "3",
        "wifi.mac.ampdu.enabled": str(ampdu).lower(),
        "wifi.ap.activity.enabled": "true",
        "wifi.ap.activity.window.us": "50000",
        "wifi.ap.energy.enabled": "true",
        PERIOD_KEY: "1000",
        ALPHA_KEY: "0.25",
    }
    if ampdu:
        values["wifi.mac.ampdu.max-mpdus"] = "8"
    for sfu, mode in enumerate(modes, 1):
        values[f"sfu.{sfu}.wifi.ap.mode"] = mode
        values[f"sta.{sfu}.sfu.id"] = str(sfu)
    for packet, (_, ac, direction, sta) in enumerate(packets, 1):
        values[f"packet.{packet}.type"] = ac
        if values["traffic.direction"] == "BOTH":
            values[f"packet.{packet}.direction"] = direction
        if len(modes) > 1:
            values[f"packet.{packet}.sta.id"] = str(sta)
    return values


def scenarios() -> dict[str, dict[str, str]]:
    light = [(when, ACS[i % 4], "DL", 1)
             for i, when in enumerate((0, 800, 990, 1250, 1990, 2900, 3100, 3950))]
    # 120 packets at 20 μs spacing = 600 Mb/s of 1500 B payload. All ACs are continuously offered.
    saturated = [(20 * i, ACS[i % 4], "DL", 1) for i in range(120)]
    cases = {
        f"{mode.lower()}-{'ampdu' if ampdu else 'single'}-{load}":
            experiment_properties((mode,), ampdu, packets)
        for mode in ("LCM", "HCM") for ampdu in (False, True)
        for load, packets in (("light", light), ("saturated", saturated))
    }
    mixed = [
        (0, "BE", "DL", 1), (0, "BE", "DL", 1), (0, "BE", "DL", 1), (0, "BE", "DL", 1),
        (0, "VO", "UL", 2), (0, "VO", "UL", 2), (1000, "VI", "DL", 2),
        (1990, "BK", "DL", 1), (3000, "BK", "UL", 2),
    ]
    partial = experiment_properties(("HCM", "LCM"), True, mixed)
    partial.update({
        PERIOD_KEY: "100",
        "wifi.ap.activity.window.us": "4000",
        "wifi.mac.edca.BE.cwmin": "0",
        "wifi.mac.ampdu.max-mpdus": "4",
        "wifi.mac.ampdu.first-attempt-error.packet.ids": "2,4",
    })
    cases["mixed-partial-ba"] = partial
    cases["mixed-drop"] = dict(partial, **{"wifi.mac.retry.max": "0"})
    collided = experiment_properties(("HCM",), False,
                                     [(0, "BE", "DL", 1), (10, "BE", "UL", 1)])
    collided.update({PERIOD_KEY: "100", "wifi.mac.edca.BE.cwmin": "0", "wifi.mac.retry.max": "1"})
    cases["mixed-collision"] = collided
    return cases


def write_properties(path: Path, values: dict[str, str]) -> None:
    path.write_text("".join(f"{name}={value}\n" for name, value in values.items()), encoding="utf-8")


def trace_fields(line: str) -> dict[str, str]:
    return dict(field.split("=", 1) for field in line.split() if "=" in field)


def time_ns(row: dict[str, str]) -> int:
    if "t_ns" in row:
        return int(row["t_ns"])
    return int(Decimal(row["t_us"]) * 1000)


def csv_rows(path: Path, rows: list[dict[str, str]]) -> None:
    require(bool(rows), f"No trace rows for {path.name}")
    columns = tuple(dict.fromkeys(key for row in rows for key in row))
    write_csv(path, columns, rows)


def flow(row: dict[str, str]) -> tuple[str, str, str]:
    return row["sfu_id"], row.get("direction", "DL"), row.get("ac", row.get("type"))


def transition_key(row: dict[str, str], action: str, packet_bytes: int) -> tuple:
    return (time_ns(row), *flow(row), action, row["packet_id"], packet_bytes)


def check_protocol_events(name: str, protocol: list[dict[str, str]],
                          queue_events: list[dict[str, str]], ppdu_events: list[dict[str, str]]) -> None:
    packet_sizes = {row["packet_id"]: int(row["bytes"]) for row in protocol
                    if row.get("event") == "ARRIVAL" and row["node"] in ("MFU", "STA")}
    expected_queue = Counter()
    expected_pdus = Counter()
    for row in protocol:
        event = row.get("event")
        direction = row.get("direction", "DL")
        action = None
        if direction == "DL":
            if event == "SFU_ENQUEUE":
                action = "ENQUEUED"
            elif event == "WIFI_MAC_ATTEMPT":
                action = "TX_START" if int(row["retry_count"]) == 0 else "RETRANSMIT_START"
                expected_pdus[(time_ns(row), *flow(row))] += 1
            elif event == "DELIVERED":
                action = "DELIVERED"
            elif event == "WIFI_MAC_SUCCESS":
                action = "CONFIRMED"
            elif event == "WIFI_MAC_RETRY":
                action = "REQUEUED"
            elif event == "WIFI_RETRY_LIMIT_DROP":
                action = "DROPPED"
        elif event == "WIFI_DATA_END" or (event == "WIFI_AMPDU_MPDU" and row["status"] == "DELIVERED"):
            # A result is visible at this AP when a DATA MPDU reaches the receiver.
            action = "UL_RECEIVED"
            expected_pdus[(time_ns(row), *flow(row))] += 1
        if action is not None:
            expected_queue[transition_key(row, action, packet_sizes[row["packet_id"]])] += 1
    actual_queue = Counter(transition_key(row, row["action"], int(row["bytes"])) for row in queue_events)
    require(actual_queue == expected_queue, f"{name}: C1 and MAC packet events differ: "
            f"{list((actual_queue - expected_queue).items())[:2]} extra, "
            f"{list((expected_queue - actual_queue).items())[:2]} missing")
    actual_pdus = {(time_ns(row), *flow(row)): int(row["depth"]) for row in ppdu_events}
    require(len(actual_pdus) == len(ppdu_events) and actual_pdus == expected_pdus,
            f"{name}: observed PPDU depth/timestamp does not match protocol DATA")


class WindowLedger:
    """Replay the observed identity/byte transitions without the production observer."""

    def __init__(self) -> None:
        self.pending = {}
        self.inflight = {}
        self.awaiting_confirmation = set()
        self.totals = Counter()
        self.delivered = set()
        self.finished = set()
        self.ewma_pps = self.ewma_mbps = None

    def apply(self, name: str, row: dict[str, str]) -> None:
        packet = row["packet_id"]
        byte_count = int(row["bytes"])
        action = row["action"]
        if action in ("ENQUEUED", "UL_RECEIVED"):
            require(packet not in self.pending and packet not in self.inflight and packet not in self.finished,
                    f"{name}: admitted packet {packet} twice")
            self.totals["arrival_packets"] += 1
            self.totals["arrival_bytes"] += byte_count
        if action == "ENQUEUED":
            self.pending[packet] = (byte_count, time_ns(row), int(row["trace_sequence"]))
        elif action in ("TX_START", "RETRANSMIT_START"):
            oldest = min(self.pending, key=lambda key: self.pending[key][2])
            require(packet == oldest, f"{name}: started {packet} ahead of queue head {oldest}")
            self.inflight[packet] = self.pending.pop(packet)
            if action == "RETRANSMIT_START":
                self.totals["retry_attempt_mpdus"] += 1
                self.totals["retry_attempt_bytes"] += byte_count
        elif action in ("DELIVERED", "UL_RECEIVED"):
            require(packet not in self.delivered, f"{name}: duplicate delivery of packet {packet}")
            self.delivered.add(packet)
            self.totals["delivered_packets"] += 1
            self.totals["delivered_bytes"] += byte_count
            if action == "DELIVERED":
                require(packet in self.inflight, f"{name}: delivered packet {packet} before it started DATA")
                self.awaiting_confirmation.add(packet)
            else:
                self.finished.add(packet)
        elif action == "CONFIRMED":
            self.awaiting_confirmation.remove(packet)
            del self.inflight[packet]
            self.finished.add(packet)
            self.totals["confirmed_packets"] += 1
            self.totals["confirmed_bytes"] += byte_count
        elif action == "REQUEUED":
            self.pending[packet] = self.inflight.pop(packet)
            self.totals["requeued_packets"] += 1
        elif action == "DROPPED":
            del self.inflight[packet]
            self.finished.add(packet)
            self.totals["dropped_packets"] += 1
            self.totals["dropped_bytes"] += byte_count
        else:
            raise ValueError(f"{name}: unrecognized C1 packet action {action}")
        self.check_queue(name, row)

    def check_queue(self, name: str, row: dict[str, str]) -> None:
        sizes = {
            "pending_packets": len(self.pending),
            "pending_bytes": sum(size for size, _, _ in self.pending.values()),
            "inflight_packets": len(self.inflight),
            "inflight_bytes": sum(size for size, _, _ in self.inflight.values()),
            "awaiting_confirm_packets": len(self.awaiting_confirmation),
        }
        for field, expected in sizes.items():
            require(int(row[field]) == expected, f"{name}: {field} expected {expected} ({row})")

    def check_sample(self, name: str, row: dict[str, str], alpha: float) -> None:
        self.check_queue(name, row)
        end_ns = int(row["window_end_ns"])
        duration_ns = end_ns - int(row["window_start_ns"])
        age = end_ns - min(enqueued for _, enqueued, _ in self.pending.values()) if self.pending else -1
        require(int(row["head_wait_ns"]) == age, f"{name}: wrong AP-local head waiting time ({row})")
        for field in ("arrival_packets", "arrival_bytes", "delivered_packets", "delivered_bytes",
                      "confirmed_packets", "confirmed_bytes", "retry_attempt_mpdus", "retry_attempt_bytes",
                      "requeued_packets", "dropped_packets", "dropped_bytes", "ppdus", "mpdus"):
            require(int(row[field]) == self.totals[field], f"{name}: {field} expected {self.totals[field]} ({row})")
        pps = 1e9 / duration_ns if duration_ns else 0
        byte_to_mbps = 8000.0 / duration_ns if duration_ns else 0
        arrival_pps = self.totals["arrival_packets"] * pps
        arrival_mbps = self.totals["arrival_bytes"] * byte_to_mbps
        settled = self.totals["confirmed_packets"] + self.totals["dropped_packets"]
        self.ewma_pps = arrival_pps if self.ewma_pps is None else alpha * arrival_pps + (1 - alpha) * self.ewma_pps
        self.ewma_mbps = arrival_mbps if self.ewma_mbps is None else alpha * arrival_mbps + (1 - alpha) * self.ewma_mbps
        rates = {
            "arrival_pps": arrival_pps,
            "arrival_mbps": arrival_mbps,
            "delivery_pps": self.totals["delivered_packets"] * pps,
            "delivery_mbps": self.totals["delivered_bytes"] * byte_to_mbps,
            "ewma_arrival_pps": self.ewma_pps,
            "ewma_arrival_mbps": self.ewma_mbps,
            "avg_depth": self.totals["mpdus"] / self.totals["ppdus"] if self.totals["ppdus"] else 0,
            "delivery_success_ratio": (self.totals["confirmed_packets"] / settled
                                       if row["direction"] == "DL" and settled else -1),
        }
        for field, expected in rates.items():
            require(isclose(float(row[field]), expected, rel_tol=1e-10, abs_tol=1e-9),
                    f"{name}: {field} expected {expected} ({row})")
        self.totals.clear()


def activity_intervals(protocol: list[dict[str, str]], end_ns: int) -> dict[str, list[tuple[int, int, str]]]:
    intervals = defaultdict(list)
    starts = defaultdict(int)
    states = defaultdict(lambda: "IDLE")
    for row in protocol:
        if row.get("event") != "WIFI_AP_ACTIVITY_CHANGE":
            continue
        sfu = row["sfu_id"]
        now = time_ns(row)
        intervals[sfu].append((starts[sfu], now, states[sfu]))
        starts[sfu] = now
        states[sfu] = row["to"]
    for sfu in starts:
        intervals[sfu].append((starts[sfu], end_ns, states[sfu]))
    return intervals


def overlaps(intervals: list[tuple[int, int, str]], from_ns: int, to_ns: int) -> Counter:
    result = Counter()
    for begin, end, state in intervals:
        result[state] += max(0, min(to_ns, end) - max(from_ns, begin))
    return result


def check_windows(name: str, props: dict[str, str], protocol: list[dict[str, str]],
                  observation_lines: list[dict[str, str]], queue_lines: list[dict[str, str]],
                  ppdu_lines: list[dict[str, str]]) -> None:
    samples_end = max(int(row["window_end_ns"]) for row in observation_lines)
    simulation_end = next(int(Decimal(row["simulation_end_us"]) * 1000) for row in protocol if "simulation_end_us" in row)
    require(samples_end == simulation_end, f"{name}: samples changed the natural end or did not flush the tail")
    intervals = activity_intervals(protocol, simulation_end)
    alpha = float(props[ALPHA_KEY])
    period_ns = int(Decimal(props[PERIOD_KEY]) * 1000)
    sfus = tuple(str(index) for index in range(1, int(props["sfu.count"]) + 1))
    expected_flows = {(sfu, direction, category) for sfu in sfus for direction in ("DL", "UL") for category in ACS}
    states = defaultdict(WindowLedger)
    previous_sample_ns = 0
    last_sfu = ""
    observed_flows = set()
    ordered = sorted(observation_lines + queue_lines + ppdu_lines, key=lambda row: int(row["trace_sequence"]))
    for end, rows in groupby(ordered, key=time_ns):
        for row in rows:
            state = states[flow(row)]
            event = row["event"]
            if event == QUEUE_EVENT:
                require(not observed_flows, f"{name}: queue change at {end} was processed after the sample")
                state.apply(name, row)
            elif event == PPDU_EVENT:
                require(not observed_flows, f"{name}: PPDU at {end} was processed after the sample")
                state.totals["ppdus"] += 1
                state.totals["mpdus"] += int(row["depth"])
            else:
                if not observed_flows:
                    require(0 < end - previous_sample_ns <= period_ns and
                            (end % period_ns == 0 or end == simulation_end),
                            f"{name}: bad sample period at {end} ns")
                require(int(row["window_start_ns"]) == previous_sample_ns and int(row["window_end_ns"]) == end,
                        f"{name}: inconsistent window boundary ({row})")
                if row["sfu_id"] != last_sfu:
                    last_sfu = row["sfu_id"]
                    activity = overlaps(intervals[last_sfu], previous_sample_ns, end)
                require(int(row["tx_ns"]) == activity["TX"] and int(row["rx_ns"]) == activity["RX"],
                        f"{name}: window TX/RX was not the actual AP state duration ({row})")
                state.check_sample(name, row, alpha)
                require(flow(row) not in observed_flows, f"{name}: duplicate sample row ({row})")
                observed_flows.add(flow(row))
        if observed_flows:
            require(observed_flows == expected_flows, f"{name}: AP/direction/AC sample is incomplete")
            observed_flows.clear()
            last_sfu = ""
            previous_sample_ns = end
    require(all(not state.pending and not state.inflight and not state.totals for state in states.values()),
            f"{name}: natural end left unaccounted AP-local state")


def check_case(name: str, protocol: list[dict[str, str]], observations: list[dict[str, str]],
               queue_events: list[dict[str, str]], ppdu_events: list[dict[str, str]]) -> None:
    depths = [int(row["depth"]) for row in ppdu_events]
    if name.endswith("saturated"):
        require(max(int(row["pending_packets"]) for row in observations) > 4,
                f"{name}: did not sustain an AP backlog")
        require((max(depths) > 1) == ("-ampdu-" in name), f"{name}: saturation aggregation did not match config")
    if name.endswith("light"):
        require(any(row["action"] == "ENQUEUED" and time_ns(row) == 1_000_000 for row in queue_events),
                f"{name}: missing exact-window-boundary AP arrival")
    if name in ("mixed-partial-ba", "mixed-drop"):
        expected = "RETRANSMIT_START" if name == "mixed-partial-ba" else "DROPPED"
        affected = [(row["sfu_id"], row["packet_id"]) for row in queue_events if row["action"] == expected]
        require(affected == [("1", "2"), ("1", "4")],
                f"{name}: partial Block ACK did not isolate the two configured failed members")
        require(any(row["action"] == "UL_RECEIVED" and row["sfu_id"] == "2" for row in queue_events)
                and all(row["action"] == "UL_RECEIVED" for row in queue_events if row["direction"] == "UL"),
                f"{name}: UL events exposed remote queue or were recorded on the wrong AP")
    if name == "mixed-collision":
        require(any(row.get("event") == "WIFI_COLLISION_END" for row in protocol),
                f"{name}: competing DL and UL did not actually collide")


def verify(jar: Path, output: Path, chosen: list[str]) -> None:
    cases = scenarios()
    configs = output / "configs"
    configs.mkdir(parents=True, exist_ok=True)
    observations = []
    queue_events = []
    for name in chosen or cases:
        props = cases[name]
        traces = {}
        for enabled in (False, True):
            config = configs / f"{name}{'' if enabled else '-observation-off'}.properties"
            # The disabled control is the same input, protocol, seed and activity window.
            actual_props = dict(props, **{ENABLED_KEY: str(enabled).lower()})
            if not enabled:
                del actual_props[PERIOD_KEY], actual_props[ALPHA_KEY]
            write_properties(config, actual_props)
            traces[enabled] = simulate(jar, config).splitlines()
        observation_lines = []
        queue_lines = []
        ppdu_lines = []
        protocol_lines = []
        for sequence, line in enumerate(traces[True], 1):
            row = trace_fields(line)
            if row.get("event") == OBSERVATION_EVENT:
                observation_lines.append(dict(scenario=name, trace_sequence=str(sequence), **row))
            elif row.get("event") == QUEUE_EVENT:
                queue_lines.append(dict(scenario=name, trace_sequence=str(sequence), **row))
            elif row.get("event") == PPDU_EVENT:
                ppdu_lines.append(dict(scenario=name, trace_sequence=str(sequence), **row))
            else:
                protocol_lines.append(line)
        require(protocol_lines == traces[False], f"{name}: C1 changed the original trace")
        require(bool(observation_lines) and bool(queue_lines), f"{name}: missing C1 rows")
        protocol_rows = [trace_fields(line) for line in protocol_lines]
        check_protocol_events(name, protocol_rows, queue_lines, ppdu_lines)
        check_windows(name, props, protocol_rows, observation_lines, queue_lines, ppdu_lines)
        check_case(name, protocol_rows, observation_lines, queue_lines, ppdu_lines)
        observations.extend(observation_lines)
        queue_events.extend(sorted(queue_lines + ppdu_lines, key=lambda row: int(row["trace_sequence"])))
        print(f"{name}: {len(observation_lines)} observations, {len(queue_lines)} queue events, "
              f"{len(protocol_lines)} identical protocol/summary lines")
    csv_rows(output / "c1-ap-observations.csv", observations)
    csv_rows(output / "c1-queue-events.csv", queue_events)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=RESULTS)
    parser.add_argument("--scenario", choices=tuple(scenarios()), action="append", default=[])
    args = parser.parse_args()
    verify(args.jar.resolve(), args.output.resolve(), args.scenario)
