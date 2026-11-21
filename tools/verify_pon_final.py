"""Verify final PON fixtures and compare deterministic Java event output."""

from __future__ import annotations

import argparse
from collections import Counter
import csv
import hashlib
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/fttr-chap4-0.1.0-SNAPSHOT.jar"
BASE = ROOT / "configs/pon-final-dba.properties"
SMOKE = ROOT / "results/pon-final/smoke-summary.csv"
MODE_KEY = "fiber.uplink.dba.mode"
TCONT_PREFIX = "fiber.uplink.tcont."
KINDS = (
    "PON_DOWNLINK_ENQUEUE", "PON_UPLINK_ENQUEUE", "TCONT_ENQUEUE", "TCONT_REPORT_RX", "PON_BWMAP_TX",
    "PON_DBA_SHADOW", "PON_DBA_BWMAP_TX", "PON_DBA_FILL",
    "PON_DBA_SETTLEMENT_RX", "PON_POLL_TX_START", "PON_GIANT_DECISION",
)


def variant(mode: str, directory: Path) -> Path:
    settings = dict(line.split("=", 1) for line in BASE.read_text(encoding="utf-8").splitlines()
                    if line and not line.startswith("#"))
    settings[MODE_KEY] = mode
    if mode in {"fcfs", "shadow-rr"}:
        settings = {key: value for key, value in settings.items() if not key.startswith(TCONT_PREFIX)}
    if mode == "giant":
        # Classic GIANT accepts Types 2–4. Preserve the wireless class mapping.
        first = TCONT_PREFIX + "alloc.101."
        second = TCONT_PREFIX + "alloc.201."
        for prefix, kind in ((first, "2"), (second, "3")):
            for key in ("si.fixed.frames", "ab.fixed.bytes", "si.be.frames", "ab.be.bytes"):
                settings.pop(prefix + key, None)
            settings[prefix + "type"] = kind
        settings[first + "si.max.frames"] = "1"
        settings[first + "ab.min.bytes"] = "1200"
    path = directory / f"{mode}.properties"
    path.write_text("".join(f"{key}={value}\n" for key, value in settings.items()), encoding="utf-8")
    return path


def simulate(jar: Path, name: str, config: Path | None, dl: int, ul: int, required: str = "") -> dict:
    args = ["java", "-jar", str(jar)] + ([str(config)] if config else [])
    run = subprocess.run(args, capture_output=True, check=False)
    stdout, stderr = (data.replace(b"\r\n", b"\n") for data in (run.stdout, run.stderr))
    if name == "infeasible-sla":
        if run.returncode != 2 or b"GIANT SLA infeasible" not in stderr or stdout:
            raise ValueError(f"{name}: expected rejection, got {run.returncode} {stderr!r}")
    elif run.returncode or stderr:
        raise ValueError(f"{name}: {run.returncode} {stderr!r}")
    delivered = {}
    event_counts = Counter()
    for line in stdout.decode("utf-8").splitlines():
        row = dict(token.split("=", 1) for token in line.split() if "=" in token)
        event = row.get("event")
        if event is None:
            continue
        event_counts[event] += 1
        if event == "DELIVERED":
            packet = int(row["packet_id"])
            if packet in delivered:
                raise ValueError(f"{name}: repeated delivery of packet {packet}")
            delivered[packet] = row["node"]
    if (set(delivered) != set(range(1, dl + ul + 1))
            or Counter(delivered.values()) != Counter({node: count for node, count in (("STA", dl), ("MFU", ul)) if count})):
        raise ValueError(f"{name}: expected {dl}/{ul} DL/UL deliveries, got {delivered}")
    if required and not event_counts[required]:
        raise ValueError(f"{name}: missing {required}")
    if event_counts["PON_DBA_BWMAP_TX"] != event_counts["PON_DBA_SETTLEMENT_RX"]:
        raise ValueError(f"{name}: DATA grants and settlements differ")
    return dict(scenario=name, exit_code=run.returncode, delivered_dl=dl, delivered_ul=ul,
                **{kind.lower(): event_counts[kind] for kind in KINDS},
                stdout_sha256=hashlib.sha256(stdout).hexdigest(), stderr_sha256=hashlib.sha256(stderr).hexdigest())


def run_cases(jar: Path) -> list[dict]:
    cases = [simulate(jar, "default-fixed", None, 1, 0)]
    cases.append(simulate(jar, "shared-bidirectional", ROOT / "configs/pon-shared-uplink.properties", 1, 2,
                          "PON_UPLINK_ENQUEUE"))
    with TemporaryDirectory() as temporary:
        directory = Path(temporary)
        for mode, event in (
            ("fcfs", "PON_BWMAP_TX"), ("shadow-rr", "PON_DBA_SHADOW"), ("rr", "PON_DBA_FILL"),
            ("rr-multi", "PON_DBA_FILL"), ("giant", "PON_GIANT_DECISION"), ("giant-complete", "PON_GIANT_DECISION"),
        ):
            config = BASE if mode == "giant-complete" else variant(mode, directory)
            cases.append(simulate(jar, mode, config, 2, 10, event))
    cases.append(simulate(jar, "infeasible-sla",
                          ROOT / "results/pon-giant-completion/configs/infeasible-sla.properties", 0, 0))
    return cases


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--summary", type=Path, default=SMOKE)
    parser.add_argument("--record", action="store_true", help="Save the reference trace fingerprints")
    args = parser.parse_args()
    rows = run_cases(args.jar)
    if args.record:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        with args.summary.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=tuple(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    else:
        with args.summary.open(newline="", encoding="utf-8") as stream:
            expected = list(csv.DictReader(stream))
        if expected != [{name: str(value) for name, value in row.items()} for row in rows]:
            differences = [row["scenario"] for row, before in zip(rows, expected)
                           if {key: str(value) for key, value in row.items()} != before]
            raise ValueError(f"PON fingerprint mismatch: {differences or 'case count/order'}")
    print(f"{len(rows)} PON fixture results {'recorded' if args.record else 'matched'}")


if __name__ == "__main__":
    main()
