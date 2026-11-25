"""Run paired D2 scenarios and audit the Java decisions, delivery and energy ledgers."""

from __future__ import annotations

from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from hashlib import sha256
from itertools import product
from math import inf, isclose, isfinite
from pathlib import Path
import argparse

from pon_experiment_io import ROOT, require, simulate, write_csv
from run_b3_ap_energy import DEFAULT_POWERS
from run_b4_ap_switch import by_summary
from verify_c1_ap_observation import time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import endpoint_state, events, metrics_at, packet_inputs, verify_queue
from verify_d1_mode_control import (
    DECISION as D1_DECISION, FIXED, INITIAL, JAR, POLICY, POLICIES as BASELINES,
    SEEDS, SWITCH_EVENTS, audit_decisions as audit_threshold, audit_energy, audit_switches, configuration, packet_metrics,
)


OUT = ROOT / "results/d2-energy-decision"
WORK = ROOT / "target/d2-energy-decision"
ENERGY_POLICIES = ("e-aware", "ec-aware")
POLICIES = (*BASELINES, *ENERGY_POLICIES)
D2_DECISION = "WIFI_AP_ENERGY_MODE_DECISION"
MODES = ("LCM", "HCM")
PATTERNS = {"ideal": (), "primary": (0,), "secondary": (3,), "wide": (0, 1, 2, 3)}
GRID_LOADS = (0, 12, 25, 40, 60, 85)
LENGTHS = (500, 1000, 1500)
DYNAMIC_CASES = ("step-ideal", "step-secondary", "burst-multi")
GRID_WINDOW_US = 40_000
DYNAMIC_WINDOW_US = 120_000
PERIOD_US = 5_000

IDENTITY = ("scenario", "profile", "seed", "policy", "packet_bytes", "sfu_id", "nominal_load_mbps")
EVENT_FIELDS = (
    "trace_sequence", "event", "t_ns", "window_start_ns", "horizon_ns", "arrival_mbps", "ewma_mbps", "dl_queue",
    "dl_inflight", "current_mode", "projected_mode", "candidate_mode", "target_mode", "reason", "action", "switch_id",
    "channel_confidence", "channel_incomplete", "observed_busy_fraction", "expected_busy_fraction", "external_busy",
    "mean_failure", "ampdu_depth", "margin_uj", "local_wait_ns", "prefix_tx_ns", "prefix_rx_ns", "active_switch_ns",
    "local_wait_uncertain", "cca_wait_ns", "new_switch_ns", "transition_fits", "available_ns", "arrival_bits", "demand_packets",
    "demand_bits", "full_work_ns", "full_tx_ns", "full_rx_ns", "full_success_bits", "effective_service_mbps", "success_bits",
    "lower_success_bits", "upper_success_bits", "unserved_bits", "ending_queue_packets", "tx_ns", "rx_ns", "idle_ns", "tx_uj",
    "rx_uj", "idle_uj", "active_switch_uj", "new_switch_uj", "window_uj", "tail_ns", "tail_uj", "total_cost_uj", "low_cost_uj",
    "high_cost_uj", "request_ns", "switch_start_ns", "switch_end_ns", "wait_ns", "from_mode", "to_mode", "switch_energy_uj",
    "phase", "sta_id", "subchannel", "measured_ns", "arrival_ns", "expires_ns", "sinr_db", "observed_mask", "access_mask", "busy",
)
PERFORMANCE_FIELDS = (
    "scope", "observation_end_ns", "input_sha256", "generated", "delivered", "dropped", "incomplete", "delivered_after_window",
    "dropped_after_window", "offered_mbps", "goodput_mbps", "p95_ns", "p99_ns", "peak_queue_packets", "queue_packets",
    "inflight_packets", "awaiting_confirm_packets", "retransmissions", "idle_ns", "tx_ns", "rx_ns", "switch_ns", "hcm_ns",
    "hcm_fraction", "switches_started", "switches_completed", "switch_energy_uj", "radio_energy_uj", "scan_energy_uj",
    "total_energy_uj", "energy_nj_per_success_bit",
)


