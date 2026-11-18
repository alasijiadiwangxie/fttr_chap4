"""运行 EDCA 专项实验，复用综合验收的轨迹校验与统计。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import NamedTuple

from validate_edca import DEFAULT_SEEDS, PAYLOAD_BYTES, ROOT, aggregate, require, run, scenario, write_csv


DURATION_US = 1_000_000
MEASUREMENTS = (
    "avg_access_delay_us", "avg_e2e_delay_us", "offered_window_throughput_mbps",
    "physical_collision_attempts", "retransmissions",
)
GROUP = ("experiment", "offered_mbps", "bandwidth_mhz", "profile")
# 1200 B 数据、默认 34 B 封装、MCS 11、2 NSS、0.8 μs GI、PE=0。
# 前导码 50.4 μs。20 MHz 用 3 个数据符号，80/160 MHz 各用 1 个。
DATA_DURATIONS_NS = {20: 91_200, 80: 64_000, 160: 64_000}


class Point(NamedTuple):
    offered_mbps: int
    bandwidth_mhz: int
    profile: str


EXPERIMENTS = {
    "load": [Point(load, 160, "single") for load in (10, 20, 40, 60, 80)],
    "bandwidth": [Point(40, bandwidth, profile) for bandwidth in (20, 80, 160)
                  for profile in ("single", "txop")],
}


def execute(jar: Path, point: Point, seed: int):
    total_pps = point.offered_mbps * 1_000_000 / (PAYLOAD_BYTES * 8)
    config = scenario(total_pps, seed, point.profile, DURATION_US, bandwidth_mhz=point.bandwidth_mhz)
    return run(jar, config, 1, DURATION_US, data_duration_ns=DATA_DURATIONS_NS[point.bandwidth_mhz])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/edca-focus")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR first with mvn -o clean package")
    jar = args.jar.resolve()
    points = list(dict.fromkeys(point for points in EXPERIMENTS.values() for point in points))
    traffic = {}
    results = {}
    checks = []
    for point in points:
        for seed in DEFAULT_SEEDS:
            trace, verification = execute(jar, point, seed)
            fingerprint = verification["generated_sequence_sha256"]
            same_input = traffic.setdefault((point.offered_mbps, seed), fingerprint)
            require(same_input == fingerprint, "Bandwidth or TXOP changed the offered traffic sequence")

            metadata = dict(point._asdict(), seed=seed)
            by_ac = [dict(ac=row["ac"], **{m: row[m] for m in MEASUREMENTS})
                     for row in trace.metric_rows({}) if row["direction"] == "ALL"]
            totals = {
                "loss_rate": verification["dropped_packets"] / verification["generated_packets"],
                "channel_utilization": verification["busy_union_us"] / verification["simulation_end_us"],
                "simulation_end_us": verification["simulation_end_us"],
            }
            results[point, seed] = (metadata, by_ac, totals)
            checks.append(dict(metadata, **verification))
        print(f"{point.offered_mbps} Mb/s, {point.bandwidth_mhz} MHz, {point.profile}: 5 seeds verified", flush=True)

    # 中负载的共用基准只仿真一次，输出中同时用于两组图。
    per_run = []
    totals = []
    for experiment, points in EXPERIMENTS.items():
        for point in points:
            for seed in DEFAULT_SEEDS:
                metadata, by_ac, summary = results[point, seed]
                per_run.extend(dict(experiment=experiment, **metadata, **row) for row in by_ac)
                totals.append(dict(experiment=experiment, **metadata, **summary))

    replay_point = Point(40, 160, "single")
    _, replay = execute(jar, replay_point, DEFAULT_SEEDS[0])
    original_hash = next(check["trace_sha256"] for check in checks
                         if check["seed"] == DEFAULT_SEEDS[0] and all(check[k] == v for k, v in replay_point._asdict().items()))
    require(replay["trace_sha256"] == original_hash, "Fixed-seed full trace replay differs")

    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "runs.csv", per_run)
    write_csv(args.output / "aggregate.csv", aggregate(per_run, GROUP + ("ac",)))
    write_csv(args.output / "totals.csv", aggregate(totals, GROUP))
    metadata = dict(
        all_runs_verified=True, paired_traffic_sequences_identical=True, fixed_seed_replay_identical=True,
        replay_trace_sha256=original_hash, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        loader_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        validator_sha256=hashlib.sha256(Path(__file__).with_name("validate_edca.py").read_bytes()).hexdigest(),
        java_version=subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip(),
        python_version=sys.version, duration_us=DURATION_US, payload_bytes=PAYLOAD_BYTES, seeds=DEFAULT_SEEDS,
        data_durations_ns=DATA_DURATIONS_NS, runs=checks,
    )
    (args.output / "verification.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
                                                   encoding="utf-8")
    print(f"{len(checks)} unique runs and one full trace replay verified.")


if __name__ == "__main__":
    main()
