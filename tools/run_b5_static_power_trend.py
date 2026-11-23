"""Build a fixed-payload power-throughput curve from matched Java runs."""

from __future__ import annotations

import argparse
import csv
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import json
from pathlib import Path
from statistics import mean, stdev
from tempfile import TemporaryDirectory

from pon_experiment_io import ROOT, require, simulate, write_csv
from reweight_b5_paper_power import paper_power_w
from run_b5_paper_replication import Case, compile_driver, digest, periodic_arrivals, run_pair


OUTPUT = ROOT / "results/b5-static-power-trend"
PAYLOAD_BYTES = 1500
INTERVALS_US = (12000, 2400, 1200, 800, 600, 550, 500, 450, 400, 375, 350, 320,
                300, 280, 270, 260, 240, 180, 90)
NUMERIC_FIELDS = ("offered_load_mbps", "throughput_mbps", "avg_power_b3_w", "avg_power_paper_w", "backlog_packets")
RUN_COLUMNS = ("mode", "interval_us", "seed", "observation_us", "source", *NUMERIC_FIELDS)


def idle_run(jar: Path, config_path: Path, plan: dict, mode: str) -> dict:
    window = plan["observation_us"]
    properties = dict(plan["properties"], **{
        "traffic.mode": "poisson",
        "traffic.duration.us": window,
        "traffic.sfu.1.class.BE.rate.mbps": 0,
        "wifi.ap.activity.window.us": window,
        "sfu.1.wifi.ap.mode": mode,
    })
    config_path.write_text("".join(f"{key}={value}\n" for key, value in sorted(properties.items())), encoding="ascii")
    summaries = {}
    for line in simulate(jar, config_path).splitlines():
        if line.startswith(("summary=WIFI_AP_ACTIVITY ", "summary=WIFI_AP_ENERGY ", "delivered_packets=")):
            row = dict(item.split("=", 1) for item in line.split())
            summaries[row.get("summary", "RUN")] = row
    activity = summaries["WIFI_AP_ACTIVITY"]
    energy = summaries["WIFI_AP_ENERGY"]
    require(int(activity["idle_ns"]) == window * 1000 and activity["tx_ns"] == activity["rx_ns"] == "0",
            f"Unexpected {mode} activity for zero load")
    require(summaries["RUN"]["delivered_packets"] == "0" and activity["ap_mode"] == energy["ap_mode"] == mode,
            f"Unexpected {mode} idle trace")
    return dict(mode=mode, interval_us=0, seed="", observation_us=window, source="JAVA_IDLE",
                offered_load_mbps=0, throughput_mbps=0, avg_power_b3_w=energy["avg_power_w"],
                avg_power_paper_w=paper_power_w(mode)["idle"], backlog_packets=0)


def archived_runs(plan: dict) -> dict:
    source = ROOT / "results/b5-paper-replication/paper-aligned-metrics.csv"
    with source.open(encoding="utf-8", newline="") as stream:
        rows = {(int(row["interval_us"]), int(row["seed"]), row["mode"]): row for row in csv.DictReader(stream)
                if row["arrival_model"] == "PERIODIC" and int(row["packet_bytes"]) == PAYLOAD_BYTES}
    for (interval, seed, mode), row in rows.items():
        case = Case("PERIODIC", PAYLOAD_BYTES, interval, seed)
        configuration = case.configuration(plan, mode, periodic_arrivals(interval, plan["observation_us"]))
        require(digest(configuration) == row["configuration_sha256"], f"Archived configuration differs: {case.name()}")
    return rows


def mean_rows(runs: list[dict]) -> list[dict]:
    groups = {}
    for row in runs:
        groups.setdefault((row["mode"], int(row["interval_us"])), []).append(row)
    output = []
    for (mode, interval), rows in groups.items():
        summary = dict(mode=mode, interval_us=interval, seed_count=sum(row["seed"] != "" for row in rows))
        for field in NUMERIC_FIELDS:
            values = [Decimal(row[field]) for row in rows]
            summary[field] = mean(values)
            summary[f"{field}_sd"] = stdev(values) if len(values) > 1 else Decimal(0)
        output.append(summary)
    return output


def run(jar: Path, output: Path, workers: int) -> None:
    plan = json.loads((ROOT / "configs/b5-paper-aligned.json").read_text(encoding="utf-8"))
    existing = archived_runs(plan)
    rows = []
    with TemporaryDirectory(prefix="fttr-b5-static-trend-") as folder, ThreadPoolExecutor(max_workers=workers) as pool:
        temp = Path(folder)
        command = compile_driver(jar, temp)
        for mode in ("LCM", "HCM"):
            rows.append(idle_run(jar, temp / f"idle-{mode}.properties", plan, mode))
        pending = {}
        for interval in INTERVALS_US:
            for seed in plan["seeds"]:
                cached = [existing.get((interval, seed, mode)) for mode in ("LCM", "HCM")]
                if all(cached):
                    require(cached[0]["input_sha256"] == cached[1]["input_sha256"], "Archived inputs differ")
                    pending[interval, seed] = cached
                else:
                    case = Case("PERIODIC", PAYLOAD_BYTES, interval, seed)
                    pending[interval, seed] = pool.submit(run_pair, command, temp, plan, case)

        for (interval, seed), item in pending.items():
            archived = isinstance(item, list)
            results = item if archived else item.result()
            rows.extend(dict(mode=row["mode"], interval_us=interval, seed=seed,
                             observation_us=plan["observation_us"],
                             source="REUSED_PAPER_REPLICATION" if archived else "JAVA_NEW",
                             **{key: row[key] for key in NUMERIC_FIELDS}) for row in results)
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "power-throughput-runs.csv", RUN_COLUMNS, rows)
    means = mean_rows(rows)
    write_csv(output / "power-throughput.csv", tuple(means[0]), means)
    print(f"Wrote {len(rows)} Java observations ({len(means)} mean points) to {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--jobs", type=int, default=6)
    args = parser.parse_args()
    if not args.jar.is_file() or args.jobs < 1:
        parser.error("A simulator JAR and positive worker count are required.")
    run(args.jar.resolve(), args.output, args.jobs)


if __name__ == "__main__":
    main()
