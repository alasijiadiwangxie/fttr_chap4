"""Draw Chinese D4 plots directly from the verified Java experiment CSVs."""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
from statistics import mean

from matplotlib.patches import Patch

from plot_c_phase import COLORS, plt, read_csv, resolve_font, save, setup_font
from verify_d4_economic_residency import ACS, END, INITIAL, MAIN, NEW, OLD, OUT, POLICIES, START


LABELS = dict(zip(POLICIES, ("固定阈值", "能耗与信道", "加入 QoS", "D3 主动延迟", "D4 防振荡")))
PALETTE = dict(zip(POLICIES, ("#785daa", "#36795d", "#1281a4", "#c95522", "#183c65")))
CASES = ("无干扰", "主信道", "次信道", "持续负载")


def mode_timeline(rows):
    events = [row for row in rows if row["scenario"] == "mixed-primary" and row["policy"] in (OLD, NEW)]
    figure, axes = plt.subplots(1, 2, figsize=(12.4, 4.3), width_ratios=(1.65, 1), layout="constrained")
    figure.suptitle("主信道干扰下的实际工作模式与反向切换间隔", fontsize=16, fontweight="bold")
    axis, distribution = axes
    ylabels = []
    window = 120_000_000
    for y, (policy, sfu) in enumerate(((OLD, "1"), (OLD, "2"), (NEW, "1"), (NEW, "2"))):
        records = [row for row in events if row["policy"] == policy and row["sfu_id"] == sfu and row["seed"] == "101"]
        state, previous = next(r["current_mode"] for r in records if r["event"] == INITIAL), 0
        for record in records:
            t = min(window, int(record["t_ns"]))
            kind = record["event"]
            if kind not in (START, END):
                continue
            axis.broken_barh([(previous / 1e6, (t - previous) / 1e6)], (y - .31, .62),
                             facecolors=COLORS.get(state, "#b2b9c1"), linewidth=0)
            state, previous = (record["to_mode"] if kind == END else "SWITCH"), t
        axis.broken_barh([(previous / 1e6, (window - previous) / 1e6)], (y - .31, .62),
                         facecolors=COLORS.get(state, "#b2b9c1"), linewidth=0)
        ylabels.append(f"{'D3' if policy == OLD else 'D4'} · SFU {sfu}")
    axis.set(xlim=(0, 120), yticks=range(4), yticklabels=ylabels, ylim=(-.65, 3.65), xlabel="仿真时间（ms）",
             title="实际模式时间线（种子 101）")
    axis.invert_yaxis()
    axis.grid(False, axis="y")
    axis.legend(handles=[Patch(color=COLORS[m], label=f"{m} 工作") for m in ("LCM", "HCM")]
                + [Patch(color="#b2b9c1", label="实际切换")], loc="upper center", bbox_to_anchor=(.5, -.23),
                ncol=3, frameon=False)

    cuts = (0, .5, 1, 2, 5, 10, float("inf"))
    for index, policy in enumerate((OLD, NEW)):
        gaps = [int(row["residence_before_switch_ns"]) / 1e6 for row in events if row["policy"] == policy
                and row["event"] == START and row["residence_before_switch_ns"] and int(row["t_ns"]) <= window]
        shares = [100 * sum(lower <= duration < upper for duration in gaps) / len(gaps) if gaps else 0
                  for lower, upper in zip(cuts, cuts[1:])]
        distribution.bar([i + (index - .5) * .36 for i in range(len(shares))], shares, .34,
                         color=PALETTE[policy], label=f"{LABELS[policy]}（{len(gaps)} 段）")
    distribution.set_xticks(range(6), ("<0.5", "0.5–1", "1–2", "2–5", "5–10", "≥10"))
    distribution.set(xlabel="前次完成至下次开始（ms）", ylabel="观测间隔占比（%）", title="三种子反向切换间隔")
    distribution.grid(False, axis="x")
    distribution.legend(loc="upper center", bbox_to_anchor=(.5, -.23), ncol=2, frameon=False)
    return figure


def groups(rows):
    by_key = defaultdict(list)
    for row in rows:
        if row["scenario"] in MAIN and row["sfu_id"] == "ALL":
            by_key[row["scenario"], row["policy"], row["ac"]].append(row)
    return by_key


