"""Plot the three D2 acceptance figures from Java-derived D2 CSVs."""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
from statistics import mean

from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import Patch

from plot_c_phase import COLORS, plt, read_csv, resolve_font, save, setup_font
from verify_d2_energy_decision import (
    D2_DECISION, DYNAMIC_CASES, ENERGY_POLICIES, GRID_LOADS, INITIAL, LENGTHS, MODES, OUT, PATTERNS, POLICIES, SEEDS,
)


CHANNEL_ZH = {"ideal": "理想信道", "primary": "主信道干扰", "secondary": "次信道干扰", "wide": "宽带干扰"}
CASE_ZH = {"step-ideal": "理想信道阶梯", "step-secondary": "次信道干扰阶梯", "burst-multi": "双 AP 突发"}
POLICY_ZH = dict(zip(POLICIES, ("固定 LCM", "固定 HCM", "固定阈值", "仅能耗", "能耗与信道")))
POLICY_COLORS = dict(zip(POLICIES, (COLORS["LCM"], COLORS["HCM"], "#785daa", "#ad7e20", "#36795d")))


def mode_boundary(rows):
    choices = defaultdict(list)
    for row in rows:
        if row["scenario"] == "grid" and row["event"] == D2_DECISION and row["candidate_mode"] == "LCM":
            if int(row["t_ns"]) >= 10_000_000:
                key = row["policy"], row["profile"], int(row["packet_bytes"]), int(row["nominal_load_mbps"])
                choices[key].append(row["target_mode"] == "HCM")
    cmap = LinearSegmentedColormap.from_list("d2modes", [COLORS["LCM"], "#f0ede9", COLORS["HCM"]])
    figure, axes = plt.subplots(2, 4, figsize=(13.7, 5.2), layout="constrained")
    figure.suptitle("不同负载及干扰下的模式选择边界", fontsize=16, fontweight="bold")
    for y, policy in enumerate(ENERGY_POLICIES):
        for x, profile in enumerate(PATTERNS):
            axis = axes[y, x]
            grid = [[100 * mean(choices[policy, profile, size, load]) for load in GRID_LOADS] for size in LENGTHS]
            rendered = axis.imshow(grid, cmap=cmap, origin="lower", aspect="auto", vmin=0, vmax=100)
            axis.set_xticks(range(len(GRID_LOADS)), GRID_LOADS)
            if x == 0:
                axis.set_yticks(range(len(LENGTHS)), LENGTHS)
            else:
                axis.set_yticks([])
            axis.grid(False)
            axis.tick_params(length=0)
            if y == 0:
                axis.set_title(CHANNEL_ZH[profile], fontsize=11)
            if x == 0:
                axis.set_ylabel(POLICY_ZH[policy] + "\n报文长度（B）")
            for iy, row in enumerate(grid):
                for ix, value in enumerate(row):
                    fg = "white" if value < 22 or value > 78 else "#242424"
                    axis.text(ix, iy, f"{value:.0f}", ha="center", va="center", color=fg, fontsize=9)
    figure.colorbar(rendered, ax=axes[:, -1], shrink=.94, pad=.02, label="选择 HCM 的决策比例（%）")
    figure.supxlabel("名义下行负载（Mb/s）", fontsize=11)
    return figure


def mode_segments(rows, stop):
    mode = next(row["current_mode"] for row in rows if row["event"] == INITIAL)
    start = 0.0
    for row in rows:
        event = row["event"]
        t = min(int(row["t_ns"]) / 1e6, stop)
        if event == "WIFI_AP_SWITCH_START":
            yield mode, start, t
            mode, start = "SWITCH", t
        elif event == "WIFI_AP_SWITCH_END":
            yield mode, start, t
            mode, start = row["to_mode"], t
    yield mode, start, stop


