"""Run paired D3 Java scenarios and reconcile deadline decisions, deferred requests and real outcomes."""

from __future__ import annotations

import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from hashlib import sha256
from itertools import product
from math import floor, isclose
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv
from run_b4_ap_switch import by_summary
from verify_c1_ap_observation import ACS, time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import endpoint_state, events, metrics_at, packet_inputs, verify_queue
from verify_d1_mode_control import INITIAL, JAR, POLICY, SEEDS, SWITCH_EVENTS, audit_energy, audit_switches, packet_metrics
from verify_d2_energy_decision import D2_DECISION, audit_candidate, audit_controller, base, grid_config, radio_config, set_associations


OUT = ROOT / "results/d3-qos-deferral"
WORK = ROOT / "target/d3-qos-deferral"
POLICIES = ("fixed-threshold", "ec-aware", "ecq-aware", "ecq-sdps")
QOS = "WIFI_AP_QOS_MODE_DECISION"
DEADLINE = "WIFI_AP_QOS_DEADLINE_PREDICTION"
DEFER = "WIFI_AP_HCM_DEFER"
MAIN = ("mixed-ideal", "mixed-primary", "mixed-secondary", "sustained-85")
DIAGNOSTICS = {
    "urgent-vo": ("0", {"packet.1.type": "VO", "wifi.ap.qos.VO.deadline.us": "254.225"}),
    "urgent-vi": ("0", {"packet.1.type": "VI", "wifi.ap.qos.VI.deadline.us": "265.475"}),
    "delay-be": ("0", {"wifi.ap.qos.BE.deadline.us": "295"}),
    "delay-bk": ("0", {"packet.1.type": "BK", "wifi.ap.qos.BK.deadline.us": "350"}),
    "cancel": ("0", {"wifi.ap.qos.BE.deadline.us": "320"}),
    "merge": ("0,10", {"packet.2.type": "BK", "wifi.ap.qos.BE.deadline.us": "295", "wifi.ap.qos.BK.deadline.us": "1000"}),
    "tighten": ("0,10", {"packet.2.type": "BK", "wifi.ap.qos.BE.deadline.us": "295", "wifi.ap.qos.BK.deadline.us": "520"}),
    "early": ("0,10", {"packet.2.type": "VO", "wifi.ap.qos.BE.deadline.us": "295", "wifi.ap.qos.VO.deadline.us": "250"}),
    "passive": ("0,50", {"packet.2.type": "VO", "wifi.ap.qos.VO.deadline.us": "480"}),
    "periodic-merge": ("0", {"wifi.ap.qos.BE.deadline.us": "295", "wifi.ap.control.period.us": "10"}),
}
DEADLINES = dict(zip(ACS, (650, 1400, 6000, 12000)))
IDENTITY = ("scenario", "seed", "policy", "service_model", "sfu_id")
EVENT_FIELDS = (
    "trace_sequence", "event", "t_ns", "trigger", "phase", "current_mode", "projected_mode", "candidate_mode", "target_mode",
    "action", "qos_status", "uncertain", "qos_reason", "reason", "ac", "packet_id", "depth", "queued_packets", "dl_queue",
    "deadline_ns", "required_on_time", "predicted_on_time", "predicted_misses", "earliest_remaining_ns", "queue_service_ns",
    "qos_local_wait_ns", "qos_cca_wait_ns", "qos_active_switch_ns", "qos_switch_ns", "qos_margin_ns", "qos_min_slack_ns",
    "capacity_shortfall_mbps", "max_delay_ns", "defer_id", "opened_ns", "due_ns", "active_delay_ns", "switch_id", "request_ns",
    "switch_start_ns", "switch_end_ns", "wait_ns", "duration_ns", "from_mode", "to_mode", "switch_energy_uj",
    "horizon_ns", "arrival_mbps", "ewma_mbps", "demand_bits", "ampdu_depth", "saturated_ampdu_depth", "saturated_service_mbps",
    "effective_service_mbps", "predicted_goodput_mbps", "success_bits", "unserved_bits", "mean_failure", "observed_busy_fraction",
    "expected_busy_fraction", "channel_confidence", "channel_incomplete", "window_uj", "tail_uj", "total_cost_uj",
)
METRIC_FIELDS = (
    "ac", "cutoff_ns", "input_sha256", "deadline_ns", "generated", "delivered", "dropped", "incomplete", "overdue",
    "on_time", "decided", "violations", "on_time_ratio", "violation_ratio", "drop_ratio", "goodput_mbps",
    "avg_ns", "p95_ns", "p99_ns", "jitter_ns", "queue_packets", "inflight_packets", "peak_queue_packets",
    "idle_ns", "tx_ns", "rx_ns", "switch_ns", "hcm_fraction", "switches_completed", "switch_energy_uj",
    "radio_energy_uj", "scan_energy_uj", "total_energy_uj", "energy_nj_per_success_bit", "deferrals_opened",
    "deferrals_merged", "deferrals_advanced", "deferrals_cancelled", "deferrals_released", "active_delay_ns",
    "cancelled_delay_ns", "passive_wait_ns", "urgent_decisions", "predicted_feasible", "predicted_risk",
)


