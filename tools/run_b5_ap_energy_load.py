"""Run the B5 paired Java experiments and verify packet, time and energy conservation."""

from __future__ import annotations

import argparse
from bisect import bisect_right
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory, TemporaryFile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from pon_experiment_io import ROOT, require, write_csv


STATES = ("idle", "tx", "rx")
CLASSES = ("VO", "VI", "BE", "BK")
MODES = ("LCM", "HCM")
PROFILE = "RESEARCH_INITIAL_V1"
EVENT_MARKERS = (
    b" event=ARRIVAL ", b" event=DELIVERED ", b" event=WIFI_ACK_END ",
    b" event=WIFI_RETRY_LIMIT_DROP ", b" event=WIFI_COLLISION_DROP ",
    b" event=WIFI_AP_ACTIVITY_CHANGE ",
)


def fields(line: bytes) -> dict[str, str]:
    return dict(word.split("=", 1) for word in line.decode("utf-8").split() if "=" in word)


def nanos(us: str) -> int:
    exact = Decimal(us) * 1000
    require(exact == int(exact), f"Event time is not on the nanosecond clock: {us} us")
    return int(exact)


def us(ns: int) -> Decimal:
    return Decimal(ns).scaleb(-3)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ActivityTimeline:
    """Snapshots of exact per-state time at each B2 transition, including the initial IDLE state."""

    def __init__(self) -> None:
        self.times = [0]
        self.states = ["idle"]
        self.totals = [(0, 0, 0)]

    def add(self, time_ns: int, source: str, target: str) -> None:
        require(time_ns >= self.times[-1] and source == self.states[-1] and target in STATES,
                f"Unexpected AP activity transition at {time_ns}: {source} -> {target}")
        self.totals.append(self.at(time_ns))
        self.times.append(time_ns)
        self.states.append(target)

    def at(self, time_ns: int) -> tuple[int, int, int]:
        index = bisect_right(self.times, time_ns) - 1
        elapsed = list(self.totals[index])
        elapsed[STATES.index(self.states[index])] += time_ns - self.times[index]
        return tuple(elapsed)


