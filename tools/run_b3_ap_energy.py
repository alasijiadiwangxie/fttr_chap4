"""重放 B3 场景，用 B2 活动边界独立复核 AP 能耗。"""

from __future__ import annotations

import argparse
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory

from pon_experiment_io import ROOT, require, simulate, write_csv


MODEL = "RESEARCH_INITIAL_V1"
STATES = ("idle", "tx", "rx")
DEFAULT_POWERS = {
    "LCM": tuple(map(Decimal, ("7.38", "9.20", "8.20"))),
    "HCM": tuple(map(Decimal, ("7.62", "10.50", "8.70"))),
}
# SFU 顺序、能力模式及手算 IDLE、TX、RX 纳秒时长。
CASES = {
    "lcm-natural": (("LCM", (118_000, 331_200, 56_000)),),
    "hcm-natural": (("HCM", (118_000, 155_200, 56_000)),),
    "lcm-1000us": (("LCM", (612_800, 331_200, 56_000)),),
    "hcm-1000us": (("HCM", (788_800, 155_200, 56_000)),),
    "mixed": (
        ("LCM", (806_400, 165_600, 28_000)),
        ("HCM", (894_400, 28_000, 77_600)),
        ("LEGACY", (1_000_000, 0, 0)),
    ),
    "hcm-clipped": (("HCM", (34_000, 16_125, 0)),),
    "zero-traffic": (("LCM", (1_000_000, 0, 0)),),
    "hcm-retry": (("HCM", (196_000, 56_000, 232_800)),),
}
ENERGY_SUMMARIES = {"WIFI_AP_ENERGY", "WIFI_AP_ENERGY_NETWORK"}
ENERGY_FIELDS = (
    "model", "observation_start_ns", "observation_end_ns", "modeled_ap_count", "legacy_ap_count",
    *(f"{state}_power_w" for state in STATES), *(f"{state}_energy_uj" for state in STATES),
    "total_energy_uj", "avg_power_w",
)
SUMMARY_COLUMNS = (
    "scenario", "configuration", "scope", "sfu_id", "ap_mode", "model", "observation_start_ns",
    "observation_end_ns", "modeled_ap_count", "legacy_ap_count",
    *(f"{state}_ns" for state in STATES),
    *(f"{state}_power_w" for state in STATES),
    *(f"{state}_energy_uj" for state in STATES),
    "total_energy_uj", "avg_power_w", "matches_manual", "protocol_unchanged",
)
INTERVAL_COLUMNS = (
    "scenario", "configuration", "sfu_id", "ap_mode", "state", "start_ns", "end_ns", "duration_ns",
    "start_reason", "start_packet_id", "start_direction", "end_reason", "end_packet_id", "end_direction",
    "power_w", "interval_energy_uj", "cumulative_energy_uj",
)


def fields(line: str) -> dict[str, str]:
    return dict(item.split("=", 1) for item in line.split() if "=" in item)


def kind(row: dict[str, str], name: str) -> bool:
    return row.get("summary") == name


def windows(row: dict[str, str]) -> tuple[int, int]:
    return int(row["observation_start_ns"]), int(row["observation_end_ns"])


def powers_for_mode(mode: str, configuration: list[str]) -> dict[str, Decimal]:
    overrides = dict(line.split("=", 1) for line in configuration
                     if line.startswith(f"wifi.ap.energy.{mode.lower()}."))
    return {state: Decimal(overrides.get(f"wifi.ap.energy.{mode.lower()}.{state}.w", value))
            for state, value in zip(STATES, DEFAULT_POWERS[mode])}


def reconstruct_intervals(rows: list[dict[str, str]], identity: dict[str, str], end_ns: int,
                          powers: dict[str, Decimal]) -> tuple[list[dict], dict[str, int], dict[str, Decimal]]:
    sfu_id = identity["sfu_id"]
    transitions = [row for row in rows
                   if row.get("event") == "WIFI_AP_ACTIVITY_CHANGE" and row["sfu_id"] == sfu_id]
    transitions.append(dict(t_ns=str(end_ns), reason="OBSERVATION_END"))
    previous = {"reason": "OBSERVATION_START"}
    start_ns = 0
    state = "idle"
    durations = dict.fromkeys(STATES, 0)
    energies = dict.fromkeys(STATES, Decimal(0))
    intervals = []
    cumulative = Decimal(0)
    for boundary in transitions:
        time_ns = int(boundary["t_ns"])
        require(start_ns <= time_ns <= end_ns, f"SFU {sfu_id}: invalid interval at {time_ns} ns")
        if time_ns > start_ns:
            duration = time_ns - start_ns
            energy = powers[state] * (Decimal(duration) / 1000)
            durations[state] += duration
            energies[state] += energy
            cumulative += energy
            intervals.append(dict(
                **identity, state=state.upper(), start_ns=start_ns, end_ns=time_ns, duration_ns=duration,
                start_reason=previous["reason"], start_packet_id=previous.get("packet_id", ""),
                start_direction=previous.get("direction", ""), end_reason=boundary["reason"],
                end_packet_id=boundary.get("packet_id", ""), end_direction=boundary.get("direction", ""),
                power_w=powers[state], interval_energy_uj=energy, cumulative_energy_uj=cumulative,
            ))
        if "from" in boundary:
            require(boundary["ap_mode"] == identity["ap_mode"] and boundary["from"].lower() == state,
                    f"SFU {sfu_id}: discontinuous transition at {time_ns} ns")
            state = boundary["to"].lower()
        start_ns = time_ns
        previous = boundary
    return intervals, durations, energies


