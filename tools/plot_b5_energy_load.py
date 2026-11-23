"""Render B5 load curves from the per-load Java experiment aggregates."""

from __future__ import annotations

import argparse
import csv
import math
from dataclasses import dataclass
from pathlib import Path

from plot_pon_qos_special import GRID, INK, MUTED, Svg, tick
from pon_experiment_io import ROOT


RESULTS = ROOT / "results" / "b5-energy-load"
DIRECTIONS = {"DL": "Downlink", "UL": "Uplink", "BOTH": "Bidirectional"}
MODES = {"LCM": "#076D90", "HCM": "#B34A20"}


@dataclass(frozen=True)
class Metric:
    field: str
    label: str
    log: bool = False
    zero_baseline: bool = False
    offered_load_guide: bool = False


@dataclass(frozen=True)
class Sample:
    load: float
    mean: float
    sd: float


@dataclass(frozen=True)
class Axis:
    lower: float
    upper: float
    ticks: tuple[float, ...]
    log: bool = False

    def position(self, value: float) -> float:
        if self.log:
            return math.log(value / self.lower) / math.log(self.upper / self.lower)
        return (value - self.lower) / (self.upper - self.lower)


def sample_value(value: str) -> float | None:
    if not value.strip():
        return None
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise ValueError(f"Expected a finite, nonnegative B5 metric, received {value!r}")
    return number


def linear_axis(minimum: float, maximum: float, divisions: int) -> Axis:
    if maximum <= minimum:
        maximum = minimum + max(minimum * 0.1, 1)
    raw_step = (maximum - minimum) / divisions
    magnitude = 10 ** math.floor(math.log10(raw_step))
    step = next(unit * magnitude for unit in (1, 2, 2.5, 5, 10) if unit * magnitude >= raw_step)
    first = math.floor(minimum / step + 1e-9)
    last = math.ceil(maximum / step - 1e-9)
    return Axis(step * first, step * last, tuple(step * i for i in range(first, last + 1)))


def log_axis(minimum: float, maximum: float) -> Axis:
    low_decade = math.floor(math.log10(minimum))
    high_decade = math.ceil(math.log10(maximum))
    candidates = tuple(unit * 10**exponent for exponent in range(low_decade - 1, high_decade + 2)
                       for unit in (1, 2, 5))
    first = max(i for i, value in enumerate(candidates) if value <= minimum)
    last = max(first + 1, next(i for i, value in enumerate(candidates) if value >= maximum))
    labels = candidates[first:last + 1]
    if len(labels) > 11:
        labels = tuple(value for value in labels if math.isclose(math.log10(value), round(math.log10(value))))
    return Axis(candidates[first], candidates[last], labels, log=True)


def mode_style(svg: Svg, x: float, y: float, mode: str) -> None:
    color = MODES[mode]
    if mode == "LCM":
        svg.circ(x, y, 4, color, "white", 0.9)
    else:
        svg.rect(x - 3.6, y - 3.6, 7.2, 7.2, "white", color, stroke_width=1.7)


def series_for(rows: list[dict[str, str]], direction: str, mode: str, metric: Metric,
               log_x: bool) -> list[Sample | None]:
    selected = sorted((row for row in rows if row["direction"] == direction and row["mode"] == mode),
                      key=lambda row: float(row["load_mbps"]))
    samples = []
    for row in selected:
        load = float(row["load_mbps"])
        if log_x and load == 0:
            continue
        mean = sample_value(row[metric.field])
        sd = sample_value(row[f"{metric.field}_sd"])
        samples.append(None if mean is None or (metric.log and mean == 0) else Sample(load, mean, sd or 0.))
    return samples


