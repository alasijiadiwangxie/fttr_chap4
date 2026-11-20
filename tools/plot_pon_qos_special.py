"""Render static SVG comparisons from the dedicated DBA QoS experiment CSVs."""

from __future__ import annotations

import argparse
import csv
import math
from dataclasses import dataclass
from pathlib import Path
from statistics import mean
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results" / "pon-dba-qos-comparison"
TYPES = {1: "FIXED", 2: "ASSURED", 3: "ASSURED + NON-ASSURED", 4: "BEST EFFORT"}
INK, MUTED, GRID = "#172A3B", "#465D72", "#DFE8EF"
GIANT, RR = "giant-complete", "rr-multi"
POLICIES = {GIANT: ("Complete GIANT", "#11698A"), RR: ("Round robin", "#B95225")}


@dataclass(frozen=True)
class Metric:
    field: str
    title: str
    unit: str
    factor: float = 1
    ceiling: float | None = None
    minimum_range: float = 1
    tail_flag: str | None = None
    reference: str | None = None
    t4_field: str | None = None


@dataclass(frozen=True)
class Point:
    value: float
    low: float
    high: float
    sparse: bool


class Svg:
    """The experiment only needs plain SVG primitives, with no plotting dependencies."""

    def __init__(self, width: int, height: int, title: str, description: str):
        self.content = [
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
            f'width="{width}" height="{height}" role="img" aria-labelledby="title desc">',
            f'<title id="title">{escape(title)}</title><desc id="desc">{escape(description)}</desc>',
            '<g font-family="Arial, Helvetica, sans-serif">',
        ]
        self.rect(0, 0, width, height, "white")

    def txt(self, x: float, y: float, value: str, size: int = 14, fill: str = INK,
            weight: int = 400, anchor: str = "start") -> None:
        self.content.append(f'<text x="{x:g}" y="{y:g}" font-size="{size}" fill="{fill}" '
                            f'font-weight="{weight}" text-anchor="{anchor}">{escape(value)}</text>')

    def rect(self, x: float, y: float, width: float, height: float, fill: str,
             stroke: str = "none", radius: float = 0, stroke_width: float = 1) -> None:
        self.content.append(f'<rect x="{x:g}" y="{y:g}" width="{width:g}" height="{height:g}" '
                            f'rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="{stroke_width}"/>')

    def circ(self, x: float, y: float, radius: float, fill: str, stroke: str,
             width: float = 1, dash: str = "") -> None:
        self.content.append(f'<circle cx="{x:g}" cy="{y:g}" r="{radius:g}" fill="{fill}" stroke="{stroke}" '
                            f'stroke-width="{width:g}" stroke-dasharray="{dash or "none"}"/>')

    def line(self, x: float, y: float, x2: float, y2: float, color: str = GRID,
             width: float = 1, dash: str = "", opacity: float = 1) -> None:
        self.content.append(f'<line x1="{x:g}" y1="{y:g}" x2="{x2:g}" y2="{y2:g}" stroke="{color}" '
                            f'stroke-width="{width:g}" stroke-dasharray="{dash or "none"}" opacity="{opacity:g}"/>')

    def marker(self, x: float, y: float, policy: str, sparse: bool = False) -> None:
        color = POLICIES[policy][1]
        if policy == GIANT:
            self.circ(x, y, 3.5, color, "white", 0.8)
        else:
            self.rect(x - 4.7, y - 4.7, 9.4, 9.4, "none", color, 0, 1.8)
        if sparse:
            self.circ(x, y, 8, "none", color, 1, "2 2")

    def save(self, path: Path) -> None:
        path.write_text("\n".join([*self.content, "</g>", "</svg>", ""]), encoding="utf-8")


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as file:
        return [row for row in csv.DictReader(file) if row["scope"] == "type"]


def numeric(raw: str | None) -> float | None:
    if raw is None or raw.strip().lower() in ("", "na", "n/a", "none", "nan", "unsupported"):
        return None
    value = float(raw)
    if value < 0 or not math.isfinite(value):
        raise ValueError(f"Cannot plot a negative or nonfinite QoS value: {raw}")
    return value


def point(rows: list[dict[str, str]], metric: Metric, tcont: int) -> Point | None:
    # Type 4 has no reference SLA. Its vacuous count of zero is not evidence of compliance.
    if not rows or (tcont == 4 and metric.field.startswith("reference_")):
        return None
    field = metric.t4_field if tcont == 4 and metric.t4_field else metric.field
    # Some optional SLA values are legitimately unavailable. Missing CSV columns are errors.
    samples = [(value * metric.factor, bool(metric.tail_flag and row[metric.tail_flag].lower() != "supported"))
               for row in rows if (value := numeric(row[field])) is not None]
    if not samples:
        return None
    values, flags = zip(*samples)
    return Point(mean(values), min(values), max(values), any(flags))


def axis_top(values: list[float], metric: Metric) -> float:
    if metric.ceiling is not None:
        return metric.ceiling
    maximum = max(values, default=0)
    if maximum == 0:
        return metric.minimum_range
    target = maximum * 1.12
    order = 10 ** math.floor(math.log10(target))
    return next(order * step for step in (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10)
                if order * step >= target)


