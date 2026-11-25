"""Match each D5 switch request with an independently replayed hold window."""

from __future__ import annotations

from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256

from e0_trials import capture
from pon_experiment_io import require
from run_b4_ap_switch import DEFAULT_POWERS, STATES
from verify_c1_ap_observation import WindowLedger, time_ns
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import events
from verify_d5_ecq_sdps import DECISION, DECODE, END, QUEUE, START


REQUEST = "WIFI_AP_SWITCH_REQUEST"
CANCEL = "WIFI_AP_SWITCH_CANCELLED"
ACTIVITY = "WIFI_AP_ACTIVITY_CHANGE"
FEEDBACK = "WIFI_CHANNEL_FEEDBACK"
SCAN = "WIFI_CHANNEL_SCAN"
CCA = "WIFI_EXTERNAL_CCA"
PREDICT = "WIFI_AP_ENERGY_MODE_DECISION"
QOS = "WIFI_AP_QOS_MODE_DECISION"
DEADLINE = "WIFI_AP_QOS_DEADLINE_PREDICTION"
ACS = ("VO", "VI", "BE", "BK")

# This fingerprint uses the same event subset as E0Trace. The request itself is the first differing action.
FINGERPRINT_EVENTS = {
    "ARRIVAL", "DELIVERED", "WIFI_MAC_ATTEMPT", "WIFI_MAC_SUCCESS", "WIFI_MAC_RETRY", "WIFI_RETRY_LIMIT_DROP",
    "WIFI_COLLISION_DROP", DECODE, QUEUE, CCA, SCAN, FEEDBACK, ACTIVITY, REQUEST, CANCEL, START, END,
}


def switch_key(row):
    return row["sfu_id"], row["switch_id"]


def decision_key(row):
    return row["sfu_id"], time_ns(row)


def by_candidate(rows, event):
    return {(*decision_key(row), row["candidate_mode"]): row for row in events(rows, event)}


def overlap(a, b, left, right):
    return max(0, min(b, right) - max(a, left))


def wide_busy_ns(edges, left, right):
    cursor, mask, busy = 0, 0, 0
    for edge in edges:
        now = time_ns(edge)
        if now > right:
            break
        if mask:
            busy += overlap(cursor, now, left, right)
        cursor, mask = now, int(edge["observed_mask"])
    return busy + (overlap(cursor, right, left, right) if mask else 0)


def local_outcome(rows, packets, sfu, left, right, live_due, initial, settings):
    before_mode, before_state, active_scans = initial
    now, mode, state = left, before_mode, before_state
    radio_uj, scans, bits, errors, switches = 0.0, 0.0, 0, 0, 0
    delivered, timely = set(), set()
    powers = {(m, s.upper()): float(v) for m, values in DEFAULT_POWERS.items() for s, v in zip(STATES, values)}
    for m, direction in (("LCM", "lcm-to-hcm"), ("HCM", "hcm-to-lcm")):
        powers[m, "SWITCH"] = float(settings[f"wifi.ap.switch.{direction}.power.w"])
    scan_power = float(settings.get("wifi.channel.scan.power.w", 0))
    for row in (*active_scans, *rows):
        event = row.get("event")
        if event == "DELIVERED":
            packet = row["packet_id"]
            meta = packets[packet]
            if meta[5] == sfu and left <= time_ns(row) <= right:
                delivered.add(packet)
                bits += meta[1] * 8
                if packet in live_due and time_ns(row) <= live_due[packet]:
                    timely.add(packet)
        elif row.get("sfu_id") == sfu and event in (ACTIVITY, SCAN, DECODE, END):
            ts = time_ns(row)
            if event == SCAN and row["phase"] == "START":
                scans += scan_power * overlap(int(row["started_ns"]), int(row["ended_ns"]), left, right) / 1000
            if not left <= ts <= right:
                continue
            if event == ACTIVITY:
                require(state == row["from"], f"SFU {sfu}: unmatched physical state at {ts}")
                radio_uj += powers[mode, state] * (ts - now) / 1000
                now, mode, state = ts, row["ap_mode"], row["to"]
            elif event == DECODE and row["outcome"] == "SINR_ERROR":
                errors += 1
            elif event == END:
                switches += 1
    radio_uj += powers[mode, state] * (right - now) / 1000
    return dict(bits=bits, packets=len(delivered), sinr_failures=errors, switches=switches, energy_uj=radio_uj + scans,
                **{f"timely_{ac}": sum(packets[p][2] == ac for p in timely) for ac in ACS})


