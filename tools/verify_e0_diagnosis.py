"""Generate the E0 diagnostics from current Java events and paired hold replays."""

from __future__ import annotations

import argparse
import csv
import json
from hashlib import sha256
from math import isclose
from pathlib import Path

from d5_experiments import D4, FIXED, FULL
from e0_switch_metrics import diagnose_switches
from e0_trace_metrics import account_trial, deferral_records, pon_lifecycle, voice_outcomes
from e0_trials import JAR, OUT, WORK, run_inputs, runner, trials
from pon_experiment_io import require
from verify_c1_ap_observation import csv_rows
from verify_c2b1_sinr_per import parse_trace
from verify_d5_ecq_sdps import OUT as D5_RESULTS


def d5_reference():
    frozen = {}
    for name in ("d5-performance.csv", "d5-ablation.csv"):
        with (D5_RESULTS / name).open(encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                if (row["ac"], row["direction"], row["sfu_id"]) == ("ALL", "BOTH", "ALL"):
                    frozen[row["scenario"], int(row["seed"]), row["policy"], row["disabled"]] = row
    return frozen


def compare_baseline(trial, summary, reference):
    if trial.group == "switch" and trial.variant != "periodic-qos":
        policy = {"d4": D4, "threshold": FIXED}.get(trial.variant, FULL)
        feature = {"no-channel": "channel", "no-qos": "qos"}.get(trial.variant, "")
        key = trial.scenario, trial.seed, policy, feature
    elif trial.scenario == "d5-primary-anchor":
        key = "defer-primary", trial.seed, FULL, "deferral" if trial.variant == "immediate" else ""
    elif trial.group == "pon-joint":
        key = trial.scenario, trial.seed, FULL, ""
    else:
        return None
    previous = reference[key]
    require(summary["input_sha256"] == previous["input_sha256"], f"{trial.name}: D5 input changed")
    for field in ("generated", "delivered_window", "delivered_final", "dropped_final", "unsettled_final", "on_time_final",
                  "backlog_window", "switches", "drain_ns", "total_uj", "drain_uj", "p95_ms", "p99_ms"):
        require(isclose(float(summary[field]), float(previous[field]), rel_tol=1e-12, abs_tol=1e-9),
                f"{trial.name}: {field} differs from D5")
    return True


def write_qos_comparisons(outcomes):
    changes = []
    for (scenario, seed, variant), result in outcomes.items():
        if variant not in ("no-qos", "no-channel", "periodic-qos"):
            continue
        full = outcomes[scenario, seed, "full"]
        require(full.keys() == result.keys(), f"{scenario}: missing voice packet in comparison")
        for packet, compare in result.items():
            actual = full[packet]
            if compare["on_time"] != actual["on_time"]:
                id_fields = ("ac", "sfu_id", "generated_ns", "deadline_ns")
                changes.append(dict(scenario=scenario, seed=seed, variant=variant, packet_id=packet) |
                               {key: actual[key] for key in id_fields} |
                               {f"full_{key}": value for key, value in actual.items() if key not in id_fields} |
                               {f"comparison_{key}": value for key, value in compare.items() if key not in id_fields})
    return changes


def check_pon_cycle(rows):
    cycle = [r for r in rows if r["scenario"] == "two-1500-q2400" and r.get("grant_id")]
    require(len(cycle) > 5 and cycle[0]["budget_bytes"] == "2400" and cycle[0]["actual_bytes"] == "1500",
            "E0 fixture did not reach the two-packet partial first grant")
    for index, row in enumerate(cycle[1:]):
        require(row["budget_bytes"] == ("600" if index % 2 == 0 else "900") and row["actual_bytes"] == "0"
                and row["head_bytes"] == "1500" and int(row["frame_idle_after_budget_ns"]) >= 4800,
                "E0 two-packet fixture failed to replay the sub-packet grant cycle")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--reuse", action="store_true", help="Use existing Java logs and verified branch cache in --work.")
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    cases = tuple(trials())
    commands = runner(JAR, args.work)
    if not args.reuse:
        run_inputs(cases, commands, args.work, args.jobs)
    reference = d5_reference()
    summaries, switches, deferrals, pon = [], [], [], []
    outcomes, pair_inputs = {}, {}
    for trial in cases:
        config = args.work / f"{trial.name}.properties"
        require(config.read_text(encoding="utf-8") == "".join(f"{key}={value}\n" for key, value in trial.settings.items()),
                f"{trial.name}: existing configuration differs from the defined trial")
        raw = config.with_suffix(".log").read_text(encoding="utf-8")
        events = parse_trace(raw)
        summary, packets, delivery, pending = account_trial(trial, events)
        key = trial.scenario, trial.seed
        require(pair_inputs.setdefault(key, summary["input_sha256"]) == summary["input_sha256"], f"{trial.name}: unpaired input")
        summary["config_sha256"] = sha256(config.read_bytes()).hexdigest()
        summary["d5_reference_matches"] = compare_baseline(trial, summary, reference)
        if trial.group == "switch":
            outcomes[trial.scenario, trial.seed, trial.variant] = voice_outcomes(trial, events, packets, delivery)
            if trial.variant == "full":
                cache = args.work / f"{trial.name}.branches.json"
                if args.reuse and cache.exists():
                    branch = json.loads(cache.read_text(encoding="utf-8"))
                else:
                    branch = diagnose_switches(trial, raw, events, packets, commands[1], args.work, args.jobs)
                    cache.write_text(json.dumps(branch), encoding="utf-8")
                require(sum(r["status"] == "COMPLETED" for r in branch) == summary["switches"] and
                        all(r.get("prefix_sha256") for r in branch if r["status"] == "COMPLETED"),
                        f"{trial.name}: incomplete switch diagnosis")
                switches.extend(branch)
        elif trial.group == "deferral":
            detail, counts = deferral_records(trial, events)
            deferrals.extend(detail)
            summary.update(counts)
        else:
            detail, counts = pon_lifecycle(trial, events, pending)
            pon.extend(detail)
            summary.update(counts)
        summaries.append(summary)
    checks = sum(bool(row["d5_reference_matches"]) for row in summaries)
    check_pon_cycle(pon)
    changes = write_qos_comparisons(outcomes)
    for name, rows in (("e0-summary", summaries), ("e0-switches", switches), ("e0-qos-changes", changes),
                       ("e0-deferral", deferrals), ("e0-pon-timeline", pon)):
        csv_rows(args.output / f"{name}.csv", rows)
        print(f"{name}: {len(rows)} rows")
    print(f"{len(cases)} Java runs, {checks} matched D5 controls, "
          f"{sum(r['status'] == 'COMPLETED' for r in switches)} fingerprinted hold replays.")


if __name__ == "__main__":
    main()
