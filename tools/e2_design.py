"""Frozen experiment design for the E2 thesis study."""

from __future__ import annotations

from dataclasses import dataclass, replace
from random import Random

from d5_experiments import PAPERS, SIZES, WEIGHTS
from pon_experiment_io import require
from verify_d2_energy_decision import base, set_associations
from verify_d3_qos_deferral import DEADLINES


LCM, HCM, FIXED, ECQ = "always-lcm", "always-hcm", "fixed-threshold", "ecq-sdps-e1"
MAIN = (LCM, HCM, FIXED, ECQ)
FEATURES = ("channel", "qos", "deferral", "residency")
SEEDS = (11317, 14851, 18233, 22751, 26479, 30757, 34963, 38993, 43801, 47441)
PILOT_SEEDS = (5219, 7877)
GROUPS = ("01-load", "02-interference", "03-qos", "04-dynamics", "05-joint", "06-sensitivity")
RF_PERIOD_US = 4000
WINDOW_US = 500_000
DRAIN_GUARD_US = 80_000
BANDS = {"clear": (), "primary": (0,), "secondary": (3,), "wide": (0, 1, 2, 3)}
RF_DBM = {"weak": (-85, -87), "strong": (-74, -81)}  # AP sensor, STA receiver.


@dataclass(frozen=True)
class Case:
    group: str
    name: str
    per_ap_mbps: int
    window_us: int = WINDOW_US
    sfus: int = 1
    stas_per_sfu: int = 2
    uplink_share: float = 0
    traffic: str = "constant"
    traffic_key: str = ""
    pure_be: bool = False
    rf: str = "clear"
    intensity: str = "strong"
    duty: int = 0
    power: str = "B3"
    feedback_us: int = 100
    scan: str = "paid"
    variants: tuple[str, ...] = MAIN

    @property
    def associations(self):
        return tuple(i for i in range(1, self.sfus + 1) for _ in range(self.stas_per_sfu))

    @property
    def workload_key(self):
        return self.traffic_key or self.name

    def metadata(self):
        return dict(group=self.group, scenario=self.name, configured_load_mbps=self.per_ap_mbps * self.sfus,
                    configured_load_per_ap_mbps=self.per_ap_mbps, window_us=self.window_us, sfus=self.sfus,
                    stas_per_sfu=self.stas_per_sfu, uplink_share=self.uplink_share, traffic=self.traffic,
                    traffic_key=self.workload_key, ac_mix="BE" if self.pure_be else "VO/VI/BE/BK", rf=self.rf,
                    rf_intensity=self.intensity if self.rf != "clear" else "", duty_percent=self.duty,
                    power_profile=self.power, feedback_latency_us=self.feedback_us if self.rf != "clear" else None,
                    scan=self.scan if self.rf != "clear" else "off")


def study():
    load, radio, qos, dynamic, optical, extras = GROUPS
    loads = tuple(Case(load, f"load-{rate:03}", rate, stas_per_sfu=1, pure_be=True) for rate in (0, 12, 31, 50, 85, 130))
    rf_clear = Case(radio, "rf-clear", 62, traffic_key="rf-family")
    radios = (rf_clear, *(replace(rf_clear, name=f"rf-{band}-{power}-{duty}", rf=band, intensity=power, duty=duty)
                          for band in ("primary", "secondary", "wide") for power, duty in (("weak", 15), ("strong", 15), ("strong", 35))))
    qos_cases = tuple(Case(qos, f"mixed-{rate}", rate, stas_per_sfu=4) for rate in (45, 100))
    dynamics = tuple(Case(dynamic, f"dynamic-{name}", rate, window_us=800_000, traffic=name)
                     for name, rate in (("step", 84), ("periodic", 92), ("random", 92), ("near", 31)))
    joint = tuple(Case(optical, f"joint-{sfus}sfu", 55, sfus=sfus, uplink_share=.30) for sfus in (2, 4))
    mechanism = Case(extras, "mechanisms", 92, window_us=600_000, traffic="periodic", rf="primary", duty=15,
                     variants=(ECQ, *(f"no-{feature}" for feature in FEATURES)))
    paper = replace(loads[2], group=extras, name="power-paper-31", traffic_key=loads[2].workload_key, power="paper")
    observer_base = next(case for case in radios if case.name == "rf-secondary-strong-15")
    observation = tuple(replace(observer_base, group=extras, name=f"observation-{name}", variants=(FIXED, ECQ), **changes)
                        for name, changes in (("delayed", {"feedback_us": 1500}), ("no-scan", {"scan": "off"}),
                                              ("free-scan", {"scan": "costless"})))
    return (*loads, *radios, *qos_cases, *dynamics, *joint, mechanism, paper, *observation)


