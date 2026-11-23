"""汇总 B5 配对实验，按已观测活动时长检验功率敏感性。"""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal, localcontext
from pathlib import Path
from statistics import mean, stdev

from pon_experiment_io import ROOT, require, write_csv


DIRECTIONS = ("DL", "UL", "BOTH")
MODES = ("LCM", "HCM")
STATES = ("idle", "tx", "rx")
METRICS = (
    "observation_us", "actual_load_mbps", "generated_packets", "delivered_packets", "successful_bits",
    "throughput_mbps", "e2e_p95_us", "e2e_p99_us", "dropped_packets", "backlog_packets",
    "idle_ns", "tx_ns", "rx_ns", "total_energy_uj", "average_power_w", "energy_per_success_bit_nj",
    "equal_success_packets", "equal_success_bits", "equal_success_end_ns", "equal_success_idle_ns",
    "equal_success_tx_ns", "equal_success_rx_ns", "equal_success_energy_uj", "equal_success_energy_per_bit_nj",
    "idle_power_w", "tx_power_w", "rx_power_w",
)
CONDITIONAL = {
    "e2e_p95_us", "e2e_p99_us", "energy_per_success_bit_nj", "equal_success_end_ns", "equal_success_idle_ns",
    "equal_success_tx_ns", "equal_success_rx_ns", "equal_success_energy_uj", "equal_success_energy_per_bit_nj",
}
# Each row perturbs one HCM parameter group. LCM always uses its actual configured powers.
POWER_CASES = (
    ("B3_BASELINE", Decimal("0"), Decimal("1")),
    ("HCM_IDLE_MINUS_0_12_W", Decimal("-0.12"), Decimal("1")),
    ("HCM_IDLE_PLUS_0_12_W", Decimal("0.12"), Decimal("1")),
    ("HCM_TX_RX_MINUS_10_PCT", Decimal("0"), Decimal("0.9")),
    ("HCM_TX_RX_PLUS_10_PCT", Decimal("0"), Decimal("1.1")),
)
COMPARISONS = {
    "FIXED_WINDOW_ENERGY": "uJ",
    "FIXED_WINDOW_ENERGY_PER_SUCCESS_BIT": "nJ/bit",
    "EQUAL_SUCCESS_ENERGY": "uJ",
}
AGGREGATE_COLUMNS = ("direction", "load_mbps", "mode", "seed_count", "seeds") + tuple(
    column
    for metric in METRICS
    for column in (metric, f"{metric}_sd", *((f"{metric}_n",) if metric in CONDITIONAL else ()))
)
SENSITIVITY_COLUMNS = (
    "power_case", "direction", "load_mbps", "comparison", "unit", "hcm_idle_offset_w",
    "hcm_tx_rx_multiplier", "hcm_idle_w", "hcm_tx_w", "hcm_rx_w", "sample_count",
    "lcm_mean", "hcm_mean", "mean_hcm_minus_lcm", "paired_sd", "seed_hcm_minus_lcm",
    "hcm_lower_seed_count", "tied_seed_count", "hcm_higher_seed_count",
    "equal_delivered_bits_pair_count", "all_generated_delivered_pair_count",
)
ENDPOINT_COLUMNS = (
    "load_mbps", "delta_mean", "delta_seeds", "sample_count", "equal_delivered_bits_pair_count",
    "all_generated_delivered_pair_count",
)
CROSSOVER_COLUMNS = (
    "power_case", "direction", "comparison", "unit", "result", "seed_concordant",
    *(f"{endpoint}_{column}" for endpoint in ("lower", "upper") for column in ENDPOINT_COLUMNS),
)


@dataclass(frozen=True)
class SeedPair:
    seed: int
    lcm: dict[str, Decimal | None]
    hcm: dict[str, Decimal | None]


def read_pairs(path: Path) -> dict[tuple[str, Decimal], list[SeedPair]]:
    grouped = defaultdict(dict)
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"direction", "load_mbps", "mode", "seed", *METRICS}
        require(required.issubset(reader.fieldnames or ()),
                f"B5 metrics missing columns: {sorted(required.difference(reader.fieldnames or ()))}")
        for row in reader:
            direction, load, mode = row["direction"], Decimal(row["load_mbps"]), row["mode"]
            require(direction in DIRECTIONS and mode in MODES, f"Unexpected B5 direction or mode: {direction}/{mode}")
            key = (direction, load, int(row["seed"]))
            require(mode not in grouped[key], f"Duplicate B5 run: {key}/{mode}")
            grouped[key][mode] = {metric: Decimal(row[metric]) if row[metric] else None for metric in METRICS}
    require(bool(grouped), "No B5 runs supplied")

    pairs = defaultdict(list)
    for (direction, load, seed), runs in sorted(
        grouped.items(), key=lambda item: (DIRECTIONS.index(item[0][0]), *item[0][1:])
    ):
        require(set(runs) == set(MODES), f"Incomplete paired run: {direction}/{load}/{seed}")
        lcm, hcm = runs["LCM"], runs["HCM"]
        for field in ("observation_us", "actual_load_mbps", "generated_packets",
                      "equal_success_bits", "equal_success_packets"):
            require(lcm[field] == hcm[field], f"Unpaired {field}: {direction}/{load}/{seed}")
        pairs[(direction, load)].append(SeedPair(seed, lcm, hcm))
    return pairs


