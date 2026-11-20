"""Windowed DBA QoS metrics from Java enqueue, grant, load and outcome events."""

from __future__ import annotations

from bisect import bisect_left
from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal
from statistics import mean, stdev

from pon_dba_comparison_metrics import analyze_run
from pon_giant_completion_workload import COMPLETE, Experiment
from run_pon_dba_execution_test import events, parse
from run_pon_frame_test import require


KEY_COLUMNS = (
    "scenario_id", "policy", "load", "seed", "offered_mbps", "scope", "tcont_type",
    "allocation_id", "sfu_id", "observation_end_ns", "decision_end_ns",
)
LATENCY_COLUMNS = KEY_COLUMNS + (
    "fifo_sample_count", "fifo_mean_ns", "fifo_p95_ns", "fifo_p99_ns", "fifo_p999_ns", "fifo_jitter_sd_ns",
    "fifo_max_ns", "fifo_unloaded_at_cutoff_packets", "fifo_max_censored_age_ns", "fifo_load_coverage",
    "p95_expected_tail_samples", "p99_expected_tail_samples", "p999_expected_tail_samples",
    "p95_tail_support", "p99_tail_support", "p999_tail_support",
)
SLA_COLUMNS = KEY_COLUMNS + (
    "configured_fixed_si_frames", "configured_fixed_ab_bytes", "configured_assured_si_frames",
    "configured_assured_ab_bytes", "configured_surplus_si_frames", "configured_surplus_ab_bytes",
    "configured_sla_mbps", "configured_due_opportunities", "configured_due_bytes",
    "reference_qualified_opportunities", "reference_wanted_bytes",
    "reference_grant_covered_bytes", "reference_actual_covered_bytes", "reference_grant_coverage",
    "reference_actual_coverage", "reference_grant_violations", "reference_actual_violations",
    "fixed_due_bytes", "fixed_granted_bytes", "fixed_attributed_actual_bytes", "fixed_reservation_ratio",
    "fixed_carry_mbps", "assured_demand_qualified_bytes", "assured_granted_bytes",
    "assured_attributed_actual_bytes", "assured_reservation_ratio", "assured_actual_ratio",
    "assured_violations", "assured_actual_shortfalls", "assured_unknown_opportunities",
    "non_assured_granted_bytes", "non_assured_attributed_actual_bytes",
    "best_effort_granted_bytes", "best_effort_attributed_actual_bytes",
    "non_assured_share_of_surplus_granted", "best_effort_share_of_surplus_granted",
    "si_or_component_bound_violations", "priority_inversions",
    "grant_frames", "actual_service_frames", "max_grant_gap_frames", "max_actual_service_gap_frames",
    "max_known_backlog_no_service_frames", "jain_same_type_goodput", "same_type_peers",
    "actual_delivered_bytes", "actual_final_delivered_bytes", "actual_goodput_mbps",
)
LOSS_COLUMNS = KEY_COLUMNS + (
    "generated_packets", "generated_bytes", "optical_enqueued_packets", "optical_enqueued_bytes",
    "actual_dropped_packets", "actual_dropped_bytes", "actual_drop_fraction",
    "overflow_status", "overflow_dropped_packets", "overflow_drop_fraction",
    "peak_queue_packets", "peak_queue_bytes", "pending_at_cutoff_packets", "fifo_pending_at_cutoff_packets",
    "final_pending_packets", "final_pending_bytes", "final_pending_fraction",
    "delivered_at_cutoff_packets", "drain_delivered_packets", "final_delivered_packets", "simulation_end_ns",
)
PHASES = ("fixed", "assured", "non_assured", "best_effort")
PHASE_COUNT_FIELDS = (
    "fixed_due_bytes", "fixed_granted_bytes", "fixed_attributed_actual_bytes",
    "assured_demand_qualified_bytes", "assured_granted_bytes", "assured_attributed_actual_bytes",
    "assured_violations", "assured_actual_shortfalls", "assured_unknown_opportunities",
    "non_assured_granted_bytes", "non_assured_attributed_actual_bytes", "best_effort_granted_bytes",
    "best_effort_attributed_actual_bytes", "si_or_component_bound_violations", "priority_inversions",
)


@dataclass
class QosRun:
    latency_rows: list[dict]
    sla_rows: list[dict]
    loss_rows: list[dict]
    workload: tuple[tuple[int, int, int, str, int], ...]


def ratio(numerator: int | float, denominator: int | float) -> float | None:
    return numerator / denominator if denominator else None


