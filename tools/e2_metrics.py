"""Rebuild the trial metrics from actual Java events and check the frozen protocol's accounting."""

from __future__ import annotations

from collections import defaultdict
from decimal import Decimal
from hashlib import sha256
from statistics import mean

from e2_design import DEADLINES, ECQ, arrivals, properties
from pon_experiment_io import ROOT, require
from run_b4_ap_switch import by_summary
from verify_c1_ap_observation import time_ns
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import endpoint_state, events, ns, packet_inputs, quantile
from verify_d1_mode_control import INITIAL, audit_switches
from verify_d3_qos_deferral import DEFER, audit_deferrals
from verify_d5_ecq_sdps import audit_optical, energy_account

ENDPOINTS = {"DELIVERED", "WIFI_RETRY_LIMIT_DROP", "WIFI_COLLISION_DROP"}
NETWORK = ("ALL", "BOTH", "ALL")


def summarize_packets(keys, packets, delivery, drops, order, cutoff, optical, deadline_ns=None):
    if deadline_ns is None:
        deadline_ns = {ac: us * 1000 for ac, us in DEADLINES.items()}
    made = len(keys)
    finished = sorted((p for p in keys if p in delivery), key=order.__getitem__)
    dropped = [p for p in keys if p in drops]
    window = [p for p in finished if delivery[p] <= cutoff]
    drop_window = sum(drops[p] <= cutoff for p in dropped)
    final_pending = made - len(finished) - len(dropped)
    window_pending = made - len(window) - drop_window
    delays = {p: delivery[p] - packets[p][0] for p in finished}
    on_time = sum(delays[p] <= deadline_ns[packets[p][2]] for p in finished)
    window_on_time = sum(delays[p] <= deadline_ns[packets[p][2]] for p in window)
    overdue = sum(delivery.get(p, drops.get(p, cutoff + 1)) > cutoff >= packets[p][0] + deadline_ns[packets[p][2]]
                  for p in keys)
    require(window_pending == len(finished) - len(window) + len(dropped) - drop_window + final_pending,
            "Window and final packet counts do not reconcile")
    streams = defaultdict(list)
    for packet in finished:
        _, _, ac, direction, sta, sfu = packets[packet]
        streams[sfu, sta, direction, ac].append(delays[packet])
    jitter = [abs(b - a) for flow in streams.values() for a, b in zip(flow, flow[1:])]
    delay_ms = [value / 1e6 for value in delays.values()]
    offered_bits = sum(packets[p][1] * 8 for p in keys)
    window_bits = sum(packets[p][1] * 8 for p in window)
    final_bits = sum(packets[p][1] * 8 for p in finished)
    return dict(
        generated=made, offered_bits=offered_bits, offered_mbps=offered_bits * 1000 / cutoff,
        delivered_window=len(window), window_bits=window_bits, goodput_mbps=window_bits * 1000 / cutoff,
        dropped_window=drop_window, backlog_window=window_pending, overdue_window=overdue, on_time_window=window_on_time,
        delivered_final=len(finished), final_bits=final_bits, dropped_final=len(dropped), unsettled_final=final_pending,
        delivered_in_drain=len(finished) - len(window), dropped_in_drain=len(dropped) - drop_window,
        on_time_final=on_time, violations_final=made - on_time, delay_samples=len(delays), jitter_pairs=len(jitter),
        on_time_pct=100 * on_time / made if made else None, drop_pct=100 * len(dropped) / made if made else None,
        final_success_pct=100 * len(finished) / made if made else None,
        mean_ms=mean(delay_ms) if delay_ms else None, p95_ms=quantile(delay_ms, .95), p99_ms=quantile(delay_ms, .99),
        jitter_ms=mean(jitter) / 1e6 if jitter else None,
        optical_mean_us=mean(optical[p] for p in finished) / 1000 if finished else None,
        wireless_mean_us=mean(delays[p] - optical[p] for p in finished) / 1000 if finished else None,
    )


def mode_intervals(initial, switches, cutoff):
    """Run-length encode each AP timeline for the representative traces."""
    segments = []
    for sfu, initial_mode in initial.items():
        cursor, mode = 0, initial_mode
        for row in switches[sfu]:
            if row["start_ns"] >= cutoff:
                break
            segments.extend(((sfu, mode, cursor, row["start_ns"]),
                             (sfu, "SWITCH", row["start_ns"], min(cutoff, row["finish_ns"]))))
            cursor, mode = min(cutoff, row["finish_ns"]), row["target"]
        segments.append((sfu, mode, cursor, cutoff))
    return [{"sfu_id": sfu, "mode": mode, "start_ns": start, "end_ns": end}
            for sfu, mode, start, end in segments if start < end]


