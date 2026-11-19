"""运行 REPORT–Grant 需求账本场景，并从实际 Java 日志生成两份复核轨迹。"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_bwmap_test import (
    BWMAP_COLUMNS, BWMAP_ENABLED, STATE_TRACE_COLUMNS, bwmap_scenario, check_packet_events,
    packet_state_rows, simulate_config,
)
from run_pon_frame_test import ROOT, ns, packet_events, require, write_csv
from run_pon_report_delivery_test import REPORT_RX, parse, report_key, report_rows
from run_pon_report_test import REPORT_ACTION, REPORT_ENABLED


LEDGER_ENABLED = "fiber.uplink.demand.ledger.enabled"
LEDGER_EVENT = "PON_DEMAND_LEDGER"
GRANT_EVENT = "PON_DEMAND_LEDGER_GRANT"
LEDGER_TOKENS = {f"event={LEDGER_EVENT}", f"event={GRANT_EVENT}"}
ESTIMATE_FIELDS = (
    "latest_report_frame_id", "report_snapshot_ns", "report_receive_ns", "reported_pending_bytes",
    "granted_bytes_since_snapshot", "effective_known_bytes", "report_age_ns",
)
LEDGER_COLUMNS = ("checkpoint_id", "time_ns", "sfu_id", *ESTIMATE_FIELDS, "state", "trigger")
RECONCILIATION_COLUMNS = (
    "checkpoint_id", "time_ns", "sfu_id", "latest_report_frame_id", "report_snapshot_ns", "grant_id",
    "grant_create_ns", "grant_bytes", "included", "counted_grant_bytes", "deducted_bytes",
    "effective_known_bytes_after_grant",
)


def ledger_scenario() -> str:
    return """# Deterministic manual traffic, with zero Wi-Fi delay to isolate optical timing.
