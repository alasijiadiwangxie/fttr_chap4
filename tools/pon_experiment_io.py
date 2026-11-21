"""Shared file and simulator utilities for PON experiments."""

from __future__ import annotations

import csv
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def simulate(jar: Path, configuration: Path) -> str:
    result = subprocess.run(
        ["java", "-jar", str(jar), str(configuration)], capture_output=True, encoding="utf-8", check=False
    )
    if result.returncode != 0:
        raise RuntimeError(f"Simulation exited with code {result.returncode}: {result.stderr.strip()}")
    return result.stdout


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def write_csv(path: Path, columns: tuple[str, ...], rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
