"""Compare the five controllers on paired Java inputs and independently audit D4 decisions."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from hashlib import sha256
from itertools import product
from math import isclose
from pathlib import Path
from statistics import median

from pon_experiment_io import ROOT, require, simulate, write_csv
from verify_c1_ap_observation import time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import endpoint_state, events, packet_inputs, verify_queue
from verify_d1_mode_control import INITIAL, JAR, SEEDS, SWITCH_EVENTS, audit_switches
from verify_d2_energy_decision import D2_DECISION, audit_controller, base
from verify_d3_qos_deferral import (ACS, DEFER, EVENT_FIELDS, IDENTITY, MAIN, METRIC_FIELDS, POLICIES as D3_POLICIES,
                                     QOS, audit_deferrals, audit_qos, per_ap_accounting, report_metrics, scenario_config)

OUT = ROOT / "results/d4-economic-residency"
WORK = ROOT / "target/d4-economic-residency"
OLD, NEW = "ecq-sdps", "ecq-sdps-d4"
POLICIES = (*D3_POLICIES, NEW)
DECISION = "WIFI_AP_D4_DECISION"
RESIDE = "WIFI_AP_D4_RESIDENCY"
EXPIRED = "WIFI_AP_D4_EXPIRED_INGRESS"
START, END = "WIFI_AP_SWITCH_START", "WIFI_AP_SWITCH_END"
EXTRA_EVENTS = ("qos_target", "keep_qos", "change_qos", "residency_start_ns", "residency_until_ns", "economic_residency_ns",
                "live_packets", "expired_packets", "live_ingress", "expired_ingress", "decisive_ac", "on_time_gain",
                "clear_qos_gain", "urgent_packet_id", "urgent_slack_ns", "allowed_delay_ns", "chosen_delay_ns",
                "deferred_batches", "deferred_bits", "keep_uj", "change_uj", "return_reserve_uj", "round_trip_premium_uj",
                "saving_w", "net_saving_uj", "margin_uj", "forecast_residency_ns", "scan_power_w", "keep_scan_uj",
                "change_scan_uj", "immediate_uj", "deferred_uj", "residence_before_switch_ns")
EXTRA_METRICS = ("urgencies_from_expired_only", "urgent_switches", "both_infeasible_switches", "switches_with_expired_queue",
                 "reversals_under_1ms", "median_completed_residence_ns", "residency_holds", "reverse_hysteresis_holds",
                 "unproven_qos_holds", "urgent_exceptions", "expired_ingress_skips")


def configurations(smoke):
    for name, seed, policy in product(MAIN, SEEDS[:1] if smoke else SEEDS, POLICIES):
        yield name, seed, policy, scenario_config(name, seed, policy)

    seed = SEEDS[0]
    for name, span in (("idle", "5000"), ("idle-short", "832.947")):
        settings = base(seed, NEW, 6000)
        del settings["packet.bytes"]
        settings.update({"traffic.mode": "poisson", "traffic.duration.us": "6000", "traffic.sfu.1.rate.pps": "0",
                         "sfu.1.wifi.ap.mode": "HCM", "wifi.ap.prediction.window.us": span})
        yield name, seed, NEW, settings

    for policy in POLICIES:
        settings = base(seed, policy, 80000)
        # Each segment crosses the fixed 31 Mb/s threshold on a common clock.
        arrivals = [str(t) for segment, rate in enumerate((27, 35, 29, 33, 28, 34, 30, 32))
                    for t in range(segment * 10000, (segment + 1) * 10000, round(12000 / rate))]
        yield "near-threshold", seed, policy, settings | {"packet.arrival.us": ",".join(arrivals)}

    diagnostic = {
        "urgent": ("0,400,800", {"wifi.ap.qos.VO.deadline.us": "254.225", "wifi.ap.control.residency.minimum.us": "5000"}),
        "infeasible": ("0,70,160", {"wifi.ap.qos.VO.deadline.us": "120"}),
        "expired": ("0", {"wifi.ap.qos.VO.deadline.us": "254.225", "fiber.delay.us": "300"}),
        "no-useful-delay": ("0", {"wifi.ap.qos.BE.deadline.us": "295", "wifi.ap.control.residency.minimum.us": "0"}),
    }
    for name, policy in product(diagnostic, (OLD, NEW)):
        arrivals, options = diagnostic[name]
        settings = base(seed, policy, 1500)
        settings.update({f"wifi.mac.edca.{ac}.cwmin": "0" for ac in ACS})
        if name != "no-useful-delay":
            settings.update({f"packet.{index}.type": "VO" for index in range(1, len(arrivals.split(",")) + 1)})
        yield name, seed, policy, settings | options | {"packet.arrival.us": arrivals}


def annotate_decisions(name, rows, packets, settings, policy):
    """Replay the actual FIFO so the expiry diagnosis uses packet ages rather than aggregate lateness."""
    waiting, arrivals, observations, scans = defaultdict(set), defaultdict(list), {}, defaultdict(list)
    qos_pairs = defaultdict(dict)
    deadlines = {p: data[0] + int(Decimal(settings[f"wifi.ap.qos.{data[2]}.deadline.us"]) * 1000)
                 for p, data in packets.items()}
    for row in events(rows, QOS):
        qos_pairs[row["sfu_id"], time_ns(row)][row["candidate_mode"]] = row["qos_status"]
    projected = {(row["sfu_id"], time_ns(row)): row["projected_mode"] for row in events(rows, D2_DECISION)}
    for row in rows:
        kind = row.get("event")
        if kind not in ("WIFI_AP_QUEUE_EVENT", QOS, DECISION, EXPIRED, "WIFI_CHANNEL_SCAN"):
            continue
        sfu, t = row["sfu_id"], time_ns(row)
        if kind == "WIFI_AP_QUEUE_EVENT" and row["direction"] == "DL":
            packet, action = row["packet_id"], row["action"]
            if action in ("ENQUEUED", "REQUEUED"):
                waiting[sfu].add(packet)
                arrivals[sfu, t].append(packet)
            elif action in ("TX_START", "RETRANSMIT_START"):
                waiting[sfu].remove(packet)
        elif kind == "WIFI_CHANNEL_SCAN" and row["phase"] in ("START", "SKIP_MODE"):
            scans[sfu].append((t, int(row["ended_ns"])))
        elif kind == EXPIRED:
            require(t >= deadlines[row["packet_id"]], f"{name}: future deadline treated as expired")
        elif kind == DECISION or (policy != NEW and kind == QOS and row["candidate_mode"] == row["target_mode"]):
            expired = sum(deadlines[packet] <= t for packet in waiting[sfu])
            expired_ingress = sum(deadlines[packet] <= t for packet in arrivals[sfu, t])
            live = len(waiting[sfu]) - expired
            live_ingress = len(arrivals[sfu, t]) - expired_ingress
            data = dict(live_packets=live, expired_packets=expired, live_ingress=live_ingress, expired_ingress=expired_ingress)
            if kind == DECISION:
                require(int(row["live_packets"]) == live and int(row["expired_packets"]) == expired,
                        f"{name}: online deadline membership differs from queue replay")
                require(row["trigger"] != "URGENT_QUEUE" or live_ingress > 0, f"{name}: expired-only urgency")
                require(0 <= int(row["chosen_delay_ns"]) <= int(row["allowed_delay_ns"]), f"{name}: illegal delay")
                if int(row["chosen_delay_ns"]) > 0:
                    require(int(row["deferred_batches"]) > 0 and float(row["deferred_uj"]) < float(row["immediate_uj"]),
                            f"{name}: delayed without energy advantage or a complete batch")
                horizon = int(Decimal(settings.get("wifi.ap.prediction.window.us", "5000")) * 1000)
                scan_work = sum(max(0, min(t, end) - max(t - horizon, start)) for start, end in scans[sfu])
                scan_rate = float(settings.get("wifi.channel.scan.power.w", "0")) * scan_work / horizon
                require(isclose(float(row["scan_power_w"]), scan_rate, abs_tol=1e-12), f"{name}: noncausal scan forecast")
                if row["action"] == "REQUESTED":
                    require(t >= int(row["residency_until_ns"]) or row["reason"] == "URGENT_EXCEPTION",
                            f"{name}: request bypasses residence without verified urgency")
                    if row["reason"] == "URGENT_EXCEPTION":
                        packet = row["urgent_packet_id"]
                        require(row["clear_qos_gain"] == "true" and packet in waiting[sfu] and deadlines[packet] > t,
                                f"{name}: urgency bypass did not rescue an eligible packet")
            else:
                pair = qos_pairs[sfu, t]
                keep = projected[sfu, t]
                data.update(current_mode=keep, keep_qos=pair[keep], change_qos=pair["LCM" if keep == "HCM" else "HCM"])
            observations[sfu, t] = row | data
    return observations


def audit_residence(name, rows, cutoff, policy, decisions, settings):
    per_sfu = defaultdict(list)
    last_end, gaps, joined = {}, {}, {}
    requests = {(row["sfu_id"], row["switch_id"]): row for row in events(rows, START)}
    for row in rows:
        sfu, kind = row.get("sfu_id"), row.get("event")
        if kind not in (RESIDE, START, END):
            continue
        t, switch_id = time_ns(row), row["switch_id"]
        if kind == START:
            if sfu in last_end:
                elapsed = t - last_end[sfu]
                require(elapsed >= 0 and (policy != NEW or elapsed > 0), f"{name}: illegal reverse on completion")
                gaps[sfu, switch_id] = elapsed
            per_sfu[sfu].append(row)
        elif kind == END:
            last_end[sfu] = t
        elif switch_id != "0":
            require(last_end[sfu] == t and int(row["residency_start_ns"]) == t, f"{name}: residence starts outside B4 END")
            previous = requests[sfu, switch_id]
            decision = decisions[sfu, int(previous["request_ns"])]
            hold = int(Decimal(settings.get("wifi.ap.control.residency.minimum.us", "500")) * 1000)
            if row["reason"] == "ECONOMICAL_SWITCH":
                hold = max(hold, int(decision["economic_residency_ns"]))
            require(int(row["residency_until_ns"]) == t + hold and row["reason"] == decision["reason"],
                    f"{name}: invalid completed residence plan")

    aggregates = {}
    all_gaps = []
    for sfu in (row["sfu_id"] for row in events(rows, INITIAL)):
        local = [row for row in per_sfu[sfu] if time_ns(row) <= cutoff]
        local_gaps = [gaps[sfu, row["switch_id"]] for row in local if (sfu, row["switch_id"]) in gaps]
        all_gaps.extend(local_gaps)
        determining = []
        for row in local:
            detail = decisions.get((sfu, int(row["request_ns"])))
            if detail:
                determining.append(detail)
                joined[sfu, row["switch_id"]] = {k: detail[k] for k in ("trigger", "keep_qos", "change_qos", "expired_packets",
                                                                          "live_packets", "expired_ingress", "live_ingress")}
                if policy == NEW:
                    joined[sfu, row["switch_id"]]["reason"] = detail["reason"]
                    if detail["keep_qos"] != "FEASIBLE" and detail["change_qos"] != "FEASIBLE":
                        require(detail["clear_qos_gain"] == "true", f"{name}: both modes failed without reliable gain")
        evals = [r for (device, t), r in decisions.items() if device == sfu and t <= cutoff]
        aggregates[sfu] = dict(
            urgencies_from_expired_only=sum(r["trigger"] == "URGENT_QUEUE" and r["expired_ingress"] > 0 and r["live_ingress"] == 0 for r in evals),
            urgent_switches=sum(r["trigger"] == "URGENT_QUEUE" for r in determining),
            both_infeasible_switches=sum(r["keep_qos"] != "FEASIBLE" and r["change_qos"] != "FEASIBLE" for r in determining),
            switches_with_expired_queue=sum(r["expired_packets"] > 0 for r in determining),
            reversals_under_1ms=sum(gap < 1_000_000 for gap in local_gaps),
            median_completed_residence_ns=median(local_gaps) if local_gaps else None,
            residency_holds=sum(r["reason"] == "RESIDENCY_HOLD" for r in evals) if policy == NEW else 0,
            reverse_hysteresis_holds=sum(r["reason"] == "REVERSE_HYSTERESIS" for r in evals) if policy == NEW else 0,
            unproven_qos_holds=sum(r["reason"] == "HOLD_UNPROVEN_QOS" for r in evals) if policy == NEW else 0,
            urgent_exceptions=sum(r["reason"] == "URGENT_EXCEPTION" and r["action"] == "REQUESTED" for r in evals) if policy == NEW else 0,
            expired_ingress_skips=sum(r["sfu_id"] == sfu and time_ns(r) <= cutoff for r in events(rows, EXPIRED)),
        )
    aggregates["ALL"] = {key: sum(r[key] for r in aggregates.values()) for key in EXTRA_METRICS
                         if key != "median_completed_residence_ns"}
    aggregates["ALL"]["median_completed_residence_ns"] = median(all_gaps) if all_gaps else None
    return aggregates, gaps, joined


def assert_diagnostics(case, name, rows, budget, decisions, metrics):
    if case == "idle":
        choice = next(iter(decisions.values()))
        require(choice["economic_residency_ns"] == "767573" and choice["reason"] == "ECONOMICAL_SWITCH"
                and isclose(float(choice["return_reserve_uj"]), 155.685), f"{name}: idle payback differs from B3/B4 arithmetic")
    if case == "idle-short":
        require(next(iter(decisions.values()))["reason"] == "PAYBACK_BEYOND_FORECAST" and not events(rows, START),
                f"{name}: short idle opportunity must stay in HCM")
    if case == "urgent":
        require(budget["urgent_exceptions"] == 1 and metrics["on_time"] == 3, f"{name}: real urgent service not preserved")
    if case in ("expired", "infeasible"):
        require(not events(rows, START) and metrics["delivered"] == metrics["generated"] and metrics["on_time"] == 0,
                f"{name}: unrescuable packets should still be delivered and counted as late")
    if case == "no-useful-delay":
        choice = next(iter(decisions.values()))
        require(choice["allowed_delay_ns"] == "29525" and choice["chosen_delay_ns"] == "0" and not events(rows, DEFER),
                f"{name}: unused slack incorrectly created a delay")


def evaluate(case, jar, work):
    scenario, seed, policy, settings = case
    name = f"{scenario}-{seed}-{policy}"
    path = work / f"{name}.properties"
    write_properties(path, settings)
    raw = simulate(jar, path)
    if seed == SEEDS[0] and (policy in (OLD, NEW) or scenario.startswith("idle")):
        path.with_suffix(".log").write_text(raw, encoding="utf-8")
    rows = parse_trace(raw)
    queue, packets = verify_queue(name, rows), packet_inputs(rows, settings)
    endpoint = endpoint_state(name, rows, packets)
    digest = sha256(repr(list(packets.items())).encode()).hexdigest()
    cutoff = int(settings["wifi.ap.activity.window.us"]) * 1000
    if policy in (OLD, "ecq-aware"):
        audit_qos(name, rows, settings)
    elif policy != NEW:
        audit_controller(name, rows, queue, settings, cutoff)
    audit_deferrals(name, rows)
    initial = {r["sfu_id"]: r["current_mode"] for r in events(rows, INITIAL)}
    switches = audit_switches(name, rows, settings, cutoff)
    accounts = per_ap_accounting(name, rows, queue, packets, endpoint, switches, initial, cutoff)
    decisions = annotate_decisions(name, rows, packets, settings, policy)
    extra, gaps, tied = audit_residence(name, rows, cutoff, policy, decisions, settings)
    meta = dict(scenario=scenario, seed=seed, policy=policy, service_model="threshold" if policy == POLICIES[0] else "load-aware")
    if policy == NEW:
        for sfu in accounts:
            choices = [r for (d, t), r in decisions.items() if d == sfu and t <= cutoff]
            good = sum((r["keep_qos"] if r["target_mode"] == r["current_mode"] else r["change_qos"]) == "FEASIBLE" for r in choices)
            accounts[sfu].update(urgent_decisions=sum(r["trigger"] == "URGENT_QUEUE" for r in choices),
                                 predicted_feasible=good, predicted_risk=len(choices) - good)
    metrics = report_metrics(name, rows, queue, packets, endpoint, accounts, cutoff, settings, meta | {"input_sha256": digest})
    for record in metrics:
        if record["ac"] == "ALL":
            record.update(extra[record["sfu_id"]])
    if policy == NEW and scenario not in (*MAIN, "near-threshold"):
        focus_ac = "VO" if scenario in ("expired", "infeasible", "urgent") else "BE"
        focus = next((r for r in metrics if r["ac"] == focus_ac and r["sfu_id"] == "ALL"), {})
        assert_diagnostics(scenario, name, rows, extra["ALL"], decisions, focus)

    trace = []
    for row in rows:
        kind = row.get("event")
        if kind not in (INITIAL, DECISION, RESIDE, EXPIRED, QOS, DEFER, "WIFI_AP_MODE_DECISION", *SWITCH_EVENTS):
            continue
        if kind == QOS and (policy == NEW or row["candidate_mode"] != row["target_mode"]):
            continue
        t, sfu = time_ns(row), row["sfu_id"]
        data = row
        if kind in (DECISION, QOS):
            data = decisions[sfu, t]
        elif kind == START:
            key = sfu, row["switch_id"]
            data = row | tied.get(key, {}) | {"residence_before_switch_ns": gaps.get(key)}
        trace.append(meta | {"sfu_id": sfu, "t_ns": t} | {k: data[k] for k in (*EVENT_FIELDS, *EXTRA_EVENTS) if k in data and k != "t_ns"})
    summary = next(r for r in metrics if r["sfu_id"] == "ALL" and r["ac"] == "ALL")
    print(f"{name}: delivered={summary['delivered']}/{summary['generated']} waiting={summary['incomplete']} "
          f"switches={summary['switches_completed']} energy_mJ={summary['total_energy_uj'] / 1000:.3f}", flush=True)
    return (scenario, seed), digest, trace, metrics


def audit_d3_baseline(metrics):
    baseline_path = ROOT / "results/d3-qos-deferral/d3-performance.csv"
    keys = (*IDENTITY, "ac")
    with baseline_path.open(encoding="utf-8", newline="") as stream:
        baseline = {tuple(row[key] for key in keys): row for row in csv.DictReader(stream)}
    checked = 0
    for row in metrics:
        if row["scenario"] not in MAIN or row["policy"] == NEW:
            continue
        old = baseline[tuple(str(row[key]) for key in keys)]
        require(all(old[key] == ("" if row.get(key) is None else str(row[key])) for key in METRIC_FIELDS),
                f"D3 baseline changed at {tuple(row[key] for key in keys)}")
        checked += 1
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--workers", default=3, type=int)
    parser.add_argument("--smoke", action="store_true", help="Use only seed 101 for the four D3 main scenarios.")
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    inputs, trace, metrics, cases = {}, [], [], 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for key, digest, events_out, metrics_out in pool.map(lambda case: evaluate(case, args.jar, args.work), configurations(args.smoke)):
            require(inputs.setdefault(key, digest) == digest, f"{key}: different policy input packets")
            trace.extend(events_out)
            metrics.extend(metrics_out)
            cases += 1
    matched = audit_d3_baseline(metrics)
    write_csv(args.output / "d4-decision-events.csv", IDENTITY + EVENT_FIELDS + EXTRA_EVENTS, trace)
    write_csv(args.output / "d4-performance.csv", IDENTITY + METRIC_FIELDS + EXTRA_METRICS, metrics)
    print(f"Verified {cases} Java runs and {matched} committed D3 performance rows.")


if __name__ == "__main__":
    main()