def summarize(values: list[Decimal]) -> tuple[Decimal | None, Decimal | None]:
    if not values:
        return None, None
    return mean(values), stdev(values) if len(values) > 1 else None


def aggregate(pairs: dict[tuple[str, Decimal], list[SeedPair]]) -> list[dict]:
    result = []
    for (direction, load), samples in pairs.items():
        for mode in MODES:
            runs = [pair.lcm if mode == "LCM" else pair.hcm for pair in samples]
            row = dict(direction=direction, load_mbps=load, mode=mode,
                       seed_count=len(samples), seeds=";".join(str(pair.seed) for pair in samples))
            for metric in METRICS:
                values = [run[metric] for run in runs if run[metric] is not None]
                row[metric], row[f"{metric}_sd"] = summarize(values)
                if metric in CONDITIONAL:
                    row[f"{metric}_n"] = len(values)
            result.append(row)
    return result


def integrate(powers: tuple[Decimal, ...], times_ns: tuple[Decimal, ...]) -> Decimal:
    return sum((power * duration for power, duration in zip(powers, times_ns)), Decimal(0)) / 1000


def comparison_values(run: dict[str, Decimal | None], powers: tuple[Decimal, ...]) -> dict[str, Decimal | None]:
    times_ns = tuple(run[f"{state}_ns"] for state in STATES)
    require(sum(times_ns) == run["observation_us"] * 1000, "B5 fixed-window state times do not conserve")
    fixed = integrate(powers, times_ns)
    successful_bits = run["successful_bits"]
    prefix = None
    if run["equal_success_bits"]:
        prefix_times_ns = tuple(run[f"equal_success_{state}_ns"] for state in STATES)
        require(sum(prefix_times_ns) == run["equal_success_end_ns"], "B5 equal-work state times do not conserve")
        prefix = integrate(powers, prefix_times_ns)
    return {
        "FIXED_WINDOW_ENERGY": fixed,
        "FIXED_WINDOW_ENERGY_PER_SUCCESS_BIT": fixed * 1000 / successful_bits if successful_bits else None,
        "EQUAL_SUCCESS_ENERGY": prefix,
    }


def sensitivity(pairs: dict[tuple[str, Decimal], list[SeedPair]]) -> list[dict]:
    result = []
    for (direction, load), pairs_at_load in pairs.items():
        for label, idle_delta, active_scale in POWER_CASES:
            by_metric = defaultdict(list)
            hcm_powers = []
            for pair in pairs_at_load:
                power_lcm = tuple(pair.lcm[f"{state}_power_w"] for state in STATES)
                power_hcm = (pair.hcm["idle_power_w"] + idle_delta,
                             pair.hcm["tx_power_w"] * active_scale,
                             pair.hcm["rx_power_w"] * active_scale)
                require(all(power > 0 for power in power_hcm), f"{label}: perturbed HCM power is nonpositive")
                hcm_powers.append(power_hcm)
                lcm = comparison_values(pair.lcm, power_lcm)
                hcm = comparison_values(pair.hcm, power_hcm)
                if label == "B3_BASELINE":
                    require(lcm["FIXED_WINDOW_ENERGY"] == pair.lcm["total_energy_uj"]
                            and hcm["FIXED_WINDOW_ENERGY"] == pair.hcm["total_energy_uj"],
                            "B5 reconstructed baseline energy differs from Java output")
                    require(lcm["EQUAL_SUCCESS_ENERGY"] == pair.lcm["equal_success_energy_uj"]
                            and hcm["EQUAL_SUCCESS_ENERGY"] == pair.hcm["equal_success_energy_uj"],
                            "B5 reconstructed baseline energy differs for equal-work prefixes")
                for comparison in COMPARISONS:
                    if lcm[comparison] is not None and hcm[comparison] is not None:
                        by_metric[comparison].append((pair, lcm[comparison], hcm[comparison]))

            for comparison, unit in COMPARISONS.items():
                samples = by_metric[comparison]
                differences = [hcm - lcm for _, lcm, hcm in samples]
                delta_mean, delta_sd = summarize(differences)
                result.append(dict(
                    power_case=label, direction=direction, load_mbps=load, comparison=comparison, unit=unit,
                    hcm_idle_offset_w=idle_delta, hcm_tx_rx_multiplier=active_scale,
                    hcm_idle_w=mean(row[0] for row in hcm_powers), hcm_tx_w=mean(row[1] for row in hcm_powers),
                    hcm_rx_w=mean(row[2] for row in hcm_powers),
                    sample_count=len(samples), lcm_mean=mean(row[1] for row in samples) if samples else None,
                    hcm_mean=mean(row[2] for row in samples) if samples else None,
                    mean_hcm_minus_lcm=delta_mean, paired_sd=delta_sd,
                    seed_hcm_minus_lcm=";".join(f"{pair.seed}:{delta}" for (pair, _, _), delta in zip(samples, differences)),
                    hcm_lower_seed_count=sum(delta < 0 for delta in differences),
                    tied_seed_count=sum(delta == 0 for delta in differences),
                    hcm_higher_seed_count=sum(delta > 0 for delta in differences),
                    equal_delivered_bits_pair_count=sum(pair.lcm["successful_bits"] == pair.hcm["successful_bits"]
                                                        for pair, _, _ in samples),
                    all_generated_delivered_pair_count=sum(
                        pair.lcm["generated_packets"] == pair.lcm["delivered_packets"] == pair.hcm["delivered_packets"]
                        for pair, _, _ in samples
                    ),
                ))
    return result