def scenario_config(case, seed, policy):
    settings = base(seed, policy, 120000)
    settings["wifi.ap.control.service.model"] = "load-aware"
    for ac, deadline in DEADLINES.items():
        settings[f"wifi.ap.qos.{ac}.deadline.us"] = str(deadline)
    if case == "sustained-85":
        del settings["packet.bytes"]
        settings.update({"traffic.mode": "poisson", "traffic.duration.us": "120000", "traffic.sfu.1.seed": str(seed)})
        for ac, load, size in zip(ACS, (8, 15, 50, 12), (1000, 1500, 1500, 1500)):
            settings[f"traffic.sfu.1.class.{ac}.rate.mbps"] = str(load)
            settings[f"traffic.sfu.1.class.{ac}.packet.bytes"] = str(size)
        return settings

    associations = (1, 1, 2, 2)
    set_associations(settings, associations)
    arrivals = []
    for sfu, sta in ((1, 1), (2, 3)):
        offset = (sfu - 1) * 110
        arrivals.extend((t, sta, "VO", 500) for t in range(offset, 120000, 2000))
        arrivals.extend((t, sta + 1, "VI", 1000) for t in range(offset + 90, 120000, 1600))
        arrivals.extend((t, sta + ix % 2, "BE", 1500) for ix, t in enumerate(range(offset, 120000, 800)))
        arrivals.extend((t, sta + 1, "BK", 500) for t in range(offset + 60, 120000, 2700))
        for start, end in ((18500, 36500), (67000, 78100)):
            arrivals.extend((t, sta + ix % 2, "BE", 1500)
                            for ix, t in enumerate(range(start + offset, end + offset, 205)))
    arrivals.sort()
    settings["packet.arrival.us"] = ",".join(str(t) for t, *_ in arrivals)
    for packet, (_, sta, ac, size) in enumerate(arrivals, 1):
        if sta != 1:
            settings[f"packet.{packet}.sta.id"] = str(sta)
        if ac != "BE":
            settings[f"packet.{packet}.type"] = ac
        if size != 1500:
            settings[f"packet.{packet}.bytes"] = str(size)
    if case != "mixed-ideal":
        band = 0 if case == "mixed-primary" else 3
        radio_config(settings, associations, {1: (band,), 2: (band,)}, 16000, 97000, pulses=True)
    return settings


