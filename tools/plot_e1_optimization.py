"""Render the three E1 charts from Java-derived CSVs using the project's Chinese plot font."""

from __future__ import annotations

import argparse
from pathlib import Path
from statistics import mean

from plot_c_phase import plt, read_csv, resolve_font, save, setup_font
from verify_e1_optimization import OUT, SEEDS


BLUE, GREEN, ORANGE, GRAY = "#234f72", "#16866f", "#bf6036", "#a9bfcc"
CASES = {"rf-secondary": "次信道", "step-recovery": "阶梯恢复", "rf-primary": "主信道",
         "periodic": "周期突发", "rate-low": "轻载", "rate-high": "持续高载"}


def pon_timeline(rows):
    figure, axes = plt.subplots(2, 1, figsize=(10.4, 5.15), sharex=True, sharey=True, layout="constrained")
    figure.suptitle("GIANT 上行从碎片授权循环恢复", fontsize=16, fontweight="bold")
    for ax, (variant, title) in zip(axes, (("frozen", "冻结版的 600 B／900 B 循环"), ("recovery", "修复版结算后重新取得整包授权"))):
        sample = [row for row in rows if row["scenario"] == "two-1500-q2400" and row["variant"] == variant
                  and 3 <= int(row["target_frame_id"]) <= 14]
        slots = [row for row in sample if row["slot_start_ns"]]
        for row in sample:
            time = int(row["target_frame_id"]) * .125
            ax.axvline(time, color="#dae2e6", linewidth=.6, zorder=0)
            if row["recovery_waiting"] == "true" and row["budget_bytes"] == "0":
                ax.annotate("等待结算", (time, 870), ha="center", color=ORANGE, fontsize=9)
        times = [int(row["slot_start_ns"]) / 1e6 for row in slots]
        ax.bar(times, [int(r["budget_bytes"]) for r in slots], .038, color=GRAY, label="授权额度")
        ax.bar(times, [int(r["actual_bytes"]) for r in slots], .038, color=GREEN, label="实际发送")
        ax.axhline(1500, linestyle="--", color=ORANGE, linewidth=.9, label="整包需 1500 B")
        ax.set(ylabel="字节数（B）", title=title, xlim=(.42, 1.82), ylim=(0, 2770))
        ax.grid(False, axis="x")
    axes[0].legend(ncols=3, loc="upper right", frameon=False, fontsize=9)
    axes[1].set_xlabel("上行时隙开始时刻（ms）")
    return figure


def cca_comparison(rows):
    figure, axes = plt.subplots(1, 2, figsize=(12.1, 4.7), layout="constrained", gridspec_kw={"width_ratios": [1.6, 1]})
    figure.suptitle("固定窗口 CCA 预测与实测宽带忙碌率", fontsize=16, fontweight="bold")
    ax = axes[0]
    anchor = [r for r in rows if r["group"] == "e0-anchor" and r["sfu_id"] == "2" and r["candidate_mode"] == "HCM"
              and 29_000_000 <= int(r["t_ns"]) <= 44_000_000]
    anchor.sort(key=lambda row: int(row["t_ns"]))
    for key, label, color, marker in (("legacy_expected", "旧版估计", ORANGE, "^"), ("predicted_busy", "5 ms 滑窗估计", BLUE, "o"),
                                       ("actual_busy", "随后窗口实测", GREEN, ".")):
        ax.plot([int(r["t_ns"]) / 1e6 for r in anchor], [float(r[key]) * 100 for r in anchor], color=color,
                label=label, marker=marker, markersize=2.4, linewidth=1.1)
    ax.axvline(35.984, linestyle="--", color="#8d9ba5", linewidth=.85)
    ax.annotate("35.984 ms 复核点", (35.984, 80), xytext=(36.45, 89), fontsize=9,
                arrowprops={"arrowstyle": "-", "color": "#8d9ba5"})
    ax.set(xlabel="决策时刻（ms）", ylabel="HCM 宽带忙碌率（%）", title="E0 冻结轨迹中的同点复核", ylim=(-2, 103))
    ax.legend(loc="upper left", frameon=False, fontsize=8.5)

    ax = axes[1]
    categories = [(case, periodic) for case in ("rf-secondary", "step-recovery") for periodic in (False, True)]
    labels = [CASES[case] + ("\n周期" if periodic else "\n全部") for case, periodic in categories]
    for idx, (key, label, color) in enumerate((("applied_error", "旧版估计", ORANGE), ("prediction_error", "5 ms 滑窗估计", BLUE))):
        values = []
        for case, periodic in categories:
            matched = [r for r in rows if r["scenario"] == case and r["group"] == "sensitivity" and r["variant"] == "shadow-5ms"
                       and r["candidate_mode"] == "HCM" and (not periodic or r["periodic_sample"] == "True")]
            values.append(100 * mean(abs(float(r[key])) for r in matched))
        ax.bar([i + (.34 * (idx - .5)) for i in range(len(categories))], values, .31, color=color, label=label)
    ax.set_xticks(range(len(categories)), labels)
    ax.set(title="同一轨迹上的全部与周期重判点", ylabel="平均绝对误差（百分点）")
    ax.set_ylim(0, ax.get_ylim()[1] * 1.17)
    ax.legend(loc="upper center", ncols=2, frameon=False, fontsize=8.5)
    ax.grid(False, axis="x")
    return figure


