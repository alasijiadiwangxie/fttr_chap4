"""Read and reconcile PON execution and REPORT events from Java traces."""

from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, ROUND_CEILING

from pon_experiment_io import require


PERIOD_NS = 125000


def fields(line: str) -> dict[str, str]:
    return dict(token.split("=", 1) for token in line.split() if "=" in token)


def events(trace: list[dict], action: str) -> list[dict]:
    return [row for row in trace if row.get("event") == action]


def only(trace: list[dict], action: str) -> dict:
    found = events(trace, action)
    require(len(found) == 1, f"Expected exactly one {action}, got {len(found)}")
    return found[0]


def parse(log: str) -> list[dict]:
    """记录日志执行顺序。无独立事件时间的本地快照沿用当前时钟，原snapshot另列保存。"""
    result = []
    now = 0
    for index, line in enumerate(log.splitlines(), 1):
        row = fields(line)
        if "time_ns" in row:
            now = int(row["time_ns"])
        elif "t_us" in row:
            now = int(Decimal(row["t_us"]) * 1000)
        elif "boundary_time_ns" in row:
            now = int(row["boundary_time_ns"])
        row.update(_time_ns=now, _index=index, raw_event=line)
        result.append(row)
    return result


def check_windows(trace: list[dict]) -> None:
    windows = events(trace, "PON_DBA_BWMAP_TX") + events(trace, "PON_POLL_BWMAP_TX")
    previous_end = 0
    for row in sorted(windows, key=lambda item: int(item["tx_start_ns"])):
        start, end, frame = (int(row[field]) for field in ("tx_start_ns", "tx_end_ns", "target_frame_id"))
        require(start >= previous_end and frame * PERIOD_NS <= start < end <= (frame + 1) * PERIOD_NS,
                "Alloc-ID windows overlap or exceed the shared frame")
        previous_end = end


def same_identity(row: dict, other: dict) -> bool:
    return all(row[field] == other[field] for field in ("allocation_id", "sfu_id", "target_frame_id"))


