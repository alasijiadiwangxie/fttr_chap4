"""Create the five EDCA figures from the focused experiment's aggregate CSV."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter, LogLocator, MaxNLocator, NullFormatter


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results" / "edca-focus"
ACCESS_CATEGORIES = {
    "VO": ("#0072B2", "o"),
    "VI": ("#D55E00", "s"),
    "BE": ("#00876C", "^"),
    "BK": ("#7A5195", "D"),
}
LOAD_LABEL = "Total offered load (Mb/s)"


def measurements(rows: list[dict], metric: str, scale: float = 1) -> tuple[list[float], list[float]]:
    means = [float(row[f"{metric}_mean"]) / scale for row in rows]
    deviations = [float(row[f"{metric}_sd"]) / scale for row in rows]
    return means, deviations


def series(axis, x: list[float], values: list[float], deviations: list[float], label: str,
           color: str, marker: str, *, dashed: bool = False) -> None:
    # Metrics are non-negative. Keep a zero lower bound when mean minus SD is negative.
    lower = [min(value, deviation) for value, deviation in zip(values, deviations)]
    axis.errorbar(x, values, yerr=[lower, deviations], label=label, color=color,
                  linestyle="--" if dashed else "-", marker=marker,
                  markerfacecolor="white" if dashed else color, markeredgewidth=1.45,
                  markersize=6.5, linewidth=2.0, elinewidth=1.0, capsize=3)


def style_axis(axis, xlabel: str, ylabel: str) -> None:
    axis.set_xlabel(xlabel)
    axis.set_ylabel(ylabel)
    axis.spines[["top", "right"]].set_visible(False)
    axis.set_axisbelow(True)
    axis.grid(axis="y", color="#DCE3EB", linewidth=0.8)
    axis.tick_params(length=4)


def save(figure, output: Path, name: str, title: str, subtitle: str) -> None:
    figure.text(0.10, 0.96, title, fontsize=20, fontweight="bold", va="top")
    figure.text(0.10, 0.895, subtitle, fontsize=11, color="#46566B", va="top")
    figure.savefig(output / name, dpi=220, facecolor="white")
    plt.close(figure)


def load_series(axis, rows: list[dict], metric: str, scale: float = 1) -> None:
    loads = sorted({float(row["offered_mbps"]) for row in rows})
    if not loads:
        raise ValueError("Expected rows for the load experiment")
    for ac, (color, marker) in ACCESS_CATEGORIES.items():
        selected = sorted((row for row in rows if row["ac"] == ac),
                          key=lambda row: float(row["offered_mbps"]))
        if [float(row["offered_mbps"]) for row in selected] != loads:
            raise ValueError(f"Expected one load result for each point in AC {ac}")
        values, deviations = measurements(selected, metric, scale)
        series(axis, loads, values, deviations, ac, color, marker)
    axis.set_xticks(loads, [f"{point:g}" for point in loads])


def load_figure(rows: list[dict], output: Path, name: str, metric: str, title: str,
                subtitle: str, ylabel: str, scale: float = 1, *, logarithmic: bool = False) -> None:
    figure, axis = plt.subplots(figsize=(8.5, 5.1))
    figure.subplots_adjust(left=0.14, right=0.96, bottom=0.16, top=0.79)
    use_log_scale = logarithmic and all(mean > sd for mean, sd in zip(*measurements(rows, metric, scale)))
    if use_log_scale:
        axis.set_yscale("log")
        axis.yaxis.set_major_locator(LogLocator(subs=(1, 2, 5)))
        axis.yaxis.set_major_formatter(FuncFormatter(lambda value, _: f"{value:g}"))
        axis.yaxis.set_minor_formatter(NullFormatter())
        ylabel += " · log scale"
    load_series(axis, rows, metric, scale)
    if not use_log_scale:
        axis.set_ylim(bottom=0)
    style_axis(axis, LOAD_LABEL, ylabel)
    axis.legend(loc="upper left", ncol=4, columnspacing=1.15, frameon=True)
    save(figure, output, name, title, subtitle)


def collision_figure(rows: list[dict], output: Path) -> None:
    figure, axes = plt.subplots(1, 2, sharey=True, figsize=(10.4, 5.0))
    figure.subplots_adjust(left=0.10, right=0.97, bottom=0.17, top=0.73, wspace=0.15)
    panels = (("physical_collision_attempts", "Collided transmissions"),
              ("retransmissions", "Retransmissions"))
    for index, (axis, (metric, title)) in enumerate(zip(axes, panels)):
        load_series(axis, rows, metric)
        style_axis(axis, LOAD_LABEL, "Attempts per run" if index == 0 else "")
        axis.set_title(title, loc="left", fontsize=13, fontweight="bold", pad=9)
    axes[0].set_ylim(bottom=0)
    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="upper right", bbox_to_anchor=(0.97, 0.865),
                  ncol=4, columnspacing=1.25, frameon=False)
    save(figure, output, "04-collisions-and-retries-vs-load.png", "04   Collisions and retries as load rises",
         "Per-AC counts over the full run · Mean ± sample SD over fixed seeds")


def bandwidth_figure(rows: list[dict], output: Path) -> None:
    bandwidths = sorted({int(row["bandwidth_mhz"]) for row in rows})
    if not bandwidths:
        raise ValueError("Expected rows for the bandwidth experiment")
    figure, axes = plt.subplots(1, 2, sharey=True, figsize=(9.0, 4.9))
    figure.subplots_adjust(left=0.12, right=0.96, bottom=0.16, top=0.73, wspace=0.15)
    x = list(range(len(bandwidths)))
    for index, (axis, ac) in enumerate(zip(axes, ("VO", "VI"))):
        color, marker = ACCESS_CATEGORIES[ac]
        for profile, label, offset in (("single", "TXOP off", -0.045), ("txop", "TXOP on", 0.045)):
            selected = sorted((row for row in rows if row["ac"] == ac and row["profile"] == profile),
                              key=lambda row: int(row["bandwidth_mhz"]))
            if [int(row["bandwidth_mhz"]) for row in selected] != bandwidths:
                raise ValueError(f"Expected one bandwidth result for AC {ac} and profile {profile}")
            values, deviations = measurements(selected, "avg_access_delay_us")
            series(axis, [point + offset for point in x], values, deviations, label, color, marker,
                   dashed=profile == "single")
        style_axis(axis, "Channel bandwidth (MHz)", "Mean access delay (μs)" if index == 0 else "")
        axis.set_title(ac, loc="left", fontsize=14, fontweight="bold", color=color, pad=9)
        axis.set_xticks(x, [str(bandwidth) for bandwidth in bandwidths])
        axis.legend(loc="upper right", frameon=True)
    axes[0].set_ylim(bottom=0)
    axes[0].yaxis.set_major_locator(MaxNLocator(nbins=6))
    load, = {float(row["offered_mbps"]) for row in rows}
    save(figure, output, "05-bandwidth-and-txop.png", "05   Bandwidth and TXOP for VO and VI",
         f"{load:g} Mb/s total input · Mean ± sample SD over fixed seeds · Shared delay scale")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS / "aggregate.csv")
    parser.add_argument("--output", type=Path, default=RESULTS / "figures")
    args = parser.parse_args()
    with args.input.open(encoding="utf-8-sig", newline="") as source:
        rows = [row for row in csv.DictReader(source) if row.get("direction", "ALL") == "ALL"]
    load = [row for row in rows if row["experiment"] == "load"]
    bandwidth = [row for row in rows if row["experiment"] == "bandwidth"]
    args.output.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({
        "font.family": "DejaVu Sans",
        "font.size": 11,
        "axes.labelsize": 11,
        "axes.labelcolor": "#243246",
        "axes.edgecolor": "#647386",
        "xtick.color": "#35455B",
        "ytick.color": "#35455B",
        "text.color": "#172538",
        "legend.fontsize": 10,
        "legend.edgecolor": "#DCE3EB",
        "legend.framealpha": 0.96,
    })
    load_figure(load, args.output, "01-access-delay-vs-load.png", "avg_access_delay_us",
                "01   Channel access delay by traffic class", "160 MHz, TXOP off · Mean ± sample SD over fixed seeds",
                "Mean access delay (μs)", logarithmic=True)
    load_figure(load, args.output, "02-end-to-end-delay-vs-load.png", "avg_e2e_delay_us",
                "02   End-to-end delay as load rises", "160 MHz, TXOP off · Mean ± sample SD over fixed seeds",
                "Mean end-to-end delay (ms)", scale=1000, logarithmic=True)
    load_figure(load, args.output, "03-throughput-vs-load.png", "offered_window_throughput_mbps",
                "03   Payload throughput by traffic class",
                "Delivered within the generation window · Mean ± sample SD over fixed seeds",
                "Payload throughput (Mb/s)")
    collision_figure(load, args.output)
    bandwidth_figure(bandwidth, args.output)
    print(f"Saved 5 EDCA figures to {args.output}")


if __name__ == "__main__":
    main()
