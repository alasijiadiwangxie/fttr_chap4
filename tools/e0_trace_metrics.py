"""Reduce E0's baseline, delay, QoS, and optical records without changing simulation state."""

from __future__ import annotations

from collections import Counter, defaultdict, deque
from decimal import Decimal
from hashlib import sha256
from math import isfinite

from e0_switch_metrics import ACS, ACTIVITY, CANCEL, CCA, DECODE, END, PREDICT, QOS, QUEUE, REQUEST, START
from e0_switch_metrics import by_candidate, decision_key, overlap, switch_key
from pon_experiment_io import require
from run_b4_ap_switch import by_summary
from verify_c1_ap_observation import time_ns
from verify_c_phase import endpoint_state, events, packet_inputs
from verify_d1_mode_control import INITIAL, audit_switches
from verify_d3_qos_deferral import DEFER, audit_deferrals
from verify_d5_ecq_sdps import DECISION, audit_optical, energy_account, summarize_cohorts


def deferral_records(trial, rows):
    """A proposal is one LCM→HCM decision. OPEN is a new delay episode, not MERGE or ADVANCE."""
    energy = by_candidate(rows, PREDICT)
    qos = by_candidate(rows, QOS)
    lifecycle = {decision_key(r): r for r in events(rows, DEFER)}
    rise = [r for r in events(rows, DECISION) if r["current_mode"] == "LCM" and r["target_mode"] == "HCM"]
    issued = [r for r in events(rows, REQUEST) if r["to_mode"] == "HCM"]
    cancelled = [r for r in events(rows, CANCEL) if r["to_mode"] == "HCM"]
    starts = {switch_key(r): r for r in events(rows, START)}
    finishes = {switch_key(r): r for r in events(rows, END)}
    result = []
    gates = Counter()
    for row in rise:
        sfu_id, now = decision_key(row)
        plan = qos[sfu_id, now, "HCM"]
        allowed, chosen = int(row["allowed_delay_ns"]), int(row["chosen_delay_ns"])
        cost, immediate = float(row["deferred_uj"]), float(row["immediate_uj"])
        high = energy[sfu_id, now, "HCM"]
        phase = lifecycle.get((sfu_id, now), {})
        if trial.variant == "immediate":
            gate = "FEATURE_OFF"
        elif row["change_qos"] != "FEASIBLE":
            gate = "HCM_QOS_RISK"
        elif row["qos_target"] != "HCM":
            gate = "QOS_PLAN_PREFERS_LCM"
        elif int(plan["max_delay_ns"]) == 0:
            gate = "NO_QOS_SLACK"
        elif allowed == 0:
            gate = "NO_ROOM_BEFORE_EXISTING_DUE_OR_CUTOFF"
        elif row["reason"] == "URGENT_EXCEPTION":
            gate = "URGENT_BYPASS"
        elif not isfinite(cost):
            if allowed <= int(high["local_wait_ns"]) or allowed >= int(high["horizon_ns"]) or int(high["active_switch_ns"]) > 0:
                gate = "EXCHANGE_BUSY_OR_HORIZON"
            else:
                gate = "NO_COMPLETE_LCM_BATCH" if int(row["deferred_batches"]) == 0 else "NONFINITE_DELAY_COST"
        elif chosen == 0:
            gate = "DELAY_COST_NOT_LOWER"
        else:
            gate = "DELAY_SELECTED"
            require(phase.get("phase") in ("OPEN", "MERGE", "ADVANCE"), f"{trial.name}: selected delay not applied")
        gates[gate] += 1
        start = starts.get(switch_key(row), {}) if row["action"] == "REQUESTED" else {}
        finish = finishes.get(switch_key(row), {}) if row["action"] == "REQUESTED" else {}
        result.append(trial.identity() | dict(kind="PROPOSAL", t_ns=now, sfu_id=sfu_id, gate=gate,
                       decision_trigger=row["trigger"], reason=row["reason"], action=row["action"],
                       keep_qos=row["keep_qos"], hcm_qos=row["change_qos"], qos_max_delay_ns=int(plan["max_delay_ns"]),
                       allowed_delay_ns=allowed, chosen_delay_ns=chosen, hcm_qos_slack_ns=plan["qos_min_slack_ns"],
                       local_wait_ns=high["local_wait_ns"], lcm_batches=row["deferred_batches"], lcm_success_bits=row["deferred_bits"],
                       delayed_cost_uj=cost if isfinite(cost) else None, immediate_cost_uj=immediate,
                       b4_request_ns=now if row["action"] == "REQUESTED" else None,
                       b4_start_ns=start.get("switch_start_ns"), b4_wait_ns=start.get("wait_ns"),
                       b4_finish_ns=finish.get("switch_end_ns"),
                       switch_id=row["switch_id"], defer_id=phase.get("defer_id"), phase=phase.get("phase")))
    for row in events(rows, DEFER):
        result.append(trial.identity() | dict(kind="EPISODE", **{key: row[key] for key in (
            "t_ns", "sfu_id", "phase", "trigger", "opened_ns", "due_ns", "defer_id", "active_delay_ns", "switch_id")}))
    phases = Counter(row["phase"] for row in events(rows, DEFER))
    require(phases["OPEN"] == phases["RELEASE"] + phases["CANCEL"], f"{trial.name}: unresolved deferred episode")
    return result, dict(hcm_proposals=len(rise), hcm_b4_requests=len(issued), hcm_b4_cancellations=len(cancelled),
                        delay_allowed_proposals=sum(r["allowed_delay_ns"] > 0 for r in result if r["kind"] == "PROPOSAL"),
                        delay_opened=phases["OPEN"], delay_released=phases["RELEASE"], delay_cancelled=phases["CANCEL"],
                        delay_merged=phases["MERGE"], delay_advanced=phases["ADVANCE"],
                        released_wait_ns=sum(int(r["active_delay_ns"]) for r in events(rows, DEFER) if r["phase"] == "RELEASE"),
                        cancelled_wait_ns=sum(int(r["active_delay_ns"]) for r in events(rows, DEFER) if r["phase"] == "CANCEL"),
                        **{f"gate_{key.lower()}": count for key, count in gates.items()})


