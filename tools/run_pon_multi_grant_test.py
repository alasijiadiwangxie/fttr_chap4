"""从实际 Java rr-multi 执行导出同帧授权及资源使用，复核旧 rr / FCFS / shadow。"""

from __future__ import annotations

import argparse
import csv
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR
from pathlib import Path
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, require, write_csv
from run_pon_dba_shadow_test import execute
from run_pon_dba_execution_test import events, only, parse
from run_pon_grant_fill_test import verify_protocol_regression
from run_pon_multi_sfu_dba_test import (
    LEDGER_COLUMNS, RR_COLUMNS, check_step_twelve, scenarios as old_scenarios,
    verify_and_export as verify_old_rr, verify_windows,
)


GRANT_COLUMNS = (
    "scenario_id", "decision_time_ns", "target_frame_id", "plan_grant_count", "grant_index",
    "grant_id", "allocation_id", "sfu_id", "known_demand_bytes", "budget_outstanding_at_decision",
    "budget_bytes", "planned_duration_ns", "bwmap_receive_ns", "slot_start_ns", "slot_end_ns",
    "selected_packet_ids", "selected_bytes", "actual_bytes", "unused_bytes", "actual_data_ns",
    "unused_duration_ns", "fill_reason", "delivery_packet_ids", "delivery_times_ns",
    "settlement_receive_ns", "ledger_before_create", "ledger_after_create", "ledger_before_settlement",
    "ledger_after_settlement", "decision_reason", "row_type",
)
FRAME_COLUMNS = (
    "scenario_id", "frame_id", "frame_start_ns", "frame_end_ns", "frame_period_ns",
    "data_grants", "control_reserved_ns", "control_actual_ns", "data_reserved_ns", "data_actual_ns",
    "unused_reserved_ns", "total_reserved_ns", "total_actual_busy_ns", "unreserved_idle_ns",
    "physical_idle_ns", "data_capacity_bytes", "granted_bytes", "actual_bytes", "unused_bytes",
    "within_capacity",
)


def load_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def configuration(packets: list[tuple[int, int, int]], end_us: int = 1250, rate: int = 2500) -> str:
    """每个元组为源STA（固定一STA一SFU）、字节数、生成微秒，全部走真实无线/REPORT路径。"""
    base = (ROOT / "results/pon-dba-execution/test-config.properties").read_text(encoding="utf-8")
    settings = dict(line.split("=", 1) for line in base.splitlines() if line and not line.startswith("#"))
    settings.update({
        "sfu.count": "2", "sta.count": "2", "sta.1.sfu.id": "1", "sta.2.sfu.id": "2",
        "packet.arrival.us": ",".join(str(time_us) for _, _, time_us in packets), "packet.bytes": "1200",
        "fiber.uplink.dba.mode": "rr-multi", "fiber.uplink.poll.end.us": str(end_us),
        "fiber.uplink.rate.mbps": str(rate),
    })
    for index, (sta, packet_bytes, _) in enumerate(packets, 1):
        settings[f"packet.{index}.sta.id"] = str(sta)
        settings[f"packet.{index}.bytes"] = str(packet_bytes)
    return "# Actual Java rr-multi experiment; manual traffic, no bootstrap REPORT or random seed.\n" + "".join(
        f"{key}={value}\n" for key, value in settings.items()
    )


def scenarios() -> dict[str, str]:
    return {
        # 两次自然预热授权之后，双方各保留1200 B需求，在750 us真正形成同帧两预算。
        "same_frame_1200": configuration([(1, 38000, 0), (1, 1200, 0), (2, 1200, 0), (2, 1200, 600)]),
        "outstanding_skip": configuration([(1, 38000, 0), (1, 2000, 0), (2, 1200, 0)]),
        "head_blocked": configuration([(1, 39062, 0), (2, 1200, 0)], 625),
        # 2560 Mb/s下，第一笔1 B为4 ns；剩余124796 ns只能给第二SFU39934 B，不能给39935 B。
        "rounding_capacity": configuration([(1, 39936, 0), (1, 1, 0), (2, 1200, 0), (2, 39935, 400)], 1500, 2560),
        "known_zero": configuration([(2, 1200, 0)], 875),
    }


