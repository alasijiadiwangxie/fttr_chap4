"""Calibrate the hysteresis baseline, then replay the registered E4 study in Java."""

from __future__ import annotations

import argparse
import csv
import os
import subprocess
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path
from statistics import mean
from time import monotonic

from e2_analyze import save
from e2_design import SEEDS as E2_SEEDS, PILOT_SEEDS as E2_PILOT
from e2_metrics import NETWORK, summarize_run
from e3_diagnose import replace_once
from e3_experiments import ACCEPTANCE_SEEDS as E3_SEEDS, DEVELOPMENT_SEEDS as E3_DEVELOPMENT
from e4_design import CALIBRATION_SEEDS, E3, FIXED, FORMAL_SEEDS, HYST, formal, packet_fixture, settings, training
from pon_experiment_io import ROOT, require
from verify_c1_ap_observation import time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import events
from verify_d1_mode_control import JAR

WORK = ROOT / "target/e4-thesis"
OUT = ROOT / "results/e4-thesis"
CUTOFF_PERCENT = 0.99
PRIORITY_GAP_PP = 1.0


def compile_runner(jar=JAR):
    source = ROOT / "tools/java/E2Trace.java"
    classes = WORK / "projection"
    classes.mkdir(exist_ok=True, parents=True)
    entry = classes / source.name
    entry.write_text(replace_once(source.read_text(encoding="utf-8"), '"WIFI_EXTERNAL_CCA");',
                                  '"WIFI_EXTERNAL_CCA", "WIFI_AP_MODE_DECISION", "WIFI_AP_E3_DECISION");'), encoding="utf-8")
    subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes),
                    str(entry)], check=True)
    return ["java", "-Xmx800m", "-XX:ActiveProcessorCount=2", "-cp", os.pathsep.join((str(jar), str(classes))), "E2Trace"]


def trial(experiment, seed, band, suite, runner, version):
    directory = WORK / suite / experiment.case.name / str(seed)
    directory.mkdir(exist_ok=True, parents=True)
    fixture = packet_fixture(experiment, seed)
    stems, new, hashes = {}, [], {}
    common = {}
    for policy in experiment.policies:
        stem = directory / policy
        cfg = settings(experiment, seed, policy, fixture, band)
        treatment_keys = ("wifi.ap.control.", "wifi.channel.scan.")
        underlying = {k: v for k, v in cfg.items() if not k.startswith(treatment_keys)}
        require(not common or common == underlying, f"Unpaired physical/traffic parameters: {directory}")
        common = underlying
        stems[policy] = stem
        props, log, stamp = (stem.with_suffix(suffix) for suffix in (".properties", ".log", ".sha256"))
        write_properties(props, cfg)
        signature = sha256(version + props.read_bytes()).hexdigest()
        if log.is_file() and stamp.is_file() and stamp.read_text(encoding="utf-8").strip() == signature:
            continue
        new.extend((str(props), str(log)))
        hashes[stamp] = signature
    if new:
        subprocess.run([*runner, *new], check=True, timeout=420, cwd=ROOT)
        for stamp, signature in hashes.items():
            stamp.write_text(signature + "\n", encoding="utf-8")
    return experiment, seed, band, suite, stems


def threshold_trace(rows, policy, band):
    if not policy.startswith(HYST):
        return
    lo, hi = map(int, policy.split("-")[1:]) if policy != HYST else band
    last, previous = {}, {}
    for row in events(rows, "WIFI_AP_MODE_INITIAL"):
        last[row["sfu_id"]] = row["current_mode"]
    for row in events(rows, "WIFI_AP_MODE_DECISION"):
        sfu = row["sfu_id"]
        offered = float(row["arrival_mbps"])
        estimate = offered if sfu not in previous else previous[sfu] + .25 * (offered - previous[sfu])
        require(abs(estimate - float(row["ewma_mbps"])) < 1e-10, "Hysteresis EWMA differed from D1")
        previous[sfu] = estimate
        last[sfu] = "HCM" if estimate > hi else "LCM" if estimate < lo else last[sfu]
        require(row["target_mode"] == last[sfu], "Illegal hysteresis target")


