"""Render two Chinese C-final validation figures from the paired experiment CSVs."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from matplotlib.lines import Line2D

from plot_c_phase import COLORS, MODE_ZH, plt, read_csv, resolve_font, save, setup_font
from pon_experiment_io import ROOT


RESULT = ROOT / "results/c-final"
MODES = ("LCM", "HCM")
BRANCHES = {"stay": "保持当前模式", "switch": "切换到候选模式"}
MARKERS = {("legacy", "stay"): "x", ("legacy", "switch"): "+",
           ("revised", "stay"): "o", ("revised", "switch"): "^"}
MODELS = {
    ("legacy", "expected"): "修正前",
    ("revised", "expected"): "修正后期望",
    ("revised", "conservative"): "修正后保守",
}
OUTCOMES = (
    ("CORRECT_FEASIBLE", "正确可行", "#16758b"),
    ("CORRECT_INFEASIBLE", "正确不可行", "#70ad9c"),
    ("FALSE_FEASIBLE", "错误可行", "#b44327"),
    ("FALSE_INFEASIBLE", "错误不可行", "#e5a239"),
    ("UNKNOWN", "信息不足", "#77808f"),
)


def present_pairs(rows, actual, predicted):
    for row in rows:
        if row.get(actual) not in (None, "") and row.get(predicted) not in (None, ""):
            yield float(row[actual]), float(row[predicted])


def prediction_figure(rows):
    figure, axes = plt.subplots(2, 2, figsize=(11.1, 9), layout="constrained")
    for mode, panels in zip(MODES, axes):
        for axis, metric, unit, scale in zip(
            panels, ("success_mbps", "total_energy_uj"), ("成功吞吐量（Mb/s）", "AP 总能耗（mJ）"), (1, 0.001)
        ):
            comparable = [row for row in rows if row["candidate_mode"] == mode
                          and row.get("policy", "expected") == "expected"]
            bounds = []
            for (model, branch), marker in MARKERS.items():
                points = list(present_pairs(
                    (row for row in comparable if row["model"] == model and row["branch"] == branch),
                    "actual_" + metric, "predicted_" + metric,
                ))
                if not points:
                    continue
                x, y = ([point[index] * scale for point in points] for index in (0, 1))
                bounds.extend(x + y)
                axis.scatter(x, y, color=COLORS[mode], s=20 if branch == "switch" else 14,
                             marker=marker, alpha=.24 if model == "legacy" else .43, linewidths=.8)
            if not bounds:
                raise ValueError(f"Missing paired prediction data for {mode} {metric}")
            low, high = min(bounds), max(bounds)
            span = max(high - low, 1)
            limits = max(0, low - .05 * span), high + .05 * span
            axis.plot(limits, limits,
                      color="#575b62", linestyle="--", linewidth=1, label="预测等于实测")
            axis.set(xlim=limits, ylim=limits)
            axis.set_title(MODE_ZH[mode], fontsize=12, loc="left")
            axis.set_xlabel("实测" + unit)
            axis.set_ylabel("预测" + unit)
    handles = [
        Line2D([], [], linestyle="none", color="#565b64", marker=marker,
               label=f"{MODELS[model, 'expected']} · {BRANCHES[branch]}")
        for (model, branch), marker in MARKERS.items()
    ]
    handles.append(Line2D([], [], color="#575b62", linestyle="--", label="预测等于实测"))
    figure.legend(handles=handles, loc="outside lower center", ncol=5, frameon=False, fontsize=9)
    figure.suptitle("修正前后预测与离线实测对照", fontsize=16, fontweight="bold")
    return figure


def qos_figure(rows):
    figure, axes = plt.subplots(2, 2, figsize=(11.5, 8.4), layout="constrained", sharey=True)
    methods = list(MODELS)
    for mode, panels in zip(MODES, axes):
        for axis, (branch, branch_text) in zip(panels, BRANCHES.items()):
            counts = []
            for model, policy in methods:
                matching = [row["outcome"] for row in rows
                            if row["candidate_mode"] == mode and row["branch"] == branch
                            and row["model"] == model and row["policy"] == policy]
                counts.append(Counter(matching))
            totals = [sum(values[category] for category, _, _ in OUTCOMES) for values in counts]
            bottoms = [0.0] * len(methods)
            for category, name, color in OUTCOMES:
                shares = [100 * values[category] / total if total else 0
                          for values, total in zip(counts, totals)]
                axis.bar(range(len(methods)), shares, bottom=bottoms, color=color, width=.65, label=name)
                for index, share in enumerate(shares):
                    if share >= 7:
                        axis.text(index, bottoms[index] + share / 2, f"{share:.0f}%",
                                  color="white" if category != "FALSE_INFEASIBLE" else "#412901",
                                  ha="center", va="center", fontsize=9)
                bottoms = [base + share for base, share in zip(bottoms, shares)]
            if not any(totals):
                axis.text(.5, .5, "无有效队列样本", ha="center", va="center", transform=axis.transAxes)
            axis.set_xticks(range(len(methods)), [
                f"{MODELS[method]}\n有效样本 {total}" for method, total in zip(methods, totals)
            ])
            axis.set_ylim(0, 100)
            axis.set_title(f"{MODE_ZH[mode]} · {branch_text}", loc="left", color=COLORS[mode], fontsize=12)
            axis.set_ylabel("实际 QoS 对照占比（%）")
    handles, labels = axes[0, 0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="outside lower center", ncol=5, frameon=False)
    figure.suptitle("QoS 可行性预测与实际截止时间违约对照", fontsize=16, fontweight="bold")
    return figure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=RESULT)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    output = args.output or args.data / "figures"
    output.mkdir(parents=True, exist_ok=True)
    properties, font = setup_font(resolve_font(args.font))
    plt.rcParams["svg.hashsalt"] = "fttr-c-final"
    save(prediction_figure(read_csv(args.data / "prediction-comparison.csv")),
         "01-throughput-energy", output, properties, font)
    save(qos_figure(read_csv(args.data / "qos-feasibility.csv")),
         "02-qos-feasibility", output, properties, font)


if __name__ == "__main__":
    main()
