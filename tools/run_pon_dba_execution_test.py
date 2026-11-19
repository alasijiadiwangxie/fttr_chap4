"""实际运行真实 RR-DBA，导出逐事件闭环和逐预算对账，并复核旧模式。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, require, write_csv
from run_pon_dba_shadow_test import execute
from run_pon_grant_fill_test import verify_protocol_regression


TRACE_COLUMNS = (
    "scenario_id", "event_index", "time_ns", "node", "event", "grant_id", "packet_id",
    "snapshot_time_ns", "pending_bytes", "budget_bytes", "selected_bytes", "actual_bytes",
    "unused_bytes", "effective_known_bytes", "raw_event",
    "target_frame_id", "decision_reason", "fill_reason", "selected_packet_ids",
    "selected_duration_ns", "unused_duration_ns", "execution_state",
)
ACCOUNT_COLUMNS = (
    "scenario_id", "grant_id", "allocation_id", "sfu_id", "grant_create_ns", "bwmap_receive_ns",
    "slot_start_ns", "slot_end_ns", "settlement_receive_ns", "budget_bytes", "selected_bytes",
    "actual_bytes", "unused_bytes", "reserved_ns", "actual_data_ns", "unused_duration_ns",
    "known_before_create", "known_after_create", "known_before_settlement", "known_after_settlement",
    "settlement_report_snapshot_ns", "pending_bytes_after_settlement",
)


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


def check_and_account(scenario: str, log: str) -> tuple[list[dict], list[dict]]:
    trace = parse(log)
    grants = events(trace, "PON_DBA_BWMAP_TX")
    require(len({row["grant_id"] for row in grants}) == len(grants), "Duplicate budget identity")
    require(not events(trace, "PON_DEMAND_LEDGER_GRANT"), "Budget DATA incorrectly created packet debits")
    delivered = events(trace, "DELIVERED")
    require(len({row["packet_id"] for row in delivered}) == len(delivered), "Duplicate delivery")
    accounts = []
    for tx in grants:
        grant_id = tx["grant_id"]
        group = [row for row in trace if row.get("grant_id") == grant_id]
        rx = only(group, "PON_DBA_BWMAP_RX")
        fill = only(group, "PON_DBA_FILL")
        settled = only(group, "PON_DBA_SETTLEMENT_RX")
        slot_end = only(group, "PON_DBA_SLOT_END")
        created = only(group, "PON_DBA_LEDGER_CREATED")
        budget = int(tx["budget_bytes"])
        actual = int(settled["actual_bytes"])
        unused = int(settled["unused_bytes"])
        duration = int(tx["tx_end_ns"]) - int(tx["tx_start_ns"])
        actual_ns = int(fill["selected_duration_ns"])
        require(budget == actual + unused and 0 <= actual <= budget, "Budget/actual/unused do not reconcile")
        require(int(fill["selected_bytes"]) == actual, "Selected bytes did not complete exactly once")
        require(actual_ns + int(fill["unused_duration_ns"]) == duration, "Slot time does not reconcile")
        require(int(tx["time_ns"]) <= int(rx["time_ns"]) < int(fill["time_ns"])
                == int(tx["tx_start_ns"]), "BWmap/loading causality failed")
        require(int(slot_end["time_ns"]) == int(tx["tx_end_ns"])
                < int(settled["time_ns"]), "Execution feedback did not traverse uplink propagation")
        starts = events(group, "PON_DBA_DATA_START")
        ends = events(group, "PON_DBA_DATA_END")
        require(len(starts) == len(ends), "Unfinished DATA")
        for start, end in zip(starts, ends):
            require(start["packet_id"] == end["packet_id"], "Packet completion identity mismatch")
            require(int(tx["tx_start_ns"]) <= int(start["time_ns"]) < int(end["time_ns"])
                    <= int(tx["tx_end_ns"]), "DATA outside its budget slot")
            delivery = [row for row in delivered if row["packet_id"] == start["packet_id"]]
            require(len(delivery) == 1 and delivery[0]["_time_ns"] >= int(end["time_ns"]),
                    "DATA lost, duplicated or delivered before completion")
        created_states = [row for row in trace if row.get("event") == "PON_DEMAND_LEDGER"
                          and row.get("trigger") == "DBA_BUDGET_CREATED"
                          and row.get("time_ns") == tx["time_ns"]]
        require(len(created_states) == 1, "Missing real demand accounting state")
        before = int(created["effective_before"])
        after = int(created["effective_after"])
        require(after == int(created_states[0]["effective_known_bytes"]), "Ledger log differs from actual state")
        require(after == max(0, before - budget), "Budget creation did not debit exactly once")
        accounts.append(dict(zip(ACCOUNT_COLUMNS, (
            scenario, grant_id, tx["allocation_id"], tx["sfu_id"], tx["time_ns"], rx["time_ns"],
            tx["tx_start_ns"], tx["tx_end_ns"], settled["time_ns"], budget, fill["selected_bytes"],
            actual, unused, duration, actual_ns, fill["unused_duration_ns"], before, after,
            settled["effective_before"], settled["effective_after"], settled["snapshot_time_ns"],
            settled["pending_bytes"],
        ))))
    if scenario == "success_1200":
        require(len(grants) == len(delivered) == 1, "1200 B closed loop not unique")
        require(accounts[0]["actual_bytes"] == 1200 and accounts[0]["unused_bytes"] == 0, "Wrong success budget")
        require(delivered[0]["_time_ns"] == 514070, "Wrong end-to-end delivery time")
    elif scenario == "head_blocked":
        require(len(grants) == 1 and not delivered, "Blocked head was incorrectly transmitted")
        require(accounts[0]["budget_bytes"] == accounts[0]["unused_bytes"] == 38998,
                "Unused budget not preserved")
        require(accounts[0]["known_after_settlement"] == "39062", "Known head demand was lost")
    elif scenario == "partial_rounding":
        require(len(grants) == len(delivered) == 2, "Partial budget did not resume correctly")
        require(sum(row["actual_bytes"] for row in accounts) == 2
                and sum(row["unused_bytes"] for row in accounts) == 1, "Partial rounding accounting failed")
    elif scenario == "unknown_window":
        require(not grants and not delivered, "UNKNOWN state generated real DATA")
    summary = [row for row in trace if row.get("summary") == "PON_DBA_EXECUTION"]
    require(len(summary) == 1, "Missing real DBA summary")
    for key in ("budget_bytes", "actual_bytes", "unused_bytes"):
        require(int(summary[0][key]) == sum(int(row[key]) for row in accounts), "Summary differs from grants")
    rows = []
    for row in trace:
        action = row.get("event", "")
        if action.startswith(("PON_DBA_", "PON_POLL_", "PON_UPLINK_REPORT", "PON_DEMAND_LEDGER")) \
                or action in {"FIBER_TX_START", "FIBER_TX_END", "DELIVERED"}:
            rows.append({**{key: row.get(key, "") for key in TRACE_COLUMNS},
                         "scenario_id": scenario, "event_index": row["_index"], "time_ns": row["_time_ns"]})
    return rows, accounts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-dba-execution")
    args = parser.parse_args()
    settings = (ROOT / "results/pon-dba-execution/test-config.properties").read_text(encoding="utf-8")
    cases = {
        "success_1200": settings,
        "head_blocked": settings.replace("packet.bytes=1200", "packet.bytes=39062"),
        "partial_rounding": settings.replace("packet.bytes=1200", "packet.bytes=1")
            .replace("packet.arrival.us=0", "packet.arrival.us=0,0")
            .replace("poll.end.us=625", "poll.end.us=1000"),
        "unknown_window": settings.replace("poll.end.us=625", "poll.end.us=250"),
    }
    rows, accounts = [], []
    with TemporaryDirectory(prefix="fttr-dba-execution-") as temporary:
        directory = Path(temporary)
        verify_protocol_regression(args.jar.resolve(), directory)
        for scenario, config in cases.items():
            actual = execute(args.jar.resolve(), directory / f"{scenario}.properties", config)
            # 第13步的逐对象观测不改变第12步执行。保留原导出的行/索引，使旧复现命令仍稳定。
            legacy = "".join(line for line in actual.splitlines(keepends=True)
                             if "event=PON_DBA_OBJECT_STATE" not in line.split())
            trace, accounting = check_and_account(scenario, legacy)
            rows.extend(trace)
            accounts.extend(accounting)
    args.output.mkdir(parents=True, exist_ok=True)
    for scenario, config in cases.items():
        filename = "test-config.properties" if scenario == "success_1200" else f"{scenario}.properties"
        (args.output / filename).write_text(config, encoding="utf-8")
    write_csv(args.output / "execution-trace.csv", TRACE_COLUMNS, rows)
    write_csv(args.output / "grant-accounting.csv", ACCOUNT_COLUMNS, accounts)
    print(f"Verified {len(cases)} real Java scenarios, {len(accounts)} executed budgets and {len(rows)} trace rows.")
    print("FCFS, shadow RR and prior policy/polling traces unchanged; all runs replay identically.")


if __name__ == "__main__":
    main()
