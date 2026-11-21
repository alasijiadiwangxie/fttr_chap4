"""Matched Poisson workloads for the GIANT/RR SLA, FIFO-delay and loss tests."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from pon_giant_completion_workload import COMPLETE, PACKET_BYTES, RR, SEEDS, Experiment, _config, _profiles


TRAFFIC_END_US = 25_000
DECISION_END_US = 30_000
SFU_COUNT = 2
LOADS = (("light", 250), ("medium", 1250), ("near-full", 2300), ("overload", 3050))
ISOLATION_T4_MBPS = (300, 1800, 2700)
ISOLATION_PROTECTED_MBPS = {"VO": 100, "VI": 125, "BE": 225}
SWEEP_PERCENTAGES = {"VO": 8, "VI": 10, "BE": 18, "BK": 64}


@dataclass(frozen=True)
class SpecialExperiment(Experiment):
    family: str = "load"
    axis_mbps: int = 0

    def properties(self) -> str:
        return (f"# DBA QoS comparison: {self.name}. Main observation [0,{self.traffic_end_us}) us.\n"
                + "".join(f"{key}={value}\n" for key, value in self.config.items()))


def sweep_kind(case: SpecialExperiment) -> str:
    return case.family


def axis_mbps(case: SpecialExperiment) -> int:
    return case.axis_mbps


def _case(family: str, label: str, x: int, rates: dict[str, int], policy: str,
          seed: int) -> SpecialExperiment:
    values = _config(policy, SFU_COUNT, DECISION_END_US)
    values.update({"traffic.mode": "poisson", "traffic.duration.us": TRAFFIC_END_US})
    profiles = []
    for sfu in range(1, SFU_COUNT + 1):
        values[f"traffic.sta.{sfu}.seed"] = seed * 1000 + sfu
        for category, total_mbps in rates.items():
            prefix = f"traffic.sta.{sfu}.class.{category}"
            values[f"{prefix}.rate.mbps"] = format(Decimal(total_mbps) / SFU_COUNT, "f")
            values[f"{prefix}.packet.bytes"] = PACKET_BYTES
        # Keep every class in its own Alloc-ID, identically provisioned on both SFUs.
        base = sfu * 100
        profiles.extend([
            (base + 1, sfu, 1, "VO", 0, 0, 0, 0, 1, 2400),
            (base + 2, sfu, 2, "VI", 1, 2400, 0, 0),
            (base + 3, sfu, 3, "BE", 1, 2400, 1, 2400),
            (base + 4, sfu, 4, "BK", 1, 0, 1, 38_400),
        ])
    _profiles(values, profiles)
    load = f"{label}-{x}"
    return SpecialExperiment(name=f"{load}-{seed}-{policy}", policy=policy, load=load, seed=seed,
                             traffic_end_us=TRAFFIC_END_US, decision_end_us=DECISION_END_US,
                             config=values, offered_mbps=sum(rates.values()), family=family, axis_mbps=x)


def experiments() -> list[SpecialExperiment]:
    cases = []
    for label, total_mbps in LOADS:
        # All sweep points yield integral aggregate Mb/s for each class.
        rates = {category: total_mbps * percent // 100 for category, percent in SWEEP_PERCENTAGES.items()}
        if sum(rates.values()) != total_mbps:
            raise ValueError(f"Load {total_mbps} Mb/s does not split exactly into the chosen proportions")
        cases.extend(_case("load", label, total_mbps, rates, policy, seed)
                     for seed in SEEDS for policy in (COMPLETE, RR))
    for t4_mbps in ISOLATION_T4_MBPS:
        rates = {**ISOLATION_PROTECTED_MBPS, "BK": t4_mbps}
        cases.extend(_case("isolation", "isolation", t4_mbps, rates, policy, seed)
                     for seed in SEEDS for policy in (COMPLETE, RR))
    return cases
