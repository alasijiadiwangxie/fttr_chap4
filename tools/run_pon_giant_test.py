"""Run the Java GIANT and RR policies with matching traffic and export their allocations."""

from __future__ import annotations

import argparse
from collections import defaultdict
from decimal import Decimal, ROUND_CEILING
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_dba_execution_test import events, only, parse
from run_pon_dba_shadow_test import execute
from run_pon_frame_test import ROOT, require, write_csv
from run_pon_tcont_test import PERIOD_NS, check_windows, configuration, expected_owner


DECISION_COLUMNS = (
    "scenario_id", "time_ns", "target_frame_id", "allocation_id", "sfu_id", "type",
    "si_max_frames", "si_min_frames", "si_max_before", "si_max_after", "si_min_before", "si_min_after",
    "ab_min_bytes", "ab_sur_bytes", "assured_bytes", "surplus_bytes", "budget_bytes", "duration_ns",
    "assured_reason", "surplus_reason", "state", "known_demand_bytes", "budget_outstanding",
    "report_frame_id", "report_age_ns", "control_reserved_ns", "grant_id",
)
EXECUTION_COLUMNS = (
    "scenario_id", "policy", "target_frame_id", "allocation_id", "sfu_id", "grant_id",
    "bwmap_tx_ns", "bwmap_rx_ns", "slot_start_ns", "slot_end_ns", "settlement_rx_ns",
    "budget_bytes", "assured_bytes", "surplus_bytes", "selected_bytes", "actual_bytes", "unused_bytes",
    "reservation_ns", "actual_data_ns", "unused_duration_ns", "selected_packet_ids",
    "first_data_tx_ns", "last_data_tx_end_ns", "last_delivery_ns", "fill_reason",
    "known_before_grant", "known_after_grant", "known_after_settlement",
)


def override(settings: str, overrides: dict[str, str | int]) -> str:
    """Preserve the tested config as one property per key, including modified profiles."""
    lines = [line for line in settings.splitlines() if line and not line.startswith("#")]
    values = dict(line.split("=", 1) for line in lines)
    values.update({key: str(value) for key, value in overrides.items()})
    return "# Manual arrivals used for both policies. Times are in us.\n" + "".join(
        f"{key}={value}\n" for key, value in values.items()
    )


def scenarios() -> dict[str, str]:
    # Type 3 has independent ready counters and demand of at least ABmin + ABsur.
    # Deliberately set Type 4's reserved ABmin to a positive value to check that GIANT never
    # interprets the reserved field as assured DATA.
    base = configuration(2, [
        (1, 1200, 0, "VO"), (1, 1200, 0, "VO"), (1, 1200, 0, "VO"),
        (1, 1200, 0, "BE"), (1, 1200, 0, "BE"), (1, 1200, 0, "BE"),
        (2, 1200, 0, "BK"), (2, 1200, 0, "BK"), (2, 1200, 0, "BK"),
    ], "giant")
    mixed = override(base, {
        "fiber.uplink.tcont.alloc.101.si.max.frames": 2,
        "fiber.uplink.tcont.alloc.101.ab.min.bytes": 1200,
        "fiber.uplink.tcont.alloc.102.si.min.frames": 1,
        "fiber.uplink.tcont.alloc.102.ab.min.bytes": 1200,
        "fiber.uplink.tcont.alloc.201.si.min.frames": 4,
        "fiber.uplink.tcont.alloc.201.ab.min.bytes": 1200,
        "fiber.uplink.poll.end.us": 3500,
    })
    # A slower line leaves less room than the sum of ready quotas. Keep the real
    # REPORT/BWmap handling and measure selection under a shared capacity limit.
    capacity = override(mixed, {"fiber.uplink.rate.mbps": 144,
                                "fiber.uplink.tcont.alloc.101.si.max.frames": 1,
                                "fiber.uplink.tcont.alloc.201.si.min.frames": 1})
    late = override(configuration(1, [
        (1, 1200, 0, "VO"), (1, 600, 0, "VO"), (1, 1200, 0, "BE"),
    ], "giant"), {
        "fiber.uplink.tcont.alloc.101.ab.min.bytes": 1000,
        "fiber.uplink.tcont.alloc.101.si.max.frames": 2,
        "fiber.uplink.propagation.us": "135",
        "fiber.uplink.poll.end.us": 1800,
    })
    return {
        "mixed_giant": mixed,
        "mixed_rr": override(mixed, {"fiber.uplink.dba.mode": "rr-multi"}),
        "frame_capacity": capacity,
        "delayed_report_and_unused_budget": late,
    }


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


