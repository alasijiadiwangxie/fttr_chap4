"""从实际仿真轨迹核对上行 REPORT 和 FCFS 回归，并重建复核 CSV。"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import (
    FRAME_PERIOD_US, PAYLOAD_BYTES, ROOT, check_trace, ns, require, scenario, simulate, write_csv,
)


REPORT_ACTION = "PON_UPLINK_REPORT"
REPORT_ENABLED = "fiber.uplink.report.enabled"
REPORT_OUTPUT_TOKENS = {
    f"event={REPORT_ACTION}", "event=PON_UPLINK_REPORT_RX", "event=PON_UPLINK_REPORT_STATE",
    "summary=PON_UPLINK_REPORTS",
}
REPORT_COLUMNS = (
    "frame_id", "sfu_id", "snapshot_time_ns",
    "pending_packets", "pending_bytes", "eligible_packets", "eligible_bytes",
)
CHECK_COLUMNS = (
    "scenario", "frame_id", "sfu_id", "metric",
    "expected_packets", "actual_packets", "expected_buffer_bytes", "actual_buffer_bytes", "match",
)


def boundary_scenario() -> str:
    # 三包在 25、50、75 μs 抵达 SFU 1。SFU 2 的 DATA 恰在 125 μs 收齐，先于帧事件入队。
    return "\n".join((
        "traffic.mode=manual", "traffic.direction=UL", "sfu.count=2", "sta.count=2",
        "sta.1.sfu.id=1", "sta.2.sfu.id=2", "wifi.model=fixed", "wifi.delay.us=25",
        "wifi.mac.access=fixed-priority", "fiber.uplink.mode=pon-framed",
        "fiber.uplink.rate.mbps=2500", "fiber.uplink.propagation.us=10.025",
        f"fiber.uplink.frame.period.us={FRAME_PERIOD_US}", "packet.arrival.us=0,0,0,100",
        "packet.bytes=1200", "packet.1.bytes=400", "packet.2.bytes=800", "packet.4.bytes=1600",
        "packet.4.sta.id=2",
    )) + "\n"


def run_pair(jar: Path, config: Path, settings: str) -> tuple[str, str]:
    config.write_bytes((settings + f"{REPORT_ENABLED}=false\n").encode("utf-8"))
    off = simulate(jar, config)
    config.write_bytes((settings + f"{REPORT_ENABLED}=true\n").encode("utf-8"))
    on = simulate(jar, config)
    def without_report_rows(log: str) -> str:
        return "".join(line for line in log.splitlines(keepends=True)
                       if REPORT_OUTPUT_TOKENS.isdisjoint(line.split()))

    require(without_report_rows(off) == off, "Disabled REPORT produced report rows")
    require(without_report_rows(on) == off, "Enabling REPORT changed the non-REPORT simulation trace")
    require(on == simulate(jar, config), "Identical REPORT inputs produced different traces")
    return on, off


def check_reports(log: str, name: str, expected: dict[tuple[int, int], tuple[int, ...]]) -> tuple[list[dict], list[dict]]:
    trace = [dict(token.split("=", 1) for token in line.split()) for line in log.splitlines() if line.strip()]
    active = {int(row["frame_id"]) for row in trace if row.get("event") == "PON_UPLINK_GRANT"}
    # 从实际光侧入队和授权日志独立重建队列。已跳过的空帧保留该帧边界的全零快照。
    queues: dict[int, dict[int, tuple[int, int]]] = {sfu_id: {} for _, sfu_id in expected}
    reports = {}
    for row in trace:
        action = row.get("event")
        if action == "PON_UPLINK_ENQUEUE":
            queues[int(row["sfu_id"])][int(row["packet_id"])] = (ns(row["t_us"]), int(row["bytes"]))
        elif action == "PON_UPLINK_GRANT":
            frame_id = int(row["frame_id"])
            require(all((frame_id, sfu_id) in reports for sfu_id in queues),
                    f"{name}: a frame grant preceded an SFU snapshot")
            queues[int(row["sfu_id"])].pop(int(row["packet_id"]))
        elif action == REPORT_ACTION:
            report = {column: int(row[column]) for column in REPORT_COLUMNS}
            frame_id, sfu_id = report["frame_id"], report["sfu_id"]
            key = frame_id, sfu_id
            require(row["node"] == "SFU" and key in expected and key not in reports,
                    f"{name}: duplicate or unexpected snapshot {key}")
            boundary = frame_id * FRAME_PERIOD_US * 1000
            require(report["snapshot_time_ns"] == boundary, f"{name}: snapshot {key} is off the frame boundary")
            pending = list(queues[sfu_id].values()) if frame_id in active else []
            eligible = [size for enqueue, size in pending if enqueue < boundary]
            replay = (len(pending), sum(size for _, size in pending), len(eligible), sum(eligible))
            require(replay == expected[key], f"{name}: replayed queue differs from fixture at {key}")
            reports[key] = report

    require(set(reports) == set(expected) and all(not queue for queue in queues.values()),
            f"{name}: missing snapshots or ungranted packets remain")
    checks = []
    for (frame_id, sfu_id), report in sorted(reports.items()):
        for index, metric in enumerate(("pending", "eligible")):
            expected_count, expected_bytes = expected[(frame_id, sfu_id)][index * 2:index * 2 + 2]
            actual_count, actual_bytes = report[f"{metric}_packets"], report[f"{metric}_bytes"]
            match = (expected_count, expected_bytes) == (actual_count, actual_bytes)
            checks.append(dict(scenario=name, frame_id=frame_id, sfu_id=sfu_id, metric=metric,
                               expected_packets=expected_count, actual_packets=actual_count,
                               expected_buffer_bytes=expected_bytes, actual_buffer_bytes=actual_bytes,
                               match=str(match).lower()))
            require(match, f"{name}: {metric} snapshot differs at frame {frame_id}, SFU {sfu_id}")
    return [reports[key] for key in sorted(reports)], checks


def check_previous_trace(off: str) -> None:
    packets, frames = check_trace(off)
    for filename, current in (("event-trace.csv", packets), ("frame-summary.csv", frames)):
        with (ROOT / "results/pon-frame" / filename).open(encoding="utf-8", newline="") as history:
            expect = list(csv.DictReader(history))
        require([{key: str(value) for key, value in row.items()} for row in current] == expect,
                f"Current FCFS trace does not match the previous phase: {filename}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-report")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR with mvn -o clean package")
    jar = args.jar.resolve()

    frame_counts = {(0, 1): 0, (0, 2): 0, (1, 1): 16, (1, 2): 17,
                    (2, 1): 0, (2, 2): 1, (3, 1): 1, (3, 2): 1}
    frame_expected = {key: (count, count * PAYLOAD_BYTES) * 2 for key, count in frame_counts.items()}
    boundary_expected = {
        (0, 1): (0, 0, 0, 0), (0, 2): (0, 0, 0, 0),
        (1, 1): (3, 2400, 3, 2400), (1, 2): (1, 1600, 0, 0),
        (2, 1): (0, 0, 0, 0), (2, 2): (1, 1600, 1, 1600),
    }
    settings = scenario()
    with TemporaryDirectory(prefix="fttr-pon-report-") as temporary:
        config = Path(temporary) / "report.properties"
        on, off = run_pair(jar, config, settings)
        check_previous_trace(off)
        reports, checks = check_reports(on, "35-packet-regression", frame_expected)
        boundary_on, _ = run_pair(jar, config, boundary_scenario())
        _, boundary_checks = check_reports(boundary_on, "boundary-arrival", boundary_expected)

    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "test-config.properties").write_bytes((settings + f"{REPORT_ENABLED}=true\n").encode("utf-8"))
    write_csv(args.output / "report-trace.csv", REPORT_COLUMNS, reports)
    write_csv(args.output / "report-check.csv", CHECK_COLUMNS, checks + boundary_checks)
    print(f"Verified {len(reports)} main snapshots and {len(checks + boundary_checks)} buffer comparisons.")
    print("REPORT on/off traces and previous-stage FCFS CSVs match. Generated artifacts under", args.output)


if __name__ == "__main__":
    main()
