"""Paired T2 quota sweep in the existing 3050 Mb/s upstream experiment."""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
import gzip
from io import StringIO
import json
from pathlib import Path

from pon_giant_completion_workload import COMPLETE, RR, SEEDS
from pon_qos_special_workload import SpecialExperiment, SWEEP_PERCENTAGES, _case
from run_pon_dba_execution_test import fields
from run_pon_frame_test import ROOT, require, write_csv
from run_pon_qos_special import capture, input_hash, sha


OUTPUT = ROOT / "results/pon-t2-delay"
QUOTAS = (2400, 3600, 4800)
TRAFFIC_US = 100_000
DECISION_US = 110_000
FRAME_NS = 125_000
T2_KEYS = {f"fiber.uplink.tcont.alloc.{aid}.ab.min.bytes" for aid in (102, 202)}
GRANT_FIELDS = ("authorized_bytes", "used_grant_bytes", "unused_grant_bytes", "grant_count")
QUARTER_NS = 25_000_000


@dataclass(frozen=True)
class T2Experiment(SpecialExperiment):
    abmin_t2_bytes: int = 2400

    def properties(self) -> str:
        return (f"# {self.name}. Observation [0,{self.traffic_end_us}) us.\n"
                + "".join(f"{key}={value}\n" for key, value in self.config.items()))


def experiments() -> list[T2Experiment]:
    rates = {ac: 3050 * share // 100 for ac, share in SWEEP_PERCENTAGES.items()}
    result = []
    for seed in SEEDS:
        for policy, quota in [(COMPLETE, amount) for amount in QUOTAS] + [(RR, QUOTAS[0])]:
            old = _case("load", "overload", 3050, rates, policy, seed)
            settings = dict(old.config)
            settings.update({"traffic.duration.us": TRAFFIC_US, "fiber.uplink.poll.end.us": DECISION_US})
            settings.update(dict.fromkeys(T2_KEYS, quota))
            name = f"overload-3050-{seed}-{policy}" + (f"-ab{quota}" if policy == COMPLETE else "")
            result.append(T2Experiment(name, policy, old.load, seed, TRAFFIC_US, DECISION_US, settings,
                                       old.offered_mbps, family="load", axis_mbps=3050, abmin_t2_bytes=quota))
    return result


def events_and_grants(log: str, cutoff_ns: int) -> tuple[list[dict], dict[str, tuple[int, ...]]]:
    """Index T2 events by original line and tally observed settled grants for every type."""
    from pon_t2_delay_trace import T2_EVENTS

    selected = []
    sums: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0, 0])
    for idx, raw in enumerate(StringIO(log), start=1):
        if ("event=PON_DBA_SETTLEMENT_RX " not in raw
                and "allocation_id=102 " not in raw and "allocation_id=202 " not in raw):
            continue
        row = fields(raw)
        aid, event = row.get("allocation_id", ""), row.get("event", "")
        if event == "PON_DBA_SETTLEMENT_RX" and (int(row["target_frame_id"]) + 1) * FRAME_NS <= cutoff_ns:
            amounts = [int(row[key]) for key in ("budget_bytes", "actual_bytes", "unused_bytes")] + [1]
            require(amounts[0] == amounts[1] + amounts[2], "Executed grant bytes fail conservation")
            for i, value in enumerate(amounts):
                sums[aid][i] += value
        if aid in ("102", "202") and event in T2_EVENTS:
            require("time_ns" in row, f"Expected timestamp on {event}")
            selected.append(dict(row, _index=idx, _time_ns=int(row["time_ns"]), raw_event=raw.rstrip("\r\n")))
    by_type: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0, 0])
    for aid, counts in sums.items():
        for i, value in enumerate(counts):
            by_type[aid[-1]][i] += value
    return selected, {key: tuple(counts) for key, counts in {**sums, **by_type}.items()}


def record_csv(path: Path, columns: tuple, rows: list[dict]) -> None:
    write_csv(path, columns, [{key: row.get(key, "") for key in columns} for row in rows])


def summarize_windows(frames: list[dict]) -> list[dict]:
    windows = defaultdict(list)
    for row in frames:
        if row["in_observation"]:
            block = row["frame_start_ns"] // QUARTER_NS
            windows[row["scenario_id"], row["allocation_id"], block].append(row)
    out = []
    for (_, allocation, block), rows in sorted(windows.items()):
        last = rows[-1]
        ages = [row["report_age_ns"] for row in rows if "report_age_ns" in row]
        complete = last["policy"] == COMPLETE
        quota = last["abmin_bytes_per_sfu"]
        binding = [row for row in rows if row.get("cap_exhausted_with_more_known")
                   and row["actual_bytes"] == quota and (row.get("remaining_after_fill_packets") or 0) > 0]
        limited = [row for row in rows if row["offline_backlog_gt_known"] and row["known_demand_bytes"] < quota]
        out.append(dict(
            scenario_id=last["scenario_id"], seed=last["seed"], policy=last["policy"],
            allocation_id=allocation, abmin_t2_bytes=quota,
            start_ns=block * QUARTER_NS, end_ns=(block + 1) * QUARTER_NS, frame_samples=len(rows),
            mean_fifo_start_packets=sum(row["frame_start_fifo_packets"] for row in rows) / len(rows),
            max_fifo_start_packets=max(row["frame_start_fifo_packets"] for row in rows),
            final_fifo_packets=last["frame_end_fifo_packets"],
            max_backlog_streak_frames=max(row["backlog_streak_frames"] for row in rows),
            enqueued_packets=sum(row["enqueued_packets_in_frame"] for row in rows),
            loaded_packets=sum(row["loaded_packets_in_frame"] for row in rows),
            visible_report_samples=len(ages), mean_report_age_ns=sum(ages) / len(ages) if ages else "",
            cap_binding_frames=len(binding) if complete else "",
            visible_below_cap_with_uncovered_backlog_frames=len(limited) if complete else "",
            unknown_with_uncovered_backlog_frames=sum(row["unknown_with_offline_backlog"] for row in rows),
            assured_shortfall_bytes=sum(row["assured_shortfall_bytes"] for row in rows) if complete else "",
            outstanding_blocks=sum(row["rr_outstanding_without_grant"] for row in rows) if not complete else "",
        ))
    return out