def verify_energy(row: dict[str, str], energies: dict[str, Decimal], end_ns: int, label: str) -> None:
    total = sum(energies.values())
    require(row["model"] == MODEL and windows(row) == (0, end_ns), f"{label}: wrong model or window")
    for state in STATES:
        require(Decimal(row[f"{state}_energy_uj"]) == energies[state], f"{label}: incorrect {state} energy")
    require(Decimal(row["total_energy_uj"]) == total, f"{label}: incorrect total energy")
    average = total / (Decimal(end_ns) / 1000) if end_ns else Decimal(0)
    require(abs(Decimal(row["avg_power_w"]) - average) <= Decimal("1e-12"), f"{label}: incorrect average power")


def energy_fields(row: dict[str, str]) -> dict[str, str]:
    return {key: row[key] for key in ENERGY_FIELDS if key in row}


def audit_case(jar: Path, name: str, expected: tuple) -> tuple[list[dict], list[dict]]:
    configuration = Path(f"configs/b3-ap-energy-{name}.properties")
    config_lines = (ROOT / configuration).read_text(encoding="utf-8").splitlines()
    enabled = simulate(jar, ROOT / configuration).splitlines()
    with TemporaryDirectory(prefix="fttr-ap-energy-") as folder:
        baseline_config = Path(folder) / "baseline.properties"
        baseline_config.write_text("\n".join(line for line in config_lines
                                             if not line.startswith("wifi.ap.energy.")) + "\n", encoding="utf-8")
        baseline = simulate(jar, baseline_config).splitlines()
    rows = list(map(fields, enabled))
    original = [line for line, row in zip(enabled, rows) if row.get("summary") not in ENERGY_SUMMARIES]
    require(original == baseline, f"{name}: enabling B3 changed existing trace lines")

    activity = [row for row in rows if kind(row, "WIFI_AP_ACTIVITY")]
    energy = [row for row in rows if kind(row, "WIFI_AP_ENERGY")]
    network = [row for row in rows if kind(row, "WIFI_AP_ENERGY_NETWORK")]
    end_ns = sum(expected[0][1])
    legacy_count = sum(mode == "LEGACY" for mode, _ in expected)
    modeled_count = len(expected) - legacy_count
    require(len(activity) == len(expected) and len(energy) == modeled_count and len(network) == 1,
            f"{name}: unexpected activity or energy summary count")
    trace_rows = []
    summaries = []
    combined = dict.fromkeys(STATES, Decimal(0))
    for index, (mode, expected_times) in enumerate(expected, 1):
        sfu_id = str(index)
        label = f"{name} SFU {sfu_id}"
        activity_row = activity[index - 1]
        require(activity_row["sfu_id"] == sfu_id and activity_row["ap_mode"] == mode
                and windows(activity_row) == (0, end_ns), f"{label}: inconsistent B2 mode or window")
        manual = dict(zip(STATES, expected_times))
        require(sum(expected_times) == end_ns
                and {state: int(activity_row[f"{state}_ns"]) for state in STATES} == manual,
                f"{label}: B2 activity differs from hand-checked durations")
        matches = [row for row in energy if row["sfu_id"] == sfu_id]
        if mode == "LEGACY":
            require(not matches, f"{label}: LEGACY has no configured power profile")
            continue
        require(len(matches) == 1 and matches[0]["ap_mode"] == mode, f"{label}: missing or duplicate AP energy")
        ap_summary = matches[0]
        identity = dict(scenario=name, configuration=configuration.as_posix(), sfu_id=sfu_id, ap_mode=mode)
        powers = powers_for_mode(mode, config_lines)
        for state in STATES:
            require(Decimal(ap_summary[f"{state}_power_w"]) == powers[state], f"{label}: wrong {state} power")
        intervals, reconstructed_times, reconstructed_energy = reconstruct_intervals(rows, identity, end_ns, powers)
        require(reconstructed_times == manual, f"{label}: B2 transitions do not reproduce B2 activity summary")
        verify_energy(ap_summary, reconstructed_energy, end_ns, label)
        for state in STATES:
            combined[state] += reconstructed_energy[state]
        trace_rows.extend(intervals)
        summaries.append(dict(**identity, scope="SFU", **energy_fields(ap_summary),
                              **{f"{state}_ns": manual[state] for state in STATES},
                              matches_manual="true", protocol_unchanged="true"))

    network_row = network[0]
    require(int(network_row["modeled_ap_count"]) == modeled_count and int(network_row["legacy_ap_count"]) == legacy_count,
            f"{name}: network counts differ from available profiles")
    verify_energy(network_row, combined, end_ns, name + " network")
    summaries.append(dict(scenario=name, configuration=configuration.as_posix(), scope="NETWORK",
                          **energy_fields(network_row), matches_manual="true", protocol_unchanged="true"))
    print(f"{name}: {modeled_count} modeled APs, {legacy_count} LEGACY, {network_row['total_energy_uj']} uJ")
    return trace_rows, summaries


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/b3-ap-energy")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR first with mvn -o -DskipTests package")
    intervals = []
    summaries = []
    for name, expected in CASES.items():
        events, energy = audit_case(args.jar.resolve(), name, expected)
        intervals.extend(events)
        summaries.extend(energy)
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "energy-intervals.csv", INTERVAL_COLUMNS, intervals)
    write_csv(args.output / "energy-summary.csv", SUMMARY_COLUMNS, summaries)
    print(f"Verified B3 energy written to {args.output.resolve()}")


if __name__ == "__main__":
    main()
