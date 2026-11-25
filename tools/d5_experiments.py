"""Paired D5 inputs. Rates define independent Poisson streams within each exogenous stage."""

from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from itertools import product
from random import Random

from pon_experiment_io import ROOT
from verify_d2_energy_decision import base, radio_config, set_associations
from verify_d3_qos_deferral import DEADLINES, scenario_config

FULL, D4, FIXED = "ecq-sdps-complete", "ecq-sdps-d4", "fixed-threshold"
QUANTUM_LIMIT = "joint-quantum-limit"
BASELINES = ("always-lcm", "always-hcm", FIXED, FULL)
FEATURES = ("channel", "qos", "deferral", "residency")
SEEDS, CALIBRATION_SEEDS = (1201, 2411), (407, 509)
THRESHOLDS = (20, 31, 45)
SIZES = (500, 1000, 1500, 1200)
WEIGHTS = (.07, .12, .74, .07)
PAPERS = {"lcm": (6.96, 12.96, 7.92), "hcm": (7.92, 15.0, 12.0)}


@dataclass(frozen=True)
class Scenario:
    name: str
    window_us: int
    stages: tuple[tuple[int, int, float], ...]
    associations: tuple[int, ...] = (1,)
    bands: tuple[int, ...] = ()
    interference_us: tuple[int, int] = (0, 0)
    scans: str = "ideal-clear"
    power: str = "B3"
    uplink_share: float = 0
    optical: str = "fixed"
    repeat: int = 1

    def definition(self):
        return asdict(self)


STEP = Scenario("step-recovery", 1_000_000,
                ((0, 200_000, 12), (200_000, 400_000, 84), (400_000, 600_000, 8), (600_000, 780_000, 74), (780_000, 1_000_000, 0)),
                bands=(3,), interference_us=(220_000, 570_000), scans="paid")
PERIODIC = Scenario("periodic", 300_000, tuple(stage for t in range(0, 300_000, 60_000)
                    for stage in ((t, t + 8_000, 0), (t + 8_000, t + 22_000, 80), (t + 22_000, t + 60_000, 13))), (1, 1))
RADIO = Scenario("rf-clear", 200_000, ((0, 32_000, 29), (32_000, 70_000, 127), (70_000, 117_000, 45),
                 (117_000, 143_000, 127), (143_000, 171_000, 45), (171_000, 200_000, 7)), (1, 1, 2, 2),
                 interference_us=(25_000, 162_000))
JOINT = Scenario("joint-fixed", 250_000, ((0, 60_000, 54), (60_000, 140_000, 105), (140_000, 210_000, 39),
                  (210_000, 250_000, 7)), (1, 1, 2, 2), uplink_share=.25)
TRAIN = Scenario("calibration", 300_000, ((0, 60_000, 13), (60_000, 120_000, 41), (120_000, 180_000, 25),
                 (180_000, 240_000, 62), (240_000, 300_000, 12)))
RATE = tuple(Scenario(f"rate-{tag}", 250_000, ((0, 250_000, rate),))
             for tag, rate in (("low", 12), ("mid", 35), ("high", 85), ("overload", 270)))
RF = (RADIO, *(replace(RADIO, name=f"rf-{label}", bands=band, scans="paid") for label, band in
               (("primary", (0,)), ("secondary", (3,)), ("wide", (0, 1, 2, 3)))))
MATRIX = (*RATE, *RF, STEP, PERIODIC, replace(RATE[1], name="rate-mid-paper", power="paper"), JOINT,
          replace(JOINT, name="joint-giant", optical="giant-complete"))
FREE = replace(RF[2], name="rf-secondary-free", scans="costless")
LONG = replace(STEP, name="step-recovery-long", repeat=2)


def staged_packets(case, seed):
    count = max(case.associations)
    result = []
    for cycle, (stage, (start, end, rate)) in product(range(case.repeat), enumerate(case.stages)):
        offset = cycle * case.window_us
        for direction, share in (("DL", 1 - case.uplink_share), ("UL", case.uplink_share)):
            devices = range(1, (count if direction == "DL" else len(case.associations)) + 1)
            for device, (index, (ac, size, ac_share)) in product(devices, enumerate(zip(DEADLINES, SIZES, WEIGHTS))):
                mbps = rate * share * ac_share / len(devices)
                if mbps == 0:
                    continue
                rng = Random(f"D5:{seed}:{cycle}:{stage}:{direction}:{device}:{index}")
                destinations = [i for i, sfu in enumerate(case.associations, 1) if sfu == device] if direction == "DL" else [device]
                time = start + rng.expovariate(mbps / (8 * size))
                while time < end:
                    result.append((offset + int(time), direction, rng.choice(destinations), ac, size))
                    time += rng.expovariate(mbps / (8 * size))
    return sorted(result)


