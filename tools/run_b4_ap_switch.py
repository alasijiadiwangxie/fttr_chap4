"""重放 B4 场景，逐事件核对 RF 安全边界、模式时长和能耗。"""

from __future__ import annotations

import argparse
from collections import defaultdict
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory

from pon_experiment_io import ROOT, require, simulate, write_csv
from run_b3_ap_energy import DEFAULT_POWERS, MODEL, fields


STATES = ("idle", "tx", "rx", "switch")
FRAME_EVENTS = ("WIFI_TX_START", "WIFI_DATA_END", "WIFI_ACK_START", "WIFI_ACK_END")
SWITCH_EVENTS = ("WIFI_AP_SWITCH_REQUEST", "WIFI_AP_SWITCH_START", "WIFI_AP_SWITCH_END")
EVENT_COLUMNS = (
    "scenario", "configuration", "event_index", "t_ns", "sfu_id", "event", "switch_id",
    "packet_id", "direction", "from_mode", "to_mode", "ap_mode", "from", "to", "reason",
    "duration_ns", "power_w", "observed_ns", "switch_energy_uj",
)
SUMMARY_COLUMNS = (
    "scenario", "configuration", "scope", "sfu_id", "mode_or_direction", "model", "observation_end_ns",
    *(f"{state}_ns" for state in STATES),
    *(f"{state}_energy_uj" for state in STATES),
    "total_energy_uj", "matches_trace_and_manual",
)

# (报文, SFU, 模式, DATA 开始纳秒) 及 (SFU, 请求纳秒, 开始纳秒, 完成纳秒, 旧模式, 新模式)。
ROUND_TRIP_FRAMES = ((1, 1, "LCM", 34_000), (2, 1, "HCM", 317_725), (3, 1, "LCM", 674_000))
ROUND_TRIP_SWITCHES = (
    (1, 100_000, 243_600, 283_725, "LCM", "HCM"),
    (1, 600_000, 600_000, 625_250, "HCM", "LCM"),
)
CASES = {
    "round-trip": (883_600, ROUND_TRIP_FRAMES, ROUND_TRIP_SWITCHES, {
        1: {"LCM": (114_750, 331_200, 56_000), "HCM": (210_675, 77_600, 28_000)},
    }),
    "clipped": (615_125, ROUND_TRIP_FRAMES, ROUND_TRIP_SWITCHES, {
        1: {"LCM": (50_000, 165_600, 28_000), "HCM": (210_675, 77_600, 28_000)},
    }),
    "multi-ap": (800_000, ((1, 1, "HCM", 74_125), (2, 2, "HCM", 34_000), (3, 2, "LCM", 244_000)),
                 ((1, 0, 0, 40_125, "LCM", "HCM"), (2, 50_000, 155_600, 180_850, "HCM", "LCM")), {
        1: {"LCM": (0, 0, 0), "HCM": (654_275, 77_600, 28_000)},
        2: {"LCM": (425_550, 28_000, 165_600), "HCM": (50_000, 28_000, 77_600)},
    }),
    "zero-switch": (1_000_000, ((1, 1, "LCM", 34_000), (2, 1, "LCM", 277_600)), (), {
        1: {"LCM": (612_800, 331_200, 56_000), "HCM": (0, 0, 0)},
    }),
}


def time_ns(row: dict[str, str]) -> int:
    scaled = Decimal(row["t_us"]) * 1000
    require(scaled == scaled.to_integral_value(), f"Event time is not a whole nanosecond: {row}")
    if "t_ns" in row:
        require(int(row["t_ns"]) == scaled, f"Event clock units disagree: {row}")
    return int(scaled)


def by_summary(rows: list[dict], summary: str, **filters: str) -> dict:
    matches = [row for row in rows if row.get("summary") == summary
               and all(row.get(key) == value for key, value in filters.items())]
    require(len(matches) == 1, f"Expected a single {summary} for {filters}, got {len(matches)}")
    return matches[0]


def watts_times_ns(watts: Decimal, duration_ns: int) -> Decimal:
    return watts * Decimal(duration_ns).scaleb(-3)