def base(seed, policy, end_us):
    return configuration(seed, policy, end_us) | {"wifi.ap.observation.period.us": str(PERIOD_US),
                                                  "wifi.phy.per.enabled": "false", "wifi.channel.interference.enabled": "false"}


def set_associations(settings, sta_sfu):
    settings.update({"sfu.count": str(max(sta_sfu)), "sta.count": str(len(sta_sfu))})
    settings.update({f"sta.{sta}.sfu.id": str(sfu) for sta, sfu in enumerate(sta_sfu, 1)})


def radio_config(settings, sta_sfu, bands, begin_us, end_us, pulses=False):
    # The receiver can see interference below the AP's CCA threshold. Stronger pulses also block access.
    window_us = int(settings["wifi.ap.activity.window.us"])
    ticks = range(PERIOD_US, window_us, PERIOD_US)
    noise = [(sfu, band, begin_us, end_us, -96, -80) for sfu, channels in bands.items() for band in channels]
    if pulses:
        noise.extend((sfu, band, t, min(end_us, t + 750), -73, -86)
                     for sfu, channels in bands.items() for band in channels
                     for t in range(begin_us + 2200, end_us, 4000))
    settings.update({
        "wifi.channel.observation.enabled": "true", "wifi.channel.passive.enabled": "true",
        "wifi.phy.per.enabled": "true", "wifi.phy.per.source": "SYNTHETIC_D2_SCENARIO",
        "wifi.phy.per.seed": settings["wifi.mac.edca.seed"], "wifi.phy.per.curves": "7,-12,1;7,10,0.5;7,30,0",
        "wifi.phy.per.frequency.model": "subchannel-blocks", "wifi.phy.per.reference.mpdu.bytes": "1530",
        "wifi.channel.interference.enabled": "true", "wifi.channel.interference.cca.threshold.dbm": "-82",
        "wifi.channel.interference.timeline": ";".join(",".join(map(str, event)) for event in noise),
        "wifi.channel.truth": ";".join(f"{sfu},{sta},{sub},0,30,-1,false,-95"
                                       for sta, sfu in enumerate(sta_sfu, 1) for sub in range(4)),
        "wifi.channel.feedback.airtime.us": "0",
        "wifi.channel.feedback": ";".join(f"{sfu},{sta},{sub},{tick-250},100,6000"
                                          for tick in ticks for sta, sfu in enumerate(sta_sfu, 1) for sub in range(4)),
        "wifi.channel.scan.power.w": "0.5",
        "wifi.channel.scan.schedule": ";".join(f"{sfu},{sub},{tick-1900+(sub-1)*430},400,6000"
                                                for tick in ticks for sfu in bands for sub in (1, 2, 3)),
    })


def grid_config(profile, size, seed, policy):
    settings = base(seed, policy, GRID_WINDOW_US)
    del settings["packet.bytes"]
    sta_sfu = tuple(range(1, len(GRID_LOADS) + 1))
    set_associations(settings, sta_sfu)
    settings.update({"traffic.mode": "poisson", "traffic.duration.us": str(GRID_WINDOW_US)})
    for sfu, rate in enumerate(GRID_LOADS, 1):
        prefix = f"traffic.sfu.{sfu}."
        settings.update({prefix + "seed": str(seed + 401 * sfu), prefix + "class.BE.rate.mbps": str(rate),
                         prefix + "class.BE.packet.bytes": str(size)})
    if profile != "ideal":
        radio_config(settings, sta_sfu, dict.fromkeys(sta_sfu, PATTERNS[profile]), 0, GRID_WINDOW_US)
    return settings


def packet_times(start, end, rate, size):
    return range(start, end, max(1, round(size * 8 / rate)))