def pon_settings(settings, cutoff, original_quantum):
    source = ROOT / "configs/pon-final-dba.properties"
    optical = dict(line.split("=", 1) for line in source.read_text(encoding="utf-8").splitlines() if line.startswith("fiber."))
    id_201 = "fiber.uplink.tcont.alloc.201."
    # 两台 SFU 都采用同一 VO→T1、VI→T2、BE→T3、BK→T4 映射。
    optical = {key: value for key, value in optical.items() if not key.startswith(id_201)}
    optical.update({id_201 + "sfu.id": "2", id_201 + "type": "1", id_201 + "classes": "VO",
                    id_201 + "si.fixed.frames": "1", id_201 + "ab.fixed.bytes": "1200",
                    "fiber.uplink.poll.end.us": str(cutoff + 60_000)})
    if not original_quantum:
        for allocation in (103, 203):
            prefix = f"fiber.uplink.tcont.alloc.{allocation}."
            optical[prefix + "ab.min.bytes"] = "2400"
            optical[prefix + "ab.sur.bytes"] = "2400"
    settings.update(optical)


def configuration(case, seed, policy, threshold, disabled=""):
    cutoff = case.window_us * case.repeat
    settings = base(seed, policy, cutoff)
    set_associations(settings, case.associations)
    settings["wifi.ap.control.service.model"] = "load-aware"
    settings["wifi.ap.control.threshold.mbps"] = str(threshold)
    settings.update({f"wifi.ap.qos.{ac}.deadline.us": str(limit) for ac, limit in DEADLINES.items()})
    packets = staged_packets(case, seed)
    settings["traffic.direction"] = "BOTH" if case.uplink_share else "DL"
    settings["packet.arrival.us"] = ",".join(str(t) for t, *_ in packets)
    for ordinal, (_, direction, sta, ac, size) in enumerate(packets, 1):
        prefix = f"packet.{ordinal}."
        if case.uplink_share:
            settings[prefix + "direction"] = direction
        if sta != 1:
            settings[prefix + "sta.id"] = str(sta)
        if ac != "BE":
            settings[prefix + "type"] = ac
        if size != 1500:
            settings[prefix + "bytes"] = str(size)
    if case.bands:
        radio_config(settings, case.associations, dict.fromkeys(set(case.associations), case.bands), *case.interference_us, pulses=True)
        if case.repeat > 1:
            first = settings["wifi.channel.interference.timeline"].split(";")
            timeline = list(first)
            for cycle, row in product(range(1, case.repeat), first):
                sfu, sub, start, stop, ap, sta = row.split(",")
                shift = cycle * case.window_us
                timeline.append(f"{sfu},{sub},{int(start)+shift},{int(stop)+shift},{ap},{sta}")
            settings["wifi.channel.interference.timeline"] = ";".join(timeline)
        if case.scans == "costless":
            settings["wifi.channel.scan.power.w"] = "0"
    if case.power == "paper":
        settings.update({f"wifi.ap.energy.{mode}.{state}.w": str(watts)
                         for mode, values in PAPERS.items() for state, watts in zip(("idle", "tx", "rx"), values)})
    if case.optical == "giant-complete":
        pon_settings(settings, cutoff, case.name == QUANTUM_LIMIT)
    if disabled:
        settings[f"wifi.ap.control.complete.{disabled}.enabled"] = "false"
    return settings


def calibration_jobs():
    for seed, threshold in product(CALIBRATION_SEEDS, THRESHOLDS):
        yield "calibration", TRAIN, seed, FIXED, "", threshold, configuration(TRAIN, seed, FIXED, threshold)


def test_jobs(threshold, phase):
    seeds = SEEDS[:1] if phase in ("pilot", "replay") else SEEDS
    base_cases = (STEP,) if phase == "pilot" else MATRIX
    for case, seed, policy in product(base_cases, seeds, BASELINES):
        yield "main", case, seed, policy, "", threshold, configuration(case, seed, policy, threshold)
    if phase == "pilot":
        yield "main", STEP, seeds[0], D4, "", threshold, configuration(STEP, seeds[0], D4, threshold)
        return
    for case, seed, policy in product((FREE, LONG), seeds, (FIXED, FULL)):
        yield "supplement", case, seed, policy, "", threshold, configuration(case, seed, policy, threshold)
    for case, seed in product((RF[2], STEP), seeds):
        yield "main", case, seed, D4, "", threshold, configuration(case, seed, D4, threshold)
        for feature in FEATURES:
            yield "ablation", case, seed, FULL, feature, threshold, configuration(case, seed, FULL, threshold, feature)
    for name, policy in product(("mixed-primary", "mixed-secondary"), (D4, FULL)):
        case = Scenario("d4-" + name, 120_000, (), (1, 1, 2, 2), scans="paid")
        yield "diagnosis", case, 101, policy, "", threshold, scenario_config(name, 101, policy)
    blocked = replace(JOINT, name=QUANTUM_LIMIT, optical="giant-complete")
    yield "diagnosis", blocked, SEEDS[0], FULL, "", threshold, configuration(blocked, SEEDS[0], FULL, threshold)
    yield from deferral_jobs(threshold, seeds)


def deferral_jobs(threshold, seeds):
    case = Scenario("defer-primary", 120_000, (), (1, 1, 2, 2), (0,), (16_000, 97_000), "paid")
    for seed, disabled in product(seeds, ("", "deferral")):
        settings = scenario_config("mixed-primary", seed, FULL)
        settings["wifi.ap.control.threshold.mbps"] = str(threshold)
        if disabled:
            settings["wifi.ap.control.complete.deferral.enabled"] = "false"
        yield "ablation" if disabled else "supplement", case, seed, FULL, disabled, threshold, settings
