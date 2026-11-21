"""Run matched multi-SFU overload and traffic-isolation experiments."""

from __future__ import annotations

import argparse
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, replace
from decimal import Decimal
import gzip
import hashlib
import json
from math import isclose
import os
from pathlib import Path
from shutil import copyfileobj
import subprocess
from tempfile import TemporaryDirectory
from time import perf_counter

from pon_giant_completion_workload import COMPLETE, FRAME_NS, PACKET_BYTES, RR, SEEDS, Experiment, _config, _profiles
from pon_execution_trace import fields
from pon_experiment_io import ROOT, require, write_csv


OUTPUT = ROOT / "results/pon-multi-sfu-overload"
DEFAULT_JAR = ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar"
CONTROL_BYTES = 64
GUARANTEE_BYTES = 1200
GUARANTEE_PERIOD = 2
PROTECTED_RATES = {"VO": 60, "VI": 80, "BE": 100}
JAVA_OPTIONS = ("-Xms64m", "-Xmx512m", "-XX:ActiveProcessorCount=2")
TRAFFIC_US = 100_000
DECISION_US = 110_000


@dataclass(frozen=True)
class OverloadExperiment(Experiment):
    scenario: str = ""
    sfu_count: int = 0
    t4_mbps: int = 0

    def properties(self) -> str:
        return (f"# {self.name}. Main observation [0,{self.traffic_end_us}) us.\n"
                + "".join(f"{name}={value}\n" for name, value in self.config.items()))


def make_case(scenario: str, sfus: int, t4_mbps: int, policy: str, seed: int,
              traffic_us: int = TRAFFIC_US, decision_us: int = DECISION_US) -> OverloadExperiment:
    settings = _config(policy, sfus, decision_us)
    stas = sfus * 2
    settings.update({"sta.count": stas, "traffic.mode": "poisson", "traffic.duration.us": traffic_us})
    rates = {ac: total if scenario == "isolation" else 0 for ac, total in PROTECTED_RATES.items()}
    rates["BK"] = t4_mbps
    for sta in range(1, stas + 1):
        settings[f"sta.{sta}.sfu.id"] = (sta + 1) // 2
        settings[f"traffic.sta.{sta}.seed"] = seed * 1000 + sta
        for ac, total in rates.items():
            prefix = f"traffic.sta.{sta}.class.{ac}"
            settings[prefix + ".rate.mbps"] = format(Decimal(total) / stas, "f")
            settings[prefix + ".packet.bytes"] = PACKET_BYTES
    period, quota = GUARANTEE_PERIOD, GUARANTEE_BYTES
    profiles = []
    for sfu in range(1, sfus + 1):
        base = 100 * sfu
        profiles.extend((
            (base + 1, sfu, 1, "VO", 0, 0, 0, 0, period, quota),
            (base + 2, sfu, 2, "VI", period, quota, 0, 0),
            (base + 3, sfu, 3, "BE", period, quota, period, quota),
            (base + 4, sfu, 4, "BK", 1, 0, 1, 38_400),
        ))
    _profiles(settings, profiles)
    load = f"{scenario}-{sfus}sfu-{t4_mbps}mbps"
    name = f"{load}-{seed}-{policy}"
    return OverloadExperiment(name=name, load=load, policy=policy, seed=seed, traffic_end_us=traffic_us,
                              decision_end_us=decision_us, config=settings, offered_mbps=sum(rates.values()),
                              scenario=scenario, sfu_count=sfus, t4_mbps=t4_mbps)


def experiments() -> list[OverloadExperiment]:
    return [make_case(scenario, sfus, t4_mbps, policy, seed)
            for sfus in (4, 8)
            for scenario, load in (("t4-only-input", (5000, 8000)), ("isolation", (0, 5000, 8000)))
            for t4_mbps in load
            for seed in SEEDS
            for policy in (COMPLETE, RR)]


def capture_meta(case: OverloadExperiment) -> dict:
    return dict(scenario_id=case.name, scenario=case.scenario, policy=case.policy, seed=case.seed,
                sfu_count=case.sfu_count, sta_count=case.sfu_count * 2, t4_mbps=case.t4_mbps,
                configured_offered_mbps=case.offered_mbps, traffic_end_us=case.traffic_end_us,
                decision_end_us=case.decision_end_us, observation_end_ns=case.traffic_end_us * 1000)