def analyze(out: Path, cases: list[T2Experiment]) -> None:
    from pon_qos_special_metrics import LATENCY_COLUMNS, LOSS_COLUMNS, SLA_COLUMNS, analyze_qos_run
    from pon_t2_delay_trace import T2_GRANT_COLUMNS, t2_grant_trace

    refs = {case.seed: case for case in cases if case.policy == COMPLETE and case.abmin_t2_bytes == QUOTAS[0]}
    workloads = {}
    metrics, frame_rows, index = [], [], []
    metric_columns = ("abmin_t2_bytes", "t2_quota_active") + tuple(dict.fromkeys(
        LATENCY_COLUMNS + SLA_COLUMNS + LOSS_COLUMNS + GRANT_FIELDS))
    for case in cases:
        prefix, seed = case.name, case.seed
        config_path, log_path = Path("configs", f"{prefix}.properties"), Path("raw", f"{prefix}.log.gz")
        receipt = json.loads((out / "raw" / f"{prefix}.json").read_text(encoding="utf-8"))
        saved = (out / config_path).read_bytes()
        require(saved == case.properties().encode("utf-8") and sha(saved) == receipt["config_sha256"],
                f"{prefix}: incorrect saved configuration")
        log_bytes = gzip.decompress((out / log_path).read_bytes())
        require(sha(log_bytes) == receipt["raw_log_utf8_sha256"], f"{prefix}: incorrect raw log")
        log = log_bytes.decode("utf-8")
        del log_bytes

        reference = refs[seed].config
        differences = {key for key in reference.keys() | case.config.keys()
                       if reference.get(key) != case.config.get(key)}
        allowed = ({"fiber.uplink.dba.mode"} if case.policy == RR
                   else T2_KEYS if case.abmin_t2_bytes != QUOTAS[0] else set())
        require(differences == allowed, f"{prefix}: unexpected settings change: {differences}")
        result = analyze_qos_run(log, case)
        require(workloads.setdefault(seed, result.workload) == result.workload, f"{prefix}: different input events")
        trace, totals = events_and_grants(log, TRAFFIC_US * 1000)
        frames = t2_grant_trace(trace, case)
        require(frames, f"{prefix}: missing T2 decisions")
        frame_rows.extend(frames)
        def identity(row):
            return row["scope"], row["allocation_id"], row["tcont_type"]

        latency_by_key = {identity(row): row for row in result.latency_rows}
        sla_by_key = {identity(row): row for row in result.sla_rows}
        loss_by_key = {identity(row): row for row in result.loss_rows}
        require(len(latency_by_key) == len(sla_by_key) == len(loss_by_key) == 12
                and latency_by_key.keys() == sla_by_key.keys() == loss_by_key.keys(),
                f"{prefix}: invalid metric identities")
        for key, waiting in latency_by_key.items():
            sla, loss = sla_by_key[key], loss_by_key[key]
            amounts = totals.get(key[1] if key[0] == "alloc" else key[2], (0, 0, 0, 0))
            metrics.append({**waiting, **sla, **loss, **dict(zip(GRANT_FIELDS, amounts)),
                            "abmin_t2_bytes": case.abmin_t2_bytes, "t2_quota_active": case.policy == COMPLETE})
        index.append(dict(scenario_id=prefix, policy=case.policy, seed=seed, abmin_t2_bytes=case.abmin_t2_bytes,
                          traffic_end_us=TRAFFIC_US, decision_end_us=DECISION_US, **receipt,
                          input_sha256=input_hash(result.workload), generated_packets=len(result.workload),
                          config=config_path.as_posix(), raw_log=log_path.as_posix()))
        print(f"Analyzed {prefix}: {len(result.workload)} input packets, {len(frames)} T2 decisions", flush=True)

    require(len({row["jar_sha256"] for row in index}) == 1, "More than one simulator build in the comparison")
    record_csv(out / "metrics.csv", metric_columns, metrics)
    record_csv(out / "t2-grant-trace.csv", T2_GRANT_COLUMNS, frame_rows)
    windows = summarize_windows(frame_rows)
    record_csv(out / "t2-window-summary.csv", tuple(windows[0]), windows)
    record_csv(out / "raw-index.csv", tuple(index[0]), index)
    print(f"Verified {len(cases)} Java runs, {len(workloads)} matched seed streams, "
          f"{len(metrics)} metric rows and {len(frame_rows)} trace rows.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    stage = parser.add_mutually_exclusive_group()
    stage.add_argument("--capture-only", action="store_true")
    stage.add_argument("--reuse-raw", action="store_true")
    args = parser.parse_args()
    cases = experiments()
    if not args.reuse_raw:
        capture(args.jar.resolve(), args.output, cases)
    if not args.capture_only:
        analyze(args.output, cases)


if __name__ == "__main__":
    main()
