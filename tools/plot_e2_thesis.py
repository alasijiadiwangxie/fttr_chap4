"""Render Chinese thesis figures using only audited E2 seed summaries and timelines."""

from __future__ import annotations

import argparse
from pathlib import Path

from e2_design import ECQ, FIXED, GROUPS, HCM, LCM, MAIN, SEEDS, slices, study
from e2_run import OUT
from plot_c_phase import plt, read_csv, resolve_font, save, setup_font

NAMES = {LCM: "固定 LCM", HCM: "固定 HCM", FIXED: "31 Mb/s 固定阈值", ECQ: "ECQ-SDPS"}
COLORS = {LCM: "#007c91", HCM: "#cc5c25", FIXED: "#8165a1", ECQ: "#233b76"}
MARKERS = {LCM: "o", HCM: "s", FIXED: "^", ECQ: "D"}
AC = ("VO", "VI", "BE", "BK")


def row_at(rows, scenario, policy, ac="ALL", direction="BOTH"):
    return next(r for r in rows if (r["scenario"], r["policy"], r["sfu_id"], r["direction"], r["ac"])
                == (scenario, policy, "ALL", direction, ac))


def value(row, name):
    return float(row[name]) if row.get(name) else float("nan")


def mean_ci(row, metric):
    center = value(row, metric + "_mean")
    low, high = (value(row, metric + "_ci95_" + edge) for edge in ("low", "high"))
    return center, max(0, center - low), max(0, high - center)


def series(ax, x, rows_for_policy, field, *, lines=False, zero=True, cap=None):
    for i, policy in enumerate(MAIN):
        values = [mean_ci(row, field) for row in rows_for_policy(policy)]
        centers, lows, highs = list(zip(*values))
        if lines:
            ax.errorbar(x, centers, yerr=[lows, highs], marker=MARKERS[policy], capsize=2.5, lw=1.4,
                        markersize=4.8, color=COLORS[policy], label=NAMES[policy])
        else:
            width = .18
            ax.bar([j + (i - 1.5) * width for j in x], centers, yerr=[lows, highs], capsize=2.4, width=width,
                   color=COLORS[policy], label=NAMES[policy], alpha=.95, error_kw={"linewidth": .8})
    if zero:
        ax.set_ylim(bottom=0, top=cap)


def legend(fig, ax):
    handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="outside lower center", ncols=4, frameon=False)


def figure1(rows):
    loads = (0, 12, 31, 50, 85, 130)
    fig, axes = plt.subplots(1, 3, figsize=(12.2, 4.3), layout="constrained")
    for ax, (metric, label) in zip(axes, (("power_w_per_ap", "AP 平均功率（W）"), ("goodput_mbps", "成功吞吐量（Mb/s）"),
                                         ("nj_per_success_bit", "单位成功比特能耗（nJ/bit）"))):
        series(ax, loads, lambda p: [row_at(rows, f"load-{x:03}", p) for x in loads], metric, lines=True)
        ax.set(xlabel="配置输入负载（Mb/s）", ylabel=label, xticks=loads)
    fig.suptitle("不同业务负载下的功率、交付能力与能效", fontweight="bold")
    legend(fig, axes[0])
    return fig


def figure2(rows):
    fig, axes = plt.subplots(2, 3, figsize=(12.6, 7.1), layout="constrained")
    for column, (band, caption) in enumerate((("primary", "主信道"), ("secondary", "次信道"), ("wide", "宽带"))):
        names = ["rf-clear", *(f"rf-{band}-{power}-{duty}" for power, duty in (("weak", 15), ("strong", 15), ("strong", 35)))]
        for i, (metric, label, limit) in enumerate((("goodput_mbps", "成功吞吐量（Mb/s）", 68),
                                                   ("external_busy_pct", "外部 CCA 忙碌比例（%）", 40))):
            ax = axes[i, column]
            series(ax, range(4), lambda p: [row_at(rows, name, p) for name in names], metric, cap=limit)
            ax.set_xticks(range(4), ("无干扰", "弱 · 15%", "强 · 15%", "强 · 35%"))
            if column == 0:
                ax.set_ylabel(label)
            if i == 0:
                ax.set_title(caption, fontsize=12)
            if i == 1:
                ax.set_xlabel("干扰强度与占空比")
    fig.suptitle("干扰位置、强度与占空比对无线交付的影响", fontweight="bold")
    legend(fig, axes[0, 0])
    return fig


def figure3(rows):
    fig, axes = plt.subplots(2, 3, figsize=(12.4, 7.0), layout="constrained")
    metrics = (("on_time_pct", "准时交付率（%）", 105), ("p99_ms", "P99 端到端时延（ms）", None),
               ("backlog_window", "窗末未完成（包）", None))
    for i, load in enumerate((45, 100)):
        for j, (metric, label, cap) in enumerate(metrics):
            ax = axes[i, j]
            series(ax, range(4), lambda p: [row_at(rows, f"mixed-{load}", p, ac) for ac in AC], metric, cap=cap)
            ax.set_xticks(range(4), AC)
            ax.set_ylabel(label)
            if j == 0:
                ax.set_title(f"准时交付 · {load} Mb/s", fontweight="bold")
            elif i == 0:
                ax.set_title(("准时交付", "尾时延", "窗末积压")[j], fontweight="bold")
            if i == 1:
                ax.set_xlabel("业务类别")
    fig.suptitle("混合业务的时延、准时率与窗末积压", fontweight="bold")
    legend(fig, axes[0, 0])
    return fig


