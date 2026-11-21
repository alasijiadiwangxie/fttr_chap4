"""Reconstruct the T2 demand seen by MFU and the actual optical FIFO from Java events."""

from __future__ import annotations

from bisect import bisect_left
from collections import deque
from dataclasses import dataclass, field
from decimal import Decimal

from pon_giant_completion_workload import COMPLETE, Experiment
from pon_experiment_io import require


T2_EVENTS = frozenset((
    "TCONT_ENQUEUE", "TCONT_REPORT_RX", "PON_DBA_OBJECT_STATE", "PON_GIANT_DECISION",
    "PON_DBA_BWMAP_TX", "PON_DBA_BWMAP_RX", "PON_DBA_FILL", "PON_DBA_SETTLEMENT_RX",
))
T2_GRANT_COLUMNS = (
    "scenario_id", "policy", "seed", "abmin_bytes_per_sfu", "allocation_id", "sfu_id",
    "target_frame_id", "time_ns", "frame_start_ns", "frame_end_ns", "in_observation",
    "state_log_line", "decision_log_line", "report_log_line", "fill_log_line", "report_state",
    "report_kind", "report_frame_id", "report_snapshot_ns", "report_receive_ns", "report_age_ns",
    "report_pending_bytes", "known_demand_bytes", "ledger_unsettled_debit_bytes",
    "ledger_settled_debit_bytes", "budget_outstanding", "outstanding_grants",
    "unloaded_committed_bytes", "decision_fifo_packets", "decision_fifo_bytes", "decision_fifo_head_age_ns",
    "offline_uncovered_fifo_bytes", "offline_backlog_gt_known", "unknown_with_offline_backlog",
    "frame_start_fifo_packets", "frame_start_fifo_bytes", "frame_end_fifo_packets", "frame_end_fifo_bytes",
    "enqueued_packets_in_frame", "loaded_packets_in_frame", "backlog_streak_frames",
    "si_max_frames", "si_max_before", "si_max_after", "assured_reason", "assured_wanted_bytes",
    "assured_granted_bytes", "assured_shortfall_bytes", "authorized_at_cap",
    "cap_exhausted_with_more_known", "rr_outstanding_without_grant",
    "grant_id", "granted_bytes", "loaded_bytes", "actual_bytes", "unused_bytes",
    "bwmap_send_ns", "bwmap_receive_ns", "slot_start_ns", "slot_end_ns", "settlement_ns",
    "loaded_packets", "max_loaded_fifo_wait_ns", "remaining_after_fill_packets", "fill_reason",
)


@dataclass
class Budget:
    send: dict
    receive: dict | None = None
    fill: dict | None = None
    settlement: dict | None = None
    loaded_packets: int = 0
    max_loaded_fifo_wait_ns: int | None = None

    @property
    def bytes(self) -> int:
        return int(self.send["budget_bytes"])

    @property
    def start(self) -> int:
        return int(self.send["tx_start_ns"])


