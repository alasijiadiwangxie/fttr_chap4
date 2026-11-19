"""运行真实双 SFU RR-DBA，导出逐帧选择及各 SFU 账本，并核对第12步归档。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal, ROUND_FLOOR
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, require, write_csv
from run_pon_dba_shadow_test import execute
from run_pon_dba_execution_test import (
    ACCOUNT_COLUMNS, TRACE_COLUMNS, check_and_account, events, only, parse,
)
from run_pon_grant_fill_test import verify_protocol_regression


RR_COLUMNS = (
    "scenario_id", "decision_time_ns", "target_frame_id", "selected_allocation_id", "selected_sfu_id",
    "decision_reason", "sfu_1_state", "sfu_1_known_bytes", "sfu_1_outstanding", "sfu_2_state",
    "sfu_2_known_bytes", "sfu_2_outstanding", "grant_id", "budget_bytes", "planned_duration_ns",
    "bwmap_receive_ns", "slot_start_ns", "slot_end_ns", "selected_packet_ids", "actual_bytes",
    "unused_bytes", "actual_data_ns", "unused_duration_ns", "fill_reason", "delivery_packet_ids",
    "delivery_times_ns", "settlement_receive_ns",
)
LEDGER_COLUMNS = (
    "scenario_id", "event_index", "time_ns", "node", "event", "sfu_id", "allocation_id", "state",
    "known_demand_bytes", "budget_outstanding", "known_pending_bytes", "reported_pending_bytes",
    "effective_known_bytes", "granted_bytes_since_snapshot", "report_frame_id", "report_snapshot_ns",
    "report_receive_ns", "report_age_ns", "grant_id", "budget_bytes", "selected_bytes", "actual_bytes",
    "unused_bytes", "effective_before", "effective_after", "trigger", "report_applied", "raw_event",
)


def csv_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def configuration(packets: list[tuple[int, int]], end_us: int = 1250, rate_mbps: int = 2500) -> str:
    """场景使用实际手动输入；每个包的目标 STA 也就是其上行源 STA。"""
    base = (ROOT / "results/pon-dba-execution/test-config.properties").read_text(encoding="utf-8")
    settings = dict(line.split("=", 1) for line in base.splitlines() if line and not line.startswith("#"))
    settings.update({
        "sfu.count": "2", "sta.count": "2", "sta.1.sfu.id": "1", "sta.2.sfu.id": "2",
        "packet.arrival.us": ",".join("0" for _ in packets), "packet.bytes": "1200",
        "fiber.uplink.poll.end.us": str(end_us), "fiber.uplink.rate.mbps": str(rate_mbps),
    })
    for index, (sfu, packet_bytes) in enumerate(packets, 1):
        settings[f"packet.{index}.sta.id"] = str(sfu)
        settings[f"packet.{index}.bytes"] = str(packet_bytes)
    return "# Actual deterministic dual-SFU RR-DBA experiment; no random seed.\n" + "".join(
        f"{key}={value}\n" for key, value in settings.items()
    )


def scenarios() -> dict[str, str]:
    return {
        "both_positive": configuration([(1, 20000), (2, 20000), (1, 20000), (2, 20000)]),
        "outstanding_skip": configuration([(1, 38000), (2, 1200), (1, 2000)]),
        "head_blocked": configuration([(1, 39062), (2, 1200)], 625),
        "known_zero": configuration([(2, 1200)], 875),
        # 2560 Mb/s: control=200 ns; 两条19968 B的数据各62400 ns，恰在625000 ns结束。
        "exact_boundary": configuration([(1, 19968), (2, 1200), (1, 19968)], 875, 2560),
    }


def without_object_states(log: str) -> str:
    return "".join(line for line in log.splitlines(keepends=True)
                   if "event=PON_DBA_OBJECT_STATE" not in line.split())


def check_step_twelve(jar: Path, temporary: Path) -> None:
    """新增观测行过滤后，原12步全部事件索引、原始日志与对账仍逐字段相同。"""
    historical = ROOT / "results/pon-dba-execution"
    rows, accounts = [], []
    for scenario, filename in (
        ("success_1200", "test-config.properties"), ("head_blocked", "head_blocked.properties"),
        ("partial_rounding", "partial_rounding.properties"), ("unknown_window", "unknown_window.properties"),
    ):
        settings = (historical / filename).read_text(encoding="utf-8")
        observed = without_object_states(execute(jar, temporary / f"history-{scenario}.properties", settings))
        trace, accounting = check_and_account(scenario, observed)
        rows.extend(trace)
        accounts.extend(accounting)
    for filename, columns, observed in (
        ("execution-trace.csv", TRACE_COLUMNS, rows), ("grant-accounting.csv", ACCOUNT_COLUMNS, accounts),
    ):
        normalized = [{key: str(row.get(key, "")) for key in columns} for row in observed]
        require(normalized == csv_rows(historical / filename), f"Step 12 historical output changed: {filename}")


def object_states(trace: list[dict], decision: dict) -> dict[int, dict]:
    found = [row for row in trace if row.get("event") == "PON_DBA_OBJECT_STATE"
             and row.get("time_ns") == decision["time_ns"]
             and row.get("target_frame_id") == decision["target_frame_id"]]
    require(len(found) == 2 and {row["sfu_id"] for row in found} == {"1", "2"},
            "Each decision must expose both independent SFU states")
    return {int(row["sfu_id"]): row for row in found}


def verify_windows(trace: list[dict]) -> None:
    """检查全部预约窗口，未使用DATA预算同样不能与另一窗口重复占用。"""
    windows = []
    for row in trace:
        if row.get("event") in {"PON_DBA_BWMAP_TX", "PON_POLL_BWMAP_TX"}:
            windows.append((int(row["tx_start_ns"]), int(row["tx_end_ns"]), row["grant_id"]))
    windows.sort()
    for earlier, later in zip(windows, windows[1:]):
        require(earlier[1] <= later[0], f"Reserved upstream windows overlap: {earlier} / {later}")


def verify_and_export(scenario: str, settings: str, log: str) -> tuple[list[dict], list[dict]]:
    trace = parse(log)
    # 复用实际预算接收、装载、完成、结算及唯一交付的生产事件核对。
    _, accounts = check_and_account(f"multi-{scenario}", log)
    by_grant = {row["grant_id"]: row for row in accounts}
    decisions = events(trace, "PON_DBA_DECISION")
    data_grants = events(trace, "PON_DBA_BWMAP_TX")
    require(len({row["target_frame_id"] for row in data_grants}) == len(data_grants),
            "More than one DATA budget was created for a target frame")
    verify_windows(trace)
    config = dict(line.split("=", 1) for line in settings.splitlines() if line and not line.startswith("#"))
    period_ns = int(Decimal(config["fiber.uplink.frame.period.us"]) * 1000)
    rate = Decimal(config["fiber.uplink.rate.mbps"])
    expected_cursor = 1
    rows = []
    for decision in decisions:
        states = object_states(trace, decision)
        for sfu, state in states.items():
            require(int(state["allocation_id"]) == 2 * sfu, "Logical allocation belongs to the wrong SFU")
            require(state["state"] in {"UNKNOWN", "KNOWN"}, "Unknown SFU status")
            require(state["budget_outstanding"] in {"true", "false"}, "Missing independent budget state")
            if state["state"] == "UNKNOWN":
                require(state["known_demand_bytes"] == "-1", "UNKNOWN was silently replaced by zero demand")
            else:
                require(int(state["known_demand_bytes"]) >= 0, "Negative known demand")
        capacity = int(((period_ns - int(decision["control_reserved_ns"])) * rate / 8000)
                       .to_integral_value(rounding=ROUND_FLOOR))
        candidates = []
        for sfu in (expected_cursor, 3 - expected_cursor):
            state = states[sfu]
            if state["state"] == "KNOWN" and int(state["known_demand_bytes"]) > 0 \
                    and state["budget_outstanding"] == "false":
                candidates.append(sfu)
        expected = candidates[0] if candidates and capacity > 0 else 0
        selected = int(decision["sfu_id"])
        require(selected == expected, f"Incorrect RR selection at {decision['time_ns']}: expected {expected}")
        matching = [row for row in data_grants if row["time_ns"] == decision["time_ns"]]
        require(len(matching) == (1 if selected else 0), "Decision did not produce exactly its valid DATA budget")
        row = {
            "scenario_id": scenario, "decision_time_ns": decision["time_ns"],
            "target_frame_id": decision["target_frame_id"], "selected_allocation_id": decision["allocation_id"],
            "selected_sfu_id": selected, "decision_reason": decision["decision_reason"],
            "sfu_1_state": states[1]["state"], "sfu_1_known_bytes": states[1]["known_demand_bytes"],
            "sfu_1_outstanding": states[1]["budget_outstanding"], "sfu_2_state": states[2]["state"],
            "sfu_2_known_bytes": states[2]["known_demand_bytes"], "sfu_2_outstanding": states[2]["budget_outstanding"],
            "budget_bytes": decision["planned_bytes"], "planned_duration_ns": decision["planned_duration_ns"],
        }
        if selected:
            expected_cursor = 3 - selected
            tx = matching[0]
            require(int(tx["budget_bytes"]) == min(int(states[selected]["known_demand_bytes"]), capacity),
                    "Real budget exceeds visible demand or frame capacity")
            group = [entry for entry in trace if entry.get("grant_id") == tx["grant_id"]]
            require(all(int(entry["sfu_id"]) == selected for entry in group), "Budget changed its target SFU")
            fill = only(group, "PON_DBA_FILL")
            accounting = by_grant[tx["grant_id"]]
            starts = events(group, "PON_DBA_DATA_START")
            delivered = [entry for entry in events(trace, "DELIVERED")
                         if entry["packet_id"] in {start["packet_id"] for start in starts}]
            require(all(int(entry["sfu_id"]) == selected for entry in delivered), "DATA delivered via the wrong SFU")
            row.update({
                "grant_id": tx["grant_id"], "bwmap_receive_ns": accounting["bwmap_receive_ns"],
                "slot_start_ns": accounting["slot_start_ns"], "slot_end_ns": accounting["slot_end_ns"],
                "selected_packet_ids": fill["selected_packet_ids"], "actual_bytes": accounting["actual_bytes"],
                "unused_bytes": accounting["unused_bytes"], "actual_data_ns": accounting["actual_data_ns"],
                "unused_duration_ns": accounting["unused_duration_ns"], "fill_reason": fill["fill_reason"],
                "delivery_packet_ids": ";".join(entry["packet_id"] for entry in delivered),
                "delivery_times_ns": ";".join(str(entry["_time_ns"]) for entry in delivered),
                "settlement_receive_ns": accounting["settlement_receive_ns"],
            })
        rows.append(row)
    summary = next(row for row in trace if row.get("summary") == "PON_DBA_EXECUTION")
    packet_count = len(config["packet.arrival.us"].split(","))
    require(len(events(trace, "DELIVERED")) + int(summary["pending_packets"]) == packet_count,
            "Generated/delivered/pending packet conservation failed")
    if scenario == "both_positive":
        require([row["selected_sfu_id"] for row in rows if row.get("grant_id")] == [1, 2, 1, 2],
                "Both-positive real RR did not rotate 1,2,1,2")
        require(len(events(trace, "DELIVERED")) == 4, "Both-positive DATA did not fully deliver")
    if scenario in {"both_positive", "outstanding_skip"}:
        require(any(row["selected_sfu_id"] == 2 and row["sfu_1_state"] == "KNOWN"
                    and int(row["sfu_1_known_bytes"]) > 0 and row["sfu_1_outstanding"] == "true"
                    for row in rows), "Waiting SFU1 incorrectly stopped SFU2 or lost its positive known demand")
    if scenario == "head_blocked":
        blocked = [row for row in rows if row.get("grant_id") and row["selected_sfu_id"] == 1]
        require(blocked and all(row["actual_bytes"] == 0 and row["unused_bytes"] == 38998 for row in blocked),
                "Oversized SFU1 head incorrectly transmitted")
        require([row["sfu_id"] for row in events(trace, "DELIVERED")] == ["2"],
                "SFU1 head blocking prevented independent SFU2 delivery")
    if scenario == "known_zero":
        require(any(row["sfu_1_state"] == "KNOWN" and row["sfu_1_known_bytes"] == "0"
                    and row["sfu_2_state"] == "UNKNOWN" for row in rows), "Zero and UNKNOWN became indistinguishable")
        require(all(row["selected_sfu_id"] in {0, 2} for row in rows), "Known-zero SFU received a DATA budget")
    if scenario == "exact_boundary":
        require(any(row.get("slot_end_ns") == "625000" for row in rows), "Boundary case did not end at 625000 ns")
        data_end = [row for row in events(trace, "PON_DBA_DATA_END") if row["time_ns"] == "625000"]
        poll_start = [row for row in events(trace, "PON_POLL_TX_START") if row["_time_ns"] == 625000]
        require(data_end and poll_start and data_end[0]["_index"] < poll_start[0]["_index"],
                "A new control slot started before the preceding DATA completion")

    ledger_rows = []
    for entry in trace:
        action = entry.get("event", "")
        if entry.get("sfu_id") not in {"1", "2"}:
            continue
        if action.startswith(("PON_DBA_", "PON_UPLINK_REPORT", "PON_DEMAND_LEDGER")) \
                or action in {"PON_POLL_REPORT", "PON_POLL_REPORT_RX"}:
            ledger_rows.append({
                **{key: entry.get(key, "") for key in LEDGER_COLUMNS}, "scenario_id": scenario,
                "event_index": entry["_index"], "time_ns": entry["_time_ns"],
                "state": entry.get("state", entry.get("status", "")),
                "report_frame_id": entry.get("report_frame_id", entry.get("latest_report_frame_id", "")),
                "report_snapshot_ns": entry.get("report_snapshot_ns", entry.get("snapshot_time_ns", "")),
                "report_receive_ns": entry.get("report_receive_ns", entry.get("receive_time_ns", "")),
            })
    return rows, ledger_rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-multi-sfu-dba")
    args = parser.parse_args()
    cases = scenarios()
    grants, ledger = [], []
    with TemporaryDirectory(prefix="fttr-multi-sfu-dba-") as temporary:
        directory = Path(temporary)
        verify_protocol_regression(args.jar.resolve(), directory)
        check_step_twelve(args.jar.resolve(), directory)
        for scenario, settings in cases.items():
            log = execute(args.jar.resolve(), directory / f"{scenario}.properties", settings)
            decisions, states = verify_and_export(scenario, settings, log)
            grants.extend(decisions)
            ledger.extend(states)
    args.output.mkdir(parents=True, exist_ok=True)
    for scenario, settings in cases.items():
        filename = "test-config.properties" if scenario == "both_positive" else f"{scenario}.properties"
        (args.output / filename).write_text(settings, encoding="utf-8")
    write_csv(args.output / "rr-grant-trace.csv", RR_COLUMNS, grants)
    write_csv(args.output / "per-sfu-ledger.csv", LEDGER_COLUMNS, ledger)
    real = sum(bool(row.get("grant_id")) for row in grants)
    print(f"Verified {len(cases)} actual Java scenarios: {len(grants)} frame decisions, {real} real budgets, "
          f"{len(ledger)} per-SFU state/event rows.")
    print("Two-SFU RR, independent outstanding skips, FIFO blocking, exact boundaries and packet conservation pass.")
    print("Step 12 archived execution/accounting and prior FCFS/shadow/polling remain unchanged after removing new state logs.")


if __name__ == "__main__":
    main()