def cases(smoke):
    for model in ("d2-original", "load-aware"):
        settings = grid_config("ideal", 1500, SEEDS[0], "ec-aware")
        settings["wifi.ap.control.service.model"] = model
        yield "correction-85", SEEDS[0], "ec-aware", model, settings
    yield "correction-85", SEEDS[0], "always-hcm", "static", grid_config("ideal", 1500, SEEDS[0], "always-hcm")
    for scenario, (arrivals, extra) in DIAGNOSTICS.items():
        settings = base(SEEDS[0], "ecq-sdps", 1000 if scenario != "periodic-merge" else 400)
        settings.update({f"wifi.mac.edca.{ac}.cwmin": "0" for ac in ACS})
        settings.update(extra | {"packet.arrival.us": arrivals})
        yield scenario, SEEDS[0], "ecq-sdps", "load-aware", settings
    for scenario, seed, policy in product(MAIN, SEEDS[:1] if smoke else SEEDS, POLICIES):
        yield scenario, seed, policy, "load-aware" if policy != POLICIES[0] else "threshold", scenario_config(scenario, seed, policy)


def audit_qos(name, rows, settings):
    energy, qos = defaultdict(dict), defaultdict(dict)
    for kind, collection in ((D2_DECISION, energy), (QOS, qos)):
        for row in events(rows, kind):
            stamp = (row["sfu_id"], time_ns(row))
            mode = row["candidate_mode"]
            require(mode not in collection[stamp], f"{name}: duplicate candidate evaluation at {stamp}")
            collection[stamp][mode] = row
    require(energy.keys() == qos.keys() and bool(qos), f"{name}: missing energy/QoS pair")
    limit = int(Decimal(settings.get("wifi.ap.control.defer.max.us", "2000")) * 1000)
    for stamp, pair in qos.items():
        require(set(pair) == {"LCM", "HCM"} and set(energy[stamp]) == set(pair), f"{name}: incomplete decision")
        low, high = (pair[m] for m in ("LCM", "HCM"))
        target = high["target_mode"]
        require(low["target_mode"] == target, f"{name}: conflicting modes")
        feasible = {m for m, row in pair.items() if row["qos_status"] == "FEASIBLE"}
        require(not feasible or target in feasible, f"{name}: chose an infeasible mode when a feasible mode exists")
        for row in energy[stamp].values():
            audit_candidate(name, energy[stamp], row, settings)
        allowed = 0
        if target == "HCM" and "HCM" in feasible and energy[stamp]["HCM"]["projected_mode"] == "LCM":
            allowed = floor(max(0, min(limit, float(high["qos_min_slack_ns"]))))
        require(int(high["max_delay_ns"]) == int(low["max_delay_ns"]) == allowed, f"{name}: delay limit mismatch")

    for row in events(rows, DEADLINE):
        if int(row["queued_packets"]) == 1:
            plan = qos[(row["sfu_id"], time_ns(row))][row["candidate_mode"]]
            shared = sum(float(plan[k]) for k in ("qos_local_wait_ns", "qos_cca_wait_ns", "qos_active_switch_ns", "qos_switch_ns", "qos_margin_ns"))
            expected = float(row["earliest_remaining_ns"]) - float(row["queue_service_ns"]) - shared
            require(isclose(expected, float(row["qos_min_slack_ns"]), rel_tol=1e-10, abs_tol=1e-6),
                    f"{name}: single packet deadline calculation differed")


def audit_deferrals(name, rows):
    open_by_sfu, terminal = {}, set()
    requests = {(r["sfu_id"], r["switch_id"]): r for r in events(rows, "WIFI_AP_SWITCH_REQUEST")}
    for row in events(rows, DEFER):
        sfu, pid, phase = row["sfu_id"], row["defer_id"], row["phase"]
        now, opened, due = time_ns(row), int(row["opened_ns"]), int(row["due_ns"])
        require(int(row["active_delay_ns"]) == now - opened, f"{name}: wrong active delay")
        if phase == "OPEN":
            require(sfu not in open_by_sfu and (sfu, pid) not in terminal and opened == now < due
                    and due - now <= int(row["max_delay_ns"]), f"{name}: duplicate or invalid deferred request")
        else:
            old_id, old_start, old_due = open_by_sfu[sfu]
            require((old_id, old_start) == (pid, opened) and now <= old_due and due <= old_due, f"{name}: deferral moved later")
            if phase == "MERGE" or phase == "ADVANCE":
                require(now < due and ((phase == "ADVANCE") == (due < old_due)), f"{name}: incorrect merge or advance")
            else:
                terminal.add((sfu, pid))
                del open_by_sfu[sfu]
                if phase == "RELEASE":
                    request = requests[sfu, row["switch_id"]]
                    require(time_ns(request) == now and request["to_mode"] == "HCM", f"{name}: release lacks matching B4 request")
                continue
        open_by_sfu[sfu] = (pid, opened, due)
    require(not open_by_sfu, f"{name}: a deferred request was never resolved")