@dataclass
class Demand:
    # A FILL event atomically removes the complete list of selected packets, even if
    # serialization happens later. ARRIVAL/DELIVERED are not optical FIFO mutations.
    queue: deque = field(default_factory=deque)
    queue_bytes: int = 0
    enqueued: int = 0
    loaded: int = 0
    timeline: list = field(default_factory=list)
    latest_report: dict | None = None
    report_budgets: deque = field(default_factory=deque)
    inflight: dict[str, Budget] = field(default_factory=dict)
    decisions: dict = field(default_factory=dict)
    states: dict = field(default_factory=dict)
    budgets_by_frame: dict[int, Budget] = field(default_factory=dict)

    def observe_queue(self, now: int) -> None:
        self.timeline.append((now, len(self.queue), self.queue_bytes, self.enqueued, self.loaded))

    def before(self, now: int) -> tuple[int, int, int, int]:
        # Strictly earlier events: two adjacent targets have identical boundary
        # samples. The decision snapshot instead follows exact log order.
        at = bisect_left(self.timeline, now, key=lambda row: row[0])
        return self.timeline[at - 1][1:] if at else (0, 0, 0, 0)

    def state(self, row: dict) -> dict:
        now = row["_time_ns"]
        known = int(row["known_demand_bytes"])
        outstanding = row["budget_outstanding"] == "true"
        require(outstanding == bool(self.inflight), "T2 outstanding state disagrees with grant event order")
        committed = sum(grant.bytes for grant in self.inflight.values() if grant.fill is None)
        # Conservative diagnostic only: one earlier unfilled grant can cover up
        # to its budget, even though packet-size rounding may use fewer bytes.
        uncovered = max(0, self.queue_bytes - committed)
        entry = dict(
            time_ns=now, sfu_id=int(row["sfu_id"]), state_log_line=row["_index"],
            report_state=row["state"], known_demand_bytes=known, budget_outstanding=outstanding,
            outstanding_grants=len(self.inflight), unloaded_committed_bytes=committed,
            decision_fifo_packets=len(self.queue), decision_fifo_bytes=self.queue_bytes,
            decision_fifo_head_age_ns=now - self.queue[0][2] if self.queue else None,
            offline_uncovered_fifo_bytes=uncovered, offline_backlog_gt_known=known >= 0 and uncovered > known,
            unknown_with_offline_backlog=known < 0 and uncovered > 0,
        )
        report = self.latest_report
        if report is None:
            require(row["state"] == "UNKNOWN" and known == -1, "T2 state implies a REPORT that was not received")
            return entry
        # A newer snapshot already includes the effect of allocations that
        # began at/before it. Old snapshots subtract open budgets or actual
        # settled bytes for allocations after the snapshot.
        snapshot = int(report["snapshot_time_ns"])
        while self.report_budgets and self.report_budgets[0].start <= snapshot:
            self.report_budgets.popleft()
        unsettled_debit = sum(g.bytes for g in self.report_budgets if g.settlement is None)
        settled_debit = sum(int(g.settlement["actual_bytes"]) for g in self.report_budgets if g.settlement is not None)
        pending = int(report["pending_bytes"])
        require(
            row["state"] == "KNOWN" and int(row["report_frame_id"]) == int(report["frame_id"])
            and int(row["report_age_ns"]) == now - snapshot
            and known == max(0, pending - settled_debit - unsettled_debit),
            "T2 ledger does not reconcile with the accepted REPORT and reservation/settlement events",
        )
        entry.update(
            report_log_line=report["_index"], report_kind=report["report_kind"],
            report_frame_id=int(report["frame_id"]), report_snapshot_ns=snapshot,
            report_receive_ns=report["_time_ns"], report_age_ns=now - snapshot, report_pending_bytes=pending,
            ledger_unsettled_debit_bytes=unsettled_debit, ledger_settled_debit_bytes=settled_debit,
        )
        return entry


def target_allocations(case: Experiment) -> list[str]:
    prefix = "fiber.uplink.tcont.alloc."
    return [aid for aid in str(case.config[prefix + "ids"]).split(",")
            if int(case.config[prefix + aid + ".type"]) == 2]


