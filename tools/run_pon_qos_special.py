"""Capture paired Java runs and export the DBA latency, SLA and loss study."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

from pon_qos_special_workload import experiments
from pon_experiment_io import ROOT, require, simulate, write_csv


DEFAULT_OUTPUT = ROOT / "results/pon-dba-qos-comparison"
INDEX_COLUMNS = (
    "scenario_id", "series", "x_mbps", "offered_mbps", "policy", "seed",
    "config", "config_sha256", "raw_log", "raw_log_utf8_sha256", "raw_event_lines",
    "jar_sha256", "traffic_end_us", "decision_end_us", "generated_packets",
    "input_sha256", "matched_input", "isolation_invariant_input_sha256",
)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def input_hash(workload) -> str:
    return sha(json.dumps(workload, separators=(",", ":")).encode("utf-8"))


def capture(jar: Path, output: Path, cases: list) -> None:
    configs = output / "configs"
    raw = output / "raw"
    configs.mkdir(parents=True, exist_ok=True)
    raw.mkdir(parents=True, exist_ok=True)
    (raw / ".gitignore").write_text("*.log.gz\n*.json\n", encoding="utf-8")
    jar_hash = sha(jar.read_bytes())
    for case in cases:
        config = configs / f"{case.name}.properties"
        data = case.properties().encode("utf-8")
        config.write_bytes(data)
        log = simulate(jar, config).encode("utf-8")
        with (raw / f"{case.name}.log.gz").open("wb") as target:
            with gzip.GzipFile(filename="", fileobj=target, mode="wb", compresslevel=5, mtime=0) as compressed:
                compressed.write(log)
        line_count = log.count(b"\n")
        (raw / f"{case.name}.json").write_text(json.dumps({
            "config_sha256": sha(data), "raw_log_utf8_sha256": sha(log),
            "jar_sha256": jar_hash, "raw_event_lines": line_count,
        }) + "\n", encoding="utf-8")
        print(f"Captured {case.name} ({line_count} Java log lines)", flush=True)


def analyze(output: Path, cases: list) -> None:
    from pon_qos_special_metrics import LATENCY_COLUMNS, LOSS_COLUMNS, SLA_COLUMNS, analyze_qos_run

    latency_rows, sla_rows, loss_rows, index_rows = [], [], [], []
    pairs, isolation_inputs = {}, {}
    for case in cases:
        config = Path("configs") / f"{case.name}.properties"
        raw = Path("raw") / f"{case.name}.log.gz"
        receipt = json.loads((output / raw.with_name(f"{case.name}.json")).read_text(encoding="utf-8"))
        config_data = (output / config).read_bytes()
        require(config_data == case.properties().encode("utf-8") and sha(config_data) == receipt["config_sha256"],
                f"{case.name}: saved configuration differs from the captured configuration")
        log = gzip.decompress((output / raw).read_bytes())
        require(sha(log) == receipt["raw_log_utf8_sha256"], f"{case.name}: captured output checksum differs")
        try:
            result = analyze_qos_run(log.decode("utf-8"), case)
        except (ValueError, KeyError) as exc:
            raise ValueError(f"{case.name}: {exc}") from exc

        key = (case.load, case.seed)
        if key in pairs:
            prior, source = pairs[key]
            modified = {k for k in prior.config.keys() | case.config.keys()
                        if prior.config.get(k) != case.config.get(k)}
            require(modified == {"fiber.uplink.dba.mode"} and result.workload == source,
                    f"{case.name}: policy pair differs in config or input packets")
        else:
            pairs[key] = (case, result.workload)

        invariant = ""
        if case.family == "isolation":
            # Disable identity comparison here: increasing the number of BK arrivals reassigns
            # overall IDs, but the non-BK streams must retain packet time, source, AC and length.
            high = tuple(sorted((time, sta, ac, size) for time, _, sta, ac, size in result.workload if ac != "BK"))
            require(isolation_inputs.setdefault(case.seed, high) == high,
                    f"{case.name}: protected traffic changed while varying T4")
            invariant = input_hash(high)

        meta = dict(series=case.family, x_mbps=case.axis_mbps)
        latency_rows.extend({**row, **meta} for row in result.latency_rows)
        sla_rows.extend({**row, **meta} for row in result.sla_rows)
        loss_rows.extend({**row, **meta} for row in result.loss_rows)
        index_rows.append(dict(meta, scenario_id=case.name, offered_mbps=case.offered_mbps, policy=case.policy,
                               seed=case.seed, config=config.as_posix(), raw_log=raw.as_posix(), **receipt,
                               traffic_end_us=case.traffic_end_us, decision_end_us=case.decision_end_us,
                               generated_packets=len(result.workload), input_sha256=input_hash(result.workload),
                               matched_input=True, isolation_invariant_input_sha256=invariant))
        print(f"Analyzed {case.name}: {len(result.workload)} input packets", flush=True)

    require(len(pairs) * 2 == len(cases), "Missing or duplicate policy-pair member")
    require(len({row["jar_sha256"] for row in index_rows}) == 1, "Runs used different Java builds")
    for filename, schema, rows in (
        ("latency.csv", LATENCY_COLUMNS, latency_rows),
        ("sla.csv", SLA_COLUMNS, sla_rows),
        ("loss.csv", LOSS_COLUMNS, loss_rows),
        ("raw-index.csv", INDEX_COLUMNS, index_rows),
    ):
        columns = schema if filename == "raw-index.csv" else ("series", "x_mbps") + schema
        write_csv(output / filename, columns, [{key: row.get(key, "") for key in columns} for row in rows])
    print(f"Verified {len(cases)} Java runs and {len(pairs)} identical pairs. "
          f"CSV rows: delay={len(latency_rows)}, SLA={len(sla_rows)}, loss={len(loss_rows)}.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    stage = parser.add_mutually_exclusive_group()
    stage.add_argument("--capture-only", action="store_true", help="Run Java and archive events, defer analysis")
    stage.add_argument("--reuse-raw", action="store_true", help="Analyze existing verified event archives")
    args = parser.parse_args()
    cases = experiments()
    require(len({case.name for case in cases}) == len(cases), "Duplicate scenario ID")
    if not args.reuse_raw:
        capture(args.jar.resolve(), args.output, cases)
    if not args.capture_only:
        analyze(args.output, cases)


if __name__ == "__main__":
    main()
