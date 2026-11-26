"""Targeted tests for the E2 experiment framework. No production modules are edited."""

from dataclasses import replace
from pathlib import Path
from subprocess import run
from tempfile import TemporaryDirectory
from unittest import TestCase

from e2_analyze import interval, pair_stats
from e2_design import ECQ, FIXED, GROUPS, MAIN, PILOT_SEEDS, SEEDS, arrivals, properties, study
from e2_metrics import NETWORK, external_busy, mode_intervals, summarize_packets, summarize_run
from e2_run import WORK, compile_runner, trials
from verify_c1_ap_observation import write_properties


class E2DesignTest(TestCase):
    def test_complete_grid_and_disjoint_pilot(self):
        self.assertEqual(29, len(study()))
        self.assertEqual(1110, sum(len(case.variants) for case, _ in trials()))
        self.assertEqual(32, sum(len(case.variants) for case, _ in trials(pilot=True)))
        self.assertEqual(10, len(set(SEEDS)))
        self.assertFalse(set(SEEDS) & set(PILOT_SEEDS))
        self.assertEqual(set(GROUPS), {case.group for case in study()})
        self.assertTrue(all(case.variants == MAIN for case in study() if case.group != "06-sensitivity"))

    def test_doubled_window_has_exact_prefix(self):
        short_by_name = {case.name: case for case in study()}
        for extended, seed in trials(pilot=True):
            with self.subTest(case=extended.name, seed=seed):
                short = short_by_name[extended.name]
                prefix = [packet for packet in arrivals(extended, seed) if packet[0] < short.window_us]
                self.assertEqual(arrivals(short, seed), prefix)
        case = short_by_name["dynamic-random"]
        self.assertNotEqual(arrivals(case, SEEDS[0]), arrivals(case, SEEDS[1]))

    def test_causal_fixtures_for_rf_and_sensitivity(self):
        cases = {case.name: case for case in study()}
        seed = SEEDS[0]
        ref = arrivals(cases["rf-clear"], seed)
        for case in cases.values():
            if case.workload_key == "rf-family":
                self.assertEqual(ref, arrivals(case, seed))
        case = cases["joint-4sfu"]
        workload = arrivals(case, seed)
        self.assertEqual({("DL", a) for a in ("VO", "VI", "BE", "BK")} | {("UL", a) for a in ("VO", "VI", "BE", "BK")},
                         {(direction, ac) for _, direction, _, ac, _ in workload})
        baseline = properties(case, seed, ECQ, workload)
        self.assertEqual("giant-complete-recovery", baseline["fiber.uplink.dba.mode"])
        self.assertEqual("8", baseline["wifi.mac.ampdu.max-mpdus"])
        self.assertEqual("5000", baseline["wifi.ap.control.e1.cca.window.us"])
        for policy in MAIN:
            comparison = properties(case, seed, policy, workload)
            self.assertEqual({"wifi.ap.control.policy"} if policy != ECQ else set(),
                             {key for key in baseline if baseline[key] != comparison[key]})


class E2AccountingTest(TestCase):
    def test_empty_and_incomplete_cohorts(self):
        packets = {"1": (0, 500, "VO", "DL", "1", "1"), "2": (10_000, 500, "VO", "DL", "1", "1"),
                   "3": (10_000, 500, "VO", "DL", "1", "1"), "4": (0, 500, "VO", "DL", "1", "1")}
        row = summarize_packets(list(packets), packets, {"1": 240_000, "2": 800_000}, {"3": 350_000},
                                {"1": 0, "2": 1}, 700_000, {"1": 15_000, "2": 20_000})
        self.assertEqual((4, 1, 1, 2, 1), tuple(row[k] for k in ("generated", "delivered_window", "dropped_window",
                                                                 "backlog_window", "unsettled_final")))
        self.assertEqual((25, 25, .79, .55), tuple(row[k] for k in ("on_time_pct", "drop_pct", "p99_ms", "jitter_ms")))
        self.assertEqual(2, row["overdue_window"])
        empty = summarize_packets([], {}, {}, {}, {}, 700_000, {})
        self.assertEqual(0, empty["goodput_mbps"])
        self.assertIsNone(empty["on_time_pct"])
        self.assertIsNone(empty["p99_ms"])

    def test_t_interval_on_paired_differences(self):
        values = []
        for i, seed in enumerate(SEEDS):
            meta = dict(sfu_id="ALL", direction="BOTH", ac="ALL", scenario="probe", window_us=1000,
                        seed=seed, input_sha256=str(seed))
            values.extend((meta | dict(policy=FIXED, goodput_mbps=i + 20), meta | dict(policy=ECQ, goodput_mbps=i + 21)))
        diff, = pair_stats(values, "framework example", ("probe", ECQ), ("probe", FIXED))
        self.assertEqual((10, 1, 0, 1, 1), tuple(diff[k] for k in ("n", "mean", "sd", "ci95_low", "ci95_high")))
        self.assertAlmostEqual(2 - 12.706205, interval([1, 3])["ci95_low"])
        values[0]["input_sha256"] = "changed"
        with self.assertRaisesRegex(ValueError, "not paired"):
            pair_stats(values, "framework example", ("probe", ECQ), ("probe", FIXED))

    def test_fixed_window_truncates_mode_and_busy_intervals(self):
        edge_rows = [dict(event="WIFI_EXTERNAL_CCA", sfu_id="1", t_ns="30", busy="true"),
                     dict(event="WIFI_EXTERNAL_CCA", sfu_id="1", t_ns="180", busy="false")]
        self.assertEqual({"1": 70}, external_busy(edge_rows, {"1": "LCM"}, 100))
        segments = mode_intervals({"1": "LCM"}, {"1": [dict(start_ns=80, finish_ns=105, target="HCM")]}, 100)
        self.assertEqual([("LCM", 0, 80), ("SWITCH", 80, 100)],
                         [(row["mode"], row["start_ns"], row["end_ns"]) for row in segments])

    def test_java_events_match_on_replay_and_reconcile(self):
        source = next(case for case in study() if case.name == "joint-2sfu")
        case = replace(source, window_us=30_000)
        seed = 9001
        runner = compile_runner()
        with TemporaryDirectory(dir=WORK) as temporary:
            stem = Path(temporary) / "e2-framework"
            config = stem.with_suffix(".properties")
            write_properties(config, properties(case, seed, ECQ, arrivals(case, seed)))
            first, second = stem.with_suffix(".log"), stem.with_suffix(".repeat.log")
            run([*runner, str(config), str(first), str(config), str(second)], check=True, capture_output=True)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            _, rows, _ = summarize_run(case, seed, ECQ, stem, "targeted-replay", False)
            net = next(r for r in rows if tuple(r[k] for k in ("sfu_id", "direction", "ac")) == NETWORK)
            self.assertGreater(net["pon_actual_bytes"], 0)
            self.assertEqual(net["generated"], net["delivered_final"] + net["dropped_final"] + net["unsettled_final"])
            self.assertEqual(net["total_uj"] + net["drain_uj"], net["complete_uj"])
