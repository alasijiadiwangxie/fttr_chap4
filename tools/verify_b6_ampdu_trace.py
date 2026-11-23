"""Verify the actual partial Block ACK event sequence and export its event ledger."""

from __future__ import annotations

import argparse
from decimal import Decimal
from pathlib import Path

from pon_experiment_io import ROOT, require, simulate, write_csv


CONFIG = ROOT / "configs/b6-ampdu-partial-ba.properties"
RESULTS = ROOT / "results/b6-ampdu"
COLUMNS = ("sequence", "t_ns", "node", "event", "sfu_id", "sta_id", "packet_id", "head_id", "type", "direction",
           "count", "apep_bytes", "ppdu_ns", "sifs_ns", "ba_ns", "packet_ids", "bitmap", "status", "retry_count",
           "ap_mode", "from", "to", "reason")


def verify(jar: Path, output: Path) -> None:
    rows = [dict(item.split("=", 1) for item in line.split() if "=" in item)
            for line in simulate(jar, CONFIG).splitlines()]

    def events(kind: str) -> list[dict]:
        return [row for row in rows if row.get("event") == kind]

    start, end, response_start, response_end = (
        events(f"WIFI_AMPDU_{phase}") for phase in ("DATA_START", "DATA_END", "BA_START", "BA_END"))
    require(all(len(seq) == 2 for seq in (start, end, response_start, response_end)), "Expected two aggregate exchanges")
    for begin, data, ba, finish, expected_ids, bits, bytes_ in zip(
            start, end, response_start, response_end, ("1,2,3,4", "2,4"), ("5", "3"), (6142, 3070)):
        require(begin["packet_ids"] == expected_ids and int(begin["apep_bytes"]) == bytes_, "Unexpected FIFO packing")
        require(finish["bitmap"] == bits, "Unexpected Block ACK bitmap")
        require(int(data["t_ns"]) - int(begin["t_ns"]) == int(begin["ppdu_ns"])
                and int(ba["t_ns"]) - int(data["t_ns"]) == 16_000
                and int(finish["t_ns"]) - int(ba["t_ns"]) == 32_000, "Incorrect DATA/SIFS/BA boundaries")
    require(int(start[0]["t_ns"]) == 43_000 and int(start[1]["t_ns"]) - int(response_end[0]["t_ns"]) == 43_000,
            "BE AIFS must be paid for each PPDU")
    require([row["packet_id"] for row in events("DELIVERED")] == ["1", "3", "2", "4"],
            "Partial Block ACK did not deliver each packet once")
    mpdu = events("WIFI_AMPDU_MPDU")
    for status in ("NACK", "RETRY"):
        require([row["packet_id"] for row in mpdu if row["status"] == status] == ["2", "4"], "Incorrect retry selection")
    activity = next(row for row in rows if row.get("summary") == "WIFI_AP_ACTIVITY")
    energy = next(row for row in rows if row.get("summary") == "WIFI_AP_ENERGY")
    require(int(activity["tx_ns"]) == sum(int(row["ppdu_ns"]) for row in start)
            and int(activity["rx_ns"]) == 64_000, "AP time was counted by MPDU rather than by airtime")
    require(sum(int(activity[f"{state}_ns"]) for state in ("idle", "tx", "rx")) == 1_000_000, "Window not conserved")
    for state in ("idle", "tx", "rx"):
        calculated = Decimal(energy[f"{state}_power_w"]) * Decimal(activity[f"{state}_ns"]).scaleb(-3)
        require(calculated == Decimal(energy[f"{state}_energy_uj"]), "B3 power integral mismatch")

    trace = []
    for row in rows:
        event = row.get("event", "")
        if event.startswith("WIFI_AMPDU_") or event in ("DELIVERED", "WIFI_AP_ACTIVITY_CHANGE", "WIFI_MAC_RETRY"):
            trace.append(dict(row, sequence=len(trace) + 1, t_ns=int(Decimal(row["t_us"]) * 1000)))
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "ampdu-event-trace.csv", COLUMNS, [{field: row.get(field, "") for field in COLUMNS} for row in trace])
    print(f"Verified 2 PPDUs, 4 unique deliveries and AP energy {energy['total_energy_uj']} uJ")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--output", type=Path, default=RESULTS)
    args = parser.parse_args()
    verify(args.jar.resolve(), args.output)
