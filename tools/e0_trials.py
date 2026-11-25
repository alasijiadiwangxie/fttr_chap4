"""Replay the frozen D5 scenarios and build controlled E0 test inputs."""

from __future__ import annotations

import os
import subprocess
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from itertools import product
from pathlib import Path

from d5_experiments import D4, FIXED, FULL, JOINT, RF, SEEDS, STEP, configuration, pon_settings
from pon_experiment_io import ROOT, require
from verify_c1_ap_observation import write_properties
from verify_d2_energy_decision import base, set_associations
from verify_d3_qos_deferral import DEADLINES, scenario_config
from verify_d5_ecq_sdps import JAR


WORK = ROOT / "target/e0-diagnosis"
OUT = ROOT / "results/e0-diagnosis"
JAVA_CLASS = "edu.suda.fttr.simulator.E0Trace"
THRESHOLD = 31  # Frozen by the independent D5 calibration.


@dataclass(frozen=True)
class Trial:
    group: str
    scenario: str
    seed: int
    variant: str
    end_us: int
    settings: dict[str, str]

    @property
    def name(self):
        return f"{self.scenario}-{self.seed}-{self.variant}"

    @property
    def end_ns(self):
        return self.end_us * 1000

    def identity(self):
        return dict(group=self.group, scenario=self.scenario, seed=self.seed, variant=self.variant, window_ns=self.end_ns)


def burst_inputs(settings, gap_us, deadline_pair):
    """All four deadline and gap cases are declared before observing results."""
    set_associations(settings, (1, 1))
    end = int(settings["wifi.ap.activity.window.us"])
    arrivals = []
    for first in range(6500, end - 2500, gap_us):
        for i, t in enumerate(range(first, first + 1500, 125)):
            arrivals.append((t, 1 + i % 2, "BE", 1500))
        arrivals.extend(((first + 50, 1, "VO", 500), (first + 400, 2, "VI", 1000), (first + 795, 1, "VI", 1000)))
    arrivals.sort()
    settings["packet.arrival.us"] = ",".join(str(t) for t, *_ in arrivals)
    for index, (_, sta, ac, size) in enumerate(arrivals, 1):
        prefix = f"packet.{index}."
        settings.update({prefix + "sta.id": str(sta), prefix + "type": ac, prefix + "bytes": str(size)})
    for ac, deadline in (DEADLINES | dict(zip(("VO", "VI"), deadline_pair))).items():
        settings[f"wifi.ap.qos.{ac}.deadline.us"] = str(deadline)


def pon_fixture(byte_counts, quota, poll_end_us):
    # The input is already at the optical bottleneck. There is no EDCA or active AP power in this fixture.
    settings = {"traffic.mode": "manual", "traffic.direction": "UL", "sfu.count": "1", "sta.count": "1",
                "sta.1.sfu.id": "1", "wifi.model": "fixed", "wifi.delay.us": "0"}
    pon_settings(settings, 0, False)
    settings["fiber.uplink.poll.end.us"] = str(poll_end_us)
    prefix = "fiber.uplink.tcont.alloc.103."
    settings = {key: value for key, value in settings.items()
                if not key.startswith("fiber.uplink.tcont.alloc.") or key.startswith(prefix) or key.endswith(".ids")}
    settings["fiber.uplink.tcont.alloc.ids"] = "103"
    settings[prefix + "classes"] = "VO,VI,BE,BK"
    settings[prefix + "ab.min.bytes"] = str(quota)
    settings[prefix + "ab.sur.bytes"] = str(quota)
    settings["packet.arrival.us"] = ",".join(str(25 * i) for i in range(1, len(byte_counts) + 1))
    settings["packet.bytes"] = "1500"
    for i, size in enumerate(byte_counts, 1):
        settings[f"packet.{i}.bytes"] = str(size)
    return settings