def audit_switches(rows: list[dict], settings: dict[str, str], end_ns: int, expected: tuple) -> list[dict]:
    groups = defaultdict(dict)
    event_index = defaultdict(dict)
    for index, row in enumerate(rows):
        action = row.get("event")
        if action not in (*SWITCH_EVENTS, "WIFI_AP_SWITCH_SKIPPED"):
            continue
        key = (row["sfu_id"], row["switch_id"])
        require(action not in groups[key], f"Duplicate switch event: {row}")
        groups[key][action] = row
        event_index[key][action] = index
    require(len(groups) == len(expected), "Unexpected number of switch requests")

    switches = []
    for key, switch in groups.items():
        require(set(switch) == set(SWITCH_EVENTS), f"Incomplete switch lifecycle: {switch}")
        requested, started, finished = (switch[action] for action in SWITCH_EVENTS)
        require(list(event_index[key]) == list(SWITCH_EVENTS), f"Switch lifecycle events out of order: {switch}")
        request_ns, start_ns, finish_ns = map(time_ns, (requested, started, finished))
        source, target = started["from_mode"], started["to_mode"]
        direction = f"{source.lower()}-to-{target.lower()}"
        power = Decimal(settings[f"wifi.ap.switch.{direction}.power.w"])
        delay = int(Decimal(settings[f"wifi.ap.switch.{direction}.delay.us"]) * 1000)
        observed_ns = max(0, min(finish_ns, end_ns) - start_ns)
        require(request_ns <= start_ns and finish_ns - start_ns == delay, f"Unexpected switch time: {switch}")
        for row in (started, finished):
            require((row["from_mode"], row["to_mode"]) == (source, target)
                    and int(row["duration_ns"]) == delay and Decimal(row["power_w"]) == power,
                    f"Switch parameters differ from configuration: {row}")
        require(int(finished["observed_ns"]) == observed_ns
                and Decimal(finished["switch_energy_uj"]) == watts_times_ns(power, observed_ns),
                f"Switch clipping or energy differs from manual calculation: {finished}")
        switches.append(dict(sfu=int(key[0]), request_ns=request_ns, start_ns=start_ns, finish_ns=finish_ns,
                             source=source, target=target, power=power, observed_ns=observed_ns))

    actual = [(switch["sfu"], switch["request_ns"], switch["start_ns"], switch["finish_ns"],
               switch["source"], switch["target"]) for switch in switches]
    require(actual == list(expected), f"Switch events differ from the manual schedule: {actual}")
    return switches


def audit_frames(rows: list[dict], switches: list[dict], expected: tuple) -> None:
    delivered = [row["packet_id"] for row in rows if row.get("event") == "DELIVERED"]
    require(sorted(delivered) == sorted(str(packet[0]) for packet in expected), "Packets missing or duplicated")
    for packet, sfu, mode, start_ns in expected:
        actual = []
        for action in FRAME_EVENTS:
            matches = [row for row in rows if row.get("event") == action and row.get("packet_id") == str(packet)]
            require(len(matches) == 1 and matches[0].get("sfu_id") == str(sfu),
                    f"Invalid {action} for AP {sfu}, packet {packet}")
            actual.append(time_ns(matches[0]))
        data_end_ns = start_ns + {"LCM": 165_600, "HCM": 77_600}[mode]
        ack_start_ns = data_end_ns + 16_000
        ack_end_ns = ack_start_ns + 28_000
        require(actual == [start_ns, data_end_ns, ack_start_ns, ack_end_ns],
                f"Packet {packet}: unexpected hand-calculated {mode} PPDU or ACK timing: {actual}")
        for switch in switches:
            if switch["sfu"] == sfu:
                require(ack_end_ns <= switch["start_ns"] or start_ns >= switch["finish_ns"],
                        f"AP {sfu}, packet {packet}: exchange overlaps the switch")


def trace_durations(rows: list[dict], sfu: int, mode: str, end_ns: int, switches: list[dict]) -> dict:
    switch_by_time = {switch["start_ns"]: switch for switch in switches}
    totals = {"LCM": [0, 0, 0, 0], "HCM": [0, 0, 0, 0]}
    rows = [row for row in rows if row.get("event") == "WIFI_AP_ACTIVITY_CHANGE" and row["sfu_id"] == str(sfu)]
    rows.append(dict(t_us=str(Decimal(end_ns).scaleb(-3)), reason="WINDOW_END"))
    previous_ns = 0
    state = "idle"
    during = None
    for row in rows:
        now_ns = time_ns(row)
        require(previous_ns <= now_ns <= end_ns, f"Activity timeline outside its observation window: {row}")
        if state == "switch":
            key = f"{during['source']}-to-{during['target']}"
            totals.setdefault(key, [0, 0, 0, 0])[3] += now_ns - previous_ns
        else:
            totals[mode][STATES.index(state)] += now_ns - previous_ns
        if row["reason"] == "WINDOW_END":
            break
        require(row["from"].lower() == state, f"Discontinuous B2 activity trace: {row}")
        if row["reason"] == "SWITCH_START":
            during = switch_by_time[now_ns]
            require(row["ap_mode"] == mode == during["source"], f"Wrong source mode: {row}")
        elif row["reason"] == "SWITCH_END":
            require(during is not None and row["ap_mode"] == during["target"] and now_ns == during["finish_ns"],
                    f"Wrong completed mode: {row}")
            mode = during["target"]
            during = None
        else:
            require(row["ap_mode"] == mode, f"Frame was attributed to the wrong mode: {row}")
        state = row["to"].lower()
        previous_ns = now_ns
    return totals


