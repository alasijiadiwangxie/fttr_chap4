"""Paired SI and polling diagnostics for the 3050 Mb/s T2 workload."""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass, replace
import gzip
from io import StringIO
import json
from math import ceil
from pathlib import Path
from statistics import mean

from pon_giant_completion_workload import COMPLETE, RR, SEEDS
from pon_qos_special_metrics import LATENCY_COLUMNS, LOSS_COLUMNS, SLA_COLUMNS, analyze_qos_run, latency
from pon_t2_delay_trace import T2_GRANT_COLUMNS, t2_grant_trace
from pon_execution_trace import fields
from pon_experiment_io import ROOT, require
from run_pon_qos_special import capture, input_hash, sha
from run_pon_t2_delay import FRAME_NS, GRANT_FIELDS, T2_KEYS, T2Experiment
from run_pon_t2_delay import events_and_grants, experiments as previous_experiments, record_csv


OUTPUT = ROOT / "results/pon-t2-tail"
SI_KEYS = {key.replace(".ab.min.bytes", ".si.max.frames") for key in T2_KEYS}
POLL_KEY = "fiber.uplink.poll.interval.frames"
MODE_KEY = "fiber.uplink.dba.mode"
DEFAULT_JAR = ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar"
STARTUP_EXCLUSION_NS = 5_000_000


@dataclass(frozen=True)
class TailExperiment(T2Experiment):
    variant: str = ""
    si_max_t2_frames: int = 1
    poll_interval_frames: int = 1


def experiments() -> list[TailExperiment]:
    bases = {case.seed: case for case in previous_experiments()
             if case.policy == COMPLETE and case.abmin_t2_bytes == 3600}
    cases = []
    for seed in SEEDS:
        base = bases[seed]
        reference = TailExperiment(**vars(base))
        for variant, policy, si, ab, poll in (
            ("giant-si1", COMPLETE, 1, 3600, 1),
            ("giant-si2", COMPLETE, 2, 7200, 1),
            ("rr", RR, 1, 3600, 1),
            ("giant-poll4", COMPLETE, 1, 3600, 4),
        ):
            settings = dict(base.config)
            settings.update(dict.fromkeys(T2_KEYS, ab))
            settings.update(dict.fromkeys(SI_KEYS, si))
            settings.update({MODE_KEY: policy, POLL_KEY: poll})
            cases.append(replace(reference, name=f"overload-3050-{seed}-{variant}", variant=variant,
                                 policy=policy, abmin_t2_bytes=ab, si_max_t2_frames=si,
                                 poll_interval_frames=poll, config=settings))
    return cases


def polling_rows(log: str, case: TailExperiment) -> list[dict]:
    """Measure executed REPORT-only airtime from slot events, excluding propagation."""
    tx_start = {}
    observed = []
    cutoff = case.traffic_end_us * 1000
    for raw in StringIO(log):
        if "event=PON_POLL_TX_START " not in raw and "event=PON_POLL_TX_END " not in raw:
            continue
        event = fields(raw)
        ident = event["poll_id"]
        if event["event"] == "PON_POLL_TX_START":
            require(ident not in tx_start and event["time_ns"] == event["tx_start_ns"],
                    "Duplicate or misaligned REPORT-only start")
            tx_start[ident] = event
            continue
        start = tx_start.pop(ident)
        keys = ("allocation_id", "sfu_id", "grant_frame_id", "target_frame_id", "tx_start_ns", "tx_end_ns")
        require(all(start[key] == event[key] for key in keys), "REPORT-only identity changed")
        begin, end = int(event["tx_start_ns"]), int(event["tx_end_ns"])
        frame = int(event["target_frame_id"])
        require(begin == frame * FRAME_NS and end == int(event["time_ns"]) and begin < end <= begin + FRAME_NS,
                "Executed REPORT-only slot outside its frame")
        observed.append(dict(scenario_id=case.name, variant=case.variant, seed=case.seed,
                             poll_interval_frames=case.poll_interval_frames, poll_id=ident,
                             **{key: int(event[key]) for key in keys}, control_ns=end - begin,
                             in_observation=end <= cutoff))
    require(not tx_start and observed, "Missing REPORT-only completion events")
    require(all(
        second["target_frame_id"] - first["target_frame_id"] == case.poll_interval_frames
        for first, second in zip(observed, observed[1:])
    ), "Wrong executed polling interval")
    return observed


SERVICE_FIELDS = ("previous_service_frame_id", "previous_service_start_ns", "service_gap_frames", "service_gap_ns")


def record_service_intervals(frames: list[dict]) -> None:
    """Track actual positive load events, including intervening skipped frames."""
    previous = {}
    for row in frames:
        if row["actual_bytes"] <= 0:
            continue
        allocation = row["allocation_id"]
        if allocation in previous:
            earlier = previous[allocation]
            row.update(previous_service_frame_id=earlier["target_frame_id"],
                       previous_service_start_ns=earlier["slot_start_ns"],
                       service_gap_frames=row["target_frame_id"] - earlier["target_frame_id"],
                       service_gap_ns=row["slot_start_ns"] - earlier["slot_start_ns"])
        previous[allocation] = row


