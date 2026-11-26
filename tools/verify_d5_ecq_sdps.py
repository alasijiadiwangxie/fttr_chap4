"""Freeze the threshold on independent data, then run and check the paired Java experiments."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from bisect import bisect_right
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from hashlib import sha256
from itertools import accumulate
from pathlib import Path
from statistics import mean
from time import monotonic

from d5_experiments import FULL, QUANTUM_LIMIT, SEEDS, THRESHOLDS, calibration_jobs, test_jobs
from pon_experiment_io import ROOT, require, write_csv
from run_b4_ap_switch import DEFAULT_POWERS, STATES, by_summary, trace_durations, watts_times_ns
from verify_c1_ap_observation import WindowLedger, time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import endpoint_state, events, packet_inputs, quantile
from verify_d1_mode_control import INITIAL, JAR, audit_switches
from verify_d3_qos_deferral import ACS, DEFER, audit_deferrals

OUT = ROOT / "results/d5-ecq-sdps"
WORK = ROOT / "target/d5-ecq-sdps"
DECISION = "WIFI_AP_D4_DECISION"
D5_TRACE = "WIFI_AP_D5_DECISION"
START, END = "WIFI_AP_SWITCH_START", "WIFI_AP_SWITCH_END"
QUEUE, DECODE = "WIFI_AP_QUEUE_EVENT", "WIFI_PHY_MPDU_DECODE"
ID = ("suite", "scenario", "seed", "policy", "disabled", "power", "scans", "optical", "window_ns", "sfus", "stas", "threshold_mbps", "input_sha256")
METRICS = ("sfu_id", "direction", "ac", "generated", "delivered_window", "dropped_window", "backlog_window", "overdue_window",
           "delivered_final", "dropped_final", "unsettled_final", "on_time_final", "on_time_ratio", "violations_final", "drop_ratio", "offered_mbps",
           "goodput_mbps", "p95_ms", "p99_ms", "jitter_ms", "mean_optical_us", "mean_wireless_us", "drain_ns", "delivered_in_drain",
           "radio_uj", "scan_uj", "total_uj", "drain_uj", "power_w_per_ap", "nj_per_success_bit", "nj_per_final_bit_with_drain",
           "idle_ns", "tx_ns", "rx_ns", "switch_ns", "hcm_ns", "hcm_fraction", "switches", "deferrals_opened", "deferrals_released",
           "active_delay_ns", "safe_exits", "weak_risk_holds", "urgent_exceptions", "expired_ingress_skips", "sinr_failed_attempts",
           "forecast_windows", "forecast_goodput_mae_mbps", "forecast_arrival_mae_mbps", "pon_data_utilization")
TRACE = ("sfu_id", "t_ns", "event", "reason", "trigger", "switch_id", "action", "phase", "current_mode", "candidate_mode", "from_mode",
         "to_mode", "target_mode", "keep_qos", "change_qos", "live_packets", "expired_packets", "on_time_gain", "economic_residency_ns",
         "residency_until_ns", "active_delay_ns", "net_saving_uj", "margin_uj", "safe_economic_exit", "keep_shortfall_mbps",
         "change_shortfall_mbps", "predicted_goodput_mbps", "actual_goodput_mbps", "predicted_arrival_mbps", "actual_arrival_mbps",
         "packet_id", "allocation_id", "budget_bytes", "actual_bytes", "pending_bytes", "deadline_ns")


def trace_runner(jar, work):
    classes = work / "projection"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes),
                    str(ROOT / "tools/java/D5Trace.java")], check=True)
    return ["java", "-Xmx1150m", "-cp", os.pathsep.join((str(jar), str(classes))), "D5Trace"]


def run_java(name, settings, command, work, save):
    cfg = work / (name + ".properties")
    write_properties(cfg, settings)
    process = subprocess.run([*command, str(cfg)], capture_output=True, check=False, timeout=300)
    if process.returncode:
        raise RuntimeError(f"{name}: {process.stderr.decode('utf-8', errors='replace')}")
    raw = process.stdout.decode("utf-8")
    if save:
        cfg.with_suffix(".log").write_text(raw, encoding="utf-8")
    return cfg, parse_trace(raw)


def energy_account(name, rows, initial, end, drained, switches, settings):
    activity = events(rows, "WIFI_AP_ACTIVITY_CHANGE")
    scans = [row for row in events(rows, "WIFI_CHANNEL_SCAN") if row["phase"] == "END"]
    powers = {mode: [Decimal(settings.get(f"wifi.ap.energy.{mode.lower()}.{state}.w", str(power)))
                     for state, power in zip(STATES, values)] for mode, values in DEFAULT_POWERS.items()}
    results = {}
    for sfu, mode in initial.items():
        actual = [r for r in activity if r["sfu_id"] == sfu]
        local_switches = switches[sfu]

        def integrate(limit):
            by_state = trace_durations([r for r in actual if time_ns(r) <= limit], int(sfu), mode, limit, local_switches)
            durations = {f"{state}_ns": sum(values[i] for values in by_state.values()) for i, state in enumerate(STATES)}
            require(sum(durations.values()) == limit, f"{name}: B2 time sum")
            regular = sum(watts_times_ns(watts, by_state[ap_mode][i])
                          for ap_mode, state_powers in powers.items() for i, watts in enumerate(state_powers))
            transition = sum(watts_times_ns(s["power"], max(0, min(s["finish_ns"], limit) - s["start_ns"]))
                             for s in local_switches)
            return regular + transition, durations, sum(by_state["HCM"])

        radio, durations, hcm_ns = integrate(end)
        energy = by_summary(rows, "WIFI_AP_ENERGY", sfu_id=sfu)
        reported = by_summary(rows, "WIFI_AP_ACTIVITY", sfu_id=sfu)
        require(Decimal(energy["total_energy_uj"]) == radio and all(int(reported[k]) == n for k, n in durations.items()),
                f"{name}: energy and active state reconciliation")
        local_scans = [r for r in scans if r["sfu_id"] == sfu]
        scan_w = Decimal(settings.get("wifi.channel.scan.power.w", "0"))
        per_scan = lambda r, t: watts_times_ns(scan_w, max(0, min(t, int(r["ended_ns"])) - int(r["started_ns"])))
        require(all(Decimal(r["extra_energy_uj"]) == per_scan(r, end) for r in local_scans), f"{name}: scan energy")
        scanning = sum((per_scan(r, end) for r in local_scans), Decimal(0))
        full_radio = integrate(drained)[0] if drained > end else radio
        drain_scan = sum((per_scan(r, drained) for r in local_scans), Decimal(0)) - scanning
        results[sfu] = durations | dict(radio_uj=radio, scan_uj=scanning, total_uj=radio + scanning,
                                         drain_uj=full_radio - radio + drain_scan, hcm_ns=hcm_ns,
                                         switches=sum(s["finish_ns"] <= end for s in local_switches))
    network = by_summary(rows, "WIFI_AP_ENERGY_NETWORK")
    require(sum(v["radio_uj"] for v in results.values()) == Decimal(network["total_energy_uj"]), f"{name}: network energy")
    results["ALL"] = {key: sum(r[key] for r in results.values()) for key in next(iter(results.values()))}
    return results


def audit_optical(name, rows, packets, delivery, local_dl, settings, end, unsettled):
    if settings.get("fiber.uplink.dba.mode") not in ("giant-complete", "giant-complete-recovery"):
        return {}, None
    boundaries = {kind: {} for kind in ("FIBER_TX_START", "FIBER_TX_END")}
    for row in rows:
        if row.get("event") in boundaries:
            dest = boundaries[row["event"]]
            require(row["packet_id"] not in dest, f"{name}: packet occupied fiber twice")
            dest[row["packet_id"]] = time_ns(row)
    start, finish = boundaries.values()
    require(start.keys() == finish.keys(), f"{name}: unfinished optical transmission")
    downstream = {p for p, data in packets.items() if data[3] == "DL"}
    uplink = {p for p in delivery if packets[p][3] == "UL"}
    optical_in = {r["packet_id"]: int(r["time_ns"]) for r in events(rows, "TCONT_ENQUEUE")}
    require(start.keys() == downstream | uplink and optical_in.keys() == uplink | unsettled, f"{name}: missing optical route")
    require(len(unsettled) == int(by_summary(rows, "PON_DBA_EXECUTION")["pending_packets"]), f"{name}: unaccounted optical backlog")
    durations, total_busy = {}, 0
    for direction, ids, rate, propagation in (("DL", downstream, 10000, 10_000), ("UL", uplink, 2500, 10_025)):
        last_end = 0
        for packet in sorted(ids, key=start.__getitem__):
            generated, bytes_, *_ = packets[packet]
            tx, rx = start[packet], finish[packet]
            expected = (bytes_ * 8000 + rate - 1) // rate
            entrance = generated if direction == "DL" else optical_in[packet]
            exit_ = local_dl[packet] if direction == "DL" else delivery[packet]
            require(entrance <= tx and last_end <= tx and rx == tx + expected and exit_ == rx + propagation,
                    f"{name}: {direction} fiber time or overlapping slots {packet}")
            durations[packet] = exit_ - entrance
            last_end = rx
            total_busy += max(0, min(end, rx) - tx) if direction == "UL" else 0
    grants = {r["grant_id"]: r for r in events(rows, "PON_DBA_BWMAP_RX")}
    children = events(rows, "PON_DBA_DATA_START")
    require(len(children) == len(uplink), f"{name}: missing executed BWmap for uplink")
    for r in children:
        grant = grants[r["grant_id"]]
        p = r["packet_id"]
        require(int(grant["time_ns"]) < int(grant["tx_start_ns"]) <= start[p] < finish[p] <= int(grant["tx_end_ns"]),
                f"{name}: DATA outside a received BWmap grant")
    return durations, total_busy / end


def summarize_cohorts(name, rows, packets, endpoint, end, durations, settings):
    delivery, drops, order = endpoint
    sample_groups = defaultdict(list)
    for packet in packets:
        info = packets[packet]
        require(info[0] < end, f"{name}: packet generation beyond cutoff")
        for sfu, direction, ac in (("ALL", "BOTH", "ALL"), ("ALL", "BOTH", info[2]),
                                   ("ALL", info[3], "ALL"), ("ALL", info[3], info[2]), (info[5], info[3], info[2])):
            sample_groups[sfu, direction, ac].append(packet)
    qos = {(r["sfu_id"], r["direction"], r["ac"]): r for r in events(rows, "WIFI_AP_QOS")}
    deadlines = {ac: int(Decimal(settings[f"wifi.ap.qos.{ac}.deadline.us"]) * 1000) for ac in ACS}
    delivery_index = {p: i for i, p in enumerate(order)}
    measurements = []
    for (sfu, direction, ac), keys in sample_groups.items():
        delivered = sorted((p for p in keys if p in delivery), key=delivery_index.__getitem__)
        lost = [p for p in keys if p in drops]
        nw = sum(delivery[p] <= end for p in delivered)
        lw = sum(drops[p] <= end for p in lost)
        delays = {p: delivery[p] - packets[p][0] for p in delivered}
        timely = [p for p in delivered if delays[p] <= deadlines[packets[p][2]]]
        overdue = sum(delivery.get(p, drops.get(p, end + 1)) > end >= packets[p][0] + deadlines[packets[p][2]] for p in keys)
        backlog = len(keys) - nw - lw
        unsettled = len(keys) - len(delivered) - len(lost)
        require(backlog == len(delivered) - nw + len(lost) - lw + unsettled,
                f"{name}: inconsistent completed cohort")
        if sfu != "ALL":
            java = qos[sfu, direction, ac]
            require([int(java[k]) for k in ("arrived", "delivered", "dropped", "incomplete", "overdue", "on_time")]
                    == [len(keys), nw, lw, backlog, overdue, sum(delivery[p] <= end for p in timely)], f"{name}: C4 cutoff mismatch")
        by_flow = defaultdict(list)
        for p in delivered:
            by_flow[packets[p][2], packets[p][3], packets[p][5]].append(delays[p])
        jitter = [abs(b - a) for series in by_flow.values() for a, b in zip(series, series[1:])]
        optical = [durations[p] for p in delivered if p in durations]
        wireless = [delays[p] - durations.get(p, 0) for p in delivered]
        delay_ms = [delay / 1e6 for delay in delays.values()]
        bits = sum(packets[p][1] * 8 for p in delivered if delivery[p] <= end)
        final_bits = sum(packets[p][1] * 8 for p in delivered)
        measurements.append(dict(sfu_id=sfu, direction=direction, ac=ac, generated=len(keys), delivered_window=nw,
                                 dropped_window=lw, backlog_window=backlog, overdue_window=overdue, delivered_final=len(delivered),
                                 dropped_final=len(lost), unsettled_final=unsettled, on_time_final=len(timely), on_time_ratio=len(timely) / len(keys),
                                 violations_final=len(keys) - len(timely), drop_ratio=len(lost) / len(keys), delivered_in_drain=len(delivered) - nw,
                                 offered_mbps=sum(packets[p][1] * 8000 / end for p in keys), goodput_mbps=bits * 1000 / end,
                                 p95_ms=quantile(delay_ms, .95), p99_ms=quantile(delay_ms, .99), jitter_ms=mean(jitter) / 1e6 if jitter else 0,
                                 mean_optical_us=mean(optical) / 1000 if optical else 0,
                                 mean_wireless_us=mean(wireless) / 1000 if wireless else None, bits=bits, final_bits=final_bits))
    return measurements


def forecast_examples(rows, packets, end, switches):
    inputs, outputs = defaultdict(list), defaultdict(list)
    for r in events(rows, QUEUE):
        if r["action"] == "ENQUEUED" and r["direction"] == "DL":
            inputs[r["sfu_id"]].append((time_ns(r), int(r["bytes"]) * 8))
    for r in events(rows, "DELIVERED"):
        p = r["packet_id"]
        if packets[p][3] == "DL":
            outputs[packets[p][5]].append((time_ns(r), packets[p][1] * 8))

    def indexed(flow):
        return list(zip(*(flow or [(0, 0)])))[0], list(accumulate((b for _, b in flow or [(0, 0)]), initial=0))

    index_in = {sfu: indexed(data) for sfu, data in inputs.items()}
    index_out = {sfu: indexed(data) for sfu, data in outputs.items()}
    selected = {(r["sfu_id"], time_ns(r)): r for r in events(rows, DECISION)
                if r["trigger"] == "PERIODIC" and r["current_mode"] == r["target_mode"]}
    result = []
    for r in events(rows, "WIFI_AP_ENERGY_MODE_DECISION"):
        stamp, sfu = time_ns(r), r["sfu_id"]
        decision = selected.get((sfu, stamp))
        width = int(r["horizon_ns"])
        till = stamp + width
        if (not decision or r["candidate_mode"] != decision["target_mode"] or till > end
                or any(s["start_ns"] < till and s["finish_ns"] > stamp for s in switches[sfu])):
            continue

        def actual(index):
            positions, cumulative = index.get(sfu, ((), (0,)))
            return (cumulative[bisect_right(positions, till)] - cumulative[bisect_right(positions, stamp)]) * 1000 / width

        result.append(dict(event="FORECAST_REPLAY", t_ns=stamp, sfu_id=sfu, current_mode=r["candidate_mode"],
                           predicted_goodput_mbps=float(r["success_bits"]) * 1000 / width, actual_goodput_mbps=actual(index_out),
                           predicted_arrival_mbps=float(r["ewma_mbps"]), actual_arrival_mbps=actual(index_in)))
    return result


def evaluate(job, command, work):
    suite, case, seed, policy, disabled, threshold, settings = job
    name = f"{case.name}-{seed}-{policy}" + (f"-no-{disabled}" if disabled else "") + (f"-{threshold}" if suite == "calibration" else "")
    start_wall = monotonic()
    save = not disabled and (case.name.startswith("d4-") or case.name in ("step-recovery", "joint-giant", QUANTUM_LIMIT) and policy == FULL)
    config, rows = run_java(name, settings, command, work, save)
    packets = packet_inputs(rows, settings)
    digest = sha256(repr(list(packets.items())).encode()).hexdigest()
    meta = dict(suite=suite, scenario=case.name, seed=seed, policy=policy, disabled=disabled, power=case.power, scans=case.scans,
                optical=case.optical, window_ns=case.window_us * case.repeat * 1000, sfus=max(case.associations), stas=len(case.associations),
                threshold_mbps=threshold, input_sha256=digest)
    end = meta["window_ns"]
    unsettled = set()
    if case.optical == "giant-complete":
        terminal = {r["packet_id"] for r in rows if r.get("event") in ("DELIVERED", "WIFI_RETRY_LIMIT_DROP", "WIFI_COLLISION_DROP")}
        unsettled = set(packets) - terminal
        require(all(packets[p][3] == "UL" for p in unsettled), f"{name}: unexpected downlink backlog at termination")
    if case.name == QUANTUM_LIMIT:
        require(unsettled and all(packets[p][2:4] == ("BE", "UL") and packets[p][1] == 1500 for p in unsettled),
                f"{name}: the recorded BE HOL boundary did not reproduce")
    settled_inputs = {p: data for p, data in packets.items() if p not in unsettled}
    delivery, drops, order = endpoint_state(name, rows, settled_inputs)
    initial = {r["sfu_id"]: r["current_mode"] for r in events(rows, INITIAL)}
    switches = audit_switches(name, rows, settings, end)
    audit_deferrals(name, rows)
    drained = max(end, *delivery.values(), *drops.values(), *(time_ns(r) for r in events(rows, "WIFI_AP_ACTIVITY_CHANGE")))
    if unsettled:
        drained = max(drained, int(Decimal(by_summary(rows, "PON_UPLINK")["window_us"]) * 1000))
        require(all(deadline_of(packets[p], settings) <= drained for p in unsettled), f"{name}: stranded packet deadline not settled")
    power = energy_account(name, rows, initial, end, drained, switches, settings)
    if policy == FULL:
        require(all(a["finish_ns"] < b["start_ns"] for ss in switches.values() for a, b in zip(ss, ss[1:])), f"{name}: immediate reversal")
    ledger, local_dl = defaultdict(WindowLedger), {}
    for r in events(rows, QUEUE):
        ledger[r["sfu_id"], r["direction"], r["ac"]].apply(name, r)
        if r["action"] == "ENQUEUED" and r["direction"] == "DL":
            local_dl[r["packet_id"]] = time_ns(r)
    require(all(not state.pending and not state.inflight and not state.awaiting_confirmation for state in ledger.values()),
            f"{name}: unresolved wireless ledger")
    optical, util = audit_optical(name, rows, packets, delivery, local_dl, settings, end, unsettled)
    metrics = summarize_cohorts(name, rows, packets, (delivery, drops, order), end, optical, settings)
    final = next(r for r in metrics if (r["sfu_id"], r["direction"], r["ac"]) == ("ALL", "BOTH", "ALL"))
    bits, final_bits = final["bits"], final["final_bits"]
    final.update(power["ALL"])
    defers = [r for r in events(rows, DEFER) if time_ns(r) <= end]
    history = events(rows, DECISION)
    forecast = forecast_examples(rows, packets, end, switches) if not case.uplink_share and policy == FULL else []
    final.update(drain_ns=drained - end, power_w_per_ap=final["total_uj"] * 1000 / (end * meta["sfus"]),
                 hcm_fraction=final["hcm_ns"] / (end * meta["sfus"]), nj_per_success_bit=final["total_uj"] * 1000 / bits if bits else None,
                 nj_per_final_bit_with_drain=(final["total_uj"] + final["drain_uj"]) * 1000 / final_bits if final_bits else None,
                 deferrals_opened=sum(r["phase"] == "OPEN" for r in defers), deferrals_released=sum(r["phase"] == "RELEASE" for r in defers),
                 active_delay_ns=sum(int(r["active_delay_ns"]) for r in defers if r["phase"] == "RELEASE"),
                 safe_exits=sum(r["reason"] == "SAFE_ECONOMIC_EXIT" and r["action"] == "REQUESTED" for r in history),
                 weak_risk_holds=sum(r["reason"] == "HOLD_UNPROVEN_QOS" for r in history),
                 urgent_exceptions=sum(r["reason"] == "URGENT_EXCEPTION" and r["action"] == "REQUESTED" for r in history),
                 expired_ingress_skips=len(events(rows,"WIFI_AP_D4_EXPIRED_INGRESS")),
                 sinr_failed_attempts=sum(r["outcome"] == "SINR_ERROR" and time_ns(r) <= end for r in events(rows, DECODE)),
                 forecast_windows=len(forecast), pon_data_utilization=util)
    for measure in ("goodput", "arrival"):
        final[f"forecast_{measure}_mae_mbps"] = mean(abs(r[f"predicted_{measure}_mbps"] - r[f"actual_{measure}_mbps"]) for r in forecast) if forecast else None
    for sfu in initial:
        metrics.append(dict(sfu_id=sfu, direction="BOTH", ac="ALL", **power[sfu], hcm_fraction=power[sfu]["hcm_ns"] / end))
    for record in metrics:
        record.pop("bits", None)
        record.pop("final_bits", None)

    decisions = {(r["sfu_id"], time_ns(r)): r for r in events(rows, D5_TRACE)}
    if policy == FULL:
        flags = {k: str(settings.get(f"wifi.ap.control.complete.{k}.enabled", "true")) for k in ("channel", "qos", "deferral", "residency")}
        require(all(all(row[k] == expected for k, expected in flags.items()) for row in decisions.values()), f"{name}: wrong ablation flags")
        require(flags["deferral"] == "true" or not defers, f"{name}: deferral disabled yet used")
        require(flags["qos"] == "true" or all(r["trigger"] != "URGENT_QUEUE" for r in history), f"{name}: QoS disabled yet triggered")
    projected = []
    for r in rows:
        kind = r.get("event")
        if kind in (INITIAL, START, END, DEFER, "WIFI_AP_D4_RESIDENCY") or kind == DECISION and (
                r["trigger"] == "PERIODIC" or r["action"] == "REQUESTED" or case.name.startswith("d4-")):
            t = time_ns(r)
            fields = r | decisions.get((r["sfu_id"], t), {}) if kind == DECISION else r
            projected.append(meta | {k: fields[k] for k in TRACE if k in fields and k not in ("event", "t_ns")} | {"event": kind, "t_ns": t})
    if case.name.startswith("d4-") or case.name in ("rf-secondary", "rate-overload", "step-recovery"):
        projected.extend(meta | r for r in forecast)
    if unsettled:
        for row in events(rows, "TCONT_ENQUEUE"):
            packet = row["packet_id"]
            if packet in unsettled:
                grants = [r for r in events(rows, "PON_DBA_SLOT_END") if r["allocation_id"] == row["allocation_id"]]
                for grant in grants[-2:]:
                    projected.append(meta | {key: grant[key] for key in ("sfu_id", "allocation_id", "budget_bytes", "actual_bytes", "pending_bytes")}
                                     | dict(event="PON_UNSETTLED_EVIDENCE", packet_id=packet, t_ns=grant["time_ns"],
                                            deadline_ns=deadline_of(packets[packet], settings)))
    first_cycle = [(p, info) for p, info in packets.items() if info[0] < case.window_us * 1000]
    manifest = meta | dict(config_file=config.name, config_sha256=sha256(config.read_bytes()).hexdigest(),
                            first_cycle_sha256=sha256(repr(first_cycle).encode()).hexdigest())
    print(f"{name}: {final['delivered_window']}/{final['generated']} delivered, {final['backlog_window']} backlog, "
          f"{final['switches']} switches, {monotonic() - start_wall:.1f}s wall time", flush=True)
    return ([meta | r for r in metrics], projected, manifest, case.definition())


def deadline_of(packet, settings):
    return packet[0] + int(Decimal(settings[f"wifi.ap.qos.{packet[2]}.deadline.us"]) * 1000)


def run_jobs(jobs, command, work, workers):
    metrics, trace, manifest, definitions, input_fingerprints = [], [], [], {}, {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for batch, events_out, config, definition in pool.map(lambda job: evaluate(job, command, work), jobs):
            key = (config["scenario"], config["seed"])
            require(input_fingerprints.setdefault(key, config["input_sha256"]) == config["input_sha256"], f"{key}: unpaired inputs")
            metrics.extend(batch)
            trace.extend(events_out)
            manifest.append(config)
            definitions[config["scenario"]] = definition
    pair_groups = (("rf-clear", "rf-primary", "rf-secondary", "rf-wide", "rf-secondary-free"),
                   ("rate-mid", "rate-mid-paper"), ("joint-fixed", "joint-giant", QUANTUM_LIMIT),
                   ("step-recovery", "step-recovery-long"))
    for names in pair_groups:
        for seed in SEEDS:
            fingerprints = {row["first_cycle_sha256"] for row in manifest if row["scenario"] in names and row["seed"] == seed}
            require(len(fingerprints) <= 1, f"{names}/{seed}: different inputs in paired profiles")
    return metrics, trace, manifest, definitions


def freeze_threshold(metrics):
    totals = [r for r in metrics if (r["sfu_id"], r["direction"], r["ac"]) == ("ALL", "BOTH", "ALL")]
    scores = []
    for threshold in THRESHOLDS:
        reps = [r for r in totals if r["threshold_mbps"] == threshold]
        scores.append(dict(threshold_mbps=threshold, violations=sum(r["violations_final"] for r in reps),
                           mean_nj_per_success_bit=float(mean(r["nj_per_success_bit"] for r in reps))))
    selected = min(scores, key=lambda r: (r["violations"], r["mean_nj_per_success_bit"], r["threshold_mbps"]))
    return dict(selected_mbps=selected["threshold_mbps"], rule="min(final violations, nJ/successful bit, threshold)", candidates=scores)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("calibrate", "pilot", "formal", "replay"), default="formal")
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--frozen", type=Path)
    parser.add_argument("--workers", type=int, default=3)
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    runner = trace_runner(args.jar, args.work)
    target = args.frozen or args.work / "frozen-threshold.json"
    if args.phase == "calibrate":
        rows, _, manifest, definitions = run_jobs(calibration_jobs(), runner, args.work, args.workers)
        threshold = freeze_threshold(rows) | {"runs": manifest, "scenarios": definitions}
        target.write_text(json.dumps(threshold, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Frozen fixed threshold: {threshold['selected_mbps']} Mb/s, {target}")
        return
    threshold = json.loads(target.read_text(encoding="utf-8"))
    rows, timeline, configs, definitions = run_jobs(test_jobs(threshold["selected_mbps"], args.phase), runner, args.work, args.workers)
    write_csv(args.output / "d5-performance.csv", ID + METRICS, [r for r in rows if r["suite"] != "ablation"])
    ablations = [r for r in rows if r["suite"] == "ablation" or r["policy"] == FULL
                 and r["scenario"] in ("rf-secondary", "step-recovery", "defer-primary")]
    write_csv(args.output / "d5-ablation.csv", ID + METRICS, ablations)
    write_csv(args.output / "d5-mode-events.csv", ID + TRACE, timeline)
    manifest = dict(frozen_threshold=threshold, seeds=SEEDS, scenarios=definitions, runs=configs)
    (args.output / "experiment-config.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(configs)} controlled Java runs using the frozen threshold.")


if __name__ == "__main__":
    main()
