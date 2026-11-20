"""Run matched Java RR/GIANT workloads and export per-Alloc-ID and per-packet results."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory

from pon_dba_comparison_metrics import METRIC_COLUMNS, PACKET_DELAY_COLUMNS, analyze_run
from pon_dba_comparison_workload import Scenario, scenarios
from run_pon_dba_execution_test import fields
from run_pon_frame_test import ROOT, require, simulate, write_csv


MANIFEST_COLUMNS = (
    "scenario_id", "policy", "load", "variant", "seed", "target_offered_mbps", "config",
    "traffic_end_ns", "decision_end_ns", "observation_end_ns", "simulation_end_ns",
    "generated_packets", "workload_sha256", "replay_identical", "type4_grants",
    "type4_first_target_frame", "type4_min_gap_frames",
)


def properties(text: str) -> dict[str, str]:
    return dict(line.split("=", 1) for line in text.splitlines() if line and not line.lstrip().startswith("#"))


def type4_service(log: str) -> tuple[tuple[int, int, int], ...]:
    """Record real positive GIANT Type 4 decisions and check the configured cooldown."""
    points = []
    previous_frame = {}
    for line in log.splitlines():
        if "event=PON_GIANT_DECISION " not in line:
            continue
        row = fields(line)
        budget = int(row["budget_bytes"])
        if row["type"] != "4" or budget == 0:
            continue
        frame, alloc = int(row["target_frame_id"]), int(row["allocation_id"])
        require(int(row["assured_bytes"]) == 0 and budget == int(row["surplus_bytes"])
                and budget <= int(row["ab_sur_bytes"]) and int(row["si_min_before"]) == 1,
                f"Type 4 used an invalid budget in frame {frame} for {alloc}")
        if alloc in previous_frame:
            require(frame - previous_frame[alloc] >= int(row["si_min_frames"]),
                    f"Type 4 Alloc-ID {alloc} received budgets too close together")
        previous_frame[alloc] = frame
        points.append((frame, alloc, budget))
    return tuple(points)


def check_settings(case: Scenario, reference: dict[str, str], current: dict[str, str]) -> None:
    differences = {k for k in current.keys() | reference.keys() if current.get(k) != reference.get(k)}
    if case.policy == "rr-multi":
        expected = {"fiber.uplink.dba.mode"}
    elif case.variant == "baseline":
        expected = set()
    else:
        suffix = {"type4_si2": "si.min.frames", "type4_ab7200": "ab.sur.bytes"}[case.variant]
        expected = {k.removesuffix("type") + suffix for k in reference
                    if k.startswith("fiber.uplink.tcont.alloc.") and k.endswith(".type") and reference[k] == "4"}
    require(differences == expected, f"{case.name}: unmatched config keys {sorted(differences)}")


def service_gap(points: tuple[tuple[int, int, int], ...]) -> int | str:
    last = {}
    gaps = []
    for frame, alloc, _ in points:
        if alloc in last:
            gaps.append(frame - last[alloc])
        last[alloc] = frame
    return min(gaps) if gaps else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-dba-comparison")
    args = parser.parse_args()
    cases = scenarios()
    references = {(s.load, s.seed): properties(s.settings) for s in cases
                  if s.policy == "giant" and s.variant == "baseline"}
    metric_rows, packet_rows, manifest = [], [], []
    hashes, input_by_case, service_by_case = {}, {}, {}
    with TemporaryDirectory(prefix="fttr-dba-comparison-") as temporary:
        for case in cases:
            settings = properties(case.settings)
            check_settings(case, references[case.load, case.seed], settings)
            config = Path(temporary) / f"{case.name}.properties"
            config.write_text(case.settings, encoding="utf-8")
            log = simulate(args.jar.resolve(), config)
            replayed = case.policy == "giant" and case.load == "mid" and case.variant == "baseline"
            if replayed:
                require(log == simulate(args.jar.resolve(), config), f"{case.name}: Java replay diverged")
            try:
                result = analyze_run(log, settings, case.load, case.seed, case.variant,
                                     case.traffic_end_ns, case.decision_end_ns, case.observation_end_ns)
            except (ValueError, KeyError) as exc:
                raise RuntimeError(f"{case.name}: {exc}") from exc
            digest = hashlib.sha256(json.dumps(result.workload, separators=(",", ":")).encode("utf-8")).hexdigest()
            key = case.load, case.seed
            require(input_by_case.setdefault(key, result.workload) == result.workload,
                    f"{case.name}: generated event stream differs")
            hashes[key] = digest
            points = type4_service(log) if case.policy == "giant" else ()
            service_by_case[case.policy, case.load, case.seed, case.variant] = points
            total = next(row for row in result.metrics if row["scope"] == "total")
            metric_rows.extend(result.metrics)
            packet_rows.extend(result.packet_delays)
            manifest.append({
                "scenario_id": case.name, "policy": case.policy, "load": case.load, "seed": case.seed,
                "variant": case.variant, "target_offered_mbps": case.offered_mbps,
                "config": f"configs/{config.name}", "traffic_end_ns": case.traffic_end_ns,
                "decision_end_ns": case.decision_end_ns, "observation_end_ns": case.observation_end_ns,
                "simulation_end_ns": total["simulation_end_ns"], "generated_packets": total["generated_packets"],
                "workload_sha256": digest, "replay_identical": True if replayed else "",
                "type4_grants": len(points) if points else "",
                "type4_first_target_frame": points[0][0] if points else "",
                "type4_min_gap_frames": service_gap(points),
            })
            print(f"{case.name}: generated={total['generated_packets']} delivered={total['delivered_packets']} "
                  f"drain={total['late_delivered_packets']} pending={total['final_pending_packets']}", flush=True)

    require(len(set(hashes.values())) == len(references), "Distinct seed/load pairs did not produce distinct inputs")
    for case in cases:
        if case.variant != "baseline":
            varied = service_by_case[case.policy, case.load, case.seed, case.variant]
            baseline = service_by_case[case.policy, case.load, case.seed, "baseline"]
            require(varied and varied != baseline, f"{case.name}: parameter change did not affect Type 4 service")

    configs = args.output / "configs"
    configs.mkdir(parents=True, exist_ok=True)
    for case in cases:
        (configs / f"{case.name}.properties").write_text(case.settings, encoding="utf-8")
    write_csv(args.output / "metrics.csv", METRIC_COLUMNS, metric_rows)
    write_csv(args.output / "packet-delay.csv", PACKET_DELAY_COLUMNS, packet_rows)
    write_csv(args.output / "run-manifest.csv", MANIFEST_COLUMNS, manifest)
    print(f"Verified {len(cases)} Java runs ({len(references)} matched input streams), "
          f"{sum(row['replay_identical'] is True for row in manifest)} exact Java replays, "
          f"{len(metric_rows)} metric rows and {len(packet_rows)} delivered packets.")


if __name__ == "__main__":
    main()
