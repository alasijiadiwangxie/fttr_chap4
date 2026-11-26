"""Representative E4 scenarios, registered before threshold calibration and formal replays."""

from __future__ import annotations

from dataclasses import dataclass, replace
from itertools import product
from random import Random

from e2_design import ECQ as E1, FIXED, HCM, LCM, Case, arrivals, properties, study
from e3_experiments import E3, SWITCHES

HYST = "hysteresis"
FIXED_SCAN = "fixed-with-scan"
E3_NO_SCAN = "e3-no-scan"
MAIN = (LCM, HCM, FIXED, HYST, E1, E3)
FEATURES = SWITCHES | {"e3-no-residency": "complete.residency"}
CALIBRATION_SEEDS = (109013, 119081, 123191)
FORMAL_SEEDS = (131071, 135977, 142007, 150011, 157007, 164011, 173021, 181081, 189311, 196613)
BANDS = ((20, 42), (23, 39), (26, 36), (28, 34), (30, 32))
CALIBRATION_STAGES = {
    "calib-step": ((0, 65, 8), (65, 135, 38), (135, 220, 65), (220, 310, 18), (310, 360, 0)),
    "calib-wave": tuple((i * 18, (i + 1) * 18, (24, 39, 34, 28, 44, 32)[i % 6]) for i in range(18)),
}


@dataclass(frozen=True)
class Experiment:
    case: Case
    policies: tuple[str, ...] = MAIN
    reference: str = ""
    gap_us: int = 0
    deadline: str = ""
    aggregation: str = ""

    def metadata(self):
        return self.case.metadata() | dict(reference_scenario=self.reference, burst_gap_us=self.gap_us or None,
                                           deadline_profile=self.deadline, aggregation_opportunity=self.aggregation)


def training():
    grid = (FIXED, *(f"hysteresis-{lo}-{hi}" for lo, hi in BANDS))
    return (
        Experiment(Case("calibration", "calib-step", 65, window_us=360_000), grid),
        Experiment(Case("calibration", "calib-wave", 44, window_us=324_000), grid),
        Experiment(Case("calibration", "calib-bursts", 76, window_us=320_000, traffic="periodic",
                        rf="secondary", intensity="weak", duty=20), grid),
    )


def formal():
    e2 = {case.name: case for case in study()}
    primary = ("load-012", "load-031", "load-050", "load-085", "rf-clear", "rf-primary-strong-35",
               "rf-secondary-strong-15", "rf-secondary-strong-35", "rf-wide-strong-35", "mixed-45", "mixed-100",
               "dynamic-periodic", "dynamic-random", "dynamic-near", "joint-2sfu", "joint-4sfu")
    treatments = {
        "load-031": ("e3-no-deferral",), "load-050": ("e3-no-economics",),
        "rf-primary-strong-35": ("e3-no-capacity",),
        "rf-secondary-strong-15": (FIXED_SCAN, E3_NO_SCAN, "e3-no-channel", "e3-no-qos"),
    }
    for name in primary:
        yield Experiment(e2[name], MAIN + treatments.get(name, ()))
    # Compare the same bidirectional workload with and without secondary-channel interference.
    joint = e2["joint-4sfu"]
    yield Experiment(replace(joint, name="joint-4sfu-rf", traffic_key=joint.workload_key, rf="secondary", duty=15),
                     reference=joint.name)
    yield Experiment(e2["mechanisms"], (E3, *FEATURES))
    for reference in ("load-031", "rf-secondary-strong-15", "joint-4sfu"):
        base = e2[reference]
        case = replace(base, group="06-sensitivity", name=f"paper-{reference}", power="paper",
                       traffic_key=base.workload_key)
        yield Experiment(case, reference=reference)
    for gap, deadline, aggregation in product((6000, 16000), ("tight", "relaxed"), ("grouped", "interleaved")):
        name = f"defer-{gap // 1000}ms-{deadline}-{aggregation}"
        case = Case("06-sensitivity", name, 0, window_us=192_000, traffic="bursts", traffic_key=f"defer-{gap}")
        yield Experiment(case, (E3, "e3-no-deferral"), gap_us=gap, deadline=deadline, aggregation=aggregation)


def packet_fixture(experiment, seed):
    case = experiment.case
    if case.name in CALIBRATION_STAGES:
        packets = []
        for index, (lo, hi, rate) in enumerate(CALIBRATION_STAGES[case.name]):
            stage = replace(case, per_ap_mbps=rate, window_us=(hi - lo) * 1000, traffic_key=f"{case.name}-{index}")
            packets.extend((p[0] + lo * 1000, *p[1:]) for p in arrivals(stage, seed))
        return sorted(packets)
    if not experiment.gap_us:
        return arrivals(case, seed)
    rng = Random(f"E4-bursts:{experiment.gap_us}:{seed}")
    packets = []
    for number, origin in enumerate(range(6500, case.window_us - 2500, experiment.gap_us)):
        first = origin + rng.randint(-240, 240)
        start_sta = 1 + number % 2
        for i, offset in enumerate(range(0, 1500, 125)):
            sta = 1 + (start_sta - 1 + i % 2) % 2 if experiment.aggregation == "interleaved" else start_sta
            packets.append((first + offset, "DL", sta, "BE", 1500))
        packets.extend(((first + 50, "DL", start_sta, "VO", 500), (first + 400, "DL", 3 - start_sta, "VI", 1000),
                        (first + 795, "DL", start_sta, "VI", 1000)))
    return sorted(packets)


def settings(experiment, seed, treatment, fixture, band=None):
    case = experiment.case
    cfg = properties(case, seed, E1, fixture)
    if treatment.startswith(HYST + "-"):
        band = tuple(map(int, treatment.split("-")[1:]))
        policy = HYST
    elif treatment == FIXED_SCAN:
        policy = FIXED
    elif treatment == E3_NO_SCAN or treatment in FEATURES:
        policy = E3
    else:
        policy = treatment
    cfg["wifi.ap.control.policy"] = policy
    if policy == HYST:
        if band is None:
            raise ValueError("A frozen threshold band is required")
        cfg.update(dict(zip(("wifi.ap.control.hysteresis.lower.mbps", "wifi.ap.control.hysteresis.upper.mbps"),
                            map(str, band))))
    if treatment in FEATURES:
        cfg[f"wifi.ap.control.{FEATURES[treatment]}.enabled"] = "false"
    if treatment == E3_NO_SCAN or policy in (LCM, HCM, FIXED, HYST) and treatment != FIXED_SCAN:
        cfg.pop("wifi.channel.scan.power.w", None)
        cfg.pop("wifi.channel.scan.schedule", None)
    if experiment.deadline == "relaxed":
        cfg.update({"wifi.ap.qos.VO.deadline.us": "1800", "wifi.ap.qos.VI.deadline.us": "3600"})
    return cfg
