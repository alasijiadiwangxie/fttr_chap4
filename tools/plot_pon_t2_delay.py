"""Draw an SVG comparison of FIFO waiting as GIANT's T2 ABmin varies."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path
from statistics import mean

from plot_pon_qos_special import (
    GIANT, GRID, INK, MUTED, POLICIES, ROOT, RR, TYPES, Metric, Svg, axis_top, panel, read_rows, tick,
)


RESULTS = ROOT / "results" / "pon-t2-delay"
BUDGETS = (2400, 3600, 4800)
FIGURE = "delay-vs-abmin.svg"
BACKLOG_FIGURE = "t2-backlog-over-time.svg"
BIN_NS = 5_000_000


def plot_rows(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    """Put the measured RR baseline on all three x positions only for drawing."""
    by_type_policy_budget = defaultdict(set)
    for row in rows:
        policy = row["policy"]
        kind = int(row["tcont_type"])
        budget = int(row["abmin_t2_bytes"]) if policy == GIANT else 0
        if kind not in TYPES or policy not in POLICIES or (policy == GIANT and budget not in BUDGETS):
            raise ValueError(f"Unexpected plot group {(kind, policy, budget)}")
        key = kind, policy, budget
        seed = row["seed"]
        if seed in by_type_policy_budget[key]:
            raise ValueError(f"Duplicate seed {seed} for group {key}")
        by_type_policy_budget[key].add(seed)
    expected = {(kind, GIANT, budget) for kind in TYPES for budget in BUDGETS}
    expected |= {(kind, RR, 0) for kind in TYPES}
    if (set(by_type_policy_budget) != expected
            or len({frozenset(seeds) for seeds in by_type_policy_budget.values()}) != 1):
        raise ValueError("Expected matching GIANT budget and RR baseline seeds for T1–T4")

    return [
        {**row, "x_mbps": str(budget)}
        for row in rows
        for budget in (BUDGETS if row["policy"] == RR else (int(row["abmin_t2_bytes"]),))
    ]


def render(rows: list[dict[str, str]], output: Path) -> None:
    data = plot_rows(rows)
    metrics = (
        Metric("fifo_mean_ns", "Mean", "ms", 1e-6),
        Metric("fifo_p95_ns", "P95", "ms", 1e-6,
               tail_flag="p95_tail_support" if "p95_tail_support" in rows[0] else None),
        Metric("fifo_p99_ns", "P99", "ms", 1e-6,
               tail_flag="p99_tail_support" if "p99_tail_support" in rows[0] else None),
        Metric("fifo_jitter_sd_ns", "Jitter SD", "ms", 1e-6),
    )
    title = "FIFO waiting versus the T2 assured budget"
    subtitle = "3050 Mb/s offered load · T2 SImax = 1 frame · Fixed packet arrivals within each seed"
    footer = ("FIFO wait is enqueue-to-load. Jitter is sample SD. Waits still in FIFO at the cutoff are censored. "
              "Dotted rings indicate limited tail support.")
    width, height = 1640, 1055
    start_y, x_offset, row_h, gap = 182, 118, 184, 16
    cell_w = (width - x_offset - 33 - (len(metrics) - 1) * gap) / len(metrics)
    svg = Svg(width, height, title, subtitle + " " + footer)
    svg.rect(34, 29, 35, 3, POLICIES[GIANT][1])
    svg.txt(34, 67, title, 29, INK, 700)
    svg.txt(35, 94, subtitle, 14, MUTED)
    for policy, label_left in ((GIANT, 42), (RR, 220)):
        label, color = POLICIES[policy]
        svg.line(label_left, 123, label_left + 24, 123, color, 2, "5 3" if policy == RR else "")
        svg.marker(label_left + 12, 123, policy)
        svg.txt(label_left + 33, 128, label, 14)
    svg.txt(370, 128, "Points = seed mean   |   Whiskers = seed min–max   |   RR = one matched baseline per seed",
            13, MUTED)
    for col, metric in enumerate(metrics):
        x = x_offset + col * (cell_w + gap)
        svg.txt(x + cell_w / 2, 167, f"{metric.title} ({metric.unit})", 16, INK, 700, "middle")
    for index, kind in enumerate(TYPES):
        y = start_y + index * (row_h + gap)
        svg.rect(33, y + 22, 63, 28, "#EAF3F7", radius=4)
        svg.txt(64.5, y + 42, f"T{kind}", 16, "#164B61", 700, "middle")
        for line, label in enumerate(TYPES[kind].split(" + ")):
            svg.txt(34, y + 68 + line * 15, label, 9, MUTED, 700)
        for col, metric in enumerate(metrics):
            x = x_offset + col * (cell_w + gap)
            panel(svg, (x, y, cell_w, row_h), data, list(BUDGETS), metric, kind)
    svg.txt((width + x_offset) / 2, height - 49, "GIANT T2 ABmin per SFU (bytes)", 14, INK, 700, "middle")
    svg.line(34, height - 37, width - 33, height - 37, GRID)
    svg.txt(35, height - 16, footer, 12, MUTED)
    svg.save(output)


def render_backlog(csv_path: Path, output: Path) -> None:
    """Sum both SFUs at each frame start, then take 5 ms means within each seed."""
    frames: dict[tuple[str, int, str, int], dict[str, int]] = defaultdict(dict)
    with csv_path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            if row["in_observation"].lower() != "true":
                continue
            policy = row["policy"]
            budget = int(row["abmin_bytes_per_sfu"]) if policy == GIANT else 0
            key = policy, budget, row["seed"], int(row["frame_start_ns"])
            alloc = row["allocation_id"]
            if alloc in frames[key]:
                raise ValueError(f"Duplicate T2 backlog frame/allocation {key + (alloc,)}")
            frames[key][alloc] = int(row["frame_start_fifo_packets"])
    frame_grid = defaultdict(set)
    by_seed_and_bin = defaultdict(list)
    for (policy, budget, seed, frame_start), counts in frames.items():
        if set(counts) != {"102", "202"}:
            raise ValueError(f"Expected both T2 queues in every frame, found {sorted(counts)}")
        frame_grid[policy, budget, seed].add(frame_start)
        by_seed_and_bin[policy, budget, seed, frame_start // BIN_NS].append(sum(counts.values()))
    if len({frozenset(times) for times in frame_grid.values()}) != 1:
        raise ValueError("Backlog variants must share the same observation frame grid")
    by_variant = defaultdict(lambda: defaultdict(list))
    for (policy, budget, _, bin_id), values in by_seed_and_bin.items():
        by_variant[policy, budget][bin_id].append(mean(values))
    variants = [(GIANT, value) for value in BUDGETS] + [(RR, 0)]
    if set(by_variant) != set(variants):
        raise ValueError("Backlog plot requires all three GIANT budgets and the RR baseline")

    max_bin = max(bin_id for series in by_variant.values() for bin_id in series)
    max_x_ms = (max_bin + 1) * BIN_NS / 1e6
    top = axis_top([value for variant in by_variant.values() for group in variant.values() for value in group],
                   Metric("packets", "", "packets"))
    width, height = 1250, 700
    subtitle = "Same offered load and input streams · Both SFUs summed at each target-frame start"
    footer = "Each point is a 5 ms mean within each seed. Shading spans the seed min–max. Drain frames are excluded."
    svg = Svg(width, height, "T2 backlog during active traffic", subtitle + " " + footer)
    svg.rect(34, 28, 35, 3, POLICIES[GIANT][1])
    svg.txt(34, 64, "T2 backlog during active traffic", 28, INK, 700)
    svg.txt(35, 91, subtitle, 14, MUTED)
    for index, (policy, budget) in enumerate(variants):
        x, y = 34 + index % 2 * 600, 122 + index // 2 * 249
        svg.rect(x, y, 577, 230, "#FBFDFF", GRID, 7)
        label = f"Complete GIANT · ABmin {budget:,} B" if policy == GIANT else "Round robin · matched baseline"
        svg.txt(x + 19, y + 27, label, 16, INK, 700)
        left, right, upper, bottom = x + 65, x + 553, y + 51, y + 190
        color = POLICIES[policy][1]
        to_x = lambda ms: left + ms / max_x_ms * (right - left)
        to_y = lambda packets: bottom - packets / top * (bottom - upper)
        for j in range(5):
            value = j * top / 4
            at = to_y(value)
            svg.line(left, at, right, at)
            svg.txt(left - 9, at + 4, tick(value), 12, MUTED, anchor="end")
            value_ms = j * max_x_ms / 4
            at_x = to_x(value_ms)
            svg.line(at_x, bottom, at_x, bottom + 5, GRID)
            svg.txt(at_x, bottom + 20, tick(value_ms), 12, MUTED, anchor="middle")
        series = [(to_x((bin_id + .5) * BIN_NS / 1e6), to_y(mean(values)), to_y(min(values)), to_y(max(values)))
                  for bin_id, values in sorted(by_variant[policy, budget].items())]
        band = " ".join(f"{px:g},{hi:g}" for px, _, _, hi in series)
        band += " " + " ".join(f"{px:g},{lo:g}" for px, _, lo, _ in reversed(series))
        svg.content.append(f'<polygon points="{band}" fill="{color}" opacity="0.17"/>')
        for (px, py, _, _), (qx, qy, _, _) in zip(series, series[1:]):
            svg.line(px, py, qx, qy, color, 2.2, "5 3" if policy == RR else "")
        for px, py, _, _ in series:
            svg.circ(px, py, 2.6, color, "white", 0.5)
    svg.txt(width / 2, 642, "Time (ms) · y-axis = queued packets", 14, INK, 700, "middle")
    svg.line(34, 655, width - 34, 655, GRID)
    svg.txt(35, 676, footer, 12, MUTED)
    svg.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS, help="Directory containing metrics.csv")
    parser.add_argument("--output", type=Path, help="Output directory, defaults to the input directory's figures subdirectory")
    args = parser.parse_args()
    out = args.output or args.input / "figures"
    out.mkdir(parents=True, exist_ok=True)
    render(read_rows(args.input / "metrics.csv"), out / FIGURE)
    print(f"Wrote {out / FIGURE}")
    trace = args.input / "t2-grant-trace.csv"
    if trace.is_file():
        render_backlog(trace, out / BACKLOG_FIGURE)
        print(f"Wrote {out / BACKLOG_FIGURE}")


if __name__ == "__main__":
    main()
