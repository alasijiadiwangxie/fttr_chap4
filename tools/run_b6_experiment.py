"""Run B6 fixed-payload and B5-grid A-MPDU comparisons using a 15 s Java window."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory, TemporaryFile

from pon_experiment_io import ROOT, require, write_csv
from run_b5_paper_replication import Case, MODES, PaperTrace, digest, periodic_arrivals, summarize, us


RESULTS = ROOT / "results/b6-ampdu"


class FrameAccounting:
    """Derive exact window use from individual complete exchanges, including any clipped tail."""

    def __init__(self, window_ns: int) -> None:
        self.window_ns = window_ns
        self.last_end_ns = 0
        self.ppdus = self.mpdus = self.aggregates = self.data_ns = self.sifs_ns = self.control_ns = 0
        self.full_ppdu_ns = self.full_response_ns = 0

    def add(self, fields: bytes) -> None:
        start, data, control, end, members = map(int, fields.split())
        require(self.last_end_ns <= start < data < control < end and members > 0, "Overlapping or invalid exchange")
        require(control - data == 16_000 and end - control == (32_000 if members > 1 else 28_000),
                "Unexpected SIFS or control timing")
        self.last_end_ns = end
        if start >= self.window_ns:
            return
        self.ppdus += 1
        self.mpdus += members
        self.aggregates += members > 1
        self.full_ppdu_ns += data - start
        self.full_response_ns += end - data
        window = self.window_ns
        self.data_ns += min(data, window) - start
        self.sifs_ns += max(0, min(control, window) - data)
        self.control_ns += max(0, min(end, window) - control)

    def metrics(self, activity: dict) -> dict:
        require(self.data_ns == int(activity["tx_ns"]) and self.control_ns == int(activity["rx_ns"]),
                "Aggregated frame airtime differs from the B2 ledger")
        return dict(ppdu_count=self.ppdus, mpdu_attempts=self.mpdus, ampdu_ppdu_count=self.aggregates,
                    mean_mpdu_per_ppdu=Decimal(self.mpdus) / self.ppdus,
                    mean_ppdu_us=us(self.full_ppdu_ns) / self.ppdus,
                    mean_response_us=us(self.full_response_ns) / self.ppdus,
                    data_airtime_us=us(self.data_ns), sifs_airtime_us=us(self.sifs_ns),
                    control_airtime_us=us(self.control_ns),
                    nonexchange_idle_us=us(self.window_ns - self.data_ns - self.sifs_ns - self.control_ns))


def observe(command: list[str], config: Path, case: Case, window: int, mode: str) -> tuple[PaperTrace, FrameAccounting]:
    packets = PaperTrace(case.packet_bytes)
    frames = FrameAccounting(window * 1000)
    log_hash = hashlib.sha256()
    source_hash = hashlib.sha256()
    with TemporaryFile() as error_file:
        with subprocess.Popen([*command, str(config)], stdout=subprocess.PIPE, stderr=error_file) as proc:
            try:
                for line in proc.stdout:
                    log_hash.update(line)
                    if line.startswith(b"A "):
                        frames.add(line[2:])
                        continue
                    if line.startswith(b"G "):
                        source_hash.update(line)
                    packets.consume(line)
                code = proc.wait()
            except BaseException:
                proc.kill()
                raise
        if code != 0:
            error_file.seek(0)
            raise RuntimeError(f"{config.name}: {error_file.read().decode('utf-8', 'replace')}")
    packets.input_sha256 = source_hash.hexdigest()
    packets.trace_sha256 = log_hash.hexdigest()
    packets.verify(case, mode, window)
    require(frames.last_end_ns <= int(Decimal(packets.summaries["RUN"]["simulation_end_us"]) * 1000),
            "Exchange extends beyond simulator drain")
    return packets, frames


def configs_for(base_plan: dict, experiment: dict, variant: str, case: Case, mode: str) -> bytes:
    settings = experiment["variants"][variant]
    plan = dict(base_plan)
    plan["observation_us"] = experiment["observation_us"]
    plan["properties"] = dict(base_plan["properties"])
    plan["properties"].update({"wifi.mac.edca.BE.aifsn": settings["aifsn"],
                               "wifi.mac.ampdu.enabled": "true" if settings["depth"] > 1 else "false",
                               "wifi.mac.ampdu.max-mpdus": settings["depth"],
                               "wifi.mac.ampdu.max-bytes": experiment["max_apep_bytes"]})
    return case.configuration(plan, mode, periodic_arrivals(case.interval_us, experiment["observation_us"]))


def run_pair(key: tuple, command: list[str], directory: Path, base: dict, experiment: dict) -> list[dict]:
    variant, payload, interval = key
    case = Case("PERIODIC", payload, interval, experiment["seed"])
    window = experiment["observation_us"]
    settings = experiment["variants"][variant]
    rows = []
    for mode in MODES:
        bytes_ = configs_for(base, experiment, variant, case, mode)
        name = f"{variant}-{case.name()}-{mode.lower()}.properties"
        config = directory / name
        config.write_bytes(bytes_)
        trace, frames = observe(command, config, case, window, mode)
        metrics = summarize(case, mode, window, trace, bytes_)
        frame_metrics = frames.metrics(trace.summaries["WIFI_AP_ACTIVITY"])
        require(int(metrics["generated_packets"]) == len(trace.delivered) and not trace.dropped,
                "Unexpected final loss in the ideal single-sender performance scenario")
        rows.append(dict(variant=variant, aifs_us=16 + 9 * settings["aifsn"], max_mpdus=settings["depth"],
                         observation_us=window, configuration=name,
                         delivery_ratio=Decimal(metrics["delivered_packets"]) / metrics["generated_packets"],
                         **metrics, **frame_metrics))
        config.unlink()
    require(rows[0]["input_sha256"] == rows[1]["input_sha256"], f"Inputs differ for {key}")
    return rows


def matrices(experiment: dict) -> dict:
    fixed = []
    for variant, setting in experiment["variants"].items():
        intervals = experiment["fixed_intervals_us"] + experiment["extra_intervals_us"].get(str(setting["depth"]), [])
        fixed.extend((variant, experiment["fixed_packet_bytes"], interval) for interval in intervals)
    grid = [(variant, size, interval) for variant in experiment["b5_grid_variants"]
            for size in experiment["b5_grid_packet_bytes"] for interval in experiment["b5_grid_intervals_us"]]
    return {"FIXED_1500": fixed, "B5_GRID": grid}


def emit_configs(base: dict, experiment: dict, keys: list[tuple], output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    for variant, size, interval in keys:
        case = Case("PERIODIC", size, interval, experiment["seed"])
        for mode in MODES:
            name = f"{variant}-{case.name()}-{mode.lower()}.properties"
            (output / name).write_bytes(configs_for(base, experiment, variant, case, mode))


def run(jar: Path, base: dict, experiment: dict, families: dict, output: Path, jobs: int) -> None:
    keys = list(dict.fromkeys(key for family in families.values() for key in family))
    combined = {}
    with TemporaryDirectory(prefix="fttr-b6-") as folder, ThreadPoolExecutor(max_workers=jobs) as pool:
        directory = Path(folder)
        subprocess.run(["javac", "--release", "11", "-cp", str(jar), "-d", str(directory),
                        str(ROOT / "tools/java/B6Trace.java")], check=True)
        command = ["java", "-Xmx640m", "-XX:ActiveProcessorCount=2", "-cp",
                   os.pathsep.join((str(jar), str(directory))), "B6Trace"]
        futures = {pool.submit(run_pair, key, command, directory, base, experiment): key for key in keys}
        for count, future in enumerate(as_completed(futures), 1):
            key = futures[future]
            combined[key] = future.result()
            if count % 12 == 0 or count == len(keys):
                print(f"{count}/{len(keys)} paired B6 runs verified", flush=True)
    rows = [dict(family=family, **row) for family, keys_ in families.items() for key in keys_ for row in combined[key]]
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "per-run.csv", tuple(rows[0]), rows)
    metadata = dict(actual_java_runs=len(keys) * 2, table_rows=len(rows), jar_sha256=digest(jar.read_bytes()),
                    experiment=experiment, base_plan="configs/b5-paper-aligned.json")
    (output / "experiment-manifest.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=RESULTS)
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--emit-configs", type=Path)
    parser.add_argument("--variant", help="Optional variant filter for emitting configurations")
    parser.add_argument("--interval-us", type=int, help="Optional interval filter for emitting configurations")
    args = parser.parse_args()
    base = json.loads((ROOT / "configs/b5-paper-aligned.json").read_text(encoding="utf-8"))
    experiment = json.loads((ROOT / "configs/b6-ampdu-experiments.json").read_text(encoding="utf-8"))
    if args.smoke:
        experiment["observation_us"] = 100_000
        families = {"FIXED_1500": [("A1", 1500, 450), ("D8", 1500, 90)]}
        if args.output == RESULTS:
            args.output = ROOT / "target/b6-ampdu-smoke"
    else:
        families = matrices(experiment)
    if args.emit_configs:
        selected = list(dict.fromkeys(key for family in families.values() for key in family
                                     if (args.variant is None or key[0] == args.variant)
                                     and (args.interval_us is None or key[2] == args.interval_us)))
        require(bool(selected), "No matching B6 configurations")
        emit_configs(base, experiment, selected, args.emit_configs)
    else:
        run(args.jar.resolve(), base, experiment, families, args.output, args.jobs)