def dynamic_config(scenario, seed, policy):
    settings = base(seed, policy, DYNAMIC_WINDOW_US)
    if scenario != "burst-multi":
        for_stage = (packet_times(index * 40000, (index + 1) * 40000, rate, 1500)
                     for index, rate in enumerate((10, 50, 10)))
        settings["packet.arrival.us"] = ",".join(str(t) for stage in for_stage for t in stage)
        if scenario == "step-secondary":
            radio_config(settings, (1,), {1: PATTERNS["secondary"]}, 40000, 90000, pulses=True)
        return settings

    sta_sfu = (1, 1, 2, 2)
    set_associations(settings, sta_sfu)
    settings["packet.bytes"] = "1000"
    # Background with short bursts addressed to both stations of each AP. VI and BE use separate queues.
    arrivals = [(t, sta, "BE") for sta in range(1, 5)
                for t in packet_times(0, 120000, 6 if sta < 3 else 4, 1000)]
    arrivals += [(t, sta, "VI" if sta % 2 == 0 else "BE") for sta in range(1, 5)
                 for start in ((22000, 62000) if sta < 3 else (33000, 80500))
                 for t in packet_times(start, start + 13000, 36 if sta < 3 else 26, 1000)]
    arrivals.sort()
    settings["packet.arrival.us"] = ",".join(str(t) for t, _, _ in arrivals)
    for packet_id, (_, sta, ac) in enumerate(arrivals, 1):
        if sta != 1:
            settings[f"packet.{packet_id}.sta.id"] = str(sta)
        if ac != "BE":
            settings[f"packet.{packet_id}.type"] = ac
    radio_config(settings, sta_sfu, {1: PATTERNS["secondary"], 2: PATTERNS["primary"]}, 18000, 102000, pulses=True)
    return settings


def cases(smoke):
    patterns = ("ideal", "secondary") if smoke else tuple(PATTERNS)
    lengths = (1500,) if smoke else LENGTHS
    for profile, size, policy in product(patterns, lengths, POLICIES):
        yield "grid", profile, size, SEEDS[0], policy, grid_config(profile, size, SEEDS[0], policy)
    for scenario, seed, policy in product(DYNAMIC_CASES, SEEDS[:1] if smoke else SEEDS, POLICIES):
        profile = {"step-ideal": "ideal", "step-secondary": "secondary", "burst-multi": "mixed"}[scenario]
        size = 1000 if scenario == "burst-multi" else 1500
        yield scenario, profile, size, seed, policy, dynamic_config(scenario, seed, policy)