def figure4(rows, timeline):
    fig, axes = plt.subplots(2, 2, figsize=(12.1, 7.6), layout="constrained")
    dynamic = ("step", "periodic", "random", "near")
    for ax, (metric, name, cap) in zip(axes[1], (("switches", "观察窗切换次数（次）", None),
                                                ("hcm_pct", "HCM 驻留比例（%）", 105))):
        series(ax, range(4), lambda p: [row_at(rows, f"dynamic-{case}", p) for case in dynamic], metric, cap=cap)
        ax.set_xticks(range(4), ("阶梯", "周期突发", "随机突发", "阈值附近"))
        ax.set_ylabel(name)
    case = next(c for c in study() if c.name == "dynamic-step")
    ax = axes[0, 0]
    stages = slices(case, SEEDS[0])
    times = [lo / 1000 for lo, _, _ in stages] + [stages[-1][1] / 1000]
    demand = [load for _, _, load in stages]
    ax.step(times, [*demand, demand[-1]], where="post", color="#333333", lw=1.7)
    ax.axhline(31, linestyle=":", linewidth=1.2, color=COLORS[FIXED], label="固定阈值 31 Mb/s")
    ax.set(xlim=(0, case.window_us / 1000), ylim=(0, 100), xlabel="时间（ms）", ylabel="配置输入负载（Mb/s）",
           title="阶梯负载")
    ax.legend(loc="upper right", fontsize=9, frameon=False)
    ax = axes[0, 1]
    for level, policy in enumerate((FIXED, ECQ)):
        chosen = [r for r in timeline if (r["scenario"], r["seed"], r["policy"])
                  == ("dynamic-step", str(SEEDS[0]), policy)]
        for mode, color in (("LCM", COLORS[LCM]), ("HCM", COLORS[HCM]), ("SWITCH", "#444444")):
            ax.broken_barh([(float(r["start_ns"]) / 1e6, (float(r["end_ns"]) - float(r["start_ns"])) / 1e6)
                            for r in chosen if r["mode"] == mode], (level - .25, .5),
                           facecolors=color, linewidth=0, label=mode if level == 0 and mode != "SWITCH" else None)
    ax.set(xlim=(0, 800), ylim=(-.65, 1.7), xlabel="时间（ms）", title=f"实际工作模式（种子 {SEEDS[0]}）")
    ax.set_yticks((0, 1), ("固定阈值", "ECQ-SDPS"))
    ax.legend(loc="upper right", ncols=2, fontsize=9, frameon=False)
    fig.suptitle("动态负载下的模式变化与切换开销", fontweight="bold")
    legend(fig, axes[1, 0])
    return fig


def figure5(rows):
    fig, axes = plt.subplots(2, 2, figsize=(11.4, 7.0), layout="constrained")
    route = [(sfus, d) for sfus in (2, 4) for d in ("DL", "UL")]
    for ax, (metric, label) in zip(axes.flat, (("goodput_mbps", "全网成功吞吐量（Mb/s）"),
            ("p99_ms", "端到端 P99（ms）"), ("optical_mean_us", "平均光侧时延（微秒）"),
            ("power_w_per_ap", "每台 AP 平均功率（W）"))):
        if metric == "power_w_per_ap":
            series(ax, range(2), lambda p: [row_at(rows, f"joint-{sfus}sfu", p) for sfus in (2, 4)], metric)
            ax.set_xticks(range(2), ("2 台 SFU", "4 台 SFU"))
        else:
            series(ax, range(4), lambda p: [row_at(rows, f"joint-{sfus}sfu", p, direction=d) for sfus, d in route], metric)
            ax.set_xticks(range(4), [f"{sfus} 台 · " + ("下行" if d == "DL" else "上行") for sfus, d in route])
        ax.set_ylabel(label)
    fig.suptitle("多 SFU 光无线融合性能（真实 GIANT DBA）", fontweight="bold")
    legend(fig, axes[0, 0])
    return fig


def paired_ax(ax, labels, paired, label, colors=None):
    center = [value(r, "mean") for r in paired]
    lower = [max(0, v - value(r, "ci95_low")) for v, r in zip(center, paired)]
    upper = [max(0, value(r, "ci95_high") - v) for v, r in zip(center, paired)]
    ax.bar(range(len(paired)), center, yerr=[lower, upper], capsize=3, width=.56, color=colors or COLORS[ECQ])
    ax.axhline(0, color="#333333", linewidth=1)
    ax.set_xticks(range(len(labels)), labels)
    ax.set_ylabel(label)


