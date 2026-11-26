"""Draw E3 decision and performance figures from audited CSV results."""

from __future__ import annotations

import argparse
from pathlib import Path
from statistics import mean

from e3_diagnose import OUT
from e3_experiments import ACCEPTANCE_SEEDS, COMMON, E1, E3, FIXED, HCM, SCENARIOS
from plot_c_phase import plt, read_csv, resolve_font, save, setup_font

COLORS = {FIXED: "#8a689f", HCM: "#c1552d", E1: "#24768c", E3: "#233b76"}
NAMES = {FIXED: "固定阈值", HCM: "固定 HCM", E1: "冻结 E1", E3: "修正 E3"}
CASE = ("31 Mb/s", "50 Mb/s", "85 Mb/s", "主信道\n强 35%", "次信道\n强 15%", "次信道\n强 35%",
        "周期突发", "阈值波动", "原消融\n场景")


def queue_and_modes(decisions, timeline):
    fig, axes = plt.subplots(2, 2, figsize=(11.4, 6.5), layout="constrained",
                             gridspec_kw={"height_ratios": [2.3, 1]})
    for col, (case, title) in enumerate((("rf-primary-strong-35", "主信道强干扰 35%"),
                                         ("rf-secondary-strong-35", "次信道强干扰 35%"))):
        top, bottom = axes[:, col]
        top.set_title(title, fontweight="bold")
        for level, variant in enumerate((E1, E3)):
            rows = [r for r in decisions if (r["scenario"], r["policy"], r["trigger"])
                    == (case, variant, "PERIODIC")]
            t = [0] + [int(r["time_ns"]) / 1e6 for r in rows]
            backlog = [0] + [int(r["queued_packets"]) + int(r["inflight_packets"]) for r in rows]
            top.plot(t, backlog, color=COLORS[variant], label=NAMES[variant], lw=1.7,
                     linestyle="--" if variant == E1 else "-")
            for mode, color in (("LCM", "#3a939d"), ("HCM", COLORS[HCM]), ("SWITCH", "#343434")):
                intervals = [(int(r["start_ns"]) / 1e6, (int(r["end_ns"]) - int(r["start_ns"])) / 1e6)
                             for r in timeline if (r["scenario"], r["policy"], r["mode"]) == (case, variant, mode)]
                bottom.broken_barh(intervals, (level - .23, .46), facecolors=color, linewidth=0,
                                   label=mode if level == 0 and mode != "SWITCH" else None)
        actions = [r for r in decisions if (r["scenario"], r["policy"], r["reason"], r["action"])
                   == (case, E3, "CAPACITY_RECOVERY", "REQUESTED")]
        if actions:
            top.scatter([int(r["time_ns"]) / 1e6 for r in actions],
                        [int(r["queued_packets"]) + int(r["inflight_packets"]) for r in actions],
                        color=COLORS[E3], marker="D", s=40, zorder=5, label="容量恢复请求")
        top.set(xlim=(0, 500), ylim=(0, None), ylabel="AP 待发和在途报文（包）")
        top.legend(loc="upper left", frameon=False)
        bottom.set(xlim=(0, 500), ylim=(-.6, 1.95), xlabel="时间（ms）")
        bottom.set_yticks((0, 1), ("冻结 E1", "修正 E3"))
        bottom.legend(loc="upper right", frameon=False, ncols=2, fontsize=9)
    fig.suptitle(f"持续容量不足时的 AP 队列与实际模式（验收种子 {ACCEPTANCE_SEEDS[0]}）", fontweight="bold")
    return fig


def pair_row(rows, scenario, baseline, metric, ac="ALL"):
    return next(r for r in rows if (r["scenario"], r["baseline"], r["metric"], r["ac"]) == (scenario, baseline, metric, ac))


def bounds(rows):
    m = [float(r["mean"]) for r in rows]
    low = [max(0, x - float(r["ci95_low"])) for x, r in zip(m, rows)]
    high = [max(0, float(r["ci95_high"]) - x) for x, r in zip(m, rows)]
    return m, [low, high]


