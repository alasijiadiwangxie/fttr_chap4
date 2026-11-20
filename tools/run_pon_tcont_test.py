"""第15步：从实际Java仿真导出逐Alloc-ID轨迹，实测加载带宽参数并检查非法配置。"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from run_pon_frame_test import ROOT, require, write_csv
from run_pon_dba_shadow_test import execute
from run_pon_dba_execution_test import events, only, parse


TRACE_COLUMNS = (
    "scenario_id", "time_ns", "event", "allocation_id", "sfu_id", "packet_id", "grant_id",
    "frame_id", "target_frame_id", "poll_id", "packet_bytes", "ac", "snapshot_time_ns", "report_kind", "applied",
    "pending_packets", "pending_bytes", "eligible_packets", "eligible_bytes",
    "state", "known_demand_bytes", "effective_known_bytes", "budget_outstanding", "budget_bytes",
    "selected_packet_ids", "selected_bytes", "actual_bytes", "unused_bytes", "tx_start_ns", "tx_end_ns",
    "effective_before", "effective_after", "fill_reason",
)
PROFILE_COLUMNS = (
    "scenario_id", "allocation_id", "sfu_id", "type", "classes", "si_max_frames", "si_min_frames",
    "ab_min_bytes", "ab_sur_bytes", "expected_result", "actual_result", "validation_reason",
)
PERIOD_NS = 125000
RATE_MBPS = 2500


def configuration(sfus: int, packets: list[tuple[int, int, int, str]], mode: str = "rr-multi") -> str:
    """元组依次为源STA、字节、生成微秒和AC；一STA一SFU，仅光侧有多逻辑队列。"""
    settings = {
        "traffic.mode": "manual", "traffic.direction": "UL", "sfu.count": str(sfus), "sta.count": str(sfus),
        "wifi.model": "fixed", "wifi.delay.us": "0", "wifi.mac.access": "fixed-priority",
        "fiber.uplink.mode": "pon-framed", "fiber.uplink.rate.mbps": str(RATE_MBPS),
        "fiber.uplink.propagation.us": "10.025", "fiber.uplink.frame.period.us": "125",
        "fiber.uplink.report.enabled": "true", "fiber.uplink.bwmap.enabled": "true",
        "fiber.uplink.demand.ledger.enabled": "true", "fiber.uplink.poll.enabled": "true",
        "fiber.uplink.poll.bytes": "64", "fiber.uplink.poll.interval.frames": "1",
        "fiber.uplink.poll.end.us": "2500", "fiber.uplink.dba.mode": mode,
        "fiber.downlink.mode": "pon-shared", "fiber.downlink.rate.mbps": "10000",
        "fiber.downlink.propagation.us": "10", "packet.bytes": "1200",
        "packet.arrival.us": ",".join(str(packet[2]) for packet in packets),
        "fiber.uplink.tcont.enabled": "true",
        "fiber.uplink.tcont.alloc.ids": "101,102" + (",201" if sfus >= 2 else "") + (",301" if sfus >= 3 else ""),
    }
    profiles = [(101, 1, 2, "VO,VI", 1, 0, 2400, 0), (102, 1, 3, "BE,BK", 1, 2, 2400, 1200)]
    if sfus >= 2:
        profiles.append((201, 2, 4, "VO,VI,BE,BK", 1, 1, 0, 1200))
    if sfus >= 3:
        profiles.append((301, 3, 4, "VO,VI,BE,BK", 1, 1, 0, 1200))
    for sfu in range(1, sfus + 1):
        settings[f"sta.{sfu}.sfu.id"] = str(sfu)
    for alloc, sfu, kind, classes, si_max, si_min, ab_min, ab_sur in profiles:
        prefix = f"fiber.uplink.tcont.alloc.{alloc}."
        for name, value in zip(("sfu.id", "type", "classes", "si.max.frames", "si.min.frames", "ab.min.bytes", "ab.sur.bytes"),
                               (sfu, kind, classes, si_max, si_min, ab_min, ab_sur)):
            settings[prefix + name] = str(value)
    for index, (sta, packet_bytes, _, ac) in enumerate(packets, 1):
        settings[f"packet.{index}.sta.id"] = str(sta)
        settings[f"packet.{index}.bytes"] = str(packet_bytes)
        settings[f"packet.{index}.type"] = ac
    return "# Actual Java T-CONT experiment. Deterministic manual traffic; no random seed is used.\n" + "".join(
        f"{key}={value}\n" for key, value in settings.items()
    )


def scenarios() -> dict[str, str]:
    fifo = [(1, 1200, 0, "VO"), (1, 1200, 0, "BE"), (1, 600, 2, "VO"), (1, 800, 2, "BE")]
    same_frame = [(1, 38000, 0, "VO"), (1, 1200, 0, "VO"), (1, 1200, 0, "BE"), (1, 1200, 600, "BE")]
    return {
        "same_sfu_fifo": configuration(1, fifo),
        "same_sfu_same_frame": configuration(1, same_frame),
        "two_sfu_three_alloc": configuration(2, [(1, 1200, 0, "VO"), (1, 1200, 0, "BE"),
            (2, 1200, 0, "BK"), (1, 800, 800, "VI"), (2, 900, 800, "VO")]),
        "three_sfu_four_alloc": configuration(3, [(1, 1200, 0, "VO"), (1, 1200, 0, "BE"),
            (2, 1200, 0, "BK"), (3, 1200, 0, "VI")]),
        "same_sfu_head_blocked": configuration(1, [(1, 39062, 0, "VO"), (1, 1200, 0, "VO"),
            (1, 1200, 0, "BE")]) + "fiber.uplink.poll.end.us=1000\n",
        "rr_single_mode": configuration(1, fifo, "rr"),
        "bandwidth_metadata_changed": configuration(1, fifo)
            + "fiber.uplink.tcont.alloc.101.si.max.frames=8\n"
            + "fiber.uplink.tcont.alloc.101.ab.min.bytes=24\n"
            + "fiber.uplink.tcont.alloc.102.si.min.frames=7\n"
            + "fiber.uplink.tcont.alloc.102.ab.sur.bytes=12\n",
    }


def expected_owner(configuration_text: str) -> dict[str, str]:
    settings = dict(line.split("=", 1) for line in configuration_text.splitlines() if line and not line.startswith("#"))
    return {alloc: settings[f"fiber.uplink.tcont.alloc.{alloc}.sfu.id"]
            for alloc in settings["fiber.uplink.tcont.alloc.ids"].split(",")}


def check_windows(trace: list[dict]) -> None:
    windows = events(trace, "PON_DBA_BWMAP_TX") + events(trace, "PON_POLL_BWMAP_TX")
    previous_end = 0
    for row in sorted(windows, key=lambda item: int(item["tx_start_ns"])):
        start, end, frame = (int(row[field]) for field in ("tx_start_ns", "tx_end_ns", "target_frame_id"))
        require(start >= previous_end and frame * PERIOD_NS <= start < end <= (frame + 1) * PERIOD_NS,
                "Alloc-ID windows overlap or exceed the shared frame")
        previous_end = end


def check_and_export(name: str, settings: str, log: str) -> list[dict]:
    trace = parse(log)
    owners = expected_owner(settings)
    enqueue = events(trace, "TCONT_ENQUEUE")
    require(enqueue and len(enqueue) == len({row["packet_id"] for row in enqueue}), "Missing or repeated logical enqueue")
    queued = {row["packet_id"]: row for row in enqueue}
    packet_alloc = {packet_id: row["allocation_id"] for packet_id, row in queued.items()}
    require(set(packet_alloc.values()).issubset(owners), "A packet entered an unknown Alloc-ID")
    for row in trace:
        if row.get("allocation_id") in owners and "sfu_id" in row:
            require(row["sfu_id"] == owners[row["allocation_id"]], "Logical allocation crossed physical SFU ownership")
    delivered = events(trace, "DELIVERED")
    require(len(delivered) == len({row["packet_id"] for row in delivered}), "A user packet was delivered twice")
    if name == "same_sfu_head_blocked":
        require([row["packet_id"] for row in delivered] == ["3"], "Blocked queue affected the other allocation or bypassed FIFO")
    else:
        require(len(delivered) == len(enqueue), "Generated/optically queued/received DATA conservation failed")
    starts = events(trace, "PON_DBA_DATA_START")
    ends = events(trace, "PON_DBA_DATA_END")
    require([row["packet_id"] for row in starts] == [row["packet_id"] for row in ends], "DATA transmission did not finish")
    for alloc in owners:
        in_ids = [row["packet_id"] for row in enqueue if row["allocation_id"] == alloc]
        out_ids = [row["packet_id"] for row in starts if row["allocation_id"] == alloc]
        require(out_ids == in_ids[:len(out_ids)], "A logical FIFO skipped or reordered a packet")
    for start, end in zip(starts, ends):
        require(start["allocation_id"] == end["allocation_id"] == packet_alloc[start["packet_id"]], "DATA used wrong logical queue")
        packet_bytes = int(queued[start["packet_id"]]["packet_bytes"])
        require(int(end["time_ns"]) - int(start["time_ns"]) == (packet_bytes * 8000 + RATE_MBPS - 1) // RATE_MBPS,
                "Packet serialization changed when logical queues were added")
        destination = next(row for row in delivered if row["packet_id"] == start["packet_id"])
        require(destination["sfu_id"] == start["sfu_id"] and destination["_time_ns"] == int(end["time_ns"]) + 10025,
                "MFU arrival time or physical route changed")
    grants = events(trace, "PON_DBA_BWMAP_TX")
    require(len(grants) == len({row["grant_id"] for row in grants}), "Repeated budget identity")
    for budget in grants:
        group = [row for row in trace if row.get("grant_id") == budget["grant_id"]]
        filled, settled = only(group, "PON_DBA_FILL"), only(group, "PON_DBA_SETTLEMENT_RX")
        for phase in ("PON_DBA_LEDGER_CREATED", "PON_DBA_BWMAP_RX", "PON_DBA_SLOT_END"):
            only(group, phase)
        require(all(row.get("allocation_id") == budget["allocation_id"] for row in group if "allocation_id" in row),
                "Budget states from different Alloc-IDs became mixed")
        require(int(settled["actual_bytes"]) == int(filled["selected_bytes"])
                and int(settled["actual_bytes"]) + int(settled["unused_bytes"]) == int(budget["budget_bytes"]),
                "Actual/unused budget accounting failed")
        require(int(settled["time_ns"]) == int(budget["tx_end_ns"]) + 10025, "Settlement feedback bypassed propagation")
    check_windows(trace)
    if name == "same_sfu_same_frame":
        by_frame = {}
        for grant in grants:
            by_frame.setdefault(grant["target_frame_id"], set()).add(grant["allocation_id"])
        require(any(allocs == {"101", "102"} for allocs in by_frame.values()), "No same-frame two-allocation execution was observed")
    if name == "rr_single_mode":
        require(len(grants) == len({row["target_frame_id"] for row in grants}), "rr changed to multi-budget behavior")
    # 输出来自Java的原始事件；对DELIVERED仅用既有报文身份补充逻辑归属，不改时间或状态值。
    rows = []
    for row in trace:
        action = row.get("event", "")
        if not (action.startswith(("TCONT_", "PON_DBA_", "PON_POLL_")) or action == "DELIVERED"):
            continue
        copied = dict(row)
        copied["scenario_id"] = name
        copied["time_ns"] = row.get("time_ns", row.get("snapshot_time_ns") if action == "TCONT_REPORT" else row["_time_ns"])
        if row.get("packet_id") in queued:
            copied["packet_bytes"] = queued[row["packet_id"]]["packet_bytes"]
            copied["ac"] = queued[row["packet_id"]]["ac"]
        if action == "DELIVERED":
            copied["allocation_id"] = packet_alloc[row["packet_id"]]
        rows.append(copied)
    return rows


def probe_profiles(classpath: str, configs: list[Path]) -> str:
    result = subprocess.run(["java", "-cp", classpath, "edu.suda.fttr.simulator.TcontBandwidthTest", *map(str, configs)],
                            capture_output=True, encoding="utf-8", check=False)
    require(result.returncode == 0, f"Java config probe failed; first run test-compile: {result.stderr.strip()}")
    return result.stdout


def invalid_configs(base: str) -> dict[str, str]:
    invalid = {
        "invalid_duplicate_class": "fiber.uplink.tcont.alloc.102.classes=VO,BE,BK",
        "invalid_missing_class": "fiber.uplink.tcont.alloc.102.classes=BE",
        "invalid_owner": "fiber.uplink.tcont.alloc.201.sfu.id=3",
        "invalid_duplicate_id": "fiber.uplink.tcont.alloc.ids=101,101,201",
        "invalid_si_max": "fiber.uplink.tcont.alloc.101.si.max.frames=0",
        "invalid_type2_surplus": "fiber.uplink.tcont.alloc.101.ab.sur.bytes=1",
        "invalid_type3_si": "fiber.uplink.tcont.alloc.102.si.min.frames=0",
        "invalid_type4_ab": "fiber.uplink.tcont.alloc.201.ab.min.bytes=-1",
        "invalid_type": "fiber.uplink.tcont.alloc.101.type=1",
        "invalid_fcfs_mode": "fiber.uplink.dba.mode=fcfs",
        "invalid_disabled_stray": "fiber.uplink.tcont.enabled=false",
    }
    return {name: base + value + "\n" for name, value in invalid.items()}


def check_profiles(log: str, invalid_names: set[str]) -> list[dict]:
    trace = parse(log)
    rows = []
    validations = {row["scenario_id"]: row for row in events(trace, "TCONT_CONFIG_VALIDATION")}
    for name, row in validations.items():
        expected = "REJECTED" if name in invalid_names else "ACCEPTED"
        require(row["actual_result"] == expected, f"Unexpected Java config validation: {name}: {row}")
        profiles = [profile for profile in events(trace, "TCONT_PROFILE") if profile["scenario_id"] == name]
        require(bool(profiles) == (expected == "ACCEPTED"), "Rejected config leaked usable profiles")
        for actual in profiles or [row]:
            rows.append(dict(actual, expected_result=expected))
    require(invalid_names.issubset(validations), "Java did not validate every invalid fixture")
    baseline = {int(row["allocation_id"]): row for row in rows if row["scenario_id"] == "two_sfu_three_alloc"}
    require(set(baseline) == {101, 102, 201}, "Java profile loader lost an allocation")
    require(baseline[102]["si_max_frames"] == "1" and baseline[102]["si_min_frames"] == "2"
            and baseline[102]["ab_min_bytes"] == "2400" and baseline[102]["ab_sur_bytes"] == "1200",
            "Independent SI/AB metadata was swapped or coerced")
    return rows


def check_legacy(jar: Path, directory: Path) -> None:
    """仅最终验收时选择一次：使用当前Java重放归档，不构建任何历史Git版本。"""
    from run_pon_multi_grant_test import (
        FRAME_COLUMNS, GRANT_COLUMNS, check_old_outputs, check_scenario, check_special_case, load_rows,
        scenarios as previous_scenarios,
    )
    check_old_outputs(jar, directory)
    all_grants, all_frames = [], []
    for name, settings in previous_scenarios().items():
        log = execute(jar, directory / f"legacy-step14-{name}.properties", settings)
        grants, frames = check_scenario(name, settings, log)
        check_special_case(name, parse(log), grants, frames)
        all_grants.extend(grants)
        all_frames.extend(frames)
    for filename, columns, observed in (("grant-trace.csv", GRANT_COLUMNS, all_grants),
                                         ("frame-usage.csv", FRAME_COLUMNS, all_frames)):
        normalized = [{key: str(row.get(key, "")) for key in columns} for row in observed]
        require(normalized == load_rows(ROOT / "results/pon-multi-grant" / filename), f"Archived step14 changed: {filename}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--test-classes", type=Path, default=ROOT / "target/test-classes")
    parser.add_argument("--output", type=Path, default=ROOT / "results/pon-tcont")
    parser.add_argument("--check-legacy", action="store_true", help="完成后才执行一次旧场景归档逐字段回归")
    args = parser.parse_args()
    cases = scenarios()
    invalid = invalid_configs(cases["two_sfu_three_alloc"])
    trace_rows, logs = [], {}
    jar = args.jar.resolve()
    with TemporaryDirectory(prefix="fttr-tcont-") as temporary:
        directory = Path(temporary)
        for name, settings in cases.items():
            logs[name] = execute(jar, directory / f"{name}.properties", settings)
            trace_rows.extend(check_and_export(name, settings, logs[name]))
        replay = execute(jar, directory / "same-sfu-fifo-replay.properties", cases["same_sfu_fifo"])
        require(logs["same_sfu_fifo"] == replay, "Identical inputs did not reproduce the Java event log")
        phases = {"PON_DBA_BWMAP_TX", "PON_DBA_BWMAP_RX", "PON_DBA_FILL", "PON_DBA_DATA_START", "PON_DBA_DATA_END",
                  "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX", "DELIVERED", "TCONT_REPORT_RX"}
        protocol = lambda log: [row for row in parse(log) if row.get("event") in phases]
        # _index记录整个日志行号；新增参数观察行不应改变协议事件，比较时去掉这个辅助位置。
        stable = lambda rows: [{key: value for key, value in row.items() if key != "_index"} for row in rows]
        require(stable(protocol(logs["same_sfu_fifo"])) == stable(protocol(logs["bandwidth_metadata_changed"])),
                "RR incorrectly executed configured SI/AB guarantees or changed protocol timing")
        probe_paths = []
        for name, settings in {**cases, **invalid}.items():
            path = directory / f"{name}.properties"
            path.write_text(settings, encoding="utf-8")
            probe_paths.append(path)
        classpath = os.pathsep.join((str(args.test_classes.resolve()), str(jar)))
        profile_log = probe_profiles(classpath, probe_paths)
        profile_rows = check_profiles(profile_log, set(invalid))
        if args.check_legacy:
            check_legacy(jar, directory)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, settings in {**cases, **invalid}.items():
        (args.output / f"{name}.properties").write_text(settings, encoding="utf-8")
    (args.output / "test-config.properties").write_text(cases["two_sfu_three_alloc"], encoding="utf-8")
    for name, log in logs.items():
        (args.output / f"{name}-events.log").write_text(log, encoding="utf-8")
    (args.output / "profile-validation.log").write_text(profile_log, encoding="utf-8")
    write_csv(args.output / "tcont-trace.csv", TRACE_COLUMNS,
              [{key: row.get(key, "") for key in TRACE_COLUMNS} for row in trace_rows])
    write_csv(args.output / "bandwidth-profile.csv", PROFILE_COLUMNS,
              [{key: row.get(key, "") for key in PROFILE_COLUMNS} for row in profile_rows])
    print(f"Verified {len(cases)} actual Java T-CONT scenarios, {len(invalid)} invalid Java-loaded configurations, "
          f"{len(trace_rows)} logical event rows and {len(profile_rows)} parameter/validation rows.")
    print("FIFO, per-allocation REPORT/ledger/budget/settlement, shared capacity, deterministic replay and parameter-only behavior pass.")
    if args.check_legacy:
        print("Step14 archived results and prior rr/FCFS/shadow/REPORT/BWmap traces remain unchanged.")


if __name__ == "__main__":
    main()
