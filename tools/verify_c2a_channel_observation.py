"""Run the Java C2a fixtures, check observability, and export three CSV traces."""

from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv


RESULTS = ROOT / "results/c2a-channel-observation"
CHANNEL_PREFIX = "WIFI_CHANNEL_"
ENABLED = "wifi.channel.observation.enabled"
TRUTH = (
    (1, 1, 0, "0", "28.5", 12, "false"),
    (1, 1, 0, "100.250", "6.5", 3, "true"),
    (1, 1, 0, "270", "24", 10, "false"),
    (1, 1, 1, "0", "19", 9, "false"),
    (1, 1, 1, "150", "4", 1, "true"),
    (1, 1, 1, "250", "17", 8, "false"),
    (1, 1, 2, "0", "13", 5, "true"),
    (1, 1, 3, "0", "-2", -1, "true"),
    (1, 2, 0, "0", "31", 14, "false"),
    (1, 2, 1, "0", "10", 4, "true"),
    (1, 2, 2, "0", "16", 7, "false"),
    (1, 2, 3, "600", "21", 9, "false"),
    (2, 3, 0, "0", "16", 6, "true"),
    (2, 3, 1, "0", "30", 14, "false"),
    (2, 3, 2, "0", "9", 4, "true"),
    (2, 3, 3, "0", "18", 8, "false"),
)
FEEDBACK = (
    (1, 1, 0, "40", "120", "300"),
    (1, 1, 0, "100.250", "100", "150"),
    (1, 1, 0, "90", "125", "300"),
    (1, 1, 0, "95", "200", "400"),
    (1, 1, 0, "270", "10", "5"),
    (1, 1, 0, "300", "0", "100"),
    (1, 1, 1, "150", "20", "75"),
    (1, 1, 2, "0", "25", "150"),
    (1, 1, 3, "0", "25", "160"),
    (1, 2, 0, "50", "10", "130"),
    (1, 2, 1, "50", "10", "150"),
    (1, 2, 3, "50", "10", "150"),
    (1, 2, 3, "620", "40", "250"),
    (2, 3, 0, "10", "0", "80"),
    (2, 3, 1, "10", "60", "130"),
    (2, 3, 2, "25", "0", "175"),
    (2, 3, 3, "50", "0", "200"),
    (1, 2, 2, "4500", "100", "200"),
)
READ_TIMES = (
    "0", "9.999", "10", "24.999", "25", "59.999", "60", "69.999", "70", "89.999", "90",
    "100.249", "100.250", "149.999", "150", "159.999", "160", "169.999", "170", "179.999", "180",
    "200", "200.249", "200.250", "215", "224.999", "225", "250.249", "250.250", "260",
    "280", "295", "299.999", "300", "399.999", "400", "659.999", "660", "869.999", "870", "4600", "4700",
)
LINKS = ((1, 1), (1, 2), (2, 3))