def slices(case, seed):
    """Offer fixed stages independent of all simulated controller decisions."""
    end = case.window_us
    if case.traffic == "constant":
        return [(0, end, case.per_ap_mbps)]
    if case.traffic == "step":
        # A length extension repeats the original 800 ms recipe, preserving the short run's input prefix.
        pattern = ((0, 160_000, 10), (160_000, 320_000, 84), (320_000, 530_000, 10),
                   (530_000, 660_000, 74), (660_000, 800_000, 0))
        return [(t + lo, min(t + hi, end), rate) for t in range(0, end, 800_000) for lo, hi, rate in pattern if t + lo < end]
    if case.traffic == "periodic":
        pattern = ((0, 15_000, 0), (15_000, 33_000, case.per_ap_mbps), (33_000, 80_000, 14))
        return [(t + lo, min(t + hi, end), rate) for t in range(0, end, 80_000) for lo, hi, rate in pattern if t + lo < end]
    if case.traffic == "near":
        return [(t, min(end, t + 25_000), 27 if i % 2 == 0 else 35) for i, t in enumerate(range(0, end, 25_000))]
    if case.traffic != "random":
        raise ValueError(f"Unknown arrival profile {case.traffic}")
    rng = Random(f"E2-burst-timing:{seed}")
    stages = []
    cursor = 0
    while cursor < end:
        burst = cursor + rng.randint(9000, 57_000)
        stages.append((cursor, min(end, burst), 9))
        if burst >= end:
            break
        cursor = burst + rng.randint(8000, 25_000)
        stages.append((burst, min(end, cursor), case.per_ap_mbps))
    return stages


def arrivals(case, seed):
    """Materialize the offered workload once per seed. Timestamps are in integer microseconds."""
    directions = (("DL", 1 - case.uplink_share), ("UL", case.uplink_share))
    ac_profile = (("BE", 1500, 1),) if case.pure_be else tuple(zip(DEADLINES, SIZES, WEIGHTS))
    packets = []
    for index, (lo, hi, rate) in enumerate(slices(case, seed)):
        for direction, fraction in directions:
            devices = case.sfus if direction == "DL" else len(case.associations)
            for device in range(1, devices + 1):
                for ac, size, share in ac_profile:
                    per_device = rate * case.sfus * fraction * share / devices
                    if per_device == 0:
                        continue
                    rng = Random(f"E2-data:{case.workload_key}:{seed}:{index}:{direction}:{device}:{ac}")
                    now = lo + rng.expovariate(per_device / (size * 8))
                    while now < hi:
                        first = (device - 1) * case.stas_per_sfu + 1
                        sta = rng.randrange(first, first + case.stas_per_sfu) if direction == "DL" else device
                        packets.append((int(now), direction, sta, ac, size))
                        now += rng.expovariate(per_device / (size * 8))
    return sorted(packets)


def add_optics(settings, case):
    settings.update({"fiber.downlink.mode": "pon-shared", "fiber.downlink.rate.mbps": "10000", "fiber.downlink.propagation.us": "10",
                     "fiber.uplink.mode": "pon-framed", "fiber.uplink.rate.mbps": "2500", "fiber.uplink.propagation.us": "10.025",
                     "fiber.uplink.frame.period.us": "125", "fiber.uplink.report.enabled": "true", "fiber.uplink.bwmap.enabled": "true",
                     "fiber.uplink.demand.ledger.enabled": "true", "fiber.uplink.poll.enabled": "true", "fiber.uplink.poll.bytes": "64",
                     "fiber.uplink.poll.interval.frames": "1", "fiber.uplink.poll.end.us": str(case.window_us + DRAIN_GUARD_US),
                     "fiber.uplink.dba.mode": "giant-complete-recovery", "fiber.uplink.tcont.enabled": "true"})
    ids = []
    for sfu in range(1, case.sfus + 1):
        profiles = ({"si.fixed.frames": 1, "ab.fixed.bytes": 1200}, {"si.max.frames": 1, "ab.min.bytes": 2400},
                    {"si.max.frames": 2, "ab.min.bytes": 2400, "si.min.frames": 1, "ab.sur.bytes": 2400},
                    {"si.max.frames": 1, "ab.min.bytes": 0, "si.min.frames": 1, "ab.sur.bytes": 12000})
        for kind, (ac, profile) in enumerate(zip(DEADLINES, profiles), 1):
            allocation = sfu * 100 + kind
            ids.append(str(allocation))
            prefix = f"fiber.uplink.tcont.alloc.{allocation}."
            settings.update({prefix + key: str(value) for key, value in ({"sfu.id": sfu, "classes": ac, "type": kind} | profile).items()})
    settings["fiber.uplink.tcont.alloc.ids"] = ",".join(ids)