def pair_row(rows, scenario, policy, baseline, metric, ac="ALL"):
    return next(r for r in rows if (r["scenario"], r["policy"], r["baseline_policy"], r["metric"], r["sfu_id"], r["direction"], r["ac"])
                == (scenario, policy, baseline, metric, "ALL", "BOTH", ac))


def figure6(paired):
    fig, axes = plt.subplots(2, 2, figsize=(11.3, 7.1), layout="constrained")
    variants = ("no-channel", "no-qos", "no-deferral", "no-residency")
    labels = ("信道感知", "QoS", "主动延迟", "经济驻留")
    for ax, metric, ylabel in ((axes[0, 0], "complete_uj_relative_pct", "完整结算能耗差（%）"),
                              (axes[0, 1], "on_time_pct", "全业务准时率差（百分点）")):
        paired_ax(ax, labels, [pair_row(paired, "mechanisms", ECQ, p, metric) for p in variants], ylabel)
        ax.set_title("完整策略减去关闭对应机制的版本")
    paired_ax(axes[1, 0], [NAMES[x] if x != FIXED else "固定阈值" for x in MAIN],
              [pair_row(paired, "power-paper-31", x, x, "power_w_per_ap") for x in MAIN],
              "平均功率差（W）", [COLORS[p] for p in MAIN])
    axes[1, 0].set_title("论文功率参数减去 B3 功率参数")
    probes = ("observation-delayed", "observation-no-scan", "observation-free-scan")
    tested = [next(r for r in paired if r["comparison"] == "observation-vs-reference" and r["scenario"] == case
                   and r["policy"] == ECQ and r["metric"] == "on_time_pct" and r["sfu_id"] == "ALL" and r["ac"] == "ALL"
                   and r["direction"] == "BOTH") for case in probes]
    paired_ax(axes[1, 1], ("反馈延迟", "关闭扫描", "扫描零功耗"), tested, "ECQ-SDPS 准时率差（百分点）")
    axes[1, 1].set_title("观测变化减去参考观测")
    fig.suptitle("机制消融与参数敏感性（配对差值及 95% 置信区间）", fontweight="bold")
    return fig


def relative_energy(paired):
    fig, axes = plt.subplots(1, 2, figsize=(11.8, 4.5), layout="constrained")
    loads = tuple(f"load-{rate:03}" for rate in (0, 12, 31, 50, 85, 130))
    joint = ("joint-2sfu", "joint-4sfu")
    for ax, cases, labels, caption in ((axes[0], loads, ("0", "12", "31", "50", "85", "130"), "单 AP 输入负载（Mb/s）"),
                                       (axes[1], joint, ("2", "4"), "联合仿真 SFU 数（台）")):
        for name, metric, color, marker in (("观察窗", "total_uj_relative_pct", "#446d9e", "o"),
                                            ("完整结算", "complete_uj_relative_pct", "#b06520", "s")):
            rows = [pair_row(paired, case, ECQ, FIXED, metric) for case in cases]
            y = [value(r, "mean") for r in rows]
            low, high = ([value(r, "ci95_" + bound) for r in rows] for bound in ("low", "high"))
            ax.errorbar(range(len(cases)), y, yerr=[[max(0, v - lo) for v, lo in zip(y, low)],
                                                    [max(0, hi - v) for v, hi in zip(y, high)]],
                        color=color, marker=marker, capsize=3, lw=1.4, label=name)
        ax.set_xticks(range(len(cases)), labels)
        ax.axhline(0, color="#444444", linewidth=1, linestyle=":")
        ax.set(xlabel=caption, ylabel="相对固定阈值的能耗变化（%）")
    fig.legend(*axes[0].get_legend_handles_labels(), loc="outside lower center", ncols=2, frameon=False)
    fig.suptitle("ECQ-SDPS 相对 31 Mb/s 固定阈值的能耗变化", fontweight="bold")
    return fig


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path)
    parser.add_argument("--data", type=Path, default=OUT)
    args = parser.parse_args()
    properties, font = setup_font(resolve_font(args.font))
    plt.rcParams.update({"svg.hashsalt": "e2-thesis", "axes.grid.axis": "y", "figure.titlesize": 15})
    groups = [args.data / group for group in GROUPS]
    summary = [read_csv(g / "summary.csv") for g in groups]
    paired = [read_csv(g / "paired.csv") for g in groups]
    figures = (
        ("load-performance", figure1(summary[0])),
        ("interference-performance", figure2(summary[1])),
        ("mixed-qos", figure3(summary[2])),
        ("dynamic-modes", figure4(summary[3], read_csv(groups[3] / "mode-timeline.csv"))),
        ("joint-fttr", figure5(summary[4])),
        ("mechanism-sensitivity", figure6(paired[5])),
        ("relative-energy", relative_energy(paired[0] + paired[4])),
    )
    directory = args.data / "figures"
    directory.mkdir(parents=True, exist_ok=True)
    for name, fig in figures:
        save(fig, name, directory, properties, font)


if __name__ == "__main__":
    main()
