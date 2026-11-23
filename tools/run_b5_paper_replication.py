"""Run the independent Fig. 2 comparison with the frozen Java PHY and MAC."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory, TemporaryFile

from pon_experiment_io import ROOT, require, write_csv
from reweight_b5_paper_power import paper_power_w
from run_b5_ap_energy_load import MODES, PROFILE, STATES, digest, fields, percentile_us, us


OUTPUT = ROOT / "results/b5-paper-replication"


def ns(token: bytes) -> int:
    whole, _, fraction = token.partition(b".")
    require(len(fraction) <= 3, f"Invalid nanosecond time: {token!r}")
    return int(whole) * 1000 + int(fraction.ljust(3, b"0"))


@dataclass(frozen=True)
class Case:
    arrival_model: str
    packet_bytes: int
    interval_us: int
    seed: int

    def name(self) -> str:
        return f"{self.arrival_model.lower()}-{self.packet_bytes}b-{self.interval_us}us-{self.seed}"

    def settings(self, plan: dict, mode: str) -> dict:
        props = dict(plan["properties"])
        window = plan["observation_us"]
        props.update({"wifi.ap.activity.window.us": window, "wifi.mac.edca.seed": self.seed,
                      "sfu.1.wifi.ap.mode": mode})
        if self.arrival_model == "PERIODIC":
            props.update({"traffic.mode": "manual", "packet.bytes": self.packet_bytes})
        else:
            props.update({"traffic.mode": "poisson", "traffic.duration.us": window,
                          "traffic.sfu.1.seed": self.seed * 100,
                          "traffic.sfu.1.class.BE.packet.bytes": self.packet_bytes,
                          "traffic.sfu.1.class.BE.rate.mbps": format(self.nominal_load, "f")})
        return props

    @property
    def nominal_load(self) -> Decimal:
        return Decimal(self.packet_bytes * 8) / self.interval_us

    def configuration(self, plan: dict, mode: str, arrivals: bytes) -> bytes:
        props = self.settings(plan, mode)
        base = "".join(f"{key}={value}\n" for key, value in sorted(props.items())).encode("ascii")
        return base + arrivals


def periodic_arrivals(interval_us: int, observation_us: int) -> bytes:
    return ("packet.arrival.us=" + ",".join(map(str, range(0, observation_us, interval_us))) + "\n").encode("ascii")


@dataclass
class PaperTrace:
    expected_size: int
    generated: dict[int, int] = field(default_factory=dict)
    delivered: dict[int, int] = field(default_factory=dict)
    dropped: dict[int, int] = field(default_factory=dict)
    summaries: dict[str, dict[str, str]] = field(default_factory=dict)
    first_tx: tuple[int, int] | None = None
    first_data_end: tuple[int, int] | None = None
    input_sha256: str = ""
    trace_sha256: str = ""

    def consume(self, line: bytes) -> None:
        kind, _, tail = line.partition(b" ")
        if kind in (b"G", b"D", b"L", b"X", b"Q"):
            time, identity, *metadata = tail.split()
            packet, time_ns = int(identity), ns(time)
            if kind == b"G":
                require(metadata == [str(self.expected_size).encode(), b"BE", b"1"],
                        f"Unexpected generated packet field(s): {line!r}")
                self.store(self.generated, packet, time_ns)
            elif kind == b"D":
                self.store(self.delivered, packet, time_ns)
            elif kind == b"L":
                self.store(self.dropped, packet, time_ns)
            elif kind == b"X":
                self.first_tx = (packet, time_ns)
            elif kind == b"Q":
                self.first_data_end = (packet, time_ns)
        else:
            row = fields(line)
            self.summaries[row.get("summary", "RUN")] = row

    @staticmethod
    def store(target: dict[int, int], packet: int, time_ns: int) -> None:
        require(packet not in target, f"Repeated event for packet {packet}")
        target[packet] = time_ns

    def verify(self, case: Case, mode: str, window_us: int) -> None:
        observation_ns = window_us * 1000
        energy = self.summaries["WIFI_AP_ENERGY"]
        require(energy["ap_mode"] == mode and energy["model"] == PROFILE, "Unexpected B3 AP powers")
        require(self.delivered.keys().isdisjoint(self.dropped) and
                self.delivered.keys() | self.dropped.keys() == self.generated.keys(), "Unbalanced packets after drain")
        require(all(0 <= time < observation_ns for time in self.generated.values()), "Offered packets outside window")
        if case.arrival_model == "PERIODIC":
            expected = range(0, observation_ns, case.interval_us * 1000)
            require(len(self.generated) == len(expected) and all(actual == wanted
                    for actual, wanted in zip(self.generated.values(), expected)), "Periodic schedule mismatch")
        for kind, records in (("delivery", self.delivered), ("loss", self.dropped)):
            require(all(time >= self.generated[packet] for packet, time in records.items()), f"Invalid {kind} timestamp")
        require(int(self.summaries["RUN"]["delivered_packets"]) == len(self.delivered), "Delivery summary mismatch")
        for key in ("WIFI_AP_ACTIVITY", "WIFI_AP_ENERGY"):
            row = self.summaries[key]
            require(row["sfu_id"] == "1" and row["ap_mode"] == mode and int(row["observation_start_ns"]) == 0
                    and int(row["observation_end_ns"]) == observation_ns, "Unexpected observation window or AP")
        activity = self.summaries["WIFI_AP_ACTIVITY"]
        require(sum(int(activity[f"{state}_ns"]) for state in STATES) == observation_ns, "Unbalanced AP state times")
        b3_terms = [Decimal(energy[f"{state}_power_w"]) * us(int(activity[f"{state}_ns"])) for state in STATES]
        require(b3_terms == [Decimal(energy[f"{state}_energy_uj"]) for state in STATES]
                and sum(b3_terms) == Decimal(energy["total_energy_uj"]), "B3 energy does not match its input times")
        require(self.first_tx and self.first_data_end and self.first_tx[0] == self.first_data_end[0],
                "The first DATA frame needs a matching start/end boundary")


def collect(command: list[str], path: Path, window_us: int, case: Case, mode: str) -> PaperTrace:
    result = PaperTrace(case.packet_bytes)
    input_sha = hashlib.sha256()
    trace_sha = hashlib.sha256()
    with TemporaryFile() as error_file:
        with subprocess.Popen([*command, str(path)], stdout=subprocess.PIPE, stderr=error_file) as process:
            try:
                for line in process.stdout:
                    trace_sha.update(line)
                    if line.startswith(b"G "):
                        input_sha.update(line)
                    result.consume(line)
                exit_code = process.wait()
            except BaseException:
                process.kill()
                raise
        if exit_code:
            error_file.seek(0)
            raise RuntimeError(f"{path.name}: {error_file.read().decode('utf-8', 'replace')}")
    result.input_sha256 = input_sha.hexdigest()
    result.trace_sha256 = trace_sha.hexdigest()
    result.verify(case, mode, window_us)
    return result


def summarize(case: Case, mode: str, window_us: int, result: PaperTrace, config: bytes) -> dict:
    end_ns = window_us * 1000
    activity = result.summaries["WIFI_AP_ACTIVITY"]
    energy = result.summaries["WIFI_AP_ENERGY"]
    delivered = {packet for packet, time in result.delivered.items() if time < end_ns}
    dropped = {packet for packet, time in result.dropped.items() if time < end_ns}
    pending = result.generated.keys() - delivered - dropped
    delays = [result.delivered[packet] - result.generated[packet] for packet in delivered]
    successful_bits = len(delivered) * case.packet_bytes * 8
    paper_powers = paper_power_w(mode)
    paper_terms = {state: paper_powers[state] * us(int(activity[f"{state}_ns"])) for state in STATES}
    paper_energy = sum(paper_terms.values())
    b3_energy = Decimal(energy["total_energy_uj"])
    summary = dict(
        scenario=case.name(), arrival_model=case.arrival_model, packet_bytes=case.packet_bytes,
        interval_us=case.interval_us, seed=case.seed, mode=mode, duration_us=window_us,
        nominal_load_mbps=case.nominal_load, offered_load_mbps=Decimal(len(result.generated) * case.packet_bytes * 8) / window_us,
        throughput_mbps=Decimal(successful_bits) / window_us,
        generated_packets=len(result.generated), delivered_packets=len(delivered), successful_bits=successful_bits,
        dropped_packets=len(dropped), backlog_packets=len(pending),
        drain_delivered_packets=len(pending & result.delivered.keys()),
        drain_dropped_packets=len(pending & result.dropped.keys()),
        natural_end_us=result.summaries["RUN"]["simulation_end_us"],
        first_data_ppdu_us=us(result.first_data_end[1] - result.first_tx[1]),
        e2e_mean_us=us(sum(delays)) / len(delays) if delays else "",
        e2e_p95_us=percentile_us(delays, 95), e2e_p99_us=percentile_us(delays, 99),
        e2e_max_us=us(max(delays)) if delays else "",
        total_energy_paper_uj=paper_energy, avg_power_paper_w=paper_energy / window_us,
        energy_per_success_bit_paper_nj=1000 * paper_energy / successful_bits if successful_bits else "",
        total_energy_b3_uj=b3_energy, avg_power_b3_w=energy["avg_power_w"],
        energy_per_success_bit_b3_nj=1000 * b3_energy / successful_bits if successful_bits else "",
        configuration_sha256=digest(config), input_sha256=result.input_sha256, compact_trace_sha256=result.trace_sha256,
    )
    for state in STATES:
        summary[f"{state}_ns"] = activity[f"{state}_ns"]
        summary[f"{state}_power_paper_w"] = paper_powers[state]
        summary[f"{state}_energy_paper_uj"] = paper_terms[state]
        summary[f"{state}_power_b3_w"] = energy[f"{state}_power_w"]
        summary[f"{state}_energy_b3_uj"] = energy[f"{state}_energy_uj"]
    return summary


def run_pair(command: list[str], temp: Path, plan: dict, case: Case) -> list[dict]:
    window_us = plan["observation_us"]
    arrivals = periodic_arrivals(case.interval_us, window_us) if case.arrival_model == "PERIODIC" else b""
    results = []
    for mode in MODES:
        config = case.configuration(plan, mode, arrivals)
        path = temp / f"{case.name()}-{mode.lower()}.properties"
        path.write_bytes(config)
        outcome = collect(command, path, window_us, case, mode)
        results.append(summarize(case, mode, window_us, outcome, config))
        path.unlink()
    require(results[0]["input_sha256"] == results[1]["input_sha256"], f"Unequal offered packets: {case.name()}")
    return results


def compile_driver(jar: Path, destination: Path) -> list[str]:
    subprocess.run(["javac", "--release", "11", "-cp", str(jar), "-d", str(destination),
                    str(ROOT / "tools/java/B5PaperTrace.java")], check=True)
    return ["java", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-cp", os.pathsep.join((str(jar), str(destination))),
            "B5PaperTrace"]


def all_cases(plan: dict) -> list[Case]:
    periodic = [Case("PERIODIC", size, interval, seed) for size in plan["packet_bytes"]
                for interval in plan["interval_us"] for seed in plan["seeds"]]
    poisson = [Case("POISSON", point["packet_bytes"], point["interval_us"], seed)
               for point in plan["poisson_control"] for seed in plan["seeds"]]
    return periodic + poisson


def emit_configs(plan: dict, cases: list[Case], destination: Path, only: str | None) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    selected = [case for case in cases if only is None or case.name() == only]
    require(bool(selected), f"No configured scenario named {only}")
    for case in selected:
        arrivals = periodic_arrivals(case.interval_us, plan["observation_us"]) if case.arrival_model == "PERIODIC" else b""
        for mode in MODES:
            (destination / f"{case.name()}-{mode.lower()}.properties").write_bytes(case.configuration(plan, mode, arrivals))


def write_paired_deltas(output: Path, rows: list[dict]) -> None:
    deltas = []
    for lcm, hcm in zip(rows[::2], rows[1::2]):
        require(lcm["mode"] == "LCM" and hcm["mode"] == "HCM" and lcm["scenario"] == hcm["scenario"],
                "Unmatched pair in power delta")
        pair = {key: lcm[key] for key in
                ("scenario", "arrival_model", "packet_bytes", "interval_us", "seed", "offered_load_mbps", "input_sha256")}
        for calibration in ("paper", "b3"):
            difference = Decimal(hcm[f"avg_power_{calibration}_w"]) - Decimal(lcm[f"avg_power_{calibration}_w"])
            pair[f"hcm_minus_lcm_{calibration}_w"] = difference
            for state in STATES:
                contribution = (Decimal(hcm[f"{state}_energy_{calibration}_uj"])
                                - Decimal(lcm[f"{state}_energy_{calibration}_uj"]))
                pair[f"{state}_delta_{calibration}_w"] = contribution / lcm["duration_us"]
        for mode, row in (("lcm", lcm), ("hcm", hcm)):
            pair[f"{mode}_throughput_mbps"] = row["throughput_mbps"]
            pair[f"{mode}_backlog_packets"] = row["backlog_packets"]
        pair["same_success_count"] = lcm["delivered_packets"] == hcm["delivered_packets"]
        deltas.append(pair)
    write_csv(output / "paper-aligned-paired-deltas.csv", tuple(deltas[0]), deltas)


def measure(jar: Path, plan: dict, cases: list[Case], output: Path, jobs: int) -> None:
    results = []
    with TemporaryDirectory(prefix="fttr-paper-b5-") as folder, ThreadPoolExecutor(max_workers=jobs) as pool:
        temp = Path(folder)
        command = compile_driver(jar, temp)
        futures = [pool.submit(run_pair, command, temp, plan, case) for case in cases]
        for index, future in enumerate(futures, 1):
            results.extend(future.result())
            if index % 8 == 0 or index == len(cases):
                print(f"{index}/{len(cases)} paper-aligned pairs verified", flush=True)
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "paper-aligned-metrics.csv", tuple(results[0]), results)
    write_paired_deltas(output, results)
    manifest = dict(number_of_runs=len(results), number_of_pairs=len(cases), jar_sha256=digest(jar.read_bytes()),
                    trace_projection_sha256=digest((ROOT / "tools/java/B5PaperTrace.java").read_bytes()), experiment=plan)
    (output / "paper-experiment-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, default=ROOT / "configs/b5-paper-aligned.json")
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--smoke", action="store_true", help="Run only two periods and a Poisson control at 100 ms.")
    parser.add_argument("--emit-configs", type=Path, help="Generate exact .properties files instead of running Java.")
    parser.add_argument("--scenario", help="When emitting configs, emit only this scenario (without the mode suffix).")
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    output = args.output
    if args.smoke:
        plan.update(observation_us=100_000, packet_bytes=[1200], interval_us=[90, 300],
                    seeds=plan["seeds"][:1], poisson_control=plan["poisson_control"][:1])
        if output == OUTPUT:
            output = ROOT / "target/b5-paper-smoke"
    cases = all_cases(plan)
    if args.emit_configs:
        emit_configs(plan, cases, args.emit_configs, args.scenario)
        return
    if not args.jar.is_file() or args.jobs < 1:
        parser.error("A simulator JAR and positive worker count are required.")
    measure(args.jar.resolve(), plan, cases, output, args.jobs)


if __name__ == "__main__":
    main()
