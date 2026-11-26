"""Render E4 thesis figures from the reconciled per-seed and paired CSV files."""

from __future__ import annotations

import argparse
from pathlib import Path
from statistics import mean

from e4_design import E1, E3, FIXED, HCM, HYST, LCM, MAIN
from e4_run import OUT
from plot_c_phase import plt, read_csv, resolve_font, save, setup_font

COLORS = {LCM: "#188a8a", HCM: "#c55c2d", FIXED: "#8f69aa", HYST: "#69913c", E1: "#79808c", E3: "#244578"}
LABEL = {LCM: "固定 LCM", HCM: "固定 HCM", FIXED: "固定阈值 31", HYST: "双阈值滞回", E1: "冻结 E1", E3: "冻结 E3"}
RF = ("rf-clear", "rf-primary-strong-35", "rf-secondary-strong-15", "rf-secondary-strong-35", "rf-wide-strong-35")
RF_LABELS = ("无干扰", "主信道\n35%", "次信道\n15%", "次信道\n35%", "宽带\n35%")
JOINT = ("joint-2sfu", "joint-4sfu", "joint-4sfu-rf")
JOINT_LABELS = ("2 台\n无干扰", "4 台\n无干扰", "4 台\n次信道 15%")
COMPARATORS = (FIXED, HYST, E1, E3)


def sample_means(rows, cases, metric, ac="ALL", direction="BOTH"):
    by_cell = {}
    for case in cases:
        for policy in MAIN:
            values = [float(r[metric]) for r in rows if r["scenario"] == case and r["policy"] == policy
                      and (r["sfu_id"], r["direction"], r["ac"]) == ("ALL", direction, ac) and r.get(metric)]
            by_cell[case, policy] = mean(values) if values else float("nan")
    return by_cell


def on_axes(ax, labels, values, cases, y, policies=MAIN, *, lines=False, cap=None, positions=None):
    size = len(policies)
    points = range(len(cases)) if positions is None else positions
    for i, policy in enumerate(policies):
        samples = [values[name, policy] for name in cases]
        if lines:
            ax.plot(points, samples, color=COLORS[policy], label=LABEL[policy], marker=("o", "s", "^", "v", "P", "D")[i],
                    linewidth=1.55, markersize=4.8)
        else:
            width = .82 / size
            ax.bar([p + (i - (size - 1) / 2) * width for p in points], samples, width=width,
                   color=COLORS[policy], label=LABEL[policy], alpha=.94)
    ax.set_xticks(points, labels)
    ax.set(ylabel=y, ylim=(0, cap))


def legend(fig, ax, ncol=6):
    fig.legend(*ax.get_legend_handles_labels(), loc="outside lower center", ncols=ncol, frameon=False)


def find_pair(rows, scenario, baseline, metric, ac="ALL", direction="BOTH", policy=E3):
    return next(row for row in rows if (row["scenario"], row["policy"], row["baseline"], row["metric"],
                 row["sfu_id"], row["direction"], row["ac"]) == (scenario, policy, baseline, metric, "ALL", direction, ac))


def interval_values(rows):
    means = [float(r["mean"]) for r in rows]
    return means, [[max(0, a - float(r["ci95_low"])) for a, r in zip(means, rows)],
                   [max(0, float(r["ci95_high"]) - a) for a, r in zip(means, rows)]]


def relative(ax, rows, cases, labels, metric="complete_uj_relative_pct", baselines=(FIXED, HYST)):
    for i, policy in enumerate(baselines):
        centers, errors = interval_values([find_pair(rows, case, policy, metric) for case in cases])
        ax.errorbar([j + (i - (len(baselines) - 1) / 2) * .15 for j in range(len(cases))],
                    centers, yerr=errors, marker=("o", "s", "^")[i], linestyle="none", capsize=3,
                    label="相对" + LABEL[policy], color=COLORS[policy])
    ax.axhline(0, color="#555555", linewidth=.9)
    ax.set_xticks(range(len(cases)), labels)
    ax.set_ylabel("完整结算能耗差（%）")
    ax.legend(frameon=False, loc="best", fontsize=9)


