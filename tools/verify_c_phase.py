"""Replay the joint C-phase Java matrix, independently audit events, and export compact CSVs."""

from __future__ import annotations

import argparse
from bisect import bisect_right
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from itertools import product
from math import ceil, isclose, log10
from pathlib import Path
from statistics import mean

from pon_experiment_io import ROOT, require, simulate
from verify_c1_ap_observation import (
    ACS, PPDU_EVENT, QUEUE_EVENT, WindowLedger, activity_intervals,
    check_protocol_events, csv_rows, overlaps, time_ns, write_properties,
)
from verify_c2b1_sinr_per import (
    CURVE, DECODE, REF_BYTES, SEED, configuration as flat_configuration, expected_draw, parse_trace,
)
from verify_c2b2a_subchannel_per import frequency_per


RESULT = ROOT / "results/c-phase"
JAR = ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar"
HORIZON_US = 60_000
HORIZON_NS = 1000 * HORIZON_US
SEEDS = (101, 202, 303)
MODES = ("LCM", "HCM")
LOADS = {"low": (2, 3, 6, 1), "mid": (5, 10, 30, 5), "high": (10, 18, 75, 7)}
PROFILES = {"clear": (), "primary": (0,), "secondary": (3,), "wide": (0, 1, 2, 3)}
DEADLINES_US = dict(zip(ACS, (3000, 8000, 20000, 35000)))
INTERFERENCE = "wifi.channel.interference."
OBSERVER = "wifi.channel.observation.enabled"
FORECAST = "WIFI_AP_PREDICTION"
QUALITY = "WIFI_AP_QOS"
FEASIBILITY = "WIFI_AP_QOS_FEASIBILITY"
CHANNEL_EVENTS = {DECODE, "WIFI_EXTERNAL_CCA", "WIFI_CHANNEL_FEEDBACK", "WIFI_CHANNEL_SCAN",
                  "WIFI_CHANNEL_PASSIVE", "WIFI_CHANNEL_OBSERVATION"}


def ns(value):
    return int(Decimal(value) * 1000)


def external_timeline(profile):
    rows = []
    for sfu, channel in product((1, 2), PROFILES[profile]):
        # A remote, below-CCA interferer is visible at the STA. A second source creates EDCA deferrals.
        rows.append((sfu, channel, 0, HORIZON_US, -96, -84))
        rows.extend((sfu, channel, start, start + 250, -74, -88)
                    for start in range(2400 + 50 * sfu, HORIZON_US - 250, 4000))
    return ";".join(",".join(map(str, row)) for row in rows)


def feedback_timeline(quality):
    timing = {
        "timely": (25, 25_000, ""), "delayed": (2200, 25_000, ""),
        "expired": (180, 120, ""), "lost": (50, 25_000, ",drop"),
    }
    delay, valid, suffix = timing[quality]
    return ";".join(f"{1 + (sta-1)//2},{sta},{channel},{base + (sta-1)%2*80 + 17*channel},{delay},{valid}{suffix}"
                    for base, sta, channel in product((0, 12000, 28000, 47000), range(1, 5), range(4)))


