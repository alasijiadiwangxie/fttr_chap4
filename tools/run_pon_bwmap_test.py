"""从实际 Java 仿真核对提前一帧的 BWmap，并导出逐授权与逐报文状态轨迹。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import (
    ARRIVALS_US, FRAME_PERIOD_US, PAYLOAD_BYTES, PROPAGATION_US, RATE_MBPS, ROOT,
    check_ratio, ns, packet_events, require, scenario, simulate, write_csv,
)
from run_pon_report_delivery_test import (
    DELIVERY_COLUMNS, REPORT_RX, REPORT_STATE, STATE_COLUMNS, check_delivery, check_mfu_state,
    check_previous_stage, delivery_scenario, parse, report_key, report_rows,
)
from run_pon_report_test import REPORT_ACTION, REPORT_ENABLED, run_pair


BWMAP_ENABLED = "fiber.uplink.bwmap.enabled"
DOWNLINK_PROPAGATION_US = 10
PERIOD_NS = FRAME_PERIOD_US * 1000
SERIALIZATION_NS = (PAYLOAD_BYTES * 8000 + RATE_MBPS - 1) // RATE_MBPS
BWMAP_COLUMNS = (
    "grant_frame_id", "target_frame_id", "packet_id", "sfu_id", "grant_tx_ns",
    "grant_rx_ns", "tx_start_ns", "tx_end_ns", "granted_bytes",
)
STATE_TRACE_COLUMNS = ("packet_id", "sfu_id", "state", "time_ns", "grant_frame_id", "target_frame_id", "bytes")
PACKET_STATES = {
    ("SFU", "PON_UPLINK_ENQUEUE"): "QUEUED_UNGRANTED",
    ("MFU", "PON_BWMAP_TX"): "RESERVED",
    ("SFU", "PON_BWMAP_RX"): "GRANT_RECEIVED",
    ("SFU", "FIBER_TX_START"): "TX_STARTED",
    ("SFU", "FIBER_TX_END"): "TX_FINISHED",
    ("MFU", "DELIVERED"): "DELIVERED",
}


def bwmap_scenario() -> str:
    return scenario() + "\n".join((
        "fiber.downlink.mode=pon-shared",
        "fiber.downlink.rate.mbps=10000",
        f"fiber.downlink.propagation.us={DOWNLINK_PROPAGATION_US}",
    )) + "\n"


def simulate_config(jar: Path, config: Path, settings: str) -> str:
    config.write_bytes(settings.encode("utf-8"))
    return simulate(jar, config)


def check_historical_csv(filename: str, columns: tuple[str, ...], actual: list[dict]) -> None:
    path = ROOT / "results/pon-report-delivery" / filename
    with path.open(encoding="utf-8", newline="") as source:
        expected = list(csv.DictReader(source))
    observed = [{column: str(row.get(column, "")) for column in columns} for row in actual]
    require(observed == expected, f"Disabled BWmap changed the checked-in step 5 result: {filename}")


def check_previous_bwmap_off(jar: Path, config: Path) -> None:
    settings = delivery_scenario()
    original, _ = run_pair(jar, config, settings)
    disabled = simulate_config(jar, config, settings + f"{REPORT_ENABLED}=true\n{BWMAP_ENABLED}=false\n")
    require(disabled == original, "Explicitly disabled BWmap changed the DATA or REPORT trace")
    actual = parse(disabled)
    deliveries, received = check_delivery(actual)
    states = check_mfu_state(actual, received)
    check_historical_csv("report-delivery-trace.csv", DELIVERY_COLUMNS, deliveries)
    check_historical_csv("mfu-report-state.csv", STATE_COLUMNS, states)


def check_packet_events(trace: list[dict[str, str]]) -> list[dict]:
    queues = packet_events(trace, "PON_UPLINK_ENQUEUE", "SFU")
    grants = packet_events(trace, "PON_UPLINK_GRANT", "MFU")
    bwmap_sent = packet_events(trace, "PON_BWMAP_TX", "MFU")
    bwmap_received = packet_events(trace, "PON_BWMAP_RX", "SFU")
    data_sent = packet_events(trace, "FIBER_TX_START", "SFU")
    data_ends = packet_events(trace, "FIBER_TX_END", "SFU")
    delivered = packet_events(trace, "DELIVERED", "MFU")
    packet_ids = list(range(1, len(ARRIVALS_US) + 1))
    require(all(list(events) == packet_ids for events in
                (queues, grants, bwmap_sent, bwmap_received, data_sent, data_ends, delivered)),
            "Packet conservation or FCFS order failed between queue, grant, BWmap and DATA")

    results = []
    previous_end = 0
    previous_frame = 0
    for packet_id in packet_ids:
        enqueue, grant, bw_tx, bw_rx, started, finished, arrival = (
            events[packet_id] for events in (queues, grants, bwmap_sent, bwmap_received, data_sent, data_ends, delivered)
        )
        row = {column: int(bw_rx[column]) for column in BWMAP_COLUMNS}
        require(all(int(bw_tx[column]) == row[column] for column in BWMAP_COLUMNS if column != "grant_rx_ns"),
                f"BWmap contents changed before SFU reception for packet {packet_id}")
        source = 2 if packet_id % 2 else 1
        require(all(int(event["sfu_id"]) == source for event in (enqueue, grant, bw_tx, bw_rx, started, finished, arrival)),
                f"SFU changed along the packet path for packet {packet_id}")
        require(row["packet_id"] == packet_id and row["granted_bytes"] == PAYLOAD_BYTES
                and all(int(event["bytes"]) == PAYLOAD_BYTES for event in (enqueue, started, finished, arrival)),
                f"Report size differs from DATA size for packet {packet_id}")
        frame = row["grant_frame_id"]
        target = row["target_frame_id"]
        grant_ns = row["grant_tx_ns"]
        start_ns = row["tx_start_ns"]
        end_ns = row["tx_end_ns"]
        enqueue_ns = ns(enqueue["t_us"])
        expected_frame = 1 if packet_id <= 32 else 2 if packet_id == 33 else 3
        require(frame == expected_frame == int(grant["frame_id"]) and target == frame + 1,
                f"Unexpected or repeated allocation frame for packet {packet_id}")
        require(enqueue_ns == ARRIVALS_US[packet_id - 1] * 1000 == int(grant["enqueue_time_ns"])
                and enqueue_ns < grant_ns == frame * PERIOD_NS == ns(bw_tx["t_us"]) == int(grant["grant_time_ns"]),
                f"Packet {packet_id} was not eligible at the scheduling boundary")
        require(row["grant_rx_ns"] == grant_ns + DOWNLINK_PROPAGATION_US * 1000 == ns(bw_rx["t_us"])
                and row["grant_rx_ns"] < start_ns,
                f"SFU did not receive a valid BWmap before transmission for packet {packet_id}")
        require(start_ns == (previous_end if target == previous_frame else target * PERIOD_NS)
                and previous_end <= start_ns < end_ns <= (target + 1) * PERIOD_NS
                and end_ns - start_ns == SERIALIZATION_NS,
                f"Slot overlap, incorrect duration or incorrect target boundary for packet {packet_id}")
        require(start_ns == ns(started["t_us"]) == int(grant["tx_start_ns"])
                and end_ns == ns(finished["t_us"]) == int(grant["tx_end_ns"])
                and ns(arrival["t_us"]) == end_ns + ns(PROPAGATION_US),
                f"DATA did not follow its reserved slot and propagation for packet {packet_id}")
        results.append(row)
        previous_end = end_ns
        previous_frame = target
    return results


def check_reports(trace: list[dict[str, str]], grants: list[dict]) -> None:
    snapshots = report_rows(trace, REPORT_ACTION, "SFU")
    # 帧 2 只剩第 33 包；第 34 包在同刻帧调度之后入队。帧 4 时所有报文都已预约。
    expected_counts = ((0, 0), (16, 17), (0, 1), (1, 1), (0, 0))
    require(set(snapshots) == {(frame, sfu) for frame in range(len(expected_counts)) for sfu in (1, 2)},
            "Missing or unexpected pre-grant REPORT snapshots")
    for (frame, sfu), snapshot in snapshots.items():
        count = expected_counts[frame][sfu - 1]
        require(int(snapshot["snapshot_time_ns"]) == frame * PERIOD_NS
                and all(int(snapshot[f"{metric}_packets"]) == count
                        and int(snapshot[f"{metric}_bytes"]) == count * PAYLOAD_BYTES
                        for metric in ("pending", "eligible")),
                f"Previously reserved packet leaked into the ungranted queue at frame {frame}, SFU {sfu}")

    first_grants = {}
    for grant in grants:
        first_grants.setdefault((grant["grant_frame_id"], grant["sfu_id"]), grant)
    received = report_rows(trace, REPORT_RX, "MFU")
    require(set(received) == set(first_grants), "REPORT reception does not match one first slot per frame and SFU")
    for key, report in received.items():
        grant = first_grants[key]
        snapshot = snapshots[key]
        require(int(report["packet_id"]) == grant["packet_id"]
                and int(report["receive_time_ns"]) == ns(report["t_us"]) == grant["tx_end_ns"] + ns(PROPAGATION_US)
                and all(report[column] == snapshot[column]
                        for column in ("snapshot_time_ns", "pending_bytes", "eligible_bytes"))
                and report["applied"] == "true",
                f"REPORT did not follow the first actual transmission with its original snapshot for {key}")

    states = report_rows(trace, REPORT_STATE, "MFU")
    for sfu in (1, 2):
        require(states[(2, sfu)]["status"] == "UNKNOWN"
                and states[(3, sfu)]["latest_report_frame_id"] == "1",
                f"MFU observed the REPORT before reception or lost it after reception for SFU {sfu}")


def check_frame_and_wait_summaries(trace: list[dict[str, str]], grants: list[dict]) -> None:
    frame_rows = [row for row in trace if row.get("summary") == "PON_UPLINK_FRAME"]
    expected_counts = (0, 0, 32, 1, 2)
    require(len(frame_rows) == len(expected_counts), "Physical frame window differs from the planned slot window")
    for frame, (row, count) in enumerate(zip(frame_rows, expected_counts)):
        busy = count * SERIALIZATION_NS
        expected = dict(frame_id=frame, frame_start_ns=frame * PERIOD_NS, frame_end_ns=(frame + 1) * PERIOD_NS,
                        allocated_packets=count, busy_ns=busy, idle_ns=PERIOD_NS - busy)
        require(all(int(row[key]) == value for key, value in expected.items()),
                f"Incorrect slot capacity or busy time for physical frame {frame}")
        check_ratio(row["utilization"], busy, PERIOD_NS, f"physical frame {frame}")

    totals = [row for row in trace if row.get("summary") == "PON_BWMAP"]
    require(len(totals) == 1 and int(totals[0]["packets"]) == len(grants), "Invalid BWmap wait summary")
    sums = {
        "avg_ungranted_wait_us": sum(row["grant_tx_ns"] - ARRIVALS_US[row["packet_id"] - 1] * 1000 for row in grants),
        "avg_reserved_wait_us": sum(row["tx_start_ns"] - row["grant_tx_ns"] for row in grants),
        "avg_serialization_us": sum(row["tx_end_ns"] - row["tx_start_ns"] for row in grants),
    }
    for metric, total_ns in sums.items():
        expected = Decimal(total_ns) / (len(grants) * 1000)
        require(abs(Decimal(totals[0][metric]) - expected) <= Decimal("0.0000005"),
                f"Incorrect waiting or serialization statistic: {metric}")


def packet_state_rows(trace: list[dict[str, str]], grants: list[dict]) -> list[dict]:
    by_packet = {row["packet_id"]: row for row in grants}
    packet_states = {packet_id: [] for packet_id in by_packet}
    rows = []
    for log_row in trace:
        state = PACKET_STATES.get((log_row.get("node"), log_row.get("event")))
        if state is None:
            continue
        packet_id = int(log_row["packet_id"])
        grant = by_packet[packet_id]
        packet_states[packet_id].append(state)
        # 未授权时尚无目标帧，只在预约开始后的记录中填写授权字段。
        rows.append(dict(packet_id=packet_id, sfu_id=grant["sfu_id"], state=state, time_ns=ns(log_row["t_us"]),
                         grant_frame_id="" if state == "QUEUED_UNGRANTED" else grant["grant_frame_id"],
                         target_frame_id="" if state == "QUEUED_UNGRANTED" else grant["target_frame_id"],
                         bytes=grant["granted_bytes"]))
    require(all(states == list(PACKET_STATES.values()) for states in packet_states.values()),
            "A packet skipped or repeated a state transition")
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-bwmap")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR with mvn -o clean package")
    jar = args.jar.resolve()
    settings = bwmap_scenario()
    enabled = settings + f"{REPORT_ENABLED}=true\n{BWMAP_ENABLED}=true\n"
    with TemporaryDirectory(prefix="fttr-pon-bwmap-") as directory:
        config = Path(directory) / "test-config.properties"
        check_previous_bwmap_off(jar, config)
        on, off = run_pair(jar, config, settings + f"{BWMAP_ENABLED}=false\n")
        check_previous_stage(on, off)
        log = simulate_config(jar, config, enabled)
        require(log == simulate(jar, config), "Repeated BWmap simulation produced a different trace")

    trace = parse(log)
    grants = check_packet_events(trace)
    check_reports(trace, grants)
    check_frame_and_wait_summaries(trace, grants)
    states = packet_state_rows(trace, grants)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "test-config.properties").write_bytes(enabled.encode("utf-8"))
    write_csv(args.output / "bwmap-trace.csv", BWMAP_COLUMNS, grants)
    write_csv(args.output / "packet-state-trace.csv", STATE_TRACE_COLUMNS, states)
    print(f"Verified {len(grants)} logical grants and {len(states)} packet state transitions across both SFUs.")
    print("Previous FCFS and REPORT results match with BWmap disabled. Generated artifacts under", args.output)


if __name__ == "__main__":
    main()
