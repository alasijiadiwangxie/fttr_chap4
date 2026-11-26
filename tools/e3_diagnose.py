"""Replay E2 decisions on the E1 path and fork a matched short-window alternative."""

from __future__ import annotations

import argparse
import os
import subprocess
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path

from e0_switch_metrics import ACTIVITY, CCA, DECISION, FINGERPRINT_EVENTS, PREDICT, REQUEST, SCAN, by_candidate, local_outcome
from e0_trials import capture
from e2_analyze import save
from e2_design import DEADLINES, ECQ, arrivals, properties, study
from pon_experiment_io import ROOT, require
from verify_c1_ap_observation import time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import events, packet_inputs
from verify_d1_mode_control import JAR

WORK = ROOT / "target/e3-decision"
OUT = ROOT / "results/e3-decision"
CASE_SEED = 11317  # E2 seed for diagnosis only.
DIAGNOSES = (
    ("load-050", 45_000_000, "HCM", 15_000_000),
    ("rf-primary-strong-35", None, "HCM", 25_000_000),
    ("rf-secondary-strong-15", None, "HCM", 25_000_000),
    ("rf-secondary-strong-35", None, "HCM", 25_000_000),
    ("mechanisms", None, "HCM", 15_000_000),
)


def replace_once(code, original, replacement):
    require(code.count(original) == 1, f"Review E3 probe location: {original}")
    return code.replace(original, replacement)


def compile_probe(jar):
    src = WORK / "probe-source"
    classes = WORK / "probe-classes"
    src.mkdir(parents=True, exist_ok=True)
    classes.mkdir(exist_ok=True)
    entry = (ROOT / "tools/java/E0Trace.java").read_text(encoding="utf-8")
    entry = replace_once(entry, "    private static Probe probe;",
                         "    private static Probe probe;\n    private static String forcedMode;")
    entry = replace_once(entry, "} else if (args.length == 4) {",
                         "} else if (args.length == 5) {\n            forcedMode = args[4];")
    entry = replace_once(entry, "    static boolean periodicQosOnly() {",
                         "    static edu.suda.fttr.phy.ApCapabilityMode override(int sfu, long now) {\n"
                         "        return hold(sfu, now) ? edu.suda.fttr.phy.ApCapabilityMode.valueOf(forcedMode) : null;\n"
                         "    }\n\n    static boolean periodicQosOnly() {")
    (src / "E0Trace.java").write_text(entry, encoding="utf-8")
    # Always compile the original E1 SimulationContext to keep the counterfactual separate from E3.
    frozen = subprocess.check_output(["git", "show", "fdb20aa:src/main/java/edu/suda/fttr/simulator/SimulationContext.java"],
                                     cwd=ROOT).decode("utf-8")
    frozen = replace_once(frozen, "        Event event = events.poll();",
                          "        Event event = events.poll();\n"
                          "        if (event != null) E0Trace.stopAfter(event.timeTicks());")
    frozen = replace_once(frozen, "                if (guard && rescue == null) {",
                          "                var forced = E0Trace.override(sfuId, now);\n"
                          "                if (forced != null) {\n"
                          "                    outcome = applyQosModeTarget(track, trigger, forced, 0, ready);\n"
                          "                } else if (guard && rescue == null) {")
    (src / "SimulationContext.java").write_text(frozen, encoding="utf-8")
    subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes),
                    str(src / "E0Trace.java"), str(src / "SimulationContext.java")], check=True)
    return ["java", "-Xmx700m", "-XX:ActiveProcessorCount=2", "-cp", os.pathsep.join((str(classes), str(jar))),
            "edu.suda.fttr.simulator.E0Trace"]


def choose_anchor(rows, configured, alternate):
    if configured is not None:
        return next(r for r in events(rows, DECISION) if time_ns(r) == configured)
    candidates = by_candidate(rows, PREDICT)
    # Pick a periodic decision after the initial transient where the other mode promises more service.
    stable = [r for r in events(rows, DECISION) if r["reason"] == "HOLD_UNPROVEN_QOS" and r["action"] == "HOLD"
              and r["trigger"] == "PERIODIC" and r["current_mode"] != alternate and 50_000_000 <= time_ns(r) <= 250_000_000]
    require(stable, "No E1 hold on LCM in the diagnostic range")
    improves = [r for r in stable if float(candidates["1", time_ns(r), alternate]["success_bits"])
                > float(candidates["1", time_ns(r), r["current_mode"]]["success_bits"])]
    return (improves or stable)[0]