def high_priority(row):
    return 100 * (int(row["VO_on_time_final"]) + int(row["VI_on_time_final"])) / (int(row["VO_generated"]) + int(row["VI_generated"]))


def performance(rows):
    figure, axes = plt.subplots(2, 2, figsize=(11.65, 7.05), layout="constrained")
    figure.suptitle("独立种子下的切换、能耗与交付质量", fontsize=16, fontweight="bold")
    selected = [row for row in rows if row["group"] == "radio" and int(row["seed"]) in SEEDS]
    metrics = ((lambda r: int(r["switches"]), "完成切换次数（次）", "实际模式切换"),
               (lambda r: float(r["business_settlement_uj"]) / 1e6, "完整业务结算能耗（J）", "观察窗与排空合计"),
               (high_priority, "VO/VI 合计准时率（%）", "按窗内生成报文结算截止时间"),
               (lambda r: int(r["backlog_window"]), "观察窗末未完成（包）", "实际积压"))
    for panel, (ax, (metric, unit, title)) in enumerate(zip(axes.flat, metrics)):
        for index, (variant, label, color) in enumerate((("frozen", "冻结 D5", BLUE), ("e1", "E1 优化", GREEN),
                                                        ("fixed", "固定阈值", ORANGE))):
            points = [[metric(row) for row in selected if row["scenario"] == case and row["variant"] == variant] for case in CASES]
            values = [mean(point) for point in points]
            errors = [[values[i] - min(point) for i, point in enumerate(points)],
                      [max(point) - values[i] for i, point in enumerate(points)]]
            positions = [x + (index - 1) * .25 for x in range(len(CASES))]
            if panel == 2:
                ax.errorbar(positions, values, yerr=errors, fmt="o", markersize=5, color=color, label=label,
                            elinewidth=.85, capsize=2)
            else:
                ax.bar(positions, values, .23, color=color, label=label, yerr=errors,
                       error_kw={"elinewidth": .75, "capsize": 1.7})
        ax.set_xticks(range(len(CASES)), list(CASES.values()))
        ax.set(title=title, ylabel=unit)
        ax.grid(False, axis="x")
    axes[1, 0].set_ylim(80, 101.5)
    handles, labels = axes[0, 0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="outside lower center", ncols=3, frameon=False)
    return figure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=OUT)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    style, font = setup_font(resolve_font(args.font))
    for name, figure in (("e1-giant-timeline", pon_timeline(read_csv(args.data / "e1-pon-events.csv"))),
                         ("e1-cca-prediction", cca_comparison(read_csv(args.data / "e1-cca-prediction.csv"))),
                         ("e1-performance", performance(read_csv(args.data / "e1-performance.csv")))):
        save(figure, name, args.data, style, font)


if __name__ == "__main__":
    main()