def load(rows, paired):
    cases, labels = ("load-012", "load-031", "load-050", "load-085"), ("12", "31", "50", "85")
    fig, axes = plt.subplots(2, 2, figsize=(11.6, 7.1), layout="constrained")
    for ax, (key, label) in zip(axes.flat, (("goodput_mbps", "成功吞吐量（Mb/s）"), ("power_w_per_ap", "AP 平均功率（W）"),
                                          ("nj_per_success_bit", "单位成功比特能耗（nJ/bit）"))):
        on_axes(ax, labels, sample_means(rows, cases, key), cases, label, lines=True, positions=tuple(map(int, labels)))
        ax.set_xlabel("配置输入负载（Mb/s）")
    relative(axes[1, 1], paired, cases, labels)
    axes[1, 1].set_xlabel("配置输入负载（Mb/s）")
    fig.suptitle("稳态负载下的吞吐、功率和完成业务的能耗", fontweight="bold")
    legend(fig, axes[0, 0])
    return fig


def interference(rows, paired):
    fig, axes = plt.subplots(2, 2, figsize=(12.0, 7.3), layout="constrained")
    for ax, (key, label, cap) in zip(axes.flat, (("goodput_mbps", "成功吞吐量（Mb/s）", None),
            ("on_time_pct", "全业务准时率（%）", 105), ("backlog_window", "窗末未完成（包）", None))):
        on_axes(ax, RF_LABELS, sample_means(rows, RF, key), RF, label, cap=cap)
    relative(axes[1, 1], paired, RF, RF_LABELS)
    fig.suptitle("不同子信道强干扰下的服务、积压与完整能耗", fontweight="bold")
    legend(fig, axes[0, 0])
    return fig


def qos(rows):
    fig, axes = plt.subplots(2, 3, figsize=(13.0, 7.0), layout="constrained")
    labels = ("VO", "VI", "BE", "BK")
    for line, case in zip(axes, ("mixed-45", "mixed-100")):
        for j, (key, label, cap) in enumerate((("on_time_pct", "准时率（%）", 105),
                                              ("p99_ms", "P99 端到端时延（ms）", None),
                                              ("jitter_ms", "平均抖动（ms）", None))):
            values = {}
            for ac in labels:
                values.update({(ac, p): v for (_, p), v in sample_means(rows, (case,), key, ac).items()})
            on_axes(line[j], labels, values, labels, label, cap=cap)
        line[0].set_title(f"{case.rsplit('-', 1)[1]} Mb/s 混合业务", fontweight="bold")
    fig.suptitle("四类业务的准时交付、尾时延和抖动", fontweight="bold")
    legend(fig, axes[0, 0])
    return fig


def dynamic(rows, paired):
    cases, labels = ("dynamic-periodic", "dynamic-random", "dynamic-near"), ("周期突发", "随机突发", "阈值波动")
    fig, axes = plt.subplots(2, 2, figsize=(11.5, 7.2), layout="constrained")
    for ax, (key, label, cap) in zip(axes.flat, (("switches", "观察窗切换次数（次）", None),
            ("hcm_pct", "HCM 驻留比例（%）", 105), ("on_time_pct", "全业务准时率（%）", 105))):
        on_axes(ax, labels, sample_means(rows, cases, key), cases, label, policies=COMPARATORS, cap=cap)
    relative(axes[1, 1], paired, cases, labels)
    fig.suptitle("动态业务下的模式切换、驻留与交付", fontweight="bold")
    legend(fig, axes[0, 0], 4)
    return fig


def joint(rows, paired):
    fig, axes = plt.subplots(2, 2, figsize=(12.1, 7.5), layout="constrained")
    for ax, (direction, key, label) in zip(axes.flat, (("DL", "goodput_mbps", "下行全网吞吐量（Mb/s）"),
            ("UL", "goodput_mbps", "上行全网吞吐量（Mb/s）"), ("UL", "p99_ms", "上行 P99 端到端时延（ms）"))):
        on_axes(ax, JOINT_LABELS, sample_means(rows, JOINT, key, direction=direction), JOINT, label)
    relative(axes[1, 1], paired, JOINT, JOINT_LABELS)
    fig.suptitle("2/4 台 SFU 的真实 GIANT DBA 光无线联合实验", fontweight="bold")
    legend(fig, axes[0, 0])
    return fig


