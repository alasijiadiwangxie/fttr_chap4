"""Replay C2b-1 Java fixtures, audit decodes and queues, and export both CSVs."""

from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
from math import expm1, isclose, log1p
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv
from verify_c1_ap_observation import (
    PPDU_EVENT, OBSERVATION_EVENT, QUEUE_EVENT, activity_intervals,
    check_protocol_events, check_windows, overlaps, time_ns,
)


RESULTS = ROOT / "results/c2b1-sinr-per"
ENABLED = "wifi.phy.per.enabled"
SEED = "wifi.phy.per.seed"
REF_BYTES = "wifi.phy.per.reference.mpdu.bytes"
CURVE = "wifi.phy.per.curves"
SOURCE = "wifi.phy.per.source"
DECODE = "WIFI_PHY_MPDU_DECODE"
PHY_FAILURE = "SINR_ERROR"
REFERENCE_MPDU_BYTES = 1530


def truth_timeline(changes: tuple[tuple[str, str], ...]) -> str:
    return ";".join(f"1,1,{channel},{time},{sinr},-1,{str(float(sinr) < 10).lower()}"
                    for time, sinr in changes for channel in range(4))


def configuration(arrivals: str, changes: tuple[tuple[str, str], ...],
                  *, aggregate: bool = False, mode: str = "HCM", seed: int = 101) -> dict[str, str]:
    properties = {
        "traffic.mode": "manual",
        "traffic.direction": "DL",
        "packet.arrival.us": arrivals,
        "packet.bytes": "1500",
        "sfu.count": "1",
        "sta.count": "1",
        "fiber.delay.us": "0",
        "sfu.1.wifi.ap.mode": mode,
        "wifi.model": "he-su",
        "wifi.mac.access": "edca-multi-ac",
        "wifi.mac.exchange": "data-sifs-ack",
        "wifi.mac.ack.rate.mbps": "24",
        "wifi.mac.ack.timeout.us": "60",
        "wifi.mac.retry.max": "1",
        "wifi.mac.edca.seed": "101",
        "wifi.mac.edca.BE.aifsn": "3",
        "wifi.mac.edca.BE.cwmin": "0",
        "wifi.mac.edca.BE.txop.limit.us": "0",
        "wifi.mac.ampdu.enabled": str(aggregate).lower(),
        "wifi.ap.activity.enabled": "true",
        "wifi.ap.activity.window.us": "2000",
        "wifi.ap.energy.enabled": "true",
        "wifi.ap.observation.enabled": "true",
        "wifi.ap.observation.period.us": "50",
        "wifi.ap.observation.ewma.alpha": "0.25",
        ENABLED: "true",
        SEED: str(seed),
        REF_BYTES: str(REFERENCE_MPDU_BYTES),
        CURVE: "7,-12,1;7,10,0.5;7,30,0",
        SOURCE: "SYNTHETIC_TEST",
        "wifi.channel.truth": truth_timeline(changes),
    }
    if aggregate:
        properties["wifi.mac.ampdu.max-mpdus"] = "4"
    return properties


def scenarios() -> dict[str, dict[str, str]]:
    partial = configuration("0,0,0,0", (("0", "30"), ("43", "10"), ("43.001", "-12"), ("220", "30")),
                            aggregate=True, seed=77)
    # A previously received 30 dB report should never substitute for the 10 dB true PPDU-start SINR.
    partial.update({
        "wifi.channel.observation.enabled": "true",
        "wifi.channel.feedback": "1,1,0,0,20,1000",
        "wifi.channel.observe.us": "43,43.001",
    })
    collision = configuration("0,0", (("0", "-12"),))
    collision.update({"traffic.direction": "BOTH", "packet.1.direction": "DL", "packet.2.direction": "UL",
                      "wifi.mac.retry.max": "0"})
    legacy = configuration("0,0,0,0", (("0", "-12"),), aggregate=True)
    legacy.update({ENABLED: "false", "wifi.mac.ampdu.first-attempt-error.packet.ids": "2,4"})
    return {
        "high-sinr": configuration("0,350", (("0", "30"),), mode="LCM"),
        "low-sinr": configuration("0,350", (("0", "-12"),)),
        "start-boundary": configuration("0,350", (("0", "-12"), ("43", "30"), ("43.001", "-12"))),
        "partial-ampdu": partial,
        "partial-ampdu-alt-seed": {**partial, SEED: "101"},
        "length-variation": {**configuration("0,350", (("0", "12"),)), "packet.1.bytes": "200",
                             "packet.2.bytes": "800"},
        "all-failed-ampdu": configuration("0,0,0,0", (("0", "-12"),), aggregate=True),
        "mac-collision": collision,
        "disabled-b6-compatibility": legacy,
    }