def axis_for_metric(samples: list[Sample], metric: Metric, max_load: float) -> Axis:
    if not samples:
        return log_axis(1, 10) if metric.log else linear_axis(0, 1, 4)
    upper = max(sample.mean + sample.sd for sample in samples)
    if metric.log:
        # A sample SD may extend below zero. Keep the scale positive and clip the whisker at the axis floor.
        usable_lower = min(sample.mean - sample.sd if sample.mean > sample.sd else sample.mean / 2
                           for sample in samples)
        return log_axis(usable_lower * 0.98, upper * 1.04)
    if metric.offered_load_guide:
        upper = max(upper, max_load)
    if metric.zero_baseline:
        return linear_axis(0, upper * 1.04, 5)
    lower = max(0, min(sample.mean - sample.sd for sample in samples))
    margin = max((upper - lower) * 0.07, 0.04)
    return linear_axis(max(0, lower - margin), upper + margin, 5)


def panel(svg: Svg, box: tuple[float, float, float, float], title: str, metric: Metric,
          x_axis: Axis, y_axis: Axis, lines: dict[str, list[Sample | None]]) -> None:
    x, y, width, height = box
    left, right = x + 68, x + width - 21
    top, bottom = y + 64, y + height - 47
    svg.rect(x, y, width, height, "#FBFDFF", GRID, 7)
    svg.txt(x + 18, y + 30, title, 17, INK, 700)
    svg.txt(left, top - 12, metric.label, 12, MUTED, 700)

    def plot_x(value: float) -> float:
        return left + 7 + x_axis.position(value) * (right - left - 14)

    def plot_y(value: float) -> float:
        value = max(y_axis.lower, min(value, y_axis.upper))
        return bottom - y_axis.position(value) * (bottom - top)

    for value in y_axis.ticks:
        level = plot_y(value)
        svg.line(left, level, right, level)
        svg.txt(left - 8, level + 4, tick(value), 12, MUTED, anchor="end")
    svg.line(left, top, left, bottom, "#B6C8D5")
    svg.line(left, bottom, right, bottom, "#B6C8D5")
    for value in x_axis.ticks:
        position = plot_x(value)
        svg.line(position, bottom, position, bottom + 5, "#B6C8D5")
        svg.txt(position, bottom + 22, tick(value), 11, MUTED, anchor="middle")

    if metric.offered_load_guide:
        svg.line(plot_x(0), plot_y(0), plot_x(x_axis.upper), plot_y(x_axis.upper), "#8296A5",
                 1.0, "2 4")
    for mode, samples in lines.items():
        previous = None
        color = MODES[mode]
        for sample in samples:
            if sample is None:
                previous = None  # A missing ratio or percentile is a gap in the evidence.
                continue
            px, py = plot_x(sample.load), plot_y(sample.mean)
            if previous is not None:
                svg.line(*previous, px, py, color, 2.3, "6 4" if mode == "HCM" else "")
            previous = (px, py)
        for sample in samples:
            if sample is None:
                continue
            px = plot_x(sample.load)
            if sample.sd > 0:
                upper, lower = plot_y(sample.mean + sample.sd), plot_y(sample.mean - sample.sd)
                svg.line(px, upper, px, lower, color, 1.1, opacity=0.62)
                svg.line(px - 3, upper, px + 3, upper, color, 1.1, opacity=0.62)
                svg.line(px - 3, lower, px + 3, lower, color, 1.1, opacity=0.62)
            mode_style(svg, px, plot_y(sample.mean), mode)
    if not any(sample is not None for series in lines.values() for sample in series):
        svg.txt((left + right) / 2, (top + bottom) / 2, "No applicable samples", 13, MUTED, anchor="middle")