def config(mode, load, profile, seed, feedback="timely", scan=False):
    settings = {
        "sfu.count": "2", "sta.count": "4",
        "traffic.mode": "poisson", "traffic.direction": "BOTH", "traffic.duration.us": str(HORIZON_US),
        "fiber.downlink.mode": "pon-shared", "fiber.downlink.rate.mbps": "10000",
        "fiber.downlink.propagation.us": "10", "fiber.uplink.mode": "pon-shared",
        "fiber.uplink.rate.mbps": "2500", "fiber.uplink.propagation.us": "10",
        "wifi.model": "he-su", "wifi.mac.access": "edca-multi-ac", "wifi.mac.exchange": "data-sifs-ack",
        "wifi.mac.ack.rate.mbps": "24", "wifi.mac.ack.timeout.us": "60", "wifi.mac.retry.max": "2",
        "wifi.mac.edca.seed": str(seed), "wifi.mac.edca.BE.aifsn": "3",
        "wifi.mac.ampdu.enabled": "true", "wifi.mac.ampdu.max-mpdus": "8",
        "wifi.ap.activity.enabled": "true", "wifi.ap.activity.window.us": str(HORIZON_US),
        "wifi.ap.energy.enabled": "true", "wifi.ap.observation.enabled": "true",
        "wifi.ap.switch.enabled": "true",
        "wifi.ap.switch.lcm-to-hcm.delay.us": "40", "wifi.ap.switch.lcm-to-hcm.power.w": "10",
        "wifi.ap.switch.hcm-to-lcm.delay.us": "25", "wifi.ap.switch.hcm-to-lcm.power.w": "8",
        "wifi.ap.observation.period.us": "5000", "wifi.ap.observation.ewma.alpha": "0.25",
        "wifi.ap.prediction.enabled": "true", "wifi.ap.prediction.window.us": "5000",
        "wifi.ap.prediction.alpha": "0.25",
        "wifi.phy.per.enabled": "true", "wifi.phy.per.frequency.model": "subchannel-blocks",
        CURVE: "7,-12,1;7,10,0.5;7,30,0", REF_BYTES: "1530", SEED: str(seed),
        "wifi.phy.per.source": "SYNTHETIC_TEST",
        "wifi.channel.truth": ";".join(f"{1+(sta-1)//2},{sta},{sub},0,24,-1,false,-95"
                                       for sta, sub in product(range(1, 5), range(4))),
        INTERFERENCE + "enabled": "true", INTERFERENCE + "timeline": external_timeline(profile),
        OBSERVER: "true", "wifi.channel.feedback": feedback_timeline(feedback),
        "wifi.channel.feedback.airtime.us": "8", "wifi.channel.passive.enabled": "true",
        "wifi.channel.observe.us": ",".join(map(str, range(0, HORIZON_US + 1, 2000))),
    }
    for sfu in range(1, 3):
        settings[f"sfu.{sfu}.wifi.ap.mode"] = mode
        settings[f"traffic.sfu.{sfu}.seed"] = str(seed + 401 * sfu)
        for ac, rate in zip(ACS, LOADS[load]):
            settings[f"traffic.sfu.{sfu}.class.{ac}.rate.mbps"] = str(rate)
            settings[f"traffic.sfu.{sfu}.class.{ac}.packet.bytes"] = "1500"
    for sta in range(1, 5):
        settings[f"sta.{sta}.sfu.id"] = str(1 + (sta-1)//2)
        settings[f"traffic.sta.{sta}.seed"] = str(seed + 907 * sta)
        for ac, rate in zip(ACS, (0.2, 0.3, 1.1, 0.4)):
            settings[f"traffic.sta.{sta}.class.{ac}.rate.mbps"] = str(rate)
            settings[f"traffic.sta.{sta}.class.{ac}.packet.bytes"] = "1500"
    for ac, deadline in DEADLINES_US.items():
        settings[f"wifi.ap.qos.{ac}.deadline.us"] = str(deadline)
    if scan:
        settings["wifi.channel.scan.schedule"] = ";".join(
            f"{sfu},{sub},{start+sub*80},50,4000"
            for start, sfu, sub in product((2200, 10200, 18200, 26200, 34200, 42200, 50200, 58200),
                                          (1, 2), (1, 2, 3)))
        settings["wifi.channel.scan.power.w"] = "0.5"
    return settings


def cases():
    for load, profile, mode, seed in product(LOADS, PROFILES, MODES, SEEDS):
        yield (load, profile, mode, seed, "timely", False, "main")
    for mode, seed, feedback in product(MODES, SEEDS, ("delayed", "expired", "lost")):
        yield ("mid", "secondary", mode, seed, feedback, False, "feedback")
    for mode, seed in product(MODES, SEEDS):
        yield ("mid", "secondary", mode, seed, "timely", True, "scan")


def events(rows, name):
    return [row for row in rows if row.get("event") == name]


def actual_fail(name, tag, row):
    raise ValueError(f"{name}: failed {tag}: {row}")


def class_of(row):
    return row.get("ac", row.get("type"))


def packet_inputs(rows, settings):
    packets = {}
    for row in events(rows, "ARRIVAL"):
        if row["node"] not in ("MFU", "STA"):
            continue
        key = row["packet_id"]
        direction = row.get("direction", "DL")
        sta = row["destination_sta_id"] if direction == "DL" else row["source_sta_id"]
        value = (time_ns(row), int(row["bytes"]), class_of(row), direction, sta, settings[f"sta.{sta}.sfu.id"])
        require(key not in packets, f"duplicate generated packet {key}")
        packets[key] = value
    return packets


def endpoint_state(name, rows, packets):
    deliveries = {}
    drops = {}
    ordered = []
    for row in rows:
        event = row.get("event")
        if event not in ("DELIVERED", "WIFI_RETRY_LIMIT_DROP", "WIFI_COLLISION_DROP"):
            continue
        key, now = row["packet_id"], time_ns(row)
        require(key in packets and key not in deliveries and key not in drops and now >= packets[key][0],
                f"{name}: unknown or duplicate endpoint settlement {row}")
        if event == "DELIVERED":
            deliveries[key] = now
            ordered.append(key)
            if "e2e_delay_us" in row:
                require(ns(row["e2e_delay_us"]) == now - packets[key][0], f"{name}: UL endpoint latency mismatch")
        else:
            drops[key] = now
    require(len(deliveries) + len(drops) == len(packets), f"{name}: unfinished packets at natural drain")
    return deliveries, drops, ordered


def interference_sources(settings):
    sources = defaultdict(list)
    for row in settings[INTERFERENCE + "timeline"].split(";"):
        if not row:
            continue
        sfu, band, start, end, ap, sta = row.split(",")
        sources[sfu, int(band)].append((ns(start), ns(end), float(ap), float(sta)))
    return sources


def effective_sinr(sources, sfu, band, now):
    ratio = sum(10 ** ((sta + 95) / 10) for start, end, _, sta in sources[sfu, band]
                if start <= now < end)
    return 24 - 10 * log10(1 + ratio)


def ap_busy(sources, sfu, band, now):
    return sum(10 ** ((ap + 82) / 10) for start, end, ap, _ in sources[sfu, band]
               if start <= now < end) >= 1


def verify_per(name, rows, settings, sources):
    curve = [(float(x.split(",")[1]), float(x.split(",")[2])) for x in settings[CURVE].split(";")]
    busy_per_sfu = defaultdict(list)
    for row in events(rows, "WIFI_EXTERNAL_CCA"):
        sfu, now = row["sfu_id"], time_ns(row)
        mask = sum(1 << band for band in range(4) if ap_busy(sources,sfu,band,now))
        access = 1 if settings[f"sfu.{sfu}.wifi.ap.mode"] == "LCM" else 15
        require(int(row["observed_mask"]) == mask and int(row["access_mask"]) == access
                and (row["busy"] == "true") == bool(mask & access), f"{name}: incorrect external CCA mask")
        busy_per_sfu[sfu].append((now, row["busy"] == "true"))
    cca_times = {sfu: [entry[0] for entry in values] for sfu, values in busy_per_sfu.items()}
    attempts = events(rows, DECODE)
    for row in events(rows, "WIFI_MAC_ATTEMPT"):
        sfu, now = row["sfu_id"], time_ns(row)
        latest = bisect_right(cca_times.get(sfu, ()), now) - 1
        if latest >= 0:
            require(not busy_per_sfu[sfu][latest][1], f"{name}: started a PPDU while local external CCA busy")
    for row in attempts:
        sfu, now = row["sfu_id"], int(row["ppdu_start_ns"])
        if row["outcome"] == "MAC_COLLISION":
            require(row["per"] == row["draw"] == "-", f"{name}: collision sampled channel PER")
            continue
        expected_count = 1 if settings[f"sfu.{sfu}.wifi.ap.mode"] == "LCM" else 4
        occupied = tuple(map(int, row["subchannels"].split(",")))
        require(occupied == tuple(range(expected_count)), f"{name}: incorrect occupied prefix")
        sinrs = [effective_sinr(sources, sfu, band, now) for band in occupied]
        given = [float(value) for value in row["subchannel_sinr_db"].split(",")]
        require(len(given) == len(sinrs) and all(isclose(x, y, abs_tol=1e-9) for x, y in zip(given, sinrs)),
                f"{name}: incorrect effective SINR {given}, expected {sinrs}")
        probability = frequency_per(curve, sinrs, [1 / expected_count] * expected_count,
                                    int(row["mpdu_bytes"]), int(settings[REF_BYTES]))
        draw = expected_draw(int(settings[SEED]), int(sfu), int(row["sta_id"]),
                             int(row["packet_id"]), int(row["attempt"]))
        require(isclose(float(row["per"]), probability, abs_tol=1e-10) and float(row["draw"]) == draw
                and row["outcome"] == ("SINR_ERROR" if draw < probability else "DECODED"),
                f"{name}: incorrect frequency PER or RNG")
    return attempts


def verify_responses(name, rows, decodes):
    frame_groups = defaultdict(list)
    for row in decodes:
        frame_groups[row["sfu_id"], row["head_id"], int(row["ppdu_start_ns"])].append(row)
    aggregation = {(row["sfu_id"], row["packet_id"], time_ns(row)): row
                   for row in events(rows, "WIFI_AMPDU_DATA_START") if row["direction"] == "DL"}
    single_ack = {(row["sfu_id"], row["packet_id"], time_ns(row))
                  for row in events(rows, "WIFI_ACK_END") if row.get("direction", "DL") == "DL"}
    block_ack = {(row["sfu_id"], row["packet_id"], time_ns(row)): int(row["bitmap"], 16)
                 for row in events(rows, "WIFI_AMPDU_BA_END") if row["direction"] == "DL"}
    timeout = {(row["sfu_id"], row["packet_id"], time_ns(row))
               for row in events(rows, "WIFI_ACK_TIMEOUT") if row["direction"] == "DL"}
    for key, members in frame_groups.items():
        sfu, head, start = key
        end = time_ns(members[0])
        require(all(time_ns(member) == end for member in members), f"{name}: split aggregate completion")
        bitmap = sum((1 << i) for i, member in enumerate(members) if member["outcome"] == "DECODED")
        if len(members) > 1:
            frame = aggregation[key]
            require(end - start == int(frame["ppdu_ns"])
                    and frame["packet_ids"].split(",") == [m["packet_id"] for m in members],
                    f"{name}: decode order/timing mismatch in A-MPDU")
            response = sfu, head, end + int(frame["sifs_ns"]) + int(frame["ba_ns"])
            require(block_ack.get(response, 0) == bitmap, f"{name}: incorrect Block ACK bitmap")
        else:
            require(((sfu, head, end + 44_000) in single_ack) == bool(bitmap), f"{name}: incorrect ACK")
        require(((sfu, head, end + 60_000) in timeout) == (bitmap == 0), f"{name}: incorrect timeout")


def verify_queue(name, rows):
    queue = events(rows, QUEUE_EVENT)
    check_protocol_events(name, rows, queue, events(rows, PPDU_EVENT))
    state = defaultdict(WindowLedger)
    for row in queue:
        state[row["sfu_id"], row["direction"], row["ac"]].apply(name, row)
    require(all(not ledger.pending and not ledger.inflight for ledger in state.values()),
            f"{name}: C1 left pending/in-flight identities after drain")
    return queue


def quantile(values, q):
    return sorted(values)[ceil(q * len(values)) - 1] if values else None


def metrics_at(keys, now, deadline, packets, deliveries, drops, order):
    keys = {key for key in keys if packets[key][0] <= now}
    done = [key for key in order if key in keys and deliveries[key] <= now]
    delays = [deliveries[key] - packets[key][0] for key in done]
    dropped = {key for key in keys if drops.get(key, now+1) <= now}
    pending = keys.difference(done, dropped)
    on_time = sum(delay <= deadline for delay in delays)
    overdue = sum(now - packets[key][0] >= deadline for key in pending)
    violations = len(done) - on_time + len(dropped) + overdue
    decided = len(done) + len(dropped) + overdue
    by_sfu = defaultdict(list)
    for key, delay in zip(done, delays):
        by_sfu[packets[key][5]].append(delay)
    differences = [abs(y - x) for series in by_sfu.values() for x, y in zip(series, series[1:])]
    return {
        "arrived": len(keys), "delivered": len(done), "dropped": len(dropped), "incomplete": len(pending),
        "overdue": overdue, "avg_ns": mean(delays) if delays else None, "p95_ns": quantile(delays, .95),
        "p99_ns": quantile(delays, .99), "jitter_ns": mean(differences) if differences else 0,
        "on_time": on_time, "violations": violations,
        "on_time_ratio": on_time / decided if decided else None, "violation_ratio": violations / decided if decided else None,
        "drop_ratio": len(dropped) / len(keys) if keys else None,
        "offered_mbps": sum(packets[key][1] * 8000 / now for key in keys),
        "goodput_mbps": sum(packets[key][1] * 8000 / now for key in done),
    }


def phy_power(name, rows):
    spans = activity_intervals(rows, HORIZON_NS)
    overhead = {row["sfu_id"]: row for row in rows if row.get("summary") == "WIFI_CHANNEL_OVERHEAD"}
    result = {}
    for activity in (row for row in rows if row.get("summary") == "WIFI_AP_ACTIVITY"):
        sfu = activity["sfu_id"]
        summary = next(row for row in rows if row.get("summary") == "WIFI_AP_ENERGY" and row["sfu_id"] == sfu)
        power_summary = next((row for row in rows if row.get("summary") == "WIFI_AP_ENERGY_MODE"
                             and row["sfu_id"] == sfu and row["ap_mode"] == activity["ap_mode"]), summary)
        require(int(activity.get("switch_ns","0")) == 0, f"{name}: Always-mode trial changed mode")
        durations = overlaps(spans.get(sfu, [(0,HORIZON_NS,"IDLE")]), 0, HORIZON_NS)
        require(sum(durations.values()) == HORIZON_NS, f"{name}: B2 state time not conserved")
        for state in ("TX", "RX", "IDLE"):
            require(durations[state] == int(activity[f"{state.lower()}_ns"]), f"{name}: B2 state mismatch")
        power = {state: Decimal(power_summary[f"{state.lower()}_power_w"]) for state in ("TX", "RX", "IDLE")}
        integral = sum(power[state] * durations[state] / 1000 for state in durations)
        require(integral == Decimal(summary["total_energy_uj"]), f"{name}: B3 state integral mismatch")
        extra = overhead.get(sfu, {})
        scan = Decimal(extra.get("scan_energy_uj", "0"))
        scan_events = sum(Decimal(row.get("extra_energy_uj", "0")) for row in rows
                          if row.get("event") == "WIFI_CHANNEL_SCAN" and row.get("sfu_id") == sfu
                          and row.get("phase") == "END")
        require(scan == scan_events, f"{name}: scan energy does not reconcile")
        feedback_intervals = [(time_ns(row), time_ns(row) + int(row["airtime_ns"])) for row in rows
                              if row.get("event") == "WIFI_CHANNEL_FEEDBACK" and row["sfu_id"] == sfu
                              and row["phase"] == "TX_START"]
        feedback_full = sum(end - begin for begin, end in feedback_intervals)
        feedback_clipped = sum(max(0,min(end,HORIZON_NS)-begin) for begin, end in feedback_intervals)
        require(feedback_full == int(extra.get("feedback_airtime_ns", "0"))
                and Decimal(extra.get("feedback_rx_energy_uj", "0")) == feedback_clipped * power["RX"] / 1000
                and Decimal(extra.get("feedback_extra_energy_uj", "0")) ==
                feedback_clipped * (power["RX"] - power["IDLE"]) / 1000,
                f"{name}: feedback airtime or RX energy mismatch")
        for begin, end in feedback_intervals:
            end = min(end, HORIZON_NS)
            if begin < end:
                require(overlaps(spans[sfu], begin, end)["RX"] == end - begin,
                        f"{name}: feedback airtime missing from B2 RX")
        result[sfu] = (durations, power, integral, scan, extra)
    require(len(result) == 2, f"{name}: expected two energy summaries")
    return spans, result


def compare_forecasts(name, rows, packets, deliveries, spans, energy, mode):
    output = []
    by_sfu = defaultdict(list)
    for packet, now in deliveries.items():
        if packets[packet][3] == "DL":
            by_sfu[packets[packet][5]].append((now, packets[packet][1]))
    for row in events(rows, QUEUE_EVENT):
        if row["action"] == "UL_RECEIVED":
            # C3 observes the local wireless receiver, before any uplink PON serialization.
            by_sfu[row["sfu_id"]].append((time_ns(row), int(row["bytes"])))
    feasibility = defaultdict(dict)
    for row in events(rows, FEASIBILITY):
        key = row["t_ns"], row["sfu_id"], row["mode"]
        feasibility[key][row["ac"]] = row
    for row in events(rows, FORECAST):
        begin, end = time_ns(row), int(row["window_end_ns"])
        sfu, candidate = row["sfu_id"], row["mode"]
        if candidate == mode:
            require(int(row["switch_ns"]) == 0 and float(row["switch_uj"]) == 0,
                    f"{name}: stay-in-mode forecast incurred a switch")
        else:
            delay, energy_uj = (40_000,400) if mode == "LCM" else (25_000,200)
            require(int(row["switch_ns"]) == delay and float(row["switch_uj"]) == energy_uj,
                    f"{name}: counterfactual switch profile mismatch")
        values = dict(scenario=name, **row)
        assessment = feasibility[row["t_ns"], sfu, candidate]
        require(set(assessment) == set(ACS), f"{name}: missing per-AC candidate QoS assessment")
        for ac, detail in assessment.items():
            values[ac + "_feasibility"] = detail["status"]
            values[ac + "_predicted_e2e_ns"] = detail["e2e_ns"]
            values[ac + "_predicted_success_probability"] = detail["success_probability"]
        reason = 'different_mode' if candidate != mode else 'partial_window' if end > HORIZON_NS else 'matched'
        values["verification"] = reason
        if reason == "matched":
            actual = sum(size * 8000 / (end - begin) for at, size in by_sfu[sfu] if begin < at <= end)
            states = overlaps(spans.get(sfu, [(0,HORIZON_NS,"IDLE")]), begin, end)
            power = energy[sfu][1]
            actual_uj = float(sum(power[state] * states[state] / 1000 for state in states))
            values.update(actual_success_mbps=actual, actual_energy_uj=actual_uj)
            for prediction, measured, label in (
                ("success_mbps", actual, "goodput"), ("energy_uj", actual_uj, "energy"),
            ):
                predicted = float(row[prediction])
                values[f"{label}_error"] = predicted - measured
                if measured:
                    values[f"{label}_absolute_percentage_error"] = 100 * abs(predicted - measured) / measured
        output.append(values)
    require(bool(output), f"{name}: missing C3 predictions")
    return output


def verify_qos(name, rows, packets, deliveries, drops, order):
    snapshots = [row for row in rows if row.get("event") == QUALITY and time_ns(row) == HORIZON_NS]
    require(bool(snapshots), f"{name}: missing horizon C4 QoS summaries")
    for row in snapshots:
        ac = class_of(row)
        keys = [key for key, data in packets.items() if data[2] == ac and data[3] == row["direction"]
                and data[5] == row["sfu_id"]]
        actual = metrics_at(keys, HORIZON_NS, ns(DEADLINES_US[ac]), packets, deliveries, drops, order)
        for key in ("arrived", "delivered", "dropped", "incomplete", "overdue", "on_time", "violations",
                    "avg_ns", "p95_ns", "p99_ns", "jitter_ns"):
            expected = actual[key]
            if expected is None:
                continue
            if not isclose(float(row[key]), expected, abs_tol=1, rel_tol=1e-9):
                actual_fail(name, f"QoS {key} expected {expected}", row)


def verify_feedback(name, rows, settings, profile, scan, sources):
    phases = Counter()
    indicated = {}
    visible = defaultdict(list)
    for row in events(rows, "WIFI_CHANNEL_FEEDBACK"):
        phase = row["phase"]
        phases[phase] += 1
        measured, arrival, expiry, now = (int(row[field]) for field in ("measured_ns", "arrival_ns", "expires_ns", "t_ns"))
        key = row["report_id"]
        if phase == "MEASURED":
            require(now == measured and arrival >= measured and expiry >= measured, f"{name}: feedback clocks reversed")
            indicated[key] = row.get("sinr_db")
            expected = effective_sinr(sources, row["sfu_id"], int(row["subchannel"]), measured)
            require(isclose(float(row["sinr_db"]), expected, abs_tol=1e-9), f"{name}: wrong measurement-time CSI")
        if phase in ("ACCEPTED", "EXPIRED", "STALE"):
            require(now == arrival and indicated.get(key) == row.get("sinr_db"), f"{name}: feedback arrival/sample mismatch")
        if phase == "ACCEPTED":
            require(now < expiry, f"{name}: expired feedback was accepted")
            visible[row["sfu_id"], row["sta_id"], row["subchannel"]].append(row)
    for row in events(rows, "WIFI_CHANNEL_OBSERVATION"):
        now, sfu, band = time_ns(row), row["sfu_id"], row["subchannel"]
        received = [report for report in visible[sfu, row["sta_id"], band] if int(report["arrival_ns"]) <= now]
        latest = received[-1] if received else None
        if settings[f"sfu.{sfu}.wifi.ap.mode"] == "LCM" and band != "0":
            expected = "MODE_LIMIT"
        else:
            expected = "UNKNOWN" if latest is None else "VISIBLE" if int(latest["expires_ns"]) > now else "EXPIRED"
        require(row["status"] == expected, f"{name}: invalid causal CSI visibility ({row})")
        if expected == "VISIBLE":
            require(all(row[field] == latest[field] for field in ("measured_ns", "arrival_ns", "expires_ns", "sinr_db")),
                    f"{name}: reported CSI differs from last actually received sample")
    scans = [row for row in events(rows, "WIFI_CHANNEL_SCAN") if row["phase"] == "END"]
    for row in scans:
        require(int(row["started_ns"]) <= int(row["ended_ns"]) == time_ns(row)
                and 0 <= float(row["busy_fraction"]) <= 1 and float(row["extra_energy_uj"]) >= 0,
                f"{name}: invalid scan result")
        require("sinr_db" not in row and "sta_id" not in row, f"{name}: auxiliary scan exposed STA CSI")
        begin, end = int(row["started_ns"]), int(row["ended_ns"])
        sfu, band = row["sfu_id"], int(row["subchannel"])
        edges = {begin, end}
        for start, stop, _, _ in sources[sfu, band]:
            edges.update(point for point in (start, stop) if begin < point < end)
        ordered = sorted(edges)
        busy = sum(y - x for x, y in zip(ordered,ordered[1:]) if ap_busy(sources,sfu,band,x))
        require(busy == int(row["busy_ns"]), f"{name}: scan busy fraction mismatch")
    if scan and settings["sfu.1.wifi.ap.mode"] == "LCM":
        require(bool(scans), f"{name}: enabled LCM scan never ran")
    if not scan or settings["sfu.1.wifi.ap.mode"] == "HCM":
        require(not scans, f"{name}: unexpected physical scan")
    if profile == "lost":
        require(phases["LOST"] > 0 and phases["ACCEPTED"] == 0, f"{name}: scripted losses not observed")
    if profile == "expired":
        require(phases["EXPIRED"] > 0 and phases["ACCEPTED"] == 0, f"{name}: expired feedback not observed")
    return phases


def combined_rows(name, labels, packets, deliveries, drops, order, queue, decodes, energy, phases):
    good_dl_cutoff = [key for key, at in deliveries.items() if at <= HORIZON_NS and packets[key][3] == "DL"]
    radio = sum(values[2] for values in energy.values())
    scan = sum(values[3] for values in energy.values())
    total_power = float((radio + scan) * 1000 / HORIZON_NS)
    offered_all = sum(packets[key][1] * 8000 / HORIZON_NS for key in packets if packets[key][3] == "DL")
    decisions = [row for row in decodes if int(row["ppdu_start_ns"]) <= HORIZON_NS]
    probabilities = [float(row["per"]) for row in decisions if row["per"] != "-"]
    waiting = defaultdict(set)
    inflight = defaultdict(set)
    for row in queue:
        if row["direction"] != "DL" or time_ns(row) > HORIZON_NS:
            continue
        ac, key, action = row["ac"], row["packet_id"], row["action"]
        if action in ("ENQUEUED", "REQUEUED"):
            waiting[ac].add(key)
        if action in ("TX_START", "RETRANSMIT_START"):
            waiting[ac].remove(key)
            inflight[ac].add(key)
        if action in ("CONFIRMED", "DROPPED", "REQUEUED"):
            inflight[ac].remove(key)
    common = dict(scenario=name, **labels, cutoff_ns=HORIZON_NS)
    output = []
    for direction in ("DL", "UL"):
        for ac in ACS:
            keys = [key for key in packets if packets[key][2:4] == (ac, direction)]
            stats = metrics_at(keys, HORIZON_NS, ns(DEADLINES_US[ac]), packets, deliveries, drops, order)
            output.append(dict(**common, ac=ac, direction=direction, **stats,
                               queue_pending=len(waiting[ac]) if direction == "DL" else None,
                               queue_inflight=len(inflight[ac]) if direction == "DL" else None))
    all_dl = [row for row in output if row["direction"] == "DL"]
    decided = sum(row["delivered"] + row["dropped"] + row["overdue"] for row in all_dl)
    delays = [deliveries[key] - packets[key][0] for key in order if key in set(good_dl_cutoff)]
    output.append(dict(**common, ac="ALL", direction="DL",
                       arrived=sum(row["arrived"] for row in all_dl), delivered=len(good_dl_cutoff),
                       dropped=sum(row["dropped"] for row in all_dl), incomplete=sum(row["incomplete"] for row in all_dl),
                       overdue=sum(row["overdue"] for row in all_dl), p95_ns=quantile(delays,.95), p99_ns=quantile(delays,.99),
                       on_time=sum(row["on_time"] for row in all_dl), violations=sum(row["violations"] for row in all_dl),
                       violation_ratio=sum(row["violations"] for row in all_dl) / decided if decided else None,
                       offered_mbps=offered_all, goodput_mbps=sum(row["goodput_mbps"] for row in all_dl),
                       queue_pending=sum(map(len, waiting.values())), queue_inflight=sum(map(len, inflight.values())),
                       mean_per=mean(probabilities) if probabilities else None, mpdu_attempts=len(decisions),
                       sinr_errors=sum(row["outcome"]=="SINR_ERROR" for row in decisions),
                       mac_collisions=sum(row["outcome"]=="MAC_COLLISION" for row in decisions),
                       radio_energy_uj=radio, scan_energy_uj=scan, mean_network_power_w=total_power,
                       energy_uj_per_success_bit=float(radio + scan) / sum(packets[key][1]*8 for key in good_dl_cutoff)
                       if good_dl_cutoff else None, feedback_accepted=phases["ACCEPTED"], feedback_expired=phases["EXPIRED"],
                       feedback_lost=phases["LOST"], feedback_busy_drop=phases["CCA_BUSY_DROP"]))
    return output


def run_case(jar, directory, case, retain=False):
    load, interference, mode, seed, feedback, scan, suite = case
    name = '-'.join((load, interference, mode.lower(), str(seed), feedback, "scan" if scan else "noscan"))
    settings = config(mode, load, interference, seed, feedback, scan)
    path = directory / f"{name}.properties"
    write_properties(path, settings)
    raw = simulate(jar, path)
    rows = parse_trace(raw)
    packets = packet_inputs(rows, settings)
    deliveries, drops, order = endpoint_state(name, rows, packets)
    sources = interference_sources(settings)
    decodes = verify_per(name, rows, settings, sources)
    verify_responses(name, rows, decodes)
    queue = verify_queue(name, rows)
    phases = verify_feedback(name, rows, settings, feedback, scan, sources)
    spans, energy = phy_power(name, rows)
    verify_qos(name, rows, packets, deliveries, drops, order)
    predictions = compare_forecasts(name, rows, packets, deliveries, spans, energy, mode)
    labels = dict(load=load, interference=interference, ap_mode=mode, seed=seed, feedback_profile=feedback,
                  scan_enabled=str(scan).lower(), suite=suite)
    summary = combined_rows(name, labels, packets, deliveries, drops, order, queue, decodes, energy, phases)
    # Preserve all control traces for one seed and a representative PPDU/witness per attempted AC.
    selected = []
    unique = set()
    if seed == SEEDS[0] or retain:
        for row in rows:
            category = row.get("event")
            if category not in CHANNEL_EVENTS and row.get("summary") != "WIFI_CHANNEL_OVERHEAD":
                continue
            if category in (DECODE, "WIFI_CHANNEL_OBSERVATION", "WIFI_CHANNEL_PASSIVE") and not retain:
                key = category, row.get("sfu_id"), row.get("sta_id"), row.get("subchannels", row.get("subchannel")), row.get("outcome"), row.get("status")
                if key in unique:
                    continue
                unique.add(key)
            selected.append(dict(scenario=name, **labels, **row))
    return name, case, packets, summary, predictions, selected, raw if retain else None


def verify_switch_off(jar, directory):
    baseline = flat_configuration("0,0,0,0", (("0", "10"), ("120", "30")), aggregate=True, seed=101)
    switches_off = {**baseline, INTERFERENCE + "enabled": "false",
                    "wifi.phy.per.frequency.model": "flat", OBSERVER: "false",
                    "wifi.channel.passive.enabled": "false", "wifi.ap.prediction.enabled": "false"}
    paths = [directory / f"{name}.properties" for name in ("c2b1-baseline", "c-features-switch-off")]
    for path, settings in zip(paths, (baseline, switches_off)):
        write_properties(path, settings)
    require(simulate(jar, paths[0]) == simulate(jar, paths[1]),
            "C features disabled did not preserve C2b-1 trace verbatim")


def verify(jar, output, workers, smoke):
    directory = output / "configs"
    directory.mkdir(parents=True, exist_ok=True)
    verify_switch_off(jar, directory)
    configured_cases = list(cases())
    if smoke:
        configured_cases = [("mid", "secondary", mode, SEEDS[0], "timely", True, "scan") for mode in MODES]
    combined, forecasts, channel_events = [], [], []
    workloads = {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for name, case, input_map, qos, prediction, channel, _ in pool.map(
                lambda case: run_case(jar, directory, case), configured_cases):
            load, interference, mode, seed, feedback, scan, suite = case
            match = load, seed
            if match in workloads:
                require(input_map == workloads[match], f"{name}: workload changed across modes or channel profiles")
            else:
                workloads[match] = input_map
            combined.extend(qos)
            forecasts.extend(prediction)
            channel_events.extend(channel)
            print(name, f"{len(input_map)} packets independently audited", flush=True)
    csv_rows(output / "channel-events.csv", channel_events)
    csv_rows(output / "prediction-vs-actual.csv", forecasts)
    csv_rows(output / "qos-energy.csv", combined)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--output", type=Path, default=RESULT)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    verify(args.jar.resolve(), args.output, args.workers, args.smoke)


if __name__ == "__main__":
    main()
