"""Draw the three E0 figures directly from the checked Java CSVs."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
from statistics import mean

from e0_trials import OUT
from plot_c_phase import plt, read_csv, resolve_font, save, setup_font


INK = "#174f75"
TEAL = "#13806e"
RUST = "#bd5834"
GOLD = "#d59136"
PURPLE = "#74639c"
GRAY = "#9aabba"
SWITCH_CASES = {"rf-secondary": "次信道干扰", "step-recovery": "阶梯与恢复"}
VARIANTS = {"full": "完整策略", "no-channel": "关闭信道感知", "no-qos": "关闭 QoS", "periodic-qos": "仅周期 QoS"}
TIMING_CASES = {"d5-primary-anchor": "D5 主信道", "burst-6ms-tight": "6 ms\n紧时限", "burst-6ms-relaxed": "6 ms\n宽时限",
                "burst-16ms-tight": "16 ms\n紧时限", "burst-16ms-relaxed": "16 ms\n宽时限"}


def switch_figure(summary, requests):
    figure, axes = plt.subplots(2, 2, figsize=(11.9, 7.5), layout="constrained")
    figure.suptitle("次信道干扰与阶梯恢复的切换诊断", fontsize=17, fontweight="bold")
    executed = [r for r in requests if r["status"] == "COMPLETED"]
    counts = Counter((r["scenario"], r["short_window_evidence"]) for r in executed)
    results = (("DEADLINE_GAIN", "准时获益", TEAL), ("SERVICE_GAIN", "交付量增加", INK),
               ("NO_QOS_OR_SERVICE_GAIN", "准时与交付量持平", GRAY), ("SERVICE_LOSS", "交付量减少", GOLD),
               ("DEADLINE_LOSS", "准时退化", RUST))
    offset = [0] * len(SWITCH_CASES)
    ax = axes[0, 0]
    for key, label, color in results:
        values = [counts[case, key] for case in SWITCH_CASES]
        ax.barh(list(SWITCH_CASES.values()), values, left=offset, label=label, color=color, height=.56)
        offset = [a + b for a, b in zip(offset, values)]
    ax.set(xlabel="完成切换次数（两种子合计）", title="每次切换与保持原档的 5 ms 对照")
    ax.grid(False, axis="y")
    ax.legend(loc="upper center", bbox_to_anchor=(.49, -.22), ncols=3, frameon=False, fontsize=8.5)

    ax = axes[0, 1]
    for mode, color, marker in (("LCM", INK, "o"), ("HCM", RUST, "^")):
        points = [r for r in executed if r["from_mode"] == mode and float(r["hcm_expected_busy"]) >= 0]
        ax.scatter([100 * float(r["next_window_wide_busy"]) for r in points], [100 * float(r["hcm_expected_busy"]) for r in points],
                   label=f"切换前为 {mode}", color=color, marker=marker, s=17, alpha=.64, linewidths=0)
    ax.plot((0, 100), (0, 100), color="#849097", linewidth=.8, linestyle="--", label="估计等于实测")
    ax.set(xlim=(-2, 102), ylim=(-2, 102), xlabel="随后宽带忙碌比例（%）", ylabel="决策时估计的宽带忙碌比例（%）",
           title="预测与下一时窗的干扰占用")
    ax.legend(loc="lower right", frameon=False, fontsize=8.5)

    def high_priority(row):
        return 100 * (int(row["VO_on_time_final"]) + int(row["VI_on_time_final"])) / (int(row["VO_generated"]) + int(row["VI_generated"]))

    for ax, metric, title, unit in ((axes[1, 0], lambda r: int(r["switches"]), "启用与关闭控制机制", "完成切换次数（种子均值）"),
                                    (axes[1, 1], high_priority, "两类实时业务的最终准时率", "VO/VI 合计准时率（%）")):
        width = .19
        for i, ((variant, label), color) in enumerate(zip(VARIANTS.items(), (INK, TEAL, GOLD, PURPLE))):
            means = [mean(metric(r) for r in summary if r["scenario"] == case and r["variant"] == variant) for case in SWITCH_CASES]
            ax.bar([j + (i - 1.5) * width for j in range(len(SWITCH_CASES))], means, width=width * .94, color=color, label=label)
        ax.set_xticks(range(len(SWITCH_CASES)), list(SWITCH_CASES.values()))
        ax.set(title=title, ylabel=unit)
        ax.grid(False, axis="x")
    axes[1, 1].set_ylim(80, 100)  # The visible interval is labeled on the axis to resolve small QoS differences.
    handles, labels = axes[1, 0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="outside lower center", ncols=4, frameon=False)
    return figure


def deferral_figure(summary):
    figure, axes = plt.subplots(2, 2, figsize=(11.8, 7.35), layout="constrained")
    figure.suptitle("主动延迟的使用门槛与成对实测结果", fontsize=17, fontweight="bold")
    rows = {(r["scenario"], r["seed"], r["variant"]): r for r in summary if r["group"] == "deferral"}
    seeds = sorted({r["seed"] for r in rows.values()})
    categories = list(TIMING_CASES)
    xs = list(range(len(categories)))
    width = .23

    ax = axes[0, 0]
    for i, (field, label, color) in enumerate((("hcm_proposals", "升档决策", GRAY), ("delay_allowed_proposals", "允许等待", TEAL),
                                               ("delay_opened", "实际开启等待", INK))):
        totals = [sum(int(rows[c, s, "deferred"][field]) for s in seeds) for c in categories]
        ax.bar([x + (i - 1) * width for x in xs], totals, width * .93, label=label, color=color)
    ax.set(ylabel="次数（两种子合计）", title="从升档建议到真正等待")
    ax.legend(loc="upper right", frameon=False, fontsize=8.5)

    for ax, field, factor, y_label, chart_title in (
            (axes[0, 1], "total_uj", .001, "能耗差（mJ）", "观察窗能耗差"),
            (axes[1, 0], "drain_uj", .001, "能耗差（mJ）", "排空阶段能耗差"),
            (axes[1, 1], "on_time_final", 1, "准时报文数差（包）", "最终准时交付差")):
        for n, (seed, marker, color) in enumerate(zip(seeds, ("o", "s"), (INK, RUST))):
            delta = [(float(rows[c, seed, "deferred"][field]) - float(rows[c, seed, "immediate"][field])) * factor for c in categories]
            ax.scatter([x + (n - .5) * .16 for x in xs], delta, marker=marker, color=color, label=f"种子 {seed}", s=27, zorder=3)
        ax.axhline(0, color="#8799a6", linewidth=.75)
        ax.set(ylabel=y_label, title=chart_title)
    for ax in axes.flat:
        ax.set_xticks(xs, list(TIMING_CASES.values()))
        ax.grid(False, axis="x")
    handles, labels = axes[0, 1].get_legend_handles_labels()
    figure.legend(handles, labels, title="差值为启用延迟减去立即切换", loc="outside lower center", ncols=2, frameon=False)
    return figure


def optical_figure(summary, frames):
    figure, axes = plt.subplots(1, 2, figsize=(11.9, 4.65), layout="constrained")
    figure.suptitle("GIANT 上行整包在队首无法前进", fontsize=17, fontweight="bold")
    rows = [r for r in frames if r["scenario"] == "two-1500-q2400" and r["grant_id"]][:10]
    ax = axes[0]
    frame_ids = [int(r["target_frame_id"]) for r in rows]
    ax.bar(frame_ids, [int(r["budget_bytes"]) for r in rows], width=.62, color="#b0c6d4", label="预约额度")
    ax.bar(frame_ids, [int(r["actual_bytes"]) for r in rows], width=.62, color=INK, label="实际发送")
    ax.axhline(1500, color=RUST, linewidth=1.0, linestyle="--", label="队首所需 1500 B")
    ax.set_xticks(frame_ids)
    ax.set(xlabel="目标帧编号（周期 125 μs）", ylabel="字节数（B）", title="首笔发走一包，随后 600 B 与 900 B 交替")
    ax.legend(loc="upper right", frameon=False, fontsize=8.5)
    ax.grid(False, axis="x")

    labels = {"single-1500-q1200": "一包 1500 B，额度 1200 B", "single-1200-q1200": "一包 1200 B，额度 1200 B",
              "single-1500-q2400": "一包 1500 B，额度 2400 B", "two-1500-q2400": "两包 1500 B，额度 2400 B",
              "two-1500-q2400-long": "两包 1500 B，轮询延至 20 ms", "two-1500-q3000": "两包 1500 B，额度 3000 B"}
    index = {r["scenario"]: r for r in summary if r["group"] == "pon-only"}
    ax = axes[1]
    delivered = [int(index[key]["delivered_final"]) for key in labels]
    pending = [int(index[key]["unsettled_final"]) for key in labels]
    ax.barh(list(labels.values()), delivered, height=.58, color=INK, label="已交付")
    ax.barh(list(labels.values()), pending, left=delivered, height=.58, color=RUST, label="结束时仍未交付")
    ax.invert_yaxis()
    ax.set(xticks=(0, 1, 2), xlim=(0, 2.15), xlabel="报文数（包）", title="孤立用例的最终去向")
    ax.grid(False, axis="y")
    ax.legend(loc="upper center", bbox_to_anchor=(.55, -.2), ncols=2, frameon=False, fontsize=8.5)
    return figure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=OUT)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    style, font = setup_font(resolve_font(args.font))
    summary = read_csv(args.data / "e0-summary.csv")
    switches = read_csv(args.data / "e0-switches.csv")
    optical = read_csv(args.data / "e0-pon-timeline.csv")
    for name, figure in (("e0-switch-diagnosis", switch_figure(summary, switches)),
                         ("e0-active-deferral", deferral_figure(summary)),
                         ("e0-pon-head-blocking", optical_figure(summary, optical))):
        save(figure, name, args.data, style, font)


if __name__ == "__main__":
    main()
