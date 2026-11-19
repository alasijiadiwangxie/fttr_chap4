"""复跑真实 Java 仿真与计划校验器，导出第 10 步 DBA 策略复核材料。"""

from __future__ import annotations

import argparse
import csv
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, require, write_csv
from run_pon_polling_test import events, parse
from run_pon_dba_shadow_test import (
    ACTION as SHADOW_ACTION,
    BUDGET_COLUMNS,
    DECISION_COLUMNS,
    MODE,
    PERIOD_NS,
    check_decisions,
    check_real_transmission,
    execute,
)


POLICY_ACTION = "PON_DBA_POLICY"
VALIDATION_MAIN = "edu.suda.fttr.simulator.DbaPlanValidationCases"
POLICY_COLUMNS = (
    "decision_time_ns", "target_frame_id", "policy_name", "allocation_id", "sfu_id",
    "known_demand_bytes", "planned_bytes", "planned_duration_ns", "decision_reason",
    "preview_start_ns", "preview_end_ns",
)
VALIDATION_COLUMNS = (
    "scenario_id", "target_frame_id", "proposed_bytes", "control_reserved_ns",
    "planned_data_ns", "available_ns", "expected_result", "actual_result", "validation_reason",
)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def without_planning_events(log: str) -> str:
    """只移除两类观测日志；所有真实事件、统计及其先后顺序仍逐行比较。"""
    excluded = {f"event={SHADOW_ACTION}", f"event={POLICY_ACTION}"}
    return "".join(
        line for line in log.splitlines(keepends=True) if not excluded.intersection(line.split())
    )


def without_mode(settings: str) -> str:
    # 用第 9 步原始配置去掉显式 mode，验证默认模式与显式 fcfs 完全相同。
    return "".join(
        line for line in settings.splitlines(keepends=True)
        if line.split("=", 1)[0].strip() != MODE
    )


def compare_shadow_history(log: str) -> list[dict[str, str]]:
    decisions, budgets = check_decisions(log)
    history = ROOT / "results/pon-dba-shadow"
    for filename, columns, rows in (
        ("dba-decision-trace.csv", DECISION_COLUMNS, decisions),
        ("frame-budget-check.csv", BUDGET_COLUMNS, budgets),
    ):
        # 包括报告帧号、Report Age、UNKNOWN 数量等全部旧字段，不能只比较授权字节数。
        observed = [{column: str(row[column]) for column in columns} for row in rows]
        require(observed == read_csv(history / filename), f"Step 9 history changed in {filename}")
    return decisions


def check_policy(log: str, historical: list[dict[str, str]]) -> list[dict[str, str]]:
    rows = events(parse(log), POLICY_ACTION)
    require(len(rows) == len(historical) == 6, "Expected exactly six policy/preview decisions")
    result = []
    for row, old in zip(rows, historical):
        require(all(column in row for column in POLICY_COLUMNS), "Policy event is missing a CSV field")
        require(row["node"] == "MFU" and row["policy_name"] == "shadow-rr", "Unexpected policy owner or name")
        selected = int(old["selected_sfu_id"])
        # allocationId 是本仿真的逻辑对象标识；当前稳定映射为 2 * sfuId，非标准 Alloc-ID 编码。
        require(int(row["allocation_id"]) == 2 * selected and int(row["sfu_id"]) == selected,
                "Logical allocation and SFU mapping differ")
        correspondence = {
            "decision_time_ns": "decision_time_ns",
            "target_frame_id": "target_frame_id",
            "known_demand_bytes": "effective_known_bytes",
            "planned_bytes": "planned_grant_bytes",
            "planned_duration_ns": "planned_grant_ns",
            "decision_reason": "decision_reason",
        }
        require(all(row[new] == old[previous] for new, previous in correspondence.items()),
                "Unified policy differs from the historical RR decision")
        if selected:
            start = int(old["target_frame_id"]) * PERIOD_NS + int(old["control_reserved_ns"])
            end = start + int(old["planned_grant_ns"])
            require(int(row["preview_start_ns"]) == start and int(row["preview_end_ns"]) == end,
                    "Read-only slot preview has the wrong control offset or duration")
            require(end <= (int(old["target_frame_id"]) + 1) * PERIOD_NS,
                    "Slot preview extends beyond the target frame")
        else:
            require(row["preview_start_ns"] == row["preview_end_ns"] == "-1",
                    "An empty decision created a slot preview")
        result.append({column: row[column] for column in POLICY_COLUMNS})
    return result


