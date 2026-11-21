"""Replay matched T2 tail packets from the Java log in both DBA policies."""

from __future__ import annotations

from bisect import bisect_left, bisect_right
from collections import defaultdict
from dataclasses import dataclass, field
from decimal import Decimal
from io import StringIO
from typing import Iterable

from pon_giant_completion_workload import Experiment
from pon_t2_delay_trace import target_allocations
from pon_execution_trace import fields
from pon_experiment_io import require


T2_TAIL_SUMMARY_COLUMNS = (
    "seed", "scenario_id", "variant", "policy", "packet_id", "allocation_id", "sfu_id", "packet_bytes",
    "giant_si1_tail", "rr_tail", "giant_si1_p99_ns", "rr_p99_ns", "giant_si1_loaded_sample_n", "rr_loaded_sample_n",
    "abmin_bytes_per_sfu", "si_max_frames", "poll_interval_frames", "observation_status", "enqueue_ns",
    "predecessors_at_enqueue", "first_applied_report_while_queued_ns", "first_report_snapshot_while_queued_ns",
    "funding_decision_ns", "planned_before_enqueue", "target_frame_id", "target_frame_start_ns", "grant_id",
    "funding_report_age_ns", "funding_known_demand_bytes", "funding_actual_fifo_bytes", "funding_assured_reason",
    "grant_bytes", "bwmap_receive_ns", "fill_ns", "predecessors_in_funding_grant", "fifo_wait_ns",
    "enqueue_to_decision_ns", "decision_to_target_frame_ns", "target_frame_to_fill_ns", "fill_to_data_start_ns",
    "data_start_ns", "data_end_ns", "mfu_delivery_ns", "queue_age_at_observation_end_ns",
    "waited_decisions", "pre_funding_decisions", "awaiting_settlement_frames", "not_due_frames", "known_prefix_short_frames",
    "uncovered_fifo_gt_known_frames", "zero_known_subject_uncovered_frames",
    "positive_known_subject_uncovered_outstanding_frames", "unknown_frames", "no_capacity_frames", "full_cap_fills_ahead",
    "earlier_fills_loading_predecessors", "polled_report_receives_while_waiting", "first_exact_evidence_line",
)

T2_TAIL_EVENT_COLUMNS = (
    "seed", "scenario_id", "variant", "policy", "packet_id", "allocation_id", "sfu_id", "event_time_ns",
    "event_log_line", "event", "event_relation", "target_frame_id", "grant_id", "trace_packet_status",
    "fifo_predecessors", "fifo_prefix_bytes_including_packet", "actual_fifo_packets", "actual_fifo_bytes",
    "report_state", "effective_known_bytes", "report_frame_id", "report_snapshot_ns", "report_receive_ns",
    "report_age_ns", "report_pending_bytes", "report_kind", "report_applied", "packet_queued_at_report_snapshot",
    "report_snapshot_log_line", "report_receive_log_line", "outstanding_budget",
    "unloaded_committed_bytes", "offline_uncovered_fifo_bytes", "ledger_unsettled_debit_bytes",
    "ledger_settled_debit_bytes", "abmin_bytes_per_sfu", "si_max_frames", "si_max_before", "si_max_after",
    "assured_reason", "assured_wanted_bytes", "assured_granted_bytes", "planned_grant_bytes",
    "actual_grant_bytes", "unused_grant_bytes", "fill_selected_packet_ids", "sent_predecessor_ids",
    "fill_reason", "remaining_after_fill_packets", "evidence_status", "diagnostic", "raw_java_event",
)

ALLOC_EVENTS = frozenset((
    "TCONT_ENQUEUE", "TCONT_REPORT", "TCONT_REPORT_RX", "PON_DBA_OBJECT_STATE", "PON_GIANT_DECISION",
    "PON_DBA_MULTI_DECISION", "PON_DBA_LEDGER_CREATED", "PON_DBA_BWMAP_TX", "PON_DBA_BWMAP_RX",
    "PON_DBA_FILL", "PON_DBA_DATA_START", "PON_DBA_DATA_END", "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX",
    "PON_POLL_BWMAP_TX", "PON_POLL_BWMAP_RX", "PON_POLL_TX_START", "PON_POLL_TX_END",
))
GRANT_EVENTS = frozenset(event for event in ALLOC_EVENTS if event.startswith("PON_DBA_")) - {
    "PON_DBA_OBJECT_STATE", "PON_DBA_MULTI_DECISION", "PON_DBA_DATA_START", "PON_DBA_DATA_END",
}