@dataclass
class Trace:
    # The input identity includes direction, intended STA and access category.
    generated: dict[int, tuple[int, int, str, str, int]] = field(default_factory=dict)
    delivered: dict[int, int] = field(default_factory=dict)
    drops: dict[int, int] = field(default_factory=dict)
    ack_ends: dict[int, int] = field(default_factory=dict)
    timeline: ActivityTimeline = field(default_factory=ActivityTimeline)
    activity: dict[str, str] = field(default_factory=dict)
    energy: dict[str, str] = field(default_factory=dict)
    total: dict[str, str] = field(default_factory=dict)
    sha256: str = ""

    def handle(self, row: dict[str, str]) -> None:
        summary = row.get("summary")
        if summary == "WIFI_AP_ACTIVITY":
            require(not self.activity, "B5 expects one SFU in each run")
            self.activity = row
        elif summary == "WIFI_AP_ENERGY":
            require(not self.energy, "B5 expects one modeled AP in each run")
            self.energy = row
        elif "simulation_end_us" in row:
            self.total = row
        else:
            self.handle_event(row)

    def handle_event(self, row: dict[str, str]) -> None:
        event = row["event"]
        if event == "WIFI_AP_ACTIVITY_CHANGE":
            time_ns = int(row["t_ns"])
            self.timeline.add(time_ns, row["from"].lower(), row["to"].lower())
            return
        packet = int(row["packet_id"])
        timestamp = nanos(row["t_us"])
        if event == "ARRIVAL":
            if row["node"] == "SFU":
                return  # The DL arrival at the relay is not a second generated packet.
            direction = row.get("direction", "DL")
            correct_origin = "MFU" if direction == "DL" else "STA"
            require(packet not in self.generated and row["node"] == correct_origin,
                    f"Unexpected source event for packet {packet}")
            peer = row["destination_sta_id"] if direction == "DL" else row["source_sta_id"]
            self.generated[packet] = (timestamp, int(row["bytes"]), direction, row["type"], int(peer))
        else:
            require(packet in self.generated, f"Event with no source packet: {packet}")
            generation, size, direction, category, _ = self.generated[packet]
            require(timestamp >= generation and row.get("direction", "DL") == direction
                    and int(row["bytes"]) == size and row["type"] == category,
                    f"Packet identity changed along the route for {packet}")
            if event == "DELIVERED":
                require(row["node"] == ("STA" if direction == "DL" else "MFU"), f"Wrong sink for packet {packet}")
                self.put_once(self.delivered, packet, timestamp)
            elif event == "WIFI_ACK_END":
                self.put_once(self.ack_ends, packet, timestamp)
            else:
                self.put_once(self.drops, packet, timestamp)

    @staticmethod
    def put_once(records: dict[int, int], packet: int, time_ns: int) -> None:
        require(packet not in records, f"Duplicate outcome for packet {packet}")
        records[packet] = time_ns

    def verify(self, mode: str, window_ns: int, packet_bytes: int) -> None:
        require(self.energy and self.activity and self.total, "Missing B2, B3 or run summary")
        require(self.energy["model"] == PROFILE and self.energy["ap_mode"] == self.activity["ap_mode"] == mode,
                "B3 model/profile does not match the B5 configuration")
        require(all(row["sfu_id"] == "1" and int(row["observation_start_ns"]) == 0
                    and int(row["observation_end_ns"]) == window_ns for row in (self.activity, self.energy)),
                "Unexpected observation window or SFU")
        require(all(0 <= time_ns < window_ns and size == packet_bytes
                    for time_ns, size, *_ in self.generated.values()), "Offered traffic exceeds its time or size bounds")
        require(self.delivered.keys().isdisjoint(self.drops) and self.ack_ends.keys() == self.delivered.keys()
                and self.delivered.keys() | self.drops.keys() == self.generated.keys(),
                "Each generated packet must have a unique delivery or explicit final drop after draining")
        require(int(self.total["delivered_packets"]) == len(self.delivered)
                and int(self.total["delivered_bytes"]) == len(self.delivered) * packet_bytes,
                "Run summary differs from counted deliveries")
        require(self.timeline.times[-1] <= window_ns, "Activity trace extends beyond the fixed window")
        durations = self.timeline.at(window_ns)
        require(sum(durations) == window_ns
                and durations == tuple(int(self.activity[f"{state}_ns"]) for state in STATES),
                "Activity intervals must partition the window and match the B2 summary")
        by_state = self.state_energies(durations)
        require(all(value == Decimal(self.energy[f"{state}_energy_uj"])
                    for state, value in zip(STATES, by_state))
                and sum(by_state) == Decimal(self.energy["total_energy_uj"]),
                "Energy integration differs from the B3 summary")
        require(abs(sum(by_state) / us(window_ns) - Decimal(self.energy["avg_power_w"])) < Decimal("1e-20"),
                "Average power differs from the B3 summary")

    def state_energies(self, durations: tuple[int, ...]) -> tuple[Decimal, ...]:
        return tuple(Decimal(self.energy[f"{state}_power_w"]) * us(duration)
                     for state, duration in zip(STATES, durations))

    def complete_before(self, window_ns: int) -> list[tuple[int, int]]:
        # Include the last ACK and the sink delivery for every packet in the equal-count comparison.
        completed = [(max(delivered, self.ack_ends[packet]), packet) for packet, delivered in self.delivered.items()]
        return sorted((time_ns, packet) for time_ns, packet in completed if time_ns < window_ns)