def external_busy(rows, initial, cutoff):
    last = {sfu: (0, False) for sfu in initial}
    totals = dict.fromkeys(initial, 0)
    for row in events(rows, "WIFI_EXTERNAL_CCA"):
        now, sfu = time_ns(row), row["sfu_id"]
        if now > cutoff:
            continue
        prev, busy = last[sfu]
        require(now >= prev, "CCA trace is not monotone")
        totals[sfu] += (now - prev) if busy else 0
        last[sfu] = now, row["busy"] == "true"
    return {sfu: total + (cutoff - last[sfu][0]) * last[sfu][1] for sfu, total in totals.items()}


def summarize_run(case, seed, policy, path, signature, representative, *, fixture=None, cfg=None, rows=None):
    name = f"{case.name}/{seed}/{policy}/{case.window_us}"
    if fixture is None:
        fixture = arrivals(case, seed)
    if cfg is None:
        cfg = properties(case, seed, policy, fixture)
    if rows is None:
        rows = parse_trace(path.with_suffix(".log").read_text(encoding="utf-8"))
    deadline_ns = {ac: ns(cfg[f"wifi.ap.qos.{ac}.deadline.us"]) for ac in DEADLINES}
    packets = packet_inputs(rows, cfg)
    expected = {str(p): (us * 1000, size, ac, direction, str(sta), str(case.associations[sta - 1]))
                for p, (us, direction, sta, ac, size) in enumerate(fixture, 1)}
    require(packets == expected, f"{name}: actual per-packet input differs from the paired fixture")
    end = case.window_us * 1000
    terminal = {row["packet_id"] for row in rows if row.get("event") in ENDPOINTS}
    unsettled = set(packets) - terminal
    require(all(packets[p][3] == "UL" for p in unsettled), f"{name}: downlink has unresolved frames at natural drain")
    delivery, drops, order = endpoint_state(name, rows, {p: data for p, data in packets.items() if p not in unsettled})
    initial = {r["sfu_id"]: r["current_mode"] for r in events(rows, INITIAL)}
    require(set(initial) == {str(sfu) for sfu in range(1, case.sfus + 1)}, f"{name}: missing AP state")
    switches = audit_switches(name, rows, cfg, end)
    audit_deferrals(name, rows)
    if policy in (ECQ, "ecq-sdps-e3"):
        require(all(a["finish_ns"] < b["start_ns"] for seq in switches.values() for a, b in zip(seq, seq[1:])),
                f"{name}: immediate mode reversal")
    drained = max([end, *delivery.values(), *drops.values(), *(time_ns(r) for r in events(rows, "WIFI_AP_ACTIVITY_CHANGE"))])
    simulator_end = ns(next(row["simulation_end_us"] for row in rows if "simulation_end_us" in row))
    if unsettled:
        drained = max(drained, simulator_end)
        require(all(packets[p][0] + deadline_ns[packets[p][2]] <= drained for p in unsettled),
                f"{name}: deadline for an unresolved packet has not elapsed")
    power = energy_account(name, rows, initial, end, drained, switches, cfg)
    cca = external_busy(rows, initial, end)
    local_dl = {}
    for row in events(rows, "ARRIVAL"):
        if row["node"] == "SFU" and row.get("direction", "DL") == "DL":
            p = row["packet_id"]
            require(p not in local_dl, f"{name}: downlink reached the AP twice")
            local_dl[p] = time_ns(row)
    require(set(local_dl) == {p for p in packets if packets[p][3] == "DL"}, f"{name}: wrong destination in downlink")
    for row in events(rows, "DELIVERED"):
        _, _, _, direction, sta, sfu = packets[row["packet_id"]]
        require(row["sfu_id"] == sfu and ((row["node"], row["sta_id"]) == ("STA", sta) if direction == "DL"
                else (row["node"], row["source_sta_id"], row["destination_mfu_id"]) == ("MFU", sta, "1")),
                f"{name}: wrong final endpoint")
    optical, utilization = audit_optical(name, rows, packets, delivery, local_dl, cfg, end, unsettled)
    pon = by_summary(rows, "PON_DBA_EXECUTION")
    polling = by_summary(rows, "PON_POLLING")
    audit = by_summary(rows, "E2_AUDIT")
    require(all(audit[k] == pon[k] for k in ("grants", "budget_bytes", "actual_bytes", "unused_bytes", "reserved_ns", "actual_data_ns"))
            and int(pon["actual_bytes"]) == sum(packets[p][1] for p in delivery if packets[p][3] == "UL")
            and int(polling["report_busy_ns"]) == 205 * int(polling["polls"]),
            f"{name}: PON budget, transmitted bytes or REPORT-only clock mismatch")
    cells = defaultdict(list)
    for p, data in packets.items():
        _, _, ac, direction, _, sfu = data
        for key in (NETWORK, ("ALL", "BOTH", ac), ("ALL", direction, "ALL"), ("ALL", direction, ac),
                    (sfu, "BOTH", "ALL"), (sfu, direction, ac)):
            cells[key].append(p)
    for sfu in ("ALL", *initial):
        cells.setdefault((sfu, "BOTH", "ALL"), [])
    qos = {(r["sfu_id"], r["direction"], r["ac"]): r for r in events(rows, "WIFI_AP_QOS")}
    ordering = {p: i for i, p in enumerate(order)}
    result = []
    for (sfu, direction, ac), keys in cells.items():
        metric = summarize_packets(keys, packets, delivery, drops, ordering, end, optical, deadline_ns)
        if sfu != "ALL" and direction != "BOTH":
            actual = [int(qos[sfu, direction, ac][k]) for k in ("arrived", "delivered", "dropped", "incomplete", "overdue", "on_time")]
            require(actual == [metric[k] for k in ("generated", "delivered_window", "dropped_window", "backlog_window",
                                                   "overdue_window", "on_time_window")], f"{name}: C4 deadline ledger mismatch")
        if direction == "BOTH" and ac == "ALL":
            group_power = power[sfu]
            ap_count = case.sfus if sfu == "ALL" else 1
            total = group_power["total_uj"]
            final = total + group_power["drain_uj"]
            if sfu != "ALL" and cfg.get("wifi.channel.observation.enabled") == "true":
                channel = by_summary(rows, "WIFI_CHANNEL_OVERHEAD", sfu_id=sfu)
                require(group_power["scan_uj"] == Decimal(channel["scan_energy_uj"])
                        and int(channel["feedback_airtime_ns"]) == 0, f"{name}: scan or feedback energy mismatch")
            deferred = [r for r in events(rows, DEFER) if time_ns(r) <= end and (sfu == "ALL" or r["sfu_id"] == sfu)]
            metric.update(group_power, complete_uj=final, drain_ns=drained - end,
                          network_power_w=total * 1000 / end, power_w_per_ap=total * 1000 / (end * ap_count),
                          goodput_mbps_per_ap=metric["goodput_mbps"] / ap_count,
                          nj_per_success_bit=1000 * total / metric["window_bits"] if metric["window_bits"] else None,
                          nj_per_final_bit=1000 * final / metric["final_bits"] if metric["final_bits"] else None,
                          hcm_pct=100 * group_power["hcm_ns"] / (end * ap_count),
                          external_busy_pct=100 * (sum(cca.values()) if sfu == "ALL" else cca[sfu]) / (end * ap_count),
                          deferrals_opened=sum(r["phase"] == "OPEN" for r in deferred),
                          deferrals_released=sum(r["phase"] == "RELEASE" for r in deferred),
                          deferrals_cancelled=sum(r["phase"] == "CANCEL" for r in deferred),
                          active_delay_us=sum(int(r["active_delay_ns"]) for r in deferred if r["phase"] == "RELEASE") / 1000)
        if (sfu, direction, ac) == NETWORK:
            metric.update(pon_data_util_pct=100 * utilization, settlement_end_ns=drained, simulator_end_ns=simulator_end,
                          pon_budget_bytes=int(pon["budget_bytes"]), pon_actual_bytes=int(pon["actual_bytes"]),
                          pon_unused_bytes=int(pon["unused_bytes"]), pon_polls=int(polling["polls"]),
                          pon_poll_busy_ns=int(polling["report_busy_ns"]), sinr_errors=int(audit["sinr_errors"]),
                          decoded=int(audit["decoded"]), mac_collision_errors=int(audit["collision_errors"]),
                          retries=int(audit["retries"]))
        result.append(dict(sfu_id=sfu, direction=direction, ac=ac) | metric)
    # An actual input digest covers ID, generation time, size, AC, direction and both destination identifiers.
    digest = sha256(repr(sorted(packets.items(), key=lambda item: int(item[0]))).encode()).hexdigest()
    meta = case.metadata() | dict(policy=policy, seed=seed, input_sha256=digest,
                                  config_sha256=sha256(path.with_suffix(".properties").read_bytes()).hexdigest(),
                                  java_experiment_sha256=signature, trace_file=path.with_suffix(".log").relative_to(ROOT).as_posix(),
                                  config_file=path.with_suffix(".properties").relative_to(ROOT).as_posix())
    timeline = mode_intervals(initial, switches, end) if representative else []
    return meta, result, timeline
