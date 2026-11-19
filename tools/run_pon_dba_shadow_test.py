"""从实际 Java 仿真验证 DBA 影子预算，并导出决策和逐帧容量复核表。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, require, simulate, write_csv
from run_pon_polling_test import (
    FRAME_COLUMNS, POLL_COLUMNS, STATE_COLUMNS, check_polls, check_states, check_usage, events, parse, scenario,
)


MODE = "fiber.uplink.dba.mode"
ACTION = "PON_DBA_SHADOW"
PERIOD_NS = 125_000
RATE_MBPS = Decimal(2500)
DECISION_COLUMNS = (
    "decision_time_ns", "target_frame_id", "selected_sfu_id", "report_frame_id", "report_age_ns",
    "effective_known_bytes", "control_reserved_ns", "available_data_ns", "data_capacity_bytes",
    "planned_grant_bytes", "planned_grant_ns", "decision_reason", "unknown_sfus", "known_zero_sfus",
)
BUDGET_COLUMNS = (
    "decision_time_ns", "target_frame_id", "frame_capacity_ns", "control_reserved_ns",
    "available_data_ns", "data_capacity_bytes", "planned_grant_bytes", "planned_grant_ns",
    "remaining_ns", "within_capacity",
)


def execute(jar: Path, config: Path, settings: str) -> str:
    config.write_text(settings, encoding="utf-8")
    log = simulate(jar, config)
    require(log == simulate(jar, config), "Same shadow configuration produced different complete traces")
    return log


def without_shadow(log: str) -> str:
    return "".join(line for line in log.splitlines(keepends=True)
                   if not {f"event={ACTION}", "event=PON_DBA_POLICY"}.intersection(line.split()))


def compare_history(filename: str, columns: tuple[str, ...], rows: list[dict]) -> None:
    with (ROOT / "results/pon-polling" / filename).open(encoding="utf-8", newline="") as source:
        expected = list(csv.DictReader(source))
    observed = [{key: str(row.get(key, "")) for key in columns} for row in rows]
    require(observed == expected, f"Actual polling changed in {filename}")


def check_real_transmission(log: str) -> None:
    trace = parse(log)
    polls = check_polls(trace, False)
    usage = check_usage(trace, polls, False)
    states = check_states(trace)
    compare_history("polling-trace.csv", POLL_COLUMNS, polls)
    compare_history("frame-usage.csv", FRAME_COLUMNS, usage)
    compare_history("mfu-state-trace.csv", STATE_COLUMNS, states)


def check_decisions(log: str) -> tuple[list[dict], list[dict]]:
    """按实际 REPORT RX 和真实预约创建顺序重算影子输入，防止查询未来状态。"""
    trace = parse(log)
    latest = {}
    grants = {1: [], 2: []}
    actual_controls = {
        int(row["target_frame_id"]): int(row["tx_end_ns"]) - int(row["tx_start_ns"])
        for row in events(trace, "PON_POLL_BWMAP_TX")
    }
    decisions = []
    budgets = []
    next_sfu = 1
    for row in trace:
        action = row.get("event")
        if action in ("PON_POLL_REPORT_RX", "PON_UPLINK_REPORT_RX") and row["applied"] == "true":
            latest[int(row["sfu_id"])] = row
        elif action == "PON_DEMAND_LEDGER_GRANT":
            grants[int(row["sfu_id"])].append((int(row["grant_create_ns"]), int(row["grant_bytes"])))
        elif action == ACTION:
            decision = {key: row[key] for key in DECISION_COLUMNS}
            time_ns = int(row["decision_time_ns"])
            target = int(row["target_frame_id"])
            require(target == time_ns // PERIOD_NS + 1 and time_ns % PERIOD_NS == 0,
                    "Shadow decision is outside its existing frame callback")
            control_ns = actual_controls.get(target, 0)
            available = PERIOD_NS - control_ns
            capacity = int((Decimal(available) * RATE_MBPS / 8000).to_integral_value(rounding=ROUND_FLOOR))
            require(int(row["control_reserved_ns"]) == control_ns and int(row["available_data_ns"]) == available
                    and int(row["data_capacity_bytes"]) == capacity, "Shadow capacity differs from actual control slots")
            demands = {}
            for sfu, report in latest.items():
                snapshot = int(report["snapshot_time_ns"])
                counted = sum(size for created, size in grants[sfu] if snapshot <= created <= time_ns)
                demands[sfu] = max(0, int(report["pending_bytes"]) - counted)
            require(int(row["unknown_sfus"]) == 2 - len(demands)
                    and int(row["known_zero_sfus"]) == sum(value == 0 for value in demands.values()),
                    "UNKNOWN and known zero were confused")
            candidates = (next_sfu, 3 - next_sfu)
            selected = next((sfu for sfu in candidates if demands.get(sfu, 0) > 0), 0) if capacity else 0
            require(int(row["selected_sfu_id"]) == selected, "Shadow RR selection used unavailable demand")
            planned_bytes = min(demands[selected], capacity) if selected else 0
            planned_ns = int((Decimal(planned_bytes) * 8000 / RATE_MBPS).to_integral_value(rounding=ROUND_CEILING))
            require(int(row["planned_grant_bytes"]) == planned_bytes
                    and int(row["planned_grant_ns"]) == planned_ns, "Incorrect shadow byte budget or serialization")
            if selected:
                report = latest[selected]
                report_frame = report.get("frame_id", report.get("target_frame_id"))
                require(int(row["report_frame_id"]) == int(report_frame)
                        and int(row["report_age_ns"]) == time_ns - int(report["snapshot_time_ns"])
                        and int(row["effective_known_bytes"]) == demands[selected], "Shadow used the wrong report baseline")
                next_sfu = 3 - selected
            require(control_ns + planned_ns <= PERIOD_NS, "Shadow budget exceeds the target frame")
            decisions.append(decision)
            budgets.append(dict(
                decision_time_ns=time_ns, target_frame_id=target, frame_capacity_ns=PERIOD_NS,
                control_reserved_ns=control_ns, available_data_ns=available, data_capacity_bytes=capacity,
                planned_grant_bytes=planned_bytes, planned_grant_ns=planned_ns,
                remaining_ns=PERIOD_NS - control_ns - planned_ns, within_capacity="true",
            ))

    require([int(row["decision_time_ns"]) for row in decisions] == [125_000, 250_000, 375_000, 500_000, 625_000, 750_000],
            "Shadow added or skipped existing frame callbacks")
    require([int(row["planned_grant_bytes"]) for row in decisions] == [0, 0, 38998, 38998, 39062, 0],
            "Expected demand/capacity boundary was not exercised")
    # 原有大包在 750 μs 才真实发送。此前的小于包长预算只是观测值。
    starts = events(trace, "FIBER_TX_START")
    require(len(starts) == 1 and starts[0]["t_us"] == "750", "Shadow suggestion created an actual DATA transmission")
    require(len(events(trace, "PON_DEMAND_LEDGER_GRANT")) == 1, "Shadow budget entered the real grant ledger")
    return decisions, budgets


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-dba-shadow")
    args = parser.parse_args()
    require(args.jar.is_file(), "Build the Java JAR with mvn -o clean package")
    settings = scenario()
    enabled = settings + f"{MODE}=shadow-rr\n"
    with TemporaryDirectory(prefix="fttr-dba-shadow-") as directory:
        config = Path(directory) / "test.properties"
        original = execute(args.jar.resolve(), config, settings)
        off = execute(args.jar.resolve(), config, settings + f"{MODE}=fcfs\n")
        on = execute(args.jar.resolve(), config, enabled)
        require(original == off == without_shadow(on), "Shadow changed actual FCFS/control/DATA/ledger events")
        check_real_transmission(on)
    decisions, budgets = check_decisions(on)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "test-config.properties").write_bytes(enabled.encode("utf-8"))
    write_csv(args.output / "dba-decision-trace.csv", DECISION_COLUMNS, decisions)
    write_csv(args.output / "frame-budget-check.csv", BUDGET_COLUMNS, budgets)
    print(f"Verified {len(decisions)} shadow decisions and {len(budgets)} capacity checks.")
    print("Actual polling, DATA and grant ledger match the previous stage.")


if __name__ == "__main__":
    main()