def check_old_outputs(jar: Path, temporary: Path) -> None:
    """不覆写旧结果；实际重跑原 rr 五场景，逐字段检查原归档CSV。"""
    verify_protocol_regression(jar, temporary)
    check_step_twelve(jar, temporary)
    old_grants, old_ledger = [], []
    for scenario, settings in old_scenarios().items():
        log = execute(jar, temporary / f"old-{scenario}.properties", settings)
        grants, ledger = verify_old_rr(scenario, settings, log)
        old_grants.extend(grants)
        old_ledger.extend(ledger)
    history = ROOT / "results/pon-multi-sfu-dba"
    for filename, columns, observed in (
        ("rr-grant-trace.csv", RR_COLUMNS, old_grants),
        ("per-sfu-ledger.csv", LEDGER_COLUMNS, old_ledger),
    ):
        normalized = [{key: str(row.get(key, "")) for key in columns} for row in observed]
        require(normalized == load_rows(history / filename), f"Prior rr output changed: {filename}")


def matching(trace: list[dict], action: str, frame: str, time: str | None = None) -> list[dict]:
    return [row for row in events(trace, action) if row["target_frame_id"] == frame
            and (time is None or row["time_ns"] == time)]


def duration(row: dict) -> int:
    return int(row["tx_end_ns"]) - int(row["tx_start_ns"])


