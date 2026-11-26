"""Targeted checks for E4's experimental fixtures, paired inference, and archived E3 replay."""

from __future__ import annotations

import os
import subprocess
from collections import Counter
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, skipUnless

from e2_design import PILOT_SEEDS, SEEDS
from e2_metrics import summarize_packets
from e3_experiments import ACCEPTANCE_SEEDS, DEVELOPMENT_SEEDS
from e4_design import CALIBRATION_SEEDS, E1, E3, E3_NO_SCAN, FIXED, FIXED_SCAN, FORMAL_SEEDS, HYST, MAIN
from e4_design import formal, packet_fixture, settings, training
from e4_run import WORK, select_band
from e4_stats import matched_stats
from pon_experiment_io import ROOT
from verify_c1_ap_observation import write_properties
from verify_d1_mode_control import JAR


class E4DesignTest(TestCase):
    def test_registered_cases_separate_calibration_and_formal_samples(self):
        seeds = FORMAL_SEEDS + CALIBRATION_SEEDS + SEEDS + PILOT_SEEDS + ACCEPTANCE_SEEDS + DEVELOPMENT_SEEDS
        self.assertEqual(len(seeds), len(set(seeds)))
        self.assertEqual(1500, len(FORMAL_SEEDS) * sum(len(exp.policies) for exp in formal()))
        self.assertEqual(54, len(CALIBRATION_SEEDS) * sum(len(exp.policies) for exp in training()))
        self.assertFalse({e.case.name for e in formal()} & {e.case.name for e in training()})

    def test_scan_free_thresholds_and_power_treatments_keep_the_workload_and_radio(self):
        cases = {e.case.name: e for e in formal()}
        case = cases["rf-secondary-strong-15"]
        seed = FORMAL_SEEDS[0]
        packets = packet_fixture(case, seed)
        cfg = settings(case, seed, E3, packets)
        for policy in (*MAIN, FIXED_SCAN, E3_NO_SCAN):
            other = settings(case, seed, policy, packets, (30, 32))
            allowed = ("wifi.ap.control.policy", "wifi.ap.control.hysteresis.", "wifi.channel.scan.")
            self.assertEqual({k: v for k, v in cfg.items() if not k.startswith(allowed)},
                             {k: v for k, v in other.items() if not k.startswith(allowed)})
            self.assertEqual(policy in (E1, E3, FIXED_SCAN), "wifi.channel.scan.schedule" in other)
        paper = cases["paper-rf-secondary-strong-15"]
        self.assertEqual(packets, packet_fixture(paper, seed))
        substitute = settings(paper, seed, E3, packets)
        self.assertEqual({k for k in cfg.keys() | substitute.keys() if cfg.get(k) != substitute.get(k)},
                         {f"wifi.ap.energy.{mode}.{state}.w" for mode in ("lcm", "hcm") for state in ("idle", "tx", "rx")})
        joint = cases["joint-4sfu"]
        self.assertEqual(packet_fixture(joint, seed), packet_fixture(cases["joint-4sfu-rf"], seed))

    def test_deferral_factorial_preserves_sizes_times_and_balanced_sta_inputs(self):
        cases = {e.case.name: e for e in formal()}
        seed = FORMAL_SEEDS[0]
        for gap in ("6ms", "16ms"):
            group = cases[f"defer-{gap}-tight-grouped"]
            spread = cases[f"defer-{gap}-tight-interleaved"]
            relaxed = cases[f"defer-{gap}-relaxed-grouped"]
            a, b, c = (packet_fixture(x, seed) for x in (group, spread, relaxed))
            self.assertEqual(a, c)
            self.assertNotEqual(a, b)
            self.assertEqual([p[:2] + p[3:] for p in a], [p[:2] + p[3:] for p in b])
            # A partially filled sequence of bursts can change a count by half one BE burst at most.
            aa, bb = (Counter((p[2], p[3]) for p in values) for values in (a, b))
            self.assertTrue(all(abs(aa[cell] - bb[cell]) <= 6 for cell in aa))
            self.assertEqual("1800", settings(relaxed, seed, E3, c)["wifi.ap.qos.VO.deadline.us"])
            self.assertNotIn("wifi.ap.switch.requests", settings(relaxed, seed, E3, c))

    def test_qos_audit_applies_custom_experiment_deadlines(self):
        packets = {"1": (0, 500, "VO", "DL", "1", "1")}
        nominal = summarize_packets(["1"], packets, {"1": 800_000}, {}, {"1": 0}, 1_000_000, {"1": 10_000})
        altered = summarize_packets(["1"], packets, {"1": 800_000}, {}, {"1": 0}, 1_000_000, {"1": 10_000}, {"VO": 1_800_000})
        self.assertEqual((0, 100), (nominal["on_time_pct"], altered["on_time_pct"]))

    def test_selects_energy_saving_band_only_after_priority_protection(self):
        rows = []
        grid = (FIXED, "hysteresis-30-32", "hysteresis-26-36")
        for case in training():
            for seed in CALIBRATION_SEEDS:
                for policy in grid:
                    for ac in ("ALL", "VO", "VI"):
                        rows.append(dict(scenario=case.case.name, seed=seed, policy=policy, ac=ac, sfu_id="ALL",
                                         direction="BOTH", goodput_mbps=30, switches=3,
                                         complete_uj=dict(zip(grid, (100, 98, 80)))[policy],
                                         on_time_pct=90 if policy == grid[2] and ac == "VI" else 100))
        chosen, = (r for r in select_band(rows) if r["selected"])
        self.assertEqual("hysteresis-30-32", chosen["policy"])
        self.assertTrue(chosen["constraints_met"])

    def test_paired_inference_fails_on_changed_packet_sequence(self):
        exp = next(formal())
        scope = ("ALL", "BOTH", "ALL", FORMAL_SEEDS[0])
        control = dict(input_sha256="input", window_us=500000, goodput_mbps=42)
        actual = control | {"goodput_mbps": 43}
        index = {(exp.case.name, FIXED): {scope: control}, (exp.case.name, E3): {scope: actual}}
        self.assertEqual(1, matched_stats(index, exp, E3, exp.case.name, FIXED, "main")[0]["mean"])
        actual["input_sha256"] = "wrong"
        with self.assertRaisesRegex(ValueError, "paired workload"):
            matched_stats(index, exp, E3, exp.case.name, FIXED, "main")

    @skipUnless((WORK / "e3-frozen.jar").is_file(), "Place the frozen 9d96c7f jar in target/e4-thesis to compare traces")
    def test_archived_e3_trace_matches_the_current_jar(self):
        frozen = WORK / "e3-frozen.jar"
        with TemporaryDirectory(dir=WORK) as temporary:
            work = Path(temporary)
            source = ROOT / "tools/java/E2Trace.java"
            entry = work / source.name
            code = source.read_text(encoding="utf-8").replace('"WIFI_EXTERNAL_CCA");',
                   '"WIFI_EXTERNAL_CCA", "WIFI_AP_D4_DECISION", "WIFI_AP_E3_DECISION", "WIFI_AP_ENERGY_MODE_DECISION");')
            entry.write_text(code, encoding="utf-8")
            subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8", "-cp", str(JAR), "-d", str(work),
                            str(entry)], check=True, capture_output=True)
            for exp in formal():
                if exp.case.name not in ("load-050", "rf-secondary-strong-35", "joint-4sfu"):
                    continue
                cfg = work / (exp.case.name + ".properties")
                seed = FORMAL_SEEDS[0]
                write_properties(cfg, settings(exp, seed, E3, packet_fixture(exp, seed)))
                logs = [work / (exp.case.name + f"-{i}.log") for i in range(2)]
                for jar, log in zip((frozen, JAR), logs):
                    cmd = ["java", "-Xmx750m", "-cp", os.pathsep.join((str(jar), str(work))), "E2Trace"]
                    subprocess.run([*cmd, str(cfg), str(log)], check=True, capture_output=True, timeout=120)
                self.assertEqual(logs[0].read_bytes(), logs[1].read_bytes(), exp.case.name)
