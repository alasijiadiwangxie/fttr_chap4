"""Verify E1a/E1b independently, then produce the three compact E1 event and result tables."""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from dataclasses import replace
from hashlib import sha256
from itertools import product
from math import isclose
from pathlib import Path

from d5_experiments import FIXED, FULL, PERIODIC, RATE, RF, STEP, configuration
from e0_switch_metrics import CCA, PREDICT, by_candidate, diagnose_switches, wide_busy_ns
from e0_trace_metrics import account_trial, pon_lifecycle
from e0_trials import JAR, THRESHOLD, Trial, pon_fixture, run_inputs, runner, trials as e0_trials
from pon_experiment_io import ROOT, require
from verify_c1_ap_observation import csv_rows, time_ns
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import events


OUT = ROOT / "results/e1-optimization"
WORK = ROOT / "target/e1-final"
OPTIMIZED = "ecq-sdps-e1"
RECOVERY = "giant-complete-recovery"
# Chosen before any comparisons. The main 5 ms history matches the existing 5 ms decision cadence.
SEEDS = (6719, 9283)
WINDOWS = (5000, 10000, 20000)
KEY_CASES = (RF[2], STEP)
RADIO_CASES = (RF[2], STEP, RF[1], PERIODIC, RATE[0], RATE[2])


def radio_trial(case, seed, variant, group="radio", history=5000):
    policy = {"frozen": FULL, "fixed": FIXED}.get(variant, OPTIMIZED)
    settings = configuration(case, seed, policy, THRESHOLD)
    if policy == OPTIMIZED:
        settings["wifi.ap.control.e1.cca.window.us"] = str(history)
        settings["wifi.ap.control.e1.cca.enabled"] = str(variant not in ("urgent-only", "shadow")).lower()
        settings["wifi.ap.control.e1.urgent.guard.enabled"] = str(variant not in ("cca-only", "shadow")).lower()
    return Trial(group, case.name, seed, variant if variant != "shadow" else f"shadow-{history // 1000}ms", case.window_us, settings)


def optical_trials():
    old = [trial for trial in e0_trials() if trial.group.startswith("pon-")]
    impossible = Trial("pon-only", "single-1500-q700", 0, "frozen-giant", 2000, pon_fixture((1500,), 700, 8000))
    multi = pon_fixture((1750, 1750, 1000, 1530, 1100, 1750, 1900, 1750, 850, 1750, 1750, 1750), 2500, 8000)
    multi["packet.arrival.us"] = "25,50,65,85,240,400,510,610,730,815,960,1010"
    multi["fiber.uplink.tcont.alloc.ids"] = "103,104"
    multi["fiber.uplink.tcont.alloc.103.classes"] = "BE,BK"
    for key, value in {"sfu.id": "1", "type": "3", "classes": "VO,VI", "si.max.frames": "2", "si.min.frames": "1",
                       "ab.min.bytes": "2200", "ab.sur.bytes": "2200"}.items():
        multi[f"fiber.uplink.tcont.alloc.104.{key}"] = value
    for index in (3, 4, 7):
        multi[f"packet.{index}.type"] = "VI"
    across = Trial("pon-only", "multiple-allocations", 0, "frozen-giant", 2000, multi)
    for trial in (*old, impossible, across):
        yield replace(trial, variant="frozen")
        yield replace(trial, variant="recovery", settings=trial.settings | {"fiber.uplink.dba.mode": RECOVERY})
        if trial.group == "pon-joint":
            yield replace(trial, variant="both", settings=trial.settings | {
                "fiber.uplink.dba.mode": RECOVERY, "wifi.ap.control.policy": OPTIMIZED})


def cases():
    yield from optical_trials()
    for case, seed, variant in product(RADIO_CASES, SEEDS, ("frozen", "e1", "fixed")):
        yield radio_trial(case, seed, variant)
    for case, seed, variant in product(KEY_CASES, SEEDS, ("cca-only", "urgent-only")):
        yield radio_trial(case, seed, variant)
    # The frozen action path is used for the paired point-estimator comparison and sensitivity checks.
    for case, seed in product(KEY_CASES, SEEDS):
        for history in (WINDOWS if seed == SEEDS[0] else WINDOWS[:1]):
            yield radio_trial(case, seed, "shadow", "sensitivity", history)
    yield radio_trial(RF[2], 1201, "shadow", "e0-anchor")