def verify_totals(row: dict, energies: list[Decimal], end_ns: int) -> None:
    total = sum(energies)
    require(row["model"] == MODEL and int(row["observation_end_ns"]) == end_ns,
            f"Invalid energy model or window: {row}")
    require([Decimal(row[f"{state}_energy_uj"]) for state in STATES] == energies
            and Decimal(row["total_energy_uj"]) == total,
            f"State energies do not reconcile with the total: {row}")
    require(abs(Decimal(row["avg_power_w"]) - total / Decimal(end_ns).scaleb(-3)) < Decimal("1e-12"),
            f"Average power mismatch: {row}")


def output_summary(identity: dict, scope: str, end_ns: int, mode: str, durations: list[int],
                   energies: list[Decimal]) -> dict:
    return dict(**identity, scope=scope, mode_or_direction=mode, model=MODEL, observation_end_ns=end_ns,
                **{f"{state}_ns": value for state, value in zip(STATES, durations)},
                **{f"{state}_energy_uj": value for state, value in zip(STATES, energies)},
                total_energy_uj=sum(energies), matches_trace_and_manual="true")


def audit_ap(rows: list[dict], identity: dict, initial_mode: str, end_ns: int,
             switches: list[dict], expected: dict) -> tuple[list[dict], list[int], list[Decimal]]:
    sfu = identity["sfu_id"]
    timelines = trace_durations(rows, sfu, initial_mode, end_ns, switches)
    output = []
    energy_by_state = [Decimal(0)] * len(STATES)
    time_by_state = [0] * len(STATES)
    for mode, power in DEFAULT_POWERS.items():
        durations = timelines[mode]
        require(durations == [*expected[mode], 0], f"AP {sfu}: {mode} activity differs from hand calculation")
        energy = [watts_times_ns(watts, ticks) for watts, ticks in zip(power, durations)] + [Decimal(0)]
        mode_row = by_summary(rows, "WIFI_AP_ENERGY_MODE", sfu_id=str(sfu), ap_mode=mode)
        require([Decimal(mode_row[f"{state}_power_w"]) for state in STATES[:3]] == list(power)
                and [int(mode_row[f"{state}_ns"]) for state in STATES[:3]] == durations[:3]
                and [Decimal(mode_row[f"{state}_energy_uj"]) for state in STATES[:3]] == energy[:3],
                f"AP {sfu}: Java mode summary differs from reconstructed intervals: {mode_row}")
        output.append(output_summary(identity, "MODE", end_ns, mode, durations, energy))
        time_by_state = [left + right for left, right in zip(time_by_state, durations)]
        energy_by_state = [left + right for left, right in zip(energy_by_state, energy)]
    for source, target in (("LCM", "HCM"), ("HCM", "LCM")):
        direction = f"{source}-to-{target}"
        matches = [switch for switch in switches if (switch["source"], switch["target"]) == (source, target)]
        if not matches:
            continue
        observed = sum(switch["observed_ns"] for switch in matches)
        durations = timelines.get(direction, [0, 0, 0, 0])
        require(durations == [0, 0, 0, observed], f"AP {sfu}: directional switch duration mismatch")
        energy = [Decimal(0)] * 3 + [watts_times_ns(matches[0]["power"], observed)]
        time_by_state[3] += observed
        energy_by_state[3] += energy[3]
        output.append(output_summary(identity, "DIRECTION", end_ns, direction, durations, energy))
    activity = by_summary(rows, "WIFI_AP_ACTIVITY", sfu_id=str(sfu))
    require(sum(time_by_state) == end_ns == int(activity["observation_end_ns"])
            and [int(activity[f"{state}_ns"]) for state in STATES] == time_by_state,
            f"AP {sfu}: activity did not partition the observation window: {activity}")
    energy_summary = by_summary(rows, "WIFI_AP_ENERGY", sfu_id=str(sfu))
    verify_totals(energy_summary, energy_by_state, end_ns)
    output.append(output_summary(identity, "SFU", end_ns, energy_summary["ap_mode"], time_by_state, energy_by_state))
    return output, time_by_state, energy_by_state


