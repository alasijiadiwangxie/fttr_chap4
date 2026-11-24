"""Replay C2b-2a Java fixtures and independently reconcile subchannel PER traces."""

from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
from math import expm1, isclose, log1p
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv
from verify_c1_ap_observation import (
    OBSERVATION_EVENT, PPDU_EVENT, QUEUE_EVENT, check_protocol_events, check_windows, time_ns,
)
from verify_c2b1_sinr_per import (
    CURVE, DECODE, PHY_FAILURE, REF_BYTES, SEED, configuration, events, expected_draw, interpolated_per,
    parse_trace, verify_activity, verify_responses, write_properties, write_records,
)


RESULTS = ROOT / "results/c2b2a-subchannel-per"
MODEL = "wifi.phy.per.frequency.model"
BLOCK_MODEL = "subchannel-blocks"
WEIGHTS = "wifi.phy.per.subchannel.weights"
CHANNELS = "subchannels"
CHANNEL_SINR = "subchannel_sinr_db"
TRUTH = "wifi.channel.truth"
PROFILES = {
    "flat-good": (30, 30, 30, 30),
    "flat-moderate": (10, 10, 10, 10),
    "primary-weak": (-10, 30, 30, 30),
    "secondary-weak": (30, 30, 30, -10),
}


def truth_timeline(sinrs: tuple[int, ...]) -> str:
    # Keep the previous 30 dB report in the AP inbox and change the environment twice during the first attempt.
    changes = ((0, (30,) * len(sinrs)), (43, sinrs), ("43.001", (30,) * len(sinrs)))
    return ";".join(f"1,1,{channel},{time},{sinr},-1,{str(sinr < 10).lower()}"
                    for time, values in changes for channel, sinr in enumerate(values))


def scenario(mode: str, sinrs: tuple[int, ...], seed: int = 77) -> dict[str, str]:
    properties = configuration("0,0,0,0", (("0", "30"),), aggregate=True, mode=mode, seed=seed)
    return {**properties, MODEL: BLOCK_MODEL, TRUTH: truth_timeline(sinrs),
            "wifi.channel.observation.enabled": "true", "wifi.channel.feedback": "1,1,0,0,20,1000",
            "wifi.channel.observe.us": "43,43.001"}


def frequency_per(knots: list[tuple[float, float]], sinrs: list[float], fractions: list[float],
                  mpdu_bytes: int, reference_bytes: int) -> float:
    if len(set(sinrs)) == 1:
        return interpolated_per(knots, sinrs[0], mpdu_bytes, reference_bytes)
    block_probabilities = [interpolated_per(knots, sinr, reference_bytes, reference_bytes) for sinr in sinrs]
    if 1.0 in block_probabilities:
        return 1.0
    log_reference_success = sum(fraction * log1p(-probability)
                                for fraction, probability in zip(fractions, block_probabilities))
    return -expm1(log_reference_success * mpdu_bytes / reference_bytes)


def effective_bandwidth(config: dict[str, str]) -> int:
    ap_bandwidth = {"LCM": 20, "HCM": 80}[config["sfu.1.wifi.ap.mode"]]
    return min(ap_bandwidth, int(config.get("sta.1.wifi.max.bandwidth.mhz", "80")))