def value(row, key, multiplier):
    return float(row[key]) * multiplier if row.get(key) not in ("", None) else None


def bars(axis, data, ac, metric, scale=1, error=False):
    for index, policy in enumerate(POLICIES):
        series = [[sample for row in data[scenario, policy, ac] if (sample := value(row, metric, scale)) is not None]
                  for scenario in MAIN]
        centers = [mean(samples) if samples else float("nan") for samples in series]
        low = [m - min(samples) if samples else 0 for m, samples in zip(centers, series)]
        high = [max(samples) - m if samples else 0 for m, samples in zip(centers, series)]
        axis.bar([group + (index - 2) * .15 for group in range(len(MAIN))], centers, .141,
                 color=PALETTE[policy], yerr=[low, high] if error else None,
                 error_kw={"elinewidth": .75, "capsize": 1.7, "capthick": .75})
    axis.set_xticks(range(len(MAIN)), CASES)
    axis.tick_params(axis="x", labelsize=9)
    axis.grid(False, axis="x")


def policy_legend(figure):
    handles = [Patch(color=PALETTE[p], label=LABELS[p]) for p in POLICIES]
    figure.legend(handles=handles, loc="outside lower center", ncols=len(POLICIES), frameon=False)


def energy_and_residence(data):
    figure, axes = plt.subplots(2, 2, figsize=(11.5, 6.6), layout="constrained")
    figure.suptitle("驻留、切换次数与实际净能耗", fontsize=16, fontweight="bold")
    for scenario in MAIN:
        reference = {row["seed"]: float(row["total_energy_uj"]) for row in data[scenario, POLICIES[0], "ALL"]}
        for policy in POLICIES:
            for row in data[scenario, policy, "ALL"]:
                row["hcm_ms"] = float(row["hcm_fraction"]) * int(row["cutoff_ns"]) / 1e6
                row["energy_delta_uj"] = float(row["total_energy_uj"]) - reference[row["seed"]]
    for axis, field, scale, label in zip(axes.flat, ("hcm_ms", "switches_completed", "total_energy_uj", "energy_delta_uj"),
                                         (1, 1, 1e-3, 1e-3), ("单 AP 平均 HCM 驻留（ms）", "完成切换（次）",
                                                                 "AP 净能耗（mJ）", "相对固定阈值能耗差（mJ）")):
        bars(axis, data, "ALL", field, scale, error=True)
        axis.set_ylabel(label)
    axes[1, 1].axhline(0, linewidth=.8, color="#787878")
    policy_legend(figure)
    return figure


def throughput_qos(data):
    figure = plt.figure(figsize=(14.1, 8.4), layout="constrained")
    grid = figure.add_gridspec(3, 4, height_ratios=(1.1, 1, 1))
    figure.suptitle("成功吞吐、窗末积压与各业务的交付质量", fontsize=16, fontweight="bold")
    for positions, field, label in ((grid[0, :2], "goodput_mbps", "全网成功吞吐（Mb/s）"),
                                    (grid[0, 2:], "incomplete", "窗末未完成（包）")):
        axis = figure.add_subplot(positions)
        bars(axis, data, "ALL", field, error=True)
        axis.set_ylabel(label)
    for column, ac in enumerate(ACS):
        top = figure.add_subplot(grid[1, column])
        bottom = figure.add_subplot(grid[2, column], sharex=top)
        bars(top, data, ac, "on_time_ratio", 100)
        bars(bottom, data, ac, "p99_ns", 1e-6)
        top.set_ylim(0, 105)
        top.tick_params(labelbottom=False)
        top.set_title(ac, fontweight="bold")
        if column == 0:
            top.set_ylabel("截止时间内交付率（%）")
            bottom.set_ylabel("端到端 P99（ms）")
    policy_legend(figure)
    return figure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=OUT)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    properties, font = setup_font(resolve_font(args.font))
    data = groups(read_csv(args.input / "d4-performance.csv"))
    save(mode_timeline(read_csv(args.input / "d4-decision-events.csv")), "d4-mode-timeline", args.input, properties, font)
    save(energy_and_residence(data), "d4-residency-energy", args.input, properties, font)
    save(throughput_qos(data), "d4-throughput-qos", args.input, properties, font)


if __name__ == "__main__":
    main()