def service_summary(frames: list[dict], case: TailExperiment) -> list[dict]:
    groups = defaultdict(list)
    for row in frames:
        if row["in_observation"]:
            groups[row["allocation_id"]].append(row)
    rows = []
    for allocation, group in groups.items():
        gaps = [row["service_gap_frames"] for row in group if "service_gap_frames" in row]
        times = sorted(row["service_gap_ns"] for row in group if "service_gap_ns" in row)
        ages = [row["report_age_ns"] for row in group if "report_age_ns" in row]
        require(gaps and ages, "T2 service or report samples missing")
        rows.append(dict(scenario_id=case.name, variant=case.variant, seed=case.seed,
                         allocation_id=allocation, sfu_id=group[0]["sfu_id"],
                         si_max_t2_frames=case.si_max_t2_frames, abmin_t2_bytes=case.abmin_t2_bytes,
                         poll_interval_frames=case.poll_interval_frames, observed_decisions=len(group),
                         positive_service_frames=sum(row["actual_bytes"] > 0 for row in group),
                         si_not_due_frames=sum(row.get("assured_reason") == "NOT_DUE" for row in group),
                         mean_service_gap_frames=mean(gaps), max_service_gap_frames=max(gaps),
                         mean_service_gap_ns=mean(times), p95_service_gap_ns=times[ceil(.95 * len(times)) - 1],
                         max_service_gap_ns=max(times), report_age_mean_ns=mean(ages),
                         latest_poll_report_samples=sum(row.get("report_kind") == "REPORT_ONLY" for row in group),
                         latest_settlement_report_samples=sum(row.get("report_kind") == "SETTLEMENT" for row in group),
                         mean_decision_fifo_bytes=mean(row["decision_fifo_bytes"] for row in group),
                         mean_uncovered_fifo_bytes=mean(row["offline_uncovered_fifo_bytes"] for row in group),
                         mean_known_demand_bytes=mean(row["known_demand_bytes"] for row in group
                                                      if row["report_state"] == "KNOWN"),
                         uncovered_gt_known_frames=sum(row["offline_backlog_gt_known"] for row in group),
                         unknown_with_backlog_frames=sum(row["unknown_with_offline_backlog"] for row in group),
                         cap_full_with_backlog_frames=sum(row.get("cap_exhausted_with_more_known", False)
                                                          and row["actual_bytes"] == case.abmin_t2_bytes
                                                          and (row.get("remaining_after_fill_packets") or 0) > 0
                                                          for row in group),
                         rr_outstanding_skip_frames=sum(row.get("rr_outstanding_without_grant", False)
                                                        for row in group),
                         assured_shortfall_bytes=sum(row.get("assured_shortfall_bytes", 0) for row in group),
                         max_nonempty_streak_frames=max(row["backlog_streak_frames"] for row in group)))
    return rows


def warmup_check(events: list[dict], case: TailExperiment) -> dict:
    """Retain the 100 ms cutoff and omit arrivals in the first 5 ms for both policies."""
    packets = {}
    for event in events:
        if event["event"] == "TCONT_ENQUEUE" and event["_time_ns"] >= STARTUP_EXCLUSION_NS:
            packets[event["packet_id"]] = {"pon_enqueue_ns": event["_time_ns"]}
        elif event["event"] == "PON_DBA_FILL":
            for ident in event["selected_packet_ids"].split(";"):
                if ident in packets:
                    packets[ident]["pon_load_ns"] = event["_time_ns"]
    return dict(scenario_id=case.name, variant=case.variant, seed=case.seed,
                enqueue_at_or_after_ns=STARTUP_EXCLUSION_NS, observation_end_ns=case.traffic_end_us * 1000,
                **latency(list(packets.values()), case.traffic_end_us * 1000))


def read_run(output: Path, case: TailExperiment) -> tuple[str, dict]:
    config = Path("configs", f"{case.name}.properties")
    log_file = Path("raw", f"{case.name}.log.gz")
    receipt = json.loads((output / log_file.with_suffix("").with_suffix(".json")).read_text(encoding="utf-8"))
    settings = (output / config).read_bytes()
    log = gzip.decompress((output / log_file).read_bytes())
    require(settings == case.properties().encode("utf-8") and sha(settings) == receipt["config_sha256"]
            and sha(log) == receipt["raw_log_utf8_sha256"], f"{case.name}: archive or configuration mismatch")
    return log.decode("utf-8"), dict(scenario_id=case.name, variant=case.variant, policy=case.policy,
                                    seed=case.seed, si_max_t2_frames=case.si_max_t2_frames,
                                    abmin_t2_bytes=case.abmin_t2_bytes, poll_interval_frames=case.poll_interval_frames,
                                    traffic_end_us=case.traffic_end_us, decision_end_us=case.decision_end_us,
                                    config=config.as_posix(), raw_log=log_file.as_posix(), **receipt)


