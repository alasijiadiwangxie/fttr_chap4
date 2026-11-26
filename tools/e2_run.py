"""Materialize and replay the frozen E2 paired experiments with the original Java simulator."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from hashlib import sha256
from pathlib import Path
from time import monotonic

from e2_design import ECQ, FIXED, GROUPS, PILOT_SEEDS, SEEDS, arrivals, properties, study
from pon_experiment_io import ROOT, require
from verify_c1_ap_observation import write_properties
from verify_d1_mode_control import JAR

WORK = ROOT / "target/e2-thesis"
OUT = ROOT / "results/e2-thesis"
PROJECTION = ROOT / "tools/java/E2Trace.java"


def trials(pilot=False, groups=GROUPS):
    for case in study():
        if case.group not in groups:
            continue
        if pilot:
            if case.name not in ("load-031", "rf-secondary-strong-35", "dynamic-step", "joint-4sfu"):
                continue
            for length in (1, 2):
                extended = replace(case, window_us=case.window_us * length, variants=(FIXED, ECQ))
                for seed in PILOT_SEEDS:
                    yield extended, seed
        else:
            for seed in SEEDS:
                yield case, seed


def trial_path(case, seed, variant, root=WORK):
    return root / case.group / case.name / str(case.window_us) / str(seed) / variant


def compile_runner():
    classes = WORK / "projection"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-cp", str(JAR), "-d", str(classes),
                    str(PROJECTION)], check=True)
    return ["java", "-Xmx675m", "-XX:ActiveProcessorCount=2", "-cp", os.pathsep.join((str(JAR), str(classes))), "E2Trace"]


def raw_signature(pilot=False):
    digest = sha256()
    for path in (JAR, PROJECTION, Path(__file__).with_name("e2_design.py")):
        digest.update(path.read_bytes())
    digest.update(str(pilot).encode())
    return digest.hexdigest()


def run_pair(case, seed, runner, signature):
    packets = arrivals(case, seed)
    pending = []
    manifests = []
    for policy in case.variants:
        stem = trial_path(case, seed, policy)
        stem.parent.mkdir(parents=True, exist_ok=True)
        cfg, log, stamp = (stem.with_suffix(suffix) for suffix in (".properties", ".log", ".sha256"))
        expected = {"experiment_sha256": signature, "config_sha256": ""}
        write_properties(cfg, properties(case, seed, policy, packets))
        expected["config_sha256"] = sha256(cfg.read_bytes()).hexdigest()
        if stamp.exists() and json.loads(stamp.read_text(encoding="utf-8")) == expected and log.is_file():
            continue
        pending.extend((str(cfg), str(log)))
        manifests.append((stamp, expected))
    if not pending:
        return 0
    run = subprocess.run([*runner, *pending], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300)
    require(run.returncode == 0, f"{case.name}/{seed}/{case.window_us}: {run.stderr}")
    for stamp, entry in manifests:
        stamp.write_text(json.dumps(entry) + "\n", encoding="utf-8")
    return len(manifests)


def launch(pilot, workers, groups, scenarios=None, seeds=None):
    runner, stamp = compile_runner(), raw_signature(pilot)
    batches = [(case, seed) for case, seed in trials(pilot, groups)
               if (not scenarios or case.name in scenarios) and (not seeds or seed in seeds)]
    require(batches, "No E2 replay matches those filters")
    label = "duration pilot" if pilot else "formal"
    started = monotonic()
    print(f"{label}: {sum(len(case.variants) for case, _ in batches)} Java runs across {len(batches)} paired batches", flush=True)
    failures, executed = [], 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        # Report failed batches while continuing independent scenarios. Only successful batches are cached.
        submitted = [(case, seed, pool.submit(run_pair, case, seed, runner, stamp)) for case, seed in batches]
        for i, (case, seed, future) in enumerate(submitted, 1):
            try:
                executed += future.result()
            except Exception as error:
                failures.append((case.name, seed, str(error)))
                print(error, flush=True)
            if i % 10 == 0 or i == len(submitted):
                print(f"{label}: {i}/{len(batches)} paired batches checked in {monotonic() - started:.1f} s", flush=True)
    require(not failures, f"{len(failures)} trial batches failed: {failures[:3]}")
    print(f"{label}: {executed} new Java runs (others matched the cache)", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pilot", action="store_true", help="run the short/long duration checks with independent seeds")
    parser.add_argument("--workers", type=int, default=5)
    parser.add_argument("--groups", choices=GROUPS, nargs="+", default=GROUPS)
    parser.add_argument("--scenario", choices=tuple(case.name for case in study()), action="append")
    parser.add_argument("--seed", type=int, action="append")
    args = parser.parse_args()
    launch(args.pilot, args.workers, args.groups, args.scenario, args.seed)
