"""Render the PON freeze overview from the archived comparative experiment CSVs."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
from statistics import mean

from plot_pon_qos_special import GIANT, GRID, INK, MUTED, POLICIES, ROOT, RR, Metric, Point, Svg, axis_top, tick


RESULTS = ROOT / "results"
SEEDS = {101, 202, 303}
POLICY_LABELS = {GIANT: "giant-complete", RR: "rr-multi"}


def rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as source:
        return list(csv.DictReader(source))


def summarize(samples: list[dict[str, str]], field: str, scale: float) -> Point:
    if {int(row["seed"]) for row in samples} != SEEDS or len(samples) != len(SEEDS):
        raise ValueError(f"Expected one row per matched seed for {field}")
    values = [float(row[field]) * scale for row in samples]
    return Point(mean(values), min(values), max(values), False)


def read_points(results: Path) -> tuple[list[Point], list[Point], list[Point], Point, list[Point]]:
    link = rows(results / "pon-multi-sfu-overload" / "link-throughput-utilization.csv")
    isolated = rows(results / "pon-multi-sfu-overload" / "isolation-delta.csv")
    budget = rows(results / "pon-t2-delay" / "metrics.csv")
    timing = rows(results / "pon-t2-tail" / "si-metrics.csv")
    throughput = [
        summarize([row for row in link if row["scenario"] == "t4-only-input" and row["scope"] == "link"
                   and int(row["t4_mbps"]) == 8000 and int(row["sfu_count"]) == sfu and row["policy"] == policy],
                  "throughput_mbps", 1e-3)
        for sfu in (4, 8) for policy in (GIANT, RR)
    ]
    tail_delta = [
        summarize([row for row in isolated if int(row["sfu_count"]) == 8 and int(row["t4_mbps"]) == 8000
                   and int(row["tcont_type"]) == kind and row["policy"] == policy], "delta_fifo_p99_ns", 1e-6)
        for kind in (1, 2, 3) for policy in (GIANT, RR)
    ]
    t2_budget = [row for row in budget if row["scope"] == "type" and row["tcont_type"] == "2"]
    sweep = [
        summarize([row for row in t2_budget if row["policy"] == GIANT and int(row["abmin_t2_bytes"]) == amount],
                  "fifo_mean_ns", 1e-6)
        for amount in (2400, 3600, 4800)
    ]
    baseline = summarize([row for row in t2_budget if row["policy"] == RR], "fifo_mean_ns", 1e-6)
    comparison = [
        summarize([row for row in timing if row["scope"] == "type" and row["tcont_type"] == "2"
                   and row["variant"] == variant], "fifo_p99_ns", 1e-6)
        for variant in ("rr", "giant-si1", "giant-si2", "giant-poll4")
    ]
    return throughput, tail_delta, sweep, baseline, comparison


def panel(svg: Svg, x: float, y: float, title: str, subtitle: str, unit: str,
          labels: list[tuple[str, str]], plot_points: list[Point], marker_policies: list[str],
          *, lines: bool = False, offset_pairs: bool = False,
          reference: tuple[float, str, str] | None = None) -> None:
    width, height = 625, 307
    svg.rect(x, y, width, height, "#FBFDFF", GRID, 7)
    svg.txt(x + 19, y + 31, title, 18, INK, 700)
    svg.txt(x + 19, y + 53, subtitle, 13, MUTED)
    left, right, upper, bottom = x + 61, x + 603, y + 91, y + 253
    values = [point.high for point in plot_points]
    if reference is not None:
        values.append(reference[0])
    top = axis_top(values, Metric("", "", unit))
    minimum = min(0, *(point.low for point in plot_points))
    low = -axis_top([-minimum], Metric("", "", unit)) if minimum < 0 else 0

    def to_y(value: float) -> float:
        return bottom - (value - low) / (top - low) * (bottom - upper)

    svg.txt(left, upper - 10, unit, 12, MUTED, 700)
    for i in range(5):
        value = low + (top - low) * i / 4
        py = to_y(value)
        svg.line(left, py, right, py)
        svg.txt(left - 10, py + 4, tick(value), 11, MUTED, anchor="end")

    x_positions = [left + (index + 0.5) * (right - left) / len(labels) for index in range(len(labels))]
    if reference is not None:
        value, label, color = reference
        py = to_y(value)
        svg.line(left, py, right, py, color, 1, "5 4", 0.8)
        svg.txt(left + 4, py - 6, label, 11, color)
    for px, (heading, subheading) in zip(x_positions, labels):
        svg.txt(px, bottom + 23, heading, 13, INK, 700, "middle")
        svg.txt(px, bottom + 40, subheading, 11, MUTED, anchor="middle")
    positions = [px + shift for px in x_positions for shift in (-26, 26)] if offset_pairs else x_positions
    if lines:
        color = POLICIES[marker_policies[0]][1]
        for (px, a), (qx, b) in zip(zip(positions, plot_points), zip(positions[1:], plot_points[1:])):
            svg.line(px, to_y(a.value), qx, to_y(b.value), color, 1.65)
    for px, point, policy in zip(positions, plot_points, marker_policies):
        color = POLICIES[policy][1]
        hi, lo = to_y(point.high), to_y(point.low)
        svg.line(px, hi, px, lo, color, 1.6)
        for y_cap in (hi, lo):
            svg.line(px - 4, y_cap, px + 4, y_cap, color, 1.6)
        svg.marker(px, to_y(point.value), policy)
        svg.txt(px, hi - 9, f"{point.value:.3f}", 11, color, 700, "middle")


def render(results: Path, output: Path) -> None:
    throughput, tail_delta, sweep, baseline, comparison = read_points(results)
    width, height = 1342, 845
    title = "PON upstream DBA performance summary"
    subtitle = "Matched GIANT/RR inputs · 100 ms observation · 1200-byte packets · 2.5 Gb/s uplink"
    svg = Svg(width, height, title, subtitle)
    svg.rect(34, 28, 34, 3, POLICIES[GIANT][1])
    svg.txt(34, 64, title, 30, INK, 700)
    svg.txt(35, 91, subtitle, 14, MUTED)
    for index, (policy, label) in enumerate(POLICY_LABELS.items()):
        px = 43 + index * 151
        svg.marker(px, 119, policy)
        svg.txt(px + 12, 123, label, 13)
    svg.txt(326, 123, "Points show the mean of three seeds. Whiskers span the seed min–max.", 13, MUTED)

    panel(svg, 34, 144, "A · Sustained overload goodput",
          "8 Gb/s T4-only input · Configured T1 fixed windows retained", "Delivered (Gb/s)",
          [("4 SFUs", "8 STAs"), ("8 SFUs", "16 STAs")], throughput, [GIANT, RR] * 2,
          offset_pairs=True, reference=(2.5, "Physical rate", MUTED))
    panel(svg, 683, 144, "B · Protected services under T4 overload",
          "8 SFUs · T4 at 8 Gb/s relative to the matched T4 = 0 baseline", "Change in FIFO P99 (ms)",
          [("T1", "Fixed"), ("T2", "Assured"), ("T3", "Assured + surplus")], tail_delta, [GIANT, RR] * 3,
          offset_pairs=True)
    panel(svg, 34, 469, "C · T2 budget sensitivity",
          "2 SFUs · 3.05 Gb/s total input · SImax = 1 frame · ABmin per SFU", "Mean FIFO wait (ms)",
          [("2,400 B", ""), ("3,600 B", ""), ("4,800 B", "")], sweep, [GIANT] * 3,
          lines=True, reference=(baseline.value, f"RR mean {baseline.value:.3f}", POLICIES[RR][1]))
    panel(svg, 683, 469, "D · T2 service interval and polling sensitivity",
          "2 SFUs · 3.05 Gb/s total input · Same nominal GIANT rate for SI1 and SI2", "FIFO P99 (ms)",
          [("RR", "Poll / 1 fr."), ("GIANT SI1", "3,600 B · poll / 1 fr."),
           ("GIANT SI2", "7,200 B · poll / 1 fr."), ("GIANT SI1", "3,600 B · poll / 4 fr.")],
          comparison, [RR, GIANT, GIANT, GIANT])
    svg.line(34, 796, width - 34, 796, GRID)
    svg.txt(35, 818, "FIFO wait runs from entry to optical loading. Packets still waiting at 100 ms are censored. "
            "The panels use different scales.", 13, MUTED)
    svg.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, default=RESULTS, help="Root directory containing archived CSVs")
    parser.add_argument("--output", type=Path, help="SVG output file")
    options = parser.parse_args()
    output = options.output or options.results / "pon-final" / "pon-performance-summary.svg"
    output.parent.mkdir(parents=True, exist_ok=True)
    render(options.results, output)
    print(f"Wrote {output}")


if __name__ == "__main__":
    main()