def per_ap_accounting(name, rows, queue, packets, endpoint, switches, initial, cutoff):
    accounts = {}
    all_defers = events(rows, DEFER)
    starts = events(rows, "WIFI_AP_SWITCH_START")
    ends = events(rows, "WIFI_AP_SWITCH_END")
    all_qos = [r for r in events(rows, QOS) if r["target_mode"] == r["candidate_mode"]]
    for sfu, mode in initial.items():
        energy = audit_energy(rows, mode, cutoff, sfu, switches[sfu])
        scan = sum((Decimal(r["extra_energy_uj"]) for r in events(rows, "WIFI_CHANNEL_SCAN")
                    if r["sfu_id"] == sfu and r["phase"] == "END"), Decimal(0))
        if any(r.get("summary") == "WIFI_CHANNEL_OVERHEAD" for r in rows):
            require(Decimal(by_summary(rows, "WIFI_CHANNEL_OVERHEAD", sfu_id=sfu)["scan_energy_uj"]) == scan, f"{name}: scan energy mismatch")
        energy["radio_energy_uj"] = energy["total_energy_uj"]
        energy["total_energy_uj"] += scan
        energy["scan_energy_uj"] = scan
        local = [r for r in all_defers if r["sfu_id"] == sfu and time_ns(r) <= cutoff]
        quality = [r for r in all_qos if r["sfu_id"] == sfu and time_ns(r) <= cutoff]
        cutoff_count = packet_metrics(name, rows, queue, packets, endpoint, cutoff, sfu, "ALL")
        actual_ends = [r for r in ends if r["sfu_id"] == sfu and time_ns(r) <= cutoff]
        start_by_id = {r["switch_id"]: r for r in starts if r["sfu_id"] == sfu}
        waits = sum(int(start_by_id[r["switch_id"]]["wait_ns"]) for r in actual_ends)
        counters = {"deferrals_" + title: sum(r["phase"] == phase for r in local) for title, phase in (
            ("opened", "OPEN"), ("merged", "MERGE"), ("advanced", "ADVANCE"), ("released", "RELEASE"), ("cancelled", "CANCEL"))}
        accounts[sfu] = energy | counters | {
            "active_delay_ns": sum(int(r["active_delay_ns"]) for r in local if r["phase"] == "RELEASE"),
            "cancelled_delay_ns": sum(int(r["active_delay_ns"]) for r in local if r["phase"] == "CANCEL"),
            "passive_wait_ns": waits,
            "urgent_decisions": sum(r["trigger"] == "URGENT_QUEUE" for r in quality),
            "predicted_feasible": sum(r["qos_status"] == "FEASIBLE" for r in quality),
            "predicted_risk": sum(r["qos_status"] != "FEASIBLE" for r in quality),
            **{key: cutoff_count[key] for key in ("peak_queue_packets", "inflight_packets", "queue_packets")},
        }
    require(sum(a["radio_energy_uj"] for a in accounts.values()) ==
            Decimal(by_summary(rows, "WIFI_AP_ENERGY_NETWORK")["total_energy_uj"]), f"{name}: network energy mismatch")
    return accounts