def run_validation_cases(classpath: str, output: Path) -> list[dict[str, str]]:
    """由 Java 调用生产校验器，CSV 中的实测结论不在 Python 内伪造。"""
    process = subprocess.run(
        ["java", "-cp", classpath, VALIDATION_MAIN, str(output)],
        cwd=ROOT, capture_output=True, encoding="utf-8", check=False,
    )
    require(process.returncode == 0,
            f"Java plan validation failed; build test classes first.\n{process.stdout}\n{process.stderr}")
    rows = read_csv(output)
    require(rows, "Java validator produced no validation cases")
    require(all(set(VALIDATION_COLUMNS).issubset(row) for row in rows), "Validation CSV columns are incomplete")
    require(len({row["scenario_id"] for row in rows}) == len(rows), "Duplicate validation scenario")
    require(all(row["expected_result"] == row["actual_result"] for row in rows),
            "A Java plan validation result differs from its expectation")
    require(all(row["actual_result"] in {"ACCEPTED", "REJECTED"} for row in rows),
            "Unexpected Java validation result token")
    required = {
        "normal_6000", "capacity_38998", "invalid_allocation", "negative_bytes",
        "frame_capacity_exceeded", "duplicate_allocation", "two_allocations_nonoverlap",
    }
    require(required.issubset({row["scenario_id"] for row in rows}), "Required validation boundary is absent")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-dba-policy")
    parser.add_argument("--test-classpath", default=os.pathsep.join((
        str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
    )))
    args = parser.parse_args()
    require(args.jar.is_file(), "Build the Java JAR and test classes with mvn -o clean package")
    # 原样复用第 9 步已归档配置，避免在重构验收时悄悄改变实验条件。
    config_bytes = (ROOT / "results/pon-dba-shadow/test-config.properties").read_bytes()
    enabled = config_bytes.decode("utf-8")
    base = without_mode(enabled)
    with TemporaryDirectory(prefix="fttr-dba-policy-") as directory:
        temporary = Path(directory)
        config = temporary / "test.properties"
        original = execute(args.jar.resolve(), config, base)
        disabled = execute(args.jar.resolve(), config, base + f"{MODE}=fcfs\n")
        on = execute(args.jar.resolve(), config, enabled)
        require(original == disabled == without_planning_events(on),
                "Policy/preview changed actual FCFS, BWmap, REPORT, DATA, statistics or ledger events")
        check_real_transmission(on)
        historical = compare_shadow_history(on)
        decisions = check_policy(on, historical)
        validation_path = temporary / "plan-validation.csv"
        validations = run_validation_cases(args.test_classpath, validation_path)
        args.output.mkdir(parents=True, exist_ok=True)
        write_csv(args.output / "policy-decision-trace.csv", POLICY_COLUMNS, decisions)
        # 保留 Java 程序的原始输出；Python 只验证内容，不重写实际结果。
        (args.output / "plan-validation.csv").write_bytes(validation_path.read_bytes())
        (args.output / "test-config.properties").write_bytes(config_bytes)
    print(f"Verified {len(decisions)} unchanged historical decisions and {len(validations)} Java plan validations.")
    print("Repeated runs are identical; actual FCFS/BWmap/REPORT/DATA/ledger traces match planning disabled.")
    print(f"Generated policy decision, plan validation and configuration files in {args.output.resolve()}")


if __name__ == "__main__":
    main()