def write_properties(path: Path, properties: dict[str, str]) -> None:
    path.write_text("".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8")


def parse_trace(output: str) -> list[dict[str, str]]:
    parsed = []
    for index, line in enumerate(output.splitlines(), 1):
        parsed.append(dict((item.split("=", 1) for item in line.split() if "=" in item), trace_sequence=str(index)))
    return parsed


def events(rows: list[dict[str, str]], name: str) -> list[dict[str, str]]:
    return [row for row in rows if row.get("event") == name]


def verify_activity(name: str, rows: list[dict[str, str]]) -> dict[str, str]:
    activity = next(row for row in rows if row.get("summary") == "WIFI_AP_ACTIVITY")
    energy = next(row for row in rows if row.get("summary") == "WIFI_AP_ENERGY")
    end_ns = int(activity["observation_end_ns"])
    durations = overlaps(activity_intervals(rows, end_ns)["1"], 0, end_ns)
    total = Decimal(0)
    for state in ("idle", "tx", "rx"):
        require(int(activity[f"{state}_ns"]) == durations[state.upper()], f"{name}: B2 {state} time mismatch")
        calculated = Decimal(energy[f"{state}_power_w"]) * Decimal(activity[f"{state}_ns"]).scaleb(-3)
        require(calculated == Decimal(energy[f"{state}_energy_uj"]), f"{name}: B3 {state} energy mismatch")
        total += calculated
    require(sum(durations.values()) == end_ns, f"{name}: B2 time is not conserved")
    require(total == Decimal(energy["total_energy_uj"]), f"{name}: B3 total energy mismatch")
    return activity


def interpolated_per(knots: list[tuple[float, float]], sinr: float, mpdu_bytes: int, reference_bytes: int) -> float:
    if sinr <= knots[0][0]:
        reference_per = knots[0][1]
    elif sinr >= knots[-1][0]:
        reference_per = knots[-1][1]
    else:
        for (left_db, left_per), (right_db, right_per) in zip(knots, knots[1:]):
            if sinr <= right_db:
                reference_per = left_per + (right_per - left_per) * (sinr - left_db) / (right_db - left_db)
                break
    if reference_per in (0.0, 1.0):
        return reference_per
    return -expm1(log1p(-reference_per) * mpdu_bytes / reference_bytes)


def expected_draw(seed: int, sfu_id: int, sta_id: int, packet_id: int, attempt: int) -> float:
    mask = (1 << 64) - 1

    def mix(value: int) -> int:
        value = ((value ^ (value >> 30)) * 0xbf58476d1ce4e5b9) & mask
        value = ((value ^ (value >> 27)) * 0x94d049bb133111eb) & mask
        return value ^ (value >> 31)

    key = mix((seed & mask) ^ 0x9e3779b97f4a7c15)
    for value in (sfu_id, sta_id, packet_id & ((1 << 32) - 1), attempt):
        key = mix(key ^ value)
    return (key >> 11) / (1 << 53)


def verify_decodes(name: str, properties: dict[str, str], rows: list[dict[str, str]]) -> list[dict[str, str]]:
    decode = events(rows, DECODE)
    if properties[ENABLED] == "false":
        require(not decode, f"{name}: disabled model logged PHY decisions")
        return decode

    attempts = {(row["sfu_id"], row["packet_id"], row["retry_count"]): row
                for row in events(rows, "WIFI_MAC_ATTEMPT") if row["direction"] == "DL"}
    require(set(attempts) == {(row["sfu_id"], row["packet_id"], row["attempt"]) for row in decode}
            and len(attempts) == len(decode), f"{name}: missing or duplicate MPDU attempt")
    truth = []
    for entry in properties["wifi.channel.truth"].split(";"):
        sfu, sta, channel, starts_us, sinr, _, _ = entry.split(",")
        truth.append((sfu, sta, channel, int(Decimal(starts_us) * 1000), float(sinr)))
    curves = {}
    for entry in properties[CURVE].split(";"):
        mcs, sinr, probability = entry.split(",")
        curves.setdefault(mcs, []).append((float(sinr), float(probability)))

    successes = {}
    for row in decode:
        attempt = attempts[row["sfu_id"], row["packet_id"], row["attempt"]]
        start_ns = int(row["ppdu_start_ns"])
        require(start_ns == time_ns(attempt) < time_ns(row), f"{name}: PPDU start/finish mismatch ({row})")
        require(int(row["mpdu_bytes"]) == int(attempt["bytes"]) + 30, f"{name}: actual MPDU size mismatch ({row})")
        require(row["mcs"] == "7", f"{name}: wrong effective B1 MCS ({row})")
        if row["outcome"] == "MAC_COLLISION":
            require(all(row[field] == "-" for field in ("sinr_db", "per", "draw")),
                    f"{name}: collision also took an SINR sample")
            continue
        actual_sinr = []
        for channel in range(4):
            candidates = [(at, quality) for sfu, sta, idx, at, quality in truth
                          if (sfu, sta, idx) == (row["sfu_id"], row["sta_id"], str(channel)) and at <= start_ns]
            actual_sinr.append(max(candidates)[1])
        require(len(set(actual_sinr)) == 1 and float(row["sinr_db"]) == actual_sinr[0],
                f"{name}: decision used a different SINR than the flat state at PPDU start")
        probability = interpolated_per(curves[row["mcs"]], actual_sinr[0], int(row["mpdu_bytes"]),
                                       int(properties[REF_BYTES]))
        require(isclose(float(row["per"]), probability, abs_tol=1e-12, rel_tol=1e-10),
                f"{name}: MPDU PER mismatch ({row})")
        draw = float(row["draw"])
        independently_drawn = expected_draw(int(properties[SEED]), int(row["sfu_id"]), int(row["sta_id"]),
                                            int(row["packet_id"]), int(row["attempt"]))
        require(draw == independently_drawn and row["outcome"] == (PHY_FAILURE if draw < probability else "DECODED"),
                f"{name}: decode result does not follow the recorded draw ({row})")
        if row["outcome"] == "DECODED":
            packet = row["packet_id"]
            require(packet not in successes, f"{name}: duplicate decoded packet")
            successes[packet] = time_ns(row)
    delivery = [(row["packet_id"], time_ns(row)) for row in events(rows, "DELIVERED")
                if row.get("direction", "DL") == "DL"]
    require(len(delivery) == len(successes) and dict(delivery) == successes,
            f"{name}: successful MPDUs differ from actual delivery events")
    return decode


def verify_responses(name: str, properties: dict[str, str], rows: list[dict[str, str]],
                     decode: list[dict[str, str]]) -> None:
    if not decode:
        return
    frames = {}
    for row in decode:
        frames.setdefault((row["sfu_id"], row["head_id"], row["ppdu_start_ns"]), []).append(row)
    ack_end = {(row["sfu_id"], row["packet_id"], time_ns(row)) for row in events(rows, "WIFI_ACK_END")}
    ba_end = {(row["sfu_id"], row["packet_id"], time_ns(row)): int(row["bitmap"], 16)
              for row in events(rows, "WIFI_AMPDU_BA_END")}
    timeout = {(row["sfu_id"], row["packet_id"], time_ns(row)) for row in events(rows, "WIFI_ACK_TIMEOUT")}
    for (sfu_id, head_id, start_ns), members in frames.items():
        end_ns = time_ns(members[0])
        require(all(time_ns(row) == end_ns for row in members), f"{name}: inconsistent DATA end in PPDU")
        bitmap = sum((1 << index) for index, row in enumerate(members) if row["outcome"] == "DECODED")
        if len(members) > 1:
            start = next(row for row in events(rows, "WIFI_AMPDU_DATA_START")
                         if str(time_ns(row)) == start_ns and row["sfu_id"] == sfu_id and row["packet_id"] == head_id)
            require(start["packet_ids"].split(",") == [row["packet_id"] for row in members]
                    and end_ns - int(start_ns) == int(start["ppdu_ns"]),
                    f"{name}: decision order/timing does not match A-MPDU allocation")
            response = sfu_id, head_id, end_ns + int(start["sifs_ns"]) + int(start["ba_ns"])
            require((ba_end.get(response) == bitmap) if bitmap else (response not in ba_end),
                    f"{name}: unexpected Block ACK completion/bitmap for frame {start_ns}")
        else:
            response = sfu_id, head_id, end_ns + 16_000 + 28_000
            require((response in ack_end) == bool(bitmap), f"{name}: incorrect singleton ACK for frame {start_ns}")
        deadline = sfu_id, head_id, end_ns + int(properties["wifi.mac.ack.timeout.us"]) * 1000
        require((deadline in timeout) == (bitmap == 0), f"{name}: wrong timeout result for frame {start_ns}")


def verify_case(name: str, rows: list[dict[str, str]], decode: list[dict[str, str]],
                activity: dict[str, str]) -> None:
    outcomes = Counter(row["outcome"] for row in decode)
    retries = next(row for row in rows if row.get("summary") == "WIFI_RETRY")
    collisions = next((row for row in rows if row.get("summary") == "WIFI_COLLISION"), {})
    require(int(collisions.get("collision_events", 0)) == (1 if name == "mac-collision" else 0),
            f"{name}: MAC collision statistics include a wrong cause")
    if name == "mac-collision":
        require(outcomes == {"MAC_COLLISION": 1}, f"{name}: collision was not isolated")
    elif name == "high-sinr":
        require(outcomes == {"DECODED": 2} and int(retries["retransmissions"]) == 0,
                f"{name}: two clean DATA transmissions expected")
    elif name == "low-sinr":
        require(outcomes == {PHY_FAILURE: 4} and int(retries["retry_limit_drops"]) == 2,
                f"{name}: both singleton MPDUs should hit retry limit")
        require(int(activity["rx_ns"]) == 0 and int(activity["tx_ns"]) > 0,
                f"{name}: failed DATA still consumes TX but no ACK RX")
    elif name == "start-boundary":
        first = decode[0]
        require(first["ppdu_start_ns"] == "43000" and first["outcome"] == "DECODED"
                and float(first["sinr_db"]) == 30 and outcomes == {"DECODED": 1, PHY_FAILURE: 2},
                f"{name}: exact boundary or in-flight truth change was mishandled")
    elif name.startswith("partial-ampdu"):
        first = [row for row in decode if row["ppdu_start_ns"] == decode[0]["ppdu_start_ns"]]
        first_failures = [row["packet_id"] for row in first if row["outcome"] == PHY_FAILURE]
        retries_actual = [row["packet_id"] for row in decode if row["attempt"] == "1"]
        expected_failed = ["1"] if name.endswith("alt-seed") else ["1", "4"]
        require(len(first) == 4 and first_failures == expected_failed and retries_actual == first_failures,
                f"{name}: partial BA did not retry exactly the failed members")
        require(outcomes["DECODED"] == 4 and int(retries["retry_limit_drops"]) == 0,
                f"{name}: failed members did not recover")
        observation = next(row for row in events(rows, "WIFI_CHANNEL_OBSERVATION")
                           if row["t_ns"] == "43000" and row["subchannel"] == "0")
        require(float(observation["sinr_db"]) == 30 and all(float(row["sinr_db"]) == 10 for row in first),
                f"{name}: AP report and simulator true SINR were not kept distinct")
    elif name == "length-variation":
        probabilities = {int(row["mpdu_bytes"]): float(row["per"]) for row in decode}
        require(set(probabilities) == {230, 830} and probabilities[230] < probabilities[830],
                f"{name}: actual MPDU lengths did not change PER")
    elif name == "all-failed-ampdu":
        require(outcomes == {PHY_FAILURE: 8} and int(retries["retry_limit_drops"]) == 4
                and int(retries["ack_timeouts"]) == 2 and int(activity["rx_ns"]) == 0,
                f"{name}: failed aggregate generated a response or incorrect retry/drop statistics")
    elif name == "disabled-b6-compatibility":
        require(not outcomes and len(events(rows, "WIFI_AMPDU_BA_END")) == 2
                and len(events(rows, "DELIVERED")) == 4,
                f"{name}: disabled model changed the original B6 retry path")


def write_records(path: Path, rows: list[dict[str, str]]) -> None:
    columns = tuple(dict.fromkeys(key for row in rows for key in row))
    write_csv(path, columns, rows)


def verify_rejected_truth(jar: Path, configs: Path) -> None:
    props = configuration("0,350", (("0", "30"),))
    truth = props["wifi.channel.truth"]
    cases = (
        ("reject-nonflat", truth.replace("1,1,3,0,30", "1,1,3,0,20"), "supports only a flat channel"),
        ("reject-missing-truth", ";".join(truth.split(";")[:3]), "Missing channel truth"),
    )
    for name, malformed, expected_error in cases:
        config = configs / f"{name}.properties"
        write_properties(config, {**props, "wifi.channel.truth": malformed})
        try:
            simulate(jar, config)
        except RuntimeError as error:
            require(expected_error in str(error), f"{name}: unexpected Java rejection {error}")
        else:
            raise ValueError(f"{name}: invalid channel truth was silently accepted")
        print(f"{name}: rejected by Java ({expected_error})")


def verify(jar: Path, output: Path) -> None:
    configs = output / "configs"
    configs.mkdir(parents=True, exist_ok=True)
    decoded_rows = []
    retry_rows = []
    timeline_events = {
        "WIFI_MAC_ATTEMPT", "WIFI_MAC_SUCCESS", "WIFI_MAC_RETRY", "WIFI_ACK_TIMEOUT",
        "WIFI_RETRY_LIMIT_DROP", "WIFI_AMPDU_DATA_START", "WIFI_AMPDU_BA_START", "WIFI_AMPDU_BA_END",
        "WIFI_ACK_END", "WIFI_COLLISION_START", "DELIVERED", "WIFI_AP_ACTIVITY_CHANGE",
        "WIFI_CHANNEL_OBSERVATION", QUEUE_EVENT, OBSERVATION_EVENT,
    }
    summaries = {"WIFI_RETRY", "WIFI_COLLISION", "WIFI_AP_ACTIVITY", "WIFI_AP_ENERGY"}
    for name, props in scenarios().items():
        config = configs / f"{name}.properties"
        write_properties(config, props)
        stdout = simulate(jar, config)
        rows = parse_trace(stdout)
        activity = verify_activity(name, rows)
        queue = events(rows, QUEUE_EVENT)
        check_protocol_events(name, rows, queue, events(rows, PPDU_EVENT))
        check_windows(name, props, rows, events(rows, OBSERVATION_EVENT), queue, events(rows, PPDU_EVENT))
        decode = verify_decodes(name, props, rows)
        verify_responses(name, props, rows, decode)
        verify_case(name, rows, decode, activity)
        if name == "partial-ampdu":
            require(simulate(jar, config) == stdout, f"{name}: same-seed Java output is not reproducible")
        if name == "disabled-b6-compatibility":
            baseline = configs / "b6-without-per-properties.properties"
            write_properties(baseline, {key: value for key, value in props.items()
                                        if not key.startswith("wifi.phy.per.") and key != "wifi.channel.truth"})
            require(simulate(jar, baseline) == stdout, f"{name}: switch-off changed original trace")
        decoded_rows.extend(dict(scenario=name, **row) for row in decode)
        for row in rows:
            if row.get("event") not in timeline_events and row.get("summary") not in summaries:
                continue
            if row.get("event") == OBSERVATION_EVENT and row["ac"] != "BE":
                continue
            if row.get("event") == "WIFI_CHANNEL_OBSERVATION" and row["subchannel"] != "0":
                continue
            selected = dict(row)
            if "t_ns" in row or "t_us" in row:
                selected["t_ns"] = str(time_ns(row))
            retry_rows.append(dict(scenario=name, **selected))
        print(f"{name}: {dict(Counter(row['outcome'] for row in decode))}, "
              f"{activity['tx_ns']} ns AP TX, {activity['rx_ns']} ns AP RX")
    verify_rejected_truth(jar, configs)
    write_records(output / "c2b1-mpdu-decode.csv", decoded_rows)
    write_records(output / "c2b1-retry-queue.csv", retry_rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=RESULTS)
    args = parser.parse_args()
    verify(args.jar.resolve(), args.output)


if __name__ == "__main__":
    main()