def request_snapshots(trial, raw, rows, packets):
    energy = by_candidate(rows, PREDICT)
    qos = by_candidate(rows, QOS)
    per_ac = {(*decision_key(r), r["candidate_mode"], r["ac"]): r for r in events(rows, DEADLINE)}
    decisions = {decision_key(r): r for r in events(rows, DECISION)}
    started = {switch_key(r): r for r in events(rows, START)}
    ended = {switch_key(r): r for r in events(rows, END)}
    cancelled = {switch_key(r): r for r in events(rows, CANCEL)}
    edges = defaultdict(list)
    for r in events(rows, CCA):
        edges[r["sfu_id"]].append(r)
    latest_reports, scans, scan_started, mask = defaultdict(dict), defaultdict(dict), defaultdict(dict), defaultdict(int)
    radio, waiting, previous = {}, defaultdict(WindowLedger), {}
    initial = {r["sfu_id"]: r["current_mode"] for r in events(rows, "WIFI_AP_MODE_INITIAL")}
    radio.update({sfu: (mode, "IDLE") for sfu, mode in initial.items()})
    cut = {ac: round(1000 * float(trial.settings[f"wifi.ap.qos.{ac}.deadline.us"])) for ac in ACS}
    prefix = sha256()
    for index, (line, row) in enumerate(zip(raw.splitlines(), rows)):
        event = row.get("event")
        sfu = row.get("sfu_id")
        if event == QUEUE and row["direction"] == "DL":
            waiting[sfu, row["ac"]].apply(trial.name, row)
        elif event == ACTIVITY:
            radio[sfu] = row["ap_mode"], row["to"]
        elif event == FEEDBACK and row["phase"] == "ACCEPTED":
            require(int(row["measured_ns"]) <= time_ns(row) < int(row["expires_ns"]), f"{trial.name}: feedback outside TTL")
            latest_reports[sfu][row["sta_id"], row["subchannel"]] = row
        elif event == SCAN:
            if row["phase"] == "START":
                scan_started[sfu][row["subchannel"]] = row
            elif row["phase"] == "END":
                scans[sfu][row["subchannel"]] = row
                scan_started[sfu].pop(row["subchannel"], None)
        elif event == CCA:
            mask[sfu] = int(row["observed_mask"])
        elif event == DECISION:
            previous[sfu] = time_ns(row)
        elif event == REQUEST and time_ns(row) < trial.end_ns:
            left = time_ns(row)
            source, target = row["from_mode"], row["to_mode"]
            key = switch_key(row)
            decision = decisions[sfu, left]
            keep, change, high = (energy[sfu, left, mode] for mode in (source, target, "HCM"))
            require(decision["switch_id"] == row["switch_id"], f"{trial.name}: no matching online decision")
            right = left + int(change["horizon_ns"])
            # Stay within the offered-load window and scale the forecasts to the same duration only when complete.
            comparable = right <= trial.end_ns
            right = min(right, trial.end_ns)
            reports = tuple(latest_reports[sfu].values())
            expired = [r for r in reports if int(r["expires_ns"]) <= left]
            valid = [r for r in reports if int(r["expires_ns"]) > left]
            sec_scan = scans[sfu].get("3")
            prev = energy.get((sfu, previous.get(sfu, -1), "HCM"), {})
            live = {p: packets[p][0] + cut[ac] for ac in ACS for p in waiting[sfu, ac].pending
                    if packets[p][0] + cut[ac] > left}
            due = {p: deadline for p, deadline in live.items() if deadline <= right}
            expected_gain = {}
            for ac in ACS:
                a = per_ac.get((sfu, left, source, ac), {})
                b = per_ac.get((sfu, left, target, ac), {})
                n = sum(packets[p][2] == ac for p in due)
                if n > 0 and n == int(a.get("queued_packets", 0)) == int(b.get("queued_packets", 0)):
                    expected_gain[f"predicted_timely_gain_{ac}"] = n * (float(b["predicted_on_time"]) - float(a["predicted_on_time"]))
            end = ended.get(key)
            start = started.get(key)
            cancel = cancelled.get(key)
            margin = float(decision["net_saving_uj"]) - float(decision["margin_uj"])
            expiry = int(sec_scan["expires_ns"]) if sec_scan else None
            fields = trial.identity() | dict(sfu_id=sfu, switch_id=row["switch_id"], request_ns=left, end_of_probe_ns=right,
                status="COMPLETED" if end else "CANCELLED" if cancel else "PENDING",
                start_ns=time_ns(start) if start else None, completed_ns=time_ns(end) if end else None,
                cancel_ns=time_ns(cancel) if cancel else None, from_mode=source, to_mode=target,
                passive_wait_ns=int(start["wait_ns"]) if start else None, switch_energy_uj=end["switch_energy_uj"] if end else None,
                trigger=decision["trigger"], reason=decision["reason"], keep_qos=decision["keep_qos"], change_qos=decision["change_qos"],
                decisive_ac=decision["decisive_ac"], predicted_timely_gain_selected_ac=decision["on_time_gain"],
                urgent_packet_id=decision["urgent_packet_id"], live_packets=len(live), expired_at_decision=decision["expired_packets"],
                required_old_mode_mbps=keep["ewma_mbps"], keep_saturated_mbps=keep["saturated_service_mbps"],
                change_saturated_mbps=change["saturated_service_mbps"], predicted_bits_delta=float(change["success_bits"]) - float(keep["success_bits"]),
                predicted_keeping_cost_uj=decision["keep_uj"], predicted_changing_cost_uj=decision["change_uj"],
                net_saving_uj=decision["net_saving_uj"], margin_uj=decision["margin_uj"], savings_above_margin_uj=margin,
                small_energy_edge=(decision["reason"] in ("ECONOMICAL_SWITCH", "SAFE_ECONOMIC_EXIT") and 0 < margin <= 25),
                hcm_feedback_confidence=high["channel_confidence"], hcm_channel_incomplete=high["channel_incomplete"],
                hcm_uncertain=qos[sfu, left, "HCM"]["uncertain"], hcm_predicted_per=high["mean_failure"],
                preceding_decision_ns=previous.get(sfu), preceding_hcm_busy=prev.get("observed_busy_fraction"),
                hcm_observed_busy=high["observed_busy_fraction"], hcm_expected_busy=high["expected_busy_fraction"],
                next_window_wide_busy=wide_busy_ns(edges[sfu], left, right) / (right - left),
                cca_raw_mask=mask[sfu], feedback_valid=len(valid), feedback_expired=len(expired),
                max_valid_feedback_age_ns=max((left - int(r["measured_ns"]) for r in valid), default=None),
                secondary_scan_state="MISSING" if not sec_scan else "EXPIRED" if expiry <= left else "VALID",
                secondary_scan_age_ns=left - time_ns(sec_scan) if sec_scan else None,
                secondary_scan_busy=sec_scan["busy_fraction"] if sec_scan else None,
                horizon_comparable=comparable, **expected_gain,
                **{f"due_{ac}": sum(packets[p][2] == ac for p in due) for ac in ACS})
            future_scans = [r for r in scan_started[sfu].values() if int(r["ended_ns"]) > left]
            snapped = (*radio[sfu], future_scans)
            yield fields, index, due, snapped, prefix.hexdigest()
        if event in FINGERPRINT_EVENTS:
            prefix.update((line.strip() + "\n").encode("utf-8"))


