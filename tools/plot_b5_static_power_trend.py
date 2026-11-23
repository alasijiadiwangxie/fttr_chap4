"""Draw a 0–80 Mb/s frame around the measured static AP throughput curves."""

from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path

from plot_b5_energy_load import Axis, MODES, mode_style
from plot_b5_paper_replication import Panel
from plot_pon_qos_special import INK, MUTED, Svg
from pon_experiment_io import ROOT, require


RESULTS = ROOT / "results/b5-static-power-trend"
TITLE = "Average AP power vs. delivered throughput"
THROUGHPUT_AXIS = Axis(0, 80, tuple(range(0, 81, 10)))
POWER_AXIS = Axis(6.5, 11, (6.5, 7, 8, 9, 10, 11))
PANELS = (
    ("B3 default powers", "avg_power_b3_w",
     "LCM  7.38 / 9.20 / 8.20 W", "HCM  7.62 / 10.50 / 8.70 W"),
    ("Paper currents × 12 V", "avg_power_paper_w",
     "LCM  6.96 / 12.96 / 7.92 W", "HCM  7.92 / 15.00 / 12.00 W"),
)


def draw_legend(svg: Svg) -> None:
    for x, mode in ((44, "LCM"), (207, "HCM")):
        color = MODES[mode]
        svg.line(x, 119, x + 34, 119, color, 2.2, "7 4" if mode == "HCM" else "")
        mode_style(svg, x + 17, 119, mode)
        svg.txt(x + 44, 124, f"Always-{mode}", 14, INK, 700)
    svg.txt(397, 123, "Markers = three-seed means · Blue band = LCM after crossover", 13, MUTED)

def crossover(rows: list[dict[str, str]], field: str) -> tuple[float, float] | None:
    """Bracket the crossing with matched-load observations delivering the same throughput."""
    by_mode = {mode: {row["interval_us"]: row for row in rows if row["mode"] == mode} for mode in MODES}
    matched = []
    for interval, lcm in by_mode["LCM"].items():
        hcm = by_mode["HCM"].get(interval)
        if hcm is None or not math.isclose(float(lcm["throughput_mbps"]), float(hcm["throughput_mbps"]),
                                           rel_tol=0, abs_tol=1e-9):
            continue
        power = float(lcm[field])
        matched.append((float(lcm["throughput_mbps"]), power, float(hcm[field]) - power))
    matched.sort()
    for (x0, y0, gap0), (x1, y1, gap1) in zip(matched, matched[1:]):
        if x1 > x0 and gap0 > 0 >= gap1:
            fraction = gap0 / (gap0 - gap1)
            return x0 + fraction * (x1 - x0), y0 + fraction * (y1 - y0)
    return None


def draw_panel(panel: Panel, rows: list[dict[str, str]], name: str, field: str, maximum: dict[str, float]) -> None:
    svg = panel.svg
    cap = max(maximum.values())
    start = panel.x(cap)
    shade = "#F0F4F8"
    svg.rect(start, panel.top, panel.right - start, panel.bottom - panel.top, shade)
    crossing = crossover(rows, field)
    lcm_ends = sorted((float(row["throughput_mbps"]), float(row[field]))
                      for row in rows if row["mode"] == "LCM")
    if crossing:
        left = panel.x(crossing[0])
        svg.rect(left, panel.top, panel.x(maximum["LCM"]) - left, panel.bottom - panel.top, "#E4F2F8")
    svg.txt(panel.left, 165, name, 17, INK, 700)
    panel.axes("Average AP power (W)")
    svg.txt((start + panel.right) / 2, panel.top + 31, "No observations", 12, MUTED, anchor="middle")
    for mode, color in MODES.items():
        limit = panel.x(maximum[mode])
        svg.line(limit, panel.top + 1, limit, panel.bottom, color, 1, "3 5", 0.46)
        points = sorted((float(row["throughput_mbps"]), float(row[field]))
                        for row in rows if row["mode"] == mode)
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            svg.line(panel.x(x0), panel.y(y0), panel.x(x1), panel.y(y1), color, 2.3,
                     "7 4" if mode == "HCM" else "")
        for x, y in points:
            mode_style(svg, panel.x(x), panel.y(y), mode)
    if crossing:
        color = MODES["LCM"]
        tail = [crossing, *(point for point in lcm_ends if point[0] > crossing[0])]
        for (x0, y0), (x1, y1) in zip(tail, tail[1:]):
            svg.line(panel.x(x0), panel.y(y0), panel.x(x1), panel.y(y1), color, 3.4)
        svg.circ(panel.x(crossing[0]), panel.y(crossing[1]), 4.3, "white", color, 1.7)
        svg.circ(panel.x(lcm_ends[-1][0]), panel.y(lcm_ends[-1][1]), 4.3, color, "white", 1.2)
        svg.txt(panel.left, 626,
                f"LCM after crossover: {crossing[0]:.2f}–{maximum['LCM']:.2f} Mb/s  ·  "
                f"{crossing[1]:.2f} to {lcm_ends[-1][1]:.2f} W", 13, color, 700)


def plot(output: Path) -> Path:
    with (output / "power-throughput.csv").open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    maximum = {mode: max(float(row["throughput_mbps"]) for row in rows if row["mode"] == mode) for mode in MODES}
    require(max(maximum.values()) < 80, "Expected both measured throughput limits below 80 Mb/s")
    svg = Svg(1180, 678, TITLE, "Static LCM and HCM observations under two sets of state powers.")
    svg.rect(39, 28, 42, 3, MODES["LCM"])
    svg.txt(39, 66, TITLE, 30, INK, 700)
    svg.txt(39, 94, "1 AP · 1 STA · 1500 B periodic downlink · HE SU MCS 7, GI 0.8 μs · 15 s per run", 14, MUTED)
    draw_legend(svg)
    for (name, field, lcm, hcm), left in zip(PANELS, (97, 670)):
        panel = Panel(svg, THROUGHPUT_AXIS, POWER_AXIS, left, 209, left + 455, 495)
        draw_panel(panel, rows, name, field, maximum)
        svg.txt(left, 572, "State powers (IDLE / TX / RX)", 12, MUTED, 700)
        svg.txt(left, 594, lcm, 13, MODES["LCM"], 700)
        svg.txt(left + 207, 594, hcm, 13, MODES["HCM"], 700)
    svg.txt(39, 655, f"Crossovers use interpolation between equal-throughput observations. "
                    f"Measured limits: LCM {maximum['LCM']:.2f} Mb/s · HCM {maximum['HCM']:.2f} Mb/s.", 13, MUTED)
    path = output / "power-vs-throughput-0-80.svg"
    svg.save(path)
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS)
    print(plot(parser.parse_args().input))