@dataclass
class PacketHistory:
    enqueue: dict
    position: int
    predecessors_at_enqueue: int
    fill: dict | None = None
    fill_position: int | None = None
    data_start: dict | None = None
    data_end: dict | None = None
    delivered: dict | None = None

    @property
    def wait_ns(self) -> int | None:
        return self.fill["_time_ns"] - self.enqueue["_time_ns"] if self.fill else None


@dataclass
class AllocationHistory:
    packet_ids: list[int] = field(default_factory=list)
    prefix_bytes: list[int] = field(default_factory=lambda: [0])
    fills: list[tuple[int, int]] = field(default_factory=list)
    removed: int = 0
    decisions: list[dict] = field(default_factory=list)
    reports: list[dict] = field(default_factory=list)

    def fifo_prefix(self, packet: PacketHistory, line: int) -> tuple[int, int]:
        """Packets/bytes ahead in the real FIFO before applying a given log record."""
        at = bisect_left(self.fills, line, key=lambda fill: fill[0])
        removed = self.fills[at - 1][1] if at else 0
        return packet.position - removed, self.prefix_bytes[packet.position + 1] - self.prefix_bytes[removed]


@dataclass
class T2PacketRun:
    case: Experiment
    packets: dict[int, PacketHistory]
    allocations: dict[str, AllocationHistory]
    lines: dict[int, dict]
    grants: dict[str, dict]
    polls: dict[str, dict]
    snapshots: dict[tuple[str, int, int], list[dict]]
    frames: dict[tuple[str, int], dict]


def _event_ns(row: dict) -> int:
    if "time_ns" in row:
        return int(row["time_ns"])
    if row["event"] == "TCONT_REPORT":
        return int(row["snapshot_time_ns"])
    value = Decimal(row["t_us"]) * 1000
    require(value == int(value), "T2 tail event lost its ns precision")
    return int(value)


def _report_key(event: dict) -> tuple[str, int, int]:
    return event["allocation_id"], int(event["frame_id"]), int(event["snapshot_time_ns"])


def _selected_ids(fill: dict) -> list[int]:
    return [int(pid) for pid in fill["selected_packet_ids"].split(";") if pid != "-"]