def tick(value: float) -> str:
    return f"{value:,.0f}" if value == round(value) else f"{value:,.3f}".rstrip("0").rstrip(".")


def panel(svg: Svg, box: tuple[float, float, float, float], rows: list[dict[str, str]],
          loads: list[float], metric: Metric, tcont: int) -> None:
    x, y, width, height = box
    svg.rect(x, y, width, height, "#FBFDFF", GRID, 7)
    matching = [row for row in rows if int(row["tcont_type"]) == tcont]
    lines = {policy: [point([row for row in matching if row["policy"] == policy
                             and float(row["x_mbps"]) == load], metric, tcont) for load in loads]
             for policy in POLICIES}
    references = [numeric(row[metric.reference]) for row in matching] if metric.reference else []
    refs = {value for value in references if value is not None and value > 0}
    if len(refs) > 1:
        raise ValueError(f"Expected a single configured bandwidth reference for T{tcont}: {refs}")
    actual_ref = next(iter(refs), None)
    values = [sample.high for group in lines.values() for sample in group if sample is not None]
    top = axis_top(values + ([actual_ref] if actual_ref is not None else []), metric)
    left, right = x + 61, x + width - 22
    top_y, bottom = y + 20, y + height - 28
    min_x, max_x = loads[0], loads[-1]

    def to_x(load: float) -> float:
        # Leave a few pixels on both sides so the tail rings remain in the panel.
        return left + 10 + (right - left - 20) * ((load - min_x) / (max_x - min_x) if max_x > min_x else 0.5)

    def to_y(value: float) -> float:
        return bottom - value * (bottom - top_y) / top

    for index in range(5):
        value = top * index / 4
        line_y = to_y(value)
        svg.line(left, line_y, right, line_y)
        svg.txt(left - 9, line_y + 4, tick(value), 12, MUTED, anchor="end")
    for load in loads:
        line_x = to_x(load)
        svg.line(line_x, bottom, line_x, bottom + 5, GRID)
        svg.txt(line_x, bottom + 20, tick(load), 11, MUTED, anchor="middle")
    if actual_ref is not None:
        svg.line(left, to_y(actual_ref), right, to_y(actual_ref), "#5E7183", 1.2, "2.5 3.5")

    if not values:
        svg.txt((left + right) / 2, (top_y + bottom) / 2 + 4,
                "N/A · no applicable observations", 12, MUTED, anchor="middle")
        return

    for policy, line in lines.items():
        color = POLICIES[policy][1]
        previous = None
        for load, sample in zip(loads, line):
            if sample is None:
                previous = None
                continue
            px, py = to_x(load), to_y(sample.value)
            if previous is not None:
                svg.line(*previous, px, py, color, 2.3, "5 3" if policy == RR else "")
            previous = (px, py)
        for load, sample in zip(loads, line):
            if sample is None:
                continue
            px = to_x(load)
            if sample.low != sample.high:
                lo, hi = to_y(sample.low), to_y(sample.high)
                svg.line(px, lo, px, hi, color, 1, opacity=0.58)
                svg.line(px - 3, lo, px + 3, lo, color, 1, opacity=0.58)
                svg.line(px - 3, hi, px + 3, hi, color, 1, opacity=0.58)
            svg.marker(px, to_y(sample.value), policy, sample.sparse)