def control_metrics(rows, end):
    completed = [r for r in events(rows, "WIFI_AP_SWITCH_END") if time_ns(r) <= end]
    intervals = [(r["sfu_id"], r["from_mode"], r["to_mode"], int(r["switch_start_ns"]), time_ns(r)) for r in completed]
    finish_times = {(r["sfu_id"], r["switch_id"]): time_ns(r) for r in completed}
    releases = [r for r in events(rows, "WIFI_AP_HCM_DEFER") if r["phase"] == "RELEASE" and time_ns(r) <= end]
    attached = [r for r in releases if (r["sfu_id"], r["switch_id"]) in finish_times]
    sizes = Counter((time_ns(r), r["sfu_id"], r["type"], r["sta_id"]) for r in events(rows, "WIFI_MAC_ATTEMPT")
                    if r["direction"] == "DL" and time_ns(r) <= end)
    require(all(1 <= size <= 8 for size in sizes.values()), "An actual DL PPDU exceeded the MPDU budget")
    decisions = Counter(r["reason"] for r in events(rows, "WIFI_AP_E3_DECISION") if time_ns(r) <= end)
    periodic = [r for r in events(rows, "WIFI_AP_E3_DECISION") if r["trigger"] == "PERIODIC" and time_ns(r) <= end]
    stats = dict(dl_ppdu_attempts=len(sizes), dl_mean_mpdus_per_ppdu=mean(sizes.values()) if sizes else None,
                 switch_trace_sha256=sha256(repr(intervals).encode()).hexdigest(),
                 first_switch_ns=intervals[0][-1] if intervals else None, executed_deferrals=len(attached),
                 capacity_requests=sum(r["reason"] == "CAPACITY_RECOVERY" and r["action"] == "REQUESTED" for r in periodic),
                 capacity_limited_holds=sum(int(r["deficit_windows"]) >= 2 and r["action"] == "HOLD" for r in periodic),
                 uncertain_capacity_reviews=sum(r["capacity_evidence"] == "INSUFFICIENT_INFORMATION" for r in periodic),
                 priority_access_holds=decisions["KEEP_PRIORITY_ACCESS"], economic_guard_holds=decisions["ECONOMIC_UNCERTAINTY"],
                 unproven_qos_holds=decisions["HOLD_UNPROVEN_QOS"])
    return stats, intervals


def audit(job):
    exp, seed, band, suite, paths = job
    fixture = packet_fixture(exp, seed)
    data, traces = [], {}
    for policy, stem in paths.items():
        cfg = settings(exp, seed, policy, fixture, band)
        rows = parse_trace(stem.with_suffix(".log").read_text(encoding="utf-8"))
        threshold_trace(rows, policy, band)
        meta, metrics, _ = summarize_run(exp.case, seed, cfg["wifi.ap.control.policy"], stem,
                                         stem.with_suffix(".sha256").read_text().strip(), False,
                                         fixture=fixture, cfg=cfg, rows=rows)
        meta.update(exp.metadata(), policy=policy, scanned="wifi.channel.scan.schedule" in cfg,
                    hyst_lower_mbps=cfg.get("wifi.ap.control.hysteresis.lower.mbps"),
                    hyst_upper_mbps=cfg.get("wifi.ap.control.hysteresis.upper.mbps"),
                    vo_deadline_us=cfg["wifi.ap.qos.VO.deadline.us"], vi_deadline_us=cfg["wifi.ap.qos.VI.deadline.us"])
        extra, intervals = control_metrics(rows, exp.case.window_us * 1000)
        for metric in metrics:
            record = {k: float(v) if hasattr(v, "as_tuple") else v for k, v in metric.items()} | meta
            if tuple(record[k] for k in ("sfu_id", "direction", "ac")) == NETWORK:
                if not meta["scanned"]:
                    require(record["scan_uj"] == 0, "Scan-free policy consumed scan energy")
                record.update(extra)
            data.append(record)
        if "e3-no-deferral" in paths and policy in (E3, "e3-no-deferral"):
            traces[policy] = intervals
    require(len({r["input_sha256"] for r in data}) == 1, f"Different actual Java inputs for {exp.case.name}/{seed}")
    return data, traces


def read_rows(path):
    with path.open(encoding="utf-8", newline="") as data:
        return list(csv.DictReader(data))


def select_band(rows):
    cells = {(r["scenario"], r["seed"], r["policy"], r["ac"]): r for r in rows if r["sfu_id"] == "ALL" and r["direction"] == "BOTH"}
    def get(scenario, seed, policy, ac, metric):
        return cells[scenario, seed, policy, ac][metric]

    scores = []
    for policy in sorted({r["policy"] for r in rows if r["policy"] != FIXED}):
        comparisons = []
        for experiment in training():
            scenario = experiment.case.name
            for seed in CALIBRATION_SEEDS:
                ratio = get(scenario, seed, policy, "ALL", "goodput_mbps") / get(scenario, seed, FIXED, "ALL", "goodput_mbps")
                energy = get(scenario, seed, policy, "ALL", "complete_uj") / get(scenario, seed, FIXED, "ALL", "complete_uj")
                margins = {ac: get(scenario, seed, policy, ac, "on_time_pct") - get(scenario, seed, FIXED, ac, "on_time_pct")
                           for ac in ("VO", "VI")}
                comparisons.append(dict(scenario=scenario, seed=seed, policy=policy, goodput_ratio=ratio,
                                        final_energy_ratio=energy, vo_delta_pp=margins["VO"], vi_delta_pp=margins["VI"],
                                        switches=get(scenario, seed, policy, "ALL", "switches")))
        # Protect each training scenario's mean, then minimize full-cohort AP energy across the paired inputs.
        shortfalls = []
        for scenario in {r["scenario"] for r in comparisons}:
            group = [r for r in comparisons if r["scenario"] == scenario]
            shortfalls.extend((100 * (CUTOFF_PERCENT - mean(r["goodput_ratio"] for r in group)),
                              *(-PRIORITY_GAP_PP - mean(r[key] for r in group) for key in ("vo_delta_pp", "vi_delta_pp"))))
        lo, hi = map(int, policy.split("-")[1:])
        scores.append(dict(policy=policy, lower_mbps=lo, upper_mbps=hi, training_pairs=len(comparisons),
                           largest_constraint_shortfall_pp=max(0, *shortfalls),
                           mean_final_energy_ratio=mean(r["final_energy_ratio"] for r in comparisons),
                           mean_switches=mean(r["switches"] for r in comparisons)))
    eligible = [row for row in scores if row["largest_constraint_shortfall_pp"] <= 1e-10]
    choices = eligible or scores
    best = min(choices, key=lambda r: (r["largest_constraint_shortfall_pp"] if not eligible else 0,
                                      r["mean_final_energy_ratio"], r["mean_switches"], r["lower_mbps"]))
    for row in scores:
        row["selected"] = row is best
        row["constraints_met"] = row["largest_constraint_shortfall_pp"] <= 1e-10
    return scores


