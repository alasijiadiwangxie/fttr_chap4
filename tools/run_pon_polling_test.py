"""运行 REPORT-only 实验，从实际 Java 事件核对因果、共享占用和 MFU 状态。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, ns, require, simulate, write_csv

POLL_COLUMNS = ("poll_id", "sfu_id", "grant_frame_id", "target_frame_id", "bwmap_tx_ns", "bwmap_rx_ns",
                "poll_tx_start_ns", "poll_tx_end_ns", "snapshot_time_ns", "pending_bytes", "report_rx_ns")
FRAME_COLUMNS = ("frame_id", "frame_start_ns", "frame_end_ns", "data_busy_ns", "report_busy_ns",
                 "busy_ns", "idle_ns", "utilization")
STATE_COLUMNS = ("checkpoint_id", "time_ns", "sfu_id", "state", "trigger", "latest_report_frame_id",
                 "report_snapshot_ns", "report_receive_ns", "reported_pending_bytes",
                 "granted_bytes_since_snapshot", "effective_known_bytes", "report_age_ns")
ENABLED = "fiber.uplink.poll.enabled"


def scenario(empty: bool = False) -> str:
    lines = [
        "# Finite deterministic polling. No random seed is needed.",
        "traffic.direction=UL", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2",
        "wifi.model=fixed", "wifi.delay.us=0", "fiber.uplink.mode=pon-framed",
        "fiber.uplink.rate.mbps=2500", "fiber.uplink.frame.period.us=125",
        "fiber.uplink.propagation.us=10.025", "fiber.downlink.mode=pon-shared",
        "fiber.downlink.rate.mbps=10000", "fiber.downlink.propagation.us=10",
        "fiber.uplink.report.enabled=true", "fiber.uplink.bwmap.enabled=true",
        "fiber.uplink.demand.ledger.enabled=true", f"{ENABLED}=true",
        "fiber.uplink.poll.bytes=64", "fiber.uplink.poll.interval.frames=1", "fiber.uplink.poll.end.us=625",
    ]
    if empty:
        lines += ["traffic.mode=poisson", "traffic.duration.us=1"]
    else:
        lines += ["traffic.mode=manual", "packet.arrival.us=0", "packet.bytes=39062"]
    return "\n".join(lines) + "\n"


def parse(log: str) -> list[dict[str, str]]:
    return [dict(token.split("=", 1) for token in line.split()) for line in log.splitlines() if line.strip()]


def events(trace: list[dict], action: str) -> list[dict]:
    return [row for row in trace if row.get("event") == action]


def summary(trace: list[dict], name: str) -> dict:
    matches = [row for row in trace if row.get("summary") == name]
    require(len(matches) == 1, f"Expected one {name} summary")
    return matches[0]


def execute(jar: Path, config: Path, settings: str) -> str:
    config.write_text(settings, encoding="utf-8")
    log = simulate(jar, config)
    require(log == simulate(jar, config), "Identical configuration produced different complete event traces")
    return log


def check_previous_stage(jar: Path, config: Path) -> None:
    # 对照真实的第 7 步场景，并核对已提交的逐状态账本数据。
    base = (ROOT / "results/pon-demand-ledger/test-config.properties").read_text(encoding="utf-8")
    previous = execute(jar, config, base)
    disabled = execute(jar, config, base + f"{ENABLED}=false\n")
    require(previous == disabled, "Explicitly disabled polling changed the complete previous-stage trace")
    observed = events(parse(previous), "PON_DEMAND_LEDGER")
    with (ROOT / "results/pon-demand-ledger/demand-ledger-trace.csv").open(encoding="utf-8") as stream:
        history = list(csv.DictReader(stream))
    require(len(observed) == len(history), "Historical ledger observation count differs")
    for row, expected in zip(observed, history):
        require(all(row.get(key, "") == value for key, value in expected.items() if key != "checkpoint_id"),
                "Historical ledger state changed when polling was off")


def check_polls(trace: list[dict], empty: bool) -> list[dict]:
    phases = [events(trace, action) for action in (
        "PON_POLL_BWMAP_TX", "PON_POLL_BWMAP_RX", "PON_POLL_TX_START",
        "PON_POLL_REPORT", "PON_POLL_TX_END", "PON_POLL_REPORT_RX")]
    require(all(len(phase) == 4 for phase in phases), "Each of four polls must traverse every control phase once")
    rows = []
    for index, (tx, bw_rx, start, snapshot, end, rx) in enumerate(zip(*phases), 1):
        sfu = 1 if index % 2 else 2
        start_ns = (index + 1) * 125_000
        end_ns = start_ns + 205
        expected_bytes = 0 if empty or sfu == 2 else 39062
        require(all(int(row["poll_id"]) == index and int(row["sfu_id"]) == sfu
                    and "packet_id" not in row for row in (tx, bw_rx, start, snapshot, end, rx)),
                f"Wrong identity or fabricated DATA packet in poll {index}")
        require(all(row["grant_id"] == f"R{index}" and row["grant_type"] == "REPORT_ONLY"
                    for row in (tx, bw_rx, start, end, rx)), "Control grant lost its independent identity")
        require(ns(tx["t_us"]) == index * 125_000 and int(tx["target_frame_id"]) == index + 1,
                "Round-robin scheduling changed")
        require(ns(bw_rx["t_us"]) == ns(tx["t_us"]) + 10_000 < start_ns, "Late or early control BWmap")
        require(ns(start["t_us"]) == start_ns == int(snapshot["snapshot_time_ns"]),
                "REPORT did not snapshot the actual control slot start")
        require(ns(end["t_us"]) == end_ns and int(rx["receive_time_ns"]) == end_ns + 10_025,
                "Control serialization or propagation differs")
        require(int(snapshot["pending_bytes"]) == expected_bytes == int(rx["pending_bytes"]),
                "Local snapshot changed during report transport")
        require(rx["applied"] == "true", "The polled snapshot must be fresh in this fixture")
        require(trace.index(start) < trace.index(snapshot) < trace.index(end) < trace.index(rx),
                "Control event execution order differs")
        rows.append(dict(poll_id=index, sfu_id=sfu, grant_frame_id=index, target_frame_id=index + 1,
                         bwmap_tx_ns=ns(tx["t_us"]), bwmap_rx_ns=ns(bw_rx["t_us"]),
                         poll_tx_start_ns=start_ns, poll_tx_end_ns=end_ns, snapshot_time_ns=start_ns,
                         pending_bytes=expected_bytes, report_rx_ns=end_ns + 10_025))
    return rows


def check_usage(trace: list[dict], polls: list[dict], empty: bool) -> list[dict]:
    starts = events(trace, "FIBER_TX_START")
    ends = events(trace, "FIBER_TX_END")
    require(len(starts) == len(ends) == (0 if empty else 1), "Unexpected DATA transmissions")
    slots = [(row["target_frame_id"], row["poll_tx_start_ns"], row["poll_tx_end_ns"], "report")
             for row in polls]
    if not empty:
        require(ns(starts[0]["t_us"]) == 750_000 and ns(ends[0]["t_us"]) == 874_999,
                "Full-size FCFS head must wait until control polling finishes")
        slots.append((6, 750_000, 874_999, "data"))
    ordered = sorted(slots, key=lambda slot: slot[1])
    require(all(left[2] <= right[1] for left, right in zip(ordered, ordered[1:])), "Shared uplink slots overlap")
    frames = []
    for source in trace:
        if source.get("summary") != "PON_POLL_FRAME":
            continue
        row = {key: int(source[key]) for key in FRAME_COLUMNS[:-1]}
        frame_id = row["frame_id"]
        data = sum(end - start for frame, start, end, kind in slots if frame == frame_id and kind == "data")
        report = sum(end - start for frame, start, end, kind in slots if frame == frame_id and kind == "report")
        require(row["frame_start_ns"] == frame_id * 125_000
                and row["frame_end_ns"] == (frame_id + 1) * 125_000, "Incorrect physical frame")
        require(row["data_busy_ns"] == data and row["report_busy_ns"] == report
                and row["busy_ns"] == data + report
                and row["idle_ns"] == 125_000 - data - report, "Busy time counted twice or omitted")
        require(abs(Decimal(source["utilization"]) - Decimal(data + report) / 125_000) <= Decimal("0.0000000005"),
                "Incorrect frame utilization")
        frames.append(dict(row, utilization=source["utilization"]))
    require([row["frame_id"] for row in frames] == list(range(6 if empty else 7)), "Missing frames")
    total = summary(trace, "PON_POLLING")
    require(int(total["polls"]) == 4 and int(total["report_busy_ns"]) == 820
            and int(total["data_busy_ns"]) == (0 if empty else 124999), "Incorrect control/data summary")
    upstream = summary(trace, "UL_GLOBAL")
    require(int(upstream["delivered_packets"]) == (0 if empty else 1)
            and int(upstream["delivered_bytes"]) == (0 if empty else 39062), "Control affected DATA deliveries")
    debits = events(trace, "PON_DEMAND_LEDGER_GRANT")
    require(len(debits) == (0 if empty else 1), "Control grant incorrectly debited DATA demand")
    if debits:
        require(debits[0]["grant_bytes"] == "39062" and debits[0]["grant_create_ns"] == "625000", "DATA debit changed")
    return frames


def check_states(trace: list[dict]) -> list[dict]:
    reports = {}
    grants = {1: [], 2: []}
    states = []
    for row in trace:
        action = row.get("event")
        if action in ("PON_POLL_REPORT_RX", "PON_UPLINK_REPORT_RX") and row["applied"] == "true":
            reports[int(row["sfu_id"])] = row
        elif action == "PON_DEMAND_LEDGER_GRANT":
            grants[int(row["sfu_id"])].append((int(row["grant_create_ns"]), int(row["grant_bytes"])))
        elif action == "PON_DEMAND_LEDGER":
            sfu = int(row["sfu_id"])
            time_ns = int(row["time_ns"])
            known = reports.get(sfu)
            require((row["state"] == "KNOWN") == (known is not None), "UNKNOWN/KNOWN visibility differs")
            if known:
                snapshot_ns = int(known["snapshot_time_ns"])
                counted = sum(size for created, size in grants[sfu] if snapshot_ns <= created <= time_ns)
                require(int(row["reported_pending_bytes"]) == int(known["pending_bytes"])
                        and int(row["granted_bytes_since_snapshot"]) == counted
                        and int(row["effective_known_bytes"]) == max(0, int(known["pending_bytes"]) - counted),
                        "MFU estimate does not reconcile against received reports and DATA grants")
            states.append(dict(checkpoint_id=len(states) + 1, **{key: row[key] for key in STATE_COLUMNS[1:] if key in row}))
    require(any(row["state"] == "UNKNOWN" for row in states), "Initial UNKNOWN was not tested")
    require(any(row.get("time_ns") == "260230" and row.get("effective_known_bytes") == "39062" for row in states),
            "Polling did not expose the buffered demand before DATA reservation")
    require(any(row.get("sfu_id") == "2" and row.get("reported_pending_bytes") == "0" for row in states),
            "Empty SFU did not acquire a known zero baseline")
    return states


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-polling")
    args = parser.parse_args()
    require(args.jar.is_file(), "Build the JAR with mvn -o clean package")
    with TemporaryDirectory(prefix="fttr-pon-polling-") as directory:
        config = Path(directory) / "test.properties"
        check_previous_stage(args.jar.resolve(), config)
        log = execute(args.jar.resolve(), config, scenario())
        empty = parse(execute(args.jar.resolve(), config, scenario(True)))
        check_usage(empty, check_polls(empty, True), True)
        require(empty[-1]["simulation_end_us"] == "635.23", "Zero-DATA polling did not stop at its finite window")
    trace = parse(log)
    polls = check_polls(trace, False)
    frames = check_usage(trace, polls, False)
    states = check_states(trace)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "test-config.properties").write_bytes(scenario().encode("utf-8"))
    write_csv(args.output / "polling-trace.csv", POLL_COLUMNS, polls)
    write_csv(args.output / "frame-usage.csv", FRAME_COLUMNS, frames)
    write_csv(args.output / "mfu-state-trace.csv", STATE_COLUMNS, states)
    print(f"Verified {len(polls)} control slots, {len(frames)} physical frames and {len(states)} MFU states.")
    print("Empty-traffic finite termination and previous-stage DATA trace parity verified.")


if __name__ == "__main__":
    main()
