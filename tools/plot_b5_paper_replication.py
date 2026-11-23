"""Render Figure 2-style plots and infer supported OLS crossings from Java observations."""

from __future__ import annotations

import argparse
from bisect import bisect_left, bisect_right
import csv
from dataclasses import dataclass
import math
from pathlib import Path
from statistics import fmean

from plot_b5_energy_load import MODES, Axis, linear_axis, log_axis
from plot_pon_qos_special import GRID, INK, MUTED, Svg, tick
from pon_experiment_io import ROOT, require, write_csv


RESULTS = ROOT / "results" / "b5-paper-replication"
METRIC_FILE = "paper-aligned-metrics.csv"
MODEL_FIELDS = (("PAPER_12V", "avg_power_paper_w"), ("B3", "avg_power_b3_w"))
PAPER_REFERENCE_MBPS = 31
POINT_FILL = {"LCM": "#91BCD0", "HCM": "#E3B29F"}
AXIS_COLOR = "#A3B5C3"
CROSS_COLOR = "#5D418D"
STATUS_LABEL = {
    "SUPPORTED_CROSSOVER": "Intersection on common throughput support",
    "OUTSIDE_COMMON_SUPPORT": "Intersection is outside common throughput support",
    "NO_COMMON_SUPPORT": "The throughput ranges have no common interval",
    "PARALLEL_FITS": "No intersection: approximately parallel fits",
    "COINCIDENT_FITS": "No unique intersection: approximately coincident fits",
    "NO_DISTINCT_THROUGHPUT": "Fit requires distinct throughput observations",
}


@dataclass(frozen=True)
class Sample:
    mode: str
    seed: int
    interval_us: float
    packet_bytes: int
    throughput: float
    paper_power: float
    b3_power: float
    mean_delay_ms: float | None
    backlog_packets: int
    dropped_packets: int

    def power(self, model: str) -> float:
        return self.paper_power if model == "PAPER_12V" else self.b3_power


@dataclass(frozen=True)
class Line:
    slope: float
    intercept: float
    r2: float

    def at(self, throughput: float) -> float:
        return self.intercept + self.slope * throughput


@dataclass(frozen=True)
class Crossing:
    lines: dict[str, Line | None]
    support: tuple[float, float]
    result: str
    raw_intersection: float | None = None
    crossover: float | None = None
    bracket: tuple[float, float] | None = None


def number(raw: str, field: str) -> float:
    value = float(raw)
    require(math.isfinite(value) and value >= 0, f"Invalid {field}: {raw}")
    return value


def read_samples(path: Path) -> list[Sample]:
    columns = {"arrival_model", "mode", "seed", "interval_us", "packet_bytes", "throughput_mbps",
               "backlog_packets", "dropped_packets",
               "e2e_mean_us", *(field for _, field in MODEL_FIELDS)}
    samples = []
    cases: dict[tuple[int, float, int], set[str]] = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        missing = columns - set(reader.fieldnames or ())
        require(not missing, f"{path} missing columns: {sorted(missing)}")
        for row in reader:
            if row["arrival_model"] != "PERIODIC":
                continue
            mode, seed = row["mode"], int(row["seed"])
            interval, packet = number(row["interval_us"], "interval_us"), int(row["packet_bytes"])
            require(mode in MODES and interval > 0 and packet > 0, f"Invalid periodic run: {row}")
            key = (seed, interval, packet)
            modes = cases.setdefault(key, set())
            require(mode not in modes, f"Duplicate periodic run: {key}/{mode}")
            modes.add(mode)
            delay = number(row["e2e_mean_us"], "e2e_mean_us") / 1000 if row["e2e_mean_us"] else None
            samples.append(Sample(mode, seed, interval, packet, number(row["throughput_mbps"], "throughput_mbps"),
                                  *(number(row[field], field) for _, field in MODEL_FIELDS), delay,
                                  int(row["backlog_packets"]), int(row["dropped_packets"])))
    require(samples and all(modes == set(MODES) for modes in cases.values()), "Expected paired periodic observations")
    return samples


def fit_line(points: list[tuple[float, float]]) -> Line | None:
    if len(points) < 2:
        return None
    x_mean, y_mean = (fmean(column) for column in zip(*points))
    xx = math.fsum((x - x_mean) ** 2 for x, _ in points)
    if xx == 0:
        return None
    slope = math.fsum((x - x_mean) * (y - y_mean) for x, y in points) / xx
    intercept = y_mean - slope * x_mean
    residual = math.fsum((y - (intercept + slope * x)) ** 2 for x, y in points)
    total = math.fsum((y - y_mean) ** 2 for _, y in points)
    return Line(slope, intercept, 1 - residual / total if total else 1.0)