def protocol_properties(first_mode: str, second_mode: str) -> dict[str, str]:
    # Two stations share the first AP. The first DL A-MPDU has two intentional failures.
    packets = [
        (0, "DL", "BE", 1), (0, "DL", "BE", 1), (0, "DL", "BE", 1), (0, "DL", "BE", 1),
        (0, "UL", "VO", 3), (0, "UL", "VO", 3), (100, "DL", "VI", 2),
        (300, "UL", "VI", 2), (400, "DL", "BK", 3), (950, "DL", "BE", 2),
    ]
    settings = {
        "traffic.mode": "manual",
        "traffic.direction": "BOTH",
        "sfu.count": "2",
        "sta.count": "3",
        "sta.1.sfu.id": "1",
        "sta.2.sfu.id": "1",
        "sta.3.sfu.id": "2",
        "fiber.delay.us": "10",
        "packet.arrival.us": ",".join(str(packet[0]) for packet in packets),
        "packet.bytes": "1500",
        "wifi.model": "he-su",
        "wifi.mac.access": "edca-multi-ac",
        "wifi.mac.exchange": "data-sifs-ack",
        "wifi.mac.ack.rate.mbps": "24",
        "wifi.mac.edca.seed": "101",
        "wifi.mac.edca.BE.aifsn": "3",
        "wifi.mac.edca.BE.cwmin": "0",
        "wifi.mac.ampdu.enabled": "true",
        "wifi.mac.ampdu.max-mpdus": "4",
        "wifi.mac.ampdu.first-attempt-error.packet.ids": "2,4",
        "sfu.1.wifi.ap.mode": first_mode,
        "sfu.2.wifi.ap.mode": second_mode,
        "wifi.ap.activity.enabled": "true",
        "wifi.ap.activity.window.us": "3000",
        "wifi.ap.energy.enabled": "true",
        "wifi.ap.observation.enabled": "true",
        "wifi.ap.observation.period.us": "100",
    }
    for index, (_, direction, ac, sta_id) in enumerate(packets, 1):
        settings[f"packet.{index}.direction"] = direction
        settings[f"packet.{index}.type"] = ac
        settings[f"packet.{index}.sta.id"] = str(sta_id)
    return settings


def write_properties(path: Path, values: dict[str, str]) -> None:
    path.write_text("".join(f"{name}={value}\n" for name, value in values.items()), encoding="utf-8")


def trace_fields(line: str) -> dict[str, str]:
    return dict(field.split("=", 1) for field in line.split() if "=" in field)


def serialize(entries: tuple) -> str:
    return ";".join(",".join(map(str, entry)) for entry in entries)


def ns(us: str) -> int:
    return int(Decimal(us) * 1000)


def row_time(row: dict[str, str]) -> int:
    return int(row["t_ns"]) if "t_ns" in row else ns(row["t_us"])


def link(row: dict[str, str]) -> tuple[int, int, int]:
    return int(row["sfu_id"]), int(row["sta_id"]), int(row["subchannel"])


def quality(row: dict[str, str]) -> tuple[Decimal, int]:
    return Decimal(row["sinr_db"]), int(row["cqi"])


def verify_truth(rows: list[dict[str, str]]) -> None:
    expected = [(ns(at), (sfu, sta, subchannel), Decimal(sinr), cqi, interfered)
                for sfu, sta, subchannel, at, sinr, cqi, interfered in TRUTH]
    actual = [(row_time(row), link(row), *quality(row), row["interfered"]) for row in rows]
    require(Counter(actual) == Counter(expected), "Truth trace differs from the configured subchannel timelines")
    require([row_time(row) for row in rows] == sorted(row_time(row) for row in rows), "Truth rows are out of order")


def expected_feedback() -> tuple[list[tuple], dict[tuple, list[tuple]]]:
    phases = []
    deliveries = []
    for report_id, (sfu, sta, subchannel, measured, delay, validity) in enumerate(FEEDBACK, 1):
        key = sfu, sta, subchannel
        measured_ns = ns(measured)
        arrival_ns = measured_ns + ns(delay)
        expires_ns = measured_ns + ns(validity)
        stamps = measured_ns, arrival_ns, expires_ns
        known_truth = sorted((ns(at), Decimal(sinr), cqi)
                             for a, b, c, at, sinr, cqi, _ in TRUTH
                             if (a, b, c) == key and ns(at) <= measured_ns)
        if not known_truth:
            phases.append((measured_ns, report_id, key, "NO_TRUTH", stamps, None))
            continue
        signal = known_truth[-1][1:]
        phases.append((measured_ns, report_id, key, "MEASURED", stamps, signal))
        deliveries.append((arrival_ns, report_id, key, stamps, signal))
    accepted = {}
    for arrival_ns, report_id, key, stamps, signal in sorted(deliveries):
        history = accepted.setdefault(key, [])
        measured_ns, _, expires_ns = stamps
        if arrival_ns >= expires_ns:
            decision = "EXPIRED"
        elif history and measured_ns <= history[-1][0][0]:
            decision = "STALE"
        else:
            decision = "ACCEPTED"
            history.append((stamps, signal))
        phases.append((arrival_ns, report_id, key, decision, stamps, signal))
    return phases, accepted