def timeline(rows, performance):
    sample = [row for row in rows if row["scenario"] == "step-secondary" and row["seed"] == str(SEEDS[0])]
    sensible = [row for row in sample if row["policy"] == "ec-aware"]
    stop = int(next(row["observation_end_ns"] for row in performance if row["scenario"] == "step-secondary")) / 1e6
    predictions = {mode: [row for row in sensible if row["event"] == D2_DECISION and row["candidate_mode"] == mode]
                   for mode in MODES}
    end = next(row for row in sensible if row["event"] == "C1_INGRESS_TAIL")
    windows = predictions["LCM"] + [end]
    elapsed = [int(row["t_ns"]) / 1e6 for row in predictions["LCM"]]
    edges = [0] + [int(row["t_ns"]) / 1e6 for row in windows]

    figure, (flow, channel, state) = plt.subplots(3, 1, figsize=(11.4, 7.0), sharex=True, layout="constrained",
                                                 gridspec_kw=dict(height_ratios=(2.4, 2.7, 1.5)))
    figure.suptitle("次信道干扰下的业务、信道与实际模式", fontsize=16, fontweight="bold")
    flow.stairs([float(row["arrival_mbps"]) for row in windows], edges, baseline=None, color="#55878b",
                linewidth=1.7, label="下行实际到达率")
    estimate = [float(row["ewma_mbps"]) for row in predictions["LCM"]]
    flow.step(elapsed + [stop], estimate + [estimate[-1]], where="post", color=POLICY_COLORS["fixed-threshold"],
              linewidth=1.8, label="平滑后的到达率")
    flow.set_ylabel("到达速率（Mb/s）")
    flow.legend(loc="upper left", ncol=2, frameon=False)

    for mode, predictions_by_time in predictions.items():
        error = [100 * float(row["mean_failure"]) for row in predictions_by_time]
        busy = [100 * float(row["expected_busy_fraction"]) for row in predictions_by_time]
        channel.step(elapsed + [stop], error + error[-1:], where="post", color=COLORS[mode], linewidth=1.7,
                     label=f"{mode} 单次失败估计")
        channel.step(elapsed + [stop], busy + busy[-1:], where="post", color=COLORS[mode], linestyle=":", linewidth=1.45,
                     label=f"{mode} 忙时估计")
    channel.set_ylabel("信道估计（%）")
    channel.legend(loc="upper left", ncol=2, frameon=False)
    channel.margins(y=.22)

    colors = COLORS | {"SWITCH": "#51545b"}
    traces = ("fixed-threshold", *ENERGY_POLICIES)
    for i, policy in enumerate(traces):
        own = [row for row in sample if row["policy"] == policy]
        for mode, start, stop_i in mode_segments(own, stop):
            state.broken_barh([(start, stop_i - start)], (i - .28, .56), facecolors=colors[mode], linewidth=0)
    state.set(xlim=(0, stop), yticks=range(len(traces)), yticklabels=[POLICY_ZH[p] for p in traces],
              ylim=(-.55, len(traces) - .4), xlabel="仿真时间（ms）")
    state.invert_yaxis()
    state.grid(False, axis="y")
    state.legend(handles=[Patch(color=colors["LCM"], label="LCM 低能力"), Patch(color=colors["HCM"], label="HCM 高能力"),
                         Patch(color=colors["SWITCH"], label="模式切换")], loc="upper center",
                 bbox_to_anchor=(.5, -.48), ncol=3, frameon=False)
    return figure


def comparison(rows):
    grouped = defaultdict(list)
    for row in rows:
        case = row["scenario"]
        sfu = "ALL" if case == "burst-multi" else "1"
        if case in DYNAMIC_CASES and row["sfu_id"] == sfu:
            grouped[(case, row["policy"])].append(row)

    figure, axes = plt.subplots(2, 2, figsize=(12.1, 7.4), layout="constrained")
    figure.suptitle("五种策略的配对实验结果", fontsize=16, fontweight="bold")
    panels = (("total_energy_uj", 1000, "AP 总能耗（mJ）"), ("goodput_mbps", 1, "成功吞吐量（Mb/s）"),
              ("p99_ns", 1e6, "P99 端到端时延（ms）"), ("switches_completed", 1, "完成切换（次）"))
    x = list(range(len(DYNAMIC_CASES)))
    for axis, (field, divisor, title) in zip(axes.flat, panels):
        for i, policy in enumerate(POLICIES):
            series = [[float(row[field]) / divisor for row in grouped[case, policy]] for case in DYNAMIC_CASES]
            middle = list(map(mean, series))
            bounds = [[a - min(b) for a, b in zip(middle, series)], [max(b) - a for a, b in zip(middle, series)]]
            axis.bar([tick + (i - 2) * .15 for tick in x], middle, .139, yerr=bounds,
                     color=POLICY_COLORS[policy], error_kw={"elinewidth": .75, "capsize": 1.8, "capthick": .75})
        axis.set_xticks(x, [CASE_ZH[case] for case in DYNAMIC_CASES])
        axis.grid(False, axis="x")
        axis.set_title(title, fontsize=11, pad=7)
    figure.legend(handles=[Patch(color=POLICY_COLORS[p], label=POLICY_ZH[p]) for p in POLICIES],
                  loc="outside lower center", ncol=len(POLICIES), frameon=False)
    return figure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=OUT)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    decisions = read_csv(args.input / "d2-decision-events.csv")
    perf = read_csv(args.input / "d2-performance.csv")
    properties, font = setup_font(resolve_font(args.font))
    save(mode_boundary(decisions), "d2-mode-boundary", args.input, properties, font)
    save(timeline(decisions, perf), "d2-channel-mode-timeline", args.input, properties, font)
    save(comparison(perf), "d2-policy-comparison", args.input, properties, font)


if __name__ == "__main__":
    main()