def audit_candidate(name, pair, row, settings):
    def n(key):
        return float(row[key])

    mode = row["candidate_mode"]
    projected = row["projected_mode"]
    changing = mode != projected
    base = pair[projected]
    horizon = n("horizon_ns")
    rem = horizon - n("local_wait_ns") - n("active_switch_ns")
    cca = rem * float(base["expected_busy_fraction"]) if changing and row["external_busy"] == "true" else 0
    key = f"wifi.ap.switch.{projected.lower()}-to-{mode.lower()}."
    switch = min(float(settings[key + "delay.us"]) * 1000, rem - cca) if changing else 0
    service_window = rem - cca - switch
    available = service_window * (1 - n("expected_busy_fraction"))
    share = min(1, available / n("full_work_ns")) if n("full_work_ns") else 1
    success_bits = min(n("demand_bits"), n("full_success_bits") * share)
    remainder = n("demand_bits") - success_bits
    tx, rx = n("full_tx_ns") * share, n("full_rx_ns") * share
    powers = tuple(map(float, DEFAULT_POWERS[mode]))
    source = tuple(map(float, DEFAULT_POWERS[row["current_mode"]]))
    idle_wait = n("local_wait_ns") - n("prefix_tx_ns") - n("prefix_rx_ns")
    tx_uj = (n("prefix_tx_ns") * source[1] + tx * powers[1]) / 1000
    rx_uj = (n("prefix_rx_ns") * source[2] + rx * powers[2]) / 1000
    idle_uj = (idle_wait * source[0] + cca * float(DEFAULT_POWERS[projected][0])
               + max(0, service_window - tx - rx) * powers[0]) / 1000
    actual_switch = switch * float(settings[key + "power.w"]) / 1000 if changing else 0
    active_key = f"wifi.ap.switch.{row['current_mode'].lower()}-to-{projected.lower()}.power.w"
    active_switch = n("active_switch_ns") * float(settings[active_key]) / 1000 if n("active_switch_ns") else 0
    window_uj = tx_uj + rx_uj + idle_uj + actual_switch + active_switch
    tail_uj = 0
    if remainder > 0:
        if n("full_success_bits") == 0 or n("expected_busy_fraction") == 1:
            tail_uj = inf
        else:
            tail_air = n("full_work_ns") / (1 - n("expected_busy_fraction"))
            tail_uj = remainder * (powers[0] * (tail_air - n("full_tx_ns") - n("full_rx_ns"))
                                   + powers[1] * n("full_tx_ns") + powers[2] * n("full_rx_ns")) / n("full_success_bits") / 1000
    total = window_uj + tail_uj if row["transition_fits"] == "true" else inf
    partition = n("idle_ns") + n("tx_ns") + n("rx_ns") + n("active_switch_ns") + switch
    require(isclose(partition, horizon, abs_tol=1e-6), f"{name}: prediction does not fully partition time")
    expected = dict(available_ns=available, success_bits=success_bits, unserved_bits=remainder, tx_uj=tx_uj, rx_uj=rx_uj,
                    idle_uj=idle_uj, active_switch_uj=active_switch, new_switch_uj=actual_switch,
                    window_uj=window_uj, tail_uj=tail_uj, total_cost_uj=total)
    require(all(isclose(n(key), value, rel_tol=1e-10, abs_tol=1e-7) for key, value in expected.items()),
            f"{name}: cost mismatch for {mode} at {row['t_ns']}")
    require(n("lower_success_bits") <= success_bits + 1e-8 <= n("upper_success_bits") + 2e-8,
            f"{name}: forecast bounds do not contain expected success")


