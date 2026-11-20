"""Extract and reconcile common-window metrics from the existing Java traces."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal
from statistics import mean

from run_pon_dba_execution_test import events, parse
from run_pon_frame_test import require
from run_pon_giant_test import read_execution, verify_report_ledger


KEY_COLUMNS = ("policy", "load", "seed", "variant")
WINDOW_COLUMNS = ("traffic_end_ns", "decision_end_ns", "observation_end_ns", "simulation_end_ns")
COUNT_KINDS = (
    "generated", "delivered", "dropped", "pending", "pending_wireless", "pending_optical",
    "pending_in_flight", "late_delivered", "late_dropped", "final_pending",
)
METRIC_COLUMNS = KEY_COLUMNS + (
    "scope", "allocation_id", "sfu_id", "tcont_type",
) + WINDOW_COLUMNS + tuple(
    f"{kind}_{unit}" for kind in COUNT_KINDS for unit in ("packets", "bytes")
) + (
    "offered_mbps", "throughput_mbps", "avg_e2e_delay_ns", "p95_e2e_delay_ns",
    "avg_pon_wait_ns", "p95_pon_wait_ns", "avg_optical_fifo_wait_ns",
    "avg_queue_packets", "avg_queue_bytes", "peak_queue_packets", "peak_queue_bytes",
    "data_busy_ns", "data_reserved_ns", "unused_reservation_ns", "report_busy_ns",
    "resource_utilization", "data_reservation_fraction", "budget_grants", "budget_bytes",
    "used_budget_bytes", "unused_budget_bytes", "unused_budget_fraction",
    "report_samples", "report_known_samples", "report_unknown_samples", "report_known_fraction",
    "avg_report_age_ns", "p95_report_age_ns",
)
PACKET_DELAY_COLUMNS = KEY_COLUMNS + (
    "allocation_id", "sfu_id", "tcont_type", "packet_id", "source_sta_id", "access_category", "packet_bytes",
    "generation_ns", "pon_enqueue_ns", "pon_load_ns", "pon_tx_start_ns", "pon_tx_end_ns", "delivery_ns",
    "wireless_path_ns", "optical_fifo_wait_ns", "pon_wait_ns", "pon_serialization_ns", "pon_propagation_ns",
    "e2e_delay_ns", "in_observation", "grant_id",
)


@dataclass
class RunMetrics:
    metrics: list[dict]
    packet_delays: list[dict]
    workload: tuple[tuple[int, int, int, str, int], ...]


def _ns(microseconds: str) -> int:
    return int(Decimal(microseconds) * 1000)


def _index(rows: list[dict], key: str, label: str) -> dict:
    indexed = {row[key]: row for row in rows}
    require(len(indexed) == len(rows), f"Duplicate {label}")
    return indexed


def _summary(trace: list[dict], label: str) -> dict:
    rows = [row for row in trace if row.get("summary") == label]
    require(len(rows) == 1, f"Expected one {label} summary")
    return rows[0]


def _overlap(start_ns: int, end_ns: int, observation_end_ns: int) -> int:
    return max(0, min(end_ns, observation_end_ns) - max(0, start_ns))


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _average(values: list[int]) -> float | None:
    return mean(values) if values else None


def _p95(values: list[int]) -> int | None:
    # Nearest-rank percentile, including all tied measurements.
    return sorted(values)[(95 * len(values) + 99) // 100 - 1] if values else None


def _profiles(settings: dict[str, str]) -> tuple[dict, dict]:
    profiles = {}
    by_route = {}
    for allocation in settings["fiber.uplink.tcont.alloc.ids"].split(","):
        allocation = allocation.strip()
        prefix = f"fiber.uplink.tcont.alloc.{allocation}."
        owner = settings[prefix + "sfu.id"]
        profiles[allocation] = (owner, settings[prefix + "type"])
        for category in settings[prefix + "classes"].split(","):
            by_route[owner, category.strip()] = allocation
    return profiles, by_route


def _loaded_packets(trace: list[dict], starts: dict) -> dict:
    loaded = {}
    starts_by_grant = defaultdict(list)
    for row in starts.values():
        starts_by_grant[row["grant_id"]].append(row["packet_id"])
    for fill in events(trace, "PON_DBA_FILL"):
        ids = fill["selected_packet_ids"]
        selected = ids.split(";") if ids != "-" else []
        require(selected == starts_by_grant[fill["grant_id"]], "Loaded packet IDs differ from DATA start sequence")
        for packet_id in selected:
            require(packet_id not in loaded, "Packet loaded in more than one grant")
            loaded[packet_id] = fill
    require(loaded.keys() == starts.keys(), "Data started without a matching FIFO load")
    return loaded


def _queue_stats(ids: set, arrivals: dict, queued: dict, loaded: dict, cutoff: int) -> dict:
    # A report snapshot is an observation, not a queue change. FILL removes the complete selection atomically.
    changes = defaultdict(lambda: [0, 0])
    for packet_id in ids & queued.keys():
        start = queued[packet_id]["_time_ns"]
        end = loaded.get(packet_id, {}).get("_time_ns", cutoff)
        duration = _overlap(start, end, cutoff)
        if duration:
            size = int(arrivals[packet_id]["bytes"])
            changes[start][0] += 1
            changes[start][1] += size
            changes[start + duration][0] -= 1
            changes[start + duration][1] -= size
    packets = byte_count = area_packets = area_bytes = peak_packets = peak_bytes = before = 0
    # Equal timestamps are one instant. A queue state lasting zero ns contributes neither area nor peak.
    for time, (delta_packets, delta_bytes) in sorted(changes.items()):
        area_packets += packets * (time - before)
        area_bytes += byte_count * (time - before)
        packets += delta_packets
        byte_count += delta_bytes
        peak_packets = max(peak_packets, packets)
        peak_bytes = max(peak_bytes, byte_count)
        before = time
    return dict(avg_queue_packets=area_packets / cutoff, avg_queue_bytes=area_bytes / cutoff,
                peak_queue_packets=peak_packets, peak_queue_bytes=peak_bytes)


def _packet_counts(row: dict, kind: str, ids: set, arrivals: dict) -> None:
    row[f"{kind}_packets"] = len(ids)
    row[f"{kind}_bytes"] = sum(int(arrivals[packet_id]["bytes"]) for packet_id in ids)


def _resource_stats(budgets: list[dict], deliveries: list[dict], report_ns: int, cutoff: int) -> dict:
    reserved = sum(_overlap(grant["slot_start_ns"], grant["slot_end_ns"], cutoff) for grant in budgets)
    data = sum(_overlap(packet["pon_tx_start_ns"], packet["pon_tx_end_ns"], cutoff) for packet in deliveries)
    require(0 <= data <= reserved and reserved + report_ns <= cutoff, "Observed PON time budget does not reconcile")
    # Count whole-grant bytes only for slots that end by cutoff, even when the settlement is received later.
    completed = [grant for grant in budgets if grant["slot_end_ns"] <= cutoff]
    grant_bytes = sum(grant["budget_bytes"] for grant in completed)
    used_bytes = sum(grant["actual_bytes"] for grant in completed)
    return dict(data_busy_ns=data, data_reserved_ns=reserved, unused_reservation_ns=reserved - data,
                report_busy_ns=report_ns, resource_utilization=(data + report_ns) / cutoff,
                data_reservation_fraction=reserved / cutoff, budget_grants=len(completed),
                budget_bytes=grant_bytes, used_budget_bytes=used_bytes, unused_budget_bytes=grant_bytes - used_bytes,
                unused_budget_fraction=_ratio(grant_bytes - used_bytes, grant_bytes))


def _age_stats(states: list[dict]) -> dict:
    ages = [int(row["report_age_ns"]) for row in states if row["state"] == "KNOWN"]
    return dict(report_samples=len(states), report_known_samples=len(ages),
                report_unknown_samples=len(states) - len(ages), report_known_fraction=_ratio(len(ages), len(states)),
                avg_report_age_ns=_average(ages), p95_report_age_ns=_p95(ages))


def analyze_run(log: str, settings: dict[str, str], load: str, seed: int, variant: str,
                traffic_end_ns: int, decision_end_ns: int, observation_end_ns: int,
                *, include_undelivered: bool = False) -> RunMetrics:
    """Validate and summarize one stdout trace. Use [0, observation_end_ns) for observations."""
    require(0 < traffic_end_ns <= observation_end_ns <= decision_end_ns, "Inconsistent experiment horizons")
    require(_ns(settings["fiber.uplink.poll.end.us"]) == decision_end_ns,
            "Logged simulation configuration and declared DBA stop differ")
    if settings["traffic.mode"] == "poisson":
        require(_ns(settings["traffic.duration.us"]) == traffic_end_ns,
                "Logged simulation configuration and declared traffic stop differ")
    trace = parse(log)
    identity = dict(policy=settings["fiber.uplink.dba.mode"], load=load, seed=seed, variant=variant)
    profiles, by_route = _profiles(settings)
    observed = observation_end_ns
    verify_report_ledger(trace)
    # This verifier already checks full-run grants, settlement, serialization, propagation, FIFO,
    # reservation overlap and Java DBA totals. Keep the performance layer independent of the policy.
    budgets = read_execution(str(identity), settings, trace)
    arrivals = _index([row for row in events(trace, "ARRIVAL") if row.get("node") == "STA"
                       and row.get("direction") == "UL"], "packet_id", "generated packet")
    queued = _index(events(trace, "TCONT_ENQUEUE"), "packet_id", "optical FIFO enqueue")
    drops = _index(events(trace, "WIFI_COLLISION_DROP") + events(trace, "WIFI_RETRY_LIMIT_DROP"),
                   "packet_id", "terminal Wi-Fi drop")
    starts = _index(events(trace, "PON_DBA_DATA_START"), "packet_id", "optical DATA start")
    ends = _index(events(trace, "PON_DBA_DATA_END"), "packet_id", "optical DATA end")
    delivered_rows = events(trace, "DELIVERED")
    delivered = _index(delivered_rows, "packet_id", "MFU delivery")
    loaded = _loaded_packets(trace, starts)
    require(delivered.keys() == ends.keys() == starts.keys(), "Delivery and DATA transmission disagree")
    require(queued.keys() | drops.keys() == arrivals.keys() and not queued.keys() & drops.keys(),
            "Generated packets do not reconcile with terminal Wi-Fi drops and optical enqueues")
    require(all(0 <= row["_time_ns"] < traffic_end_ns for row in arrivals.values()),
            "An input arrived outside the generation window")
    require(all(int(row["bwmap_tx_ns"]) < decision_end_ns for row in budgets),
            "DATA grant created at or after DBA stop")
    if traffic_totals := [row for row in trace if row.get("summary") == "TRAFFIC_UL_STA"]:
        require((sum(int(row["generated_packets"]) for row in traffic_totals),
                 sum(int(row["generated_bytes"]) for row in traffic_totals))
                == (len(arrivals), sum(int(row["bytes"]) for row in arrivals.values())),
                "Java generated traffic summary disagrees with input events")
    summary = _summary(trace, "UL_GLOBAL")
    require((int(summary["delivered_packets"]), int(summary["delivered_bytes"]))
            == (len(delivered), sum(int(row["bytes"]) for row in delivered.values())),
            "Java UL_GLOBAL summary disagrees with MFU deliveries")
    if any(row.get("summary") == "WIFI_COLLISION" for row in trace):
        collision = _summary(trace, "WIFI_COLLISION")
        require((int(collision["dropped_packets"]), int(collision["dropped_bytes"]))
                == (len(drops), sum(int(row["bytes"]) for row in drops.values())),
                "Java collision summary disagrees with terminal drop events")
    run_end = [row for row in trace if "simulation_end_us" in row]
    require(len(run_end) == 1, "Missing Java run-end timestamp")
    simulation_end_ns = _ns(run_end[0]["simulation_end_us"])

    packet_alloc = {}
    for packet_id, packet in arrivals.items():
        owner = settings.get(f'sta.{packet["source_sta_id"]}.sfu.id', "1")
        packet_alloc[packet_id] = by_route[owner, packet["type"]]
        if packet_id in queued:
            row = queued[packet_id]
            require((row["allocation_id"], row["sfu_id"], row["ac"], row["packet_bytes"])
                    == (packet_alloc[packet_id], owner, packet["type"], packet["bytes"])
                    and (packet["_time_ns"], packet["_index"]) <= (row["_time_ns"], row["_index"]),
                    "Optical queue event does not match input source, class or time")
        if packet_id in drops:
            row = drops[packet_id]
            require((row["sfu_id"], row["type"], row["bytes"], row["sta_id"], row["direction"])
                    == (owner, packet["type"], packet["bytes"], packet["source_sta_id"], "UL")
                    and packet["_time_ns"] <= row["_time_ns"], "Terminal drop does not match its input")

    packet_delays = []
    for row in delivered_rows:
        packet_id = row["packet_id"]
        packet, enq, fill, start, end = (table[packet_id] for table in (arrivals, queued, loaded, starts, ends))
        gen_ns, enq_ns, load_ns, start_ns, end_ns, delivery_ns = (
            event["_time_ns"] for event in (packet, enq, fill, start, end, row)
        )
        allocation = packet_alloc[packet_id]
        owner, tcont_type = profiles[allocation]
        require(gen_ns <= enq_ns <= load_ns <= start_ns < end_ns <= delivery_ns <= simulation_end_ns
                and row["node"] == "MFU" and row["direction"] == "UL"
                and (row["source_sta_id"], row["sfu_id"], row["type"], row["bytes"])
                == (packet["source_sta_id"], owner, packet["type"], packet["bytes"])
                and _ns(row["e2e_delay_us"]) == delivery_ns - gen_ns,
                "MFU delivery timestamp, delay or packet identity disagrees with its path")
        packet_delays.append(dict(identity, allocation_id=allocation, sfu_id=owner, tcont_type=tcont_type,
                                  packet_id=packet_id, source_sta_id=packet["source_sta_id"],
                                  access_category=packet["type"], packet_bytes=int(packet["bytes"]),
                                  generation_ns=gen_ns, pon_enqueue_ns=enq_ns, pon_load_ns=load_ns,
                                  pon_tx_start_ns=start_ns, pon_tx_end_ns=end_ns, delivery_ns=delivery_ns,
                                  wireless_path_ns=enq_ns - gen_ns, optical_fifo_wait_ns=load_ns - enq_ns,
                                  pon_wait_ns=start_ns - enq_ns, pon_serialization_ns=end_ns - start_ns,
                                  pon_propagation_ns=delivery_ns - end_ns, e2e_delay_ns=delivery_ns - gen_ns,
                                  in_observation=delivery_ns < observed, grant_id=start["grant_id"]))

    polls = _index(events(trace, "PON_POLL_TX_START"), "poll_id", "REPORT-only transmission")
    poll_ends = _index(events(trace, "PON_POLL_TX_END"), "poll_id", "REPORT-only completion")
    require(polls.keys() == poll_ends.keys(), "Missing REPORT-only transmission or completion")
    report_ns = full_report_ns = 0
    for poll_id, start in polls.items():
        end = poll_ends[poll_id]
        first_ns, last_ns = int(start["tx_start_ns"]), int(start["tx_end_ns"])
        require(first_ns == start["_time_ns"] < end["_time_ns"] == last_ns
                and int(start["bwmap_tx_ns"]) < decision_end_ns, "Invalid REPORT-only timing")
        report_ns += _overlap(first_ns, last_ns, observed)
        full_report_ns += last_ns - first_ns
    poll_summary = _summary(trace, "PON_POLLING")
    require((int(poll_summary["polls"]), int(poll_summary["report_busy_ns"])) == (len(polls), full_report_ns),
            "REPORT-only timings disagree with Java polling summary")

    states = [row for row in events(trace, "PON_DBA_OBJECT_STATE") if row["_time_ns"] < observed]
    require(all(row["allocation_id"] in profiles for row in states), "REPORT contains an unknown allocation")
    observed_delivered = {packet_id for packet_id, row in delivered.items() if row["_time_ns"] < observed}
    observed_drops = {packet_id for packet_id, row in drops.items() if row["_time_ns"] < observed}
    queued_by_cutoff = {packet_id for packet_id, row in queued.items() if row["_time_ns"] < observed}
    loaded_by_cutoff = {packet_id for packet_id, row in loaded.items() if row["_time_ns"] < observed}
    workload = tuple(sorted((row["_time_ns"], int(packet_id), int(row["source_sta_id"]), row["type"], int(row["bytes"]))
                            for packet_id, row in arrivals.items()))
    metrics = []
    for allocation in (*profiles, "TOTAL"):
        total = allocation == "TOTAL"
        owner, tcont_type = ("", "") if total else profiles[allocation]
        ids = arrivals.keys() if total else {packet_id for packet_id in arrivals if packet_alloc[packet_id] == allocation}
        available = set(ids)
        row = dict(identity, scope="total" if total else "tcont", allocation_id=allocation,
                   sfu_id=owner, tcont_type=tcont_type, traffic_end_ns=traffic_end_ns, decision_end_ns=decision_end_ns,
                   observation_end_ns=observed, simulation_end_ns=simulation_end_ns)
        completed = available & observed_delivered
        lost = available & observed_drops
        pending = available - completed - lost
        counts = (available, completed, lost, pending, pending - queued_by_cutoff,
                  pending & (queued_by_cutoff - loaded_by_cutoff), pending & loaded_by_cutoff,
                  pending & delivered.keys(), pending & drops.keys(), pending - delivered.keys() - drops.keys())
        require(sum(map(len, counts[4:7])) == len(pending) == sum(map(len, counts[7:10])),
                "Pending packet categories do not partition observations")
        for kind, packets in zip(COUNT_KINDS, counts):
            _packet_counts(row, kind, packets, arrivals)
        all_delays = [packet for packet in packet_delays if total or packet["allocation_id"] == allocation]
        delays = [packet for packet in all_delays if packet["in_observation"]]
        e2e = [packet["e2e_delay_ns"] for packet in delays]
        pon = [packet["pon_wait_ns"] for packet in delays]
        row.update(offered_mbps=row["generated_bytes"] * 8000 / traffic_end_ns,
                   throughput_mbps=row["delivered_bytes"] * 8000 / observed,
                   avg_e2e_delay_ns=_average(e2e), p95_e2e_delay_ns=_p95(e2e), avg_pon_wait_ns=_average(pon),
                   p95_pon_wait_ns=_p95(pon),
                   avg_optical_fifo_wait_ns=_average([packet["optical_fifo_wait_ns"] for packet in delays]))
        row.update(_queue_stats(available, arrivals, queued, loaded, observed))
        row.update(_resource_stats([grant for grant in budgets if total or grant["allocation_id"] == allocation],
                                   all_delays, report_ns if total else 0, observed))
        row.update(_age_stats([state for state in states if total or state["allocation_id"] == allocation]))
        metrics.append(row)
    aggregate = metrics[-1]
    for field in ("generated_packets", "generated_bytes", "delivered_packets", "delivered_bytes",
                  "dropped_packets", "dropped_bytes", "pending_packets", "pending_bytes",
                  "data_busy_ns", "data_reserved_ns", "budget_bytes", "unused_budget_bytes"):
        require(aggregate[field] == sum(row[field] for row in metrics[:-1]), f"T-CONT {field} does not sum to TOTAL")
    if include_undelivered:
        for row in packet_delays:
            row.update(status="delivered", drop_ns="")
        for packet_id in arrivals.keys() - delivered.keys():
            packet, enq, drop = arrivals[packet_id], queued.get(packet_id), drops.get(packet_id)
            allocation = packet_alloc[packet_id]
            sfu, kind = profiles[allocation]
            packet_delays.append(dict(
                identity, allocation_id=allocation, sfu_id=sfu, tcont_type=kind,
                packet_id=packet_id, source_sta_id=packet["source_sta_id"], access_category=packet["type"],
                packet_bytes=int(packet["bytes"]), generation_ns=packet["_time_ns"],
                pon_enqueue_ns=enq["_time_ns"] if enq else "", drop_ns=drop["_time_ns"] if drop else "",
                status="dropped" if drop else "pending", in_observation=False,
            ))
        packet_delays.sort(key=lambda row: int(row["packet_id"]))
    return RunMetrics(metrics, packet_delays, workload)
