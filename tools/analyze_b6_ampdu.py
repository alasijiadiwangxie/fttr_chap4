"""Analyze B6 simulations without extending either fit outside measured support."""

from __future__ import annotations

import argparse
from bisect import bisect_left, bisect_right
import csv
from dataclasses import dataclass
import math
from pathlib import Path
from statistics import fmean

from plot_b5_energy_load import Axis, linear_axis, log_axis
from plot_b5_paper_replication import Panel, fit_line
from plot_pon_qos_special import GRID, INK, MUTED, Svg, tick
from pon_experiment_io import ROOT, require, write_csv


RESULTS = ROOT / "results/b6-ampdu"
MODES = {"LCM": "#076D90", "HCM": "#B34A20"}
DEPTHS = {1: "#576879", 2: "#0072B2", 4: "#935000", 8: "#008576"}
VARIANTS = ("A1", "B1", "C2", "C4", "C8", "D2", "D4", "D8")
CROSSOVER_OBSERVATION_FIELDS = ("packet_bytes", "interval_us", "offered_load_mbps", "throughput_mbps",
                               "avg_power_paper_w", "energy_per_success_bit_paper_nj", "e2e_mean_us", "e2e_p95_us",
                               "e2e_p99_us", "backlog_packets", "dropped_packets", "delivery_ratio")


@dataclass(frozen=True)
class Run:
    row: dict[str, str]

    def n(self, field: str) -> float:
        value = float(self.row[field])
        require(math.isfinite(value) and value >= 0, f"Invalid B6 field {field}: {self.row}")
        return value

    @property
    def mode(self) -> str:
        return self.row["mode"]

    @property
    def variant(self) -> str:
        return self.row["variant"]

    @property
    def pair(self) -> tuple[str, str, str]:
        return tuple(self.row[field] for field in ("packet_bytes", "interval_us", "seed"))

    @property
    def low_backlog(self) -> bool:
        return self.n("backlog_packets") <= 1 and self.n("dropped_packets") == 0


def read_runs(path: Path) -> dict[tuple[str, str], list[Run]]:
    groups = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            require(row["mode"] in MODES and row["variant"] in VARIANTS, f"Invalid B6 group: {row}")
            groups.setdefault((row["family"], row["variant"]), []).append(Run(row))
    require(bool(groups), "No B6 runs")
    for group in groups.values():
        keys = {(run.pair, run.mode) for run in group}
        expected = {(run.pair, mode) for run in group for mode in MODES}
        require(len(keys) == len(group) and keys == expected, "Incomplete or duplicate B6 pairs")
    return groups


def saturation_rows(groups: dict) -> list[dict]:
    records = []
    metrics = (
        "offered_load_mbps", "throughput_mbps", "avg_power_paper_w", "energy_per_success_bit_paper_nj",
        "e2e_mean_us", "e2e_p95_us", "e2e_p99_us", "backlog_packets", "dropped_packets",
        "ppdu_count", "mean_mpdu_per_ppdu", "ampdu_ppdu_count", "mean_ppdu_us", "mean_response_us",
        "control_airtime_us", "data_airtime_us",
    )
    for (family, variant), group in groups.items():
        if family != "FIXED_1500":
            continue
        for mode in MODES:
            runs = [run for run in group if run.mode == mode]
            maximum = max(runs, key=lambda run: (run.n("throughput_mbps"), run.n("offered_load_mbps")))
            highest_load = max(runs, key=lambda run: run.n("offered_load_mbps"))
            low = [run.n("throughput_mbps") for run in runs if run.low_backlog]
            row = dict(family=family, variant=variant, mode=mode,
                       aifs_us=maximum.row["aifs_us"], max_mpdus=maximum.row["max_mpdus"],
                       highest_observed_throughput_mbps=maximum.row["throughput_mbps"],
                       offered_at_highest_observed_throughput_mbps=maximum.row["offered_load_mbps"],
                       low_backlog_max_throughput_mbps=max(low) if low else "",
                       low_backlog_point_count=len(low),
                       saturated_at_highest_offered_load=not highest_load.low_backlog)
            row.update({f"highest_load_{field}": highest_load.row[field] for field in metrics})
            response_time = highest_load.n("control_airtime_us")
            delivered = highest_load.n("delivered_packets")
            row["highest_load_control_airtime_us_per_success"] = response_time / delivered if delivered else ""
            row["highest_load_control_duty_pct"] = 100 * response_time / highest_load.n("observation_us")
            records.append(row)
    require(bool(records), "Missing FIXED_1500 measurements")
    maxima = {(row["variant"], row["mode"]): float(row["highest_observed_throughput_mbps"]) for row in records}
    for row in records:
        baseline = maxima.get(("A1", row["mode"]))
        actual = float(row["highest_observed_throughput_mbps"])
        row["peak_gain_vs_a1_pct"] = 100 * (actual / baseline - 1) if baseline else ""
    return records