def figure(output: Path, filename: str, rows_by_column: list[list[dict[str, str]]], metrics: tuple[Metric, ...],
           title: str, subtitle: str, footer: str, series: str = "load", types: tuple[int, ...] = (1, 2, 3, 4)) -> None:
    sets = [[row for row in rows if row["series"] == series] for rows in rows_by_column]
    loads = sorted({float(row["x_mbps"]) for rows in sets for row in rows})
    if not loads:
        raise ValueError(f"Expected plot data for experiment series {series}")
    if len(sets) != len(metrics):
        raise ValueError("One data set is required for each plot column")
    width, row_h, gap = (1640 if len(metrics) == 4 else 1330), 184, 16
    start_y, x_offset = 182, 118
    height = start_y + len(types) * (row_h + gap) + 73
    cell_w = (width - x_offset - 33 - gap * (len(metrics) - 1)) / len(metrics)
    svg = Svg(width, height, title, subtitle + " " + footer)
    svg.rect(34, 29, 35, 3, "#11698A")
    svg.txt(34, 67, title, 29, INK, 700)
    svg.txt(35, 94, subtitle, 14, MUTED)
    for policy, lx in ((GIANT, 42), (RR, 220)):
        svg.line(lx, 123, lx + 24, 123, POLICIES[policy][1], 2, "5 3" if policy == RR else "")
        svg.marker(lx + 12, 123, policy)
        svg.txt(lx + 33, 128, POLICIES[policy][0], 14)
    svg.txt(370, 128, "Points = seed mean   |   Whiskers = seed min–max", 13, MUTED)
    for col, metric in enumerate(metrics):
        mid = x_offset + col * (cell_w + gap) + cell_w / 2
        svg.txt(mid, 167, f"{metric.title} ({metric.unit})", 16, INK, 700, "middle")
    for index, tcont in enumerate(types):
        y = start_y + index * (row_h + gap)
        svg.rect(33, y + 22, 63, 28, "#EAF3F7", radius=4)
        svg.txt(64.5, y + 42, f"T{tcont}", 16, "#164B61", 700, "middle")
        for i, word in enumerate(TYPES[tcont].split(" + ")):
            svg.txt(34, y + 68 + i * 15, word, 9, MUTED, 700)
        for col, metric in enumerate(metrics):
            panel(svg, (x_offset + col * (cell_w + gap), y, cell_w, row_h), sets[col], loads, metric, tcont)
    xlabel = ("Configured Type 4 input (Mb/s)" if series == "isolation"
              else "Total configured offered load (Mb/s)")
    svg.txt((width + x_offset) / 2, height - 49, xlabel, 14, INK, 700, "middle")
    svg.line(34, height - 37, width - 33, height - 37, GRID)
    svg.txt(35, height - 16, footer, 12, MUTED)
    svg.save(output / filename)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()
    out = args.output or args.input / "figures"
    out.mkdir(parents=True, exist_ok=True)
    latency, sla, loss = (read_rows(args.input / f"{name}.csv") for name in ("latency", "sla", "loss"))

    central = (Metric("fifo_mean_ns", "Mean", "ms", 1e-6),
               Metric("fifo_jitter_sd_ns", "Jitter SD", "ms", 1e-6),
               Metric("fifo_max_ns", "Sample maximum", "ms", 1e-6))
    tail = tuple(Metric(f"fifo_{name}_ns", display, "ms", 1e-6, tail_flag=f"{name}_tail_support")
                 for name, display in (("p95", "P95"), ("p99", "P99"), ("p999", "P99.9")))
    goodput = Metric("actual_goodput_mbps", "MFU delivered throughput", "Mb/s", reference="configured_sla_mbps")
    figure(out, "delay-summary.svg", [latency] * 3, central,
           "Optical FIFO waiting as load rises",
           "T1–T4 · FIFO enqueue to grant loading · Compare by class across the same Poisson inputs",
           "Jitter = per-run sample standard deviation. Maxima are observed samples. Check the backlog figures for censored packets.")
    figure(out, "delay-tail.svg", [latency] * 3, tail,
           "Tail of optical FIFO waiting time",
           "T1–T4 · Nearest-rank estimates per seed · Panel scales vary with class and metric",
           "Dotted rings flag limited tail support in at least one measured seed. Missing observations have no marker.")
    figure(out, "sla-bandwidth.svg", [sla] * 4, (
        goodput,
        Metric("max_actual_service_gap_frames", "Max. observed service gap", "frames"),
        Metric("jain_same_type_goodput", "Same-type Jain index", "%", 100, ceiling=100),
        Metric("non_assured_share_of_surplus_granted", "Share of surplus grants", "%", 100,
               ceiling=100, t4_field="best_effort_share_of_surplus_granted"),
    ), "Delivered bandwidth and service regularity",
        "T1–T4 · Configured SLA shown by grey dotted lines · Two SFUs compared within each type",
        "Service gap uses successive frames with actual sending. Surplus share: T3 non-assured, T4 best-effort. RR has no phase labels.")
    figure(out, "sla-coverage.svg", [sla] * 3, (
        Metric("reference_grant_coverage", "Reference grant coverage", "%", 100, ceiling=100),
        Metric("reference_actual_coverage", "Reference actual coverage", "%", 100, ceiling=100),
        Metric("reference_grant_violations", "Reference grant shortfalls", "count"),
    ), "SLA reference: reserved and actually carried",
        "T1–T4 · Offline reference from configured SI/AB and actual FIFO at target-frame start · T4: N/A",
        "Compare this common opportunity test for both schedulers. GIANT's phase-specific counts and rates are in sla.csv.")
    figure(out, "loss-backlog.svg", [loss] * 3, (
        Metric("actual_drop_fraction", "Terminal drop rate", "%", 100),
        Metric("peak_queue_packets", "Peak optical FIFO", "packets"),
        Metric("final_pending_packets", "Final pending", "packets"),
    ), "Terminal drops and queue accumulation",
        "T1–T4 · Backlog and explicit terminal drops are counted separately",
        "Overflow drops: N/A. These runs use the current unbounded optical FIFO. A zero terminal drop rate can coexist with backlog.")
    figure(out, "isolation.svg", [sla, latency, latency], (goodput, central[0], central[1]),
           "Isolation: fixed T1 and T2 input under T4 pressure",
           "T1 and T2 · Identical protected input across the load sweep · Observe throughput, FIFO mean and jitter",
           "Only the Type 4 input rate changes along the x-axis. A grey dotted line marks the configured protected rate.",
           series="isolation", types=(1, 2))
    print(f"Wrote six SVG figures to {out}")


if __name__ == "__main__":
    main()
