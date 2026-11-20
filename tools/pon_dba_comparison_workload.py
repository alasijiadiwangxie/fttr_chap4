"""Matched Poisson inputs and observation windows for the DBA comparison."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal


TRAFFIC_END_US = 15_000
DECISION_END_US = 18_000
OBSERVATION_END_US = TRAFFIC_END_US
FRAME_PERIOD_US = 125
PACKET_BYTES = 1_200
UPLINK_RATE_MBPS = 2_500
SEEDS = (101, 202, 303)
LOADS_MBPS = {"low": 200, "mid": 700, "high": 1_400}
AC_SHARES = {"VO": Decimal("0.20"), "VI": Decimal("0.35"),
             "BE": Decimal("0.30"), "BK": Decimal("0.15")}
STA_SFU = {1: 1, 2: 2}


@dataclass(frozen=True)
class Scenario:
    name: str
    policy: str
    load: str
    variant: str
    seed: int
    offered_mbps: int
    settings: str
    traffic_end_ns: int
    decision_end_ns: int
    observation_end_ns: int


def _scenario(policy: str, load: str, seed: int, variant: str = "baseline") -> Scenario:
    offered_mbps = LOADS_MBPS[load]
    settings = {
        "traffic.mode": "poisson",
        "traffic.direction": "UL",
        "traffic.duration.us": TRAFFIC_END_US,
        "sfu.count": len(STA_SFU),
        "sta.count": len(STA_SFU),
        # A fixed zero-time radio isolates the upstream DBA bottleneck.
        "wifi.model": "fixed",
        "wifi.delay.us": 0,
        "wifi.mac.access": "fixed-priority",
        "fiber.downlink.mode": "pon-shared",
        "fiber.downlink.rate.mbps": 10_000,
        "fiber.downlink.propagation.us": "10",
        "fiber.uplink.mode": "pon-framed",
        "fiber.uplink.rate.mbps": UPLINK_RATE_MBPS,
        "fiber.uplink.propagation.us": "10.025",
        "fiber.uplink.frame.period.us": FRAME_PERIOD_US,
        "fiber.uplink.report.enabled": "true",
        "fiber.uplink.bwmap.enabled": "true",
        "fiber.uplink.demand.ledger.enabled": "true",
        "fiber.uplink.poll.enabled": "true",
        "fiber.uplink.poll.bytes": 64,
        "fiber.uplink.poll.interval.frames": 1,
        "fiber.uplink.poll.end.us": DECISION_END_US,
        "fiber.uplink.dba.mode": policy,
        "fiber.uplink.tcont.enabled": "true",
    }
    for sta, sfu in STA_SFU.items():
        settings[f"sta.{sta}.sfu.id"] = sfu
        # A seed label is common across treatments; each STA's seed is distinct.
        # The Java generator derives independent substreams for the four ACs.
        settings[f"traffic.sta.{sta}.seed"] = seed * 1_000 + sta
        for ac, fraction in AC_SHARES.items():
            prefix = f"traffic.sta.{sta}.class.{ac}"
            settings[f"{prefix}.rate.mbps"] = offered_mbps * fraction / len(STA_SFU)
            settings[f"{prefix}.packet.bytes"] = PACKET_BYTES

    type4_si = 2 if variant == "type4_si2" else 4
    type4_ab = 7_200 if variant == "type4_ab7200" else 4_800
    ids = []
    for sfu in STA_SFU.values():
        # (local ID, type, ACs, SImax frames, SImin frames, ABmin B, ABsur B)
        profiles = (
            (1, 2, "VO", 2, 0, 4_800, 0),
            (2, 3, "VI", 2, 4, 2_400, 4_800),
            (3, 4, "BE,BK", 1, type4_si, 0, type4_ab),
        )
        for local, kind, classes, si_max, si_min, ab_min, ab_sur in profiles:
            alloc_id = sfu * 100 + local
            ids.append(str(alloc_id))
            prefix = f"fiber.uplink.tcont.alloc.{alloc_id}"
            settings.update({
                f"{prefix}.sfu.id": sfu,
                f"{prefix}.type": kind,
                f"{prefix}.classes": classes,
                f"{prefix}.si.max.frames": si_max,
                f"{prefix}.si.min.frames": si_min,
                f"{prefix}.ab.min.bytes": ab_min,
                f"{prefix}.ab.sur.bytes": ab_sur,
            })
    settings["fiber.uplink.tcont.alloc.ids"] = ",".join(ids)

    name = f"{load}-{seed}-{policy}" + ("" if variant == "baseline" else f"-{variant}")
    config = (
        f"# DBA comparison: {name}; offered aggregate {offered_mbps} Mb/s.\n"
        + f"# Common observation [0, {OBSERVATION_END_US} us); decisions at t < {DECISION_END_US} us.\n"
        + "".join(f"{key}={value}\n" for key, value in settings.items())
    )
    return Scenario(name, policy, load, variant, seed, offered_mbps, config,
                    TRAFFIC_END_US * 1_000, DECISION_END_US * 1_000, OBSERVATION_END_US * 1_000)


def scenarios() -> list[Scenario]:
    """Pair both policies for every load/seed and vary one Type 4 parameter at a time."""
    baseline = [_scenario(policy, load, seed)
                for load in LOADS_MBPS for seed in SEEDS for policy in ("rr-multi", "giant")]
    sensitivity = [_scenario("giant", "mid", seed, variant)
                   for seed in SEEDS for variant in ("type4_si2", "type4_ab7200")]
    return baseline + sensitivity