def crossing_fields(samples: list[Run]) -> dict:
    points = {mode: [(run.n("throughput_mbps"), run.n("avg_power_paper_w"))
                     for run in samples if run.mode == mode] for mode in MODES}
    xs = {mode: sorted({point[0] for point in selected}) for mode, selected in points.items()}
    fits = {mode: fit_line(selected) for mode, selected in points.items()}
    lcm, hcm = fits["LCM"], fits["HCM"]
    has_both = all(xs.values())
    lower = max(values[0] for values in xs.values()) if has_both else None
    upper = min(values[-1] for values in xs.values()) if has_both else None
    raw = crossover = below = above = None
    if not lcm or not hcm:
        status = "INSUFFICIENT_DISTINCT_THROUGHPUT"
    elif lower >= upper:
        status = "NO_COMMON_THROUGHPUT_SUPPORT"
    elif math.isclose(lcm.slope, hcm.slope, rel_tol=0, abs_tol=1e-12):
        status = "PARALLEL_OR_COINCIDENT_FITS"
    else:
        raw = (hcm.intercept - lcm.intercept) / (lcm.slope - hcm.slope)
        if lower <= raw <= upper:
            status = "SUPPORTED_CROSSOVER"
            crossover = raw
            below = min(values[bisect_right(values, raw) - 1] for values in xs.values())
            above = max(values[bisect_left(values, raw)] for values in xs.values())
        else:
            status = "OUTSIDE_COMMON_THROUGHPUT_SUPPORT"
    result = dict(result=status, common_min_mbps=lower, common_max_mbps=upper,
                  raw_fit_intersection_mbps=raw, supported_crossing_mbps=crossover,
                  fitted_power_w_at_crossing=lcm.at(crossover) if crossover is not None else None,
                  observation_bracket_lower_mbps=below, observation_bracket_upper_mbps=above)
    for mode, fit in fits.items():
        prefix = mode.lower()
        result.update({f"{prefix}_points": len(points[mode]),
                       f"{prefix}_max_observed_throughput_mbps": xs[mode][-1] if xs[mode] else None,
                       f"{prefix}_slope_w_per_mbps": fit.slope if fit else None,
                       f"{prefix}_intercept_w": fit.intercept if fit else None,
                       f"{prefix}_fit_r2": fit.r2 if fit else None})
        # Retain a real observation next to the fit. Its latency/backlog are not evaluated on the fitted line.
        nearest = (min((run for run in samples if run.mode == mode),
                       key=lambda run: (abs(run.n("throughput_mbps") - crossover), run.n("offered_load_mbps")))
                   if crossover is not None else None)
        result.update({f"{prefix}_nearest_{field}": nearest.row[field] if nearest else None
                       for field in CROSSOVER_OBSERVATION_FIELDS})
    return result


def crossing_rows(groups: dict) -> list[dict]:
    rows = []
    for (family, variant), group in groups.items():
        high_backlog = {run.pair for run in group if not run.low_backlog}
        low = [run for run in group if run.pair not in high_backlog]
        low_fit = crossing_fields(low)
        for subset, fit in (("ALL", crossing_fields(group)), ("PAIRED_BACKLOG_LE_1", low_fit)):
            cross = fit["supported_crossing_mbps"]
            low_max = low_fit["common_max_mbps"]
            beyond = None if cross is None or low_max is None else cross > low_max
            rows.append(dict(family=family, variant=variant, subset=subset, power_model="PAPER_12V",
                             aifs_us=group[0].row["aifs_us"], max_mpdus=group[0].row["max_mpdus"],
                             paired_low_backlog_common_max_mbps=low_max, crossing_above_low_backlog_range=beyond,
                             **fit))
    return rows