def report_metrics(name, rows, queue, packets, endpoint, accounts, cutoff, settings, meta):
    delivered, dropped, order = endpoint
    actual_sfu = {r["sfu_id"] for r in events(rows, INITIAL)}
    quality = {(r["sfu_id"], r["ac"]): r for r in events(rows, "WIFI_AP_QOS") if r["direction"] == "DL"}
    groups = []
    for sfu in sorted(actual_sfu, key=int) + ["ALL"]:
        for ac in (*ACS, "ALL"):
            keys = [p for p, data in packets.items() if (sfu == "ALL" or sfu == data[5]) and (ac == "ALL" or ac == data[2])]
            if not keys and ac != "ALL":
                continue
            deadline = int(Decimal(settings[f"wifi.ap.qos.{ac}.deadline.us"]) * 1000) if ac != "ALL" else 0
            actual = metrics_at(keys, cutoff, deadline, packets, delivered, dropped, order)
            if sfu != "ALL" and ac != "ALL":
                record = quality[sfu, ac]
                require(all(actual[k] is None or isclose(float(record[k]), actual[k], abs_tol=1e-3, rel_tol=1e-9)
                            for k in ("arrived", "delivered", "dropped", "incomplete", "overdue", "on_time", "violations",
                                      "p95_ns", "p99_ns", "jitter_ns")), f"{name}: QoS ledger differs for {sfu}/{ac}")
            independent = dict(generated=actual["arrived"], delivered=actual["delivered"], dropped=actual["dropped"],
                               incomplete=actual["incomplete"], goodput_mbps=actual["goodput_mbps"],
                               **{k: actual[k] for k in ("p95_ns", "p99_ns", "avg_ns")})
            if ac != "ALL":
                independent.update(deadline_ns=deadline, decided=actual["delivered"] + actual["dropped"] + actual["overdue"],
                                   **{k: actual[k] for k in ("overdue", "on_time", "violations", "jitter_ns", "violation_ratio", "drop_ratio")},
                                   on_time_ratio=actual["on_time_ratio"])
                if sfu != "ALL":
                    counts = [r for r in queue if r["sfu_id"] == sfu and r["ac"] == ac and time_ns(r) <= cutoff]
                    independent.update(queue_packets=int(counts[-1]["pending_packets"]) if counts else 0,
                                       inflight_packets=int(counts[-1]["inflight_packets"]) if counts else 0,
                                       peak_queue_packets=max((int(r["pending_packets"]) for r in counts), default=0))
            else:
                selected = (accounts[sfu],) if sfu != "ALL" else tuple(accounts.values())
                independent.update({key: sum(a[key] for a in selected) for key in METRIC_FIELDS if key in selected[0]
                                    and key not in ("hcm_fraction", "peak_queue_packets")})
                independent["peak_queue_packets"] = max(a["peak_queue_packets"] for a in selected)
                independent["hcm_fraction"] = sum(a["hcm_fraction"] for a in selected) / len(selected)
                bits = sum(packets[p][1] * 8 for p in keys if delivered.get(p, cutoff + 1) <= cutoff)
                independent["energy_nj_per_success_bit"] = independent["total_energy_uj"] * 1000 / bits if bits else None
            groups.append(meta | {"sfu_id": sfu, "ac": ac, "cutoff_ns": cutoff} | independent)
    return groups