def packet_index(log: str | Iterable[str], case: Experiment, frames: list[dict]) -> T2PacketRun:
    """Index only T2 events, preserving the original Java log's one-based line references."""
    allocations = {aid: AllocationHistory() for aid in target_allocations(case)}
    tokens = tuple(f"allocation_id={aid} " for aid in allocations)
    packets: dict[int, PacketHistory] = {}
    retained: dict[int, dict] = {}
    grants: dict[str, dict] = defaultdict(dict)
    polls: dict[str, dict] = defaultdict(dict)
    snapshots: dict[tuple[str, int, int], list[dict]] = defaultdict(list)
    rr_decisions: dict[tuple[str, int], dict] = {}
    previous_ns = -1
    for index, raw in enumerate(StringIO(log) if isinstance(log, str) else log, 1):
        delivery = "node=MFU event=DELIVERED " in raw and "type=VI " in raw and "direction=UL " in raw
        if not delivery and not any(token in raw for token in tokens):
            continue
        row = fields(raw)
        event = row.get("event")
        if event not in ALLOC_EVENTS and not delivery:
            continue
        row = dict(row, _index=index, _time_ns=_event_ns(row), _raw=raw.rstrip("\r\n"))
        require(row["_time_ns"] >= previous_ns, "Java T2 tail trace is out of event order")
        previous_ns = row["_time_ns"]
        retained[index] = row
        if delivery:
            packet = packets[int(row["packet_id"])]
            require(packet.data_end is not None and packet.delivered is None, "Unexpected second or early MFU delivery")
            require(packet.enqueue["sfu_id"] == row["sfu_id"], "T2 delivery arrived via a different SFU")
            packet.delivered = row
            continue
        allocation = allocations[row["allocation_id"]]
        if event == "TCONT_ENQUEUE":
            pid, size = int(row["packet_id"]), int(row["packet_bytes"])
            position = len(allocation.packet_ids)
            require(pid not in packets, "T2 packet entered the optical FIFO twice")
            packets[pid] = PacketHistory(row, position, position - allocation.removed)
            allocation.packet_ids.append(pid)
            allocation.prefix_bytes.append(allocation.prefix_bytes[-1] + size)
        elif event == "PON_DBA_FILL":
            selected = _selected_ids(row)
            start = allocation.removed
            require(allocation.packet_ids[start:start + len(selected)] == selected, "T2 tail replay found non-FIFO fill")
            for position, pid in enumerate(selected):
                packets[pid].fill = row
                packets[pid].fill_position = position
            allocation.removed += len(selected)
            allocation.fills.append((index, allocation.removed))
        elif event in {"PON_DBA_DATA_START", "PON_DBA_DATA_END"}:
            packet = packets[int(row["packet_id"])]
            require(packet.fill is not None and packet.fill["grant_id"] == row["grant_id"], "T2 DATA has no matching FILL")
            if event == "PON_DBA_DATA_START":
                require(packet.data_start is None, "T2 DATA started twice")
                packet.data_start = row
            else:
                require(packet.data_start is not None and packet.data_end is None, "T2 DATA ended twice or before starting")
                packet.data_end = row
        elif event == "TCONT_REPORT":
            snapshots[_report_key(row)].append(row)
        elif event == "TCONT_REPORT_RX":
            require(any(item["pending_bytes"] == row["pending_bytes"] for item in snapshots[_report_key(row)]),
                    "T2 received REPORT has no matching source snapshot")
            allocation.reports.append(row)
        elif event == "PON_DBA_MULTI_DECISION":
            rr_decisions[row["allocation_id"], int(row["target_frame_id"])] = row
        if event in GRANT_EVENTS:
            by_action = grants[row["grant_id"]]
            require(event not in by_action, "Duplicate grant event in T2 tail index")
            by_action[event] = row
        elif event.startswith("PON_POLL_"):
            polls[row["poll_id"]][event] = row

    frames_by_key = {(str(row["allocation_id"]), row["target_frame_id"]): dict(row) for row in frames}
    require(len(frames_by_key) == len(frames), "Duplicate T2 allocation/frame index")
    for (aid, frame_id), frame in frames_by_key.items():
        allocations[aid].decisions.append(frame)
        frame["_multi_plan"] = rr_decisions.get((aid, frame_id))
        state = retained[frame["state_log_line"]]
        require(state["_time_ns"] == frame["time_ns"] and state["allocation_id"] == aid,
                "T2 packet and frame analyses do not reference the same Java trace")
    for allocation in allocations.values():
        allocation.decisions.sort(key=lambda row: row["state_log_line"])
    return T2PacketRun(case, packets, allocations, retained, grants, polls, snapshots, frames_by_key)


read_packet_run = packet_index


def _variant(run: T2PacketRun) -> str:
    return getattr(run.case, "variant", run.case.policy)


