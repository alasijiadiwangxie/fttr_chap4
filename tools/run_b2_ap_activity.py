"""从实际 JAR 日志复核 B2 AP 活动状态和时长。"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv


DATA_NS = {"LCM": 165_600, "HCM": 77_600}
SIFS_NS = 16_000
ACK_NS = 28_000
STATES = ("IDLE", "TX", "RX")
TRANSITION_COLUMNS = (
    "scenario", "configuration", "event_index", "t_ns", "sfu_id", "ap_mode",
    "packet_id", "direction", "from", "to", "reason",
)
SUMMARY_COLUMNS = (
    "scenario", "configuration", "sfu_id", "ap_mode", "simulation_end_ns",
    "observation_start_ns", "observation_end_ns", "idle_ns", "tx_ns", "rx_ns",
    "manual_idle_ns", "manual_tx_ns", "manual_rx_ns", "matches_manual",
)
PROTOCOL_EVENTS = ("WIFI_TX_START", "WIFI_DATA_END", "WIFI_ACK_START", "WIFI_ACK_END")
ACTIVITY_REASONS = ("DATA_START", "DATA_END", "ACK_START", "ACK_END")


@dataclass(frozen=True)
class PacketFixture:
    sfu_id: int
    direction: str
    data_start_ns: int


@dataclass(frozen=True)
class Scenario:
    name: str
    modes: tuple[str, ...]
    packets: tuple[PacketFixture, ...]
    window_ns: int | None

    @property
    def configuration(self) -> Path:
        return Path(f"configs/b2-ap-activity-{self.name}.properties")


def scenarios() -> list[Scenario]:
    result = []
    for mode, data_ns in DATA_NS.items():
        # B1 的第一次退避为 0 槽，第二次退避为 2 槽。
        second_start = 34_000 + data_ns + SIFS_NS + ACK_NS + 52_000
        packets = (PacketFixture(1, "DL", 34_000), PacketFixture(1, "DL", second_start))
        result.append(Scenario(f"{mode.lower()}-natural", (mode,), packets, None))
        result.append(Scenario(f"{mode.lower()}-1000us", (mode,), packets, 1_000_000))
    result.append(Scenario("mixed", ("LCM", "HCM"),
                           (PacketFixture(1, "DL", 34_000), PacketFixture(2, "UL", 34_000)), 1_000_000))
    return result


def nanos(microseconds: str) -> int:
    ticks = Decimal(microseconds) * 1_000
    require(ticks == int(ticks), f"Timestamp is not a whole nanosecond: {microseconds} µs")
    return int(ticks)


def read_trace(jar: Path, configuration: Path) -> list[dict[str, str]]:
    return [dict(word.split("=", 1) for word in line.split() if "=" in word)
            for line in simulate(jar, configuration).splitlines() if line]


def compare_protocol(rows: list[dict[str, str]], scenario: Scenario) -> tuple[dict, int]:
    expected_transitions = {str(sfu): [] for sfu in range(1, len(scenario.modes) + 1)}
    final_ack_ns = 0
    delivered = [row.get("packet_id") for row in rows if row.get("event") == "DELIVERED"]
    require(sorted(delivered) == [str(i) for i in range(1, len(scenario.packets) + 1)],
            f"{scenario.name}: original packets must each be delivered once")
    for packet_id, packet in enumerate(scenario.packets, start=1):
        sfu_id = str(packet.sfu_id)
        mode = scenario.modes[packet.sfu_id - 1]
        data_end_ns = packet.data_start_ns + DATA_NS[mode]
        ack_start_ns = data_end_ns + SIFS_NS
        ack_end_ns = ack_start_ns + ACK_NS
        final_ack_ns = max(final_ack_ns, ack_end_ns)
        timeline = (packet.data_start_ns, data_end_ns, ack_start_ns, ack_end_ns)
        data_state, ack_state = ("TX", "RX") if packet.direction == "DL" else ("RX", "TX")
        state_pairs = (("IDLE", data_state), (data_state, "IDLE"), ("IDLE", ack_state), (ack_state, "IDLE"))
        for i, event in enumerate(PROTOCOL_EVENTS):
            matches = [row for row in rows if row.get("event") == event and row.get("packet_id") == str(packet_id)]
            require(len(matches) == 1, f"{scenario.name}: expected one {event} for packet {packet_id}")
            row = matches[0]
            time_ns = nanos(row["t_us"])
            require(time_ns == timeline[i], f"{scenario.name}: unexpected {event} time {time_ns}")
            require(row["sfu_id"] == sfu_id, f"{scenario.name}: wrong SFU for {event}")
            source, target = state_pairs[i]
            expected_transitions[sfu_id].append(
                (time_ns, source, target, ACTIVITY_REASONS[i], str(packet_id), mode, packet.direction))
    observation_end_ns = scenario.window_ns if scenario.window_ns is not None else final_ack_ns
    return expected_transitions, observation_end_ns


def read_activity(rows: list[dict[str, str]], scenario: Scenario, expected: dict, end_ns: int) -> tuple[list, dict]:
    traces = []
    active = {sfu_id: [] for sfu_id in expected}
    for row in rows:
        if row.get("event") != "WIFI_AP_ACTIVITY_CHANGE":
            continue
        sfu_id = row["sfu_id"]
        time_ns = int(row["t_ns"])
        require(time_ns == nanos(row["t_us"]), f"{scenario.name}: inconsistent timestamp {row}")
        require(sfu_id in active, f"{scenario.name}: unknown SFU {sfu_id}")
        active[sfu_id].append((time_ns, row["from"], row["to"], row["reason"], row["packet_id"],
                               row["ap_mode"], row["direction"]))
        traces.append(dict(scenario=scenario.name, configuration=scenario.configuration.as_posix(),
                           event_index=len(traces) + 1, **{key: row[key] for key in TRANSITION_COLUMNS[3:]}))
    require(active == expected, f"{scenario.name}: AP activity differs from the audited PHY/MAC sequence")

    by_sfu = {}
    for sfu_id, transitions in active.items():
        totals = dict.fromkeys(STATES, 0)
        previous_ns = 0
        state = "IDLE"
        for time_ns, source, target, *_ in transitions:
            require(time_ns >= previous_ns and source == state, f"{scenario.name}: discontinuous AP state trace")
            clipped_ns = min(time_ns, end_ns)
            totals[state] += clipped_ns - previous_ns
            previous_ns, state = clipped_ns, target
        totals[state] += end_ns - previous_ns
        by_sfu[sfu_id] = totals
    return traces, by_sfu


def verified_case(jar: Path, scenario: Scenario) -> tuple[list, list]:
    rows = read_trace(jar, ROOT / scenario.configuration)
    expected, end_ns = compare_protocol(rows, scenario)
    transitions, totals_by_sfu = read_activity(rows, scenario, expected, end_ns)
    simulation_end_ns = nanos(rows[-1]["simulation_end_us"])
    require(simulation_end_ns == max(event[0] for events in expected.values() for event in events),
            f"{scenario.name}: activity observation changed the simulation end")
    summaries = [row for row in rows if row.get("summary") == "WIFI_AP_ACTIVITY"]
    require(len(summaries) == len(scenario.modes), f"{scenario.name}: one activity summary required per SFU")
    results = []
    for sfu_id, mode in enumerate(scenario.modes, start=1):
        matches = [row for row in summaries if row["sfu_id"] == str(sfu_id)]
        require(len(matches) == 1, f"{scenario.name}: missing or duplicate summary for SFU {sfu_id}")
        row = matches[0]
        require(row["ap_mode"] == mode and int(row["observation_start_ns"]) == 0
                and int(row["observation_end_ns"]) == end_ns,
                f"{scenario.name}: unexpected activity mode or observation window")
        tx_ns = rx_ns = 0
        for packet in scenario.packets:
            if packet.sfu_id != sfu_id:
                continue
            tx, rx = (DATA_NS[mode], ACK_NS) if packet.direction == "DL" else (ACK_NS, DATA_NS[mode])
            tx_ns += tx
            rx_ns += rx
        manual = dict(IDLE=end_ns - tx_ns - rx_ns, TX=tx_ns, RX=rx_ns)
        actual = {state: int(row[f"{state.lower()}_ns"]) for state in STATES}
        require(sum(actual.values()) == end_ns and actual == totals_by_sfu[str(sfu_id)] == manual,
                f"{scenario.name} SFU {sfu_id}: expected {manual}, got {actual}")
        result = dict(scenario=scenario.name, configuration=scenario.configuration.as_posix(), sfu_id=sfu_id,
                      ap_mode=mode, simulation_end_ns=simulation_end_ns, observation_start_ns=0,
                      observation_end_ns=end_ns, idle_ns=actual["IDLE"], tx_ns=actual["TX"], rx_ns=actual["RX"],
                      manual_idle_ns=manual["IDLE"], manual_tx_ns=manual["TX"], manual_rx_ns=manual["RX"],
                      matches_manual="true")
        results.append(result)
        print(f"{scenario.name} SFU {sfu_id} {mode}: IDLE/TX/RX {actual['IDLE']}/{actual['TX']}/{actual['RX']} ns")
    return transitions, results


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/b2-ap-activity")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR first with mvn -o clean verify")

    traces = []
    summaries = []
    for scenario in scenarios():
        transitions, results = verified_case(args.jar.resolve(), scenario)
        traces.extend(transitions)
        summaries.extend(results)
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "activity-transitions.csv", TRANSITION_COLUMNS, traces)
    write_csv(args.output / "activity-summary.csv", SUMMARY_COLUMNS, summaries)
    print(f"Verified B2 activity written to {args.output.resolve()}")


if __name__ == "__main__":
    main()