def prefix_at(raw, rows, anchor):
    now = time_ns(anchor)
    index = int(anchor["trace_sequence"]) - 1
    if anchor["action"] == "REQUESTED":
        index = next(i for i in range(index - 1, -1, -1) if rows[i].get("event") == REQUEST and time_ns(rows[i]) == now)
    digest = sha256()
    for line, row in zip(raw.splitlines()[:index], rows[:index]):
        if row.get("event") in FINGERPRINT_EVENTS:
            digest.update((line.strip() + "\n").encode())
    state = next((r for r in reversed(rows[:index]) if r.get("event") == ACTIVITY), None)
    active_scans = [r for r in rows[:index] if r.get("event") == SCAN and r["phase"] == "START"
                    and int(r["started_ns"]) < now < int(r["ended_ns"])]
    return index, digest.hexdigest(), (state["ap_mode"], state["to"], active_scans) if state is not None else (
        anchor["current_mode"], "IDLE", active_scans)


def diagnose(command, cases, description):
    scenario, configured, alternate, span = description
    case = cases[scenario]
    settings = properties(case, CASE_SEED, ECQ, arrivals(case, CASE_SEED))
    directory = WORK / "diagnosis"
    directory.mkdir(exist_ok=True)
    path = directory / f"{scenario}.properties"
    write_properties(path, settings)
    raw = capture(command, path).decode("utf-8")
    path.with_suffix(".log").write_text(raw, encoding="utf-8")
    rows = parse_trace(raw)
    packets = packet_inputs(rows, settings)
    anchor = choose_anchor(rows, configured, alternate)
    left = time_ns(anchor)
    index, prefix, initial = prefix_at(raw, rows, anchor)
    raw_fork = capture(command, path, 1, left, left + span, alternate).decode("utf-8")
    path.with_suffix(".fork.log").write_text(raw_fork, encoding="utf-8")
    fork = parse_trace(raw_fork)
    witness, = events(fork, "E0_PREFIX")
    require(witness["sha256"] == prefix, f"{scenario}: divergent history at {left}")
    candidates = by_candidate(rows, PREDICT)
    snapshot = dict(scenario=scenario, seed=CASE_SEED, decision_ns=left, current=anchor["current_mode"],
                    e1_target=anchor["target_mode"], alternate=alternate, e1_reason=anchor["reason"],
                    e1_keep_qos=anchor["keep_qos"], e1_change_qos=anchor["change_qos"], prefix_sha256=prefix,
                    predicted_switch_saving_uj=anchor["net_saving_uj"], margin_uj=anchor["margin_uj"])
    for mode in ("LCM", "HCM"):
        estimate = candidates["1", left, mode]
        for field in ("ewma_mbps", "saturated_service_mbps", "success_bits", "expected_busy_fraction",
                      "channel_incomplete", "cost_uj", "tail_uj"):
            if field in estimate:
                snapshot[f"{mode.lower()}_{field}"] = estimate[field]
    result = []
    # The same fork is scored over one decision cycle and a longer interval to expose backlog effects.
    for interval in (5_000_000, span):
        right = left + interval
        deadlines = {p: birth + DEADLINES[ac] * 1000 for p, (birth, _, ac, *_) in packets.items()
                     if birth <= right and birth + DEADLINES[ac] * 1000 >= left}
        result.extend(snapshot | dict(branch=label, until_ns=right, duration_ns=interval)
                      | local_outcome(history, packets, "1", left, right, deadlines, initial, settings)
                      for label, history in (("E1_actual", rows[index:]), ("forced_" + alternate, fork)))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    args = parser.parse_args()
    command = compile_probe(args.jar)
    cases = {case.name: case for case in study()}
    with ThreadPoolExecutor(max_workers=4) as pool:
        trials = list(pool.map(lambda item: diagnose(command, cases, item), DIAGNOSES))
    save(OUT / "counterfactual.csv", [row for trial in trials for row in trial])


if __name__ == "__main__":
    main()
