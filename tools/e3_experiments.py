"""Run the selected E3 comparisons without writing to the E2 experiment directory."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
from hashlib import sha256

from e2_analyze import interval, save
from e2_design import ECQ as E1, FIXED, HCM, SEEDS as E2_SEEDS, arrivals, properties, study
from e2_metrics import NETWORK, summarize_run
from e3_diagnose import OUT, WORK, replace_once
from pon_experiment_io import ROOT, require
from verify_c1_ap_observation import time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import events
from verify_d1_mode_control import JAR

E3 = "ecq-sdps-e3"
DEVELOPMENT_SEEDS = (61363, 67211, 71287)
ACCEPTANCE_SEEDS = (74897, 78781, 82139, 87223, 91577, 96401)
SCENARIOS = ("load-031", "load-050", "load-085", "rf-primary-strong-35", "rf-secondary-strong-15",
             "rf-secondary-strong-35", "dynamic-periodic", "dynamic-near", "mechanisms")
SWITCHES = {"e3-no-capacity": "e3.capacity", "e3-no-economics": "e3.economics",
            "e3-no-channel": "complete.channel", "e3-no-qos": "complete.qos",
            "e3-no-deferral": "complete.deferral"}
COMMON = (FIXED, HCM, E1, E3)
PAIRED = ("goodput_mbps", "backlog_window", "complete_uj", "total_uj", "nj_per_final_bit", "on_time_pct",
          "p99_ms", "drop_pct", "unsettled_final", "switches", "hcm_pct", "deferrals_released")


def variants(case, suite):
    if suite == "development":
        return COMMON
    extra = ()
    if case.name != "load-031" and case.name != "load-085":
        extra = ("e3-no-capacity", "e3-no-economics")
    if case.name in ("mechanisms", "rf-secondary-strong-15", "load-031", "load-050"):
        extra += ("e3-no-deferral",)
    if case.name in ("mechanisms", "rf-secondary-strong-15"):
        extra += ("e3-no-channel", "e3-no-qos")
    return COMMON + extra


def config(case, seed, variant, packets):
    # All changes are controller treatments. Alternate configurations preserve the E2 PHY/PON/input.
    baseline = variant if variant in COMMON[:-1] else E1
    settings = properties(case, seed, baseline if baseline in case.variants else E1, packets)
    settings["wifi.ap.control.policy"] = baseline if variant in COMMON[:-1] else E3
    if variant in SWITCHES:
        settings["wifi.ap.control." + SWITCHES[variant] + ".enabled"] = "false"
    return settings


def runner(jar=JAR, original=False):
    token = "frozen-trace" if original else "experiment-trace"
    path = WORK / token
    path.mkdir(exist_ok=True, parents=True)
    original_entry = ROOT / "tools/java/E2Trace.java"
    source = original_entry.read_text(encoding="utf-8")
    if not original:
        source = replace_once(source, '"WIFI_EXTERNAL_CCA");',
                              '"WIFI_EXTERNAL_CCA", "WIFI_AP_D4_DECISION", "WIFI_AP_E3_DECISION",'
                              ' "WIFI_AP_ENERGY_MODE_DECISION");')
    entry = path / original_entry.name
    entry.write_text(source, encoding="utf-8")
    subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-cp", str(jar),
                    "-d", str(path), str(entry)], check=True)
    return ["java", "-Xmx675m", "-XX:ActiveProcessorCount=2", "-cp", os.pathsep.join((str(jar), str(path))), "E2Trace"]


def batch(case, seed, suite, command, version):
    folder = WORK / suite / case.name / str(seed)
    folder.mkdir(parents=True, exist_ok=True)
    packets = arrivals(case, seed)
    paths = {}
    pending = []
    checksums = {}
    for variant in variants(case, suite):
        path = folder / variant
        paths[variant] = path
        cfg = path.with_suffix(".properties")
        write_properties(cfg, config(case, seed, variant, packets))
        digest = sha256(version + cfg.read_bytes()).hexdigest()
        log, stamp = path.with_suffix(".log"), path.with_suffix(".sha256")
        if log.is_file() and stamp.is_file() and stamp.read_text(encoding="utf-8").strip() == digest:
            continue
        pending.extend((str(cfg), str(log)))
        checksums[stamp] = digest
    if pending:
        subprocess.run([*command, *pending], check=True, cwd=ROOT, timeout=240)
        for stamp, digest in checksums.items():
            stamp.write_text(digest + "\n", encoding="utf-8")
    return case, seed, paths


def decision_rows(rows, name, seed, variant):
    prediction = {(row["sfu_id"], time_ns(row), row["candidate_mode"]): row
                  for row in events(rows, "WIFI_AP_ENERGY_MODE_DECISION")}
    new = {(row["sfu_id"], time_ns(row)): row for row in events(rows, "WIFI_AP_E3_DECISION")}
    result = []
    for decision in events(rows, "WIFI_AP_D4_DECISION"):
        now, sfu, mode = time_ns(decision), decision["sfu_id"], decision["current_mode"]
        if decision["trigger"] != "PERIODIC" and decision["action"] not in ("REQUESTED", "DEFERRED", "CANCELLED"):
            continue
        keep = prediction[sfu, now, mode]
        change = prediction[sfu, now, "HCM" if mode == "LCM" else "LCM"]
        row = {k: decision[k] for k in ("trigger", "current_mode", "target_mode", "reason", "action", "keep_qos",
                                       "change_qos", "on_time_gain", "net_saving_uj", "margin_uj", "chosen_delay_ns")}
        row.update({k: v for k, v in new.get((sfu, now), {}).items() if k not in ("trace_sequence", "event")})
        row.update({label + key: data[key] for label, data in (("keep_", keep), ("change_", change))
                    for key in ("success_bits", "unserved_bits", "saturated_service_mbps", "expected_busy_fraction",
                                "channel_incomplete", "ampdu_depth")})
        row.update(scenario=name, seed=seed, policy=variant, time_ns=now, sfu_id=sfu, queued_packets=int(keep["dl_queue"]),
                   inflight_packets=int(keep["dl_inflight"]), demand_bits=keep["demand_bits"], ewma_demand_mbps=keep["ewma_mbps"])
        result.append(row)
    return result


def analyze(job):
    case, seed, paths = job
    records, decisions, timelines = [], [], []
    for variant, path in paths.items():
        representative = seed == ACCEPTANCE_SEEDS[0] and variant in (E1, E3)
        # The E2 reconciler uses its shared optical, energy, date and deadline settings. Treatment changes only the controller.
        meta, metrics, timeline = summarize_run(case, seed, E1, path, path.with_suffix(".sha256").read_text().strip(),
                                                representative)
        meta["policy"] = variant
        records.extend(meta | {k: float(v) if hasattr(v, "as_tuple") else v for k, v in metric.items()} for metric in metrics)
        if representative:
            rows = parse_trace(path.with_suffix(".log").read_text(encoding="utf-8"))
            decisions.extend(decision_rows(rows, case.name, seed, variant))
            timelines.extend(meta | item for item in timeline)
    require(len({r["input_sha256"] for r in records}) == 1, f"Divergent paired input for {case.name}/{seed}")
    return records, decisions, timelines


def paired(records, seeds):
    groups = {}
    for row in records:
        if row["sfu_id"] != "ALL" or row["direction"] != "BOTH":
            continue
        groups.setdefault((row["scenario"], row["ac"]), {}).setdefault(row["policy"], {})[row["seed"]] = row
    result = []
    for (scenario, ac), policies in groups.items():
        candidate = policies[E3]
        for name, control in policies.items():
            if name == E3:
                continue
            require(set(candidate) == set(control) == set(seeds), f"E3 unpaired {scenario}/{ac}/{name}")
            for key in PAIRED:
                finite = [(candidate[seed][key], control[seed][key]) for seed in seeds
                          if candidate[seed].get(key) is not None and control[seed].get(key) is not None]
                if finite:
                    result.append(dict(scenario=scenario, ac=ac, baseline=name, metric=key)
                                  | interval([change - base for change, base in finite]))
                    if key in ("total_uj", "complete_uj", "nj_per_final_bit") and all(base > 0 for _, base in finite):
                        result.append(dict(scenario=scenario, ac=ac, baseline=name, metric=key + "_relative_pct")
                                      | interval([100 * (change - base) / base for change, base in finite]))
    return result


def frozen_check(frozen_jar):
    reference, current = runner(frozen_jar, original=True), runner(JAR, original=True)
    directory = WORK / "baseline-check"
    directory.mkdir(exist_ok=True)
    check = []
    for case in (c for c in study() if c.name in ("load-050", "rf-secondary-strong-35", "dynamic-periodic")):
        seed = E2_SEEDS[0]
        settings = config(case, seed, E1, arrivals(case, seed))
        e1 = directory / f"{case.name}.properties"
        disabled = directory / f"{case.name}.off.properties"
        write_properties(e1, settings)
        write_properties(disabled, settings | {"wifi.ap.control.policy": E3,
                          "wifi.ap.control.e3.capacity.enabled": "false", "wifi.ap.control.e3.economics.enabled": "false"})
        old, new, off = (directory / f"{case.name}.{label}.log" for label in ("frozen", "e1", "e3-both-off"))
        subprocess.run([*reference, str(e1), str(old)], check=True, timeout=120)
        subprocess.run([*current, str(e1), str(new), str(disabled), str(off)], check=True, timeout=120)
        require(old.read_bytes() == new.read_bytes(), f"Frozen E1 regression: {case.name}")
        require(new.read_bytes() == off.read_bytes().replace(E3.encode(), E1.encode()),
                f"Two disabled refinements changed E1 physical events: {case.name}")
        check.append(dict(scenario=case.name, seed=seed, frozen_and_current_match=True, both_disabled_matches=True,
                          trace_sha256=sha256(new.read_bytes()).hexdigest()))
    save(OUT / "baseline-check.csv", check)


def run_suite(suite, workers):
    seeds = DEVELOPMENT_SEEDS if suite == "development" else ACCEPTANCE_SEEDS
    require(len(set(E2_SEEDS + DEVELOPMENT_SEEDS + ACCEPTANCE_SEEDS)) == len(E2_SEEDS + DEVELOPMENT_SEEDS + ACCEPTANCE_SEEDS),
            "E2, development and acceptance must have independent seeds")
    cases = [case for case in study() if case.name in SCENARIOS]
    command = runner()
    signature = sha256(JAR.read_bytes() + (ROOT / "tools/e3_experiments.py").read_bytes()).digest()
    print(f"E3 {suite}: {len(cases)} scenarios, {len(seeds)} paired seeds, "
          f"{sum(len(variants(case, suite)) for case in cases) * len(seeds)} Java runs", flush=True)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(batch, case, seed, suite, command, signature) for case in cases for seed in seeds]
        jobs = []
        for i, future in enumerate(futures, 1):
            jobs.append(future.result())
            if i % 6 == 0 or i == len(futures):
                print(f"E3 replay {i}/{len(futures)} paired batches", flush=True)
    records, decisions, timeline = [], [], []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for metrics, details, modes in pool.map(analyze, jobs, chunksize=1):
            records.extend(metrics)
            decisions.extend(details)
            timeline.extend(modes)
    output = WORK if suite == "development" else OUT
    save(output / f"{suite}-performance.csv", records)
    save(output / f"{suite}-paired.csv", paired(records, seeds))
    if decisions:
        save(output / "decisions.csv", decisions)
        save(output / "mode-timeline.csv", timeline)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("development", "acceptance"), default="acceptance")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--compare-frozen", type=str)
    args = parser.parse_args()
    if args.compare_frozen:
        from pathlib import Path

        frozen_check(Path(args.compare_frozen).resolve())
    else:
        run_suite(args.suite, args.workers)
