"""Validate matched DBA workloads against the Java simulator and export GIANT QoS evidence."""

from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory

from pon_dba_comparison_metrics import METRIC_COLUMNS, PACKET_DELAY_COLUMNS, analyze_run
from pon_giant_completion_workload import COMPLETE, FRAME_NS, Experiment, experiments
from run_pon_dba_execution_test import events, parse
from run_pon_frame_test import ROOT, require, simulate, write_csv


PHASES = ("fixed", "assured", "non_assured", "best_effort")
COUNTERS = ("fixed", "max", "min", "be")
GRANT_COLUMNS = (
    "scenario_id", "policy", "load", "seed", "target_frame_id", "time_ns",
    "in_observation", "allocation_id", "sfu_id", "type", "state", "known_demand_bytes",
    "report_age_ns", "budget_outstanding", "control_reserved_ns",
) + tuple(f"si_{counter}_{field}" for counter in COUNTERS for field in ("frames", "before", "after")) + (
    "ab_fixed_bytes", "ab_min_bytes", "ab_sur_bytes", "ab_be_bytes", "fixed_wanted_bytes",
    "assured_wanted_bytes",
) + tuple(f"{phase}_{field}" for phase in PHASES for field in ("bytes", "reason", "order", "actual_bytes")) + (
    "grant_id", "budget_bytes", "selected_bytes", "actual_bytes", "unused_bytes",
    "slot_start_ns", "slot_end_ns", "bwmap_tx_ns", "bwmap_rx_ns", "settlement_rx_ns",
    "fill_reason", "selected_packet_ids", "known_before_grant", "known_after_grant",
    "known_after_settlement", "priority_inversions",
)
QOS_COLUMNS = ("scenario_id",) + METRIC_COLUMNS + (
    "p99_e2e_delay_ns", "delivered_generation_ratio", "scheduled_grant_frames",
    "actual_service_frames", "max_gap_between_service_frames",
    "max_known_backlog_no_service_frames", "same_type_peers", "jain_same_type_goodput",
    "guarantee_opportunities", "unknown_assured_opportunities",
    "guarantee_wanted_bytes", "guarantee_granted_bytes", "guarantee_actual_bytes",
    "guarantee_grant_ratio", "guarantee_actual_ratio",
    "service_interval_violations", "service_interval_actual_shortfalls", "priority_inversions",
)
PACKET_COLUMNS = ("scenario_id",) + PACKET_DELAY_COLUMNS + ("status", "drop_ns")
MANIFEST_COLUMNS = (
    "scenario_id", "policy", "load", "seed", "offered_mbps", "observation_end_ns", "decision_end_ns",
    "config", "status", "generated_packets", "workload_sha256", "matched_input", "exact_replay",
    "rejection_reason",
)
WITNESS_COLUMNS = ("scenario_id", "target_frame_id", "event_index", "time_ns", "event", "raw_event")


def index(rows: list[dict], fields: tuple[str, ...]) -> dict:
    keyed = {tuple(row[field] for field in fields): row for row in rows}
    require(len(keyed) == len(rows), f"Duplicate event identity: {fields}")
    return keyed


def validate_counter(row: dict, counter: str, phase: str, initial: int, active: bool,
                     previous: dict, expected_quota: int) -> None:
    before, after = (int(row[f"si_{counter}_{edge}"]) for edge in ("before", "after"))
    debit = int(row[f"{phase}_bytes"])
    key = (row["allocation_id"], counter)
    limit = initial if active else 0
    require(before == previous.get(key, limit) and 0 <= before <= limit, f"{key}: invalid timer")
    expected = max(before - 1, 0)
    if before == 1:
        expected = limit if phase in ("fixed", "assured") or debit > 0 else 1
    require(after == expected and 0 <= debit <= expected_quota and (debit == 0 or before == 1),
            f"{key}: invalid timer transition or phase bytes")
    previous[key] = after