def trials():
    for case, seed, variant in product((RF[2], STEP), SEEDS, ("full", "no-channel", "no-qos", "periodic-qos", "d4", "threshold")):
        policy = {"d4": D4, "threshold": FIXED}.get(variant, FULL)
        disabled = {"no-channel": "channel", "no-qos": "qos"}.get(variant, "")
        yield Trial("switch", case.name, seed, variant, case.window_us, configuration(case, seed, policy, THRESHOLD, disabled))

    for seed, variant in product(SEEDS, ("deferred", "immediate")):
        settings = scenario_config("mixed-primary", seed, FULL)
        settings["wifi.ap.control.threshold.mbps"] = str(THRESHOLD)
        if variant == "immediate":
            settings["wifi.ap.control.complete.deferral.enabled"] = "false"
        yield Trial("deferral", "d5-primary-anchor", seed, variant, 120000, settings)
    for gap, (budget, deadlines), seed, variant in product(
            (6000, 16000), (("tight", (650, 1400)), ("relaxed", (1800, 3600))), SEEDS, ("deferred", "immediate")):
        settings = base(seed, FULL, 96000)
        settings.update({"wifi.ap.control.service.model": "load-aware", "wifi.ap.control.threshold.mbps": str(THRESHOLD)})
        burst_inputs(settings, gap, deadlines)
        if variant == "immediate":
            settings["wifi.ap.control.complete.deferral.enabled"] = "false"
        yield Trial("deferral", f"burst-{gap // 1000}ms-{budget}", seed, variant, 96000, settings)

    for name, sizes, quota, stop in (
            ("single-1500-q1200", (1500,), 1200, 8000),
            ("single-1200-q1200", (1200,), 1200, 8000),
            ("single-1500-q2400", (1500,), 2400, 8000),
            ("two-1500-q2400", (1500, 1500), 2400, 8000),
            ("two-1500-q2400-long", (1500, 1500), 2400, 20000),
            ("two-1500-q3000", (1500, 1500), 3000, 8000),
    ):
        yield Trial("pon-only", name, 0, "frozen-giant", 2000, pon_fixture(sizes, quota, stop))
    # The PON-only fixture proves the grant cycle. The original joint case retains the measured AP energy.
    case = replace(JOINT, name="joint-giant", optical="giant-complete")
    for seed in SEEDS:
        yield Trial("pon-joint", case.name, seed, "full", case.window_us, configuration(case, seed, FULL, THRESHOLD))


def runner(jar, work):
    """Build temporary hooks using the released source. The normal path uses the unmodified JAR."""
    classes = work / "classes"
    source = work / "probe-src/SimulationContext.java"
    classes.mkdir(parents=True, exist_ok=True)
    source.parent.mkdir(parents=True, exist_ok=True)
    probe_classes = work / "probe-classes"
    probe_classes.mkdir(exist_ok=True)
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes),
                    str(ROOT / "tools/java/E0Trace.java")], check=True)
    original = ROOT / "src/main/java/edu/suda/fttr/simulator/SimulationContext.java"
    code = original.read_text(encoding="utf-8")
    substitutions = (
        ("        Event event = events.poll();", "        Event event = events.poll();\n"
         "        if (event != null) E0Trace.stopAfter(event.timeTicks());"),
        ("outcome = applyQosModeTarget(track, trigger, choice.target, choice.delayNs, ready);",
         "boolean probeHold = E0Trace.hold(sfuId, now);\n"
         "                outcome = applyQosModeTarget(track, trigger, probeHold ? ready.sourceMode : choice.target,\n"
         "                        probeHold ? 0 : choice.delayNs, ready);"),
        ("if (ingress && screenQos && !expired && deadlineModePolicy.urgent",
         "if (ingress && screenQos && !expired && !E0Trace.periodicQosOnly() && deadlineModePolicy.urgent"),
    )
    for match, replacement in substitutions:
        require(code.count(match) == 1, "E0 probe must be rechecked after changes to the frozen controller")
        code = code.replace(match, replacement)
    source.write_text(code, encoding="utf-8")
    classpath = os.pathsep.join(str(path) for path in (classes, jar))
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(probe_classes), str(source)], check=True)
    prefix = ["java", "-Xms32m", "-Xmx640m", "-cp"]
    return (prefix + [classpath, JAVA_CLASS], prefix + [str(probe_classes) + os.pathsep + classpath, JAVA_CLASS])


def capture(command, path, *extra):
    result = subprocess.run([*command, str(path), *map(str, extra)], capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{path.name} {extra}: {result.stderr.decode('utf-8', errors='replace')}")
    return result.stdout


def run_inputs(cases, commands, work, jobs):
    clean, probe = commands

    def produce(trial):
        path = work / f"{trial.name}.properties"
        write_properties(path, trial.settings)
        if trial.variant == "periodic-qos":
            raw = capture(probe, path, "periodic-qos")
        else:
            raw = capture(clean, path)
        path.with_suffix(".log").write_bytes(raw)
        print(trial.name, flush=True)

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        list(pool.map(produce, cases))
