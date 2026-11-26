"""Export E4 per-seed results and paired confidence intervals without pooling packets across seeds."""

from __future__ import annotations

from collections import defaultdict

from e2_analyze import interval, save
from e4_design import E3, FORMAL_SEEDS, MAIN, formal
from pon_experiment_io import require

METRICS = (
    "total_uj", "drain_uj", "complete_uj", "power_w_per_ap", "nj_per_success_bit", "nj_per_final_bit",
    "goodput_mbps", "goodput_mbps_per_ap", "mean_ms", "p95_ms", "p99_ms", "jitter_ms", "on_time_pct", "drop_pct",
    "final_success_pct", "dropped_final", "backlog_window", "unsettled_final", "switches", "hcm_pct",
    "scan_uj", "optical_mean_us", "wireless_mean_us", "pon_data_util_pct", "executed_deferrals",
    "deferrals_released", "deferrals_cancelled", "dl_mean_mpdus_per_ppdu",
)
RELATIVE = ("total_uj", "drain_uj", "complete_uj", "nj_per_success_bit", "nj_per_final_bit")


def matched_stats(index, exp, variant, reference, baseline, kind):
    controls = index[reference, baseline]
    tests = index[exp.case.name, variant]
    require(controls.keys() == tests.keys(), f"{exp.case.name}/{variant}: paired inputs or scopes differ")
    differences = defaultdict(list)
    for key, actual in tests.items():
        control = controls[key]
        require(actual["input_sha256"] == control["input_sha256"] and actual["window_us"] == control["window_us"],
                f"{exp.case.name}/{variant}: paired workload timestamps differ")
        for metric in METRICS:
            a, b = actual.get(metric), control.get(metric)
            if a is None or b is None:
                continue
            differences[key[:3], metric].append(a - b)
            if metric in RELATIVE and b > 0:
                differences[key[:3], metric + "_relative_pct"].append(100 * (a - b) / b)
    rows = []
    for (scope, metric), samples in differences.items():
        rows.append(dict(scenario=exp.case.name, reference_scenario=reference, policy=variant, baseline=baseline,
                         comparison=kind, sfu_id=scope[0], direction=scope[1], ac=scope[2], metric=metric) | interval(samples))
    return rows


def export(rows, trajectory):
    # The output path is shared with the runner, without importing it at module initialization.
    from e4_run import OUT

    index = defaultdict(dict)
    run_scopes = defaultdict(set)
    for row in rows:
        trial = row["scenario"], row["policy"]
        key = row["sfu_id"], row["direction"], row["ac"], row["seed"]
        require(key not in index[trial], f"Duplicate formal result: {trial} {key}")
        index[trial][key] = row
        if key[:3] == ("ALL", "BOTH", "ALL"):
            run_scopes[trial].add(row["seed"])
    experiments = tuple(formal())
    require(set(run_scopes) == {(e.case.name, p) for e in experiments for p in e.policies}
            and all(seeds == set(FORMAL_SEEDS) for seeds in run_scopes.values()), "Formal seed grid is incomplete")
    stats = defaultdict(list)
    for experiment in experiments:
        case, variants = experiment.case, experiment.policies
        for baseline in variants:
            if baseline != E3:
                kind = "main" if baseline in MAIN else "ablation" if baseline.startswith("e3-no-") else "scan"
                stats[case.group].extend(matched_stats(index, experiment, E3, case.name, baseline, kind))
        if "fixed-with-scan" in variants:
            stats[case.group].extend(matched_stats(index, experiment, "fixed-with-scan", case.name, "fixed-threshold", "scan"))
        if experiment.reference:
            kind = "power" if case.power == "paper" else "interference"
            for policy in MAIN:
                stats[case.group].extend(matched_stats(index, experiment, policy, experiment.reference, policy, kind))
    for group, paired in stats.items():
        destination = OUT / group
        save(destination / "seeds.csv", [r for r in rows if r["group"] == group])
        save(destination / "paired.csv", paired)
    save(OUT / "06-sensitivity/deferral-trajectory.csv", trajectory)