def audit_controller(name, rows, queue, settings, cutoff_ns):
    policy = settings[POLICY]
    if policy not in ENERGY_POLICIES:
        return {str(sfu): audit_threshold(name, rows, queue, settings, cutoff_ns, str(sfu))
                for sfu in range(1, int(settings["sfu.count"]) + 1)}

    grouped = defaultdict(dict)
    for row in events(rows, D2_DECISION):
        group = grouped[(row["sfu_id"], time_ns(row))]
        require(row["candidate_mode"] not in group, f"{name}: repeated mode calculation")
        group[row["candidate_mode"]] = row

    # Window-end ingress belongs to the current D1/D2 interval. Requeues and goodput are not arrivals.
    period_ns = PERIOD_US * 1000
    arrivals = defaultdict(int)
    for row in queue:
        if row["action"] == "ENQUEUED" and row["direction"] == "DL":
            tick = max(1, (time_ns(row) + period_ns - 1) // period_ns) * period_ns
            arrivals[(row["sfu_id"], tick)] += int(row["bytes"])

    initial = {row["sfu_id"]: row["current_mode"] for row in events(rows, INITIAL)}
    require(len(grouped) == len(initial) * len(range(period_ns, cutoff_ns, period_ns)), f"{name}: missing decisions")
    for (sfu, tick), pair in grouped.items():
        require(set(pair) == set(MODES) and tick % period_ns == 0 and 0 < tick < cutoff_ns,
                f"{name}: incomplete prediction pair or unexpected decision time")
        low, high = pair.values()
        require(low["target_mode"] == high["target_mode"]
                and isclose(float(low["demand_bits"]), float(high["demand_bits"]), rel_tol=1e-12, abs_tol=1e-8),
                f"{name}: two proposals must cover identical work")
        for row in pair.values():
            require(isclose(float(row["arrival_mbps"]), arrivals[sfu, tick] * 8000 / period_ns, abs_tol=1e-9),
                    f"{name}: D2 used other than the local C1 ingress at {tick}")
            if policy == "e-aware":
                require(float(row["expected_busy_fraction"]) == float(row["mean_failure"]) == 0,
                        f"{name}: E-Aware read channel data")
            audit_candidate(name, pair, row, settings)

        keep = pair[low["projected_mode"]]
        alt = next(row for row in pair.values() if row is not keep)
        central, current = float(alt["total_cost_uj"]), float(keep["total_cost_uj"])
        can_switch = (alt["transition_fits"] == "true" and isfinite(central)
                      and central + float(alt["margin_uj"]) < current and alt["local_wait_uncertain"] == "false")
        if alt["channel_incomplete"] == "true" or keep["channel_incomplete"] == "true":
            can_switch &= float(alt["high_cost_uj"]) + float(alt["margin_uj"]) < float(keep["low_cost_uj"])
        require(low["target_mode"] == (alt if can_switch else keep)["candidate_mode"], f"{name}: mode choice mismatch")
    return initial


def energy_and_performance(name, rows, queue, packets, endpoint, cutoff_ns, initial, switches, metadata, nominal):
    results = []
    for sfu, mode in initial.items():
        energy = audit_energy(rows, mode, cutoff_ns, sfu, switches[sfu])
        k = energy.pop("total_energy_uj")
        scans = [row for row in events(rows, "WIFI_CHANNEL_SCAN") if row["phase"] == "END" and row["sfu_id"] == sfu]
        scan_uj = sum((Decimal(row["extra_energy_uj"]) for row in scans), Decimal(0))
        if any(row.get("summary") == "WIFI_CHANNEL_OVERHEAD" for row in rows):
            overhead = by_summary(rows, "WIFI_CHANNEL_OVERHEAD", sfu_id=sfu)
            require(Decimal(overhead["scan_energy_uj"]) == scan_uj, f"{name}: scan energy discrepancy")
        perf = packet_metrics(name, rows, queue, packets, endpoint, cutoff_ns, sfu, "ALL")
        results.append(metadata | {"sfu_id": sfu, "scope": "AP", "nominal_load_mbps": nominal.get(sfu, "")}
                       | {f: perf[f] for f in PERFORMANCE_FIELDS if f in perf}
                       | {f: energy[f] for f in PERFORMANCE_FIELDS if f in energy}
                       | {"radio_energy_uj": k, "scan_energy_uj": scan_uj, "total_energy_uj": k + scan_uj})

    require(sum(r["radio_energy_uj"] for r in results) == Decimal(by_summary(rows, "WIFI_AP_ENERGY_NETWORK")["total_energy_uj"]),
            f"{name}: SFU energy does not reconcile to the network")
    if len(results) > 1:
        delivered, dropped, order = endpoint
        quality = metrics_at(packets, cutoff_ns, 0, packets, delivered, dropped, order)
        labels = {"scope", "observation_end_ns", "input_sha256", "p95_ns", "p99_ns", "energy_nj_per_success_bit",
                  "peak_queue_packets", "hcm_fraction"}
        totals = {key: sum(r[key] for r in results) for key in PERFORMANCE_FIELDS if key not in labels}
        results.append(metadata | {"sfu_id": "ALL", "scope": "NETWORK"} | totals
                       | dict(p95_ns=quality["p95_ns"], p99_ns=quality["p99_ns"],
                              hcm_fraction=totals["hcm_ns"] / (len(initial) * cutoff_ns)))
    for row in results:
        bits = sum(packets[p][1] * 8 for p, t in endpoint[0].items()
                   if t <= cutoff_ns and row["sfu_id"] in (packets[p][5], "ALL"))
        row["energy_nj_per_success_bit"] = row["total_energy_uj"] * 1000 / bits if bits else None
    return results


def run_case(case, jar, work, keep_logs):
    scenario, profile, size, seed, policy, settings = case
    name = f"{scenario}-{profile}-{size}-{seed}-{policy}"
    path = work / f"{name}.properties"
    write_properties(path, settings)
    raw = simulate(jar, path)
    detailed = scenario == "step-secondary" and seed == SEEDS[0] and policy == "ec-aware"
    if detailed or keep_logs:
        (work / f"{name}.log").write_text(raw, encoding="utf-8")
    rows = parse_trace(raw)
    del raw
    packets = packet_inputs(rows, settings)
    digest = sha256(repr(list(packets.items())).encode("utf-8")).hexdigest()
    endpoint = endpoint_state(name, rows, packets)
    queue = verify_queue(name, rows)
    cutoff_ns = int(settings["wifi.ap.activity.window.us"]) * 1000
    initial = audit_controller(name, rows, queue, settings, cutoff_ns)
    switches = audit_switches(name, rows, settings, cutoff_ns)
    nominal = {str(sfu): b for sfu, b in enumerate(GRID_LOADS, 1)} if scenario == "grid" else {}
    fixed = dict(scenario=scenario, profile=profile, seed=seed, policy=policy, packet_bytes=size)
    decisions = []
    for row in rows:
        event = row.get("event")
        channel = event == "WIFI_EXTERNAL_CCA" or (event == "WIFI_CHANNEL_FEEDBACK" and row["phase"] == "ACCEPTED")
        if event in (INITIAL, D1_DECISION, D2_DECISION, *SWITCH_EVENTS) or (detailed and channel):
            details = {key: row[key] for key in EVENT_FIELDS if key in row}
            decisions.append(fixed | dict(t_ns=time_ns(row), sfu_id=row["sfu_id"],
                                          nominal_load_mbps=nominal.get(row["sfu_id"], "")) | details)
    if detailed:
        # The observation closes at the cutoff, when the controller no longer issues a new decision.
        start_ns = cutoff_ns - PERIOD_US * 1000
        tail_bytes = sum(int(row["bytes"]) for row in queue if row["action"] == "ENQUEUED"
                         and start_ns < time_ns(row) <= cutoff_ns)
        decisions.append(fixed | dict(event="C1_INGRESS_TAIL", t_ns=cutoff_ns, sfu_id="1", window_start_ns=start_ns,
                                      arrival_mbps=tail_bytes * 8000 / (cutoff_ns - start_ns)))
    performance = energy_and_performance(name, rows, queue, packets, endpoint, cutoff_ns, initial, switches,
                                         fixed | dict(observation_end_ns=cutoff_ns, input_sha256=digest), nominal)
    print(f"{name}: {len(packets)} packets, {len(endpoint[0])} delivered, {len(endpoint[1])} dropped after drain", flush=True)
    return (scenario, profile, size, seed), digest, decisions, performance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--output", type=Path, default=OUT)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--keep-logs", action="store_true")
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    fingerprints, decision_rows, performance_rows = {}, [], []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for group, identity, decisions, performance in pool.map(
                lambda case: run_case(case, args.jar, args.work, args.keep_logs), cases(args.smoke)):
            require(fingerprints.setdefault(group, identity) == identity, f"Unmatched packet inputs in {group}")
            decision_rows.extend(decisions)
            performance_rows.extend(performance)
    write_csv(args.output / "d2-decision-events.csv", IDENTITY + EVENT_FIELDS, decision_rows)
    write_csv(args.output / "d2-performance.csv", IDENTITY + PERFORMANCE_FIELDS, performance_rows)


if __name__ == "__main__":
    main()