def t2_grant_trace(trace: list[dict], case: Experiment) -> list[dict]:
    """Emit per-frame T2 rows. Accept a parsed full log or its T2_EVENTS subset."""
    by_id = {aid: Demand() for aid in target_allocations(case)}
    require(bool(by_id), "No Type 2 allocation configured")
    budgets: dict[str, Budget] = {}
    input_ids: set[str] = set()
    frame_ns = int(Decimal(str(case.config["fiber.uplink.frame.period.us"])) * 1000)
    cutoff, horizon = case.traffic_end_us * 1000, case.decision_end_us * 1000
    propagation = int(Decimal(str(case.config["fiber.uplink.propagation.us"])) * 1000)
    control_propagation = int(Decimal(str(case.config["fiber.downlink.propagation.us"])) * 1000)
    previous_time = -1
    previous_line = 0
    for row in trace:
        allocation, event = row.get("allocation_id"), row.get("event")
        if event not in T2_EVENTS or allocation not in by_id:
            continue
        now = row["_time_ns"]
        require(now >= previous_time and row["_index"] > previous_line, "T2 events are out of execution order")
        previous_time, previous_line = now, row["_index"]
        demand = by_id[allocation]
        if event == "TCONT_ENQUEUE":
            packet, size = row["packet_id"], int(row["packet_bytes"])
            require(packet not in input_ids and size > 0, "T2 packet was enqueued twice or had zero size")
            input_ids.add(packet)
            demand.queue.append((packet, size, now))
            demand.queue_bytes += size
            demand.enqueued += 1
            demand.observe_queue(now)
        elif event == "TCONT_REPORT_RX" and row["applied"] == "true":
            # Reject a timeline that would prune still-relevant earlier bookings.
            if demand.latest_report is not None:
                require(int(row["snapshot_time_ns"]) >= int(demand.latest_report["snapshot_time_ns"]),
                        "An older T2 REPORT replaced a newer accepted snapshot")
            demand.latest_report = row
        elif event in ("PON_DBA_OBJECT_STATE", "PON_GIANT_DECISION"):
            frame = int(row["target_frame_id"])
            dest = demand.states if event == "PON_DBA_OBJECT_STATE" else demand.decisions
            require(frame not in dest and now == (frame - 1) * frame_ns, "Duplicate or misaligned T2 frame state")
            dest[frame] = demand.state(row) if event == "PON_DBA_OBJECT_STATE" else row
        elif event == "PON_DBA_BWMAP_TX":
            frame, gid = int(row["target_frame_id"]), row["grant_id"]
            require(gid not in budgets and frame not in demand.budgets_by_frame
                    and frame in demand.states and now == (frame - 1) * frame_ns,
                    "Duplicate or misaligned T2 budget")
            grant = Budget(row)
            demand.budgets_by_frame[frame] = demand.inflight[gid] = budgets[gid] = grant
            require(not demand.report_budgets or demand.report_budgets[-1].start <= grant.start,
                    "T2 grants overlap chronologically")
            demand.report_budgets.append(grant)
        elif event in ("PON_DBA_BWMAP_RX", "PON_DBA_FILL", "PON_DBA_SETTLEMENT_RX"):
            grant = budgets.get(row["grant_id"])
            require(grant is not None and grant.send["allocation_id"] == allocation
                    and grant.send["target_frame_id"] == row["target_frame_id"], "T2 budget identity changed")
            if event == "PON_DBA_BWMAP_RX":
                require(grant.receive is None and now == grant.send["_time_ns"] + control_propagation
                        and now < grant.start, "Duplicate or late T2 BWmap reception")
                grant.receive = row
            elif event == "PON_DBA_FILL":
                require(grant.receive is not None and grant.fill is None and now == grant.start,
                        "Duplicate T2 FILL or FILL before BWmap reception")
                grant.fill = row
                selected = row["selected_packet_ids"]
                loaded_bytes = 0
                for packet in [] if selected == "-" else selected.split(";"):
                    require(bool(demand.queue), "T2 grant loaded an empty FIFO")
                    head, size, inserted = demand.queue.popleft()
                    require(packet == head, "T2 FILL did not select the real FIFO head")
                    loaded_bytes += size
                    grant.loaded_packets += 1
                    wait = now - inserted
                    grant.max_loaded_fifo_wait_ns = max(grant.max_loaded_fifo_wait_ns or 0, wait)
                demand.loaded += grant.loaded_packets
                demand.queue_bytes -= loaded_bytes
                demand.observe_queue(now)
                require(loaded_bytes == int(row["selected_bytes"]) and loaded_bytes <= grant.bytes
                        and len(demand.queue) == int(row["remaining_packets"]),
                        "T2 FIFO or loaded-byte reconstruction differs from FILL")
            else:
                actual, unused = int(row["actual_bytes"]), int(row["unused_bytes"])
                require(grant.fill is not None and grant.settlement is None
                        and now == int(grant.send["tx_end_ns"]) + propagation
                        and grant.bytes == actual + unused and actual == int(grant.fill["selected_bytes"]),
                        "T2 settlement timing or byte conservation failed")
                grant.settlement = row
                demand.inflight.pop(row["grant_id"])

    expected_frames = set(range(2, (horizon - 1) // frame_ns + 2))
    complete = case.policy == COMPLETE
    output = []
    for aid, demand in by_id.items():
        prefix = "fiber.uplink.tcont.alloc." + aid + "."
        abmin, simax = int(case.config[prefix + "ab.min.bytes"]), int(case.config[prefix + "si.max.frames"])
        require(set(demand.states) == expected_frames and not demand.inflight,
                "T2 trace omitted decisions or ended before grants settled")
        require(set(demand.decisions) == expected_frames if complete else not demand.decisions,
                "T2 trace omitted or unexpectedly emitted GIANT decisions")
        backlog_streak = 0
        for frame, state in sorted(demand.states.items()):
            start, end = frame * frame_ns, (frame + 1) * frame_ns
            start_packets, start_bytes, start_enqueued, start_loaded = demand.before(start)
            end_packets, end_bytes, end_enqueued, end_loaded = demand.before(end)
            in_observation = end <= cutoff
            backlog_streak = backlog_streak + 1 if end_packets and in_observation else 0
            grant = demand.budgets_by_frame.get(frame)
            planned = grant.bytes if grant else 0
            actual = int(grant.settlement["actual_bytes"]) if grant else 0
            entry = dict(
                state, scenario_id=case.name, policy=case.policy, seed=case.seed,
                abmin_bytes_per_sfu=abmin, allocation_id=aid, si_max_frames=simax, target_frame_id=frame,
                frame_start_ns=start, frame_end_ns=end, in_observation=in_observation,
                frame_start_fifo_packets=start_packets, frame_start_fifo_bytes=start_bytes,
                frame_end_fifo_packets=end_packets, frame_end_fifo_bytes=end_bytes,
                enqueued_packets_in_frame=end_enqueued - start_enqueued,
                loaded_packets_in_frame=end_loaded - start_loaded,
                backlog_streak_frames=backlog_streak if in_observation else None,
                granted_bytes=planned, loaded_bytes=actual, actual_bytes=actual, unused_bytes=planned - actual,
                loaded_packets=grant.loaded_packets if grant else 0,
            )
            if grant:
                entry.update(
                    grant_id=grant.send["grant_id"], bwmap_send_ns=grant.send["_time_ns"],
                    bwmap_receive_ns=grant.receive["_time_ns"], slot_start_ns=grant.start,
                    slot_end_ns=int(grant.send["tx_end_ns"]), settlement_ns=grant.settlement["_time_ns"],
                    fill_log_line=grant.fill["_index"], max_loaded_fifo_wait_ns=grant.max_loaded_fifo_wait_ns,
                    remaining_after_fill_packets=int(grant.fill["remaining_packets"]), fill_reason=grant.fill["fill_reason"],
                )
            if complete:
                decision = demand.decisions[frame]
                wanted, assured = int(decision["assured_wanted_bytes"]), int(decision["assured_bytes"])
                require(assured == planned and int(decision["ab_min_bytes"]) == abmin
                        and int(decision["si_max_frames"]) == simax
                        and int(decision["known_demand_bytes"]) == state["known_demand_bytes"],
                        "T2 assured decision disagrees with state, configured reference, or BWmap")
                entry.update(
                    decision_log_line=decision["_index"], si_max_before=int(decision["si_max_before"]),
                    si_max_after=int(decision["si_max_after"]), assured_reason=decision["assured_reason"],
                    assured_wanted_bytes=wanted, assured_granted_bytes=assured,
                    assured_shortfall_bytes=max(0, wanted - assured), authorized_at_cap=assured == abmin,
                    cap_exhausted_with_more_known=assured == abmin and state["known_demand_bytes"] > abmin,
                )
            else:
                entry["rr_outstanding_without_grant"] = (
                    state["budget_outstanding"] and state["known_demand_bytes"] > 0 and planned == 0
                )
            output.append(entry)
    return sorted(output, key=lambda row: (row["target_frame_id"], row["sfu_id"], row["allocation_id"]))