def windows_peak_rss(proc: subprocess.Popen) -> int | None:
    """Report the Windows peak working set; other hosts still record wall time and bytes."""
    if os.name != "nt":
        return None
    from ctypes import POINTER, Structure, WinDLL, byref, c_size_t, sizeof
    from ctypes.wintypes import BOOL, DWORD, HANDLE

    class ProcessMemoryCounters(Structure):
        _fields_ = [("size", DWORD), ("page_faults", DWORD)] + [(name, c_size_t) for name in (
            "peak_working_set", "working_set", "peak_paged_pool", "paged_pool",
            "peak_nonpaged_pool", "nonpaged_pool", "pagefile", "peak_pagefile",
        )]

    info = ProcessMemoryCounters()
    info.size = sizeof(info)
    query = WinDLL("psapi").GetProcessMemoryInfo
    query.argtypes = [HANDLE, POINTER(ProcessMemoryCounters), DWORD]
    query.restype = BOOL
    # Popen still owns Windows' process handle even after wait() returns.
    return info.peak_working_set if query(proc._handle, byref(info), info.size) else None


def java_runtime() -> tuple[str, str]:
    # The Windows PATH can resolve to a thin launcher. Measure the JVM itself.
    info = subprocess.run(["java", "-XshowSettings:properties", "-version"],
                          capture_output=True, encoding="utf-8", check=True)
    properties = dict(tuple(part.strip() for part in line.split("=", 1)) for line in info.stderr.splitlines()
                      if line.startswith(("    java.home ", "    java.runtime.version ")) and "=" in line)
    binary = Path(properties["java.home"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
    return str(binary), properties["java.runtime.version"]


def run_java(java: str, jar: Path, config_path: Path, stdout_path: Path, stderr_path: Path) -> tuple[int, float, int | None]:
    started = perf_counter()
    with stdout_path.open("wb", buffering=1024 * 1024) as out, stderr_path.open("wb") as err:
        with subprocess.Popen([java, *JAVA_OPTIONS, "-jar", str(jar), str(config_path)],
                              stdout=subprocess.PIPE, stderr=err) as process:
            copyfileobj(process.stdout, out, length=1024 * 1024)
            code = process.wait()
            peak_rss = windows_peak_rss(process)
    return code, perf_counter() - started, peak_rss


def capture_one(jar: Path, output: Path, case: OverloadExperiment, jar_sha: str, runtime: tuple[str, str]) -> dict:
    relative_config = Path("configs", f"{case.name}.properties")
    relative_raw = Path("raw", f"{case.name}.log.gz")
    config_path = output / relative_config
    config = case.properties().encode("utf-8")
    config_path.write_bytes(config)
    with TemporaryDirectory(dir=output / "raw") as scratch:
        scratch = Path(scratch)
        stdout, stderr = scratch / "simulation.log", scratch / "stderr.log"
        code, wall, rss = run_java(runtime[0], jar, config_path, stdout, stderr)
        if code != 0:
            raise RuntimeError(f"{case.name}: Java failed ({code}): {stderr.read_text(encoding='utf-8').strip()}")
        digest = hashlib.sha256()
        lines = 0
        with stdout.open("rb") as source, (output / relative_raw).open("wb") as target:
            with gzip.GzipFile(filename="", fileobj=target, mode="wb", compresslevel=3, mtime=0) as zipper:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    zipper.write(chunk)
                    digest.update(chunk)
                    lines += chunk.count(b"\n")
        receipt = dict(capture_meta(case), config=relative_config.as_posix(), raw_log=relative_raw.as_posix(),
                       config_sha256=hashlib.sha256(config).hexdigest(), raw_sha256=digest.hexdigest(),
                       jar_sha256=jar_sha, raw_event_lines=lines, stdout_bytes=stdout.stat().st_size,
                       compressed_bytes=(output / relative_raw).stat().st_size, java_wall_seconds=round(wall, 4),
                       java_peak_rss_bytes=rss, java_version=runtime[1], capture_transport="pipe")
    (output / relative_raw.with_suffix("").with_suffix(".json")).write_text(
        json.dumps(receipt, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Captured {case.name}: {lines:,} lines in {wall:.1f} s", flush=True)
    return receipt


def capture(output: Path, jar: Path, cases: list[OverloadExperiment], jobs: int, *, resume: bool = False) -> list[dict]:
    (output / "configs").mkdir(parents=True, exist_ok=True)
    (output / "raw").mkdir(parents=True, exist_ok=True)
    (output / "raw" / ".gitignore").write_text("*\n!.gitignore\n", encoding="utf-8")
    jar_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
    runtime = java_runtime()
    saved = {}
    if resume:
        for case in cases:
            marker = output / "raw" / f"{case.name}.json"
            if marker.is_file() and marker.with_suffix(".log.gz").is_file():
                info = json.loads(marker.read_text(encoding="utf-8"))
                config_sha = hashlib.sha256(case.properties().encode("utf-8")).hexdigest()
                if (info["jar_sha256"], info["java_version"], info["config_sha256"]) == (jar_sha, runtime[1], config_sha):
                    saved[case.name] = info
    missing = [case for case in cases if case.name not in saved]
    if jobs == 1:
        for case in missing:
            saved[case.name] = capture_one(jar, output, case, jar_sha, runtime)
    elif missing:
        with ProcessPoolExecutor(max_workers=jobs) as pool:
            futures = {case.name: pool.submit(capture_one, jar, output, case, jar_sha, runtime) for case in missing}
            saved.update({name: future.result() for name, future in futures.items()})
    return [saved[case.name] for case in cases]


def export(output: Path, name: str, rows: list[dict]) -> None:
    columns = tuple(dict.fromkeys(key for row in rows for key in row))
    write_csv(output / name, columns, [{key: row.get(key, "") for key in columns} for row in rows])


def pilot(output: Path, jar: Path, jobs: int) -> None:
    output = output / "pilot"
    cases = [make_case(scenario, sfus, 8000, policy, SEEDS[0], traffic_us=5000, decision_us=6000)
             for scenario, sfus in (("t4-only-input", 4), ("isolation", 8)) for policy in (COMPLETE, RR)]
    export(output, "scale-check.csv", capture(output, jar, cases, jobs))
    reference = cases[0]
    fixed_key = "fiber.uplink.tcont.alloc.101.ab.fixed.bytes"
    no_fixed = {**reference.config, fixed_key: 0}
    no_t1 = {key: value for key, value in reference.config.items()
             if not key.startswith("fiber.uplink.tcont.alloc.101.")}
    ids_key = "fiber.uplink.tcont.alloc.ids"
    no_t1[ids_key] = ",".join(key for key in no_t1[ids_key].split(",") if key != "101")
    rejected = []
    java, _ = java_runtime()
    with TemporaryDirectory(dir=output / "raw") as scratch:
        scratch = Path(scratch)
        for label, settings, reason in (("t1-zero-fixed", no_fixed, "Type 1 requires positive fixed SI/AB"),
                                        ("t1-removed", no_t1, "T-CONT mappings must cover VO, VI, BE and BK")):
            case = replace(reference, config=settings, name=label, rejected=True)
            config = output / "configs" / f"{label}.properties"
            config.write_text(case.properties(), encoding="utf-8")
            stdout, stderr = scratch / "out", scratch / "err"
            code, _, _ = run_java(java, jar, config, stdout, stderr)
            message = stderr.read_text(encoding="utf-8").strip()
            require(code != 0 and reason in message, f"{label}: expected explicit config rejection, got {message}")
            rejected.append(dict(scenario_id=label, config=f"configs/{config.name}", exit_code=code,
                                 diagnostic=message))
    export(output, "pure-t4-config-check.csv", rejected)


def analyze_one(output: Path, case: OverloadExperiment) -> tuple[dict, list[dict], list[dict]]:
    from pon_multi_sfu_overload_metrics import analyze_stream

    config = output / "configs" / f"{case.name}.properties"
    archive = output / "raw" / f"{case.name}.log.gz"
    receipt = json.loads(archive.with_suffix("").with_suffix(".json").read_text(encoding="utf-8"))
    receipt.setdefault("capture_transport", "file")
    settings = config.read_bytes()
    require(settings == case.properties().encode("utf-8")
            and hashlib.sha256(settings).hexdigest() == receipt["config_sha256"], f"{case.name}: unmatched config")
    digest = hashlib.sha256()
    count = 0
    polls = defaultdict(list)
    cutoff = case.traffic_end_us * 1000

    def verified_lines(source):
        nonlocal count
        for raw in source:
            digest.update(raw)
            count += 1
            line = raw.decode("utf-8")
            if b"event=PON_POLL_TX_START " in raw:
                event = fields(line)
                if int(event["tx_end_ns"]) <= cutoff:
                    polls[int(event["allocation_id"])].append((int(event["time_ns"]), count))
            yield line

    with gzip.open(archive, "rb") as log:
        result = analyze_stream(verified_lines(log), case)
    require(digest.hexdigest() == receipt["raw_sha256"] and count == receipt["raw_event_lines"],
            f"{case.name}: Java log/checksum mismatch")
    count_objects = 4 * case.sfu_count
    expected_interval = count_objects * FRAME_NS
    require(len(polls) == count_objects, f"{case.name}: a configured Alloc-ID was never polled")
    poll_rows = []
    for allocation, observed in sorted(polls.items()):
        gaps = [right[0] - left[0] for left, right in zip(observed, observed[1:])]
        require(gaps and min(gaps) == max(gaps) == expected_interval, f"{case.name}: unexpected polling cadence")
        poll_rows.append(dict(capture_meta(case), allocation_id=allocation, sfu_id=allocation // 100,
                              tcont_type=allocation % 100, global_poll_interval_frames=1,
                              polling_objects=count_objects, per_alloc_poll_period_ns=expected_interval,
                              observed_polls=len(observed), first_poll_ns=observed[0][0], last_poll_ns=observed[-1][0],
                              first_poll_log_line=observed[0][1], last_poll_log_line=observed[-1][1]))
    receipt.update(input_sha256=result.workload_digest, protected_input_sha256=result.protected_input_digest,
                   generated_packets=result.workload_count)
    print(f"Analyzed {case.name}: {result.workload_count:,} packets", flush=True)
    return receipt, [capture_meta(case) | row for row in result.metrics], poll_rows


def jain(values: list[float]) -> float | None:
    squared = sum(value * value for value in values)
    return sum(values) ** 2 / (len(values) * squared) if squared else None


def isolation_deltas(sla_rows: list[dict]) -> list[dict]:
    def identity(row: dict) -> tuple:
        return row["sfu_count"], row["policy"], row["seed"], row["tcont_type"]

    baseline = {identity(row): row for row in sla_rows if row["t4_mbps"] == 0 and row["scope"] == "type"}
    columns = ("throughput_mbps", "fifo_mean_ns", "fifo_p95_ns", "fifo_p99_ns", "fifo_pending_at_cutoff_packets",
               "final_pending_packets")
    output = []
    for row in sla_rows:
        if row["scope"] != "type" or row["t4_mbps"] == 0:
            continue
        ref = baseline[identity(row)]
        info = {key: row[key] for key in ("scenario_id", "sfu_count", "policy", "seed", "t4_mbps", "tcont_type")}
        for name in columns:
            before, after = ref.get(name), row.get(name)
            info[f"baseline_{name}"] = before
            info[f"overload_{name}"] = after
            info[f"delta_{name}"] = after - before if isinstance(before, (int, float)) and isinstance(after, (int, float)) else None
        output.append(info)
    return output


def analyze(output: Path, cases: list[OverloadExperiment], jobs: int) -> None:
    if jobs == 1:
        runs = [analyze_one(output, case) for case in cases]
    else:
        with ProcessPoolExecutor(max_workers=jobs) as pool:
            futures = [pool.submit(analyze_one, output, case) for case in cases]
            runs = [job.result() for job in futures]
    export_results(output, cases, runs)


def export_results(output: Path, cases: list[OverloadExperiment], runs: list[tuple[dict, list[dict], list[dict]]]) -> None:
    references, protected_streams = {}, {}
    grant_rows, link_rows, queue_rows, sla_rows, poll_rows, index = [], [], [], [], [], []
    for case, (receipt, metrics, polls) in zip(cases, runs):
        pair = (case.scenario, case.sfu_count, case.t4_mbps, case.seed)
        if pair in references:
            baseline, prior = references[pair]
            changed = {key for key in baseline.config.keys() | case.config.keys()
                       if baseline.config.get(key) != case.config.get(key)}
            require(changed == {"fiber.uplink.dba.mode"} and receipt["input_sha256"] == prior["input_sha256"]
                    and receipt["generated_packets"] == prior["generated_packets"], f"{case.name}: unequal input")
        else:
            references[pair] = (case, receipt)
        if case.scenario == "isolation":
            key = case.sfu_count, case.seed
            digest = receipt["protected_input_sha256"]
            require(digest == protected_streams.setdefault(key, digest), f"{case.name}: different protected input")
        link, = [row for row in metrics if row["scope"] == "link"]
        sfus = [row for row in metrics if row["scope"] == "sfu"]
        by_alloc = [row for row in metrics if row["scope"] == "alloc"]
        require(len(sfus) == case.sfu_count and len(by_alloc) == 4 * case.sfu_count, f"{case.name}: missing SFU/Alloc")
        require(isclose(sum(row["throughput_mbps"] for row in sfus), link["throughput_mbps"], abs_tol=1e-7),
                f"{case.name}: SFU throughput does not match link data")
        require(isclose(sum(row["configured_offered_mbps"] for row in sfus), case.offered_mbps, abs_tol=1e-7),
                f"{case.name}: SFU offered configuration does not match network input")
        t4 = [row for row in by_alloc if str(row["tcont_type"]) == "4"]
        airtime = link["data_busy_ns"] + link["report_busy_ns"]
        require(airtime <= link["data_reserved_ns"] + link["report_busy_ns"] <= case.traffic_end_us * 1000,
                f"{case.name}: invalid optical utilization")
        link.update(jain_sfu_throughput=jain([row["throughput_mbps"] for row in sfus]),
                    jain_t4_sfu_throughput=jain([row["throughput_mbps"] for row in t4]),
                    utilization_fraction=airtime / (case.traffic_end_us * 1000),
                    reserved_utilization_fraction=(link["data_reserved_ns"] + link["report_busy_ns"])
                    / (case.traffic_end_us * 1000))
        for row in sfus + by_alloc:
            row.update(jain_sfu_throughput=link["jain_sfu_throughput"],
                       jain_t4_sfu_throughput=link["jain_t4_sfu_throughput"])
        link_rows.append(link)
        grant_rows.extend(sfus + by_alloc)
        queue_rows.extend(metrics)
        sla_rows.extend(row for row in metrics if row["scope"] in {"type", "alloc"}
                        and str(row["tcont_type"]) in {"1", "2", "3"} and case.scenario == "isolation")
        poll_rows.extend(polls)
        index.append(receipt)
    require(len(references) * 2 == len(cases) and len({row["jar_sha256"] for row in index}) == 1,
            "Missing policy pairs or simulation builds differ")
    for filename, rows in (
        ("link-throughput-utilization.csv", link_rows), ("sfu-service-and-fairness.csv", grant_rows),
        ("queue-accumulation.csv", queue_rows), ("protected-sla.csv", sla_rows),
        ("isolation-delta.csv", isolation_deltas(sla_rows)), ("report-polling.csv", poll_rows),
        ("raw-index.csv", index),
    ):
        export(output, filename, rows)
    print(f"Verified {len(cases)} runs, {len(references)} policy pairs and {len(protected_streams)} protected streams.",
          flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--jobs", type=int, default=2, help="Maximum concurrent JVM or analysis processes")
    parser.add_argument("--resume-capture", action="store_true", help="Capture missing runs, keeping matching archives")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--pilot", action="store_true", help="Run 5 ms scale and configuration checks")
    mode.add_argument("--capture-only", action="store_true", help="Generate Java event archives")
    mode.add_argument("--reuse-raw", action="store_true", help="Analyze verified local event archives")
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    if args.resume_capture and (args.pilot or args.reuse_raw):
        parser.error("--resume-capture is for formal Java runs")
    if args.pilot:
        pilot(args.output.resolve(), args.jar.resolve(), args.jobs)
        return
    cases = experiments()
    if not args.reuse_raw:
        capture(args.output.resolve(), args.jar.resolve(), cases, args.jobs, resume=args.resume_capture)
    if not args.capture_only:
        analyze(args.output.resolve(), cases, args.jobs)


if __name__ == "__main__":
    main()