def figure(path: Path, rows: list[dict[str, str]], loads: list[float], directions: list[str],
           metrics: tuple[Metric, ...], title: str, subtitle: str, footer: str, *, log_x: bool = False) -> None:
    margin, gap, panel_height = 32, 20, 287
    width = max(860, len(directions) * 442 + (len(directions) - 1) * gap + 2 * margin)
    panel_width = (width - (len(directions) - 1) * gap - 2 * margin) / len(directions)
    start_y = 149
    end_y = start_y + len(metrics) * panel_height + (len(metrics) - 1) * gap
    height = end_y + 83
    svg = Svg(width, height, title, f"{subtitle} {footer}")
    svg.rect(margin, 25, 32, 3, MODES["LCM"])
    svg.txt(margin, 65, title, 29, INK, 700)
    svg.txt(margin, 91, subtitle, 14, MUTED)
    for i, mode in enumerate(MODES):
        lx = margin + i * 152
        svg.line(lx, 119, lx + 32, 119, MODES[mode], 2.3, "6 4" if mode == "HCM" else "")
        mode_style(svg, lx + 16, 119, mode)
        svg.txt(lx + 41, 124, f"Always-{mode}", 14)
    svg.txt(margin + 309, 124, "Mean ± sample SD", 12, MUTED)

    positive_loads = [load for load in loads if load > 0]
    x_axis = (log_axis(min(positive_loads), max(positive_loads)) if log_x and positive_loads
              else linear_axis(0, max(loads), 5))
    for i, metric in enumerate(metrics):
        by_direction = {direction: {mode: series_for(rows, direction, mode, metric, log_x) for mode in MODES}
                        for direction in directions}
        samples = [sample for lines in by_direction.values() for series in lines.values()
                   for sample in series if sample is not None]
        y_axis = axis_for_metric(samples, metric, x_axis.upper)
        y = start_y + i * (panel_height + gap)
        for column, direction in enumerate(directions):
            box = (margin + column * (panel_width + gap), y, panel_width, panel_height)
            panel(svg, box, DIRECTIONS[direction], metric, x_axis, y_axis, by_direction[direction])

    x_label = "Configured total offered load (Mb/s)"
    if log_x:
        x_label += "  ·  log scale"
    svg.txt(width / 2, end_y + 25, x_label, 14, INK, 700, "middle")
    svg.line(margin, end_y + 40, width - margin, end_y + 40)
    svg.txt(margin, end_y + 62, footer, 12, MUTED)
    svg.save(path)


def plot(output: Path) -> None:
    with (output / "energy-load-aggregate.csv").open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))
    directions = [direction for direction in DIRECTIONS if any(row["direction"] == direction for row in rows)]
    loads = sorted({float(row["load_mbps"]) for row in rows})
    keys = {(row["direction"], float(row["load_mbps"]), row["mode"]) for row in rows}
    expected = {(direction, load, mode) for direction in directions for load in loads for mode in MODES}
    valid_loads = all(math.isfinite(load) and load >= 0 for load in loads)
    if not rows or not valid_loads or len(keys) != len(rows) or keys != expected:
        raise ValueError("B5 plotting requires one aggregate row per direction, load and mode")

    figures = output / "figures"
    figures.mkdir(parents=True, exist_ok=True)
    figure(figures / "power-vs-load.svg", rows, loads, directions, (Metric("average_power_w", "Average AP power (W)"),),
           "Average AP power as offered load rises",
           "Always-LCM and Always-HCM · Paired Poisson inputs · Common observation window",
           "AP power includes IDLE, TX and RX. Delivery and backlog are shown in the companion figure.")
    figure(figures / "energy-per-success-bit-vs-load.svg", rows, loads, directions,
           (Metric("energy_per_success_bit_nj", "Energy per successful bit (nJ/bit, log)", log=True),),
           "Energy per successful payload bit",
           "Window AP energy divided by successfully delivered payload bits · Paired Poisson inputs",
           "Zero-success runs have no energy-per-bit value. Missing values have no marker or connecting segment.", log_x=True)
    figure(figures / "throughput-and-backlog-vs-load.svg", rows, loads, directions,
           (Metric("throughput_mbps", "Delivered payload (Mb/s)", zero_baseline=True, offered_load_guide=True),
            Metric("backlog_packets", "Window-end backlog (packets)", zero_baseline=True)),
           "Delivery and backlog under offered load",
           "Throughput and unfinished packets within the shared observation window",
           "Dotted diagonal = throughput equal to offered load. The backlog contains pending packets; explicit drops are reported in CSV.")
    print(f"Wrote B5 figures to {figures}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS, help="B5 result directory containing the aggregate CSV")
    plot(parser.parse_args().input)


if __name__ == "__main__":
    main()