def replay(suite, workers, band=None):
    cases, seeds = (training(), CALIBRATION_SEEDS) if suite == "calibration" else (tuple(formal()), FORMAL_SEEDS)
    all_seeds = CALIBRATION_SEEDS + FORMAL_SEEDS + E2_SEEDS + E2_PILOT + E3_SEEDS + E3_DEVELOPMENT
    require(len(all_seeds) == len(set(all_seeds)), "Development/acceptance seed sets overlap")
    runner = compile_runner()
    version = sha256(JAR.read_bytes() + (ROOT / "tools/java/E2Trace.java").read_bytes() + Path(__file__).read_bytes()).digest()
    start = monotonic()
    print(f"E4 {suite}: {sum(len(e.policies) for e in cases) * len(seeds)} runs with {len(seeds)} paired seeds", flush=True)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        tasks = [pool.submit(trial, exp, seed, band, suite, runner, version) for exp in cases for seed in seeds]
        recorded = []
        for i, task in enumerate(tasks, 1):
            recorded.append(task.result())
            if i % 15 == 0 or i == len(tasks):
                print(f"E4 {suite}: {i}/{len(tasks)} paired batches, {monotonic() - start:.1f}s", flush=True)
    rows, trajectory = [], []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for i, (batch, paths) in enumerate(pool.map(audit, recorded, chunksize=1), 1):
            rows.extend(batch)
            if paths:
                left, right = paths[E3], paths["e3-no-deferral"]
                net = {r["policy"]: r for r in batch if (r["sfu_id"], r["direction"], r["ac"]) == NETWORK}
                ix = next((k for k in range(max(len(left), len(right))) if left[k:k+1] != right[k:k+1]), None)
                if ix is not None:
                    a, b = left[ix:ix+1], right[ix:ix+1]
                else:
                    a = b = ()
                trajectory.append(dict(scenario=batch[0]["scenario"], seed=batch[0]["seed"], actual_path_changed=ix is not None,
                                       releases=net[E3]["deferrals_released"], executed=net[E3]["executed_deferrals"],
                                       cancellation=net[E3]["deferrals_cancelled"], active_delay_us=net[E3]["active_delay_us"],
                                       changed_switch_e3=str(a[0]) if a else "", changed_switch_no_deferral=str(b[0]) if b else "",
                                       observed_mpdus_per_ppdu=net[E3]["dl_mean_mpdus_per_ppdu"],
                                       e3_trace=net[E3]["trace_file"], no_deferral_trace=net["e3-no-deferral"]["trace_file"]))
            if i % 30 == 0 or i == len(recorded):
                print(f"E4 {suite}: audited {i}/{len(recorded)}", flush=True)
    if suite == "calibration":
        scores = select_band(rows)
        save(OUT / "hysteresis-calibration.csv", scores)
        # Small per-seed table supports independent recomputation of the threshold selection.
        save(OUT / "calibration-seeds.csv", [r for r in rows if r["sfu_id"] == "ALL" and r["direction"] == "BOTH"
                                           and r["ac"] in ("ALL", "VO", "VI")])
        return next((r["lower_mbps"], r["upper_mbps"]) for r in scores if r["selected"])
    from e4_stats import export

    export(rows, trajectory)


def frozen_band():
    selected, = (r for r in read_rows(OUT / "hysteresis-calibration.csv") if r["selected"] == "True")
    return int(selected["lower_mbps"]), int(selected["upper_mbps"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("all", "calibration", "formal"), default="all")
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args()
    if args.suite in ("all", "calibration"):
        band = replay("calibration", args.workers)
        print(f"E4 selected and froze hysteresis {band} Mb/s from calibration only.", flush=True)
    if args.suite in ("all", "formal"):
        replay("formal", args.workers, frozen_band())