def verify_decodes(name: str, config: dict[str, str], rows: list[dict[str, str]]) -> list[dict[str, str]]:
    decodes = events(rows, DECODE)
    attempts = {(row["sfu_id"], row["packet_id"], row["retry_count"]): row
                for row in events(rows, "WIFI_MAC_ATTEMPT") if row["direction"] == "DL"}
    require(set(attempts) == {(row["sfu_id"], row["packet_id"], row["attempt"]) for row in decodes}
            and len(attempts) == len(decodes), f"{name}: expected a decision for every DL MPDU attempt")
    timelines = {}
    for entry in config[TRUTH].split(";"):
        sfu, sta, index, time, sinr, _, _ = entry.split(",")
        timelines.setdefault((sfu, sta, int(index)), []).append((int(Decimal(time) * 1000), float(sinr)))
    curves = {}
    for entry in config[CURVE].split(";"):
        mcs, sinr, probability = entry.split(",")
        curves.setdefault(mcs, []).append((float(sinr), float(probability)))
    delivered = {}
    expected_channels = list(range(effective_bandwidth(config) // 20))
    used_weights = [float(value) for value in config.get(WEIGHTS, "1,1,1,1").split(",")][:len(expected_channels)]
    expected_fractions = [weight / sum(used_weights) for weight in used_weights]
    for row in decodes:
        key = row["sfu_id"], row["packet_id"], row["attempt"]
        attempt = attempts[key]
        start_ns = int(row["ppdu_start_ns"])
        require(start_ns == time_ns(attempt) < time_ns(row), f"{name}: inconsistent PPDU timing")
        require(int(row["mpdu_bytes"]) == int(attempt["bytes"]) + 30, f"{name}: wrong effective MPDU size")
        if row["outcome"] == "MAC_COLLISION":
            require(all(row[field] == "-" for field in (CHANNEL_SINR, "block_weights", "per", "draw")),
                    f"{name}: collision also took a SINR sample")
            continue
        channels = [int(index) for index in row[CHANNELS].split(",")]
        sinrs = [float(value) for value in row[CHANNEL_SINR].split(",")]
        fractions = [float(value) for value in row["block_weights"].split(",")]
        expected_sinrs = [max((at, value) for at, value in timelines[row["sfu_id"], row["sta_id"], index]
                              if at <= start_ns)[1] for index in expected_channels]
        require(channels == expected_channels and sinrs == expected_sinrs,
                f"{name}: channel/SINR list disagrees with effective width and current true state")
        require(row["frequency_model"] == BLOCK_MODEL and len(fractions) == len(expected_fractions)
                and all(isclose(actual, expected, rel_tol=1e-12)
                        for actual, expected in zip(fractions, expected_fractions)),
                f"{name}: unnormalized or incorrect occupied subchannel weights")
        if len(set(sinrs)) == 1:
            require(float(row["sinr_db"]) == sinrs[0], f"{name}: uniform channel lost the scalar SINR")
        else:
            require(row["sinr_db"] == "-", f"{name}: nonuniform channels must not claim a scalar SINR")
        per = frequency_per(curves[row["mcs"]], sinrs, fractions, int(row["mpdu_bytes"]), int(config[REF_BYTES]))
        require(isclose(float(row["per"]), per, rel_tol=1e-12, abs_tol=1e-12),
                f"{name}: Java PER {row['per']} differs from audited PER {per}")
        draw = expected_draw(int(config[SEED]), int(row["sfu_id"]), int(row["sta_id"]),
                             int(row["packet_id"]), int(row["attempt"]))
        require(draw == float(row["draw"]) and row["outcome"] == (PHY_FAILURE if draw < per else "DECODED"),
                f"{name}: invalid deterministic draw or outcome")
        if row["outcome"] == "DECODED":
            require(row["packet_id"] not in delivered, f"{name}: duplicate successful packet")
            delivered[row["packet_id"]] = time_ns(row)
    deliveries = [(row["packet_id"], time_ns(row)) for row in events(rows, "DELIVERED")]
    require(len(deliveries) == len(delivered) and dict(deliveries) == delivered,
            f"{name}: deliveries disagree with MPDU successes")
    return decodes


def compare_legacy(name: str, jar: Path, configs: Path, config: dict[str, str], output: str,
                   decodes: list[dict[str, str]]) -> None:
    legacy = configs / f"{name}-c2b1.properties"
    write_properties(legacy, {key: value for key, value in config.items() if key != MODEL})
    baseline = simulate(jar, legacy)
    protocol = lambda text: [line for line in text.splitlines() if f"event={DECODE} " not in line]
    require(protocol(baseline) == protocol(output), f"{name}: flat-channel protocol differs from C2b-1")
    fields = ("t_ns", "ppdu_start_ns", "sfu_id", "sta_id", "head_id", "packet_id", "attempt", "mcs",
              "mpdu_bytes", "per", "draw", "outcome")
    signatures = lambda decisions: [tuple(row[key] for key in fields) for row in decisions]
    require(signatures(events(parse_trace(baseline), DECODE)) == signatures(decodes),
            f"{name}: flat-channel PER, draw or decode differs from C2b-1")


def compare_modes(name: str, profile: str, config: dict[str, str], rows: list[dict[str, str]],
                  decodes: list[dict[str, str]], activity: dict[str, str], repeated: bool,
                  flat_legacy_equal: bool) -> dict:
    mode = config["sfu.1.wifi.ap.mode"]
    first = [row for row in decodes if row["ppdu_start_ns"] == decodes[0]["ppdu_start_ns"]]
    ap_observation = next(row for row in events(rows, "WIFI_CHANNEL_OBSERVATION")
                          if row["t_ns"] == first[0]["ppdu_start_ns"] and row["subchannel"] == "0")
    require(ap_observation["status"] == "VISIBLE" and float(ap_observation["sinr_db"]) == 30,
            f"{name}: expected previous AP report to be 30 dB throughout the first PPDU")
    outcomes = Counter(row["outcome"] for row in decodes)
    retry = next(row for row in rows if row.get("summary") == "WIFI_RETRY")
    energy = next(row for row in rows if row.get("summary") == "WIFI_AP_ENERGY")
    initial_mask = sum(1 << index for index, row in enumerate(first) if row["outcome"] == "DECODED")
    return {
        "scenario": name, "profile": profile, "ap_mode": mode, "per_seed": config[SEED],
        "effective_bandwidth_mhz": effective_bandwidth(config),
        "first_ppdu_subchannels": first[0][CHANNELS], "first_ppdu_sinr_db": first[0][CHANNEL_SINR],
        "first_ppdu_block_weights": first[0]["block_weights"],
        "ap_visible_primary_sinr_db": ap_observation["sinr_db"],
        "first_ppdu_start_ns": first[0]["ppdu_start_ns"], "first_ppdu_end_ns": first[0]["t_ns"],
        "first_ppdu_mpdu_per": first[0]["per"],
        "first_ppdu_failed_packet_ids": ",".join(row["packet_id"] for row in first if row["outcome"] != "DECODED"),
        "first_ppdu_ba_bitmap": hex(initial_mask) if initial_mask else "",
        "mpdu_attempts": len(decodes), "unique_deliveries": outcomes["DECODED"], "sinr_errors": outcomes[PHY_FAILURE],
        "retransmissions": retry["retransmissions"], "ack_timeouts": retry["ack_timeouts"],
        "retry_limit_drops": retry["retry_limit_drops"], "observation_end_ns": activity["observation_end_ns"],
        "ap_tx_ns": activity["tx_ns"], "ap_rx_ns": activity["rx_ns"], "ap_idle_ns": activity["idle_ns"],
        "ap_energy_uj": energy["total_energy_uj"],
        "fixed_seed_trace_equal": "true" if repeated else "",
        "flat_c2b1_trace_equal": "true" if flat_legacy_equal else "",
    }


def verify(jar: Path, output: Path) -> None:
    configs = output / "configs"
    configs.mkdir(parents=True, exist_ok=True)
    decoded_rows = []
    comparisons = []
    cases = [(profile, mode, scenario(mode, sinrs)) for profile, sinrs in PROFILES.items()
             for mode in ("LCM", "HCM")]
    cases.extend((
        ("secondary-weak-alt-seed", "HCM", scenario("HCM", PROFILES["secondary-weak"], 101)),
        ("secondary-weak-weighted", "HCM", {**scenario("HCM", PROFILES["secondary-weak"]), WEIGHTS: "1,1,1,3"}),
        ("primary-only-truth", "LCM", scenario("LCM", (30,))),
        ("secondary-weak-hcm-40mhz", "HCM", {**scenario("HCM", PROFILES["secondary-weak"]),
                                            "sta.1.wifi.max.bandwidth.mhz": "40"}),
    ))
    for profile, mode, config in cases:
        name = f"{profile}-{mode.lower()}"
        path = configs / f"{name}.properties"
        write_properties(path, config)
        stdout = simulate(jar, path)
        rows = parse_trace(stdout)
        activity = verify_activity(name, rows)
        queue = events(rows, QUEUE_EVENT)
        ppdus = events(rows, PPDU_EVENT)
        check_protocol_events(name, rows, queue, ppdus)
        check_windows(name, config, rows, events(rows, OBSERVATION_EVENT), queue, ppdus)
        decodes = verify_decodes(name, config, rows)
        verify_responses(name, config, rows, decodes)
        repeated = profile.startswith("secondary-weak") and mode == "HCM"
        if repeated:
            require(simulate(jar, path) == stdout, f"{name}: fixed-seed trace changed on replay")
        flat = profile.startswith("flat-")
        if flat:
            compare_legacy(name, jar, configs, config, stdout, decodes)
        decoded_rows.extend(dict(scenario=name, ap_mode=mode, per_seed=config[SEED], **row) for row in decodes)
        comparisons.append(compare_modes(name, profile, config, rows, decodes, activity, repeated, flat))
        print(f"{name}: {comparisons[-1]['first_ppdu_mpdu_per']} first-PPDU PER, "
              f"{comparisons[-1]['retransmissions']} retries, {comparisons[-1]['unique_deliveries']} deliveries")

    by_scenario = {row["scenario"]: row for row in comparisons}
    per = lambda name: float(by_scenario[name]["first_ppdu_mpdu_per"])
    require(1 > per("primary-weak-lcm") > per("primary-weak-hcm") > 0,
            "primary fade should affect both modes with a larger assigned share in LCM")
    require(per("secondary-weak-lcm") == per("flat-good-lcm") == 0 and per("secondary-weak-hcm") > 0,
            "secondary fade should affect HCM, while the LCM primary remains clean")
    require(per("secondary-weak-hcm-40mhz-hcm") == 0, "40 MHz PPDU must ignore subchannel 3")
    require(per("secondary-weak-weighted-hcm") > per("secondary-weak-hcm"), "larger faded-block weight should raise PER")
    require(by_scenario["secondary-weak-hcm"]["first_ppdu_ba_bitmap"] not in ("", "0xf")
            and by_scenario["secondary-weak-hcm"]["unique_deliveries"] == 4,
            "HCM secondary fade should exercise partial BA followed by recovery")

    invalid = configs / "reject-missing-used-hcm.properties"
    write_properties(invalid, scenario("HCM", (30, 30, 30)))
    try:
        simulate(jar, invalid)
    except RuntimeError as error:
        require("Missing channel truth" in str(error), f"unexpected missing-subchannel error: {error}")
        print("reject-missing-used-hcm: rejected by Java")
    else:
        raise ValueError("HCM accepted missing true state on used subchannel 3")
    write_records(output / "c2b2a-mpdu-decode.csv", decoded_rows)
    write_csv(output / "c2b2a-lcm-hcm-comparison.csv", tuple(comparisons[0]), comparisons)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=RESULTS)
    args = parser.parse_args()
    verify(args.jar.resolve(), args.output)


if __name__ == "__main__":
    main()