def verify_feedback(rows: list[dict[str, str]]) -> dict[tuple, list[tuple]]:
    phases, reports = expected_feedback()
    actual = []
    measurements = set()
    for row in rows:
        report_id = int(row["report_id"])
        measured = int(row["measured_ns"]), int(row["arrival_ns"]), int(row["expires_ns"])
        phase = row["phase"]
        if phase == "MEASURED":
            measurements.add(report_id)
        elif phase != "NO_TRUTH":
            require(report_id in measurements, f"Report {report_id} arrived before measurement")
        signal = None if phase == "NO_TRUTH" else quality(row)
        if signal is None:
            require(row["sinr_db"] == row["cqi"] == "-", "Missing truth must not turn into a numeric report")
        actual.append((row_time(row), report_id, link(row), phase, measured, signal))
    require(Counter(actual) == Counter(phases), "Feedback state, timing, or measurement differs from independent replay")
    return reports


def expected_local(original: list[dict[str, str]]) -> Counter:
    evidence = Counter()
    for row in original:
        event = row.get("event")
        direction = row.get("direction", "DL")
        if event == "WIFI_DATA_END" and direction == "UL":
            evidence[("UL_DATA", row_time(row), int(row["sfu_id"]), int(row["source_sta_id"]), 1)] += 1
        elif event == "WIFI_AMPDU_DATA_END" and direction == "UL" and int(row["bitmap"], 16):
            decoded = bin(int(row["bitmap"], 16)).count("1")
            evidence[("UL_DATA", row_time(row), int(row["sfu_id"]), int(row["sta_id"]), decoded)] += 1
        elif event == "WIFI_ACK_END" and direction == "DL":
            evidence[("DL_ACK", row_time(row), int(row["sfu_id"]), int(row["destination_sta_id"]), 1)] += 1
        elif event == "WIFI_AMPDU_BA_END" and direction == "DL":
            confirmed = bin(int(row["bitmap"], 16)).count("1")
            evidence[("DL_BLOCK_ACK", row_time(row), int(row["sfu_id"]), int(row["sta_id"]), confirmed)] += 1
    return evidence


def verify_local(rows: list[dict[str, str]], original: list[dict[str, str]]) -> list[tuple]:
    local = [(row["kind"], row_time(row), int(row["sfu_id"]), int(row["sta_id"]), int(row["mpdus"]))
             for row in rows]
    require(Counter(local) == expected_local(original), "Local evidence does not match actual DATA/ACK/Block ACK")
    return local


def verify_observations(rows: list[dict[str, str]], modes: tuple[str, str], reports: dict[tuple, list[tuple]],
                        local: list[tuple]) -> None:
    expected_queries = Counter((ns(at), (sfu, sta, subchannel)) for at in READ_TIMES
                               for sfu, sta in LINKS for subchannel in range(4))
    require(Counter((row_time(row), link(row)) for row in rows) == expected_queries,
            "Unexpected or missing AP subchannel observations")
    for row in rows:
        at = row_time(row)
        key = link(row)
        sfu, sta, subchannel = key
        received = [(stamps, signal) for stamps, signal in reports.get(key, ()) if stamps[1] <= at]
        if modes[sfu - 1] == "LCM" and subchannel != 0:
            expected_status = "MODE_LIMIT"
        elif not received:
            expected_status = "UNKNOWN"
        else:
            expected_status = "VISIBLE" if at < received[-1][0][2] else "EXPIRED"
        require(row["status"] == expected_status, f"Incorrect AP-visible status for {key} at {at}: {row}")
        stamp_columns = ("measured_ns", "arrival_ns", "expires_ns")
        if expected_status == "VISIBLE":
            stamps, signal = received[-1]
            require(tuple(int(row[field]) for field in stamp_columns) == stamps and quality(row) == signal,
                    f"AP received a non-causal or stale value for {key} at {at}")
        else:
            require(all(row[field] == "-" for field in (*stamp_columns, "sinr_db", "cqi")),
                    f"An unavailable observation exposed report values for {key} at {at}")
        for kind, field in (("UL_DATA", "local_ul_data_ns"), ("DL_ACK", "local_dl_ack_ns"),
                            ("DL_BLOCK_ACK", "local_dl_ba_ns")):
            observed = [time for action, time, sfu_id, sta_id, _ in local
                        if action == kind and (sfu_id, sta_id) == (sfu, sta) and time <= at]
            expected = str(max(observed)) if observed else "-"
            require(row[field] == expected, f"Non-causal or mixed-link {kind} evidence for {key} at {at}")