def account_trial(trial, rows):
    packets = packet_inputs(rows, trial.settings)
    end = trial.end_ns
    done = {row["packet_id"] for row in events(rows, "DELIVERED")}
    lost = {row["packet_id"] for event in ("WIFI_RETRY_LIMIT_DROP", "WIFI_COLLISION_DROP") for row in events(rows, event)}
    unsettled = set(packets) - done - lost
    initial = {r["sfu_id"]: r["current_mode"] for r in events(rows, INITIAL)}
    releases = {p: timing for p, timing in packets.items() if p not in unsettled}
    delivery, drops, order = endpoint_state(trial.name, rows, releases)
    is_optical = trial.group.startswith("pon-")
    require(is_optical or not unsettled, f"{trial.name}: unresolved wireless queue")
    local_dl = {r["packet_id"]: time_ns(r) for r in events(rows, QUEUE) if r["direction"] == "DL" and r["action"] == "ENQUEUED"}
    optical, util = audit_optical(trial.name, rows, packets, delivery, local_dl, trial.settings, end, unsettled)
    closed = max([end, *delivery.values(), *drops.values(), *(time_ns(r) for r in events(rows, ACTIVITY))])
    if is_optical and unsettled:
        last_pon = int(Decimal(by_summary(rows, "PON_UPLINK")["window_us"]) * 1000)
        closed = max(closed, last_pon)

    if initial:
        # The same observers, reference formulas, cutoff, and B3/B4 reconciliation are used by D5.
        switches = audit_switches(trial.name, rows, trial.settings, end)
        audit_deferrals(trial.name, rows)
        metrics = summarize_cohorts(trial.name, rows, packets, (delivery, drops, order), end, optical, trial.settings)
        quality = {r["ac"]: r for r in metrics if r["sfu_id"] == "ALL" and r["direction"] == "BOTH"}
        energy = energy_account(trial.name, rows, initial, end, closed, switches, trial.settings)["ALL"]
        delivered_bytes = sum(packets[p][1] for p in delivery)
        summary = {key: value for key, value in quality["ALL"].items() if key not in ("sfu_id", "direction", "ac", "bits", "final_bits")}
        summary.update(energy)
        summary.update(delivered_bytes_final=delivered_bytes,
                       energy_nj_per_final_bit=(energy["total_uj"] + energy["drain_uj"]) * 1000 / (8 * delivered_bytes) if delivered_bytes else None)
        for ac in ACS:
            row = quality.get(ac, {})
            for key in ("generated", "on_time_final", "on_time_ratio", "p99_ms", "backlog_window", "dropped_final", "unsettled_final"):
                summary[f"{ac}_{key}"] = row.get(key)
    else:
        nw = sum(t <= end for t in delivery.values())
        summary = dict(generated=len(packets), delivered_window=nw, delivered_final=len(delivery), dropped_final=len(drops),
                       backlog_window=len(packets) - nw, unsettled_final=len(unsettled), energy_scope="PON_ONLY_NO_AP_MODEL")
    summary.update(input_sha256=sha256(repr(list(packets.items())).encode()).hexdigest(), drain_ns=closed - end,
                   end_of_account_ns=closed, pon_data_utilization=util,
                   vo_deadline_us=trial.settings.get("wifi.ap.qos.VO.deadline.us"),
                   vi_deadline_us=trial.settings.get("wifi.ap.qos.VI.deadline.us"),
                   pon_poll_cutoff_us=trial.settings.get("fiber.uplink.poll.end.us"),
                   urgent_decisions=sum(r["trigger"] == "URGENT_QUEUE" for r in events(rows, DECISION)),
                   urgent_requests=sum(r["trigger"] == "URGENT_QUEUE" and r["action"] == "REQUESTED" for r in events(rows, DECISION)),
                   sinr_failed_attempts=sum(time_ns(r) <= end and r["outcome"] == "SINR_ERROR" for r in events(rows, DECODE)))
    return trial.identity() | summary, packets, delivery, unsettled