def check_scenario(name: str, settings: str, log: str) -> tuple[list[dict], list[dict]]:
    trace = parse(log)
    config = dict(line.split("=", 1) for line in settings.splitlines() if line and not line.startswith("#"))
    period = int(Decimal(config["fiber.uplink.frame.period.us"]) * 1000)
    rate = Decimal(config["fiber.uplink.rate.mbps"])
    propagation = int(Decimal(config["fiber.uplink.propagation.us"]) * 1000)
    plans = events(trace, "PON_DBA_MULTI_PLAN")
    require(plans and not events(trace, "PON_DBA_DECISION"), "rr-multi must expose the complete multi plan")
    grants = events(trace, "PON_DBA_BWMAP_TX")
    ids = [row["grant_id"] for row in grants]
    require(len(ids) == len(set(ids)), "Repeated real DATA budget")
    require(not events(trace, "PON_DEMAND_LEDGER_GRANT"), "DATA packet incorrectly debited the budget ledger twice")
    delivered = events(trace, "DELIVERED")
    require(len(delivered) == len({row["packet_id"] for row in delivered}), "Repeated DATA delivery")
    verify_windows(trace)
    grant_rows = []
    cursor = 1
    for plan in plans:
        frame, decision_time = plan["target_frame_id"], plan["time_ns"]
        proposed = matching(trace, "PON_DBA_MULTI_DECISION", frame, decision_time)
        created = matching(trace, "PON_DBA_BWMAP_TX", frame, decision_time)
        states = matching(trace, "PON_DBA_OBJECT_STATE", frame, decision_time)
        by_sfu = {int(row["sfu_id"]): row for row in states}
        require(len(by_sfu) == 2, "Missing per-SFU knowledge at plan time")
        require(int(plan["grant_count"]) == len(proposed) == len(created), "Plan/grant count mismatch")
        require([int(row["grant_index"]) for row in proposed] == list(range(1, len(proposed) + 1)),
                "Non-deterministic grant indices")
        require(len({row["sfu_id"] for row in proposed}) == len(proposed), "Repeated SFU in one frame")
        require(int(plan["planned_bytes"]) == sum(int(row["planned_bytes"]) for row in proposed),
                "Plan bytes do not match independent grants")
        require(int(plan["planned_duration_ns"]) == sum(int(row["planned_duration_ns"]) for row in proposed),
                "Plan times did not sum each independently rounded budget")
        remaining_ns = period - int(plan["control_reserved_ns"])
        eligible = [sfu for sfu in (cursor, 3 - cursor) if by_sfu[sfu]["state"] == "KNOWN"
                    and int(by_sfu[sfu]["known_demand_bytes"]) > 0
                    and by_sfu[sfu]["budget_outstanding"] == "false"]
        expected_sfus = []
        for sfu in eligible:
            capacity = int((Decimal(remaining_ns) * rate / 8000).to_integral_value(rounding=ROUND_FLOOR))
            if capacity == 0:
                break
            expected_sfus.append(sfu)
            proposed_row = proposed[len(expected_sfus) - 1]
            planned_bytes = min(int(by_sfu[sfu]["known_demand_bytes"]), capacity)
            rounded_ns = int((Decimal(planned_bytes) * 8000 / rate).to_integral_value(rounding=ROUND_CEILING))
            require(int(proposed_row["planned_bytes"]) == planned_bytes, "Budget exceeds demand or remaining ns")
            require(int(proposed_row["planned_duration_ns"]) == rounded_ns, "Incorrect individual grant ceil")
            remaining_ns -= rounded_ns
        require([int(row["sfu_id"]) for row in proposed] == expected_sfus, "RR traversal or skip rules changed")
        if expected_sfus:
            cursor = 3 - expected_sfus[-1]
        require(remaining_ns >= 0, "The frame overran after multi-budget rounding")
        if not proposed:
            grant_rows.append({"scenario_id": name, "decision_time_ns": decision_time, "target_frame_id": frame,
                               "plan_grant_count": 0, "budget_bytes": 0, "planned_duration_ns": 0,
                               "decision_reason": plan.get("decision_reason", ""), "row_type": "EMPTY_PLAN"})
            continue
        slot_cursor = int(frame) * period + int(plan["control_reserved_ns"])
        for proposed_row, tx in zip(proposed, created):
            grant_id, owner = tx["grant_id"], int(tx["sfu_id"])
            require(owner == int(proposed_row["sfu_id"]) and int(tx["allocation_id"]) == 2 * owner,
                    "Budget allocation/SFU mapping mismatch")
            group = [row for row in trace if row.get("grant_id") == grant_id]
            require(all(int(row["sfu_id"]) == owner for row in group), "A budget crossed SFU ownership")
            rx = only(group, "PON_DBA_BWMAP_RX")
            filled = only(group, "PON_DBA_FILL")
            completed = only(group, "PON_DBA_SLOT_END")
            settled = only(group, "PON_DBA_SETTLEMENT_RX")
            ledger = only(group, "PON_DBA_LEDGER_CREATED")
            require(int(tx["budget_bytes"]) == int(proposed_row["planned_bytes"]), "Incorrect real budget bytes")
            require(int(tx["tx_start_ns"]) == slot_cursor and duration(tx) == int(proposed_row["planned_duration_ns"]),
                    "Validated multi-budget windows were not executed in sequence")
            require(int(proposed_row["slot_offset_ns"]) == slot_cursor - int(frame) * period,
                    "Executed slot differs from the validated preview offset")
            slot_cursor = int(tx["tx_end_ns"])
            require(int(tx["time_ns"]) <= int(rx["time_ns"]) < int(filled["time_ns"])
                    == int(tx["tx_start_ns"]), "BWmap/load timing is invalid")
            require(int(completed["time_ns"]) == int(tx["tx_end_ns"])
                    and int(settled["time_ns"]) == int(tx["tx_end_ns"]) + propagation,
                    "Settlement was delivered without its actual upstream propagation")
            actual_bytes = int(settled["actual_bytes"])
            unused_bytes = int(settled["unused_bytes"])
            require(actual_bytes == int(filled["selected_bytes"])
                    and actual_bytes + unused_bytes == int(tx["budget_bytes"]), "Budget bytes did not reconcile")
            require(int(filled["selected_duration_ns"]) + int(filled["unused_duration_ns"]) == duration(tx),
                    "Used and unused grant ns did not reconcile")
            require(int(ledger["effective_after"]) == max(0, int(ledger["effective_before"]) - int(tx["budget_bytes"])),
                    "Budget demand was not debited exactly once")
            starts, ends = events(group, "PON_DBA_DATA_START"), events(group, "PON_DBA_DATA_END")
            require([row["packet_id"] for row in starts] == [row["packet_id"] for row in ends], "Unfinished DATA")
            own_delivered = []
            actual_ns = 0
            for start, end in zip(starts, ends):
                require(int(tx["tx_start_ns"]) <= int(start["time_ns"]) < int(end["time_ns"])
                        <= int(tx["tx_end_ns"]), "DATA exceeded its allocated interval")
                actual_ns += int(end["time_ns"]) - int(start["time_ns"])
                delivery = [row for row in delivered if row["packet_id"] == start["packet_id"]]
                require(len(delivery) == 1 and int(delivery[0]["sfu_id"]) == owner
                        and delivery[0]["_time_ns"] == int(end["time_ns"]) + propagation,
                        "DATA delivery was duplicated, lost or misrouted")
                own_delivered.extend(delivery)
            require(actual_ns == int(filled["selected_duration_ns"]), "Actual DATA time differs from per-packet fill")
            require(sum(int(row["bytes"]) for row in own_delivered) == actual_bytes,
                    "Delivered packet bytes differ from the settled actual bytes")
            grant_rows.append({
                "scenario_id": name, "decision_time_ns": decision_time, "target_frame_id": frame,
                "plan_grant_count": plan["grant_count"], "grant_index": proposed_row["grant_index"],
                "grant_id": grant_id, "allocation_id": tx["allocation_id"], "sfu_id": owner,
                "known_demand_bytes": by_sfu[owner]["known_demand_bytes"],
                "budget_outstanding_at_decision": by_sfu[owner]["budget_outstanding"],
                "budget_bytes": tx["budget_bytes"], "planned_duration_ns": duration(tx),
                "bwmap_receive_ns": rx["time_ns"], "slot_start_ns": tx["tx_start_ns"], "slot_end_ns": tx["tx_end_ns"],
                "selected_packet_ids": filled["selected_packet_ids"], "selected_bytes": filled["selected_bytes"],
                "actual_bytes": actual_bytes, "unused_bytes": unused_bytes, "actual_data_ns": actual_ns,
                "unused_duration_ns": filled["unused_duration_ns"], "fill_reason": filled["fill_reason"],
                "delivery_packet_ids": ";".join(row["packet_id"] for row in own_delivered),
                "delivery_times_ns": ";".join(str(row["_time_ns"]) for row in own_delivered),
                "settlement_receive_ns": settled["time_ns"], "ledger_before_create": ledger["effective_before"],
                "ledger_after_create": ledger["effective_after"], "ledger_before_settlement": settled["effective_before"],
                "ledger_after_settlement": settled["effective_after"],
                "decision_reason": plan.get("decision_reason", ""), "row_type": "DATA_GRANT",
            })

    rows_by_frame = {}
    for row in grant_rows:
        if row["row_type"] == "DATA_GRANT":
            rows_by_frame.setdefault(int(row["target_frame_id"]), []).append(row)
    last_frame = max(int(row["target_frame_id"]) for row in plans)
    frame_rows = []
    for frame in range(last_frame + 1):
        control = [row for row in events(trace, "PON_POLL_BWMAP_TX") if int(row["target_frame_id"]) == frame]
        control_done = [row for row in events(trace, "PON_POLL_TX_END") if int(row["target_frame_id"]) == frame]
        frame_grants = rows_by_frame.get(frame, [])
        control_reserved = sum(duration(row) for row in control)
        control_actual = sum(duration(row) for row in control_done)
        reserved = sum(int(row["planned_duration_ns"]) for row in frame_grants)
        actual = sum(int(row["actual_data_ns"]) for row in frame_grants)
        budget_bytes = sum(int(row["budget_bytes"]) for row in frame_grants)
        actual_bytes = sum(int(row["actual_bytes"]) for row in frame_grants)
        capacity = int((Decimal(period - control_reserved) * rate / 8000).to_integral_value(rounding=ROUND_FLOOR))
        require(control_actual == control_reserved and control_reserved + reserved <= period,
                "Unfinished control window or overbooked frame")
        require(0 <= actual <= reserved and budget_bytes <= capacity, "Actual/reserved DATA capacity mismatch")
        frame_rows.append(dict(zip(FRAME_COLUMNS, (
            name, frame, frame * period, (frame + 1) * period, period, len(frame_grants),
            control_reserved, control_actual, reserved, actual, reserved - actual,
            control_reserved + reserved, control_actual + actual, period - control_reserved - reserved,
            period - control_actual - actual, capacity, budget_bytes, actual_bytes, budget_bytes - actual_bytes, True,
        ))))
    summary = next(row for row in trace if row.get("summary") == "PON_DBA_EXECUTION")
    actual_grants = [row for row in grant_rows if row["row_type"] == "DATA_GRANT"]
    for field, summed in (("budget_bytes", "budget_bytes"), ("actual_bytes", "actual_bytes"), ("unused_bytes", "unused_bytes"),
                          ("reserved_ns", "planned_duration_ns"), ("actual_data_ns", "actual_data_ns")):
        require(int(summary[field]) == sum(int(row[summed]) for row in actual_grants), "Grant summary did not reconcile")
    require(len(delivered) + int(summary["pending_packets"]) == len(config["packet.arrival.us"].split(",")),
            "DATA generated/delivered/pending conservation failed")
    frame_summary = next(row for row in trace if row.get("summary") == "PON_UPLINK_FRAMES")
    require(int(frame_summary["frames"]) == len(frame_rows)
            and int(frame_summary["window_ns"]) == period * len(frame_rows), "Frame observation window mismatch")
    require(int(frame_summary["busy_ns"]) == sum(row["total_actual_busy_ns"] for row in frame_rows)
            and int(frame_summary["idle_ns"]) == sum(row["physical_idle_ns"] for row in frame_rows),
            "Actual physical frame statistics counted unused reservation as busy")
    check_special_case(name, trace, grant_rows, frame_rows)
    return grant_rows, frame_rows


