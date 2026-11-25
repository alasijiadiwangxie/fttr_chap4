"""Run D1 scenarios and reconcile the decision, packet, MAC, QoS and energy traces."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from decimal import Decimal
from hashlib import sha256
from math import isclose
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv
from run_b3_ap_energy import DEFAULT_POWERS
from run_b4_ap_switch import STATES, by_summary, trace_durations, verify_totals, watts_times_ns
from verify_c1_ap_observation import ACS, time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import endpoint_state, events, metrics_at, packet_inputs, verify_queue


RESULTS = ROOT / "results/d1-mode-control"
WORK = ROOT / "target/d1-mode-control"
JAR = ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar"
POLICY = "wifi.ap.control.policy"
FIXED = "fixed-threshold"
POLICIES = ("always-lcm", "always-hcm", FIXED)
SEEDS = (101, 202, 303)
STEP_US = 40_000
STEP_RATES = (10, 50, 10)
THRESHOLD = 31
DEADLINES = dict(zip(ACS, (3000, 8000, 10000, 35000)))
INITIAL = "WIFI_AP_MODE_INITIAL"
DECISION = "WIFI_AP_MODE_DECISION"
SWITCH = "WIFI_AP_SWITCH_"
SWITCH_EVENTS = tuple(SWITCH + suffix for suffix in ("REQUEST", "START", "END", "CANCELLED"))
DIAGNOSTICS = {"WIFI_TX_START", "WIFI_ACK_END", "WIFI_ACK_TIMEOUT", "WIFI_AMPDU_DATA_START", "WIFI_AMPDU_BA_END",
               "WIFI_MAC_RETRY", "WIFI_PHY_MPDU_DECODE", "WIFI_EXTERNAL_CCA", "DELIVERED"}
EVENT_COLUMNS = (
    "scenario", "seed", "policy", "trace_sequence", "event", "t_ns", "sfu_id", "window_start_ns",
    "arrival_bytes", "arrival_mbps", "ewma_mbps", "current_mode", "target_mode", "action", "switch_id",
    "from_mode", "to_mode", "request_ns", "switch_start_ns", "switch_end_ns", "wait_ns", "duration_ns",
    "power_w", "observed_ns", "switch_energy_uj", "packet_id", "packet_ids", "bitmap", "retry_count",
    "outcome", "access_mask", "busy",
)
METRIC_COLUMNS = (
    "scenario", "seed", "policy", "sfu_id", "ac", "initial_mode", "observation_end_ns", "input_sha256",
    "generated", "admitted_to_ap", "delivered", "dropped", "incomplete", "overdue", "gap_before_ap",
    "queue_packets", "inflight_packets", "awaiting_confirm_packets", "peak_queue_packets",
    "delivered_after_window", "dropped_after_window", "offered_mbps", "goodput_mbps", "retransmissions",
    "avg_ns", "p95_ns", "p99_ns", "deadline_us", "on_time", "violations", "decided", "violation_ratio",
    "lcm_ns", "hcm_ns", "hcm_fraction", "idle_ns", "tx_ns", "rx_ns", "switch_ns", "switches_started",
    "switches_completed", "switch_energy_uj", "total_energy_uj", "avg_power_w", "energy_nj_per_success_bit",
)


def configuration(seed, policy, end_us):
    settings = {
        POLICY: policy, "traffic.mode": "manual", "traffic.direction": "DL", "fiber.delay.us": "0",
        "sfu.count": "1", "sta.count": "1", "sta.1.sfu.id": "1", "packet.bytes": "1500",
        "wifi.model": "he-su", "wifi.mac.exchange": "data-sifs-ack", "wifi.mac.access": "edca-multi-ac",
        "wifi.mac.sifs.us": "16", "wifi.mac.slot.us": "9", "wifi.mac.ack.rate.mbps": "24",
        "wifi.mac.ack.timeout.us": "60", "wifi.mac.retry.max": "2", "wifi.mac.edca.seed": str(seed),
        "wifi.mac.edca.BE.aifsn": "3", "wifi.mac.ampdu.enabled": "true", "wifi.mac.ampdu.max-mpdus": "8",
        "wifi.ap.activity.enabled": "true", "wifi.ap.energy.enabled": "true",
        "wifi.ap.activity.window.us": str(end_us), "wifi.ap.observation.enabled": "true",
        "wifi.ap.qos.enabled": "true", "wifi.ap.switch.enabled": "true",
        "wifi.ap.switch.lcm-to-hcm.delay.us": "40.125", "wifi.ap.switch.lcm-to-hcm.power.w": "11.50",
        "wifi.ap.switch.hcm-to-lcm.delay.us": "25.250", "wifi.ap.switch.hcm-to-lcm.power.w": "8.75",
    }
    settings.update({f"wifi.ap.qos.{ac}.deadline.us": str(deadline) for ac, deadline in DEADLINES.items()})
    return settings


def cases():
    step_arrivals = (x for segment, rate in enumerate(STEP_RATES)
                     for x in range(segment * STEP_US, (segment + 1) * STEP_US, 12000 // rate))
    step_input = ",".join(map(str, step_arrivals))
    for seed in SEEDS:
        for policy in POLICIES:
            yield "step", seed, configuration(seed, policy, 3 * STEP_US) | {"packet.arrival.us": step_input}

    seed = SEEDS[0]
    short = {"wifi.mac.edca.BE.cwmin": "0"}
    yield "threshold-edge", seed, configuration(seed, FIXED, 1700) | short | {
        "wifi.ap.control.period.us": "400", "wifi.ap.control.ewma.alpha": "1",
        "packet.arrival.us": "1,401,801,1201", "packet.1.bytes": "1550", "packet.2.bytes": "1551",
        "packet.3.bytes": "1549", "packet.4.bytes": "1551",
    }
    for name, end, period, extra in (
        ("partial-ba", 650, 100, {"wifi.mac.ampdu.first-attempt-error.packet.ids": "2"}),
        ("retry-switch", 500, 300, {"wifi.mac.ampdu.first-attempt-error.packet.ids": "2",
                                    "sfu.1.wifi.ap.mode": "HCM", "wifi.ap.control.threshold.mbps": "121"}),
        ("cancel-pending", 350, 100, {"wifi.ap.control.ewma.alpha": "1"}),
        ("reverse-active", 400, 100, {"wifi.ap.control.ewma.alpha": "1",
                                      "wifi.ap.switch.lcm-to-hcm.delay.us": "240", "packet.arrival.us": "100,300"}),
    ):
        yield name, seed, configuration(seed, FIXED, end) | short | {
            "wifi.ap.control.period.us": str(period), "packet.arrival.us": "0,0,0",
        } | extra

    yield "ack-timeout", seed, configuration(seed, FIXED, 700) | short | {
        "packet.arrival.us": "0", "sfu.1.wifi.ap.mode": "HCM", "wifi.ap.control.period.us": "140",
        "wifi.ap.control.threshold.mbps": "100", "wifi.phy.per.enabled": "true", "wifi.phy.per.seed": str(seed),
        "wifi.phy.per.curves": "7,0,1;7,30,0", "wifi.phy.per.source": "SYNTHETIC_D1_TEST",
        "wifi.channel.observation.enabled": "true",
        "wifi.channel.truth": ";".join(f"1,1,{band},{t},{quality},-1,false,-95"
                                       for t, quality in ((0, -5), (200, 35)) for band in range(4)),
    }

    dual_arrivals = sorted((t, sta) for sta, rate in ((1, 10), (2, 50)) for t in range(0, 40000, 12000 // rate))
    dual = {
        "sfu.count": "2", "sta.count": "2", "sta.2.sfu.id": "2",
        "packet.arrival.us": ",".join(str(t) for t, _ in dual_arrivals),
    }
    dual.update({f"packet.{pid}.sta.id": str(sta) for pid, (_, sta) in enumerate(dual_arrivals, 1) if sta != 1})
    yield "dual-ap", seed, configuration(seed, FIXED, 40000) | dual

    mixed = configuration(seed, FIXED, 30000)
    del mixed["packet.bytes"]
    mixed.update({
        "traffic.mode": "poisson", "traffic.duration.us": "30000", "traffic.sfu.1.seed": str(seed),
        "sta.count": "2", "sta.2.sfu.id": "1", "wifi.channel.observation.enabled": "true",
        "wifi.phy.per.enabled": "true", "wifi.phy.per.seed": str(seed),
        "wifi.phy.per.frequency.model": "subchannel-blocks", "wifi.phy.per.reference.mpdu.bytes": "1530",
        "wifi.phy.per.curves": "7,-12,1;7,10,0.5;7,30,0", "wifi.phy.per.source": "SYNTHETIC_D1_TEST",
        "wifi.channel.truth": ";".join(f"1,{sta},{band},0,30,-1,false,-95"
                                       for sta in (1, 2) for band in range(4)),
        "wifi.channel.interference.enabled": "true",
        "wifi.channel.interference.timeline": "1,3,6000,26000,-96,-69;1,0,12000,12500,-70,-88",
    })
    for ac, rate in zip(ACS, (4, 5, 35, 2)):
        mixed[f"traffic.sfu.1.class.{ac}.rate.mbps"] = str(rate)
        mixed[f"traffic.sfu.1.class.{ac}.packet.bytes"] = "1500"
    yield "channel-multi-ac", seed, mixed


def audit_decisions(name, rows, queue, settings, end_ns, sfu):
    initial = [row for row in events(rows, INITIAL) if row["sfu_id"] == sfu]
    decisions = [row for row in events(rows, DECISION) if row["sfu_id"] == sfu]
    require(len(initial) == 1 and time_ns(initial[0]) == 0, f"{name}: missing startup mode for SFU {sfu}")
    mode = initial[0]["current_mode"]
    if settings[POLICY] != FIXED:
        require(not decisions and mode == settings[POLICY][-3:].upper(), f"{name}: Always must start pinned")
        return mode

    period_ns = int(Decimal(settings.get("wifi.ap.control.period.us", "5000")) * 1000)
    alpha = float(settings.get("wifi.ap.control.ewma.alpha", "0.25"))
    threshold = float(settings.get("wifi.ap.control.threshold.mbps", str(THRESHOLD)))
    arrivals = [(time_ns(row), int(row["bytes"])) for row in queue if row["sfu_id"] == sfu
                and row["action"] == "ENQUEUED" and row["direction"] == "DL"]
    require([time_ns(row) for row in decisions] == list(range(period_ns, end_ns, period_ns)),
            f"{name}: missing or extra decisions")
    previous = None
    start = 0
    for decision in decisions:
        stop = time_ns(decision)
        arrived = sum(nbytes for now, nbytes in arrivals if (start < now <= stop) or now == start == 0)
        load = arrived * 8000 / period_ns
        estimate = load if previous is None or load == previous else (1 - alpha) * previous + alpha * load
        require(int(decision["window_start_ns"]) == start and int(decision["arrival_bytes"]) == arrived
                and isclose(float(decision["arrival_mbps"]), load, abs_tol=1e-10)
                and isclose(float(decision["ewma_mbps"]), estimate, abs_tol=1e-10)
                and decision["target_mode"] == ("HCM" if estimate > threshold else "LCM"),
                f"{name}: decision used incorrect arrival history {decision}")
        start, previous = stop, estimate
    return mode


def audit_switches(name, rows, settings, end_ns):
    pending = {}
    active = {}
    live_mpdus = defaultdict(set)
    switches = defaultdict(list)
    used = set()
    for row in rows:
        event = row.get("event")
        if event not in (*SWITCH_EVENTS, "WIFI_MAC_ATTEMPT", "WIFI_MAC_SUCCESS", "WIFI_MAC_RETRY",
                         "WIFI_RETRY_LIMIT_DROP"):
            continue
        sfu = row["sfu_id"]
        if event == "WIFI_MAC_ATTEMPT":
            packet = row["packet_id"]
            require(sfu not in active and packet not in live_mpdus[sfu], f"{name}: duplicated or illegal DATA {row}")
            live_mpdus[sfu].add(packet)
        elif event not in SWITCH_EVENTS:
            packet = row["packet_id"]
            require(packet in live_mpdus[sfu] and sfu not in active, f"{name}: invalid MAC settlement {row}")
            live_mpdus[sfu].remove(packet)
        elif event == SWITCH + "REQUEST":
            key = sfu, row["switch_id"]
            require(key not in used and sfu not in pending, f"{name}: duplicate or conflicting request {row}")
            used.add(key)
            pending[sfu] = row
        elif event == SWITCH + "CANCELLED":
            require(pending[sfu]["switch_id"] == row["switch_id"], f"{name}: cancelled the wrong request")
            del pending[sfu]
        elif event == SWITCH + "START":
            request = pending.pop(sfu)
            start, asked = time_ns(row), time_ns(request)
            require(not live_mpdus[sfu] and sfu not in active and request["switch_id"] == row["switch_id"]
                    and start >= asked and int(row["wait_ns"]) == start - asked,
                    f"{name}: switch overlaps an active exchange or ACK wait {row}")
            active[sfu] = row
        else:
            start_row = active.pop(sfu)
            start, finish = time_ns(start_row), time_ns(row)
            source, target = row["from_mode"], row["to_mode"]
            key = f"wifi.ap.switch.{source.lower()}-to-{target.lower()}"
            power = Decimal(settings[key + ".power.w"])
            expected_ns = Decimal(settings[key + ".delay.us"]) * 1000
            observed = max(0, min(end_ns, finish) - start)
            require(row["switch_id"] == start_row["switch_id"] and finish - start == expected_ns
                    and int(row["switch_start_ns"]) == start and int(row["switch_end_ns"]) == finish
                    and int(row["observed_ns"]) == observed and Decimal(row["power_w"]) == power
                    and Decimal(row["switch_energy_uj"]) == watts_times_ns(power, observed),
                    f"{name}: invalid switch duration or energy {row}")
            switches[sfu].append(dict(start_ns=start, finish_ns=finish, source=source, target=target,
                                      observed_ns=observed, power=power))
    require(not pending and not active and not any(live_mpdus.values()), f"{name}: unfinished switch or MAC exchange")
    return switches


def audit_energy(rows, initial, end_ns, sfu, switches):
    activity_rows = [row for row in events(rows, "WIFI_AP_ACTIVITY_CHANGE") if time_ns(row) <= end_ns]
    times = trace_durations(activity_rows, int(sfu), initial, end_ns, switches)
    totals = [sum(timeline[i] for timeline in times.values()) for i in range(len(STATES))]
    energy = [sum(watts_times_ns(powers[i], times[mode][i]) for mode, powers in DEFAULT_POWERS.items())
              for i in range(3)]
    energy.append(sum((watts_times_ns(s["power"], s["observed_ns"]) for s in switches), Decimal(0)))
    activity = by_summary(rows, "WIFI_AP_ACTIVITY", sfu_id=sfu)
    verify_totals(by_summary(rows, "WIFI_AP_ENERGY", sfu_id=sfu), energy, end_ns)
    require(sum(totals) == end_ns and totals == [int(activity[f"{state}_ns"]) for state in STATES],
            f"SFU {sfu}: trace and B2 activity do not reconcile")
    lcm_ns, hcm_ns = sum(times["LCM"]), sum(times["HCM"])
    return dict(zip((f"{state}_ns" for state in STATES), totals)) | {
        "lcm_ns": lcm_ns, "hcm_ns": hcm_ns, "hcm_fraction": hcm_ns / end_ns,
        "switches_started": sum(s["start_ns"] < end_ns for s in switches),
        "switches_completed": sum(s["finish_ns"] <= end_ns for s in switches),
        "switch_energy_uj": energy[-1], "total_energy_uj": sum(energy),
        "avg_power_w": sum(energy) / (Decimal(end_ns) / 1000),
    }


def packet_metrics(name, rows, queue, packets, endpoint, end_ns, sfu, ac):
    deliveries, drops, order = endpoint
    keys = [key for key, info in packets.items() if info[5] == sfu and (ac == "ALL" or info[2] == ac)]
    deadline = DEADLINES.get(ac)
    actual = metrics_at(keys, end_ns, (deadline or 0) * 1000, packets, deliveries, drops, order)
    queue_counts = {}
    enqueued = retransmissions = peak = 0
    for row in queue:
        if row["sfu_id"] != sfu or (ac != "ALL" and row["ac"] != ac) or time_ns(row) > end_ns:
            continue
        enqueued += row["action"] == "ENQUEUED"
        retransmissions += row["action"] == "RETRANSMIT_START"
        queue_counts[row["ac"]] = tuple(int(row[key]) for key in
                                       ("pending_packets", "inflight_packets", "awaiting_confirm_packets"))
        peak = max(peak, sum(counts[0] for counts in queue_counts.values()))
    waiting, inflight, unconfirmed = (sum(counts[i] for counts in queue_counts.values()) for i in range(3))
    before_ap = actual["arrived"] - enqueued
    require(before_ap >= 0 and actual["incomplete"] == before_ap + waiting + inflight - unconfirmed,
            f"{name}: missing or duplicated live packets at fixed cutoff")
    result = {
        "generated": actual["arrived"], "admitted_to_ap": enqueued, "delivered": actual["delivered"],
        "dropped": actual["dropped"], "incomplete": actual["incomplete"], "gap_before_ap": before_ap,
        "queue_packets": waiting, "inflight_packets": inflight, "awaiting_confirm_packets": unconfirmed,
        "peak_queue_packets": peak, "retransmissions": retransmissions,
        "delivered_after_window": sum(deliveries.get(key, 0) > end_ns for key in keys),
        "dropped_after_window": sum(drops.get(key, 0) > end_ns for key in keys),
        "offered_mbps": actual["offered_mbps"], "goodput_mbps": actual["goodput_mbps"],
        "avg_ns": actual["avg_ns"], "p95_ns": actual["p95_ns"], "p99_ns": actual["p99_ns"],
    }
    if deadline is not None and keys:
        checks = dict(overdue=actual["overdue"], on_time=actual["on_time"], violations=actual["violations"],
                      decided=actual["delivered"] + actual["dropped"] + actual["overdue"])
        quality = [row for row in events(rows, "WIFI_AP_QOS")
                   if row["sfu_id"] == sfu and row["ac"] == ac and row["direction"] == "DL"]
        require(len(quality) == 1 and time_ns(quality[0]) == end_ns, f"{name}: missing QoS snapshot for {ac}")
        check = checks | {key: actual[key] for key in ("arrived", "delivered", "dropped", "incomplete", "p95_ns", "p99_ns")}
        require(all(value is None or int(quality[0][key]) == value for key, value in check.items()),
                f"{name}: endpoint reconstruction differs from C4 {quality[0]}")
        require(int(quality[0]["queue_packets"]) == waiting and int(quality[0]["inflight_packets"]) == inflight
                and int(quality[0]["awaiting_confirm_packets"]) == unconfirmed,
                f"{name}: cutoff live queues differ from C4")
        result.update(checks | {"deadline_us": deadline, "violation_ratio": actual["violation_ratio"]})
    return result


def run_case(jar, work, scenario, seed, settings, fingerprints):
    policy = settings[POLICY]
    name = f"{scenario}-{seed}-{policy}"
    path = work / f"{name}.properties"
    write_properties(path, settings)
    raw = simulate(jar, path)
    (work / f"{name}.log").write_text(raw, encoding="utf-8")
    rows = parse_trace(raw)
    packets = packet_inputs(rows, settings)
    identity = sha256(repr(list(packets.items())).encode("utf-8")).hexdigest()
    require(fingerprints.setdefault((scenario, seed), identity) == identity, f"{name}: unpaired inputs")
    endpoint = endpoint_state(name, rows, packets)
    queue = verify_queue(name, rows)
    cutoff = int(settings["wifi.ap.activity.window.us"]) * 1000
    switches = audit_switches(name, rows, settings, cutoff)
    metadata = {"scenario": scenario, "seed": seed, "policy": policy}
    mode_rows = []
    last_step_decision = max((time_ns(row) for row in events(rows, DECISION)), default=0)
    for row in rows:
        event = row.get("event")
        last_step_arrival = (scenario == "step" and policy == FIXED and event == "WIFI_AP_QUEUE_EVENT"
                             and row["action"] == "ENQUEUED" and last_step_decision < time_ns(row) <= cutoff)
        diagnostic = scenario not in ("step", "dual-ap") and event in DIAGNOSTICS
        if event in (INITIAL, DECISION, *SWITCH_EVENTS) or diagnostic or last_step_arrival:
            exported = {key: row[key] for key in EVENT_COLUMNS if key in row}
            if last_step_arrival:
                exported["arrival_bytes"] = row["bytes"]
            mode_rows.append(exported | metadata | {"t_ns": time_ns(row)})

    metrics = []
    network_energy = Counter()
    for sid in range(1, int(settings["sfu.count"]) + 1):
        sfu = str(sid)
        initial = audit_decisions(name, rows, queue, settings, cutoff, sfu)
        energy = audit_energy(rows, initial, cutoff, sfu, switches[sfu])
        network_energy["total"] += energy["total_energy_uj"]
        network_energy["switch"] += energy["switch_energy_uj"]
        per_ac = {ac: packet_metrics(name, rows, queue, packets, endpoint, cutoff, sfu, ac) for ac in ACS}
        overall = packet_metrics(name, rows, queue, packets, endpoint, cutoff, sfu, "ALL")
        for key in ("on_time", "violations", "decided", "overdue"):
            overall[key] = sum(r.get(key, 0) for r in per_ac.values())
        overall["violation_ratio"] = overall["violations"] / overall["decided"] if overall["decided"] else None
        delivered_bits = sum(info[1] * 8 for p, info in packets.items()
                             if info[5] == sfu and p in endpoint[0] and endpoint[0][p] <= cutoff)
        energy["energy_nj_per_success_bit"] = energy["total_energy_uj"] * 1000 / delivered_bits if delivered_bits else None
        common = metadata | {"sfu_id": sfu, "initial_mode": initial, "observation_end_ns": cutoff, "input_sha256": identity}
        metrics.append(common | {"ac": "ALL"} | overall | energy)
        metrics.extend(common | {"ac": ac} | data for ac, data in per_ac.items() if data["generated"] > 0)
    actual_network = by_summary(rows, "WIFI_AP_ENERGY_NETWORK")
    require(all(Decimal(actual_network[f"{key}_energy_uj"]) == value for key, value in network_energy.items()),
            f"{name}: network energy did not sum per AP")

    if policy != FIXED:
        static_path = work / f"{name}-static-reference.properties"
        write_properties(static_path, settings | {POLICY: "off", "sfu.1.wifi.ap.mode": metrics[0]["initial_mode"]})
        static_lines = [line for line in raw.splitlines() if f"event={INITIAL}" not in line]
        require(simulate(jar, static_path).splitlines() == static_lines, f"{name}: static trace changed")
    if scenario == "step" and policy == FIXED:
        require([(s["source"], s["target"]) for s in switches["1"]] == [("LCM", "HCM"), ("HCM", "LCM")],
                f"{name}: step load did not trigger both directions")
    if scenario == "dual-ap":
        require(not switches["1"] and [s["target"] for s in switches["2"]] == ["HCM"],
                f"{name}: SFU decisions did not stay independent")
    release_events = {"partial-ba": "WIFI_AMPDU_BA_END", "retry-switch": "WIFI_ACK_END",
                      "ack-timeout": "WIFI_ACK_TIMEOUT"}
    if scenario in release_events:
        release = events(rows, release_events[scenario])
        require(len(release) == len(switches["1"]) == 1 and time_ns(release[0]) == switches["1"][0]["start_ns"],
                f"{name}: switch did not wait for the required MAC release event")
    if scenario == "channel-multi-ac":
        require(events(rows, "WIFI_EXTERNAL_CCA") and events(rows, "WIFI_PHY_MPDU_DECODE")
                and {p[2] for p in packets.values()} == set(ACS), f"{name}: missed requested channel/AC coverage")
    print(f"{name}: {len(packets)} inputs, {len(endpoint[0])} delivered after drain, "
          f"{len(endpoint[1])} dropped, {sum(map(len, switches.values()))} completed switches")
    return mode_rows, metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--work", type=Path, default=WORK)
    parser.add_argument("--output", type=Path, default=RESULTS)
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    fingerprints = {}
    events_output, metrics_output = [], []
    for scenario, seed, settings in cases():
        mode_rows, metrics = run_case(args.jar, args.work, scenario, seed, settings, fingerprints)
        events_output.extend(mode_rows)
        metrics_output.extend(metrics)
    write_csv(args.output / "d1-mode-events.csv", EVENT_COLUMNS, events_output)
    write_csv(args.output / "d1-performance.csv", METRIC_COLUMNS, metrics_output)


if __name__ == "__main__":
    main()