def require_zero_switch_compatibility(jar: Path, lines: list[str], original: list[str]) -> None:
    with TemporaryDirectory(prefix="fttr-b4-zero-switch-") as directory:
        baseline = Path(directory) / "baseline.properties"
        baseline.write_text("\n".join(line for line in lines if not line.startswith("wifi.ap.switch.")) + "\n",
                            encoding="utf-8")
        default_off = simulate(jar, baseline).splitlines()
        with baseline.open("a", encoding="utf-8") as file:
            file.write("wifi.ap.switch.enabled=false\n")
        explicitly_off = simulate(jar, baseline).splitlines()
    require(default_off == explicitly_off, "Explicit false altered default switch-off output")
    activity_summaries = {"WIFI_AP_ACTIVITY", "WIFI_AP_ENERGY", "WIFI_AP_ENERGY_MODE", "WIFI_AP_ENERGY_NETWORK"}
    require([line for line in original if fields(line).get("summary") not in activity_summaries]
            == [line for line in default_off if fields(line).get("summary") not in activity_summaries],
            "Enabling B4 without requests changed existing protocol output")


def audit_case(jar: Path, name: str, case: tuple) -> tuple[list[dict], list[dict]]:
    end_ns, frames, expected_switches, expected_totals = case
    config = Path(f"configs/b4-ap-switch-{name}.properties")
    config_lines = (ROOT / config).read_text(encoding="utf-8").splitlines()
    settings = dict(line.split("=", 1) for line in config_lines if line and not line.startswith("#"))
    lines = simulate(jar, ROOT / config).splitlines()
    rows = list(map(fields, lines))
    switches = audit_switches(rows, settings, end_ns, expected_switches)
    audit_frames(rows, switches, frames)
    if name == "zero-switch":
        require_zero_switch_compatibility(jar, config_lines, lines)

    case_identity = dict(scenario=name, configuration=config.as_posix())
    events = [dict(**case_identity, event_index=index, t_ns=time_ns(row),
                   **{column: row.get(column, "") for column in EVENT_COLUMNS[4:]})
              for index, row in enumerate(rows, 1)
              if row.get("event") in (*FRAME_EVENTS, *SWITCH_EVENTS, "WIFI_AP_ACTIVITY_CHANGE")]
    reports = []
    network_times = [0] * len(STATES)
    network_energies = [Decimal(0)] * len(STATES)
    for sfu, expected in expected_totals.items():
        identity = dict(**case_identity, sfu_id=sfu)
        summaries, times, energies = audit_ap(rows, identity, settings[f"sfu.{sfu}.wifi.ap.mode"], end_ns,
                                              [switch for switch in switches if switch["sfu"] == sfu], expected)
        reports.extend(summaries)
        network_times = [left + right for left, right in zip(network_times, times)]
        network_energies = [left + right for left, right in zip(network_energies, energies)]
    network = by_summary(rows, "WIFI_AP_ENERGY_NETWORK")
    require(int(network["modeled_ap_count"]) == len(expected_totals) and int(network["legacy_ap_count"]) == 0,
            "Wrong network AP coverage")
    verify_totals(network, network_energies, end_ns)
    reports.append(output_summary(case_identity, "NETWORK", end_ns, "", network_times, network_energies))
    print(f"{name}: {len(switches)} switches, {network_times[3]} ns SWITCH, {network_energies[3]} uJ")
    return events, reports


def verify_default_behavior(jar: Path, baseline: Path) -> None:
    configurations = sorted(config for step in ("b1", "b2", "b3") for config in (ROOT / "configs").glob(f"{step}-*.properties"))
    require(configurations, "B1–B3 baseline fixtures are missing")
    for config in configurations:
        require(simulate(baseline, config) == simulate(jar, config),
                f"Default-off protocol or accounting trace diverged: {config.name}")
    print(f"{len(configurations)} B1–B3 full traces match the previous JAR")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/b4-ap-switch")
    parser.add_argument("--baseline-jar", type=Path, help="Optional B3 binary for full default-off trace comparison")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR first with mvn -o verify")
    if args.baseline_jar is not None:
        verify_default_behavior(args.jar.resolve(), args.baseline_jar.resolve())
    events = []
    reports = []
    for name, case in CASES.items():
        case_events, case_reports = audit_case(args.jar.resolve(), name, case)
        events.extend(case_events)
        reports.extend(case_reports)
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "mode-switch-events.csv", EVENT_COLUMNS, events)
    write_csv(args.output / "time-energy-reconciliation.csv", SUMMARY_COLUMNS, reports)
    print(f"Verified B4 traces written to {args.output.resolve()}")


if __name__ == "__main__":
    main()