def _tail(run: T2PacketRun) -> tuple[int, int, set[int]]:
    end_ns = run.case.traffic_end_us * 1000
    sample = {pid: packet.wait_ns for pid, packet in run.packets.items()
              if packet.fill and packet.fill["_time_ns"] < end_ns}
    require(bool(sample), "T2 tail analysis needs at least one observed FIFO exit per baseline")
    ordered = sorted(sample.values())
    cutoff = ordered[(99 * len(ordered) + 99) // 100 - 1]
    return cutoff, len(ordered), {pid for pid, wait in sample.items() if wait >= cutoff}


def _phase_summary(packet: PacketHistory, frame: dict, grant: dict) -> dict:
    enqueue, fill = packet.enqueue["_time_ns"], packet.fill["_time_ns"]
    decision, target_start = frame["time_ns"], frame["frame_start_ns"]
    start = packet.data_start["_time_ns"]
    require(decision <= target_start <= fill <= start and enqueue <= fill, "T2 packet funding phase chronology failed")
    before = max(0, decision - enqueue)
    lead = max(0, target_start - max(enqueue, decision))
    slot_wait = fill - max(enqueue, target_start)
    require(before + lead + slot_wait == packet.wait_ns, "T2 phase decomposition does not reconcile to FIFO wait")
    return dict(
        funding_decision_ns=decision,
        planned_before_enqueue=grant["PON_DBA_LEDGER_CREATED"]["_index"] < packet.enqueue["_index"],
        target_frame_id=frame["target_frame_id"], target_frame_start_ns=target_start, grant_id=frame["grant_id"],
        funding_report_age_ns=frame.get("report_age_ns"), funding_known_demand_bytes=frame["known_demand_bytes"],
        funding_actual_fifo_bytes=frame["decision_fifo_bytes"], funding_assured_reason=frame.get("assured_reason"),
        grant_bytes=frame["granted_bytes"], bwmap_receive_ns=frame["bwmap_receive_ns"], fill_ns=fill,
        predecessors_in_funding_grant=packet.fill_position, fifo_wait_ns=packet.wait_ns,
        enqueue_to_decision_ns=before, decision_to_target_frame_ns=lead,
        target_frame_to_fill_ns=slot_wait, fill_to_data_start_ns=start - fill,
        data_start_ns=start, data_end_ns=packet.data_end["_time_ns"], mfu_delivery_ns=packet.delivered["_time_ns"],
    )


def _frame_fields(frame: dict) -> dict:
    return dict(
        target_frame_id=frame["target_frame_id"], grant_id=frame.get("grant_id"),
        actual_fifo_packets=frame["decision_fifo_packets"], actual_fifo_bytes=frame["decision_fifo_bytes"],
        report_state=frame["report_state"], effective_known_bytes=frame["known_demand_bytes"],
        report_frame_id=frame.get("report_frame_id"), report_snapshot_ns=frame.get("report_snapshot_ns"),
        report_receive_ns=frame.get("report_receive_ns"), report_age_ns=frame.get("report_age_ns"),
        report_pending_bytes=frame.get("report_pending_bytes"), report_kind=frame.get("report_kind"),
        outstanding_budget=frame["budget_outstanding"], unloaded_committed_bytes=frame["unloaded_committed_bytes"],
        offline_uncovered_fifo_bytes=frame["offline_uncovered_fifo_bytes"],
        ledger_unsettled_debit_bytes=frame.get("ledger_unsettled_debit_bytes"),
        ledger_settled_debit_bytes=frame.get("ledger_settled_debit_bytes"),
        si_max_frames=frame["si_max_frames"], abmin_bytes_per_sfu=frame["abmin_bytes_per_sfu"],
        si_max_before=frame.get("si_max_before"), si_max_after=frame.get("si_max_after"),
        assured_reason=frame.get("assured_reason"), assured_wanted_bytes=frame.get("assured_wanted_bytes"),
        assured_granted_bytes=frame.get("assured_granted_bytes"), planned_grant_bytes=frame["granted_bytes"],
    )


def _report_source(run: T2PacketRun, rx: dict) -> dict:
    candidates = [snapshot for snapshot in run.snapshots[_report_key(rx)]
                  if snapshot["_index"] < rx["_index"] and snapshot["pending_bytes"] == rx["pending_bytes"]
                  and snapshot["eligible_bytes"] == rx["eligible_bytes"]]
    # A frame-boundary snapshot can have the same timestamp and content as a
    # poll's snapshot. Distinguish them through the adjoining Java events.
    if rx["report_kind"] == "REPORT_ONLY":
        candidates = [row for row in candidates
                      if run.lines.get(row["_index"] - 1, {}).get("event") == "PON_POLL_TX_START"]
    elif rx["report_kind"] == "SETTLEMENT":
        candidates = [row for row in candidates
                      if run.lines.get(row["_index"] + 1, {}).get("event") == "PON_DBA_SLOT_END"]
    require(len(candidates) == 1, "Cannot identify a unique local snapshot for a T2 REPORT reception")
    return candidates[0]


def _packet_trace(run: T2PacketRun, pid: int) -> tuple[dict, list[dict]]:
    packet = run.packets[pid]
    enqueue = packet.enqueue
    aid = enqueue["allocation_id"]
    allocation = run.allocations[aid]
    profile = f"fiber.uplink.tcont.alloc.{aid}."
    cutoff = run.case.traffic_end_us * 1000
    identity = dict(seed=run.case.seed, scenario_id=run.case.name, variant=_variant(run), policy=run.case.policy,
                    packet_id=pid, allocation_id=aid, sfu_id=int(enqueue["sfu_id"]))
    summary = dict(
        identity, packet_bytes=int(enqueue["packet_bytes"]), enqueue_ns=enqueue["_time_ns"],
        predecessors_at_enqueue=packet.predecessors_at_enqueue, si_max_frames=int(run.case.config[profile + "si.max.frames"]),
        abmin_bytes_per_sfu=int(run.case.config[profile + "ab.min.bytes"]),
        poll_interval_frames=int(run.case.config["fiber.uplink.poll.interval.frames"]), waited_decisions=0,
        pre_funding_decisions=0,
        awaiting_settlement_frames=0, not_due_frames=0, known_prefix_short_frames=0, uncovered_fifo_gt_known_frames=0,
        zero_known_subject_uncovered_frames=0, positive_known_subject_uncovered_outstanding_frames=0,
        unknown_frames=0, no_capacity_frames=0, full_cap_fills_ahead=0, earlier_fills_loading_predecessors=0,
        polled_report_receives_while_waiting=0,
    )
    fill_line = packet.fill["_index"] if packet.fill else float("inf")
    rows: dict[int, dict] = {}

    def status(line: int) -> str:
        if line < enqueue["_index"]:
            return "NOT_ENQUEUED"
        if line <= fill_line:
            return "FIFO_WAITING"
        if packet.delivered and line >= packet.delivered["_index"]:
            return "DELIVERED"
        return "ALREADY_LOADED"

    def add(event: dict, relation: str, detail: str = "", **values: object) -> dict:
        line = event["_index"]
        entry = rows.setdefault(line, dict(
            identity, event_time_ns=event["_time_ns"], event_log_line=line, event=event["event"],
            event_relation=relation, trace_packet_status=status(line), evidence_status="OBSERVED_JAVA_LOG",
            diagnostic=detail, raw_java_event=event["_raw"],
        ))
        entry.update(values)
        if status(line) == "FIFO_WAITING":
            ahead, prefix = allocation.fifo_prefix(packet, line)
            entry.update(fifo_predecessors=ahead, fifo_prefix_bytes_including_packet=prefix)
        return entry

    def add_report(rx: dict, relation: str) -> tuple[dict, bool]:
        snapshot = _report_source(run, rx)
        queued = status(snapshot["_index"]) == "FIFO_WAITING"
        fields = dict(
            report_frame_id=int(rx["frame_id"]), report_snapshot_ns=snapshot["_time_ns"],
            report_receive_ns=rx["_time_ns"], report_age_ns=rx["_time_ns"] - snapshot["_time_ns"],
            report_pending_bytes=int(rx["pending_bytes"]), report_kind=rx["report_kind"],
            report_applied=rx["applied"] == "true", packet_queued_at_report_snapshot=queued,
            report_snapshot_log_line=snapshot["_index"], report_receive_log_line=rx["_index"],
        )
        add(snapshot, relation, "LOCAL_FIFO_SNAPSHOT_AGGREGATE_ONLY", **fields)
        add(rx, relation, "MFU_RECEIPT_AGGREGATE_HAS_NO_PACKET_ID", **fields)
        if rx["report_kind"] == "REPORT_ONLY":
            poll_id = run.lines[snapshot["_index"] - 1]["poll_id"]
            for event in run.polls[poll_id].values():
                add(event, "REPORT_ONLY_CONTROL", grant_id=f"R{poll_id}", target_frame_id=int(event["target_frame_id"]))
        return fields, queued

    add(enqueue, "SUBJECT_PACKET", "FIFO_ENQUEUE")
    first_report = bisect_right(allocation.reports, enqueue["_index"], key=lambda row: row["_index"])
    last_report = bisect_left(allocation.reports, fill_line, key=lambda row: row["_index"])
    for rx in allocation.reports[first_report:last_report]:
        _, queued = add_report(rx, "REPORT_RECEIVED_DURING_FIFO_WAIT")
        summary["polled_report_receives_while_waiting"] += rx["report_kind"] == "REPORT_ONLY"
        if queued and rx["applied"] == "true" and "first_applied_report_while_queued_ns" not in summary:
            summary.update(first_applied_report_while_queued_ns=rx["_time_ns"],
                           first_report_snapshot_while_queued_ns=int(rx["snapshot_time_ns"]))

    first_decision = bisect_right(allocation.decisions, enqueue["_index"], key=lambda row: row["state_log_line"])
    last_decision = bisect_left(allocation.decisions, fill_line, key=lambda row: row["state_log_line"])
    relevant = allocation.decisions[first_decision:last_decision]
    if packet.fill:
        funding = run.frames[(aid, int(packet.fill["target_frame_id"]))]
        grant = run.grants[packet.fill["grant_id"]]
        summary.update(_phase_summary(packet, funding, grant))
        if funding["state_log_line"] < enqueue["_index"]:
            relevant = [funding, *relevant]
        summary["observation_status"] = "LOADED_IN_OBSERVATION" if packet.fill["_time_ns"] < cutoff else "LOADED_AFTER_OBSERVATION"
    else:
        funding = None
        summary["observation_status"] = "UNLOADED_AT_SIMULATION_END"
    if not packet.fill or packet.fill["_time_ns"] >= cutoff:
        summary["queue_age_at_observation_end_ns"] = max(0, cutoff - enqueue["_time_ns"])

    for frame in relevant:
        state = run.lines[frame["state_log_line"]]
        if frame is funding:
            relation = "FUNDING_FRAME"
        elif funding and frame["state_log_line"] > funding["state_log_line"]:
            relation = "FUNDED_AWAITING_FILL"
        else:
            relation = "WAITING_FRAME"
        decision_fields = _frame_fields(frame)
        if frame.get("report_log_line") is not None:
            rx = run.lines[frame["report_log_line"]]
            report_values, _ = add_report(rx, "REPORT_VISIBLE_AT_DECISION")
            decision_fields.update(report_values, report_age_ns=frame["report_age_ns"])

        diagnostics = []
        queued_at_decision = status(state["_index"]) == "FIFO_WAITING"
        if queued_at_decision:
            summary["waited_decisions"] += 1
        if queued_at_decision and relation == "WAITING_FRAME":
            summary["pre_funding_decisions"] += 1
            _, prefix = allocation.fifo_prefix(packet, state["_index"])
            subject_beyond_previous_budget = prefix > frame["unloaded_committed_bytes"]
            known = frame["known_demand_bytes"]
            outstanding_skip = frame["budget_outstanding"] and frame["granted_bytes"] == 0
            if outstanding_skip:
                summary["awaiting_settlement_frames"] += 1
                diagnostics.append("OUTSTANDING_BUDGET_NO_NEW_GRANT")
                if known > 0 and subject_beyond_previous_budget:
                    summary["positive_known_subject_uncovered_outstanding_frames"] += 1
                    diagnostics.append("POSITIVE_KNOWN_WITH_UNCOVERED_PACKET_AWAITING_PREVIOUS_SETTLEMENT")
            reason = frame.get("assured_reason")
            if reason == "NOT_DUE":
                summary["not_due_frames"] += 1
                diagnostics.append("SI_NOT_DUE")
            if reason in {"NO_CAPACITY", "PARTIAL_CAPACITY"}:
                summary["no_capacity_frames"] += 1
                diagnostics.append(reason)
            if known == -1:
                summary["unknown_frames"] += 1
                diagnostics.append("REPORT_UNKNOWN")
            elif known < prefix:
                summary["known_prefix_short_frames"] += 1
                diagnostics.append("EFFECTIVE_KNOWN_BELOW_FIFO_PREFIX")
            if frame["offline_backlog_gt_known"]:
                summary["uncovered_fifo_gt_known_frames"] += 1
                diagnostics.append("OFFLINE_UNCOVERED_FIFO_GT_KNOWN")
                if known == 0 and subject_beyond_previous_budget:
                    summary["zero_known_subject_uncovered_frames"] += 1
                    diagnostics.append("ZERO_KNOWN_WITH_PACKET_BEYOND_PREVIOUS_BUDGET")
        elif not queued_at_decision:
            diagnostics.append("FUNDING_PLAN_PRECEDES_FIFO_ENQUEUE")
        elif relation == "FUNDED_AWAITING_FILL":
            diagnostics.append("SUBJECT_ALREADY_HAS_FUNDING_GRANT")
        add(state, relation, ";".join(diagnostics), **decision_fields)
        policy = run.lines.get(frame.get("decision_log_line")) or frame["_multi_plan"]
        if policy:
            add(policy, relation, ";".join(diagnostics), **decision_fields)
        if not frame.get("grant_id"):
            continue
        grant = run.grants[frame["grant_id"]]
        fill = grant["PON_DBA_FILL"]
        selected = _selected_ids(fill)
        earlier = [selected_id for selected_id in selected if run.packets[selected_id].position < packet.position]
        waiting_at_fill = status(fill["_index"]) == "FIFO_WAITING"
        strictly_ahead_fill = waiting_at_fill and pid not in selected and bool(earlier)
        cap_full_ahead = strictly_ahead_fill and frame.get("authorized_at_cap", False) and (
            int(fill["selected_bytes"]) == frame["abmin_bytes_per_sfu"])
        summary["earlier_fills_loading_predecessors"] += strictly_ahead_fill
        summary["full_cap_fills_ahead"] += cap_full_ahead
        if cap_full_ahead and "first_exact_evidence_line" not in summary:
            summary["first_exact_evidence_line"] = fill["_index"]

        for event in grant.values():
            event_fields = dict(target_frame_id=frame["target_frame_id"], grant_id=frame["grant_id"],
                                planned_grant_bytes=int(event["budget_bytes"]))
            detail = ""
            if event["event"] == "PON_DBA_FILL":
                event_fields.update(fill_selected_packet_ids=fill["selected_packet_ids"],
                                    sent_predecessor_ids=";".join(map(str, earlier)), fill_reason=fill["fill_reason"],
                                    remaining_after_fill_packets=int(fill["remaining_packets"]))
                if cap_full_ahead:
                    detail = "CONFIGURED_ABMIN_FULLY_LOADED_BY_FIFO_PREDECESSORS"
                elif strictly_ahead_fill:
                    detail = "THIS_FILL_SELECTED_FIFO_PREDECESSORS_AND_LEFT_PACKET_QUEUED"
                elif pid in selected:
                    detail = "SUBJECT_PACKET_LOADED"
            if event["event"] == "PON_DBA_SETTLEMENT_RX":
                event_fields.update(actual_grant_bytes=int(event["actual_bytes"]), unused_grant_bytes=int(event["unused_bytes"]))
            add(event, relation, detail, **event_fields)

    for event in (packet.data_start, packet.data_end, packet.delivered):
        if event:
            add(event, "SUBJECT_PACKET", target_frame_id=summary.get("target_frame_id"), grant_id=summary.get("grant_id"))
    return summary, [rows[index] for index in sorted(rows)]


def compare_tail(giant: T2PacketRun, rr: T2PacketRun, others: Iterable[T2PacketRun] = ()) -> tuple[list[dict], list[dict]]:
    """Take the matched baseline P99 union. Threshold ties are retained in both input samples."""
    giant_p99, giant_n, giant_ids = _tail(giant)
    rr_p99, rr_n, rr_ids = _tail(rr)
    samples, events = [], []
    for run in (giant, rr, *others):
        require(run.case.seed == giant.case.seed and run.packets.keys() == giant.packets.keys(),
                "T2 tail cases do not contain the same optical FIFO input IDs")
        for pid in sorted(giant_ids | rr_ids):
            packet = run.packets[pid]
            expected = giant.packets[pid].enqueue
            require(all(packet.enqueue[key] == expected[key] for key in ("allocation_id", "sfu_id", "packet_bytes", "_time_ns")),
                    f"T2 tail cases disagree on optical enqueue for packet {pid}")
            summary, trace = _packet_trace(run, pid)
            summary.update(giant_si1_tail=pid in giant_ids, rr_tail=pid in rr_ids, giant_si1_p99_ns=giant_p99,
                           rr_p99_ns=rr_p99, giant_si1_loaded_sample_n=giant_n, rr_loaded_sample_n=rr_n)
            samples.append({column: summary.get(column) for column in T2_TAIL_SUMMARY_COLUMNS})
            events.extend({column: row.get(column) for column in T2_TAIL_EVENT_COLUMNS} for row in trace)
    return samples, events


def tail_outputs(indices: Iterable[T2PacketRun]) -> tuple[list[dict], list[dict]]:
    """Return event rows, then matched packet-summary rows, for all paired seeds."""
    by_seed: dict[int, dict[str, T2PacketRun]] = defaultdict(dict)
    for run in indices:
        name = _variant(run)
        require(name not in by_seed[run.case.seed], "Duplicate T2 tail case for the same seed and variant")
        by_seed[run.case.seed][name] = run
    events, samples = [], []
    for seed, runs in sorted(by_seed.items()):
        require({"giant-si1", "rr"} <= runs.keys(), f"Missing a T2 tail baseline for seed {seed}")
        giant, rr = runs.pop("giant-si1"), runs.pop("rr")
        selected, trace = compare_tail(giant, rr, runs.values())
        events.extend(trace)
        samples.extend(selected)
    return events, samples
