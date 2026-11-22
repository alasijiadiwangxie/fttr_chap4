"""从实际 JAR 轨迹核对 B1 静态 AP 能力对照，输出汇总和逐事件时序 CSV。"""

from __future__ import annotations

import argparse
from decimal import Decimal
import hashlib
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv


CASES = {
    "LCM": ("b1-static-ap-lcm.properties", 20, 1, Decimal("86.029412"), Decimal("165.6")),
    "HCM": ("b1-static-ap-hcm.properties", 80, 2, Decimal("720.588235"), Decimal("77.6")),
}
TIMING_COLUMNS = (
    "ap_mode", "event_index", "t_us", "node", "event", "packet_id", "sfu_id", "sta_id",
    "destination_sta_id", "bytes", "type",
)


def fields(line: str) -> dict[str, str]:
    return dict(token.split("=", 1) for token in line.split() if "=" in token)


def event_for(rows: list[dict[str, str]], event: str, packet_id: int) -> dict[str, str]:
    matches = [row for row in rows if row.get("event") == event and row.get("packet_id") == str(packet_id)]
    require(len(matches) == 1, f"Expected one {event} event for packet {packet_id}, found {len(matches)}")
    return matches[0]


def verify_case(jar: Path, mode: str) -> tuple[dict, list[dict]]:
    filename, bandwidth, nss, expected_rate, expected_data = CASES[mode]
    configuration = ROOT / "configs" / filename
    rows = [fields(line) for line in simulate(jar, configuration).splitlines() if line.strip()]
    phy_rows = [row for row in rows if row.get("summary") == "WIFI_STA_PHY"]
    require(len(phy_rows) == 1, f"{mode}: expected one effective STA PHY summary")
    phy = phy_rows[0]
    require(phy["ap_mode"] == mode and phy["sfu_id"] == "1" and phy["sta_id"] == "1",
            f"{mode}: wrong AP mode or association in PHY summary")
    require(int(phy["bandwidth_mhz"]) == bandwidth and int(phy["nss"]) == nss,
            f"{mode}: effective PHY exceeds or misses the expected AP/STA capability intersection")
    rate = Decimal(phy["phy_rate_mbps"])
    require(abs(rate - expected_rate) <= Decimal("0.000001"), f"{mode}: unexpected PHY rate {rate}")

    delivered = [row for row in rows if row.get("event") == "DELIVERED"]
    require(sorted(row["packet_id"] for row in delivered) == ["1", "2"],
            f"{mode}: each of the two original packets must be delivered once")
    data_total = Decimal(0)
    sifs_total = Decimal(0)
    ack_total = Decimal(0)
    previous_ack_end = Decimal(0)
    access_waits = []
    for packet_id in (1, 2):
        start = Decimal(event_for(rows, "WIFI_TX_START", packet_id)["t_us"])
        data_end = Decimal(event_for(rows, "WIFI_DATA_END", packet_id)["t_us"])
        sifs_start = Decimal(event_for(rows, "WIFI_SIFS_START", packet_id)["t_us"])
        ack_start = Decimal(event_for(rows, "WIFI_ACK_START", packet_id)["t_us"])
        ack_end = Decimal(event_for(rows, "WIFI_ACK_END", packet_id)["t_us"])
        released = event_for(rows, "WIFI_TX_END", packet_id)
        arrival = event_for(rows, "DELIVERED", packet_id)
        access_wait = start - previous_ack_end
        require(access_wait >= 34 and (access_wait - 34) % 9 == 0,
                f"{mode}: access wait must contain VO AIFS and a whole number of slots")
        require(data_end - start == expected_data and sifs_start == data_end,
                f"{mode}: DATA duration or SIFS boundary differs from the audited HE SU calculation")
        require(ack_start - data_end == 16 and ack_end - ack_start == 28,
                f"{mode}: unexpected SIFS or ACK duration")
        require(Decimal(released["t_us"]) == ack_end and Decimal(arrival["t_us"]) == data_end,
                f"{mode}: DATA delivery and wireless release boundaries are inconsistent")
        require(Decimal(released["wifi_service_us"]) == expected_data + 44,
                f"{mode}: exchange accounting is inconsistent")
        previous_ack_end = ack_end
        data_total += data_end - start
        sifs_total += ack_start - data_end
        ack_total += ack_end - ack_start
        access_waits.append(access_wait)

    totals = rows[-1]
    require(totals.get("delivered_packets") == "2" and totals.get("delivered_bytes") == "2400",
            f"{mode}: packet or byte conservation failed")
    busy = Decimal(totals["wifi_busy_us"])
    window = Decimal(totals["simulation_end_us"])
    require(busy == 2 * (expected_data + 44) and window == previous_ack_end
            and Decimal(totals["wifi_idle_us"]) == sum(access_waits),
            f"{mode}: unexpected channel busy union or observation window")
    global_delay = next(row for row in rows if row.get("summary") == "GLOBAL")
    delivered_packets = int(totals["delivered_packets"])
    delivered_bytes = int(totals["delivered_bytes"])
    comparison = dict(
        ap_mode=mode, configuration=f"configs/{filename}", bandwidth_mhz=bandwidth, nss=nss,
        mcs=7, gi_us="0.8", payload_bytes=1200, delivered_packets=delivered_packets, delivered_bytes=delivered_bytes,
        phy_rate_mbps=rate, data_ppdu_us=data_total / delivered_packets,
        sifs_us=sifs_total / delivered_packets, ack_ppdu_us=ack_total / delivered_packets,
        exchange_us=busy / delivered_packets, total_data_us=data_total, wifi_busy_us=busy,
        first_access_wait_us=access_waits[0], second_access_wait_us=access_waits[1],
        wifi_idle_us=totals["wifi_idle_us"],
        simulation_end_us=window, avg_e2e_delay_us=global_delay["avg_e2e_delay_us"],
        payload_throughput_mbps=Decimal(delivered_bytes * 8) / window,
        jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        configuration_sha256=hashlib.sha256(configuration.read_bytes()).hexdigest(),
    )
    timeline = [dict(ap_mode=mode, event_index=index, **{key: row.get(key, "") for key in TIMING_COLUMNS[2:]})
                for index, row in enumerate((row for row in rows if "event" in row and "t_us" in row), start=1)]
    return comparison, timeline


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=ROOT / "results/b1-static-ap")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the simulation JAR first with mvn -o clean package")
    comparisons = []
    timeline = []
    for mode in CASES:
        comparison, events = verify_case(args.jar.resolve(), mode)
        comparisons.append(comparison)
        timeline.extend(events)
        print(f"{mode}: DATA {comparison['data_ppdu_us']} us, exchange {comparison['exchange_us']} us, "
              f"two packets delivered in {comparison['simulation_end_us']} us")
    require(all(comparisons[0][key] == comparisons[1][key]
                for key in ("first_access_wait_us", "second_access_wait_us")),
            "Static AP mode changed the paired EDCA access sequence")
    args.output.mkdir(parents=True, exist_ok=True)
    write_csv(args.output / "comparison.csv", tuple(comparisons[0]), comparisons)
    write_csv(args.output / "event_timing.csv", TIMING_COLUMNS, timeline)
    print(f"Verified comparison written to {args.output.resolve()}")


if __name__ == "__main__":
    main()