def mean_points(samples: list[Run], x_field: str, y_field: str) -> list[tuple[float, float]]:
    # Rows share an identical duration; grouping offered loads also allows future multiple seeds.
    bins = {}
    for run in samples:
        bins.setdefault(run.n("offered_load_mbps"), []).append(run)
    return [(fmean(run.n(x_field) for run in group), fmean(run.n(y_field) for run in group))
            for _, group in sorted(bins.items())]


def draw_series(panel: Panel, points: list[tuple[float, float]], color: str, dashed: bool = False) -> None:
    previous = None
    for horizontal, vertical in points:
        dot = panel.x(horizontal), panel.y(vertical)
        if previous:
            panel.svg.line(*previous, *dot, color, 2.25, "6 4" if dashed else "")
        panel.svg.circ(*dot, 3, "white" if dashed else color, color, 1.3)
        previous = dot


def draw_axes(panel: Panel, x_label: str, y_label: str) -> None:
    svg = panel.svg
    svg.txt(panel.left, panel.top - 16, y_label, 12, MUTED, 700)
    for value in panel.y_axis.ticks:
        y = panel.y(value)
        svg.line(panel.left, y, panel.right, y)
        label = f"{value:g}" if panel.y_axis.log else tick(value)
        svg.txt(panel.left - 8, y + 4, label, 12, MUTED, anchor="end")
    svg.line(panel.left, panel.top, panel.left, panel.bottom, "#B6C8D5")
    svg.line(panel.left, panel.bottom, panel.right, panel.bottom, "#B6C8D5")
    for value in panel.x_axis.ticks:
        x = panel.x(value)
        svg.line(x, panel.bottom, x, panel.bottom + 5, "#B6C8D5")
        svg.txt(x, panel.bottom + 21, tick(value), 12, MUTED, anchor="middle")
    svg.txt((panel.left + panel.right) / 2, panel.bottom + 47, x_label, 12, INK, 700, "middle")