def crossing_fit(samples: list[Sample], model: str) -> Crossing:
    # As in Fig. 2(a), each mode has a separate regression using all its sampled points.
    points = {mode: [(sample.throughput, sample.power(model)) for sample in samples if sample.mode == mode]
              for mode in MODES}
    x_values = {mode: sorted({x for x, _ in values}) for mode, values in points.items()}
    support = (max(values[0] for values in x_values.values()), min(values[-1] for values in x_values.values()))
    lines = {mode: fit_line(values) for mode, values in points.items()}
    lcm, hcm = lines["LCM"], lines["HCM"]
    if lcm is None or hcm is None:
        return Crossing(lines, support, "NO_DISTINCT_THROUGHPUT")
    if support[0] >= support[1]:
        return Crossing(lines, support, "NO_COMMON_SUPPORT")
    difference = lcm.slope - hcm.slope
    if math.isclose(difference, 0.0, abs_tol=1e-12):
        coincident = math.isclose(lcm.intercept, hcm.intercept, abs_tol=1e-12, rel_tol=0)
        return Crossing(lines, support, "COINCIDENT_FITS" if coincident else "PARALLEL_FITS")
    raw = (hcm.intercept - lcm.intercept) / difference
    if not support[0] <= raw <= support[1]:
        return Crossing(lines, support, "OUTSIDE_COMMON_SUPPORT", raw_intersection=raw)
    # Report the envelope of the closest observations on either side for both modes.
    # This describes sampling resolution, with the fitted root separately reported.
    below = min(values[bisect_right(values, raw) - 1] for values in x_values.values())
    above = max(values[bisect_left(values, raw)] for values in x_values.values())
    return Crossing(lines, support, "SUPPORTED_CROSSOVER", raw, raw, (below, above))


def fmt(value: float | None) -> str:
    return "" if value is None else f"{value:.9f}".rstrip("0").rstrip(".")


def crossing_rows(samples: list[Sample]) -> tuple[list[dict], dict[str, Crossing], dict[str, list[float]]]:
    rows, pooled, seed_roots = [], {}, {}
    seeds = sorted({sample.seed for sample in samples})
    for model, _ in MODEL_FIELDS:
        groups = [(seed, [sample for sample in samples if sample.seed == seed]) for seed in seeds]
        by_seed = [(seed, group, crossing_fit(group, model)) for seed, group in groups]
        pooled_fit = crossing_fit(samples, model)
        roots = [fit.crossover for _, _, fit in by_seed if fit.crossover is not None]
        pooled[model], seed_roots[model] = pooled_fit, roots
        for seed, group, fit in [("POOLED", samples, pooled_fit), *by_seed]:
            row = dict(
                power_model=model, arrival_model="PERIODIC", seed=seed, result=fit.result,
                support_lower_mbps=fmt(fit.support[0]), support_upper_mbps=fmt(fit.support[1]),
                raw_intersection_mbps=fmt(fit.raw_intersection), supported_crossover_mbps=fmt(fit.crossover),
                nearest_observation_lower_mbps=fmt(fit.bracket[0]) if fit.bracket else "",
                nearest_observation_upper_mbps=fmt(fit.bracket[1]) if fit.bracket else "",
                supported_seed_count=len(roots) if seed == "POOLED" else "",
                total_seed_count=len(seeds) if seed == "POOLED" else "",
                seed_crossover_min_mbps=fmt(min(roots)) if seed == "POOLED" and roots else "",
                seed_crossover_max_mbps=fmt(max(roots)) if seed == "POOLED" and roots else "",
            )
            for mode in MODES:
                points = [sample.throughput for sample in group if sample.mode == mode]
                line = fit.lines[mode]
                prefix = mode.lower()
                row.update({
                    f"{prefix}_points": len(points),
                    f"{prefix}_observed_min_mbps": fmt(min(points)),
                    f"{prefix}_observed_max_mbps": fmt(max(points)),
                    f"{prefix}_slope_w_per_mbps": fmt(line.slope) if line else "",
                    f"{prefix}_intercept_w": fmt(line.intercept) if line else "",
                    f"{prefix}_r2": fmt(line.r2) if line else "",
                })
            rows.append(row)
    return rows, pooled, seed_roots