def agrees_with_mean(row: dict) -> bool:
    delta = row["mean_hcm_minus_lcm"]
    category = "hcm_higher_seed_count" if delta > 0 else "hcm_lower_seed_count" if delta < 0 else "tied_seed_count"
    return row[category] == row["sample_count"]


def crossover_endpoint(which: str, row: dict) -> dict:
    return {
        f"{which}_load_mbps": row["load_mbps"],
        f"{which}_delta_mean": row["mean_hcm_minus_lcm"],
        f"{which}_delta_seeds": row["seed_hcm_minus_lcm"],
        f"{which}_sample_count": row["sample_count"],
        f"{which}_equal_delivered_bits_pair_count": row["equal_delivered_bits_pair_count"],
        f"{which}_all_generated_delivered_pair_count": row["all_generated_delivered_pair_count"],
    }


def find_crossovers(rows: list[dict]) -> list[dict]:
    series = defaultdict(list)
    for row in rows:
        key = (row["power_case"], row["direction"], row["comparison"], row["unit"])
        series[key].append(row)
    output = []
    for (power_case, direction, comparison, unit), points in series.items():
        identity = dict(power_case=power_case, direction=direction, comparison=comparison, unit=unit)
        candidates = []
        prior = None
        for point in sorted(points, key=lambda row: row["load_mbps"]):
            delta = point["mean_hcm_minus_lcm"]
            if delta is None:
                prior = None  # Missing denominators split the observable load series.
                continue
            if delta == 0:
                candidates.append((point, point, "EXACT_TIE"))
            if prior is not None and prior["mean_hcm_minus_lcm"] * delta < 0:
                candidates.append((prior, point, "SIGN_CHANGE"))
            prior = point
        if not candidates:
            status = "NO_CROSSOVER" if any(point["sample_count"] for point in points) else "NO_COMPARABLE_SAMPLES"
            output.append(dict(**identity, result=status))
        for lower, upper, status in candidates:
            output.append(dict(**identity, result=status,
                               seed_concordant=str(agrees_with_mean(lower) and agrees_with_mean(upper)).lower(),
                               **crossover_endpoint("lower", lower), **crossover_endpoint("upper", upper)))
    return output


def analyze(output: Path) -> None:
    with localcontext() as context:
        context.prec = 50
        pairs = read_pairs(output / "energy-load-metrics.csv")
        summaries = aggregate(pairs)
        sensitivity_rows = sensitivity(pairs)
        crossovers = find_crossovers(sensitivity_rows)
    write_csv(output / "energy-load-aggregate.csv", AGGREGATE_COLUMNS, summaries)
    write_csv(output / "power-sensitivity.csv", SENSITIVITY_COLUMNS, sensitivity_rows)
    write_csv(output / "energy-crossover-intervals.csv", CROSSOVER_COLUMNS,
              [row for row in crossovers if row["power_case"] == "B3_BASELINE"])
    write_csv(output / "power-sensitivity-crossovers.csv", CROSSOVER_COLUMNS, crossovers)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "results/b5-energy-crossover")
    analyze(parser.parse_args().output)