def check_special_case(name: str, trace: list[dict], grants: list[dict], frames: list[dict]) -> None:
    real = [row for row in grants if row["row_type"] == "DATA_GRANT"]
    if name == "same_frame_1200":
        paired = [row for row in real if row["target_frame_id"] == "7"]
        require(len(paired) == 2 and [row["sfu_id"] for row in paired] == [1, 2], "Two 1200 B objects not co-scheduled")
        require([row["budget_bytes"] for row in paired] == ["1200", "1200"]
                and [row["actual_bytes"] for row in paired] == [1200, 1200], "Same-frame budgets did not fully transmit")
        require(paired[0]["slot_end_ns"] == paired[1]["slot_start_ns"], "Multi windows were not exactly adjacent")
        first_ends = events([row for row in trace if row.get("grant_id") == paired[0]["grant_id"]], "PON_DBA_DATA_END")
        second_starts = events([row for row in trace if row.get("grant_id") == paired[1]["grant_id"]], "PON_DBA_DATA_START")
        require(first_ends[-1]["_index"] < second_starts[0]["_index"], "Next DATA started before adjacent DATA completed")
    if name == "outstanding_skip":
        require(any(row["sfu_id"] == 2 and row["decision_time_ns"] == "500000" for row in real),
                "An outstanding SFU prevented the other SFU's next-frame allocation")
    if name == "head_blocked":
        first = [row for row in real if row["sfu_id"] == 1]
        require(first and all(row["actual_bytes"] == 0 for row in first), "Oversized FIFO head was split or over-transmitted")
        require([row["sfu_id"] for row in events(trace, "DELIVERED")] == ["2"], "Blocked SFU prevented other SFU delivery")
    if name == "rounding_capacity":
        paired = [row for row in real if row["target_frame_id"] == "7"]
        require([row["budget_bytes"] for row in paired] == ["1", "39934"], "Budgets ignored independent ns rounding")
        budget_frame = next(row for row in frames if row["frame_id"] == 7)
        require(budget_frame["data_reserved_ns"] == 124798 and budget_frame["unreserved_idle_ns"] == 2,
                "Per-budget ceil capacity boundary differs")
        require(paired[1]["actual_bytes"] == 0 and paired[1]["fill_reason"] == "HEAD_BYTES_EXCEED_BUDGET",
                "An undersized rounded budget bypassed whole-packet FIFO")
        require(len(events(trace, "DELIVERED")) == 4, "Rounded residual demand did not later complete")
    if name == "known_zero":
        require(all(row["sfu_id"] == 2 for row in real), "Known-zero SFU received positive DATA budget")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-multi-grant")
    args = parser.parse_args()
    cases = scenarios()
    grants, frames = [], []
    with TemporaryDirectory(prefix="fttr-multi-grant-") as temporary:
        directory = Path(temporary)
        check_old_outputs(args.jar.resolve(), directory)
        for name, settings in cases.items():
            log = execute(args.jar.resolve(), directory / f"{name}.properties", settings)
            case_grants, case_frames = check_scenario(name, settings, log)
            grants.extend(case_grants)
            frames.extend(case_frames)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, settings in cases.items():
        filename = "test-config.properties" if name == "same_frame_1200" else f"{name}.properties"
        (args.output / filename).write_text(settings, encoding="utf-8")
    write_csv(args.output / "grant-trace.csv", GRANT_COLUMNS, grants)
    write_csv(args.output / "frame-usage.csv", FRAME_COLUMNS, frames)
    real = sum(row["row_type"] == "DATA_GRANT" for row in grants)
    print(f"Verified {len(cases)} actual Java rr-multi scenarios, {real} real budgets, "
          f"{len(grants)} grant/empty-plan rows and {len(frames)} frame-usage rows.")
    print("Same-frame DATA, adjacent ends/starts, individual ceil capacity and independent settlement pass.")
    print("Prior rr, FCFS, shadow, REPORT and archived execution/ledger outputs remain unchanged.")


if __name__ == "__main__":
    main()