def pon_lifecycle(trial, rows, unsettled):
    """Join the MFU's frame-start account with each SFU's load and subsequent settlement."""
    frames = {r["target_frame_id"]: r for r in events(rows, "PON_DBA_MULTI_PLAN")}
    demand = {(r["allocation_id"], r["time_ns"]): r for r in events(rows, "TCONT_DEMAND") if r["trigger"] == "FRAME_BOUNDARY"}
    queue = defaultdict(deque)
    enqueue, head_fill = {}, {}
    slot, settle, receipts = {}, {}, {}
    for row in rows:
        event = row.get("event")
        if event == "TCONT_ENQUEUE":
            alloc, pid = row["allocation_id"], row["packet_id"]
            queue[alloc].append(pid)
            enqueue[pid] = row
        elif event == "PON_DBA_FILL":
            alloc = row["allocation_id"]
            head = queue[alloc][0] if queue[alloc] else None
            ids = row["selected_packet_ids"].split(";") if row["selected_packet_ids"] != "-" else []
            require([queue[alloc].popleft() for _ in ids] == ids, f"{trial.name}: non-FIFO selection")
            head_fill[row["target_frame_id"], alloc] = row, head, queue[alloc][0] if queue[alloc] else None
        elif event == "PON_DBA_SLOT_END":
            slot[row["grant_id"]] = row
        elif event == "PON_DBA_SETTLEMENT_RX":
            settle[row["grant_id"]] = row
        elif event == "PON_DBA_BWMAP_RX":
            receipts[row["grant_id"]] = row
    require(set(p for q in queue.values() for p in q) == unsettled, f"{trial.name}: unresolved PON queue mismatch")
    require(slot.keys() == settle.keys() == receipts.keys(), f"{trial.name}: missing BWmap or settlement")
    for grant_id, end in slot.items():
        receipt, settled = receipts[grant_id], settle[grant_id]
        require(int(receipt["time_ns"]) < int(end["tx_start_ns"]) < int(end["time_ns"]) < int(settled["time_ns"]),
                f"{trial.name}: receipt or settlement violates causality")
        require(int(end["actual_bytes"]) + int(settled["released_unused_bytes"]) == int(end["budget_bytes"]),
                f"{trial.name}: reservation did not balance at settlement")
    summary = by_summary(rows, "PON_DBA_EXECUTION")
    require(int(summary["budget_bytes"]) == sum(int(row["budget_bytes"]) for row in slot.values())
            and int(summary["unused_bytes"]) == sum(int(row["released_unused_bytes"]) for row in settle.values()),
            f"{trial.name}: aggregate grant bytes mismatch")

    result = []
    blocked_allocations = {enqueue[packet]["allocation_id"] for packet in unsettled}
    period = int(trial.settings["fiber.uplink.frame.period.us"]) * 1000
    for row in events(rows, "PON_GIANT_DECISION"):
        key = row["target_frame_id"], row["allocation_id"]
        frame = frames[key[0]]
        observed = demand[key[1], row["time_ns"]]
        if observed["state"] == "KNOWN":
            expected = max(0, int(observed["reported_pending_bytes"]) - int(observed["granted_bytes_since_snapshot"]))
            require(expected == int(row["known_demand_bytes"]), f"{trial.name}: frame did not use ledger balance")
        idle = period - int(frame["control_reserved_ns"]) - int(frame["planned_duration_ns"])
        require(idle >= 0, f"{trial.name}: overbooked frame")
        if trial.group == "pon-joint" and (key[1] not in blocked_allocations or int(row["time_ns"]) < trial.end_ns + 55_000_000):
            continue  # Reproduce the representative optical tail, leaving the full run in ignored target logs.
        state = {key: row[key] for key in ("time_ns", "target_frame_id", "allocation_id", "si_max_before", "si_min_before",
                                          "ab_min_bytes", "ab_sur_bytes", "assured_bytes", "surplus_bytes", "known_demand_bytes",
                                          "budget_outstanding", "budget_bytes", "assured_reason", "surplus_reason")}
        state.update(frame_idle_after_budget_ns=idle, report_pending_bytes=observed.get("reported_pending_bytes"),
                     report_debit_bytes=observed.get("granted_bytes_since_snapshot"), report_rx_ns=observed.get("report_receive_ns"))
        if key in head_fill:
            fill, head, next_head = head_fill[key]
            final = settle[fill["grant_id"]]
            state.update(grant_id=fill["grant_id"], slot_start_ns=fill["time_ns"], slot_end_ns=fill["tx_end_ns"],
                         head_packet_id=head, head_bytes=enqueue[head]["packet_bytes"] if head else None,
                         head_enqueue_ns=enqueue[head]["time_ns"] if head else None, filler_reason=fill["fill_reason"],
                         selected_packet_ids=fill["selected_packet_ids"], actual_bytes=final["actual_bytes"],
                         released_unused_bytes=final["released_unused_bytes"], settled_ns=final["time_ns"],
                         settlement_effective_after=final["effective_after"], pending_after=final["pending_bytes"], next_head=next_head)
        result.append(trial.identity() | state)
    return result, {f"pon_{key}": int(summary[key]) for key in ("grants", "budget_bytes", "actual_bytes", "unused_bytes", "pending_packets")}