def run_case(case, jar, work):
    scenario, seed, policy, service, settings = case
    name = f"{scenario}-{seed}-{policy}-{service}"
    path = work / f"{name}.properties"
    write_properties(path, settings)
    raw = simulate(jar, path)
    if scenario == "correction-85" or scenario in DIAGNOSTICS or (scenario == "mixed-secondary" and seed == SEEDS[0]):
        (work / f"{name}.log").write_text(raw, encoding="utf-8")
    rows = parse_trace(raw)
    packets = packet_inputs(rows, settings)
    digest = sha256(repr(list(packets.items())).encode()).hexdigest()
    cutoff = int(settings["wifi.ap.activity.window.us"]) * 1000
    endpoint = endpoint_state(name, rows, packets)
    queue = verify_queue(name, rows)
    initial = {r["sfu_id"]: r["current_mode"] for r in events(rows, INITIAL)}
    if policy.startswith("ecq-"):
        audit_qos(name, rows, settings)
    elif policy == "always-hcm":
        require(set(initial.values()) == {"HCM"}, f"{name}: HCM reference did not start in HCM")
    else:
        audit_controller(name, rows, queue, settings, cutoff)
    audit_deferrals(name, rows)
    switches = audit_switches(name, rows, settings, cutoff)
    if scenario == "correction-85":
        # 保留原始 D2 复现，修正版必须通过容量估计而非驻留限制解决这一用例。
        expected_switches = {"d2-original": 7, "load-aware": 1, "static": 0}
        require(len(switches["6"]) == expected_switches[service], f"{name}: unexpected 85 Mb/s regression")
    if scenario in DIAGNOSTICS:
        phases = tuple(r["phase"] for r in events(rows, DEFER))
        expected = {"delay-be": ("OPEN", "RELEASE"), "delay-bk": ("OPEN", "RELEASE"), "cancel": ("OPEN", "CANCEL"),
                    "early": ("OPEN", "RELEASE"), "merge": ("OPEN", "MERGE", "RELEASE"),
                    "tighten": ("OPEN", "ADVANCE", "RELEASE"),
                    "periodic-merge": ("OPEN", "MERGE", "RELEASE")}.get(scenario, ())
        require(phases == expected, f"{name}: wanted deferral lifecycle {expected}, got {phases}")
    accounts = per_ap_accounting(name, rows, queue, packets, endpoint, switches, initial, cutoff)
    meta = {"scenario": scenario, "seed": seed, "policy": policy, "service_model": service}
    metrics = report_metrics(name, rows, queue, packets, endpoint, accounts, cutoff, settings,
                             meta | {"input_sha256": digest})
    decision_rows = []
    for row in rows:
        event = row.get("event")
        extra = ((scenario in DIAGNOSTICS and event in ("WIFI_TX_START", "DELIVERED", "WIFI_ACK_END")) or
                 (scenario == "correction-85" and event in ("WIFI_AP_PPDU_OBSERVED", "DELIVERED")))
        if scenario == "correction-85" and row.get("sfu_id") != "6":
            continue
        # Keep all requests and QoS choices. Detailed per-AC and energy inputs for long runs use the same representative seed.
        if scenario in MAIN and seed != SEEDS[0] and event in (D2_DECISION, DEADLINE):
            continue
        if event in (INITIAL, D2_DECISION, QOS, DEADLINE, DEFER, "WIFI_AP_MODE_DECISION", *SWITCH_EVENTS) or extra:
            fields = {key: row[key] for key in EVENT_FIELDS if key in row}
            if event == D2_DECISION:
                fields.setdefault("predicted_goodput_mbps", float(row["success_bits"]) * 1000 / int(row["horizon_ns"]))
            decision_rows.append(meta | fields | {"sfu_id": row["sfu_id"], "t_ns": time_ns(row)})
    print(f"{name}: generated={len(packets)} delivered={len(endpoint[0])} dropped={len(endpoint[1])} "
          f"switches={sum(len(s) for s in switches.values())}", flush=True)
    return (scenario, seed), digest, decision_rows, metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    expected, traces, measurements = {}, [], []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for group, inputs, rows, metrics in pool.map(lambda case: run_case(case, args.jar, args.work), cases(args.smoke)):
            require(expected.setdefault(group, inputs) == inputs, f"{group}: unpaired packet inputs")
            traces.extend(rows)
            measurements.extend(metrics)
    write_csv(args.output / "d3-decision-requests.csv", IDENTITY + EVENT_FIELDS, traces)
    write_csv(args.output / "d3-performance.csv", IDENTITY + METRIC_FIELDS, measurements)


if __name__ == "__main__":
    main()
