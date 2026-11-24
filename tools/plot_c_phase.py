"""Draw the four Chinese C-phase acceptance figures directly from reproduced CSV data."""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from itertools import pairwise
from pathlib import Path
from statistics import mean

import matplotlib

matplotlib.use("Agg")
from matplotlib import font_manager, ft2font, pyplot as plt
from matplotlib.text import Text

from verify_c_phase import LOADS, MODES, PROFILES, RESULT


COLORS = {"LCM": "#006e9a", "HCM": "#c3460b"}
MODE_ZH = {"LCM": "LCM（低能力）", "HCM": "HCM（高能力）"}
PROFILE_ZH = {"clear": "无干扰", "primary": "主信道干扰", "secondary": "次信道干扰", "wide": "宽带干扰"}
LOAD_ZH = {"low": "低负载", "mid": "中负载", "high": "高负载"}


def resolve_font(explicit: Path | None) -> Path:
    paths = (
        explicit,
        Path("C:/Windows/Fonts/msyh.ttc"),
        Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
    )
    for path in paths:
        if path is not None and path.is_file():
            return path
    raise FileNotFoundError("A Chinese font is required. Supply --font with Microsoft YaHei or Noto Sans CJK.")


def setup_font(path: Path):
    font_manager.fontManager.addfont(path)
    chinese = font_manager.FontProperties(fname=str(path))
    plt.rcParams.update({
        "font.family": [chinese.get_name()],
        "axes.unicode_minus": False,
        "svg.fonttype": "path",
        "svg.hashsalt": "fttr-c-phase",
        "savefig.facecolor": "white",
        "font.size": 10,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.grid": True,
        "axes.axisbelow": True,
        "grid.alpha": .2,
    })
    return chinese, ft2font.FT2Font(path)


