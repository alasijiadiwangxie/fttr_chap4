"""Plot T2 FIFO waiting and backlog for the matched-input SI and REPORT polling experiment."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean

from plot_pon_qos_special import (
    GIANT, GRID, INK, MUTED, POLICIES, ROOT, RR, Metric, Svg, axis_top, point, read_rows, tick,
)


RESULTS = ROOT / "results" / "pon-t2-tail"
DELAY_FIGURE = "t2-si-delay.svg"
BACKLOG_FIGURE = "t2-si-backlog.svg"
BIN_NS = 5_000_000
BASE_VARIANTS = ("rr", "giant-si1", "giant-si2")
POLL_COLORS = ("#746099", "#886618", "#516A87")


@dataclass(frozen=True)
class Variant:
    key: str
    name: str
    detail: str
    color: str


def common_setting(rows: list[dict[str, str]], field: str) -> str:
    values = {row[field] for row in rows}
    if len(values) != 1:
        raise ValueError(f"Expected one {field}, got {sorted(values)}")
    return next(iter(values))


def delay_groups(rows: list[dict[str, str]]) -> tuple[list[Variant], dict[str, list[dict[str, str]]]]:
    grouped = defaultdict(list)
    for row in rows:
        if int(row["tcont_type"]) == 2:
            grouped[row["variant"]].append(row)
    if not set(BASE_VARIANTS) <= grouped.keys():
        raise ValueError("Expected RR, GIANT SI1 and GIANT SI2 T2 metrics")
    extras = sorted(set(grouped) - set(BASE_VARIANTS))
    if len(extras) > len(POLL_COLORS):
        raise ValueError("Too many poll variants for one comparison")

    variants = []
    seed_sets = []
    for index, key in enumerate((*BASE_VARIANTS, *extras)):
        samples = grouped[key]
        seed_set = {int(row["seed"]) for row in samples}
        if len(seed_set) != len(samples):
            raise ValueError(f"Duplicate seed for {key} in T2 metrics")
        seed_sets.append(seed_set)
        expected_policy = RR if key == "rr" else GIANT
        if common_setting(samples, "policy") != expected_policy:
            raise ValueError(f"Unexpected policy for {key}")
        poll = int(common_setting(samples, "poll_interval_frames"))
        if key == "rr":
            name, detail, color = "RR reference", f"Poll every {poll} fr.", POLICIES[RR][1]
        else:
            si = int(common_setting(samples, "si_max_t2_frames"))
            budget = int(common_setting(samples, "abmin_t2_bytes"))
            name, detail = f"GIANT SImax {si}", f"{budget:,} B · poll / {poll} fr."
            color = (POLICIES[GIANT][1], "#297866", *POLL_COLORS)[index - 1]
        variants.append(Variant(key, name, detail, color))
    if any(seeds != seed_sets[0] for seeds in seed_sets):
        raise ValueError("T2 delay variants must contain the same seed set")
    return variants, grouped


def frame_series(csv_path: Path, groups: dict[str, list[dict[str, str]]],
                 window_ns: int) -> dict[str, dict[int, list[float]]]:
    """Combine frame-start queue length across T2 allocations, then bin within each seed."""
    case_keys = {(row["variant"], int(row["seed"])): row["scenario_id"]
                 for samples in groups.values() for row in samples}
    frames: dict[tuple[str, int, int], dict[str, int]] = defaultdict(dict)
    with csv_path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            key = row["variant"], int(row["seed"])
            frame_start = int(row["frame_start_ns"])
            if (case_keys.get(key) != row["scenario_id"] or frame_start >= window_ns
                    or row["in_observation"].lower() != "true"):
                continue
            allocation_id = row["allocation_id"]
            counts = frames[(*key, frame_start)]
            if allocation_id in counts:
                raise ValueError(f"Duplicate T2 row for {key}, {frame_start}, {allocation_id}")
            counts[allocation_id] = int(row["frame_start_fifo_packets"])

    if not frames:
        raise ValueError("No frame-start FIFO data found for the selected observation window")
    allocations = set(next(iter(frames.values())))
    grid = defaultdict(set)
    by_seed_and_bin = defaultdict(list)
    for (variant, seed, frame_start), counts in frames.items():
        if counts.keys() != allocations:
            raise ValueError(f"Missing T2 allocation in {variant}, seed {seed}, frame {frame_start}")
        grid[variant, seed].add(frame_start)
        by_seed_and_bin[variant, seed, frame_start // BIN_NS].append(sum(counts.values()))
    if set(grid) != set(case_keys) or len({frozenset(times) for times in grid.values()}) != 1:
        raise ValueError("Backlog variants must share the same seed set and observation frame grid")

    series = defaultdict(lambda: defaultdict(list))
    for (variant, _, bin_id), samples in by_seed_and_bin.items():
        series[variant][bin_id].append(mean(samples))
    return series


def y_axis(svg: Svg, bounds: tuple[float, float, float, float], top: float) -> None:
    left, right, upper, bottom = bounds
    for j in range(5):
        y = bottom - (bottom - upper) * j / 4
        svg.line(left, y, right, y)
        svg.txt(left - 10, y + 4, tick(top * j / 4), 12, MUTED, anchor="end")


def header(svg: Svg, title: str, subtitle: str) -> None:
    svg.rect(34, 28, 35, 3, POLICIES[GIANT][1])
    svg.txt(34, 66, title, 30, INK, 700)
    svg.txt(35, 96, subtitle, 15, MUTED)


def footer(svg: Svg, width: int, height: int, note: str, extra: str) -> None:
    svg.line(34, height - 60, width - 34, height - 60, GRID)
    svg.txt(35, height - 37, note, 13, MUTED)
    svg.txt(35, height - 16, extra, 12, MUTED)


def render_delay(variants: list[Variant], groups: dict[str, list[dict[str, str]]], subtitle: str,
                 output: Path) -> None:
    first_row = groups["rr"][0]
    metrics = (
        Metric("fifo_mean_ns", "Mean FIFO wait", "ms", 1e-6),
        Metric("fifo_p95_ns", "P95 FIFO wait", "ms", 1e-6,
               tail_flag="p95_tail_support" if "p95_tail_support" in first_row else None),
        Metric("fifo_p99_ns", "P99 FIFO wait", "ms", 1e-6,
               tail_flag="p99_tail_support" if "p99_tail_support" in first_row else None),
        Metric("fifo_jitter_sd_ns", "FIFO jitter (sample SD)", "ms", 1e-6),
    )
    title = "T2 FIFO delay by GIANT service interval and REPORT polling"
    note = "Dots show the mean of the seed statistics. Whiskers span the seed min–max. The dashed line marks RR."
    extra = "Wait runs from enqueue to load. Waits still in the FIFO at the cutoff are censored. Rings mark limited tail support."
    width, height = 1250, 774
    svg = Svg(width, height, title, f"{subtitle}. {note} {extra}")
    header(svg, title, subtitle)
    for index, metric in enumerate(metrics):
        x, y = 34 + (index % 2) * 600, 123 + (index // 2) * 294
        svg.rect(x, y, 582, 270, "#FBFDFF", GRID, 7)
        svg.txt(x + 18, y + 30, f"{metric.title} ({metric.unit})", 18, INK, 700)
        left, right, upper, bottom = x + 64, x + 565, y + 55, y + 213
        samples = [point(groups[variant.key], metric, 2) for variant in variants]
        top = axis_top([sample.high for sample in samples if sample is not None], metric)
        y_axis(svg, (left, right, upper, bottom), top)
        guide = samples[0]
        if guide is not None:
            ref_y = bottom - guide.value / top * (bottom - upper)
            svg.line(left, ref_y, right, ref_y, variants[0].color, 1, "5 4", 0.45)
        for column, (variant, sample) in enumerate(zip(variants, samples)):
            px = left + (column + 0.5) / len(variants) * (right - left)
            svg.txt(px, bottom + 23, variant.name, 13, INK, 700, "middle")
            svg.txt(px, bottom + 41, variant.detail, 11, MUTED, anchor="middle")
            if sample is None:
                svg.txt(px, bottom - 10, "No samples", 10, MUTED, anchor="middle")
                continue
            hi = bottom - sample.high / top * (bottom - upper)
            lo = bottom - sample.low / top * (bottom - upper)
            center = bottom - sample.value / top * (bottom - upper)
            svg.line(px, hi, px, lo, variant.color, 2)
            for bound in (lo, hi):
                svg.line(px - 5, bound, px + 5, bound, variant.color, 2)
            svg.circ(px, center, 4.3, variant.color, "white", 0.8)
            if sample.sparse:
                svg.circ(px, center, 8, "none", variant.color, 1, "2 2")
    footer(svg, width, height, note, extra)
    svg.save(output)


def render_backlog(variants: list[Variant], series: dict[str, dict[int, list[float]]],
                   window_ns: int, subtitle: str, output: Path) -> None:
    title = "T2 FIFO backlog during the observation window"
    note = "The line shows the mean of the per-seed 5 ms means. Shading spans the seed min–max."
    extra = "Each frame-start sample sums the T2 FIFOs across SFUs. All panels use the same time and packet scales."
    width = 1250
    rows = (len(variants) + 1) // 2
    height = 123 + rows * 252 + 99
    svg = Svg(width, height, title, f"{subtitle}. {note} {extra}")
    header(svg, title, subtitle)
    top = axis_top([value for by_bin in series.values() for values in by_bin.values() for value in values],
                   Metric("packets", "", "packets"))
    for index, variant in enumerate(variants):
        x, y = 34 + index % 2 * 600, 123 + index // 2 * 252
        svg.rect(x, y, 582, 230, "#FBFDFF", GRID, 7)
        svg.txt(x + 19, y + 29, f"{variant.name} · {variant.detail}", 16, INK, 700)
        left, right, upper, bottom = x + 64, x + 557, y + 52, y + 185
        y_axis(svg, (left, right, upper, bottom), top)
        for j in range(5):
            at_x = left + (right - left) * j / 4
            svg.line(at_x, bottom, at_x, bottom + 5)
            svg.txt(at_x, bottom + 20, tick(j * window_ns / 4e6), 12, MUTED, anchor="middle")

        coordinates = []
        for bin_id, values in sorted(series[variant.key].items()):
            start_ns = bin_id * BIN_NS
            center_ns = (start_ns + min(start_ns + BIN_NS, window_ns)) / 2
            px = left + center_ns / window_ns * (right - left)
            scale = (bottom - upper) / top
            coordinates.append((px, bottom - mean(values) * scale,
                                bottom - min(values) * scale, bottom - max(values) * scale))
        band = [f"{px:g},{hi:g}" for px, _, _, hi in coordinates]
        band.extend(f"{px:g},{lo:g}" for px, _, lo, _ in reversed(coordinates))
        svg.content.append(f'<polygon points="{" ".join(band)}" fill="{variant.color}" opacity="0.16"/>')
        for (px, py, _, _), (qx, qy, _, _) in zip(coordinates, coordinates[1:]):
            svg.line(px, py, qx, qy, variant.color, 2.1, "5 3" if variant.key == "rr" else "")
        for px, py, _, _ in coordinates:
            svg.circ(px, py, 2.2, variant.color, "white", 0.6)
    svg.txt(width / 2, height - 75, "Simulation time (ms) · queued packets", 14, INK, 700, "middle")
    footer(svg, width, height, note, extra)
    svg.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS, help="Directory containing both input CSVs")
    parser.add_argument("--output", type=Path, help="Directory for the SVGs (default: <input>/figures)")
    args = parser.parse_args()
    rows = read_rows(args.input / "si-metrics.csv")
    window_ns = int(common_setting(rows, "observation_end_ns"))
    variants, groups = delay_groups(rows)
    series = frame_series(args.input / "t2-service-trace.csv", groups, window_ns)
    load = float(common_setting(rows, "offered_mbps"))
    subtitle = (f"{load:g} Mb/s offered load · {window_ns / 1e6:g} ms observation cutoff · "
                f"{len(groups['rr'])} matched seeds · ABmin in bytes per SFU")
    output = args.output or args.input / "figures"
    output.mkdir(parents=True, exist_ok=True)
    render_delay(variants, groups, subtitle, output / DELAY_FIGURE)
    render_backlog(variants, series, window_ns, subtitle, output / BACKLOG_FIGURE)
    print(f"Wrote {output / DELAY_FIGURE}")
    print(f"Wrote {output / BACKLOG_FIGURE}")


if __name__ == "__main__":
    main()
