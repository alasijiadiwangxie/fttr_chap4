"""Replay short CA-SDPS baseline cases and optionally compare the power branch."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from pon_execution_trace import parse as parse_pon, read_execution
from pon_experiment_io import ROOT, require, simulate
from verify_c1_ap_observation import time_ns, write_properties
from verify_c2b1_sinr_per import parse_trace
from verify_c_phase import endpoint_state, events, packet_inputs
from verify_d1_mode_control import JAR, audit_energy, audit_switches, configuration


POLICIES = ("always-lcm", "always-hcm", "fixed-threshold", "hysteresis")
STAGE_US = 30_000
WINDOW_US = 3 * STAGE_US


def replay(jar, settings, path):
    write_properties(path, settings)
    log = simulate(jar, path)
    path.with_suffix(".log").write_text(log, encoding="utf-8")
    rows = parse_trace(log)
    packets = packet_inputs(rows, settings)
    delivered, dropped, order = endpoint_state(path.stem, rows, packets)
    require(not dropped, f"{path.stem}: unexpected drops {dropped}")
    switches = audit_switches(path.stem, rows, settings, int(settings["wifi.ap.activity.window.us"]) * 1000)
    return log, rows, packets, [(packet, delivered[packet]) for packet in order], switches


def verify_baselines(jar, reference, output):
    # 上下跳变各保留六个完整决策窗口，让 EWMA 有时间越过阈值。
    arrivals = [time for stage, interval in enumerate((1200, 240, 1200))
                for time in range(stage * STAGE_US, (stage + 1) * STAGE_US, interval)]
    inputs = None
    for policy in POLICIES:
        settings = configuration(101, policy, WINDOW_US)
        if policy != "always-hcm":
            settings["sfu.1.wifi.ap.mode"] = "LCM"
        settings["packet.arrival.us"] = ",".join(map(str, arrivals))
        if policy == "hysteresis":
            # power 上的基线要求显式门限。新分支的默认值也用同一条轨迹核对。
            settings["wifi.ap.control.hysteresis.lower.mbps"] = "21"
            settings["wifi.ap.control.hysteresis.upper.mbps"] = "33"
        _, rows, packets, deliveries, switches = replay(jar, settings, output / f"{policy}.properties")
        if inputs is None:
            inputs = packets
        require(inputs == packets and len(deliveries) == len(arrivals), f"{policy}: input or delivery mismatch")
        initial = events(rows, "WIFI_AP_MODE_INITIAL")[0]["current_mode"]
        changes = switches["1"]
        expected_modes = [] if policy.startswith("always-") else ["HCM", "LCM"]
        require([change["target"] for change in changes] == expected_modes, f"{policy}: wrong switch sequence")
        energy = audit_energy(rows, initial, WINDOW_US * 1000, "1", changes)["total_energy_uj"]
        if reference is not None:
            _, base_rows, base_packets, base_deliveries, base_switches = replay(
                reference, settings, output / f"reference-{policy}.properties"
            )
            base_initial = events(base_rows, "WIFI_AP_MODE_INITIAL")[0]["current_mode"]
            base_energy = audit_energy(base_rows, base_initial, WINDOW_US * 1000,
                                       "1", base_switches["1"])["total_energy_uj"]
            require((inputs, deliveries, changes, energy) == (base_packets, base_deliveries, base_switches["1"], base_energy),
                    f"{policy}: packet, switch or energy trace differs from power")
        print(f"{policy}: delivered={len(deliveries)}, switches={len(changes)}, energy_uj={energy}")


def verify_joint(jar, output):
    base = ROOT / "configs/pon-final-dba.properties"
    settings = dict(line.split("=", 1) for line in base.read_text(encoding="utf-8").splitlines()
                    if line and not line.startswith("#"))
    del settings["wifi.delay.us"]
    wireless = configuration(101, "always-hcm", 10_000)
    settings.update({key: value for key, value in wireless.items()
                     if key.startswith(("wifi.model", "wifi.mac.", "wifi.ap."))})
    settings.update({
        "fiber.uplink.dba.mode": "giant-complete-recovery",
        "wifi.mac.edca.BE.cwmin": "0",
        "wifi.channel.observation.enabled": "true",
        "wifi.channel.truth": ";".join(f"{settings[f'sta.{sta}.sfu.id']},{sta},{band},0,30,-1,false,-95"
                                       for sta in range(1, 5) for band in range(4)),
        "wifi.phy.per.enabled": "true",
        "wifi.phy.per.seed": "101",
        "wifi.phy.per.source": "SYNTHETIC_SMOKE_TEST",
        "wifi.phy.per.curves": "7,-12,1;7,10,0.5;7,30,0",
        "wifi.phy.per.frequency.model": "subchannel-blocks",
        "wifi.channel.interference.enabled": "true",
        # 低于 AP 的 CCA 门限，但 STA 处足够强，首轮 DATA 可发出却无法解码。
        "wifi.channel.interference.timeline": "1,3,0,145,-70,-70;2,2,100,260,-70,-70;1,3,1700,1780,-90,-40",
    })
    settings["packet.arrival.us"] += ",150,150,150,150,1700,1700"
    for packet in range(13, 19):
        settings.update({f"packet.{packet}.direction": "DL", f"packet.{packet}.sta.id": "2",
                         f"packet.{packet}.type": "BE"})
    log, rows, packets, deliveries, switches = replay(jar, settings, output / "joint.properties")
    directions = Counter(packets[packet][3] for packet, _ in deliveries)
    require(directions == Counter(DL=8, UL=10), f"joint: unexpected DL/UL deliveries {directions}")
    require(any(row["busy"] == "true" for row in events(rows, "WIFI_EXTERNAL_CCA")), "joint: no secondary CCA busy")
    require(events(rows, "WIFI_AMPDU_DATA_START") and events(rows, "WIFI_MAC_RETRY"), "joint: no aggregate retry")
    require(any(row.get("packet_id") in {"17", "18"} for row in events(rows, "WIFI_MAC_RETRY")),
            "joint: expected retried MPDU in STA interference pulse")
    grants = read_execution("ca-joint", settings, parse_pon(log))
    require(grants, "joint: no real PON DATA grants")
    energy = sum(audit_energy(rows, "HCM", 10_000_000, sfu, switches[sfu])["total_energy_uj"]
                 for sfu in ("1", "2"))
    print(f"joint: DL={directions['DL']}, UL={directions['UL']}, data_grants={len(grants)}, energy_uj={energy}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--reference-jar", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "target/ca-sdps-base")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    verify_baselines(args.jar, args.reference_jar, args.output)
    verify_joint(args.jar, args.output)


if __name__ == "__main__":
    main()