def read_csv(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def float_values(rows, field, scale=1):
    return [float(row[field]) * scale for row in rows if row.get(field, "") != ""]


def select(rows, **where):
    return [row for row in rows if all(row.get(key) == value for key, value in where.items())]


def plot_bars(axis, labels, get_series, field, y_label, scale=1):
    for index, mode in enumerate(MODES):
        points = [float_values(get_series(label, mode), field, scale) for label in labels]
        positions = [idx + (-.19 if index == 0 else .19) for idx in range(len(labels))]
        centers = [mean(values) if values else float("nan") for values in points]
        errors = [[center - min(values) if values else 0 for center, values in zip(centers, points)],
                  [max(values) - center if values else 0 for center, values in zip(centers, points)]]
        axis.bar(positions, centers, yerr=errors, width=.34, color=COLORS[mode], alpha=.92,
                 edgecolor="white", capsize=3, label=MODE_ZH[mode])
    axes_labels = {**PROFILE_ZH, **LOAD_ZH}
    axis.set_xticks(range(len(labels)), [axes_labels.get(label, label) for label in labels])
    axis.set_ylabel(y_label)


def channel_figure(rows):
    figure, axes = plt.subplots(1, 2, figsize=(11.7, 4.5), layout="constrained")
    fields = (("mean_per", "平均误包概率（%）", 100), ("goodput_mbps", "成功吞吐量（Mb/s）", 1))
    for axis, (field, label, scale) in zip(axes, fields):
        plot_bars(axis, list(PROFILES), lambda profile, mode: select(rows, interference=profile, ap_mode=mode, load="mid"),
                  field, label, scale)
        axis.set_xlabel("干扰所在子信道")
    figure.suptitle("不同子信道干扰下的误包概率和成功吞吐量（中负载）", fontsize=15, fontweight="bold")
    axes[1].legend(loc="upper center", bbox_to_anchor=(.5, 1.2), ncols=2, frameon=False)
    return figure


def estimate_curve(rows, mode):
    curve = []
    for load in LOADS:
        selected = select(rows, ap_mode=mode, load=load)
        if selected:
            curve.append((mean(float(row["goodput_mbps"]) for row in selected),
                          mean(float(row["mean_network_power_w"]) for row in selected),
                          max(float(row["incomplete"]) / max(1, float(row["arrived"])) for row in selected)))
    return sorted(curve)


def interpolate(curve, x, field):
    for a, b in pairwise(curve):
        if a[0] <= x <= b[0] and b[0] > a[0]:
            if field == 2:
                return max(a[field], b[field])
            return a[field] + (b[field] - a[field]) * (x - a[0]) / (b[0] - a[0])
    return next((row[field] for row in curve if x == row[0]), None)


def crossover(left, right):
    if len(left) < 2 or len(right) < 2:
        return []
    low, high = max(left[0][0], right[0][0]), min(left[-1][0], right[-1][0])
    if high <= low:
        return []
    points = sorted({low, high} | {row[0] for row in left + right if low < row[0] < high})
    differences = {x: interpolate(left, x, 1) - interpolate(right, x, 1) for x in points}
    roots = set()
    for a, b in pairwise(points):
        da, db = differences[a], differences[b]
        if da == 0:
            roots.add(a)
        if db == 0:
            roots.add(b)
        if da * db < 0:
            roots.add(a + (b - a) * (-da) / (db - da))
    return [(x, interpolate(left, x, 1),
             max(interpolate(left, x, 2), interpolate(right, x, 2)) <= .05) for x in sorted(roots)]


def power_figure(rows):
    figure, axes = plt.subplots(2, 2, figsize=(11, 7), layout="constrained", sharey=True)
    for axis, profile in zip(axes.flat, PROFILES):
        selected = select(rows, interference=profile)
        curves = {mode: estimate_curve(selected, mode) for mode in MODES}
        for mode in MODES:
            raw = select(selected, ap_mode=mode)
            axis.scatter(float_values(raw, "goodput_mbps"), float_values(raw, "mean_network_power_w"),
                         color=COLORS[mode], s=17, alpha=.3)
            actual = curves[mode]
            axis.plot([row[0] for row in actual], [row[1] for row in actual],
                      color=COLORS[mode], marker="o", linewidth=1.6, label=MODE_ZH[mode])
        crossings = crossover(curves["LCM"], curves["HCM"])
        for index, (rate, power, feasible) in enumerate(crossings):
            marker = "D" if feasible else "x"
            axis.scatter([rate], [power], color="#303030", marker=marker, s=38, zorder=5)
            axis.annotate(f"{rate:.1f}", (rate, power), xytext=(5, 6 + 8*index), textcoords="offset points")
        print(profile, "crossings:", crossings)
        axis.set_title(PROFILE_ZH[profile])
        axis.set_xlabel("下行成功吞吐量（Mb/s）")
        axis.set_ylabel("两台 AP 平均总功率（W）")
    figure.suptitle("AP 平均功率随成功吞吐量变化", fontsize=15, fontweight="bold")
    axes[0, 1].legend(loc="upper center", bbox_to_anchor=(.53, 1.35), ncols=2, frameon=False)
    return figure


def qos_figure(rows):
    figure, axes = plt.subplots(2, 2, figsize=(11.7, 7.5), layout="constrained")
    fields = (("p95_ns", "端到端 P95 时延（ms）", 1e-6), ("p99_ns", "端到端 P99 时延（ms）", 1e-6),
              ("violation_ratio", "已判定截止时间违约率（%）", 100),
              ("incomplete", "窗口结束仍未交付（包）", 1))
    for axis, (field, label, scale) in zip(axes.flat, fields):
        plot_bars(axis, list(PROFILES), lambda profile, mode: select(rows, interference=profile, ap_mode=mode, load="high"),
                  field, label, scale)
        axis.set_xlabel("干扰所在子信道")
    figure.suptitle("高负载下的时延、截止时间与积压", fontsize=15, fontweight="bold")
    axes[0, 1].legend(loc="upper center", bbox_to_anchor=(.5, 1.2), ncols=2, frameon=False)
    return figure


def predictor_figure(rows):
    figure, axes = plt.subplots(1, 3, figsize=(14, 4.65), layout="constrained")
    panels = (("success_mbps", "actual_success_mbps", "成功吞吐量（Mb/s）", False),
              ("service_mbps", "actual_success_mbps", "积压窗口服务量（Mb/s）", True),
              ("energy_uj", "actual_energy_uj", "AP 状态能耗（μJ）", False))
    included = select(rows, verification="matched")
    for axis, (prediction, actual, label, backlog_only) in zip(axes, panels):
        bound = 0
        minimum = float("inf")
        for mode in MODES:
            selected = select(included, mode=mode)
            if backlog_only:
                selected = [row for row in selected if float(row.get("queue_packets", "0") or "0") > 0]
            pairs = [(float(row[actual]),float(row[prediction])) for row in selected
                     if row.get(actual, "") != "" and row.get(prediction, "") != ""]
            if not pairs:
                continue
            tx, ty = zip(*pairs)
            bound = max(bound, max(tx), max(ty))
            minimum = min(minimum, min(tx), min(ty))
            axis.scatter(tx, ty, color=COLORS[mode], s=11, alpha=.20, linewidths=0, label=MODE_ZH[mode])
        if bound:
            axis.plot((minimum,bound), (minimum,bound), color="#555555", linestyle="--", linewidth=1, label="预测等于实测")
        axis.set_xlabel("实测" + label)
        axis.set_ylabel("预测" + label)
    figure.suptitle("短窗口预测与下一窗口实测对照", fontsize=15, fontweight="bold")
    axes[1].legend(loc="upper center", bbox_to_anchor=(.5, 1.19), ncols=3, frameon=False)
    return figure


def save(figure, name, directory, properties, font):
    for annotation in figure.findobj(Text):
        text = annotation.get_text()
        if not text:
            continue
        for char in text:
            if ord(char) > 127 and not font.get_char_index(ord(char)):
                raise ValueError(f"Chinese font lacks glyph {char!r} in {text!r}")
        # Use the verified font path, including title and legend glyphs.
        size, weight = annotation.get_fontsize(), annotation.get_fontweight()
        annotation.set_fontproperties(properties)
        annotation.set_fontsize(size)
        annotation.set_fontweight(weight)
    figure.savefig(directory / f"{name}.png", dpi=320, metadata={"Software": "FTTR C-phase"})
    figure.savefig(directory / f"{name}.svg", metadata={"Date": None})
    plt.close(figure)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=RESULT)
    parser.add_argument("--font", type=Path)
    options = parser.parse_args()
    properties, font = setup_font(resolve_font(options.font))
    output = options.data / "figures"
    output.mkdir(parents=True, exist_ok=True)
    performance = select(read_csv(options.data / "qos-energy.csv"), suite="main", ac="ALL", direction="DL")
    predictions = read_csv(options.data / "prediction-vs-actual.csv")
    for name, figure in (
        ("01-subchannel-performance", channel_figure(performance)),
        ("02-power-crossover", power_figure(performance)),
        ("03-qos-and-backlog", qos_figure(performance)),
        ("04-prediction-validation", predictor_figure(predictions)),
    ):
        save(figure, name, output, properties, font)


if __name__ == "__main__":
    main()