# SFU 1 starts with 5 x 1200 B. The last 1200 B packet arrives at 390 us.
# The two large SFU 2 packets enforce the intended strict FCFS allocation across frames.
traffic.mode=manual
traffic.direction=UL
sfu.count=2
sta.count=2
sta.1.sfu.id=1
sta.2.sfu.id=2
wifi.model=fixed
wifi.delay.us=0
wifi.mac.access=fixed-priority
fiber.uplink.mode=pon-framed
fiber.uplink.frame.period.us=125
fiber.uplink.rate.mbps=2500
fiber.uplink.propagation.us=60
fiber.downlink.mode=pon-shared
fiber.downlink.rate.mbps=10000
fiber.downlink.propagation.us=10
fiber.uplink.report.enabled=true
fiber.uplink.bwmap.enabled=true
packet.bytes=1200
packet.arrival.us=0,1,2,3,4,5,6,390
packet.3.sta.id=2
packet.3.bytes=36000
packet.4.sta.id=2
packet.4.bytes=38400
"""


def without_ledger(log: str) -> str:
    return "".join(line for line in log.splitlines(keepends=True) if LEDGER_TOKENS.isdisjoint(line.split()))


def check_parity(jar: Path, config: Path, settings: str) -> tuple[str, str]:
    default = simulate_config(jar, config, settings)
    disabled = simulate_config(jar, config, settings + f"{LEDGER_ENABLED}=false\n")
    enabled = simulate_config(jar, config, settings + f"{LEDGER_ENABLED}=true\n")
    require(disabled == default == without_ledger(default), "Disabled or omitted ledger setting changed the trace")
    require(without_ledger(enabled) == disabled, "Demand ledger changed an existing simulation event or statistic")
    return enabled, disabled


def check_previous_stage(jar: Path, config: Path) -> None:
    settings = bwmap_scenario() + f"{REPORT_ENABLED}=true\n{BWMAP_ENABLED}=true\n"
    _, disabled = check_parity(jar, config, settings)
    trace = parse(disabled)
    grants = check_packet_events(trace)
    for filename, columns, rows in (
        ("bwmap-trace.csv", BWMAP_COLUMNS, grants),
        ("packet-state-trace.csv", STATE_TRACE_COLUMNS, packet_state_rows(trace, grants)),
    ):
        with (ROOT / "results/pon-bwmap" / filename).open(encoding="utf-8", newline="") as reference:
            expected = list(csv.DictReader(reference))
        observed = [{column: str(row[column]) for column in columns} for row in rows]
        require(observed == expected, f"FCFS/BWmap differs from the committed step 6 artifact: {filename}")

    # 另外执行原有上下行混合 EDCA 场景。开启分帧和 BWmap 保证账本实际被调用。
    mixed = (ROOT / "configs/pon-shared-uplink.properties").read_text(encoding="utf-8")
    mixed = mixed.replace("fiber.uplink.mode=pon-shared", "fiber.uplink.mode=pon-framed")
    mixed += f"\n{REPORT_ENABLED}=true\n{BWMAP_ENABLED}=true\n"
    enabled, _ = check_parity(jar, config, mixed)
    require("event=WIFI_ACK_END " in enabled and "event=PON_BWMAP_RX " in enabled
            and f"event={LEDGER_EVENT} " in enabled, "Mixed EDCA fixture missed ACK, BWmap or ledger")


def check_ledger(trace: list[dict[str, str]]) -> tuple[list[dict], list[dict]]:
    created = packet_events(trace, "PON_BWMAP_TX", "MFU")
    instances = report_rows(trace, REPORT_ACTION, "SFU")
    latest_reports = {}
    history = {1: {}, 2: {}}
    ledger_rows = []
    reconciliation = []
    for event in trace:
        action = event.get("event")
        if action not in {REPORT_RX, GRANT_EVENT, LEDGER_EVENT}:
            continue
        sfu = int(event["sfu_id"])
        if action == REPORT_RX:
            if event["applied"] == "true":
                generated = instances[report_key(event)]
                require(all(event[field] == generated[field] for field in ("snapshot_time_ns", "pending_bytes")),
                        "REPORT delivery changed its original queue snapshot")
                latest_reports[sfu] = event
            continue
        if action == GRANT_EVENT:
            grant_id = int(event["grant_id"])
            grant = created[grant_id]
            require(grant_id not in history[sfu] and grant["sfu_id"] == event["sfu_id"]
                    and grant["grant_tx_ns"] == event["grant_create_ns"]
                    and grant["granted_bytes"] == event["grant_bytes"],
                    f"Ledger grant {grant_id} was duplicate or did not match the BWmap reservation")
            history[sfu][grant_id] = (int(event["grant_create_ns"]), int(event["grant_bytes"]))
            continue

        checkpoint = len(ledger_rows) + 1
        time_ns = int(event["time_ns"])
        row = dict(checkpoint_id=checkpoint, time_ns=time_ns, sfu_id=sfu, trigger=event["trigger"],
                   state=event["state"])
        report = latest_reports.get(sfu)
        if report is None:
            require(event["state"] == "UNKNOWN" and all(field not in event for field in ESTIMATE_FIELDS),
                    "MFU reported a numeric queue estimate before receiving a REPORT")
            ledger_rows.append(row)
            continue

        frame = int(report["frame_id"])
        snapshot_ns = int(report["snapshot_time_ns"])
        receive_ns = int(report["receive_time_ns"])
        pending_bytes = int(report["pending_bytes"])
        require(snapshot_ns <= receive_ns <= time_ns, "Known REPORT timestamps are out of order")
        effective_bytes = pending_bytes
        counted_bytes = 0
        for grant_id, (create_ns, size) in history[sfu].items():
            # 只使用检查点之前日志已经出现的预约。同刻快照之后创建的授权也属于该 REPORT。
            require(create_ns <= time_ns, "Future grant counted as already created")
            included = create_ns >= snapshot_ns
            counted = size if included else 0
            after = max(0, effective_bytes - counted)
            reconciliation.append(dict(checkpoint_id=checkpoint, time_ns=time_ns, sfu_id=sfu,
                                       latest_report_frame_id=frame, report_snapshot_ns=snapshot_ns,
                                       grant_id=grant_id, grant_create_ns=create_ns, grant_bytes=size,
                                       included=str(included).lower(), counted_grant_bytes=counted,
                                       deducted_bytes=effective_bytes - after,
                                       effective_known_bytes_after_grant=after))
            effective_bytes = after
            counted_bytes += counted
        expected = dict(latest_report_frame_id=frame, report_snapshot_ns=snapshot_ns, report_receive_ns=receive_ns,
                        reported_pending_bytes=pending_bytes, granted_bytes_since_snapshot=counted_bytes,
                        effective_known_bytes=effective_bytes, report_age_ns=time_ns - snapshot_ns)
        observed = {field: int(event[field]) for field in ESTIMATE_FIELDS}
        require(event["state"] == "KNOWN" and observed == expected,
                f"Actual estimate did not match REPORT and reservations at checkpoint {checkpoint}")
        ledger_rows.append(dict(row, **observed))
    require(set(created) == set().union(*history.values()), "An actual BWmap reservation was not recorded")
    require(len(created) == 8 and all(event["granted_bytes"] for event in created.values()),
            "The expected eight grants were not exercised")
    return ledger_rows, reconciliation


def check_fixture(rows: list[dict]) -> None:
    def select(time_ns: int, sfu_id: int, trigger: str) -> list[dict]:
        return [row for row in rows if (row["time_ns"], row["sfu_id"], row["trigger"]) == (time_ns, sfu_id, trigger)]

    def check_snapshot(time_ns: int, sfu: int, frame: int, pending: int, counted: int, effective: int) -> None:
        reports = select(time_ns, sfu, "REPORT_ACCEPTED")
        require(len(reports) == 1, f"Missing accepted REPORT for SFU {sfu} at {time_ns} ns")
        keys = ("latest_report_frame_id", "reported_pending_bytes", "granted_bytes_since_snapshot",
                "effective_known_bytes")
        require(tuple(reports[0][key] for key in keys) == (frame, pending, counted, effective),
                f"Unexpected report reconciliation for SFU {sfu} at {time_ns} ns")

    require(any(row["state"] == "UNKNOWN" for row in select(250_000, 1, "FRAME_BOUNDARY")),
            "SFU 1 must still be unknown when its first DATA starts")
    check_snapshot(313_840, 1, 1, 6_000, 2_400, 3_600)
    require([row["effective_known_bytes"] for row in select(375_000, 1, "GRANT_CREATED")] == [2_400, 1_200, 0],
            "SFU 1 demand must only fall once per newly created reservation")
    check_snapshot(432_880, 2, 1, 74_400, 74_400, 0)
    before_grant = select(500_000, 1, "FRAME_BOUNDARY")
    require(len(before_grant) == 1 and before_grant[0]["reported_pending_bytes"] == 6_000
            and before_grant[0]["effective_known_bytes"] == 0, "Unreported new ingress changed the MFU estimate")
    check_snapshot(557_880, 2, 2, 38_400, 38_400, 0)
    check_snapshot(563_840, 1, 3, 3_600, 4_800, 0)
    check_snapshot(688_840, 1, 4, 1_200, 1_200, 0)
    require(not select(390_000, 1, "REPORT_ACCEPTED"), "Packet insertion was attributed to a REPORT")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-demand-ledger")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR with mvn -o clean package")
    jar = args.jar.resolve()
    settings = ledger_scenario()
    enabled = settings + f"{LEDGER_ENABLED}=true\n"
    with TemporaryDirectory(prefix="fttr-pon-demand-ledger-") as directory:
        config = Path(directory) / "test-config.properties"
        check_previous_stage(jar, config)
        log, _ = check_parity(jar, config, settings)
        require(log == simulate_config(jar, config, enabled), "Repeated ledger simulations produced different traces")
    rows, reconciliation = check_ledger(parse(log))
    check_fixture(rows)

    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "test-config.properties").write_bytes(enabled.encode("utf-8"))
    write_csv(args.output / "demand-ledger-trace.csv", LEDGER_COLUMNS, rows)
    write_csv(args.output / "grant-reconciliation.csv", RECONCILIATION_COLUMNS, reconciliation)
    print(f"Verified {len(rows)} demand observations and {len(reconciliation)} grant reconciliation rows.")
    print("Ledger on/off full traces, mixed EDCA and step 6 artifacts match. Generated results under", args.output)


if __name__ == "__main__":
    main()
