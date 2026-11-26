"""Audit E2 event logs and export seed results, paired intervals, and duration checks."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from hashlib import sha256
from math import sqrt
from pathlib import Path
from statistics import mean, stdev

from e2_design import ECQ, FIXED, GROUPS, HCM, LCM, MAIN, PILOT_SEEDS, SEEDS, arrivals, properties, study
from e2_metrics import NETWORK, summarize_run
from e2_run import OUT, raw_signature, trial_path, trials
from pon_experiment_io import require, write_csv

PAIRED = ("total_uj", "drain_uj", "complete_uj", "power_w_per_ap", "goodput_mbps", "nj_per_success_bit",
          "nj_per_final_bit", "on_time_pct", "p95_ms", "p99_ms", "jitter_ms", "drop_pct", "backlog_window",
          "unsettled_final", "switches", "hcm_pct", "optical_mean_us", "wireless_mean_us")
T95 = (0, 12.706205, 4.302653, 3.182446, 2.776445, 2.570582, 2.446912, 2.364624, 2.306004, 2.262157)
# Durations are frozen up front. The repeat's first half uses the same packet fixture.
STABILITY_LIMITS = {"goodput_mbps": 5, "power_w_per_ap": 1}


def interval(samples):
    n = len(samples)
    if not n:
        return dict(n=0, mean=None, sd=None, ci95_low=None, ci95_high=None)
    average = mean(samples)
    if n == 1:
        return dict(n=n, mean=average, sd=None, ci95_low=None, ci95_high=None)
    require(n <= len(T95), "E2 uses at most ten seed pairs for t intervals")
    sd = stdev(samples)
    radius = T95[n - 1] * sd / sqrt(n)
    return dict(n=n, mean=average, sd=sd, ci95_low=average - radius, ci95_high=average + radius)


def save(path, rows):
    require(rows, f"No results for {path.name}")
    path.parent.mkdir(exist_ok=True, parents=True)
    keys = tuple(dict.fromkeys(k for row in rows for k in row))
    rounded = [{k: float(format(v, ".12g")) if isinstance(v, float) else v for k, v in row.items()} for row in rows]
    write_csv(path, keys, rounded)


def audit_batch(task):
    case, seed, pilot, signature, analysis_code = task
    fixture = arrivals(case, seed)
    # The only treatment differences within a case are the controller and explicitly disabled mechanisms.
    shared = {}
    batch, intervals = [], []
    for policy in case.variants:
        cfg = properties(case, seed, policy, fixture)
        common = {k: v for k, v in cfg.items() if k != "wifi.ap.control.policy" and not k.startswith("wifi.ap.control.complete.")}
        if shared:
            require(shared == common, f"Non-policy configuration differs in {case.name}/{seed}")
        shared = common
        stem = trial_path(case, seed, policy)
        stamped = json.loads(stem.with_suffix(".sha256").read_text(encoding="utf-8"))
        actual_cfg_hash = sha256(stem.with_suffix(".properties").read_bytes()).hexdigest()
        require(stamped == {"experiment_sha256": signature, "config_sha256": actual_cfg_hash}
                and stem.with_suffix(".properties").read_text(encoding="utf-8") == "".join(f"{k}={v}\n" for k, v in cfg.items()),
                f"Stale event trace or input: {stem}")
        representative = not pilot and seed == SEEDS[0] and case.group in ("04-dynamics", "06-sensitivity")
        cache = stem.with_suffix(".audit.json")
        key = [signature, actual_cfg_hash, analysis_code, sha256(stem.with_suffix(".log").read_bytes()).hexdigest()]
        stored = json.loads(cache.read_text(encoding="utf-8")) if cache.is_file() else {}
        if stored.get("key") == key:
            combined, timeline = stored["trials"], stored["timeline"]
        else:
            meta, metrics, timeline = summarize_run(case, seed, policy, stem, signature, representative)
            # P* and derived intervals operate on seed-level floats, after exact Decimal energy checks.
            combined = [{k: float(v) if hasattr(v, "as_tuple") else v for k, v in row.items()} | meta for row in metrics]
            timeline = [meta | row for row in timeline]
            cache.write_text(json.dumps({"key": key, "trials": combined, "timeline": timeline}), encoding="utf-8")
        batch.extend(combined)
        intervals.extend(timeline)
    require(len({r["input_sha256"] for r in batch}) == 1, f"Actual fixture differs for {case.name}/{seed}")
    return batch, intervals


def policy_stats(rows):
    scope = ("scenario", "policy", "sfu_id", "direction", "ac")
    by_scope = defaultdict(list)
    for row in rows:
        by_scope[tuple(row[key] for key in scope)].append(row)
    out = []
    meta_fields = set(study()[0].metadata())
    for key, seeds in by_scope.items():
        require(len({row["seed"] for row in seeds}) == len(SEEDS) == len(seeds), f"Missing seed or duplicate scope: {key}")
        point = dict(zip(scope, key))
        # Retain the configured topology, traffic and treatment variables alongside the statistics.
        point.update({k: v for k, v in seeds[0].items() if k in meta_fields})
        for field in PAIRED + ("offered_mbps", "goodput_mbps_per_ap", "network_power_w", "generated", "delivered_final",
                               "dropped_final", "final_success_pct", "external_busy_pct", "scan_uj", "sinr_errors", "retries",
                               "pon_data_util_pct", "deferrals_opened", "deferrals_released", "deferrals_cancelled"):
            values = [row[field] for row in seeds if row.get(field) is not None]
            if values:
                point.update({f"{field}_{stat}": value for stat, value in interval(values).items()})
        out.append(point)
    return out


def pair_stats(rows, comparison, treatment, baseline):
    """Paired intervals use within-seed differences, never a difference of separate confidence intervals."""
    def key(row):
        return row["sfu_id"], row["direction"], row["ac"], row["seed"]

    control = {key(row): row for row in rows if (row["scenario"], row["policy"]) == baseline}
    tests = {key(row): row for row in rows if (row["scenario"], row["policy"]) == treatment}
    if not tests or not control:
        return []
    require(tests.keys() == control.keys() and {k[3] for k in tests} == set(SEEDS), f"{comparison}: unmatched seeds or classes")
    changes = defaultdict(list)
    for k, a in tests.items():
        b = control[k]
        require(a["input_sha256"] == b["input_sha256"] and a["window_us"] == b["window_us"], f"{comparison}: not paired")
        for metric in PAIRED:
            if a.get(metric) is not None and b.get(metric) is not None:
                changes[k[:3], metric].append(a[metric] - b[metric])
        for metric in ("total_uj", "complete_uj", "nj_per_success_bit", "nj_per_final_bit"):
            if a.get(metric) is not None and b.get(metric):
                changes[k[:3], metric + "_relative_pct"].append(100 * (a[metric] - b[metric]) / b[metric])
    return [dict(comparison=comparison, scenario=treatment[0], policy=treatment[1], baseline_scenario=baseline[0],
                 baseline_policy=baseline[1], sfu_id=scope[0], direction=scope[1], ac=scope[2], metric=metric) | interval(changes[scope, metric])
            for scope, metric in changes]


def comparisons(rows, group):
    output = []
    for case in study():
        if case.group != group:
            continue
        if case.variants == MAIN:
            for baseline in (LCM, HCM, FIXED):
                output.extend(pair_stats(rows, "main", (case.name, ECQ), (case.name, baseline)))
        if case.name == "mechanisms":
            for ablation in case.variants[1:]:
                output.extend(pair_stats(rows, "ablation", (case.name, ECQ), (case.name, ablation)))
        if case.power == "paper":
            for policy in case.variants:
                output.extend(pair_stats(rows, "paper-vs-B3", (case.name, policy), ("load-031", policy)))
        if case.name.startswith("observation-"):
            output.extend(pair_stats(rows, "main", (case.name, ECQ), (case.name, FIXED)))
            for policy in case.variants:
                output.extend(pair_stats(rows, "observation-vs-reference", (case.name, policy), ("rf-secondary-strong-15", policy)))
    return output


def duration_stats(rows):
    network = [r for r in rows if tuple(r[k] for k in ("sfu_id", "direction", "ac")) == NETWORK]
    short_cases = {case.name: case for case in study()}
    output = []
    for short in network:
        case = short_cases[short["scenario"]]
        if short["window_us"] != case.window_us:
            continue
        extended = next(r for r in network if (r["scenario"], r["seed"], r["policy"], r["window_us"])
                        == (short["scenario"], short["seed"], short["policy"], short["window_us"] * 2))
        for metric in (*STABILITY_LIMITS, "on_time_pct", "p99_ms", "backlog_window"):
            a, b = short[metric], extended[metric]
            relative = (b - a) * 100 / a if a and b is not None else None
            tolerance = STABILITY_LIMITS.get(metric)
            output.append(dict(scenario=short["scenario"], policy=short["policy"], seed=short["seed"],
                               short_window_us=short["window_us"], long_window_us=extended["window_us"], metric=metric,
                               short=a, long=b, relative_change_pct=relative, stability_limit_pct=tolerance,
                               within_limit=abs(relative) <= tolerance if relative is not None and tolerance else None))
    return output


def process(pilot, workers, groups):
    signature = raw_signature(pilot)
    code = sha256(b"".join(Path(__file__).with_name(name).read_bytes()
                           for name in ("e2_analyze.py", "e2_metrics.py"))).hexdigest()
    jobs = [(case, seed, pilot, signature, code) for case, seed in trials(pilot, groups)]
    if pilot:
        short_by_name = {case.name: case for case in study()}
        for long, seed, *_ in jobs:
            short = short_by_name[long.name]
            require([p for p in arrivals(long, seed) if p[0] < short.window_us] == arrivals(short, seed),
                    f"Duration check changed the prefix for {short.name}/{seed}")
    trial_rows, timelines = [], []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for i, (batch, modes) in enumerate(pool.map(audit_batch, jobs, chunksize=1), 1):
            trial_rows.extend(batch)
            timelines.extend(modes)
            if i % 20 == 0 or i == len(jobs):
                print(f"E2 audit: {i}/{len(jobs)} paired batches", flush=True)
    if pilot:
        save(OUT / "duration-check.csv", duration_stats(trial_rows))
        return
    by_workload = defaultdict(set)
    for row in trial_rows:
        by_workload[row["traffic_key"], row["seed"]].add(row["input_sha256"])
    require(all(len(digests) == 1 for digests in by_workload.values()), "Shared workload differs across treatments")
    save(OUT / "scenario-parameters.csv", [case.metadata() | {"policies": "|".join(case.variants),
         "formal_seeds": "|".join(map(str, SEEDS)), "duration_pilot_seeds": "|".join(map(str, PILOT_SEEDS))}
         for case in study()])
    for group in groups:
        subset = [row for row in trial_rows if row["group"] == group]
        directory = OUT / group
        save(directory / "seeds.csv", subset)
        save(directory / "summary.csv", policy_stats(subset))
        save(directory / "paired.csv", comparisons(trial_rows, group))
        intervals = [r for r in timelines if r["group"] == group]
        if intervals:
            save(directory / "mode-timeline.csv", intervals)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pilot", action="store_true")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--groups", choices=GROUPS, nargs="+", default=GROUPS)
    args = parser.parse_args()
    process(args.pilot, args.workers, args.groups)