@dataclass(frozen=True)
class Panel:
    svg: Svg
    x_axis: Axis
    y_axis: Axis
    left: float
    top: float
    right: float
    bottom: float

    def x(self, value: float) -> float:
        return self.left + self.x_axis.position(value) * (self.right - self.left)

    def y(self, value: float) -> float:
        return self.bottom - self.y_axis.position(value) * (self.bottom - self.top)

    def axes(self, y_label: str) -> None:
        self.svg.txt(self.left, self.top - 16, y_label, 13, MUTED, 700)
        for level in self.y_axis.ticks:
            y = self.y(level)
            self.svg.line(self.left, y, self.right, y)
            label = f"{level:g}" if self.y_axis.log else tick(level)
            self.svg.txt(self.left - 8, y + 4, label, 12, MUTED, anchor="end")
        self.svg.line(self.left, self.top, self.left, self.bottom, AXIS_COLOR)
        self.svg.line(self.left, self.bottom, self.right, self.bottom, AXIS_COLOR)
        for level in self.x_axis.ticks:
            x = self.x(level)
            self.svg.line(x, self.bottom, x, self.bottom + 5, AXIS_COLOR)
            self.svg.txt(x, self.bottom + 21, tick(level), 12, MUTED, anchor="middle")
        self.svg.txt((self.left + self.right) / 2, self.bottom + 48, "Delivered payload throughput (Mb/s)",
                     13, INK, 700, "middle")

    def sample(self, mode: str, throughput: float, value: float) -> None:
        draw_sample(self.svg, mode, self.x(throughput), self.y(value))


def draw_sample(svg: Svg, mode: str, x: float, y: float) -> None:
    if mode == "LCM":
        svg.circ(x, y, 3.2, POINT_FILL[mode], MODES[mode], 0.9)
    else:
        svg.rect(x - 3, y - 3, 6, 6, POINT_FILL[mode], MODES[mode], stroke_width=0.9)


def header(svg: Svg, title: str, subtitle: str, *, fits: bool) -> None:
    svg.rect(35, 25, 35, 3, MODES["LCM"])
    svg.txt(35, 61, title, 27, INK, 700)
    svg.txt(35, 87, subtitle, 13, MUTED)
    for idx, mode in enumerate(MODES):
        x = 39 + idx * 179
        if fits:
            svg.line(x, 110, x + 36, 110, MODES[mode], 2.0, "6 4" if mode == "HCM" else "")
        draw_sample(svg, mode, x + 18 if fits else x + 5, 110)
        svg.txt(x + (45 if fits else 19), 115, f"Always-{mode}", 13, INK, 700)
    svg.txt(407, 115, "Markers = Java runs" + ("   ·   Lines = pooled OLS fits" if fits else ""), 12, MUTED)


def power_figure(path: Path, samples: list[Sample], fits: dict[str, Crossing],
                 seed_roots: dict[str, list[float]], x_axis: Axis) -> None:
    width, height = 1140, 545
    svg = Svg(width, height, "Figure 2(a) analogue · AP power versus throughput",
              "Periodic Java runs with separate regression by AP capability. Both panels use a common scale.")
    header(svg, "Figure 2(a) analogue · AP power versus throughput",
           "1 AP · 1 STA · Periodic downlink · Fixed 15 s observation · Identical frame activity reweighted below", fits=True)
    all_power = [sample.power(model) for sample in samples for model, _ in MODEL_FIELDS]
    # Include predicted values over each mode's sampled range so all fitted lines stay within the axes.
    for model, fit in fits.items():
        for mode, line in fit.lines.items():
            values = [sample.throughput for sample in samples if sample.mode == mode]
            if line is not None:
                all_power.extend((line.at(min(values)), line.at(max(values))))
    lo, hi = min(all_power), max(all_power)
    margin = max(0.10, 0.045 * (hi - lo))
    y_axis = linear_axis(lo - margin, hi + margin, 6)
    panels = {
        "PAPER_12V": ("Paper current × 12 V", Panel(svg, x_axis, y_axis, 93, 176, 538, 415)),
        "B3": ("Original B3 state powers", Panel(svg, x_axis, y_axis, 658, 176, 1103, 415)),
    }
    for model, (label, panel) in panels.items():
        svg.txt(panel.left, 142, label, 16, INK, 700)
        panel.axes("Average AP power (W)")
        fit = fits[model]
        if model == "PAPER_12V" and x_axis.lower <= PAPER_REFERENCE_MBPS <= x_axis.upper:
            guide = panel.x(PAPER_REFERENCE_MBPS)
            svg.line(guide, panel.top + 1, guide, panel.bottom, MUTED, 0.9, "2 4", 0.62)
        if fit.crossover is not None:
            x = panel.x(fit.crossover)
            svg.line(x, panel.top + 1, x, panel.bottom, CROSS_COLOR, 1.1, "4 3")
        for sample in samples:
            panel.sample(sample.mode, sample.throughput, sample.power(model))
        for mode, line in fit.lines.items():
            if line is None:
                continue
            values = [sample.throughput for sample in samples if sample.mode == mode]
            first, last = min(values), max(values)
            svg.line(panel.x(first), panel.y(line.at(first)), panel.x(last), panel.y(line.at(last)),
                     MODES[mode], 2.3, "7 4" if mode == "HCM" else "")
        message = STATUS_LABEL[fit.result]
        if fit.crossover is not None:
            y = panel.y(fit.lines["LCM"].at(fit.crossover))
            svg.circ(panel.x(fit.crossover), y, 4.2, "white", CROSS_COLOR, 2.0)
            message = f"Pooled fit intersection: {fit.crossover:.2f} Mb/s"
        svg.txt(panel.left, 486, message, 12, INK, 700)
        roots = seed_roots[model]
        total_seeds = len({sample.seed for sample in samples})
        seed_message = (f"Supported by {len(roots)}/{total_seeds} seed fits · "
                        f"range {min(roots):.2f}–{max(roots):.2f} Mb/s" if roots
                        else f"No supported intersection in {total_seeds} seed fits")
        svg.txt(panel.left, 505, seed_message, 12, MUTED)
    svg.txt(35, 530, "Grey dotted guide: the paper reports about 31 Mb/s for its ns-3 scenario.", 11, MUTED)
    svg.save(path)