def run_java(jar: Path, config: Path) -> Trace:
    result = Trace()
    sha = hashlib.sha256()
    with TemporaryFile() as stderr:
        with subprocess.Popen(["java", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-jar", str(jar), str(config)],
                              stdout=subprocess.PIPE, stderr=stderr) as process:
            try:
                for line in process.stdout:
                    sha.update(line)
                    if line.startswith((b"summary=WIFI_AP_ACTIVITY ", b"summary=WIFI_AP_ENERGY ", b"delivered_packets=")
                                       ) or any(marker in line for marker in EVENT_MARKERS):
                        result.handle(fields(line))
                exit_code = process.wait()
            except BaseException:
                process.kill()
                raise
        if exit_code:
            stderr.seek(0)
            raise RuntimeError(f"{config.name} exited with code {exit_code}: {stderr.read().decode('utf-8', 'replace')}")
    result.sha256 = sha.hexdigest()
    return result


def scenario_properties(plan: dict, direction: str, load: int, seed: int, mode: str) -> bytes:
    props = dict(plan["properties"])
    window = plan["observation_us"]
    props.update({"traffic.direction": direction, "traffic.duration.us": window, "wifi.ap.activity.window.us": window,
                  "wifi.mac.edca.seed": seed, "sfu.1.wifi.ap.mode": mode})
    station_count = int(props["sta.count"])
    direction_load = Decimal(load) / (2 if direction == "BOTH" else 1)
    sources = []
    if direction in ("DL", "BOTH"):
        sources.append(("traffic.sfu.1", direction_load, seed * 100))
    if direction in ("UL", "BOTH"):
        sources.extend((f"traffic.sta.{sta}", direction_load / station_count, seed * 100 + sta)
                       for sta in range(1, station_count + 1))
    for prefix, source_load, source_seed in sources:
        props[f"{prefix}.seed"] = source_seed
        for category in CLASSES:
            stream = f"{prefix}.class.{category}"
            props[f"{stream}.rate.mbps"] = format(source_load / len(CLASSES), "f")
            props[f"{stream}.packet.bytes"] = plan["payload_bytes"]
    return "".join(f"{key}={value}\n" for key, value in sorted(props.items())).encode("ascii")


def percentile_us(delays: list[int], percentile: int) -> Decimal | str:
    if not delays:
        return ""
    rank = (len(delays) * percentile + 99) // 100
    return us(sorted(delays)[rank - 1])


def run_metrics(trace: Trace, window_us: int, packet_bits: int, common: int) -> dict:
    window_ns = window_us * 1000
    generated = trace.generated.keys()
    delivered = {packet for packet, time_ns in trace.delivered.items() if time_ns < window_ns}
    dropped = {packet for packet, time_ns in trace.drops.items() if time_ns < window_ns}
    pending = generated - delivered - dropped
    all_delays = [trace.delivered[packet] - trace.generated[packet][0] for packet in delivered]
    total_energy = Decimal(trace.energy["total_energy_uj"])
    successful_bits = len(delivered) * packet_bits
    result = dict(
        observation_us=window_us, generated_packets=len(generated), delivered_packets=len(delivered),
        dropped_packets=len(dropped), backlog_packets=len(pending),
        drop_rate_pct=Decimal(100 * len(dropped)) / len(generated) if generated else "",
        backlog_rate_pct=Decimal(100 * len(pending)) / len(generated) if generated else "",
        successful_bits=successful_bits, actual_load_mbps=Decimal(len(generated) * packet_bits) / window_us,
        throughput_mbps=Decimal(successful_bits) / window_us,
        e2e_p95_us=percentile_us(all_delays, 95), e2e_p99_us=percentile_us(all_delays, 99),
        p95_low_sample=len(delivered) < 20, p99_low_sample=len(delivered) < 100,
        total_energy_uj=total_energy, average_power_w=trace.energy["avg_power_w"],
        energy_per_success_bit_nj=1000 * total_energy / successful_bits if successful_bits else "",
        post_window_delivered_packets=len(pending & trace.delivered.keys()),
        post_window_dropped_packets=len(pending & trace.drops.keys()),
        natural_end_delivered_packets=len(trace.delivered), natural_end_dropped_packets=len(trace.drops),
        natural_end_pending_packets=0, natural_end_us=trace.total["simulation_end_us"],
        equal_success_packets=common, equal_success_bits=common * packet_bits,
        fully_completed_in_window_packets=len(trace.complete_before(window_ns)),
    )
    for state in STATES:
        result[f"{state}_ns"] = trace.activity[f"{state}_ns"]
        result[f"{state}_power_w"] = trace.energy[f"{state}_power_w"]
        result[f"{state}_energy_uj"] = trace.energy[f"{state}_energy_uj"]
    for direction in ("DL", "UL"):
        packets = {packet for packet, detail in trace.generated.items() if detail[2] == direction}
        suffix = direction.lower()
        result[f"generated_packets_{suffix}"] = len(packets)
        result[f"delivered_packets_{suffix}"] = len(delivered & packets)
        result[f"throughput_mbps_{suffix}"] = Decimal(len(delivered & packets) * packet_bits) / window_us
        result[f"dropped_packets_{suffix}"] = len(dropped & packets)
        result[f"backlog_packets_{suffix}"] = len(pending & packets)
    result.update(equal_completion_metrics(trace, window_ns, common, packet_bits))
    return result


def equal_completion_metrics(trace: Trace, window_ns: int, count: int, packet_bits: int) -> dict:
    keys = tuple(f"equal_success_{key}" for key in
                 ("end_ns", "idle_ns", "tx_ns", "rx_ns", "energy_uj", "energy_per_bit_nj", "dl_packets", "ul_packets"))
    if count == 0:
        return dict.fromkeys(keys, "")
    end_ns, _ = trace.complete_before(window_ns)[count - 1]
    completed_count = sum(time_ns <= end_ns for time_ns in trace.delivered.values())
    require(completed_count == count, "Equal-count endpoint also delivered a packet outside the comparison cohort")
    durations = trace.timeline.at(end_ns)
    energy = sum(trace.state_energies(durations))
    dl_count = sum(time_ns <= end_ns and trace.generated[packet][2] == "DL"
                   for packet, time_ns in trace.delivered.items())
    return dict(zip(keys, (end_ns, *durations, energy, 1000 * energy / (count * packet_bits),
                           dl_count, count - dl_count)))


def paired_run(jar: Path, temp: Path, plan: dict, direction: str, load: int, seed: int) -> tuple[list, dict]:
    prefix = f"{direction.lower()}-{load:03d}mbps-{seed}"
    traces = {}
    configs = {}
    config_files = {}
    for mode in MODES:
        name = f"{prefix}-{mode.lower()}.properties"
        contents = scenario_properties(plan, direction, load, seed, mode)
        config_path = temp / name
        config_path.write_bytes(contents)
        traces[mode] = run_java(jar, config_path)
        traces[mode].verify(mode, plan["observation_us"] * 1000, plan["payload_bytes"])
        configs[name] = contents
        config_files[mode] = name
    require(traces["LCM"].generated == traces["HCM"].generated, f"Offered packets differ for {prefix}")
    identities = sorted(traces["LCM"].generated.items())
    input_sha = digest(json.dumps(identities, separators=(",", ":")).encode("ascii"))
    common = min(len(trace.complete_before(plan["observation_us"] * 1000)) for trace in traces.values())
    results = [
        dict(direction=direction, load_mbps=load, seed=seed, mode=mode, sfu_id=1,
             configuration=config_files[mode], configuration_sha256=digest(configs[config_files[mode]]),
             input_sha256=input_sha, java_trace_sha256=traces[mode].sha256,
             **run_metrics(traces[mode], plan["observation_us"], plan["payload_bytes"] * 8, common))
        for mode in MODES
    ]
    return results, configs


def measure(jar: Path, plan: dict, output: Path, jobs: int) -> None:
    tasks = [(direction, load, seed) for direction in plan["directions"]
             for load in plan["loads_mbps"] for seed in plan["seeds"]]
    results = []
    configurations = {}
    with TemporaryDirectory(prefix="fttr-b5-") as folder, ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [pool.submit(paired_run, jar, Path(folder), plan, *task) for task in tasks]
        for index, (task, future) in enumerate(zip(tasks, futures), 1):
            rows, configs = future.result()
            results.extend(rows)
            configurations.update(configs)
            if index % 10 == 0 or index == len(tasks):
                print(f"{index}/{len(tasks)} paired runs verified, last {task}", flush=True)
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "energy-load-metrics.csv", tuple(results[0]), results)
    # Fixed metadata makes the archive portable and byte-reproducible.
    with ZipFile(output / "test-configs.zip", "w") as archive:
        for name, contents in sorted(configurations.items()):
            archive.writestr(ZipInfo(name, (2020, 1, 1, 0, 0, 0)), contents, compress_type=ZIP_DEFLATED)
    manifest = {"jar_sha256": digest(jar.read_bytes()), "number_of_runs": len(results), "experiment": plan}
    (output / "experiment-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, default=ROOT / "configs/b5-energy-load.json")
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/b5-energy-load")
    parser.add_argument("--jobs", type=int, default=5)
    parser.add_argument("--pilot", action="store_true", help="Probe four loads, one seed and a 100 ms BOTH window.")
    parser.add_argument("--measure-only", action="store_true", help="Skip aggregation and figures.")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error(f"Build the simulator JAR first: {args.jar}")
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    output = args.output
    if args.pilot:
        plan.update(observation_us=100_000, loads_mbps=[0, 10, 35, 80], seeds=plan["seeds"][:1], directions=["BOTH"])
        if output == ROOT / "results/b5-energy-load":
            output = ROOT / "target/b5-energy-load-pilot"
    measure(args.jar.resolve(), plan, output, args.jobs)
    if not args.measure_only:
        from analyze_b5_energy_load import analyze
        from plot_b5_energy_load import plot

        analyze(output)
        plot(output)


if __name__ == "__main__":
    main()