def chart(title: str, subtitle: str, legend: dict, footer: str, path: Path,
          panels: list[tuple[str, list, str, str]], x_field: str, y_field: str,
          x_label: str, y_label: str, *, log_y: bool = False, scatter: bool = False) -> None:
    samples = [run for _, runs, _, _ in panels for run in runs]
    x_axis = linear_axis(0, max(run.n(x_field) for run in samples) * 1.04, 5)
    y_vals = [run.n(y_field) for run in samples]
    y_axis = (log_axis(min(y for y in y_vals if y > 0) * .9, max(y_vals) * 1.1) if log_y
              else linear_axis(0 if min(y_vals) < 1 else min(y_vals) * .97, max(y_vals) * 1.03, 5))
    width, height = 1160, 118 + 333 * math.ceil(len(panels) / 2) + 55
    svg = Svg(width, height, title, subtitle)
    svg.rect(34, 24, 36, 3, "#076D90")
    svg.txt(34, 57, title, 26, INK, 700)
    svg.txt(34, 83, subtitle, 13, MUTED)
    for index, (label, color) in enumerate(legend.items()):
        x = 34 + index * 165
        svg.line(x, 103, x + 24, 103, color, 2.3)
        svg.circ(x + 12, 103, 3, color, color)
        svg.txt(x + 33, 108, label, 13, INK)
    for index, (caption, runs, style_key, dash_label) in enumerate(panels):
        left = 82 + (index % 2) * 564
        top = 192 + (index // 2) * 333
        svg.rect(left - 54, top - 67, 542, 318, "#FCFDFE", GRID, 9)
        svg.txt(left - 34, top - 39, caption, 16, INK, 700)
        panel = Panel(svg, x_axis, y_axis, left, top, left + 460, top + 193)
        draw_axes(panel, x_label, y_label)
        for label, color in legend.items():
            selected = [run for run in runs if
                        (run.mode if style_key == "mode" else
                         str(int(run.n("max_mpdus"))) + " MPDU") == label]
            if scatter:
                for run in selected:
                    svg.circ(panel.x(run.n(x_field)), panel.y(run.n(y_field)), 3.1, "white", color, 1.75)
                if y_field == "avg_power_paper_w" and len(selected) > 1:
                    fit = fit_line([(run.n(x_field), run.n(y_field)) for run in selected])
                    if fit:
                        first = min(run.n(x_field) for run in selected)
                        last = max(run.n(x_field) for run in selected)
                        svg.line(panel.x(first), panel.y(fit.at(first)), panel.x(last), panel.y(fit.at(last)),
                                 color, 2.0, "6 4" if label == dash_label else "")
            else:
                draw_series(panel, mean_points(selected, x_field, y_field), color, label == dash_label)
    svg.txt(34, height - 16, footer, 12, MUTED)
    svg.save(path)


def throughput_chart(groups: dict, path: Path) -> None:
    fixed = [run for (family, _), runs in groups.items() if family == "FIXED_1500" for run in runs]
    panels = [(f"{mode}  ·  AIFS {aifs} µs", rows, "depth", "8 MPDU")
              for aifs in (70, 43) for mode in MODES
              if (rows := [run for run in fixed if run.mode == mode and run.n("aifs_us") == aifs])]
    chart("A-MPDU raises the delivered-throughput plateau",
          "Single-STA periodic downlink · 1500 B · fixed 15 s window",
          {f"{depth} MPDU": color for depth, color in DEPTHS.items()},
          "Legend gives the configured maximum per PPDU. The CSV records actual aggregation depth, loss and backlog.",
          path, panels, "offered_load_mbps", "throughput_mbps", "Offered payload load (Mb/s)",
          "Delivered payload throughput (Mb/s)")


def power_chart(groups: dict, family: str, subtitle: str, path: Path) -> None:
    panels = [(f"{variant}  ·  AIFS {rows[0].row['aifs_us']} µs  ·  max {rows[0].row['max_mpdus']} MPDU",
               rows, "mode", "HCM") for variant in ("A1", "B1", "C8", "D8")
              if (rows := groups.get((family, variant)))]
    if not panels:
        return
    chart("Average AP power versus delivered throughput", subtitle,
          MODES, "Markers are Java runs. OLS lines end at each mode's measured support; crossover and backlog are in CSV.",
          path, panels, "throughput_mbps", "avg_power_paper_w",
          "Delivered payload throughput (Mb/s)", "Average AP power (W)", scatter=True)


def grid_delay_chart(groups: dict, output: Path) -> None:
    panels = [(f"{variant}  ·  AIFS {rows[0].row['aifs_us']} µs  ·  max {rows[0].row['max_mpdus']} MPDU",
               rows, "mode", "HCM") for variant in VARIANTS if (rows := groups.get(("B5_GRID", variant)))]
    if not panels:
        return
    chart("Packet delay versus delivered throughput",
          "Same B5 grid and original 15 s observation window · successful packet mean",
          MODES, "Latency is conditional on delivery. Extremely loaded cases also accumulate unfinished packets.",
          output / "delay-vs-throughput.svg", panels, "throughput_mbps", "e2e_mean_us",
          "Delivered payload throughput (Mb/s)", "Mean end-to-end delay (µs, log scale)", log_y=True, scatter=True)


def analyze(output: Path) -> None:
    groups = read_runs(output / "per-run.csv")
    saturation = saturation_rows(groups)
    crossings = crossing_rows(groups)
    write_csv(output / "saturation-comparison.csv", tuple(saturation[0]), saturation)
    write_csv(output / "energy-crossover-comparison.csv", tuple(crossings[0]), crossings)
    figures = output / "figures"
    figures.mkdir(exist_ok=True)
    throughput_chart(groups, figures / "throughput-vs-load.svg")
    power_chart(groups, "B5_GRID", "B5 packet-size and interval grid · Paper 12 V powers · Java HE SU",
                figures / "power-vs-throughput.svg")
    power_chart(groups, "FIXED_1500", "Fixed 1500 B periodic downlink · Paper 12 V powers · Java HE SU",
                figures / "power-vs-throughput-fixed-1500.svg")
    grid_delay_chart(groups, figures)
    print(f"Wrote {len(saturation)} saturation summaries and {len(crossings)} crossover rows to {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS, help="B6 results directory containing per-run.csv")
    analyze(parser.parse_args().input)