def observed_evidence(row, actual, stay):
    priority_delta = next((actual[f"timely_{ac}"] - stay[f"timely_{ac}"] for ac in ACS
                           if actual[f"timely_{ac}"] != stay[f"timely_{ac}"]), 0)
    delivered_delta = actual["bits"] - stay["bits"]
    if priority_delta:
        verdict = "DEADLINE_GAIN" if priority_delta > 0 else "DEADLINE_LOSS"
    elif delivered_delta:
        verdict = "SERVICE_GAIN" if delivered_delta > 0 else "SERVICE_LOSS"
    else:
        verdict = "NO_QOS_OR_SERVICE_GAIN"
    comparable = row["horizon_comparable"]
    return dict(short_window_evidence=verdict, actual_delivered_bits_delta=delivered_delta,
                actual_energy_uj_delta=actual["energy_uj"] - stay["energy_uj"],
                forecast_service_gain_unrealized=(comparable and row["predicted_bits_delta"] >= 4000 and delivered_delta <= 0),
                forecast_high_priority_gain_unrealized=any(
                    row.get(f"predicted_timely_gain_{ac}", 0) >= .5 and actual[f"timely_{ac}"] <= stay[f"timely_{ac}"]
                    for ac in ("VO", "VI")))


def diagnose_switches(trial, raw, rows, packets, probe, work, jobs):
    snapshots = list(request_snapshots(trial, raw, rows, packets))

    def branch(entry):
        row, index, due, state, fingerprint = entry
        if row["status"] != "COMPLETED":
            return row | dict(short_window_evidence="NOT_EXECUTED")
        sfu, left, right = (row[k] for k in ("sfu_id", "request_ns", "end_of_probe_ns"))
        path = work / f"{trial.name}.properties"
        replay_raw = capture(probe, path, sfu, left, right)
        replay = parse_trace(replay_raw.decode("utf-8"))
        witness = events(replay, "E0_PREFIX")
        require(len(witness) == 1 and witness[0]["sha256"] == fingerprint,
                f"{trial.name} SFU {sfu} at {left}: replay prefix differed")
        real = local_outcome(rows[index:], packets, sfu, left, right, due, state, trial.settings)
        held = local_outcome(replay, packets, sfu, left, right, due, state, trial.settings)
        require(real["switches"] >= 1 and held["switches"] == 0, f"{trial.name}: hold did not isolate switch {sfu}/{left}")
        return row | {f"actual_{key}": value for key, value in real.items()} | {
            f"hold_{key}": value for key, value in held.items()} | observed_evidence(row, real, held) | {"prefix_sha256": fingerprint}

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        return list(pool.map(branch, snapshots))