def check_counter(row: dict, counter: str, reason: str, phase_bytes: int, active: bool,
                  previous_after: dict[tuple[str, str], int]) -> None:
    before = int(row[f"si_{counter}_before"])
    after = int(row[f"si_{counter}_after"])
    interval = int(row[f"si_{counter}_frames"])
    key = (row["allocation_id"], counter)
    if not active:
        require(reason == "INACTIVE" and before == after and phase_bytes == 0, "Inactive phase consumed quota")
        return
    require(before == previous_after.get(key, interval) and 1 <= before <= interval,
            f"{key}: before counter disagrees with initialization or preceding frame")
    if before > 1:
        require(reason == "NOT_DUE" and after == before - 1 and phase_bytes == 0, f"{key}: nonexpired timer granted")
    else:
        expected_after = interval if counter == "max" or phase_bytes > 0 else 1
        require(after == expected_after and reason not in ("INACTIVE", "NOT_DUE"), f"{key}: expired timer state differs")
        if phase_bytes > 0:
            require(reason in ("GRANTED", "PARTIAL_CAPACITY"), f"{key}: granted without a valid reason")
    previous_after[key] = after


def read_decisions(name: str, settings: dict[str, str], trace: list[dict], execution: list[dict]) -> list[dict]:
    owners = expected_owner("\n".join(f"{key}={value}" for key, value in settings.items()))
    row_by_grant = {(row["target_frame_id"], row["allocation_id"]): row for row in execution}
    context = {(row["target_frame_id"], row["allocation_id"]): row
               for row in events(trace, "PON_DBA_OBJECT_STATE")}
    raw_decisions = events(trace, "PON_GIANT_DECISION")
    decided_keys = {(row["target_frame_id"], row["allocation_id"]) for row in raw_decisions}
    require(bool(context) and len(raw_decisions) == len(decided_keys) and decided_keys == context.keys(),
            f"{name}: missing or duplicate per-object decisions")
    control = {row["target_frame_id"]: int(row["tx_end_ns"]) - int(row["tx_start_ns"])
               for row in events(trace, "PON_POLL_BWMAP_TX")}
    previous_after: dict[tuple[str, str], int] = {}
    by_frame: dict[str, list[dict]] = defaultdict(list)
    rows = []
    for row in raw_decisions:
        allocation, frame = row["allocation_id"], row["target_frame_id"]
        tcont_type = int(row["type"])
        assured, surplus, budget = (int(row[field]) for field in ("assured_bytes", "surplus_bytes", "budget_bytes"))
        require(row["sfu_id"] == owners[allocation], f"{name}: mismatched SFU owner")
        prefix = f"fiber.uplink.tcont.alloc.{allocation}."
        for field, key in (("type", "type"), ("si_max_frames", "si.max.frames"), ("si_min_frames", "si.min.frames"),
                           ("ab_min_bytes", "ab.min.bytes"), ("ab_sur_bytes", "ab.sur.bytes")):
            require(row[field] == settings[prefix + key], f"{name}: profile {key} not passed to policy")
        before = context[(frame, allocation)]
        for field in ("time_ns", "state", "known_demand_bytes", "report_frame_id", "report_age_ns",
                      "budget_outstanding"):
            require(row[field] == before[field], f"{name}: decision changed policy input {field}")
        check_counter(row, "max", row["assured_reason"], assured, tcont_type in (2, 3), previous_after)
        check_counter(row, "min", row["surplus_reason"], surplus, tcont_type in (3, 4), previous_after)
        require(0 <= assured <= int(row["ab_min_bytes"]) and 0 <= surplus <= int(row["ab_sur_bytes"])
                and budget == assured + surplus <= max(0, int(row["known_demand_bytes"])),
                f"{name}: exceeded an independent phase quota or total known demand")
        if row["state"] == "UNKNOWN" or row["budget_outstanding"] == "true":
            require(budget == 0, f"{name}: granted UNKNOWN or unsettled demand")
        require(int(row["control_reserved_ns"]) == control.get(frame, 0), f"{name}: wrong control reservation")
        executed = row_by_grant.get((frame, allocation))
        if budget > 0:
            require(executed is not None and executed["budget_bytes"] == budget and
                    int(row["duration_ns"]) == executed["reservation_ns"], f"{name}: decision did not become one grant")
            executed.update(assured_bytes=assured, surplus_bytes=surplus)
        else:
            require(executed is None and int(row["duration_ns"]) == 0, f"{name}: empty plan reserved time")
        rows.append(dict(row, scenario_id=name, grant_id=executed["grant_id"] if executed else ""))
        by_frame[frame].append(row)
    require(all("assured_bytes" in executed for executed in execution), f"{name}: untracked budget")
    for frame, decisions in by_frame.items():
        require({row["allocation_id"] for row in decisions} == owners.keys(), f"{name}: lost an idle allocation")
        require(sum(int(row["duration_ns"]) for row in decisions) + control.get(frame, 0) <= PERIOD_NS,
                f"{name}: control and DATA exceed frame capacity")
        # A capacity-limited earlier phase consumes all whole bytes left. Lower phases
        # cannot take a separate slot while it is short; an existing merged grant can
        # still reuse its nanosecond rounding slack.
        scarce_assured = any(row["assured_reason"] in ("NO_CAPACITY", "PARTIAL_CAPACITY") for row in decisions)
        scarce_type3_surplus = any(row["type"] == "3" and row["surplus_reason"] in
                                   ("NO_CAPACITY", "PARTIAL_CAPACITY") for row in decisions)
        for row in decisions:
            if (row["type"] == "4" and scarce_type3_surplus) or (scarce_assured and int(row["assured_bytes"]) == 0):
                require(int(row["surplus_bytes"]) == 0, f"{name}: lower phase took scarce capacity")
    return rows


