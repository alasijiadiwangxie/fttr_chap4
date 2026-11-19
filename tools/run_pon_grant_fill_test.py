"""运行真实 Java SFU 预算装载用例，并核对第 10 步影子规划和 FCFS 轨迹。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal, ROUND_CEILING
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, require
from run_pon_dba_policy_test import (
    POLICY_COLUMNS, compare_shadow_history, check_policy, without_mode, without_planning_events,
)
from run_pon_dba_shadow_test import MODE, check_real_transmission, execute


MAIN = "edu.suda.fttr.simulator.PonGrantPacketFillCases"
TRACE_COLUMNS = {
    "scenario_id", "allocation_id", "sfu_id", "grant_bytes", "grant_duration_ns",
    "selected_packet_ids", "selected_bytes", "selected_duration_ns", "unused_bytes",
    "unused_duration_ns", "fill_reason",
}
CHECK_COLUMNS = {
    "scenario_id", "packet_id", "packet_bytes", "packet_duration_ns", "fifo_position",
    "selected", "reject_reason",
}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def verify_fill_outputs(directory: Path) -> tuple[int, int]:
    """只复核生产组件输出，不在 Python 中替代 FIFO 选择或填充实际结果。"""
    traces = read_csv(directory / "grant-fill-trace.csv")
    checks = read_csv(directory / "packet-selection-check.csv")
    require(traces and all(TRACE_COLUMNS <= row.keys() for row in traces), "Missing grant fill trace columns")
    require(checks and all(CHECK_COLUMNS <= row.keys() for row in checks), "Missing packet check columns")
    require(len({row["scenario_id"] for row in traces}) == len(traces), "Duplicate fill scenario ID")
    required = {
        "three_of_four_1200", "partial_1500", "head_39062", "fifo_no_skip", "empty_queue",
        "per_packet_ns_rounding", "independent_sfu_1", "independent_sfu_2",
        "future_packet_not_arrived", "same_time_already_enqueued", "fcfs_reserved_packets_excluded",
        "invalid_allocation", "allocation_sfu_mismatch",
    }
    require(required <= {row["scenario_id"] for row in traces}, "A required fill boundary is absent")
    grouped: dict[str, list[dict[str, str]]] = {}
    for row in checks:
        grouped.setdefault(row["scenario_id"], []).append(row)
    for row in traces:
        if row.get("status") == "REJECTED":
            continue
        scenario = row["scenario_id"]
        grant_bytes = int(row["grant_bytes"])
        grant_ns = int(row["grant_duration_ns"])
        used_bytes = int(row["selected_bytes"])
        used_ns = int(row["selected_duration_ns"])
        require(0 <= used_bytes <= grant_bytes and 0 <= used_ns <= grant_ns,
                f"Fill exceeds byte/time budget: {scenario}")
        require(int(row["unused_bytes"]) == grant_bytes - used_bytes,
                f"Wrong unused bytes: {scenario}")
        require(int(row["unused_duration_ns"]) == grant_ns - used_ns,
                f"Wrong unused time: {scenario}")
        candidates = grouped.get(scenario, [])
        selected = [candidate for candidate in candidates if candidate["selected"].lower() == "true"]
        require(sum(int(candidate["packet_bytes"]) for candidate in selected) == used_bytes,
                f"Selected packet bytes do not match fill result: {scenario}")
        require(sum(int(candidate["packet_duration_ns"]) for candidate in selected) == used_ns,
                f"Selected per-packet durations do not match fill result: {scenario}")
        observed_ids = ";".join(candidate["packet_id"] for candidate in selected)
        require(row["selected_packet_ids"] == observed_ids, f"Selected ID order differs: {scenario}")
        require([int(candidate["fifo_position"]) for candidate in candidates]
                == list(range(1, len(candidates) + 1)), f"Unstable FIFO positions: {scenario}")
        rejected = False
        for candidate in candidates:
            is_selected = candidate["selected"].lower() == "true"
            require(not (rejected and is_selected), f"Fill skipped a blocked FIFO head: {scenario}")
            if rejected:
                require(candidate["reject_reason"] == "NOT_REACHED", f"Wrong tail diagnostic: {scenario}")
            elif is_selected:
                require(candidate["reject_reason"] == "", f"Selected packet has a rejection reason: {scenario}")
            rejected |= not is_selected
            if "rate_mbps" in row:
                expected_ns = int((Decimal(candidate["packet_bytes"]) * 8000
                                   / Decimal(row["rate_mbps"])).to_integral_value(rounding=ROUND_CEILING))
                require(int(candidate["packet_duration_ns"]) == expected_ns,
                        f"Packet serialization differs from ceil rule: {scenario}")
    require((directory / "test-config.properties").is_file(), "Java did not save actual test inputs")
    return len(traces), len(checks)


def verify_protocol_regression(jar: Path, directory: Path) -> None:
    """真实 JAR 运行同一归档配置，所有第 10 步决策字段及真实统计都必须相同。"""
    settings = (ROOT / "results/pon-dba-policy/test-config.properties").read_text(encoding="utf-8")
    base = without_mode(settings)
    config = directory / "protocol-regression.properties"
    original = execute(jar, config, base)
    fcfs = execute(jar, config, base + f"{MODE}=fcfs\n")
    shadow = execute(jar, config, settings)
    require(original == fcfs == without_planning_events(shadow),
            "Read-only filler changed FCFS, BWmap, REPORT, DATA, EDCA or statistics")
    check_real_transmission(shadow)
    old_fields = compare_shadow_history(shadow)
    policy = check_policy(shadow, old_fields)
    observed = [{key: row[key] for key in POLICY_COLUMNS} for row in policy]
    require(observed == read_csv(ROOT / "results/pon-dba-policy/policy-decision-trace.csv"),
            "Step 10 policy or slot preview history changed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-grant-fill")
    parser.add_argument("--test-classpath", default=os.pathsep.join((
        str(ROOT / "target/test-classes"), str(ROOT / "target/classes"),
    )))
    args = parser.parse_args()
    require(args.jar.is_file(), "Build JAR and test classes first with mvn -o clean package")
    with TemporaryDirectory(prefix="fttr-grant-fill-") as temporary:
        directory = Path(temporary)
        first = directory / "first"
        second = directory / "second"
        for output in (first, second):
            run = subprocess.run(["java", "-cp", args.test_classpath, MAIN, str(output)],
                                 cwd=ROOT, capture_output=True, encoding="utf-8", check=False)
            require(run.returncode == 0, f"Java filler scenarios failed:\n{run.stdout}\n{run.stderr}")
        scenarios, candidates = verify_fill_outputs(first)
        verify_fill_outputs(second)
        names = ("grant-fill-trace.csv", "packet-selection-check.csv", "test-config.properties")
        for name in names:
            require((first / name).read_bytes() == (second / name).read_bytes(),
                    f"Repeated Java previews differ in {name}")
        verify_protocol_regression(args.jar.resolve(), directory)
        args.output.mkdir(parents=True, exist_ok=True)
        for name in names:
            (args.output / name).write_bytes((first / name).read_bytes())
    print(f"Verified {scenarios} Java fill scenarios and {candidates} packet decisions; output is reproducible.")
    print("Step 10 policy decisions and actual FCFS/BWmap/REPORT/DATA/ledger traces are unchanged.")
    print(f"Generated two CSV files and actual configuration in {args.output.resolve()}")


if __name__ == "__main__":
    main()
