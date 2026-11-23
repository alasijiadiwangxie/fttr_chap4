"""Reintegrate actual B5 AP state durations with the Wi-Fi 8 paper's power values."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from decimal import Decimal, localcontext
from pathlib import Path
from statistics import mean, stdev

from analyze_b5_energy_load import CROSSOVER_COLUMNS, STATES, find_crossovers, integrate
from pon_experiment_io import ROOT, require, write_csv


# Sánchez-Vital et al., IEEE Wireless Communications, Section IV, 10.1109/MCOM.004.2400486.
# The state labels follow B5's idle/tx/rx order; the paper states Tx, Rx and Idle.
PAPER_VOLTAGE_V = Decimal("12")
PAPER_CURRENT_A = {
    "LCM": {"idle": Decimal("0.58"), "tx": Decimal("1.08"), "rx": Decimal("0.66")},
    "HCM": {"idle": Decimal("0.66"), "tx": Decimal("1.25"), "rx": Decimal("1.00")},
}
SOURCE = ROOT / "results/b5-energy-load/energy-load-metrics.csv"
RESULTS = ROOT / "results/b5-paper-replication"
RAW_FIELDS = (
    "direction", "load_mbps", "seed", "mode", "sfu_id", "configuration", "configuration_sha256",
    "input_sha256", "java_trace_sha256", "observation_us", "actual_load_mbps", "throughput_mbps",
    "generated_packets", "delivered_packets", "dropped_packets", "backlog_packets", "successful_bits",
    *(f"{state}_ns" for state in STATES),
)
DELTA_FIELDS = (
    "power_case", "direction", "load_mbps", "comparison", "unit", "sample_count",
    "lcm_mean_w", "hcm_mean_w", "mean_hcm_minus_lcm", "paired_sd_w", "seed_hcm_minus_lcm",
    "hcm_lower_seed_count", "tied_seed_count", "hcm_higher_seed_count",
    "equal_delivered_bits_pair_count", "all_generated_delivered_pair_count",
)


def paper_power_w(mode: str) -> dict[str, Decimal]:
    return {state: current * PAPER_VOLTAGE_V for state, current in PAPER_CURRENT_A[mode].items()}


def read_reweighted(source: Path) -> tuple[list[dict], dict]:
    paired_runs = defaultdict(dict)
    runs = []
    with source.open(encoding="utf-8", newline="") as stream:
        for b5 in csv.DictReader(stream):
            mode = b5["mode"]
            key = (b5["direction"], Decimal(b5["load_mbps"]), int(b5["seed"]))
            require(mode not in paired_runs[key], f"Repeated B5 run: {key}/{mode}")
            durations = tuple(Decimal(b5[f"{state}_ns"]) for state in STATES)
            window_us = Decimal(b5["observation_us"])
            require(window_us > 0 and sum(durations) == window_us * 1000
                    and min(durations) >= 0, f"Invalid B5 observation window: {key}/{mode}")
            b3_powers = tuple(Decimal(b5[f"{state}_power_w"]) for state in STATES)
            b3_energy = integrate(b3_powers, durations)
            require(b3_energy == Decimal(b5["total_energy_uj"])
                    and abs(b3_energy / window_us - Decimal(b5["average_power_w"])) < Decimal("1e-25"),
                    f"B3 energy does not match B5 activities: {key}/{mode}")
            watts = paper_power_w(mode)
            by_state = {state: watts[state] * duration / 1000 for state, duration in zip(STATES, durations)}
            paper_energy = sum(by_state.values(), Decimal(0))
            success_bits = int(b5["successful_bits"])
            row = {field: b5[field] for field in RAW_FIELDS}
            row.update(b3_total_energy_uj=b5["total_energy_uj"], b3_average_power_w=b5["average_power_w"],
                       b3_energy_per_success_bit_nj=b5["energy_per_success_bit_nj"],
                       paper_voltage_v=PAPER_VOLTAGE_V, paper_total_energy_uj=paper_energy,
                       paper_average_power_w=paper_energy / window_us,
                       paper_energy_per_success_bit_nj=1000 * paper_energy / success_bits if success_bits else None)
            for state in STATES:
                row.update({
                    f"b3_{state}_power_w": b5[f"{state}_power_w"],
                    f"b3_{state}_energy_uj": b5[f"{state}_energy_uj"],
                    f"paper_{state}_current_a": PAPER_CURRENT_A[mode][state],
                    f"paper_{state}_power_w": watts[state],
                    f"paper_{state}_energy_uj": by_state[state],
                })
            runs.append(row)
            paired_runs[key][mode] = row
    require(bool(runs), "No B5 simulation rows to reweight")
    for key, pair in paired_runs.items():
        require(set(pair) == {"LCM", "HCM"}, f"Incomplete B5 pair: {key}")
        for name in ("input_sha256", "observation_us", "generated_packets"):
            require(pair["LCM"][name] == pair["HCM"][name], f"Unpaired {name}: {key}")
    return runs, paired_runs


def paired_deltas(paired_runs: dict, chosen_seed: int | None = None) -> list[dict]:
    samples = defaultdict(list)
    for (direction, load, seed), pair in paired_runs.items():
        if chosen_seed is None or seed == chosen_seed:
            samples[direction, load].append((seed, pair["LCM"], pair["HCM"]))
    entries = []
    for (direction, load), pairs in samples.items():
        pairs.sort(key=lambda pair: pair[0])
        for label, field in (("B3_AS_RUN", "b3_average_power_w"), ("PAPER_12_V", "paper_average_power_w")):
            lcm = [Decimal(a[field]) for _, a, _ in pairs]
            hcm = [Decimal(b[field]) for _, _, b in pairs]
            deltas = [high - low for low, high in zip(lcm, hcm)]
            entries.append(dict(
                power_case=label, direction=direction, load_mbps=load, comparison="FIXED_WINDOW_AVERAGE_POWER",
                unit="W", sample_count=len(pairs), lcm_mean_w=mean(lcm), hcm_mean_w=mean(hcm),
                mean_hcm_minus_lcm=mean(deltas), paired_sd_w=stdev(deltas) if len(deltas) > 1 else None,
                seed_hcm_minus_lcm=";".join(f"{seed}:{delta}" for (seed, _, _), delta in zip(pairs, deltas)),
                hcm_lower_seed_count=sum(delta < 0 for delta in deltas),
                tied_seed_count=sum(delta == 0 for delta in deltas),
                hcm_higher_seed_count=sum(delta > 0 for delta in deltas),
                equal_delivered_bits_pair_count=sum(a["successful_bits"] == b["successful_bits"] for _, a, b in pairs),
                all_generated_delivered_pair_count=sum(
                    a["generated_packets"] == a["delivered_packets"] == b["delivered_packets"] for _, a, b in pairs),
            ))
    return entries


def reweight(source: Path, output: Path) -> None:
    with localcontext() as context:
        context.prec = 45
        runs, paired_runs = read_reweighted(source)
        deltas = paired_deltas(paired_runs)
        crossings = [dict(scope="MEAN", seed="", **row) for row in find_crossovers(deltas)]
        for seed in sorted({seed for _, _, seed in paired_runs}):
            crossings.extend(dict(scope="SEED", seed=seed, **row)
                             for row in find_crossovers(paired_deltas(paired_runs, seed)))
        power_table = []
        for mode, currents in PAPER_CURRENT_A.items():
            for state, current in currents.items():
                b3_powers = {Decimal(row[f"b3_{state}_power_w"]) for row in runs if row["mode"] == mode}
                require(len(b3_powers) == 1, f"B3 {mode}/{state} does not have a common reference power")
                b3_w = b3_powers.pop()
                paper_w = paper_power_w(mode)[state]
                power_table.append(dict(mode=mode, state=state, paper_current_a=current, paper_voltage_v=PAPER_VOLTAGE_V,
                                        paper_power_w=paper_w, b3_power_w=b3_w, paper_minus_b3_w=paper_w - b3_w))
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "b5-paper-power-reweight.csv", tuple(runs[0]), runs)
    write_csv(output / "b5-reweight-power-deltas.csv", DELTA_FIELDS, deltas)
    write_csv(output / "b5-reweight-crossings.csv", ("scope", "seed", *CROSSOVER_COLUMNS), crossings)
    write_csv(output / "paper-vs-b3-power-parameters.csv", tuple(power_table[0]), power_table)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, default=RESULTS)
    args = parser.parse_args()
    reweight(args.source, args.output)


if __name__ == "__main__":
    main()