def read_execution(name: str, settings: dict[str, str], trace: list[dict]) -> list[dict]:
    """Join tracked budget/packet events and check causal ordering and byte conservation."""
    groups: dict[str, list[dict]] = defaultdict(list)
    for row in trace:
        if "grant_id" in row:
            groups[row["grant_id"]].append(row)
    queue_rows = events(trace, "TCONT_ENQUEUE")
    queued = {row["packet_id"]: row for row in queue_rows}
    delivered_rows = events(trace, "DELIVERED")
    delivered = {row["packet_id"]: row for row in delivered_rows}
    require(len(queued) == len(queue_rows) and len(delivered) == len(delivered_rows),
            f"{name}: duplicate enqueue or delivery")
    sent = set()
    sent_per_allocation: dict[str, list[str]] = defaultdict(list)
    accounted_slots = set()
    last_settlement: dict[str, int] = {}
    results = []
    propagation_ns = int(Decimal(settings["fiber.uplink.propagation.us"]) * 1000)
    bwmap_propagation_ns = int(Decimal(settings["fiber.downlink.propagation.us"]) * 1000)
    rate_mbps = Decimal(settings["fiber.uplink.rate.mbps"])
    pipelined = settings["fiber.uplink.dba.mode"] == "giant-complete"
    for tx in events(trace, "PON_DBA_BWMAP_TX"):
        grant_id, allocation, frame = (tx[field] for field in ("grant_id", "allocation_id", "target_frame_id"))
        require((allocation, frame) not in accounted_slots, f"{name}: split or duplicate reservation")
        accounted_slots.add((allocation, frame))
        group = groups[grant_id]
        rx = only(group, "PON_DBA_BWMAP_RX")
        fill = only(group, "PON_DBA_FILL")
        settle = only(group, "PON_DBA_SETTLEMENT_RX")
        slot_end = only(group, "PON_DBA_SLOT_END")
        debit = only(group, "PON_DBA_LEDGER_CREATED")
        require(all(same_identity(tx, row) for row in group), f"{name}: budget identity changed in execution")
        start_ns, end_ns = int(tx["tx_start_ns"]), int(tx["tx_end_ns"])
        actual, planned, unused = (int(settle[key]) for key in ("actual_bytes", "budget_bytes", "unused_bytes"))
        require(int(rx["time_ns"]) == int(tx["time_ns"]) + bwmap_propagation_ns < start_ns
                and int(fill["time_ns"]) == start_ns and int(slot_end["time_ns"]) == end_ns
                and int(settle["time_ns"]) == end_ns + propagation_ns,
                f"{name}: invalid BWmap, transmit or settlement timing")
        if not pipelined:
            require(last_settlement.get(allocation, -1) < tx["_index"],
                    f"{name}: created a second reservation before the first one settled")
        last_settlement[allocation] = settle["_index"]
        require(planned == actual + unused and actual == int(fill["selected_bytes"])
                and int(fill["selected_duration_ns"]) + int(fill["unused_duration_ns"]) == end_ns - start_ns,
                f"{name}: unused budget does not reconcile")
        before, after = int(debit["effective_before"]), int(debit["effective_after"])
        expected_after = (-1 if before == -1 else max(0, before - planned)) if pipelined else before - planned
        require(after == expected_after,
                f"{name}: reservation debit does not reconcile")
        starts = events(group, "PON_DBA_DATA_START")
        ends = events(group, "PON_DBA_DATA_END")
        require([row["packet_id"] for row in starts] == [row["packet_id"] for row in ends],
                f"{name}: packet start/end identities differ")
        bytes_sent, elapsed_ns = 0, 0
        for start, end in zip(starts, ends):
            packet = start["packet_id"]
            require(packet not in sent, f"{name}: packet transmitted multiple times")
            require(queued[packet]["allocation_id"] == allocation
                    and delivered[packet]["sfu_id"] == tx["sfu_id"],
                    f"{name}: delivered packet on wrong allocation or SFU")
            sent.add(packet)
            sent_per_allocation[allocation].append(packet)
            data_start, data_end = int(start["time_ns"]), int(end["time_ns"])
            packet_bytes = int(queued[packet]["packet_bytes"])
            expected_ns = int((Decimal(packet_bytes * 8000) / rate_mbps).to_integral_value(rounding=ROUND_CEILING))
            require(start_ns <= data_start < data_end <= end_ns and data_end - data_start == expected_ns
                    and delivered[packet]["_time_ns"] == data_end + propagation_ns,
                    f"{name}: DATA timing disagrees with serialization, reservation or propagation")
            bytes_sent += packet_bytes
            elapsed_ns += expected_ns
        require(actual == bytes_sent and int(fill["selected_duration_ns"]) == elapsed_ns,
                f"{name}: completed packets disagree with settled byte/time counters")
        results.append({
            "scenario_id": name, "policy": settings["fiber.uplink.dba.mode"], "target_frame_id": frame,
            "allocation_id": allocation, "sfu_id": tx["sfu_id"], "grant_id": grant_id,
            "bwmap_tx_ns": tx["time_ns"], "bwmap_rx_ns": rx["time_ns"], "slot_start_ns": start_ns,
            "slot_end_ns": end_ns, "settlement_rx_ns": settle["time_ns"],
            "budget_bytes": planned, "actual_bytes": actual, "unused_bytes": unused,
            "selected_bytes": fill["selected_bytes"], "reservation_ns": end_ns - start_ns,
            "actual_data_ns": elapsed_ns, "unused_duration_ns": fill["unused_duration_ns"],
            "selected_packet_ids": fill["selected_packet_ids"], "fill_reason": fill["fill_reason"],
            "first_data_tx_ns": starts[0]["time_ns"] if starts else "",
            "last_data_tx_end_ns": ends[-1]["time_ns"] if ends else "",
            "last_delivery_ns": delivered[ends[-1]["packet_id"]]["_time_ns"] if ends else "",
            "known_before_grant": debit["effective_before"], "known_after_grant": debit["effective_after"],
            "known_after_settlement": settle["effective_after"],
        })
    for allocation, packets in sent_per_allocation.items():
        enqueued = [row["packet_id"] for row in queue_rows if row["allocation_id"] == allocation]
        require(packets == enqueued[:len(packets)], f"{name}: DATA bypassed an Alloc-ID FIFO head")
    require(sent == set(delivered), f"{name}: send and delivery sets differ")
    totals = [row for row in trace if row.get("summary") == "PON_DBA_EXECUTION"]
    require(len(totals) == 1 and int(totals[0]["pending_packets"]) == len(queued) - len(sent),
            f"{name}: queued packet count differs")
    for field in ("budget_bytes", "actual_bytes", "unused_bytes"):
        require(int(totals[0][field]) == sum(row[field] for row in results), f"{name}: {field} total differs")
    check_windows(trace)
    return results


def check_latest_report(states: dict, reports: dict, allocation: str, now: int) -> None:
    report = reports.get(allocation)
    if report is None:
        require(states["state"] == "UNKNOWN" and int(states["known_demand_bytes"]) == -1,
                "MFU read unreported demand")
    else:
        require(states["state"] == "KNOWN" and states["report_frame_id"] == report["frame_id"]
                and int(states["report_age_ns"]) == now - int(report["snapshot_time_ns"]),
                "MFU used the wrong REPORT snapshot")


def verify_report_ledger(trace: list[dict]) -> None:
    """At every decision, reconcile demand using preceding received reports and grant events."""
    reports: dict[str, dict] = {}
    ledger: dict[str, list[dict]] = defaultdict(list)
    settled: dict[str, int] = {}
    for row in trace:
        action = row.get("event")
        allocation = row.get("allocation_id")
        if action == "TCONT_REPORT_RX" and row["applied"] == "true":
            reports[allocation] = row
        elif action == "PON_DBA_BWMAP_TX":
            ledger[allocation].append(row)
        elif action == "PON_DBA_SETTLEMENT_RX":
            settled[row["grant_id"]] = int(row["actual_bytes"])
        elif action == "PON_DBA_OBJECT_STATE":
            check_latest_report(row, reports, allocation, int(row["time_ns"]))
            history = ledger[allocation]
            outstanding = any(budget["grant_id"] not in settled for budget in history)
            require(outstanding == (row["budget_outstanding"] == "true"), "Outstanding state disagrees with event order")
            if allocation in reports:
                report = reports[allocation]
                debits = sum(settled.get(budget["grant_id"], int(budget["budget_bytes"])) for budget in history
                             if int(report["snapshot_time_ns"]) < int(budget["tx_start_ns"]))
                expected = max(0, int(report["pending_bytes"]) - debits)
                require(int(row["known_demand_bytes"]) == expected, "REPORT and execution ledger do not reconcile")
