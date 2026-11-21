"""Matched overload and targeted QoS fixtures for the complete GIANT policy."""

from __future__ import annotations

from dataclasses import dataclass


PACKET_BYTES = 1_200
RATE_MBPS = 2_500
FRAME_NS = 125_000
OVERLOAD_RATES = {"VO": 280, "VI": 140, "BE": 1_800, "BK": 800}
SEEDS = (101, 202, 303)
COMPLETE = "giant-complete"
RR = "rr-multi"


@dataclass(frozen=True)
class Experiment:
    name: str
    policy: str
    load: str
    seed: int
    traffic_end_us: int
    decision_end_us: int
    config: dict
    offered_mbps: int | None = None
    rejected: bool = False

    def properties(self) -> str:
        return (f"# Stage 18 experiment: {self.name}. Main observation [0,{self.traffic_end_us}) us.\n"
                + "".join(f"{key}={value}\n" for key, value in self.config.items()))


def _config(policy: str, sfu_count: int, decision_end_us: int, upstream_us: str = "10.025") -> dict:
    values = {
        "traffic.direction": "UL", "sfu.count": sfu_count, "sta.count": sfu_count,
        "wifi.model": "fixed", "wifi.delay.us": 0, "wifi.mac.access": "fixed-priority",
        "fiber.downlink.mode": "pon-shared", "fiber.downlink.rate.mbps": 10_000,
        "fiber.downlink.propagation.us": "10", "fiber.uplink.mode": "pon-framed",
        "fiber.uplink.rate.mbps": RATE_MBPS, "fiber.uplink.propagation.us": upstream_us,
        "fiber.uplink.frame.period.us": FRAME_NS // 1000, "fiber.uplink.report.enabled": "true",
        "fiber.uplink.bwmap.enabled": "true", "fiber.uplink.demand.ledger.enabled": "true",
        "fiber.uplink.poll.enabled": "true", "fiber.uplink.poll.bytes": 64,
        "fiber.uplink.poll.interval.frames": 1, "fiber.uplink.poll.end.us": decision_end_us,
        "fiber.uplink.dba.mode": policy, "fiber.uplink.tcont.enabled": "true",
    }
    for sfu in range(1, sfu_count + 1):
        values[f"sta.{sfu}.sfu.id"] = sfu
    return values


def _profiles(base: dict, profiles: list[tuple]) -> None:
    keys = ("sfu.id", "type", "classes", "si.max.frames", "ab.min.bytes", "si.min.frames", "ab.sur.bytes",
            "si.fixed.frames", "ab.fixed.bytes", "si.be.frames", "ab.be.bytes")
    base["fiber.uplink.tcont.alloc.ids"] = ",".join(str(profile[0]) for profile in profiles)
    for alloc, *params in profiles:
        for key, value in zip(keys, params):
            base[f"fiber.uplink.tcont.alloc.{alloc}.{key}"] = value


def _poisson(policy: str, seed: int, *, idle_high: bool = False) -> Experiment:
    start = _config(policy, 2, 12_500)
    start.update({"traffic.mode": "poisson", "traffic.duration.us": 10_000})
    rates = {**OVERLOAD_RATES, "VO": 0, "VI": 0} if idle_high else OVERLOAD_RATES
    profiles = []
    for sfu in (1, 2):
        start[f"traffic.sta.{sfu}.seed"] = seed * 1000 + sfu
        for category, rate in rates.items():
            prefix = f"traffic.sta.{sfu}.class.{category}"
            start[prefix + ".rate.mbps"] = rate // 2
            start[prefix + ".packet.bytes"] = PACKET_BYTES
        profiles.extend(((sfu * 100 + 1, sfu, 2, "VO", 1, 2_400, 0, 0),
                         (sfu * 100 + 2, sfu, 3, "VI", 1, 1_200, 1, 1_200),
                         (sfu * 100 + 3, sfu, 4, "BE,BK", 1, 0, 1, 38_400)))
    _profiles(start, profiles)
    load = "idle-high" if idle_high else "overload-3020"
    return Experiment(f"{load}-{seed}-{policy}", policy, load, seed, 10_000, 12_500, start, sum(rates.values()))


def _burst() -> Experiment:
    start = _config(COMPLETE, 2, 4_000, upstream_us="320")
    start.update({"traffic.mode": "manual", "packet.bytes": PACKET_BYTES})
    _profiles(start, [(101, 1, 1, "VO", 0, 0, 0, 0, 1, 1_200),
                      (102, 1, 5, "VI", 2, 2_400, 3, 2_400, 3, 1_200, 4, 1_200),
                      (103, 1, 4, "BE,BK", 1, 0, 1, 9_600),
                      (201, 2, 2, "VO,VI", 2, 2_400, 0, 0),
                      (203, 2, 4, "BE,BK", 1, 0, 1, 9_600)])
    # Type 1 must reserve its first opportunity without a report.  The later 1800-byte
    # head cannot be fragmented into its 1200-byte grant, so this also observes real unused capacity.
    packets = [(1, PACKET_BYTES, 0, "VO"), (1, PACKET_BYTES, 125, "VO"),
               (1, 1800, 1800, "VO"), (1, 600, 1860, "VO")]
    for arrival in (0, 1_300, 2_600):
        packets.extend([(1, PACKET_BYTES, arrival, "VI")] * 20)
        packets.extend([(2, PACKET_BYTES, arrival, "VI")] * 8)
        packets.extend([(1, PACKET_BYTES, arrival, "BK")] * 30)
        packets.extend([(2, PACKET_BYTES, arrival, "BE")] * 30)
    packets.sort(key=lambda p: p[2])
    start["packet.arrival.us"] = ",".join(str(p[2]) for p in packets)
    for i, (sta, size, _, category) in enumerate(packets, 1):
        start[f"packet.{i}.sta.id"] = sta
        start[f"packet.{i}.bytes"] = size
        start[f"packet.{i}.type"] = category
    return Experiment("mixed-burst-delay", COMPLETE, "mixed-burst-delay", 0, 3_500, 4_000, start)


def experiments() -> list[Experiment]:
    runs = [_poisson(policy, seed) for seed in SEEDS for policy in (COMPLETE, RR)]
    invalid = {**runs[0].config, "fiber.uplink.tcont.alloc.101.ab.min.bytes": 37_200}
    runs.extend((_poisson(COMPLETE, SEEDS[0], idle_high=True), _burst(),
                 Experiment("infeasible-sla", COMPLETE, "infeasible", SEEDS[0],
                            10_000, 12_500, invalid, 3020, rejected=True)))
    return runs