def predictions(trial, rows):
    truth = defaultdict(list)
    for row in events(rows, CCA):
        for mode, mask in (("LCM", 1), ("HCM", 15)):
            truth[row["sfu_id"], mode].append(row | {"observed_mask": str(int(row["observed_mask"]) & mask)})
    by_estimate = by_candidate(rows, "WIFI_AP_E1_CCA")
    result = []
    for (sfu, now, mode), forecast in by_candidate(rows, PREDICT).items():
        if (sfu, mode) not in truth or now + int(forecast["horizon_ns"]) > trial.end_ns:
            continue
        horizon = int(forecast["horizon_ns"])
        true = wide_busy_ns(truth[sfu, mode], now, now + horizon) / horizon
        estimate = by_estimate.get((sfu, now, mode))
        effective = float(forecast["expected_busy_fraction"])
        window = int(estimate["history_ns"]) if estimate else None
        result.append(trial.identity() | dict(
            sfu_id=sfu, t_ns=now, horizon_ns=horizon, candidate_mode=mode, current_mode=forecast["current_mode"],
            periodic_sample=now > 0 and now % 5_000_000 == 0, actual_busy=true,
            applied_prediction=effective, applied_error=effective - true,
            legacy_sample=estimate.get("legacy_busy") if estimate else forecast["observed_busy_fraction"],
            legacy_expected=effective if not estimate or estimate["applied"] == "false" else None,
            window_history_ns=window, predicted_busy=float(estimate["expected_busy"]) if estimate else None,
            prediction_error=float(estimate["expected_busy"]) - true if estimate else None,
            coverage=int(estimate["known_ns"]) / window if estimate else None,
            full_coverage=int(estimate["fully_sensed_ns"]) / window if estimate else None,
            known_busy_ns=estimate.get("known_busy_ns") if estimate else None,
            newest_age_ns=estimate.get("newest_age_ns") if estimate else None,
            oldest_band_age_ns=estimate.get("oldest_band_age_ns") if estimate else None,
            missing_recent_band=estimate.get("missing_recent_band") if estimate else None,
            ongoing_busy_ns=estimate.get("ongoing_busy_ns") if estimate else None,
            longest_busy_ns=estimate.get("longest_busy_ns") if estimate else None,
            current_external_cca=estimate.get("current_external_cca") if estimate else None,
            physical_cca=estimate.get("physical_cca") if estimate else None,
            lower_busy=estimate.get("lower_busy") if estimate else None, upper_busy=estimate.get("upper_busy") if estimate else None,
            incomplete=estimate.get("incomplete") if estimate else forecast["channel_incomplete"]))
    return result


def optical_evidence(trial, rows, pending):
    # Unlike E0, show both versions' full isolated history and the same last stage of both integrated runs.
    parse_trial = replace(trial, group="pon-events") if trial.group == "pon-joint" else trial
    plan, _ = pon_lifecycle(parse_trial, rows, pending)
    giants = {(r["target_frame_id"], r["allocation_id"]): r for r in events(rows, "PON_GIANT_DECISION")}
    settles = {r["grant_id"]: r for r in events(rows, "PON_DBA_SETTLEMENT_RX")}
    limit = trial.end_ns - 15_000_000 if trial.group == "pon-joint" else 0
    for row in plan:
        if int(row["time_ns"]) < limit:
            continue
        decision = giants[row["target_frame_id"], row["allocation_id"]]
        if trial.group == "pon-joint" and decision["type"] != "3":
            continue
        settled = settles.get(row.get("grant_id"), {})
        yield trial.identity() | row | dict(group=trial.group,
            recovery_failed_bytes=decision.get("recovery_failed_bytes"), recovery_waiting=decision.get("recovery_waiting"),
            recovery_ceiling_bytes=decision.get("recovery_ceiling_bytes"), recovery_limit=decision.get("recovery_limit"),
            head_blocked=settled.get("head_blocked"), recovery_failed_after=settled.get("recovery_failed_after"))


def archived_e0():
    with (ROOT / "results/e0-diagnosis/e0-summary.csv").open(newline="", encoding="utf-8") as file:
        return {(row["scenario"], int(row["seed"]), row["variant"]): row for row in csv.DictReader(file)}


def check_reference(trial, actual, reference):
    variant = "frozen-giant" if trial.group == "pon-only" else "full"
    if trial.variant != "frozen" and trial.group != "e0-anchor":
        return False
    prior = reference.get((trial.scenario, trial.seed, variant))
    if not prior:
        return False
    require(prior["input_sha256"] == actual["input_sha256"], f"{trial.name}: E0 replay input changed")
    for key in ("generated", "delivered_final", "on_time_final", "total_uj", "drain_uj", "switches", "unsettled_final"):
        if prior.get(key):
            require(isclose(float(prior[key]), float(actual[key]), rel_tol=1e-12, abs_tol=1e-9),
                    f"{trial.name}: frozen replay differs in {key}")
    return True


def physical_bad_switches(probes):
    outcomes = [r for r in probes if r["status"] == "COMPLETED"]
    waste = [r for r in outcomes if r["short_window_evidence"] in ("DEADLINE_LOSS", "SERVICE_LOSS")
             or (r["short_window_evidence"] == "NO_QOS_OR_SERVICE_GAIN" and r["actual_energy_uj_delta"] >= -1e-6)]
    return dict(probed_switches=len(outcomes), short_window_no_net_benefit=len(waste),
                deadline_improving_switches=sum(r["short_window_evidence"] == "DEADLINE_GAIN" for r in outcomes))