def grant_rows(case: Experiment, trace: list[dict]) -> list[dict]:
    states = index(events(trace, "PON_DBA_OBJECT_STATE"), ("target_frame_id", "allocation_id"))
    decisions = index(events(trace, "PON_GIANT_DECISION"), ("target_frame_id", "allocation_id"))
    authorizations = index(events(trace, "PON_DBA_BWMAP_TX"), ("target_frame_id", "allocation_id"))
    receives, settles, loads, debits = [
        index(events(trace, event), ("grant_id",)) for event in ("PON_DBA_BWMAP_RX", "PON_DBA_SETTLEMENT_RX",
                                                                 "PON_DBA_FILL", "PON_DBA_LEDGER_CREATED")
    ]
    require(authorizations.keys() <= states.keys(), "Grant lacks per-object state")
    complete = case.policy == COMPLETE
    expected_ids = str(case.config["fiber.uplink.tcont.alloc.ids"]).split(",")
    expected_frames = range(2, case.decision_end_us * 1000 // FRAME_NS + 1)
    require(states.keys() == {(str(frame), alloc) for frame in expected_frames for alloc in expected_ids},
            "Trace does not cover every expected frame and allocation")
    require(decisions.keys() == states.keys() if complete else not decisions, "Missing or misplaced GIANT decision")
    rows_by_frame = defaultdict(list)
    control = {row["target_frame_id"]: int(row["tx_end_ns"]) - int(row["tx_start_ns"])
               for row in events(trace, "PON_POLL_BWMAP_TX")}
    previous = {}
    for key, state in states.items():
        frame, alloc = key
        prefix = f"fiber.uplink.tcont.alloc.{alloc}."
        kind = int(case.config[prefix + "type"])
        row = {**state, **decisions.get(key, {}), "scenario_id": case.name,
               "policy": case.policy, "load": case.load, "seed": case.seed, "type": kind,
               "control_reserved_ns": control.get(frame, 0),
               "in_observation": int(frame) * FRAME_NS < case.traffic_end_us * 1000,
               "priority_inversions": 0 if complete else ""}
        tx = authorizations.get(key)
        row.update(budget_bytes=0, actual_bytes=0, unused_bytes=0)
        if tx:
            grant_key = (tx["grant_id"],)
            rx, settle, fill, debit = (table[grant_key] for table in (receives, settles, loads, debits))
            row.update(grant_id=tx["grant_id"], budget_bytes=int(tx["budget_bytes"]),
                       selected_bytes=int(fill["selected_bytes"]), actual_bytes=int(settle["actual_bytes"]),
                       unused_bytes=int(settle["unused_bytes"]), slot_start_ns=int(tx["tx_start_ns"]),
                       slot_end_ns=int(tx["tx_end_ns"]), bwmap_tx_ns=tx["time_ns"], bwmap_rx_ns=rx["time_ns"],
                       settlement_rx_ns=settle["time_ns"], fill_reason=fill["fill_reason"],
                       selected_packet_ids=fill["selected_packet_ids"],
                       known_before_grant=debit["effective_before"], known_after_grant=debit["effective_after"],
                       known_after_settlement=settle["effective_after"])
        if complete:
            decision = decisions[key]
            for field in ("state", "known_demand_bytes", "report_age_ns", "budget_outstanding"):
                require(decision[field] == state[field], "GIANT decision disagrees with preceding ledger state")
            require(int(decision["control_reserved_ns"]) == row["control_reserved_ns"]
                    and int(decision["budget_bytes"]) == row["budget_bytes"],
                    "GIANT decision disagrees with the real frame reservation")
            for counter, phase, suffix, active in (
                    ("fixed", "fixed", "fixed", kind in (1, 5)),
                    ("max", "assured", "min", kind in (2, 3, 5)),
                    ("min", "best_effort" if kind == 4 else "non_assured", "sur", kind in (3, 4, 5)),
                    ("be", "best_effort", "be", kind == 5)):
                # Type 4 and Type 5 use different BE timers. Ignore Type 4's unused si_be
                # and avoid enforcing its active phase twice.
                if counter == "be" and kind == 4:
                    continue
                interval = int(row[f"si_{counter}_frames"])
                validate_counter(row, counter, phase, interval, active, previous,
                                 int(row[f"ab_{suffix}_bytes"]) if active else 0)
            sizes = {phase: int(row[f"{phase}_bytes"]) for phase in PHASES}
            require(sum(sizes.values()) == row["budget_bytes"], "Budget is not the sum of its four phases")
            require(not sizes["best_effort"] or kind in (4, 5), "Best-effort phase granted wrong type")
            require(not sizes["non_assured"] or kind in (3, 5), "Non-assured phase granted wrong type")
            if tx:
                require(sizes["fixed"] == int(tx.get("fixed_bytes", 0)), "Fixed phase bytes lost before BWmap")
            dynamic = row["budget_bytes"] - sizes["fixed"]
            known = int(row["known_demand_bytes"])
            require(dynamic == 0 or known >= row["budget_bytes"], "Demand-driven allocation exceeds reported data")
            assured_wanted = int(row["assured_wanted_bytes"])
            fixed_wanted = int(row["ab_fixed_bytes"]) if int(row["si_fixed_before"]) == 1 else 0
            maximum_assured = min(int(row["ab_min_bytes"]), max(0, known - sizes["fixed"]))
            require(assured_wanted == (maximum_assured if int(row["si_max_before"]) == 1 else 0),
                    "Demand-qualified service target differs from timer, REPORT or AB")
            row["fixed_wanted_bytes"] = fixed_wanted
            remainder = row["actual_bytes"]
            # One merged FIFO grant has no per-phase packet labels. Attribute its *bytes* in
            # grant-priority order without inventing extra physical sends.
            for phase in PHASES:
                used = min(remainder, sizes[phase])
                row[f"{phase}_actual_bytes"] = used
                remainder -= used
        rows_by_frame[frame].append(row)

    for rows in rows_by_frame.values():
        if complete:
            ordered = sorted((int(row[f"{phase}_order"]), rank) for rank, phase in enumerate(PHASES)
                             for row in rows if int(row[f"{phase}_bytes"]) > 0)
            require([order for order, _ in ordered] == list(range(1, len(ordered) + 1)),
                    "Grant phases contain a missing or repeated decision order")
            require(all((int(row[f"{phase}_order"]) > 0) == (int(row[f"{phase}_bytes"]) > 0)
                        for row in rows for phase in PHASES), "An empty phase logged an allocation order")
            require([rank for _, rank in ordered] == sorted(rank for _, rank in ordered),
                    "Positive allocation order crossed bandwidth phases")
            slots = [row for row in rows if row["budget_bytes"]]
            slots.sort(key=lambda row: row["slot_start_ns"])
            first = [min(int(row[f"{phase}_order"]) for phase in PHASES if int(row[f"{phase}_bytes"]) > 0)
                     for row in slots]
            require(first == sorted(first), "BWmap order differs from first admission of each merged budget")
            for row in rows:
                missing = (int(row["fixed_bytes"]) < row["fixed_wanted_bytes"]
                           or int(row["assured_bytes"]) < int(row["assured_wanted_bytes"]))
                row["priority_inversions"] = int(missing and any(int(peer["surplus_bytes"]) for peer in rows))
            require(not any(row["priority_inversions"] for row in rows), "Guarantee skipped while surplus was issued")
    return [row for rows in rows_by_frame.values() for row in rows]


def qos_rows(case: Experiment, metrics: list[dict], packets: list[dict], trace: list[dict]) -> list[dict]:
    sample = [row for row in trace if row["in_observation"]]
    by_alloc = defaultdict(list)
    for row in sample:
        by_alloc[row["allocation_id"]].append(row)
    delays = defaultdict(list)
    for packet in packets:
        if packet["status"] == "delivered" and packet["in_observation"]:
            delays[packet["allocation_id"]].append(packet["e2e_delay_ns"])
            delays["TOTAL"].append(packet["e2e_delay_ns"])
    peers = defaultdict(list)
    for row in metrics:
        if row["scope"] == "tcont":
            peers[row["tcont_type"]].append(row["throughput_mbps"])

    for row in metrics:
        row["scenario_id"] = case.name
        alloc = row["allocation_id"]
        group = sample if alloc == "TOTAL" else by_alloc[alloc]
        frame_delays = sorted(delays[alloc])
        row["p99_e2e_delay_ns"] = frame_delays[(99 * len(frame_delays) + 99) // 100 - 1] if frame_delays else ""
        row["delivered_generation_ratio"] = row["delivered_bytes"] / row["generated_bytes"] if row["generated_bytes"] else ""
        row["scheduled_grant_frames"] = len({grant["target_frame_id"] for grant in group if grant["budget_bytes"] > 0})
        actual_frames = sorted({int(grant["target_frame_id"]) for grant in group if grant["actual_bytes"] > 0})
        row["actual_service_frames"] = len(actual_frames)
        row["max_gap_between_service_frames"] = max((b - a for a, b in zip(actual_frames, actual_frames[1:])), default="")
        members = peers.get(row["tcont_type"], [])
        if members:
            row["same_type_peers"] = len(members)
            square_sum = sum(value * value for value in members)
            if square_sum > 0:
                row["jain_same_type_goodput"] = sum(members) ** 2 / (len(members) * square_sum)
        if alloc != "TOTAL":
            streak = maximum = 0
            for opportunity in group:
                streak = streak + 1 if int(opportunity["known_demand_bytes"]) > 0 and not opportunity["actual_bytes"] else 0
                maximum = max(maximum, streak)
            row["max_known_backlog_no_service_frames"] = maximum

        # RR has no protected quota notion. Its actual service, gaps and delay are
        # comparable, but a blank guarantee column cannot be mistaken for a miss.
        if case.policy == COMPLETE:
            wanted_total = granted_total = actual_total = opportunities = misses = actual_shortfalls = unknown = 0
            for grant in group:
                unknown += int(grant["state"] == "UNKNOWN" and int(grant["si_max_before"]) == 1)
                for phase in ("fixed", "assured"):
                    wanted = int(grant[f"{phase}_wanted_bytes"])
                    if wanted:
                        got = int(grant[f"{phase}_bytes"])
                        actual = grant[f"{phase}_actual_bytes"]
                        opportunities += 1
                        misses += int(got < wanted)
                        actual_shortfalls += int(actual < wanted)
                        wanted_total += wanted
                        granted_total += min(wanted, got)
                        actual_total += min(wanted, actual)
            row.update(guarantee_opportunities=opportunities, unknown_assured_opportunities=unknown,
                       guarantee_wanted_bytes=wanted_total, guarantee_granted_bytes=granted_total,
                       guarantee_actual_bytes=actual_total,
                       guarantee_grant_ratio=granted_total / wanted_total if wanted_total else "",
                       guarantee_actual_ratio=actual_total / wanted_total if wanted_total else "",
                       service_interval_violations=misses, service_interval_actual_shortfalls=actual_shortfalls,
                       priority_inversions=sum(grant["priority_inversions"] for grant in group))
    return metrics


def make_witness(case: Experiment, rows: list[dict], trace: list[dict]) -> list[dict]:
    frames = defaultdict(list)
    for row in rows:
        if row["in_observation"]:
            frames[row["target_frame_id"]].append(row)
    witness = next(((number, group) for number, group in frames.items()
                    if all(int(row["assured_bytes"]) > 0 for row in group if row["type"] in (2, 3))
                    and any(int(row["non_assured_bytes"]) > 0 for row in group)
                    and all(int(row["known_demand_bytes"]) > 0 for row in group if row["type"] == 4)
                    and any(int(row["best_effort_bytes"]) > 0 for row in group)
                    and any(row["best_effort_reason"] in ("NO_CAPACITY", "PARTIAL_CAPACITY") for row in group)), None)
    require(witness is not None, "No contested frame with protected and capacity-constrained best-effort service")
    frame, choices = witness
    grants = {row["grant_id"] for row in choices if row.get("grant_id")}
    packets = {packet for row in choices if row.get("grant_id")
               for packet in row["selected_packet_ids"].split(";") if packet != "-"}
    reports = {}
    decision_time = int(choices[0]["time_ns"])
    for row in trace:
        if row["_time_ns"] > decision_time:
            break
        if row.get("event") == "TCONT_REPORT_RX" and row["applied"] == "true":
            reports[row["allocation_id"]] = row["_index"]
    keep_report = set(reports.values())
    actions = {"PON_DBA_OBJECT_STATE", "PON_GIANT_DECISION", "PON_DBA_MULTI_PLAN", "PON_POLL_BWMAP_TX",
               "PON_POLL_BWMAP_RX", "PON_POLL_TX_START", "PON_POLL_TX_END"}
    return [dict(scenario_id=case.name, target_frame_id=frame, event_index=row["_index"],
                 time_ns=row["_time_ns"], event=row["event"], raw_event=row["raw_event"])
            for row in trace if row["_index"] in keep_report or row.get("grant_id") in grants
            or (row.get("target_frame_id") == frame and row.get("event") in actions)
            or (row.get("event") == "DELIVERED" and row.get("packet_id") in packets)]


def write(path: Path, columns: tuple, rows: list[dict]) -> None:
    write_csv(path, columns, [{field: row.get(field, "") for field in columns} for row in rows])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-giant-completion")
    args = parser.parse_args()
    authorized, qos, packets, manifest, witness = [], [], [], [], []
    paired = {}
    baseline_low_input = None
    baseline_low_goodput = None
    configs = {}
    with TemporaryDirectory(prefix="fttr-giant-complete-") as temp:
        for case in experiments():
            settings = {key: str(value) for key, value in case.config.items()}
            path = Path(temp) / f"{case.name}.properties"
            configs[path.name] = case.properties()
            path.write_text(configs[path.name], encoding="utf-8")
            meta = dict(scenario_id=case.name, policy=case.policy, load=case.load, seed=case.seed,
                        offered_mbps=case.offered_mbps, observation_end_ns=case.traffic_end_us * 1000,
                        decision_end_ns=case.decision_end_us * 1000, config=f"configs/{path.name}")
            if case.rejected:
                try:
                    simulate(args.jar.resolve(), path)
                except RuntimeError as exc:
                    require("sla" in str(exc).lower(), f"Wrong rejection for infeasible SLA: {exc}")
                    manifest.append(dict(meta, status="rejected", rejection_reason=str(exc)))
                    print(f"{case.name}: rejected as expected", flush=True)
                    continue
                raise ValueError("Java accepted the infeasible guarantee capacity")
            log = simulate(args.jar.resolve(), path)
            replay = case.name == f"overload-3020-101-{COMPLETE}"
            if replay:
                require(log == simulate(args.jar.resolve(), path), "Fixed-seed replay log differed")
            try:
                result = analyze_run(log, settings, case.load, case.seed, "stage18", case.traffic_end_us * 1000,
                                     case.decision_end_us * 1000, case.traffic_end_us * 1000,
                                     include_undelivered=True)
                trace = parse(log)
                grants = grant_rows(case, trace)
                trial_qos = qos_rows(case, result.metrics, result.packet_delays, grants)
            except (KeyError, ValueError) as exc:
                raise ValueError(f"{case.name}: {exc}") from exc
            for packet in result.packet_delays:
                packet["scenario_id"] = case.name
            total = trial_qos[-1]
            require(len(result.packet_delays) == total["generated_packets"], "Missing terminal or pending status")
            require(not total["dropped_packets"] and not total["late_dropped_packets"], "Unexpected setup loss")
            if case.load == "overload-3020":
                match = (settings, result.workload)
                if case.seed in paired:
                    prior, arrivals = paired[case.seed]
                    require(arrivals == result.workload, "RR and GIANT did not see identical input")
                    require({key for key in prior.keys() | settings.keys() if prior.get(key) != settings.get(key)}
                            == {"fiber.uplink.dba.mode"}, "Paired runs differ in configuration other than policy")
                else:
                    paired[case.seed] = match
                if case.policy == COMPLETE:
                    protected = [row for row in trial_qos if row["tcont_type"] in ("2", "3")]
                    require(all(row["guarantee_grant_ratio"] == 1 and row["guarantee_actual_ratio"] == 1
                                and row["priority_inversions"] == 0 for row in protected),
                            f"{case.name}: feasible protected demand missed its budget or physical service")
                    low_rows = [row for row in trial_qos if row["tcont_type"] == "4"]
                    require(all(row["final_pending_packets"] > 0 and row["actual_service_frames"] > 0
                                and row["jain_same_type_goodput"] > 0.95
                                and row["max_gap_between_service_frames"] <= 4 for row in low_rows),
                            f"{case.name}: shared continuously backlogged Type 4 service was uneven or starved")
                if replay:
                    baseline_low_input = sorted((t, sta, cat, size) for t, _, sta, cat, size in result.workload
                                                if cat in ("BE", "BK"))
                    baseline_low_goodput = sum(row["throughput_mbps"] for row in trial_qos if row["tcont_type"] == "4")
                    witness = make_witness(case, grants, trace)
            elif case.load == "idle-high":
                require(baseline_low_input == sorted((t, sta, cat, size) for t, _, sta, cat, size in result.workload)
                        and total["throughput_mbps"] > baseline_low_goodput,
                        "Removing guaranteed traffic did not recover extra best-effort service on the same low input")
                require(total["guarantee_wanted_bytes"] == 0, "Idle assured queues reserved DATA")
            elif case.load == "mixed-burst-delay":
                require(any(row["type"] == 1 and row["state"] == "UNKNOWN" and row["actual_bytes"] > 0 for row in grants),
                        "Type 1 did not provide actual service before receiving any REPORT")
                require(any(row["type"] == 1 and row.get("fill_reason") == "HEAD_BYTES_EXCEED_BUDGET"
                            and row["unused_bytes"] > 0 for row in grants), "The fixed-grant HOL case was not exercised")
                require(all(any(row["type"] == 5 and int(row[f"{phase}_bytes"]) > 0 for row in grants)
                            for phase in PHASES), "Type 5 did not exhibit all four service components")
            authorized.extend(grants)
            qos.extend(trial_qos)
            packets.extend(result.packet_delays)
            digest = hashlib.sha256(json.dumps(result.workload, separators=(",", ":")).encode("utf-8")).hexdigest()
            manifest.append(dict(meta, status="success", generated_packets=total["generated_packets"],
                                 workload_sha256=digest, exact_replay=replay if replay else "",
                                 matched_input=True if case.load == "overload-3020" else ""))
            print(f"{case.name}: offered={total['offered_mbps']:.2f}, goodput={total['throughput_mbps']:.2f} Mb/s, "
                  f"generated={total['generated_packets']}, final_pending={total['final_pending_packets']}", flush=True)

    out = args.output
    config_dir = out / "configs"
    config_dir.mkdir(parents=True, exist_ok=True)
    for name, config in configs.items():
        (config_dir / name).write_text(config, encoding="utf-8")
    write(out / "giant-grant-trace.csv", GRANT_COLUMNS, authorized)
    write(out / "giant-qos.csv", QOS_COLUMNS, qos)
    write(out / "packet-delay.csv", PACKET_COLUMNS, packets)
    write(out / "run-manifest.csv", MANIFEST_COLUMNS, manifest)
    write(out / "priority-witness.csv", WITNESS_COLUMNS, witness)
    print(f"Verified {len(configs)} configs, {len(authorized)} per-Alloc-ID frame decisions, "
          f"{len(qos)} QoS rows, {len(packets)} packet outcomes, {len(witness)} raw witness events.")


if __name__ == "__main__":
    main()