def queue_series(packets: list[dict]) -> tuple[list[int], list[int], list[int]]:
    """Reconstruct aggregate occupancy, netting simultaneous events at one timestamp."""
    changes = defaultdict(lambda: [0, 0])
    for row in packets:
        entry = row.get("pon_enqueue_ns")
        if entry in (None, ""):
            continue
        size = row["packet_bytes"]
        changes[entry][0] += 1
        changes[entry][1] += size
        load = row.get("pon_load_ns")
        if load not in (None, ""):
            changes[load][0] -= 1
            changes[load][1] -= size
    times, counts, sizes = [], [], []
    count = size = 0
    for instant, (delta_count, delta_size) in sorted(changes.items()):
        count += delta_count
        size += delta_size
        require(count >= 0 and size >= 0, "FIFO occupancy went negative")
        times.append(instant)
        counts.append(count)
        sizes.append(size)
    return times, counts, sizes


def latency(packets: list[dict], cutoff: int) -> dict:
    samples, censored_ages = [], []
    for row in packets:
        entry = row.get("pon_enqueue_ns")
        if entry in (None, "") or entry >= cutoff:
            continue
        load = row.get("pon_load_ns")
        if load not in (None, "") and load < cutoff:
            samples.append(load - entry)
        else:
            censored_ages.append(cutoff - entry)
    samples.sort()
    count = len(samples)
    result = dict(
        fifo_sample_count=count, fifo_mean_ns=mean(samples) if count else None,
        fifo_jitter_sd_ns=stdev(samples) if count > 1 else None,
        fifo_max_ns=samples[-1] if count else None, fifo_unloaded_at_cutoff_packets=len(censored_ages),
        fifo_max_censored_age_ns=max(censored_ages, default=None),
        fifo_load_coverage=ratio(count, count + len(censored_ages)),
    )
    for label, tail_denom in (("p95", 20), ("p99", 100), ("p999", 1000)):
        # ceil(n*(1-1/k)) = n-floor(n/k). The rank is 1-indexed.
        tail = count / tail_denom
        result[f"fifo_{label}_ns"] = samples[count - count // tail_denom - 1] if count else None
        result[f"{label}_expected_tail_samples"] = tail
        result[f"{label}_tail_support"] = (
            "N/A" if not count else "unresolved" if tail < 1 else "thin_tail" if tail < 10 else "supported"
        )
    return result


def outcomes(packets: list[dict], cutoff: int, simulation_end: int) -> dict:
    dropped = [row for row in packets if row["status"] == "dropped"]
    delivered = [row for row in packets if row["status"] == "delivered"]
    pending = [row for row in packets if row["status"] == "pending"]
    require(len(packets) == len(dropped) + len(delivered) + len(pending), "Packet outcomes do not reconcile")
    enqueued = [row for row in packets if row.get("pon_enqueue_ns") not in (None, "")]
    times, counts, sizes = queue_series(packets)
    limit = bisect_left(times, cutoff)
    observed = sum(row["delivery_ns"] < cutoff for row in delivered)
    dropped_at_cutoff = sum(row["drop_ns"] < cutoff for row in dropped)
    count_at_cutoff = counts[limit - 1] if limit else 0
    return dict(
        generated_packets=len(packets), generated_bytes=sum(row["packet_bytes"] for row in packets),
        optical_enqueued_packets=len(enqueued), optical_enqueued_bytes=sum(row["packet_bytes"] for row in enqueued),
        actual_dropped_packets=len(dropped), actual_dropped_bytes=sum(row["packet_bytes"] for row in dropped),
        actual_drop_fraction=ratio(len(dropped), len(packets)), overflow_status="unsupported",
        overflow_dropped_packets=None, overflow_drop_fraction=None,
        peak_queue_packets=max(counts[:limit], default=0), peak_queue_bytes=max(sizes[:limit], default=0),
        pending_at_cutoff_packets=len(packets) - observed - dropped_at_cutoff,
        fifo_pending_at_cutoff_packets=count_at_cutoff,
        final_pending_packets=len(pending), final_pending_bytes=sum(row["packet_bytes"] for row in pending),
        final_pending_fraction=ratio(len(pending), len(packets)),
        delivered_at_cutoff_packets=observed, drain_delivered_packets=len(delivered) - observed,
        final_delivered_packets=len(delivered), simulation_end_ns=simulation_end,
    )


def max_gap(frames: list[int]) -> int | None:
    return max((later - prior for prior, later in zip(frames, frames[1:])), default=None)


def longest_known_streak(rows: list[dict]) -> int:
    streak = longest = 0
    for row in rows:
        if row["known"] > 0 and row["actual"] == 0:
            streak += 1
            longest = max(longest, streak)
        else:
            streak = 0
    return longest


def phases(decision: dict, actual: int) -> dict:
    """Attribute actual bytes to merged grant components without assigning packet labels."""
    amounts = {phase: int(decision[f"{phase}_bytes"]) for phase in PHASES}
    attributed = {}
    remaining = actual
    for phase, amount in amounts.items():
        attributed[phase] = min(remaining, amount)
        remaining -= attributed[phase]
    require(remaining == 0, "Data exceeds all logged grant components")
    return dict(amounts=amounts, attributed=attributed,
                fixed_due=int(decision["ab_fixed_bytes"]) if int(decision["si_fixed_before"]) == 1 else 0,
                assured_wanted=int(decision["assured_wanted_bytes"]))


def assess_si(decision: dict, prior: dict | None) -> int:
    """Count bad GIANT SI transitions or over-quota grants without deleting the run."""
    kind = int(decision["type"])
    rules = (
        ("fixed", "fixed", "fixed", kind == 1),
        ("max", "assured", "min", kind in (2, 3)),
        ("min", "non_assured" if kind == 3 else "best_effort", "sur", kind in (3, 4)),
    )
    bad = 0
    for counter, phase, quota, active in rules:
        if not active:
            continue
        interval = int(decision[f"si_{counter}_frames"])
        before, after = (int(decision[f"si_{counter}_{edge}"]) for edge in ("before", "after"))
        initial = int(prior[f"si_{counter}_after"]) if prior else interval
        granted = int(decision[f"{phase}_bytes"])
        if before == 1:
            after_expected = interval if phase in ("fixed", "assured") or granted else 1
        else:
            after_expected = before - 1
        bad += int(before != initial or not 1 <= before <= interval or after != after_expected
                   or (granted > 0 and before != 1) or not 0 <= granted <= int(decision[f"ab_{quota}_bytes"]))
    return bad


def frames_from_trace(trace: list[dict], cutoff: int, frame_ns: int, complete: bool) -> dict[str, list[dict]]:
    """Join a verified BWmap/settlement by Alloc-ID and full target frame."""
    state = events(trace, "PON_DBA_OBJECT_STATE")
    grants = {(row["allocation_id"], row["target_frame_id"]): row for row in events(trace, "PON_DBA_BWMAP_TX")}
    settlement = {row["grant_id"]: row for row in events(trace, "PON_DBA_SETTLEMENT_RX")}
    decisions = {(row["allocation_id"], row["target_frame_id"]): row for row in events(trace, "PON_GIANT_DECISION")}
    result = defaultdict(list)
    per_frame = defaultdict(list)
    for row in state:
        aid, token = row["allocation_id"], row["target_frame_id"]
        number = int(token)
        if (number + 1) * frame_ns > cutoff:
            continue
        tx = grants.get((aid, token))
        budget = int(tx["budget_bytes"]) if tx else 0
        actual = int(settlement[tx["grant_id"]]["actual_bytes"]) if tx else 0
        value = dict(frame=number, known=int(row["known_demand_bytes"]), budget=budget, actual=actual)
        if complete:
            decision = decisions[aid, token]
            require(int(decision["budget_bytes"]) == budget, "Decision and BWmap disagree")
            value.update(phases(decision, actual))
            prior = result[aid][-1]["decision"] if result[aid] else None
            value.update(decision=decision, bound_violations=assess_si(decision, prior), inversions=0)
            per_frame[number].append(value)
        result[aid].append(value)
    # A skipped due protected grant while sending lower priority, or out-of-order phase numbering.
    for group in per_frame.values():
        orders = []
        low = any(row["amounts"]["non_assured"] + row["amounts"]["best_effort"] > 0 for row in group)
        for row in group:
            decision = row["decision"]
            for rank, phase in enumerate(PHASES):
                order = int(decision[f"{phase}_order"])
                if row["amounts"][phase]:
                    orders.append((order, rank, row))
                elif order:
                    row["inversions"] += 1
            if low and (row["amounts"]["fixed"] < row["fixed_due"]
                        or row["amounts"]["assured"] < row["assured_wanted"]):
                row["inversions"] += 1
        max_rank = -1
        for expected_order, (order, rank, row) in enumerate(sorted(orders, key=lambda item: item[0]), start=1):
            row["inversions"] += int(rank < max_rank or order != expected_order)
            max_rank = max(max_rank, rank)
    return result


def int_setting(settings: dict[str, str], key: str) -> int:
    return int(settings.get(key, 0))


def reference(rows: list[dict], packets: list[dict], interval: int, quota: int,
              fixed: bool, frame_ns: int) -> dict:
    """Common phase-neutral benchmark on the configured SI grid and true pre-frame FIFO.

    Only the experiment analysis reads reconstructed FIFO occupancy. Both policies
    retain their original runtime inputs. For fixed bandwidth the reference
    remains the configured full reservation, including empty opportunities.
    """
    times, _, occupancy = queue_series(packets)
    due = qualified = wanted = grant_cover = actual_cover = grant_miss = actual_miss = 0
    first = rows[0]["frame"] if rows else 0
    for row in rows:
        frame = row["frame"]
        if not interval or (frame - first + 1) % interval:
            continue
        due += 1
        position = bisect_left(times, frame * frame_ns)
        demand = occupancy[position - 1] if position else 0
        target = quota if fixed else min(quota, demand)
        if target:
            qualified += 1
            wanted += target
            grant_cover += min(target, row["budget"])
            actual_cover += min(target, row["actual"])
            grant_miss += int(row["budget"] < target)
            actual_miss += int(row["actual"] < target)
    return dict(configured_due_opportunities=due, configured_due_bytes=due * quota,
                reference_qualified_opportunities=qualified, reference_wanted_bytes=wanted,
                reference_grant_covered_bytes=grant_cover, reference_actual_covered_bytes=actual_cover,
                reference_grant_violations=grant_miss, reference_actual_violations=actual_miss)


def phase_totals(rows: list[dict]) -> dict:
    out = dict.fromkeys(PHASE_COUNT_FIELDS, 0)
    for row in rows:
        for phase in PHASES:
            out[f"{phase}_granted_bytes"] += row["amounts"][phase]
            out[f"{phase}_attributed_actual_bytes"] += row["attributed"][phase]
        out["fixed_due_bytes"] += row["fixed_due"]
        out["assured_demand_qualified_bytes"] += row["assured_wanted"]
        out["assured_violations"] += int(row["amounts"]["assured"] < row["assured_wanted"])
        out["assured_actual_shortfalls"] += int(row["attributed"]["assured"] < row["assured_wanted"])
        out["assured_unknown_opportunities"] += int(
            row["known"] < 0 and int(row["decision"]["si_max_before"]) == 1
        )
        out["si_or_component_bound_violations"] += row["bound_violations"]
        out["priority_inversions"] += row["inversions"]
    return out


def ratio_fields(row: dict, cutoff: int, surplus: int | None) -> None:
    wanted = row["reference_wanted_bytes"]
    row.update(reference_grant_coverage=ratio(row["reference_grant_covered_bytes"], wanted),
               reference_actual_coverage=ratio(row["reference_actual_covered_bytes"], wanted),
               actual_goodput_mbps=row["actual_delivered_bytes"] * 8_000 / cutoff)
    if surplus is not None:
        row.update(fixed_reservation_ratio=ratio(row["fixed_granted_bytes"], row["fixed_due_bytes"]),
                   fixed_carry_mbps=row["fixed_attributed_actual_bytes"] * 8_000 / cutoff,
                   assured_reservation_ratio=ratio(row["assured_granted_bytes"], row["assured_demand_qualified_bytes"]),
                   assured_actual_ratio=ratio(row["assured_attributed_actual_bytes"], row["assured_demand_qualified_bytes"]),
                   non_assured_share_of_surplus_granted=ratio(row["non_assured_granted_bytes"], surplus),
                   best_effort_share_of_surplus_granted=ratio(row["best_effort_granted_bytes"], surplus))


def analyze_qos_run(log: str, case: Experiment) -> QosRun:
    settings = {key: str(value) for key, value in case.config.items()}
    cutoff, decision_end = case.traffic_end_us * 1000, case.decision_end_us * 1000
    frame_ns = int(Decimal(settings["fiber.uplink.frame.period.us"]) * 1000)
    complete = case.policy == COMPLETE
    verified = analyze_run(log, settings, case.load, case.seed, "qos-special", cutoff, decision_end, cutoff,
                           include_undelivered=True)
    simulation_end = next(row["simulation_end_ns"] for row in verified.metrics if row["scope"] == "total")
    trace = parse(log)
    per_alloc_frames = frames_from_trace(trace, cutoff, frame_ns, complete)
    packets_by_alloc = defaultdict(list)
    for packet in verified.packet_delays:
        packets_by_alloc[packet["allocation_id"]].append(packet)
    identities = dict(scenario_id=case.name, policy=case.policy, load=case.load, seed=case.seed,
                      offered_mbps=case.offered_mbps, observation_end_ns=cutoff, decision_end_ns=decision_end)
    latency_rows, sla_rows, loss_rows = [], [], []
    sla_allocs = defaultdict(list)
    cohort_packets = defaultdict(list)
    surplus_total = 0 if complete else None
    metric_rows = [row for row in verified.metrics if row["scope"] == "tcont"]
    for metric in metric_rows:
        aid, kind = metric["allocation_id"], metric["tcont_type"]
        meta = dict(identities, scope="alloc", allocation_id=aid, sfu_id=metric["sfu_id"], tcont_type=kind)
        members = packets_by_alloc[aid]
        cohort_packets[kind].extend(members)
        fifo_wait = latency(members, cutoff)
        outcome = outcomes(members, cutoff, simulation_end)
        require(outcome["fifo_pending_at_cutoff_packets"] == fifo_wait["fifo_unloaded_at_cutoff_packets"]
                == metric["pending_optical_packets"], "FIFO occupancy and wait censoring disagree")
        latency_rows.append(dict(meta, **fifo_wait))
        loss_rows.append(dict(meta, **outcome))

        prefix = f"fiber.uplink.tcont.alloc.{aid}."
        fixed_si, fixed_ab, assured_si, assured_ab, surplus_si, surplus_ab = (
            int_setting(settings, prefix + suffix) for suffix in (
                "si.fixed.frames", "ab.fixed.bytes", "si.max.frames", "ab.min.bytes", "si.min.frames", "ab.sur.bytes"
            )
        )
        if kind not in ("2", "3"):
            assured_si = assured_ab = 0
        interval, quota = (fixed_si, fixed_ab) if kind == "1" else (assured_si, assured_ab)
        rows = per_alloc_frames[aid]
        sla = dict(meta, configured_fixed_si_frames=fixed_si, configured_fixed_ab_bytes=fixed_ab,
                   configured_assured_si_frames=assured_si, configured_assured_ab_bytes=assured_ab,
                   configured_surplus_si_frames=surplus_si, configured_surplus_ab_bytes=surplus_ab,
                   configured_sla_mbps=quota * 8_000 / (interval * frame_ns) if interval else 0.0,
                   **reference(rows, members, interval, quota, kind == "1", frame_ns))
        grant_frames = [row["frame"] for row in rows if row["budget"] > 0]
        actual_frames = [row["frame"] for row in rows if row["actual"] > 0]
        sla.update(grant_frames=len(grant_frames), actual_service_frames=len(actual_frames),
                   max_grant_gap_frames=max_gap(grant_frames), max_actual_service_gap_frames=max_gap(actual_frames),
                   max_known_backlog_no_service_frames=longest_known_streak(rows),
                   actual_delivered_bytes=metric["delivered_bytes"],
                   actual_final_delivered_bytes=metric["delivered_bytes"] + metric["late_delivered_bytes"])
        if complete:
            sla.update(phase_totals(rows))
            surplus_total += sla["non_assured_granted_bytes"] + sla["best_effort_granted_bytes"]
        sla_allocs[kind].append(sla)

    sums = (
        "configured_sla_mbps", "configured_due_opportunities", "configured_due_bytes", "reference_qualified_opportunities",
        "reference_wanted_bytes", "reference_grant_covered_bytes", "reference_actual_covered_bytes",
        "reference_grant_violations", "reference_actual_violations", "grant_frames", "actual_service_frames",
        "actual_delivered_bytes", "actual_final_delivered_bytes",
    ) + (PHASE_COUNT_FIELDS if complete else ())
    for kind, peers in sla_allocs.items():
        meta = dict(identities, scope="type", tcont_type=kind, allocation_id="", sfu_id="")
        combined = cohort_packets[kind]
        latency_rows.append(dict(meta, **latency(combined, cutoff)))
        loss_rows.append(dict(meta, **outcomes(combined, cutoff, simulation_end)))
        goodput_basis = [peer["actual_delivered_bytes"] for peer in peers]
        jain = ratio(sum(goodput_basis) ** 2, len(peers) * sum(value ** 2 for value in goodput_basis))
        for peer in peers:
            peer.update(jain_same_type_goodput=jain, same_type_peers=len(peers))
            ratio_fields(peer, cutoff, surplus_total)
            sla_rows.append(peer)
        combined_sla = dict(meta, jain_same_type_goodput=jain, same_type_peers=len(peers))
        combined_sla.update((field, sum(peer[field] for peer in peers)) for field in sums)
        for field in ("max_grant_gap_frames", "max_actual_service_gap_frames", "max_known_backlog_no_service_frames"):
            combined_sla[field] = max((peer[field] for peer in peers if peer[field] is not None), default=None)
        ratio_fields(combined_sla, cutoff, surplus_total)
        sla_rows.append(combined_sla)
    return QosRun(latency_rows, sla_rows, loss_rows, verified.workload)