def save_csv(path: Path, rows: list[dict[str, str]]) -> None:
    require(bool(rows), f"No data to export to {path.name}")
    columns = tuple(dict.fromkeys(key for row in rows for key in row))
    write_csv(path, columns, rows)


def verify(jar: Path, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    configs = output / "configs"
    configs.mkdir(exist_ok=True)
    truth_rows = []
    observation_rows = []
    feedback_rows = []
    for name, modes in (("hcm-lcm", ("HCM", "LCM")), ("lcm-hcm", ("LCM", "HCM"))):
        enabled = configs / f"{name}.properties"
        disabled = configs / f"{name}-observation-off.properties"
        properties = {
            **protocol_properties(*modes),
            "wifi.channel.truth": serialize(TRUTH),
            "wifi.channel.feedback": serialize(FEEDBACK),
            "wifi.channel.observe.us": ",".join(READ_TIMES),
        }
        write_properties(enabled, {**properties, ENABLED: "true"})
        write_properties(disabled, {**properties, ENABLED: "false"})
        baseline = simulate(jar, disabled).splitlines()
        traced = simulate(jar, enabled).splitlines()
        probe = []
        originals = []
        for sequence, line in enumerate(traced, 1):
            row = trace_fields(line)
            if row.get("event", "").startswith(CHANNEL_PREFIX):
                probe.append({"scenario": name, "sequence": sequence, **row})
            else:
                originals.append(line)
        require(baseline == originals, f"{name}: observation altered original protocol/summary output")
        original_rows = list(map(trace_fields, originals))
        require(any(row.get("summary") == "WIFI_AP_ENERGY" for row in original_rows)
                and any(row.get("event") == "WIFI_AP_OBSERVATION" for row in original_rows)
                and any(row.get("event") == "WIFI_MAC_RETRY" for row in original_rows)
                and any(row.get("event") == "WIFI_AMPDU_BA_END" for row in original_rows),
                f"{name}: fixture did not exercise original C1/B3 and B6 paths")

        require([row_time(row) for row in probe] == sorted(row_time(row) for row in probe),
                f"{name}: C2a trace time ran backwards")

        def rows_of(suffix: str) -> list[dict[str, str]]:
            return [row for row in probe if row["event"] == CHANNEL_PREFIX + suffix]

        truth = rows_of("TRUTH")
        feedback = rows_of("FEEDBACK")
        observations = rows_of("OBSERVATION")
        verify_truth(truth)
        reports = verify_feedback(feedback)
        local = verify_local(rows_of("LOCAL"), original_rows)
        verify_observations(observations, modes, reports, local)
        truth_rows.extend(truth)
        feedback_rows.extend(feedback)
        observation_rows.extend(observations)
        print(f"{name}: {len(truth)} truth, {len(feedback)} feedback, {len(observations)} observations, "
              f"{len(local)} local events, {len(originals)} unchanged protocol/summary lines")

    save_csv(output / "c2a-channel-truth.csv", truth_rows)
    save_csv(output / "c2a-ap-observations.csv", observation_rows)
    save_csv(output / "c2a-feedback-events.csv", feedback_rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=RESULTS)
    args = parser.parse_args()
    verify(args.jar.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