def sensitive(paired, original):
    fig, axes = plt.subplots(2, 2, figsize=(12.2, 7.8), layout="constrained")
    switches = ("e3-no-capacity", "e3-no-economics", "e3-no-channel", "e3-no-qos", "e3-no-deferral", "e3-no-residency")
    labels = ("容量保护", "经济校验", "信道感知", "QoS", "主动延迟", "经济驻留")
    for ax, (key, label) in zip(axes[0], (("complete_uj_relative_pct", "完整结算能耗差（%）"),
                                        ("on_time_pct", "全业务准时率差（百分点）"))):
        m, band = interval_values([find_pair(paired, "mechanisms", option, key) for option in switches])
        ax.bar(range(len(labels)), m, yerr=band, capsize=3, color=COLORS[E3], width=.53)
        ax.axhline(0, color="#555555", linewidth=.9)
        ax.set_xticks(range(len(labels)), labels)
        ax.set_ylabel(label)
        ax.set_title("完整 E3 减去关闭对应机制")
    names = ("load-031", "rf-secondary-strong-15", "joint-4sfu")
    names_cn = ("31 Mb/s", "次信道 15%", "4 台 SFU")
    for ax, baseline in zip(axes[1], (FIXED, HYST)):
        for i, (prefix, table, caption) in enumerate((("", original, "B3 功率"), ("paper-", paired, "论文功率"))):
            m, band = interval_values([find_pair(table, prefix + name, baseline, "complete_uj_relative_pct") for name in names])
            ax.errorbar([j + (i - .5) * .18 for j in range(3)], m, yerr=band, capsize=3, marker=("o", "s")[i],
                        linestyle="none", color=COLORS[E3], alpha=.6 + i * .4, label=caption)
        ax.axhline(0, color="#555555", linewidth=.9)
        ax.set_xticks(range(3), names_cn)
        ax.set_ylabel("完整结算能耗差（%）")
        ax.set_title("E3 相对" + LABEL[baseline])
        ax.legend(frameon=False, loc="best", fontsize=9)
    fig.suptitle("六项独立消融与两组功率参数敏感性", fontweight="bold")
    return fig


def deferrals(rows, paths):
    setups = [(gap, deadline, order) for gap in (6, 16) for deadline in ("tight", "relaxed")
              for order in ("grouped", "interleaved")]
    cases = [f"defer-{gap}ms-{deadline}-{order}" for gap, deadline, order in setups] + ["load-031"]
    labels = [f"{gap} ms\n{'紧' if deadline == 'tight' else '宽'}/{ '同 STA' if order == 'grouped' else '交错'}"
              for gap, deadline, order in setups] + ["31 Mb/s\n常流"]
    fig, axes = plt.subplots(2, 1, figsize=(11.8, 6.9), layout="constrained")
    for i, (key, legend_text, color) in enumerate((("actual_path_changed", "实际切换轨迹改变", COLORS[E3]),
                                                   ("executed", "至少一次延期后完成切换", "#b6783f"))):
        numbers = [sum((r[key] == "True" if key == "actual_path_changed" else int(r[key]) > 0)
                       for r in paths if r["scenario"] == case) for case in cases]
        axes[0].bar([x + (i - .5) * .32 for x in range(len(cases))], numbers, width=.32, color=color, label=legend_text)
    axes[0].set(ylim=(0, 10.6), ylabel="出现的种子数（共 10）")
    axes[0].set_xticks(range(len(cases)), labels)
    axes[0].legend(frameon=False, loc="upper left", ncols=2)
    all_rows = {case: [r for r in rows if r["scenario"] == case and r["policy"] == E3
                      and (r["sfu_id"], r["direction"], r["ac"]) == ("ALL", "BOTH", "ALL")] for case in cases}
    depths = [mean(float(row["dl_mean_mpdus_per_ppdu"]) for row in all_rows[case]) for case in cases]
    axes[1].bar(range(len(cases)), depths, width=.5, color=COLORS[E3])
    axes[1].set(ylim=(0, None), ylabel="实际下行每帧平均报文数")
    axes[1].set_xticks(range(len(cases)), labels)
    fig.suptitle("主动延迟的实际轨迹与聚合机会", fontweight="bold")
    return fig


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    font_properties, font = setup_font(resolve_font(args.font))
    plt.rcParams.update({"svg.hashsalt": "e4-thesis", "axes.grid.axis": "y", "figure.titlesize": 15})
    groups = sorted(p for p in OUT.glob("0*") if p.is_dir())
    seeds = [read_csv(group / "seeds.csv") for group in groups]
    paired = [read_csv(group / "paired.csv") for group in groups]
    figures = (("01-load", load(seeds[0], paired[0])), ("02-interference", interference(seeds[1], paired[1])),
               ("03-qos", qos(seeds[2])), ("04-dynamics", dynamic(seeds[3], paired[3])),
               ("05-fttr", joint(seeds[4], paired[4])),
               ("06-ablations-power", sensitive(paired[5], paired[0] + paired[1] + paired[4])),
               ("07-deferral", deferrals(seeds[0] + seeds[5], read_csv(groups[5] / "deferral-trajectory.csv"))))
    folder = OUT / "figures"
    folder.mkdir(exist_ok=True)
    for name, chart in figures:
        save(chart, name, folder, font_properties, font)


if __name__ == "__main__":
    main()