def overall(performance, paired):
    fig, axes = plt.subplots(2, 2, figsize=(13.5, 8.4), layout="constrained")
    x = range(len(SCENARIOS))
    net = [r for r in performance if (r["sfu_id"], r["direction"], r["ac"]) == ("ALL", "BOTH", "ALL")]
    for ax, (metric, label) in zip((axes[0, 1], axes[1, 0]), (("goodput_mbps", "成功吞吐量（Mb/s）"),
                                                               ("on_time_pct", "全业务准时率（%）"))):
        for i, policy in enumerate(COMMON):
            width = .19
            values = [mean(float(r[metric]) for r in net if (r["scenario"], r["policy"]) == (case, policy)) for case in SCENARIOS]
            ax.bar([j + (i - 1.5) * width for j in x], values, width, color=COLORS[policy], label=NAMES[policy])
        ax.set_xticks(x, CASE)
        ax.set_ylabel(label)
        ax.set_ylim(bottom=0)
    ax = axes[0, 0]
    m, band = bounds([pair_row(paired, case, E1, "complete_uj_relative_pct") for case in SCENARIOS])
    ax.bar(x, m, yerr=band, capsize=3, width=.55, color=COLORS[E3])
    ax.set_xticks(x, CASE)
    ax.set_ylabel("完整能耗相对 E1 的变化（%）")
    ax.axhline(0, color="#444444", lw=.9)
    ax = axes[1, 1]
    for i, (ac, color) in enumerate((("VO", "#196e77"), ("VI", "#a45e33"))):
        m, band = bounds([pair_row(paired, case, E1, "on_time_pct", ac) for case in SCENARIOS[3:]])
        ax.errorbar([j + (.1 if i else -.1) for j in range(3, len(CASE))], m, yerr=band, marker="o", capsize=3,
                    color=color, linestyle="none", label=ac)
    ax.set_xticks(range(3, len(CASE)), CASE[3:])
    ax.set_ylabel("准时率相对 E1 的差值（百分点）")
    ax.axhline(0, color="#444444", lw=.9)
    ax.legend(loc="upper right", frameon=False, ncols=2, fontsize=9)
    fig.legend(*axes[0, 1].get_legend_handles_labels(), loc="outside lower center", frameon=False, ncols=4)
    fig.suptitle("修正前后的能耗、吞吐和准时交付（6 组配对种子）", fontweight="bold")
    return fig


def mechanisms(paired):
    shown = (("rf-primary-strong-35", "e3-no-capacity", "容量保护\n主信道 35%"),
             ("load-050", "e3-no-economics", "经济校验\n50 Mb/s"),
             ("mechanisms", "e3-no-channel", "信道感知\n原消融"),
             ("mechanisms", "e3-no-qos", "QoS\n原消融"),
             ("mechanisms", "e3-no-deferral", "主动延迟\n原消融"),
             ("load-031", "e3-no-deferral", "主动延迟\n31 Mb/s"))
    fig, axes = plt.subplots(2, 2, figsize=(11.7, 7.7), layout="constrained")
    for ax, (metric, label) in zip(axes.flat, (("complete_uj_relative_pct", "完整能耗差（%）"),
            ("goodput_mbps", "成功吞吐差（Mb/s）"), ("on_time_pct", "全业务准时率差（百分点）"),
            ("switches", "切换次数差（次）"))):
        m, band = bounds([pair_row(paired, case, control, metric) for case, control, _ in shown])
        ax.bar(range(len(shown)), m, yerr=band, capsize=3, width=.57, color=COLORS[E3])
        ax.set_xticks(range(len(shown)), [name for _, _, name in shown])
        ax.set_ylabel(label)
        ax.axhline(0, color="#444444", lw=.9)
    fig.suptitle("机制消融：完整 E3 减去关闭相应开关的版本", fontweight="bold")
    return fig


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    font_properties, font = setup_font(resolve_font(args.font))
    plt.rcParams.update({"svg.hashsalt": "e3-decision", "axes.grid.axis": "y", "figure.titlesize": 15})
    figures = OUT / "figures"
    figures.mkdir(exist_ok=True)
    paired = read_csv(OUT / "acceptance-paired.csv")
    outputs = (
        ("capacity-timeline", queue_and_modes(read_csv(OUT / "decisions.csv"), read_csv(OUT / "mode-timeline.csv"))),
        ("energy-service-qos", overall(read_csv(OUT / "acceptance-performance.csv"), paired)),
        ("ablation", mechanisms(paired)),
    )
    for name, figure in outputs:
        save(figure, name, figures, font_properties, font)


if __name__ == "__main__":
    main()