def analyze(output: Path, cases: list[TailExperiment]) -> None:
    from pon_t2_tail_packets import T2_TAIL_EVENT_COLUMNS, T2_TAIL_SUMMARY_COLUMNS, packet_index, tail_outputs

    references = {case.seed: case.config for case in cases if case.variant == "giant-si1"}
    allowed_changes = {"giant-si1": set(), "giant-si2": SI_KEYS | T2_KEYS, "rr": {MODE_KEY},
                       "giant-poll4": {POLL_KEY}}
    workload, traces, services, metrics, polls, index = {}, [], [], [], [], []
    warmup = []
    packet_indices = []
    for case in cases:
        log, receipt = read_run(output, case)
        baseline = references[case.seed]
        require({key for key in baseline.keys() | case.config.keys()
                 if baseline.get(key) != case.config.get(key)} == allowed_changes[case.variant],
                f"{case.name}: unexpected settings change")
        result = analyze_qos_run(log, case)
        require(workload.setdefault(case.seed, result.workload) == result.workload,
                f"{case.name}: inputs differ")
        events, totals = events_and_grants(log, case.traffic_end_us * 1000)
        frames = t2_grant_trace(events, case)
        frame_polls = polling_rows(log, case)
        observed_polls = [row for row in frame_polls if row["in_observation"]]
        control_by_frame = {row["target_frame_id"]: row["tx_end_ns"] for row in frame_polls}
        require(all(row["slot_start_ns"] >= control_by_frame.get(row["target_frame_id"], 0)
                    for row in frames if "slot_start_ns" in row), "T2 grant overlaps a poll")
        for i, key in enumerate(GRANT_FIELDS[:3]):
            source = ("granted_bytes", "actual_bytes", "unused_bytes")[i]
            require(totals["2"][i] == sum(row[source] for row in frames if row["in_observation"]),
                    f"{case.name}: per-frame T2 sum differs for {key}")
        record_service_intervals(frames)
        for row in frames:
            row.update(variant=case.variant, poll_interval_frames=case.poll_interval_frames)
        traces.extend(frames)
        services.extend(service_summary(frames, case))
        warmup.append(warmup_check(events, case))
        polls.extend(frame_polls)
        if case.variant in {"giant-si1", "rr"}:
            packet_indices.append(packet_index(log, case, frames))
        identify = lambda row: (row["scope"], row["allocation_id"], row["tcont_type"])
        sla = {identify(row): row for row in result.sla_rows}
        loss = {identify(row): row for row in result.loss_rows}
        count = len(observed_polls)
        busy = sum(row["control_ns"] for row in observed_polls)
        for wait in result.latency_rows:
            key = identify(wait)
            amounts = totals.get(key[1] if key[0] == "alloc" else key[2], (0, 0, 0, 0))
            metrics.append(dict(wait | sla[key] | loss[key] | dict(zip(GRANT_FIELDS, amounts)),
                                variant=case.variant, si_max_t2_frames=case.si_max_t2_frames,
                                abmin_t2_bytes=case.abmin_t2_bytes, poll_interval_frames=case.poll_interval_frames,
                                link_poll_slots=count, link_poll_busy_ns=busy,
                                link_poll_utilization=busy / (case.traffic_end_us * 1000)))
        index.append(dict(receipt, generated_packets=len(result.workload), input_sha256=input_hash(result.workload)))
        print(f"Analyzed {case.name}: {len(result.workload)} input packets, {count} executed polls in window",
              flush=True)

    require(len({row["jar_sha256"] for row in index}) == 1, "Multiple Java builds in paired experiment")
    columns = ("variant", "si_max_t2_frames", "abmin_t2_bytes", "poll_interval_frames",
               "link_poll_slots", "link_poll_busy_ns", "link_poll_utilization") + tuple(dict.fromkeys(
                   LATENCY_COLUMNS + SLA_COLUMNS + LOSS_COLUMNS + GRANT_FIELDS))
    for filename, fields, rows in (
        ("si-metrics.csv", columns, metrics),
        ("t2-service-trace.csv", ("variant", "poll_interval_frames") + T2_GRANT_COLUMNS + SERVICE_FIELDS, traces),
        ("t2-service-summary.csv", tuple(services[0]), services),
        ("t2-warmup-check.csv", tuple(warmup[0]), warmup),
        ("poll-slots.csv", tuple(polls[0]), polls),
        ("raw-index.csv", tuple(index[0]), index),
    ):
        record_csv(output / filename, fields, rows)
    tail_events, tail_packets = tail_outputs(packet_indices)
    record_csv(output / "t2-tail-events.csv", T2_TAIL_EVENT_COLUMNS, tail_events)
    record_csv(output / "t2-tail-packets.csv", T2_TAIL_SUMMARY_COLUMNS, tail_packets)
    print(f"Verified {len(cases)} Java runs with {len(workload)} matched input streams. "
          f"Exported {len(tail_packets)} paired packet rows and {len(tail_events)} event rows.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--capture-only", action="store_true")
    mode.add_argument("--reuse-raw", action="store_true")
    args = parser.parse_args()
    cases = experiments()
    if not args.reuse_raw:
        capture(args.jar.resolve(), args.output, cases)
    if not args.capture_only:
        analyze(args.output, cases)


if __name__ == "__main__":
    main()