def voice_outcomes(trial, rows, packets, delivery):
    """CCA here means the actual mode's blockage before the first attempt, even without a decode error."""
    first_attempt, failures, modes = {}, Counter(), {}
    cca_events, switching = defaultdict(list), defaultdict(list)
    for row in rows:
        event, sfu = row.get("event"), row.get("sfu_id")
        if event == INITIAL:
            modes[sfu] = row["current_mode"]
        elif event == END:
            modes[sfu] = row["to_mode"]
            switching[sfu].append((int(row["switch_start_ns"]), time_ns(row)))
        elif event == "WIFI_MAC_ATTEMPT":
            first_attempt.setdefault(row["packet_id"], (time_ns(row), modes[sfu]))
        elif event == DECODE and row["outcome"] == "SINR_ERROR":
            failures[row["packet_id"]] += 1
        elif event == CCA:
            cca_events[sfu].append((time_ns(row), row["busy"] == "true"))
    busy_periods = {sfu: [(a, b) for (a, busy), (b, _) in zip(rows, rows[1:]) if busy]
                    for sfu, rows in cca_events.items()}
    output = {}
    for packet, (created, _, ac, _, _, sfu) in packets.items():
        if ac not in ("VO", "VI"):
            continue
        deadline = created + round(float(trial.settings[f"wifi.ap.qos.{ac}.deadline.us"]) * 1000)
        finish = delivery.get(packet)
        attempt, mode = first_attempt.get(packet, (None, None))
        blocked = sum(overlap(a, b, created, attempt) for a, b in busy_periods[sfu]) if attempt is not None else None
        switch_wait = sum(overlap(a, b, created, attempt) for a, b in switching[sfu]) if attempt is not None else None
        output[packet] = dict(ac=ac, sfu_id=sfu, generated_ns=created, deadline_ns=deadline, finish_ns=finish,
                              on_time=finish is not None and finish <= deadline, first_tx_ns=attempt, first_mode=mode,
                              cca_busy_before_tx_ns=blocked, switch_before_tx_ns=switch_wait, sinr_failures=failures[packet])
    return output
