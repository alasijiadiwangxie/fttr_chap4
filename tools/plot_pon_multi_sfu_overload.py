"""Render the matched-seed multi-SFU DBA overload comparisons as static SVGs."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean
from xml.sax.saxutils import escape

from plot_pon_qos_special import GIANT, GRID, INK, MUTED, POLICIES, ROOT, RR, Metric, Point, Svg, axis_top, numeric, tick


RESULTS = ROOT / "results" / "pon-multi-sfu-overload"
SFU_COUNTS = (4, 8)
ISOLATION_LOADS = (0, 5000, 8000)
LINK_CONDITIONS = (
    ("t4-only-input", 5000), ("t4-only-input", 8000),
    *(("isolation", load) for load in ISOLATION_LOADS),
)
T4_LABEL = "T4-only input"
CONDITIONS = {"t4-only-input": T4_LABEL, "isolation": "Isolation"}
POLICY_LABELS = {GIANT: "giant-complete", RR: "rr-multi"}


@dataclass(frozen=True)
class Series:
    policy: str
    shift: float
    measurements: list[Point | None]
    dash: str = ""
    group_label: str = ""


def read_groups(path: Path, scope: str, conditions: tuple[tuple[str, int], ...],
                kinds: tuple[int, ...] = (0,)) -> dict[tuple, dict[int, dict]]:
    groups = defaultdict(dict)
    with path.open(encoding="utf-8-sig", newline="") as file:
        for row in csv.DictReader(file):
            if row["scope"] != scope:
                continue
            kind = int(row["tcont_type"]) if scope == "type" else 0
            if kind not in kinds:
                continue
            condition = row["scenario"], int(float(row["t4_mbps"]))
            if condition not in conditions:
                continue
            key = *condition, int(row["sfu_count"]), row["policy"], kind
            seed = int(row["seed"])
            if seed in groups[key]:
                raise ValueError(f"{path.name} has duplicate seed {seed} for {key}")
            if "utilization_fraction" not in row and "resource_utilization" in row:
                row["utilization_fraction"] = row["resource_utilization"]
            groups[key][seed] = row
    expected = {(scenario, load, count, policy, kind)
                for scenario, load in conditions for count in SFU_COUNTS for policy in POLICIES for kind in kinds}
    if set(groups) != expected:
        raise ValueError(f"{path.name} has missing or extra {scope} groups: {sorted(set(groups) ^ expected)}")
    if len({frozenset(runs) for runs in groups.values()}) != 1:
        raise ValueError(f"{path.name} has mismatched seed sets for {scope} groups")
    return groups


def measure(rows: dict[int, dict], metric: Metric) -> Point | None:
    samples = []
    for row in rows.values():
        value = numeric(row[metric.field])
        if value is not None:
            sparse = metric.tail_flag is not None and row.get(metric.tail_flag, "supported").lower() != "supported"
            samples.append((value * metric.factor, sparse))
    if not samples:
        return None
    values, sparse_flags = zip(*samples)
    return Point(mean(values), min(values), max(values), any(sparse_flags))


def domain(series: list[Series], metric: Metric, reference: float | None = None) -> tuple[float, float]:
    points = [sample for line in series for sample in line.measurements if sample is not None]
    maximums = [item.high for item in points]
    if reference is not None:
        maximums.append(reference)
    if metric.field == "jain_sfu_throughput":
        # The displayed ticks explicitly identify the narrowed axis near the Jain ceiling.
        shortfalls = [max(0, 1 - item.low) for item in points]
        span = max(0.005, axis_top([0.005, *shortfalls], Metric("", "", "")))
        return max(0, 1 - span), 1
    return 0, axis_top(maximums, metric)


def draw_panel(svg: Svg, box: tuple[float, float, float, float], x_values: tuple[float, ...], series: list[Series],
               scale: tuple[float, float], reference: float | None = None) -> None:
    x, y, width, height = box
    svg.rect(x, y, width, height, "#FBFDFF", GRID, 7)
    left, right, upper, bottom = x + 59, x + width - 15, y + 16, y + height - 32
    low, high = scale

    def to_y(value: float) -> float:
        return bottom - (value - low) / (high - low) * (bottom - upper)

    for index in range(5):
        value = low + (high - low) * index / 4
        py = to_y(value)
        svg.line(left, py, right, py)
        svg.txt(left - 8, py + 4, tick(value) if low == 0 else f"{value:.4f}".rstrip("0").rstrip("."),
                11, MUTED, anchor="end")
    if reference is not None:
        svg.line(left, to_y(reference), right, to_y(reference), MUTED, 1, "4 4", 0.65)

    edge = (right - left) / (2 * len(x_values))
    span = x_values[-1] - x_values[0]
    positions = [left + edge + (value - x_values[0]) / span * (right - left - 2 * edge) for value in x_values]
    x_labels = [tick(value) for value in x_values]
    for px, label in zip(positions, x_labels):
        svg.txt(px, bottom + 21, label, 12, MUTED, anchor="middle")
    if not any(sample is not None for line in series for sample in line.measurements):
        svg.txt((left + right) / 2, (upper + bottom) / 2, "No observations", 11, MUTED, anchor="middle")
    for line in series:
        color = POLICIES[line.policy][1]
        plotted = [(px + line.shift, sample) for px, sample in zip(positions, line.measurements)]
        for (px, a), (qx, b) in zip(plotted, plotted[1:]):
            if a is not None and b is not None:
                svg.line(px, to_y(a.value), qx, to_y(b.value), color, 1.7, line.dash, 0.8)
        for (px, sample), label in zip(plotted, x_labels):
            if sample is None:
                continue
            details = (f"{POLICY_LABELS[line.policy]} {line.group_label} · x={label} · "
                       f"mean {sample.value:.6g} · seed range {sample.low:.6g}–{sample.high:.6g}")
            svg.content.append(f"<g><title>{escape(details)}</title>")
            lo, hi = to_y(sample.low), to_y(sample.high)
            svg.line(px, lo, px, hi, color, 1.5)
            for cap in (lo, hi):
                svg.line(px - 3, cap, px + 3, cap, color, 1.5)
            svg.marker(px, to_y(sample.value), line.policy, sample.sparse)
            svg.content.append("</g>")


def start_figure(width: int, height: int, title: str, subtitle: str, seed_count: int) -> Svg:
    svg = Svg(width, height, title, subtitle)
    svg.rect(34, 27, 34, 3, POLICIES[GIANT][1])
    svg.txt(34, 62, title, 29, INK, 700)
    svg.txt(35, 88, subtitle, 14, MUTED)
    for index, (policy, label) in enumerate(POLICY_LABELS.items()):
        x = 43 + index * 155
        svg.marker(x, 115, policy)
        svg.txt(x + 12, 119, label, 13)
    svg.txt(327, 119, f"Points average available values across {seed_count} matched seeds. Whiskers show the seed min–max.",
            13, MUTED)
    return svg


def topology_grid(groups: dict[tuple, dict[int, dict]], metrics: tuple[Metric, ...], output: Path,
                  title: str, note: str) -> None:
    width, height = 1560, 267 + 208 * len(metrics)
    subtitle = "4 or 8 SFUs · 2 STAs per SFU · 100 ms observation · T4-only input retains the configured T1 fixed profile"
    svg = start_figure(width, height, title, subtitle, len(next(iter(groups.values()))))
    cell_w = (width - 68 - 14 * (len(LINK_CONDITIONS) - 1)) / len(LINK_CONDITIONS)
    for index, (scenario, load) in enumerate(LINK_CONDITIONS):
        center = 34 + index * (cell_w + 14) + cell_w / 2
        svg.txt(center, 157, CONDITIONS[scenario], 16, INK, 700, "middle")
        svg.txt(center, 180, f"T4 offered {load:,} Mb/s", 13, MUTED, anchor="middle")
    for r, metric in enumerate(metrics):
        y = 230 + r * 208
        svg.txt(35, y - 12, f"{metric.title} ({metric.unit})", 16, INK, 700)
        columns = [
            [Series(policy, shift, [
                measure(groups[scenario, load, sfu, policy, 0], metric) for sfu in SFU_COUNTS
            ]) for policy, shift in ((GIANT, -5), (RR, 5))]
            for scenario, load in LINK_CONDITIONS
        ]
        capacity = 2500 if metric.field == "throughput_mbps" else None
        scale = domain([line for column in columns for line in column], metric, capacity)
        for c, series in enumerate(columns):
            draw_panel(svg, (34 + c * (cell_w + 14), y, cell_w, 162),
                       SFU_COUNTS, series, scale, capacity)
    svg.txt(width / 2, height - 49, "Number of SFUs", 13, INK, 700, "middle")
    svg.line(34, height - 37, width - 34, height - 37, GRID)
    svg.txt(35, height - 16, note, 12, MUTED)
    svg.save(output)


def protected_grid(groups: dict[tuple, dict[int, dict]], output: Path) -> None:
    metrics = (
        Metric("throughput_mbps", "Delivered user payload", "Mb/s"),
        Metric("reference_actual_coverage", "Reference bytes carried on due frames", "%", 100, ceiling=100),
        Metric("fifo_mean_ns", "Mean optical FIFO wait", "ms", 1e-6),
        Metric("fifo_p95_ns", "P95 optical FIFO wait", "ms", 1e-6, tail_flag="p95_tail_support"),
    )
    kinds = (1, 2, 3)
    width, height = 1420, 297 + 187 * len(metrics)
    title = "Service isolation as T4 input increases"
    subtitle = "100 ms observation · 4 or 8 SFUs · 2 STAs per SFU · Fixed high-priority input within each topology"
    svg = start_figure(width, height, title, subtitle, len(next(iter(groups.values()))))
    svg.line(38, 141, 58, 141, MUTED, 1.5)
    svg.txt(63, 145, "4 SFUs", 12, MUTED)
    svg.line(139, 141, 159, 141, MUTED, 1.5, "4 3")
    svg.txt(164, 145, "8 SFUs", 12, MUTED)
    cell_w = (width - 96) / 3
    for c, kind in enumerate(kinds):
        x = 34 + c * (cell_w + 14)
        svg.txt(x + cell_w / 2, 177, f"T{kind}", 19, INK, 700, "middle")
        for r, metric in enumerate(metrics):
            y = 215 + r * 187
            svg.txt(x + 1, y - 9, f"{metric.title} ({metric.unit})", 14, INK, 700)
            series = [
                Series(policy, shift, [
                    measure(groups["isolation", load, sfu, policy, kind], metric) for load in ISOLATION_LOADS
                ], dash, f"{sfu} SFUs")
                for sfu, dash, shifts in ((4, "", (-10, -3)), (8, "4 3", (3, 10)))
                for policy, shift in zip((GIANT, RR), shifts)
            ]
            draw_panel(svg, (x, y, cell_w, 152),
                       tuple(load / 1000 for load in ISOLATION_LOADS), series, domain(series, metric))
    svg.txt(width / 2, height - 59, "T4 offered load (Gb/s)", 13, INK, 700, "middle")
    svg.line(34, height - 46, width - 34, height - 46, GRID)
    svg.txt(35, height - 27, "Each panel has its own y scale. FIFO wait is enqueue-to-load. "
            "Queued samples still waiting at the cutoff are censored. Rings mark limited tail samples.", 12, MUTED)
    svg.txt(35, height - 10, "Coverage counts due fixed bytes and caps due assured bytes at MFU-visible demand. "
            "A missing coverage point means the denominator was zero.", 12, MUTED)
    svg.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS, help="Directory containing experiment CSVs")
    parser.add_argument("--output", type=Path, help="SVG destination (default: <input>/figures)")
    args = parser.parse_args()
    out = args.output or args.input / "figures"
    out.mkdir(parents=True, exist_ok=True)
    link = read_groups(args.input / "link-throughput-utilization.csv", "link", LINK_CONDITIONS)
    queue = read_groups(args.input / "queue-accumulation.csv", "link", LINK_CONDITIONS)
    protected = read_groups(args.input / "protected-sla.csv", "type",
                            tuple(("isolation", load) for load in ISOLATION_LOADS), (1, 2, 3))

    topology_grid(link, (
        Metric("throughput_mbps", "Delivered user payload", "Mb/s"),
        Metric("utilization_fraction", "Actual DATA and REPORT airtime", "%", 100, ceiling=100),
        Metric("jain_sfu_throughput", "Jain index across SFUs", "0–1"),
    ), out / "link-performance.svg", "Upstream throughput, utilization and SFU fairness",
        "Dashed horizontal lines mark the 2,500 Mb/s physical rate. "
        "Utilization uses actual transmission airtime. Jain axes can start above zero.")
    topology_grid(queue, (
        Metric("peak_queue_packets", "Peak optical FIFO backlog", "packets"),
        Metric("fifo_pending_at_cutoff_packets", "Optical FIFO backlog at 100 ms", "packets"),
    ), out / "queues-and-isolation.svg", "Upstream optical FIFO backlog under overload",
        "Backlog counts packets waiting in the optical FIFO. The cutoff excludes later drain events.")
    protected_grid(protected, out / "protected-qos.svg")
    print(f"Wrote link, backlog and protected QoS figures to {out}")


if __name__ == "__main__":
    main()
