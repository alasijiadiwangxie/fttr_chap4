"""核对逻辑 REPORT 的实际接收、MFU 可见性和第 4 步 FCFS 回归，导出复核 CSV。"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import (
    FRAME_PERIOD_US, PAYLOAD_BYTES, RATE_MBPS, ROOT, ns, packet_events, require, scenario, write_csv,
)
from run_pon_report_test import (
    REPORT_ACTION, REPORT_COLUMNS, REPORT_ENABLED, check_previous_trace, run_pair,
)


REPORT_RX = "PON_UPLINK_REPORT_RX"
REPORT_STATE = "PON_UPLINK_REPORT_STATE"
PROPAGATION_US = "121.160"
FIRST_PACKETS = {(1, 2): 1, (2, 2): 33, (2, 1): 34, (3, 2): 35, (5, 1): 36}
DELIVERY_COLUMNS = (
    "frame_id", "sfu_id", "snapshot_time_ns", "pending_bytes", "eligible_bytes",
    "first_tx_start_ns", "first_tx_end_ns", "report_rx_time_ns", "delivery_status",
)
STATE_COLUMNS = (
    "frame_id", "sfu_id", "boundary_time_ns", "status", "latest_report_frame_id",
    "latest_snapshot_time_ns", "latest_receive_time_ns", "known_pending_bytes",
    "known_eligible_bytes", "report_age_ns",
)


def delivery_scenario() -> str:
    fields = [
        "# Packets 1-33 use SFU 2 at 0 us. Packet 34 waits at SFU 1 from 1 us.",
        "# Packets 35 and 36 activate frames 3 and 5. Frame 4 has no grants.",
        "# 121.160 us propagation puts the first REPORT RX exactly on frame 2's boundary.",
        "traffic.mode=manual", "traffic.direction=UL", "sfu.count=2", "sta.count=2",
        "sta.1.sfu.id=1", "sta.2.sfu.id=2", "wifi.model=fixed", "wifi.delay.us=0",
        "wifi.mac.access=fixed-priority", "fiber.uplink.mode=pon-framed",
        f"fiber.uplink.frame.period.us={FRAME_PERIOD_US}", f"fiber.uplink.rate.mbps={RATE_MBPS}",
        f"fiber.uplink.propagation.us={PROPAGATION_US}", f"packet.bytes={PAYLOAD_BYTES}",
        "packet.arrival.us=" + ",".join(["0"] * 33 + ["1", "260", "600"]),
    ]
    fields.extend(f"packet.{packet_id}.sta.id=2" for packet_id in (*range(1, 34), 35))
    return "\n".join(fields) + "\n"


def parse(log: str) -> list[dict[str, str]]:
    return [dict(token.split("=", 1) for token in line.split()) for line in log.splitlines() if line.strip()]


def report_key(row: dict[str, str]) -> tuple[int, int]:
    return int(row["frame_id"]), int(row["sfu_id"])


def report_rows(trace: list[dict[str, str]], event: str, node: str) -> dict[tuple[int, int], dict[str, str]]:
    rows = {}
    for row in trace:
        if row.get("event") == event:
            key = report_key(row)
            require(row.get("node") == node and key not in rows, f"Invalid or duplicate {event} for {key}")
            rows[key] = row
    return rows


def check_previous_stage(on: str, off: str) -> None:
    check_previous_trace(off)
    path = ROOT / "results/pon-report/report-trace.csv"
    with path.open(encoding="utf-8", newline="") as source:
        expected = list(csv.DictReader(source))
    actual = [{column: row[column] for column in REPORT_COLUMNS}
              for row in parse(on) if row.get("event") == REPORT_ACTION]
    require(actual == expected, "Local snapshot values differ from the checked-in step 4 trace")


def check_delivery(trace: list[dict[str, str]]) -> tuple[list[dict], dict[tuple[int, int], dict[str, str]]]:
    snapshots = report_rows(trace, REPORT_ACTION, "SFU")
    received = report_rows(trace, REPORT_RX, "MFU")
    sent_starts = packet_events(trace, "FIBER_TX_START", "SFU")
    sent_ends = packet_events(trace, "FIBER_TX_END", "SFU")
    data_arrivals = packet_events(trace, "DELIVERED", "MFU")
    packet_ids = set(range(1, 37))
    require(set(sent_starts) == set(sent_ends) == set(data_arrivals) == packet_ids,
            "Missing or unexpected DATA transmissions in the delivery fixture")
    expected_snapshots = {(frame_id, sfu_id) for frame_id in range(6) for sfu_id in (1, 2)}
    require(set(snapshots) == expected_snapshots, "Unexpected number of local frame snapshots")

    first_grants = {}
    for row in trace:
        if row.get("event") == "PON_UPLINK_GRANT":
            first_grants.setdefault(report_key(row), row)
    require({key: int(row["packet_id"]) for key, row in first_grants.items()} == FIRST_PACKETS,
            "Unexpected first-granted packet in the delivery fixture")
    require(set(received) == set(first_grants), "Reports received without a slot or missing after a grant")
    propagation_ns = ns(PROPAGATION_US)
    deliveries = []
    for key, snapshot in sorted(snapshots.items()):
        projection = {column: snapshot[column] for column in DELIVERY_COLUMNS[:5]}
        require(int(projection["snapshot_time_ns"]) == key[0] * FRAME_PERIOD_US * 1000,
                f"Snapshot off the frame boundary for {key}")
        grant = first_grants.get(key)
        if grant is None:
            deliveries.append(dict(projection, first_tx_start_ns="", first_tx_end_ns="",
                                   report_rx_time_ns="", delivery_status="NOT_DELIVERED_NO_GRANT"))
            continue

        packet = int(grant["packet_id"])
        tx_start = ns(sent_starts[packet]["t_us"])
        tx_end = ns(sent_ends[packet]["t_us"])
        result = received[key]
        rx_ns = int(result["receive_time_ns"])
        data_rx = data_arrivals[packet]
        require(int(result["packet_id"]) == packet, f"REPORT is not on the first granted packet for {key}")
        require(tx_start == int(grant["tx_start_ns"]) and tx_end == int(grant["tx_end_ns"]),
                f"Granted and actual DATA window differ for {key}")
        require(rx_ns == tx_end + propagation_ns == ns(result["t_us"]) == ns(data_rx["t_us"]),
                f"REPORT, DATA and propagation timestamps differ for {key}")
        require(trace.index(data_rx) < trace.index(result), f"REPORT RX precedes simultaneous DATA delivery for {key}")
        require(result["applied"] == "true", f"Unexpected unapplied report for {key}")
        for column in ("snapshot_time_ns", "pending_bytes", "eligible_bytes"):
            require(result[column] == snapshot[column], f"Original {column} changed during delivery for {key}")
        deliveries.append(dict(projection, first_tx_start_ns=tx_start, first_tx_end_ns=tx_end,
                               report_rx_time_ns=rx_ns, delivery_status="DELIVERED"))

    summaries = [row for row in trace if row.get("summary") == "PON_UPLINK_REPORTS"]
    expected_totals = dict(generated=len(snapshots), received=len(received),
                           without_grant=len(snapshots) - len(first_grants))
    require(len(summaries) == 1 and all(int(summaries[0][key]) == value
                                       for key, value in expected_totals.items()),
            "REPORT counters disagree with the generated, transmitted and received reports")
    require(int(snapshots[(1, 1)]["pending_bytes"]) > 0 and (1, 1) not in received,
            "The expected no-grant demand at SFU 1 was not exercised")
    return deliveries, received


def check_mfu_state(trace: list[dict[str, str]], received: dict[tuple[int, int], dict[str, str]]) -> list[dict]:
    runtime_states = report_rows(trace, REPORT_STATE, "MFU")
    active_frames = {frame_id for frame_id, _ in FIRST_PACKETS}
    require(set(runtime_states) == {(frame_id, sfu_id) for frame_id in active_frames for sfu_id in (1, 2)},
            "Missing active-frame MFU state output")

    accepted = [row for row in received.values() if row["applied"] == "true"]
    require(all(int(earlier["receive_time_ns"]) <= int(later["receive_time_ns"])
                for earlier, later in zip(accepted, accepted[1:])), "REPORT reception time went backwards")
    # 空帧不执行调度回调，按实际接收事件重建对应边界已经可见的状态。
    states = []
    latest = {}
    next_row = iter(accepted)
    upcoming = next(next_row, None)
    for frame_id in range(6):
        boundary_ns = frame_id * FRAME_PERIOD_US * 1000
        while upcoming is not None and int(upcoming["receive_time_ns"]) <= boundary_ns:
            latest[int(upcoming["sfu_id"])] = upcoming
            upcoming = next(next_row, None)
        for sfu_id in (1, 2):
            visible = latest.get(sfu_id)
            state = dict(frame_id=frame_id, sfu_id=sfu_id, boundary_time_ns=boundary_ns, status="UNKNOWN")
            if visible is not None:
                state.update(status="KNOWN", latest_report_frame_id=visible["frame_id"],
                             latest_snapshot_time_ns=visible["snapshot_time_ns"],
                             latest_receive_time_ns=visible["receive_time_ns"],
                             known_pending_bytes=visible["pending_bytes"],
                             known_eligible_bytes=visible["eligible_bytes"],
                             report_age_ns=boundary_ns - int(visible["snapshot_time_ns"]))
            key = frame_id, sfu_id
            if key in runtime_states:
                observed = runtime_states[key]
                actual = {column: observed[column] for column in STATE_COLUMNS if column in observed}
                require(actual == {column: str(value) for column, value in state.items()},
                        f"MFU state differs from actual REPORT receptions at {key}")
            states.append(state)
    return states


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-report-delivery")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR with mvn -o clean package")
    jar = args.jar.resolve()
    settings = delivery_scenario()
    with TemporaryDirectory(prefix="fttr-pon-report-delivery-") as directory:
        config = Path(directory) / "test-config.properties"
        previous_on, previous_off = run_pair(jar, config, scenario())
        check_previous_stage(previous_on, previous_off)
        on, _ = run_pair(jar, config, settings)

    trace = parse(on)
    deliveries, received = check_delivery(trace)
    states = check_mfu_state(trace, received)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "test-config.properties").write_bytes((settings + f"{REPORT_ENABLED}=true\n").encode("utf-8"))
    write_csv(args.output / "report-delivery-trace.csv", DELIVERY_COLUMNS, deliveries)
    write_csv(args.output / "mfu-report-state.csv", STATE_COLUMNS, states)
    print(f"Verified {len(deliveries)} local snapshots, {len(received)} received reports and {len(states)} MFU states.")
    print("REPORT on/off DATA traces and step 4 CSVs match. Generated artifacts under", args.output)


if __name__ == "__main__":
    main()