def measure(trial, work, probe, jobs, reuse, reference):
    cfg = work / f"{trial.name}.properties"
    require(cfg.read_text(encoding="utf-8") == "".join(f"{key}={value}\n" for key, value in trial.settings.items()),
            f"{trial.name}: replay configuration differs")
    raw = cfg.with_suffix(".log").read_text(encoding="utf-8")
    rows = parse_trace(raw)
    summary, packets, delivered, pending = account_trial(trial, rows)
    summary["config_sha256"] = sha256(cfg.read_bytes()).hexdigest()
    summary["frozen_e0_matches"] = check_reference(trial, summary, reference)
    summary["wlan_policy"] = trial.settings.get("wifi.ap.control.policy")
    summary["dba_policy"] = trial.settings.get("fiber.uplink.dba.mode")
    if "total_uj" in summary:
        summary["business_settlement_uj"] = summary["total_uj"] + summary["drain_uj"]
    guard = events(rows, "WIFI_AP_E1_URGENT")
    summary["urgent_review_only"] = sum(r["action_gate"] == "REVIEW_ONLY" for r in guard)
    summary["urgent_proven_rescue"] = sum(r["action_gate"] == "DEADLINE_RESCUE" for r in guard)
    if trial.group.startswith("pon-"):
        ul = {packet for packet, data in packets.items() if data[3] == "UL"}
        delays = sorted((delivered[p] - packets[p][0]) / 1e6 for p in ul if p in delivered)
        summary.update(ul_unsettled=len(pending & ul), ul_delivered=len(delays),
                       ul_p99_ms=delays[(99 * len(delays) + 99) // 100 - 1] if delays else None,
                       zero_load_grants=sum(int(r["actual_bytes"]) == 0 and int(r["pending_bytes"]) > 0
                                            for r in events(rows, "PON_DBA_SLOT_END")),
                       recovery_drains=sum(r.get("recovery_waiting") == "true" for r in events(rows, "PON_GIANT_DECISION")),
                       recovery_limit_marked=any(r.get("recovery_limit") == "true" for r in events(rows, "PON_GIANT_DECISION")))
        return summary, [], list(optical_evidence(trial, rows, pending))
    if (trial.group == "radio" and trial.seed == SEEDS[0] and trial.scenario in {case.name for case in KEY_CASES}
            and trial.variant in ("frozen", "e1")):
        cache = work / f"{trial.name}.branches.json"
        if reuse and cache.exists():
            probes = json.loads(cache.read_text(encoding="utf-8"))
        else:
            probes = diagnose_switches(trial, raw, rows, packets, probe, work, jobs)
            cache.write_text(json.dumps(probes), encoding="utf-8")
        summary.update(physical_bad_switches(probes))
        require(summary["probed_switches"] == summary["switches"], f"{trial.name}: unpaired switch")
    return summary, predictions(trial, rows), []


def validate_outputs(summaries, prediction_rows):
    completed = {(row["scenario"], row["seed"], row["variant"]): row for row in summaries}
    for row in summaries:
        if row["group"].startswith("pon-") and row["variant"] == "recovery" and row["scenario"] != "single-1500-q700":
            require(row["ul_unsettled"] == 0, f"legal PON recovery regressed: {row['scenario']}/{row['seed']}")
    nofit = completed["single-1500-q700", 0, "recovery"]
    require(nofit["ul_unsettled"] == 1 and nofit["recovery_limit_marked"], "unserviceable quota was masked")
    anchor = next(row for row in prediction_rows if row["group"] == "e0-anchor" and row["t_ns"] == 35_984_000
                  and row["sfu_id"] == "2" and row["candidate_mode"] == "HCM")
    require(isclose(float(anchor["legacy_sample"]), 750 / 984) and isclose(anchor["actual_busy"], .15), "E0 anchor changed")
    for tr in (row for row in summaries if row["group"] == "sensitivity"):
        original = completed[tr["scenario"], tr["seed"], "frozen"]
        require(all(tr[key] == original[key] for key in ("switches", "total_uj", "drain_uj", "on_time_final", "p99_ms")),
                "shadow observation altered the frozen controller")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--reuse", action="store_true", help="Reuse raw Java traces and verified short-window replay data.")
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    runs = tuple(cases())
    commands = runner(JAR, args.work)
    if not args.reuse:
        run_inputs(runs, commands, args.work, args.jobs)
    performance, cca, pon = [], [], []
    paired = {}
    reference = archived_e0()
    for trial in runs:
        summary, estimates, grants = measure(trial, args.work, commands[1], args.jobs, args.reuse, reference)
        key = trial.scenario, trial.seed
        require(paired.setdefault(key, summary["input_sha256"]) == summary["input_sha256"], f"{trial.name}: unequal input")
        performance.append(summary)
        cca.extend(estimates)
        pon.extend(grants)
        print(f"Audited {trial.name}", flush=True)
    validate_outputs(performance, cca)
    for name, data in (("e1-performance", performance), ("e1-cca-prediction", cca), ("e1-pon-events", pon)):
        csv_rows(args.output / f"{name}.csv", data)
    print(f"{len(performance)} runs, {sum(row.get('probed_switches', 0) for row in performance)} paired hold replays.")


if __name__ == "__main__":
    main()