def delay_figure(path: Path, samples: list[Sample], x_axis: Axis) -> None:
    selected = [sample for sample in samples if sample.mean_delay_ms is not None and sample.mean_delay_ms > 0]
    require(selected, "No positive packet delays are available for Fig. 2(b)")
    title = "Figure 2(b) analogue · Packet delay versus throughput"
    svg = Svg(980, 537, title, "Mean end-to-end delay of delivered application packets on a logarithmic axis.")
    header(svg, title, "1 AP · 1 STA · Periodic downlink · Each dot is a packet-size, interval and seed combination",
           fits=False)
    delays = [sample.mean_delay_ms for sample in selected]
    y_axis = log_axis(min(delays) * 0.80, max(delays) * 1.18)
    panel = Panel(svg, x_axis, y_axis, 100, 168, 941, 417)
    panel.axes("Mean delivered-packet delay (ms, log scale)")
    for sample in selected:
        panel.sample(sample.mode, sample.throughput, sample.mean_delay_ms)
    svg.txt(35, 495, "Window backlog can grow after delivery throughput levels off.", 13, INK, 700)
    svg.txt(35, 516, "Pending and dropped packets do not enter mean delay. P95, P99 and backlog are in the run CSV.",
            12, MUTED)
    svg.save(path)


def write_fit_sensitivity(output: Path, samples: list[Sample]) -> None:
    # Remove both sides of each pair if either mode accumulated more than one packet or lost a packet.
    limited = {(s.seed, s.interval_us, s.packet_bytes) for s in samples
               if s.backlog_packets > 1 or s.dropped_packets > 0}
    low_backlog = [s for s in samples if (s.seed, s.interval_us, s.packet_bytes) not in limited]
    subsets = {
        "PAIRED_BACKLOG_LE_1": low_backlog,
        "PAYLOAD_1200": [s for s in samples if s.packet_bytes == 1200],
        "PAYLOAD_1200_PAIRED_BACKLOG_LE_1": [s for s in low_backlog if s.packet_bytes == 1200],
    }
    rows = []
    for subset, selected in subsets.items():
        if selected:
            fits, _, _ = crossing_rows(selected)
            rows.extend(dict(fit_subset=subset, **fit) for fit in fits)
    if rows:
        write_csv(output / "paper-fit-sensitivity.csv", tuple(rows[0]), rows)


def plot(output: Path) -> None:
    samples = read_samples(output / METRIC_FILE)
    rows, pooled, seed_roots = crossing_rows(samples)
    figures = output / "figures"
    figures.mkdir(parents=True, exist_ok=True)
    write_csv(output / "paper-fit-crossings.csv", tuple(rows[0]), rows)
    write_fit_sensitivity(output, samples)
    x_axis = linear_axis(0, max(sample.throughput for sample in samples) * 1.035, 6)
    power_figure(figures / "figure-2a-power-vs-throughput.svg", samples, pooled, seed_roots, x_axis)
    delay_figure(figures / "figure-2b-delay-vs-throughput.svg", samples, x_axis)
    print(f"Wrote paper-style figures and fit diagnostics to {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS, help=f"Directory containing {METRIC_FILE}")
    plot(parser.parse_args().input)


if __name__ == "__main__":
    main()