def check_scenario(name: str, decisions: list[dict], execution: list[dict], trace: list[dict]) -> None:
    delivered = events(trace, "DELIVERED")
    queued = events(trace, "TCONT_ENQUEUE")
    if name in ("mixed_rr", "mixed_giant", "frame_capacity"):
        require(len(delivered) == len(queued) == 9, f"{name}: ordinary traffic did not drain")
    if name == "mixed_giant":
        require(any(row["type"] == "2" and int(row["assured_bytes"]) > 0 for row in decisions),
                "Type 2 did not receive assured service")
        require(any(row["type"] == "3" and int(row["assured_bytes"]) > 0 and int(row["surplus_bytes"]) > 0
                    for row in decisions), "Type 3 did not exercise combined service")
        require(any(row["type"] == "4" and int(row["surplus_bytes"]) > 0 for row in decisions),
                "Type 4 did not receive surplus service")
    elif name == "frame_capacity":
        require(any("PARTIAL_CAPACITY" in (row["assured_reason"], row["surplus_reason"])
                    or "NO_CAPACITY" in (row["assured_reason"], row["surplus_reason"]) for row in decisions),
                "Capacity scenario did not constrain requests")
    elif name == "delayed_report_and_unused_budget":
        require(any(row["assured_reason"] == "UNKNOWN" for row in decisions)
                and any(row["assured_reason"] == "AWAITING_SETTLEMENT" for row in decisions),
                "Late-report scenario did not observe an expired timer in UNKNOWN and outstanding states")
        blocked = [row for row in execution if row["allocation_id"] == "101"]
        require(blocked and all(row["budget_bytes"] == row["unused_bytes"] == 1000
                                and int(row["known_after_settlement"]) == 1800 for row in blocked),
                "Blocked whole packet did not preserve unused quota and demand")
        require([row["packet_id"] for row in delivered] == ["3"], "Blocked FIFO affected data on another allocation")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-giant")
    args = parser.parse_args()
    cases, logs, decisions, executions = scenarios(), {}, {}, {}
    with TemporaryDirectory(prefix="fttr-giant-") as temporary:
        folder = Path(temporary)
        for name, config in cases.items():
            settings = dict(line.split("=", 1) for line in config.splitlines() if line and not line.startswith("#"))
            logs[name] = execute(args.jar.resolve(), folder / f"{name}.properties", config)
            trace = parse(logs[name])
            verify_report_ledger(trace)
            executions[name] = read_execution(name, settings, trace)
            if settings["fiber.uplink.dba.mode"] == "giant":
                decisions[name] = read_decisions(name, settings, trace, executions[name])
            else:
                require(not events(trace, "PON_GIANT_DECISION"), "RR emitted GIANT-only decisions")
            check_scenario(name, decisions.get(name, []), executions[name], trace)
        signature = lambda rows: [(row["target_frame_id"], row["allocation_id"], row["budget_bytes"]) for row in rows]
        require(signature(executions["mixed_giant"]) != signature(executions["mixed_rr"]),
                "GIANT and RR produced identical allocations for the same arrivals")
    args.output.mkdir(parents=True, exist_ok=True)
    for name, config in cases.items():
        filename = "test-config.properties" if name == "mixed_giant" else f"{name}.properties"
        (args.output / filename).write_text(config, encoding="utf-8")
        (args.output / f"{name}-events.log").write_text(logs[name], encoding="utf-8")
    decision_rows = [row for rows in decisions.values() for row in rows]
    execution_rows = [row for rows in executions.values() for row in rows]
    write_csv(args.output / "giant-decision-trace.csv", DECISION_COLUMNS,
              [{key: row.get(key, "") for key in DECISION_COLUMNS} for row in decision_rows])
    write_csv(args.output / "grant-execution.csv", EXECUTION_COLUMNS,
              [{key: row.get(key, "") for key in EXECUTION_COLUMNS} for row in execution_rows])
    print(f"Verified {len(cases)} Java runs, {len(decision_rows)} Alloc-ID decisions and {len(execution_rows)} BWmap budgets.")
    for name, rows in executions.items():
        print(f"{name}: {len(rows)} budgets, actual={sum(row['actual_bytes'] for row in rows)} B, "
              f"unused={sum(row['unused_bytes'] for row in rows)} B")


if __name__ == "__main__":
    main()