def add_radio(settings, case, seed):
    if case.rf == "clear":
        return
    stop = case.window_us
    ticks = range(5000, stop, 5000)
    ap_dbm, sta_dbm = RF_DBM[case.intensity]
    width = RF_PERIOD_US * case.duty // 100
    phases = [Random(f"E2-cca:{seed}:{sfu}").randrange(RF_PERIOD_US) for sfu in range(1, case.sfus + 1)]
    pulses = ((sfu, sub, max(0, start), min(stop, start + width), ap_dbm, sta_dbm)
              for sfu, phase in enumerate(phases, 1) for sub in BANDS[case.rf]
              for start in range(phase - RF_PERIOD_US, stop, RF_PERIOD_US) if start + width > 0)
    settings.update({
        "wifi.channel.observation.enabled": "true", "wifi.channel.passive.enabled": "true",
        "wifi.phy.per.enabled": "true", "wifi.phy.per.seed": str(seed), "wifi.phy.per.source": "SYNTHETIC_E2_RESEARCH",
        "wifi.phy.per.curves": "7,-12,1;7,10,0.5;7,30,0", "wifi.phy.per.frequency.model": "subchannel-blocks",
        "wifi.phy.per.reference.mpdu.bytes": "1530", "wifi.channel.interference.enabled": "true",
        "wifi.channel.interference.cca.threshold.dbm": "-82",
        "wifi.channel.interference.timeline": ";".join(",".join(map(str, p)) for p in pulses),
        "wifi.channel.truth": ";".join(f"{sfu},{sta},{sub},0,30,-1,false,-95"
                                       for sta, sfu in enumerate(case.associations, 1) for sub in range(4)),
        "wifi.channel.feedback.airtime.us": "0",
        "wifi.channel.feedback": ";".join(f"{sfu},{sta},{sub},{tick - 250},{case.feedback_us},6000"
                                          for tick in ticks for sta, sfu in enumerate(case.associations, 1) for sub in range(4)
                                          if tick - 250 + case.feedback_us < stop)})
    if case.scan != "off":
        settings["wifi.channel.scan.power.w"] = "0" if case.scan == "costless" else "0.5"
        settings["wifi.channel.scan.schedule"] = ";".join(f"{sfu},{sub},{tick - 1900 + (sub - 1) * 430},400,6000"
                                                        for tick in ticks for sfu in range(1, case.sfus + 1) for sub in (1, 2, 3))


def properties(case, seed, variant, packets):
    require(variant in case.variants, f"Unknown policy {variant} for {case.name}")
    settings = base(seed, ECQ if variant.startswith("no-") else variant, case.window_us)
    set_associations(settings, case.associations)
    settings.update({"wifi.ap.control.period.us": "5000", "wifi.ap.control.e1.cca.window.us": "5000",
                     "wifi.ap.control.threshold.mbps": "31", "wifi.ap.control.service.model": "load-aware"})
    settings.update({f"wifi.ap.qos.{ac}.deadline.us": str(deadline) for ac, deadline in DEADLINES.items()})
    add_optics(settings, case)
    add_radio(settings, case, seed)
    if case.power == "paper":
        settings.update({f"wifi.ap.energy.{mode}.{state}.w": str(watts)
                         for mode, values in PAPERS.items() for state, watts in zip(("idle", "tx", "rx"), values)})
    if variant.startswith("no-"):
        settings[f"wifi.ap.control.complete.{variant[3:]}.enabled"] = "false"
    if not packets:
        del settings["packet.bytes"]
        settings.update({"traffic.mode": "poisson", "traffic.duration.us": str(case.window_us), "traffic.sfu.1.rate.pps": "0"})
        return settings
    settings["traffic.direction"] = "BOTH" if case.uplink_share else "DL"
    settings["packet.arrival.us"] = ",".join(str(p[0]) for p in packets)
    for packet_id, (_, direction, sta, ac, size) in enumerate(packets, 1):
        prefix = f"packet.{packet_id}."
        if case.uplink_share:
            settings[prefix + "direction"] = direction
        if sta != 1:
            settings[prefix + "sta.id"] = str(sta)
        if ac != "BE":
            settings[prefix + "type"] = ac
        if size != 1500:
            settings[prefix + "bytes"] = str(size)
    return settings
