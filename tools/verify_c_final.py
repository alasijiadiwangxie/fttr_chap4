"""Replay C3/C4 on paired histories and verify candidate modes in independent Java runs."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from decimal import Decimal
from hashlib import sha256
from itertools import product
from math import isclose
from pathlib import Path
from statistics import mean

from pon_experiment_io import ROOT, require, simulate
from verify_c1_ap_observation import ACS, PPDU_EVENT, QUEUE_EVENT, csv_rows, time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import FEASIBILITY, FORECAST, JAR, config as phase_config, endpoint_state, events, packet_inputs, verify_queue


RESULT = ROOT / "results/c-final"
WORK = ROOT / "target/c-final-runs"
HORIZON_US = 120_000
HORIZON_NS = HORIZON_US * 1000
ANCHORS = (30_000_000, 70_000_000)
SEEDS = (1709, 2081, 3191)
MODES = ("LCM", "HCM")
MODEL = "wifi.ap.prediction.model"
AIR = "WIFI_AP_ACTIVITY_CHANGE"
EVIDENCE = "WIFI_AP_MAC_EVIDENCE"
NON_PROTOCOL = {FORECAST, FEASIBILITY, EVIDENCE}
LOADS = {
    "idle": (0, 0, 0, 0),
    "near_idle": (0.05, 0.05, 0.1, 0.05),
    "low": (1, 2, 4, 1),
    "mid": (4, 7, 24, 5),
    "high": (10, 18, 75, 7),
}
PROFILES = {"clear": (), "primary": (0,), "secondary": (3,), "wide": (0, 1, 2, 3)}
SCENARIOS = (
    ("idle", "clear", 1, "timely"),
    ("near_idle", "clear", 1, "timely"),
    ("low", "clear", 1, "timely"),
    ("mid", "clear", 2, "timely"),
    ("mid", "secondary", 4, "expired"),
    ("high", "clear", 4, "timely"),
    ("high", "primary", 2, "delayed"),
    ("high", "secondary", 4, "timely"),
    ("high", "wide", 4, "lost"),
)
UNKNOWN = {"CHANNEL_INFORMATION_INSUFFICIENT", "SWITCH_COST_UNKNOWN", "UNKNOWN",
           "INSUFFICIENT_INFORMATION", "INFORMATION_INSUFFICIENT"}
POWER = {
    "LCM": {"IDLE": "7.38", "TX": "9.20", "RX": "8.20"},
    "HCM": {"IDLE": "7.62", "TX": "10.50", "RX": "8.70"},
}


def case_config(mode, load, profile, sta_count, feedback, seed):
    props = phase_config(mode, "mid", profile, seed)
    props = {key: value for key, value in props.items() if not key.startswith(
        ("sfu.2.", "traffic.sfu.2.", "sta.", "traffic.sta."))}
    props.update({"sfu.count": "1", "sta.count": str(sta_count), "traffic.duration.us": str(HORIZON_US),
                  "wifi.ap.activity.window.us": str(HORIZON_US),
                  "wifi.channel.observe.us": "30000,70000",
                  "wifi.channel.truth": ";".join(f"1,{sta},{ch},0,24,-1,false,-95"
                                                for sta, ch in product(range(1, sta_count + 1), range(4)))})
    interferers = []
    for ch in PROFILES[profile]:
        interferers.append(f"1,{ch},0,{HORIZON_US},-96,-84")
        interferers.extend(f"1,{ch},{start},{start + 250},-74,-88"
                           for start in range(2450, HORIZON_US - 250, 4000))
    props["wifi.channel.interference.timeline"] = ";".join(interferers)
    delay, validity, loss = {"timely": (25, 25_000, ""), "delayed": (2200, 25_000, ""),
                             "expired": (180, 120, ""), "lost": (50, 25_000, ",drop")}[feedback]
    props["wifi.channel.feedback"] = ";".join(
        f"1,{sta},{ch},{base + (sta-1)*80 + 17*ch},{delay},{validity}{loss}"
        for base, sta, ch in product((0, 12_000, 28_000, 47_000, 68_000, 97_000, 115_000),
                                     range(1, sta_count + 1), range(4)))
    for ac, rate in zip(ACS, LOADS[load]):
        props[f"traffic.sfu.1.class.{ac}.rate.mbps"] = str(rate)
    for sta in range(1, sta_count + 1):
        props[f"sta.{sta}.sfu.id"] = "1"
        props[f"traffic.sta.{sta}.seed"] = str(seed + 907 * sta)
        for ac, rate in zip(ACS, (0.2, 0.3, 1.1, 0.4)):
            props[f"traffic.sta.{sta}.class.{ac}.rate.mbps"] = str(
                0 if load == "idle" else rate / 20 if load == "near_idle" else rate)
            props[f"traffic.sta.{sta}.class.{ac}.packet.bytes"] = "1500"
    for mode, states in POWER.items():
        for state, value in states.items():
            props[f"wifi.ap.energy.{mode.lower()}.{state.lower()}.w"] = value
    return props


def at(row):
    if "t_ns" in row or "t_us" in row:
        return time_ns(row)
    return int(row["time_ns"]) if "time_ns" in row else None


def original_lines(raw, rows, end=None):
    return [line for line, row in zip(raw.splitlines(), rows)
            if row.get("event") not in NON_PROTOCOL and (end is None or (at(row) is not None and at(row) <= end))]


def forecast_lines_through(run, end):
    return [line for line, row in zip(run.raw.splitlines(), run.rows)
            if row.get("event") in NON_PROTOCOL and at(row) <= end]


def digest(lines):
    return sha256("\n".join(lines).encode()).hexdigest()


def span_overlap(lo, hi, begin, end):
    return max(0, min(hi, end) - max(lo, begin))


def radio_spans(rows, initial_mode):
    spans = []
    state, mode, start = "IDLE", initial_mode, 0
    switch_powers = {at(row): Decimal(row["power_w"]) for row in events(rows, "WIFI_AP_SWITCH_START")}
    for row in events(rows, AIR):
        end = at(row)
        require(row["from"] == state and start <= end <= HORIZON_NS, f"Invalid B2 transition {row}")
        spans.append((start, end, state, mode, switch_powers[start] if state == "SWITCH" else None))
        state, mode, start = row["to"], row["ap_mode"], end
    spans.append((start, HORIZON_NS, state, mode, switch_powers[start] if state == "SWITCH" else None))
    return spans


def summarize_radio(spans, begin, end):
    durations = Counter()
    total = extra = switch_energy = Decimal(0)
    for lo, hi, state, mode, switch_power in spans:
        duration = span_overlap(lo, hi, begin, end)
        durations[state] += duration
        if duration == 0:
            continue
        power = switch_power if state == "SWITCH" else Decimal(POWER[mode][state])
        energy = power * duration / 1000
        total += energy
        if state == "SWITCH":
            switch_energy += energy
        elif state in ("TX", "RX"):
            extra += (power - Decimal(POWER[mode]["IDLE"])) * duration / 1000
    require(sum(durations.values()) == end - begin, "B2 window time is not conserved")
    return durations, total, extra, switch_energy


def intervals_of_masks(rows):
    prior = 0
    busy = False
    spans = []
    for row in events(rows, "WIFI_EXTERNAL_CCA"):
        now = at(row)
        if busy and now > prior:
            spans.append((prior, now))
        prior, busy = now, row["busy"] == "true"
    if busy:
        spans.append((prior, HORIZON_NS))
    return spans


def eligible_spans(rows):
    """AP-visible DL state only. An interval includes pending or in-flight packets."""
    counts = Counter()
    previous = 0
    spans = []
    for row in events(rows, QUEUE_EVENT):
        if row["direction"] != "DL":
            continue
        now = at(row)
        if any(counts.values()) and now > previous:
            spans.append((previous, now))
        counts[row["ac"]] = int(row["pending_packets"]) + int(row["inflight_packets"])
        previous = now
    return spans


def union_spans(intervals):
    merged = []
    for lo, hi in sorted(intervals):
        if merged and lo <= merged[-1][1]:
            merged[-1] = merged[-1][0], max(hi, merged[-1][1])
        else:
            merged.append((lo, hi))
    return merged


def window_duration(intervals, lo, hi):
    return sum(span_overlap(begin, end, lo, hi) for begin, end in intervals)


def scoped(rows, begin, end):
    return [row for row in rows if begin < at(row) <= end]


@dataclass
class Run:
    raw: str
    rows: list[dict[str, str]]
    packets: dict
    deliveries: dict
    drops: dict
    radio: list
    cca: list
    eligible: list
    c1: list
    ppdus: list
    attempts: list
    feedback: list
    ends: list


def audited_run(jar, directory, name, props):
    path = directory / f"{name}.properties"
    write_properties(path, props)
    raw = simulate(jar, path)
    rows = parse_trace(raw)
    packets = packet_inputs(rows, props)
    deliveries, drops, _ = endpoint_state(name, rows, packets)
    # Zero-load runs legitimately contain no packet history.
    if packets:
        verify_queue(name, rows)
    spans = radio_spans(rows, props["sfu.1.wifi.ap.mode"])
    durations, total, _, _ = summarize_radio(spans, 0, HORIZON_NS)
    activity = next(row for row in rows if row.get("summary") == "WIFI_AP_ACTIVITY")
    energy = next(row for row in rows if row.get("summary") == "WIFI_AP_ENERGY")
    for state in ("TX", "RX", "IDLE", "SWITCH"):
        require(durations[state] == int(activity.get(state.lower() + "_ns", 0)),
                f"{name}: {state} duration differs from B2")
    require(total == Decimal(energy["total_energy_uj"]), f"{name}: B3 energy mismatch")
    c1 = events(rows, QUEUE_EVENT)
    settled = {row["packet_id"] for row in c1 if row["action"] == "CONFIRMED"}
    dl_delivered = {key for key in deliveries if packets[key][3] == "DL"}
    require(settled == dl_delivered, f"{name}: DL end-to-end and AP ACK differ after drain")
    return Run(raw, rows, packets, deliveries, drops, spans, intervals_of_masks(rows), eligible_spans(rows),
               c1, events(rows, PPDU_EVENT), events(rows, "WIFI_MAC_ATTEMPT"),
               [row for row in events(rows, "WIFI_CHANNEL_FEEDBACK") if row["phase"] == "TX_START"],
               events(rows, "WIFI_AP_SWITCH_END"))


def future_measurements(run, begin, end):
    window = end - begin
    c1 = scoped(run.c1, begin, end)
    counts = Counter()
    for row in c1:
        counts[row["action"]] += int(row["bytes"]) * 8
    successful_bits = counts["DELIVERED"] + counts["UL_RECEIVED"]
    ingress_bits = counts["ENQUEUED"] + counts["UL_RECEIVED"]
    states, total, extra, switch_energy = summarize_radio(run.radio, begin, end)
    measured_feedback = 0
    feedback_incremental = Decimal(0)
    for row in run.feedback:
        lo, hi = max(begin, at(row)), min(end, at(row) + int(row["airtime_ns"]))
        if hi > lo:
            feedback_states, _, feedback_extra, _ = summarize_radio(run.radio, lo, hi)
            require(feedback_states["RX"] == hi - lo, "Active feedback missing from observed RX")
            measured_feedback += hi - lo
            feedback_incremental += feedback_extra
    busy = window_duration(run.cca, begin, end)
    eligible = window_duration(run.eligible, begin, end)
    obsv_eligible_idle = 0
    radio_or_cca = union_spans([(lo, hi) for lo, hi, state, _, _ in run.radio if state != "IDLE"] + run.cca)
    for lo, hi in run.eligible:
        left, right = max(lo, begin), min(hi, end)
        if right > left:
            obsv_eligible_idle += right - left - window_duration(radio_or_cca, left, right)
    ap_ppdus = scoped(run.ppdus, begin, end)
    depths = [int(row["depth"]) for row in ap_ppdus if row["direction"] == "DL"]
    mac = scoped(run.attempts, begin, end)
    per_direction = Counter(row["direction"] for row in ap_ppdus)
    old_mbps = successful_bits * 1000 / window
    return dict(actual_success_mbps=old_mbps, actual_input_mbps=ingress_bits * 1000 / window,
                actual_dl_ingress_mbps=counts["ENQUEUED"] * 1000 / window,
                actual_dl_success_mbps=counts["DELIVERED"] * 1000 / window,
                actual_ul_success_mbps=counts["UL_RECEIVED"] * 1000 / window,
                actual_total_energy_uj=total, actual_incremental_energy_uj=extra,
                actual_data_incremental_energy_uj=extra - feedback_incremental,
                actual_energy_per_bit_uj=total / successful_bits if successful_bits else None,
                actual_tx_ns=states["TX"], actual_rx_ns=states["RX"], actual_idle_ns=states["IDLE"],
                actual_switch_ns=states["SWITCH"], actual_switch_energy_uj=switch_energy,
                actual_feedback_rx_ns=measured_feedback, actual_external_busy_ns=busy,
                actual_dl_eligible_ns=eligible, actual_eligible_idle_residual_ns=obsv_eligible_idle,
                actual_dl_agg_depth=mean(depths) if depths else None, actual_dl_ppdus=per_direction["DL"],
                actual_observed_ul_ppdus=per_direction["UL"], actual_mpdu_attempts=len(mac),
                actual_retry_attempts=sum(int(row["retry_count"]) > 0 for row in mac))


def model_prediction(row, actual, causal_input):
    # Clamp the old/new service estimate by the ex-post realized ingress solely for attribution.
    service = float(row.get("mac_mbps", row["service_mbps"]))
    input_mbps = float(row.get("input_mbps", causal_input))
    goodput = float(row["success_mbps"])
    if "conservative_mbps" in row:
        require(0 <= float(row["conservative_mbps"]) <= goodput <= float(row["optimistic_mbps"]),
                f"Forecast throughput band is out of order: {row}")
    horizon = int(row["window_end_ns"]) - at(row)
    tx = float(row["tx_ns"])
    rx = float(row["rx_ns"])
    idle = float(row["idle_ns"])
    switch_time = max(0, int(row["switch_ns"]))
    switch_energy = max(0, float(row["switch_uj"]))
    mode_power = {name: float(value) for name, value in POWER[row["mode"]].items()}
    projected_total_energy = (mode_power["TX"] * tx + mode_power["RX"] * rx
                              + mode_power["IDLE"] * idle) / 1000 + switch_energy
    require(isclose(horizon, tx + rx + idle + switch_time, rel_tol=1e-9, abs_tol=1e-6),
            f"Forecast time not conserved: {row}")
    require(isclose(float(row["energy_uj"]), projected_total_energy, rel_tol=1e-9, abs_tol=1e-6),
            f"Forecast energy not conserved: {row}")
    queued = float(row["queue_packets"]) * 1500 * 8000 / horizon
    predicted_demand = input_mbps + queued
    realized_demand = actual["actual_input_mbps"] + queued
    reference = min(predicted_demand, service)
    ratio = goodput / reference if reference > 0 else 0
    input_adjusted = min(realized_demand, service) * ratio
    return dict(predicted_success_mbps=goodput, predicted_input_mbps=input_mbps,
                predicted_phy_mbps=row.get("phy_mbps"), predicted_mac_mbps=service,
                predicted_conservative_mbps=row.get("conservative_mbps"),
                predicted_optimistic_mbps=row.get("optimistic_mbps"),
                predicted_total_energy_uj=row["energy_uj"],
                predicted_incremental_energy_uj=row.get("incremental_energy_uj",
                    float(row["energy_uj"]) - float(row["switch_uj"]) -
                    (horizon - int(row["switch_ns"])) * float(POWER[row["mode"]]["IDLE"]) / 1000),
                predicted_tx_ns=row["tx_ns"], predicted_rx_ns=row["rx_ns"],
                predicted_energy_per_bit_uj=None if float(row["energy_per_bit_uj"]) < 0 else row["energy_per_bit_uj"],
                predicted_queue_packets=row["queue_packets"], predicted_agg_depth=row["agg_depth"],
                predicted_retry_fraction=row["retry_fraction"], predicted_busy_fraction=row["busy_fraction"],
                confidence=row["confidence"], diagnosis_arrival_component_mbps=goodput - input_adjusted,
                diagnosis_service_component_mbps=input_adjusted - actual["actual_success_mbps"])


def qos_rows(prediction, meta, measures, run, begin, end):
    ac = prediction["ac"]
    deadline = int(prediction["deadline_ns"])
    minimum = float(prediction["min_on_time"])
    cohort = [packet for packet, (generated, _, category, direction, _, _) in run.packets.items()
              if direction == "DL" and category == ac and generated <= end
              and run.deliveries.get(packet, begin + 1) > begin and run.drops.get(packet, begin + 1) > begin]
    on_time = sum(run.deliveries.get(packet, 2**63) <= run.packets[packet][0] + deadline for packet in cohort)
    actual_label = "NO_COHORT" if not cohort else "FEASIBLE" if on_time / len(cohort) >= minimum else "INFEASIBLE"
    policies = [("expected", prediction["status"])]
    if meta["model"] == "revised":
        policies.append(("conservative", prediction["conservative_status"]))
    for policy, state in policies:
        prediction_label = "UNKNOWN" if state in UNKNOWN else "FEASIBLE" if state == "FEASIBLE" else "INFEASIBLE"
        if actual_label == "NO_COHORT":
            disposition = "NO_COHORT"
        elif prediction_label == "UNKNOWN":
            disposition = "UNKNOWN"
        elif prediction_label == actual_label:
            disposition = "CORRECT_" + prediction_label
        else:
            disposition = "FALSE_" + prediction_label
        yield dict(**meta, ac=ac, policy=policy, status=state, prediction_label=prediction_label,
                   actual_label=actual_label, outcome=disposition, cohort_packets=len(cohort), on_time_packets=on_time,
                   violations=len(cohort) - on_time, violation_ratio=(len(cohort) - on_time) / len(cohort) if cohort else None,
                   deadline_ns=deadline, min_on_time=minimum, predicted_waiting_ns=prediction["waiting_ns"],
                   predicted_e2e_ns=prediction["e2e_ns"], predicted_success_probability=prediction["success_probability"],
                   actual_success_mbps=measures["actual_success_mbps"])


def check_profile_switch(run, snapshot, candidate):
    require(len(run.ends) == 1, "Expected exactly one completed B4 switch")
    switch = run.ends[0]
    require(switch["to_mode"] == candidate and int(switch["request_ns"]) == snapshot + 1
            and at(switch) > snapshot, "Cannot confirm offline candidate branch mode/time")
    return at(switch)


def forecast_pair(run, anchor, candidate):
    forecasts = [row for row in events(run.rows, FORECAST) if at(row) == anchor and row["mode"] == candidate]
    qos = [row for row in events(run.rows, FEASIBILITY) if at(row) == anchor and row["mode"] == candidate]
    require(len(forecasts) == 1 and {row["ac"] for row in qos} == set(ACS), f"Missing predictions for {candidate} at {anchor}")
    return forecasts[0], qos


def selected_evidence(run, anchor):
    rows = [row for row in events(run.rows, EVIDENCE) if at(row) == anchor]
    fields = ("eligible_ns", "occupied_ns", "blocked_ns", "ppdus", "dl_ppdus", "ul_ppdus", "dl_attempted",
              "dl_confirmed", "arrival_mbps", "aifs_ns", "backoff_ns", "frozen_ns", "dl_depth", "ul_depth",
              "dl_data_ns", "ul_data_ns", "dl_response_ns", "ul_response_ns")
    return {"history_" + field: rows[0].get(field) if rows else None for field in fields}


def verify_case(jar, work, case):
    load, profile, count, feedback, seed, mode = case
    name = f"{load}-{profile}-{count}sta-{feedback}-{seed}-{mode.lower()}"
    props = case_config(mode, load, profile, count, feedback, seed)
    legacy = audited_run(jar, work, name + "-legacy", {**props, MODEL: "legacy"})
    revised_props = {**props, MODEL: "revised"}
    revised = audited_run(jar, work, name + "-revised", revised_props)
    require(legacy.packets == revised.packets and original_lines(legacy.raw, legacy.rows) ==
            original_lines(revised.raw, revised.rows), f"{name}: analysis changed workload or protocol")
    scenario_meta = dict(scenario=name, load=load, interference=profile, sta_count=count,
                         feedback_profile=feedback, seed=seed, current_mode=mode)
    alternate = "HCM" if mode == "LCM" else "LCM"
    predictions, qos = [], []
    for anchor in ANCHORS:
        branch = audited_run(jar, work, f"{name}-switch-{anchor}", {
            **revised_props, "sfu.1.wifi.ap.switch.requests": f"{Decimal(anchor + 1).scaleb(-3):f}:{alternate}",
        })
        require(branch.packets == revised.packets, f"{name}: candidate branch changed packet workload")
        prefix = original_lines(revised.raw, revised.rows, anchor)
        require(original_lines(branch.raw, branch.rows, anchor) == prefix,
                f"{name}: candidate branch diverged before the forecast")
        require(forecast_lines_through(branch, anchor) == forecast_lines_through(revised, anchor),
                f"{name}: a later offline switch changed as-of predictions or observations")
        switch_end = check_profile_switch(branch, anchor, alternate)
        input_ewma = sum(float(row["ewma_arrival_mbps"]) for row in events(revised.rows, "WIFI_AP_OBSERVATION")
                         if at(row) == anchor)
        for candidate, actual_run, action in ((mode, revised, "stay"), (alternate, branch, "switch")):
            preceding = selected_evidence(revised, anchor)
            before = future_measurements(revised, anchor - int(props["wifi.ap.observation.period.us"]) * 1000, anchor)
            for model, predictor_run in (("legacy", legacy), ("revised", revised)):
                record, judgments = forecast_pair(predictor_run, anchor, candidate)
                end = int(record["window_end_ns"])
                require(anchor < end <= HORIZON_NS, f"{name}: incomplete prospective comparison window")
                measures = future_measurements(actual_run, anchor, end)
                meta = dict(**scenario_meta, snapshot_ns=anchor, window_end_ns=end, candidate_mode=candidate,
                            branch=action, model=model, switch_completed_ns=switch_end if action == "switch" else None,
                            shared_prefix_sha256=digest(prefix))
                previous = {"prior_" + field: before[field] for field in (
                    "actual_success_mbps", "actual_input_mbps", "actual_tx_ns", "actual_rx_ns",
                    "actual_dl_eligible_ns", "actual_eligible_idle_residual_ns", "actual_external_busy_ns",
                    "actual_dl_agg_depth", "actual_dl_ppdus", "actual_observed_ul_ppdus", "actual_retry_attempts")}
                predictions.append({**meta, **model_prediction(record, measures, input_ewma),
                                    **measures, **preceding, **previous})
                for judgment in judgments:
                    qos.extend(qos_rows(judgment, meta, measures, actual_run, anchor, end))
    print(f"{name}: {len(revised.packets)} generated, 2 same-history offline branches", flush=True)
    return name, (load, profile, count, feedback, seed), revised.packets, predictions, qos


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--output", type=Path, default=RESULT)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    args.work.mkdir(parents=True, exist_ok=True)
    all_cases = [(*scenario, seed, mode) for scenario, seed, mode in product(SCENARIOS, SEEDS, MODES)]
    if args.smoke:
        all_cases = [(*SCENARIOS[0], SEEDS[0], "LCM"), (*SCENARIOS[5], SEEDS[0], "HCM")]
    predictions, qos, inputs = [], [], {}
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for name, case, packets, forecast, judgment in pool.map(
                lambda item: verify_case(args.jar.resolve(), args.work, item), all_cases):
            if case in inputs:
                require(inputs[case] == packets, f"{name}: initial modes did not get the same input")
            inputs[case] = packets
            predictions.extend(forecast)
            qos.extend(judgment)
    csv_rows(args.output / "prediction-comparison.csv", predictions)
    csv_rows(args.output / "qos-feasibility.csv", qos)
    print(f"{len(all_cases)} paired scenarios, {len(predictions)} forecast rows and {len(qos)} QoS rows")


if __name__ == "__main__":
    main()
