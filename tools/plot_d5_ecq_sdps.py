"""Plot the final ECQ-SDPS Java measurements with a checked Chinese font."""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
from statistics import mean

from matplotlib.patches import Patch

from d5_experiments import BASELINES, D4, FEATURES, FIXED, FULL, RATE, RF, SEEDS, STEP
from plot_c_phase import COLORS, plt, read_csv, resolve_font, save, setup_font
from verify_d5_ecq_sdps import END, INITIAL, OUT, START


POLICIES = (*BASELINES, D4)
LABELS = dict(zip(POLICIES, ("固定 LCM", "固定 HCM", "固定阈值", "完整 ECQ-SDPS", "原 D4")))
PALETTE = dict(zip(POLICIES, ("#0094a5", "#cf632a", "#745a98", "#15476a", "#728281")))
AC = ("VO", "VI", "BE", "BK")
RADIO_LABELS = ("无干扰", "主信道", "次信道", "宽带")


def index(rows):
    data = defaultdict(list)
    for row in rows:
        if row["sfu_id"] == "ALL":
            data[row["scenario"], row["policy"], row["disabled"], row["direction"], row["ac"]].append(row)
    return data


def center(rows, field, scale=1):
    sample = [float(row[field]) * scale for row in rows if row[field] != ""]
    if not sample:
        return float("nan"), 0, 0
    midpoint = mean(sample)
    return midpoint, midpoint - min(sample), max(sample) - midpoint


def policy_legend(figure, policies=BASELINES):
    figure.legend(handles=[Patch(color=PALETTE[p], label=LABELS[p]) for p in policies], loc="outside lower center",
                  ncols=len(policies), frameon=False)


def bars(axis, data, cases, labels, field, *, ac="ALL", direction="BOTH", scale=1, policies=BASELINES, errors=False):
    width = .76 / len(policies)
    for ordinal, policy in enumerate(policies):
        points = [center(data[case, policy, "", direction, ac], field, scale) for case in cases]
        means, low, high = zip(*points)
        axis.bar([i + (ordinal - (len(policies)-1)/2) * width for i in range(len(cases))], means, width*.92,
                 color=PALETTE[policy], yerr=[low, high] if errors else None,
                 error_kw={"elinewidth": .8, "capsize": 2, "capthick": .8})
    axis.set_xticks(range(len(cases)), labels)
    axis.grid(False, axis="x")


def load_efficiency(data):
    figure, axes = plt.subplots(1, 3, figsize=(13.4, 4.35), layout="constrained")
    figure.suptitle("业务负载下的 AP 功率、能效和实际吞吐", fontsize=16, fontweight="bold")
    loads = [case.stages[0][2] for case in RATE]
    metrics = (("power_w_per_ap", "平均功率（W/AP）"), ("nj_per_success_bit", "单位成功比特能耗（nJ/bit）"),
               ("goodput_mbps", "成功吞吐（Mb/s）"))
    for axis, (field, label) in zip(axes, metrics):
        for policy, marker in zip(BASELINES, ("o", "s", "^", "D")):
            points = [center(data[case.name, policy, "", "BOTH", "ALL"], field) for case in RATE]
            y, low, high = zip(*points)
            axis.errorbar(loads, y, yerr=[low, high], marker=marker, markersize=4, capsize=2,
                          linewidth=1.65, color=PALETTE[policy])
        axis.set_xticks(loads)
        axis.set(xlabel="配置输入负载（Mb/s）", ylabel=label)
    policy_legend(figure)
    return figure


def interference_qos(data):
    figure = plt.figure(figsize=(13.85, 8.0), layout="constrained")
    grid = figure.add_gridspec(3, 4, height_ratios=(.9, 1, 1))
    figure.suptitle("不同干扰下的窗末积压和逐业务 QoS", fontsize=16, fontweight="bold")
    scenarios = tuple(case.name for case in RF)
    for spec, field, label in ((grid[0, :2], "backlog_window", "窗末未完成（包）"),
                               (grid[0, 2:], "sinr_failed_attempts", "信道解码失败（次）")):
        axis = figure.add_subplot(spec)
        bars(axis, data, scenarios, RADIO_LABELS, field, errors=True)
        axis.set_ylabel(label)
    for column, ac in enumerate(AC):
        top = figure.add_subplot(grid[1, column])
        tail = figure.add_subplot(grid[2, column], sharex=top)
        bars(top, data, scenarios, RADIO_LABELS, "on_time_ratio", ac=ac, scale=100)
        bars(tail, data, scenarios, RADIO_LABELS, "p99_ms", ac=ac)
        top.set_title(ac, fontweight="bold")
        top.set_ylim(0, 105)
        top.tick_params(labelbottom=False)
        if column == 0:
            top.set_ylabel("最终准时率（%）")
            tail.set_ylabel("端到端 P99（ms）")
    policy_legend(figure)
    return figure


def dynamic_modes(data, trace):
    figure = plt.figure(figsize=(12.3, 7.0), layout="constrained")
    grid = figure.add_gridspec(3, 2, height_ratios=(.85, 1.0, 1.25))
    figure.suptitle("动态负载、实际模式时间线与驻留", fontsize=16, fontweight="bold")
    rows = [r for r in trace if r["scenario"] == STEP.name and r["seed"] == str(SEEDS[0]) and not r["disabled"]]
    load = figure.add_subplot(grid[0, :])
    times = [a/1000 for a, _, _ in STEP.stages] + [STEP.window_us/1000]
    rates = [r for _, _, r in STEP.stages] + [STEP.stages[-1][2]]
    interference = [t/1000 for t in STEP.interference_us]
    load.step(times, rates, where="post", color="#485865", linewidth=1.9)
    load.axvspan(*interference, alpha=.17, color="#ba8138", label="次信道干扰时段")
    load.set(xlim=(0, times[-1]), ylabel="输入配置（Mb/s）")
    load.tick_params(labelbottom=False)
    load.legend(loc="upper right", frameon=False)
    timing = figure.add_subplot(grid[1, :], sharex=load)
    selected = (FIXED, D4, FULL)
    for y, policy in enumerate(selected):
        track = [r for r in rows if r["policy"] == policy and r["sfu_id"] == "1"]
        mode = next(r["current_mode"] for r in track if r["event"] == INITIAL)
        last_ms = 0
        palette = COLORS | {"SWITCH": "#a8afb5"}
        for r in track:
            if r["event"] not in (START, END):
                continue
            moment_ms = min(times[-1], int(r["t_ns"]) / 1e6)
            timing.broken_barh([(last_ms, moment_ms-last_ms)], (y-.30, .60), facecolors=palette[mode], linewidth=0)
            mode = r["to_mode"] if r["event"] == END else "SWITCH"
            last_ms = moment_ms
        timing.broken_barh([(last_ms, times[-1]-last_ms)], (y-.30, .60), facecolors=palette[mode], linewidth=0)
    timing.set(yticks=range(3), yticklabels=[LABELS[p] for p in selected], ylim=(-.6, 2.6), xlabel=f"时间（ms，种子 {SEEDS[0]}）")
    timing.invert_yaxis()
    timing.grid(False, axis="y")
    timing.legend(handles=[Patch(color=COLORS[m], label=f"{m} 工作") for m in ("LCM", "HCM")]
                  + [Patch(color="#a8afb5", label="切换")], ncols=3, frameon=False, loc="upper center", bbox_to_anchor=(.5, 1.29))
    for spec, field, scale, label in ((grid[2, 0], "hcm_fraction", 100, "HCM 驻留比例（%）"),
                                     (grid[2, 1], "switches", 1, "完成切换（次）")):
        axis = figure.add_subplot(spec)
        bars(axis, data, (STEP.name,), ("1 秒阶梯负载",), field, scale=scale, policies=POLICIES, errors=True)
        axis.set_ylabel(label)
    policy_legend(figure, POLICIES)
    return figure


def joint_fttr(data):
    figure, axes = plt.subplots(2, 2, figsize=(11.4, 6.8), layout="constrained")
    figure.suptitle("FTTR 光无线融合的端到端性能", fontsize=16, fontweight="bold")
    names, labels = ("joint-fixed", "joint-giant"), ("理想光路", "共享 PON 与 GIANT")
    for i, (direction, label) in enumerate((("DL", "下行"), ("UL", "上行"))):
        axis, tail = axes[:, i]
        bars(axis, data, names, labels, "goodput_mbps", direction=direction, errors=True)
        bars(tail, data, names, labels, "p99_ms", direction=direction)
        axis.set_title(label, fontweight="bold")
        axis.set_ylabel("成功吞吐（Mb/s）")
        tail.set_ylabel("端到端 P99（ms）")
    policy_legend(figure)
    return figure


def paired_results(data, scenario, field, disabled, percent=False, ac="ALL"):
    reference = {r["seed"]: float(r[field]) for r in data[scenario, FULL, "", "BOTH", ac]}
    actual = {r["seed"]: float(r[field]) for r in data[scenario, FULL, disabled, "BOTH", ac]}
    return {seed: ((value/ref - 1) * 100 if percent else value-ref)
            for seed, value in actual.items() if (ref := reference.get(seed)) is not None}


def ablations(data):
    figure, axes = plt.subplots(2, 2, figsize=(11.5, 6.55), layout="constrained")
    figure.suptitle("逐项关闭机制后的配对变化", fontsize=16, fontweight="bold")
    cases = (("rf-secondary", "次信道干扰", "#c65d2d"), (STEP.name, "阶梯与信道恢复", "#245b77"),
             ("defer-primary", "主信道主动延迟补测", "#498953"))
    for axis, field, percentage, label in zip(axes.flat, ("total_uj", "goodput_mbps", "on_time_ratio", "switches"),
                                              (True, False, False, False), ("总能耗差（%）", "成功吞吐差（Mb/s）",
                                                                            "VO/VI 准时率差（百分点）", "切换次数差（次）")):
        for j, (name, _, color) in enumerate(cases):
            outcomes = []
            for flag in FEATURES:
                if field == "on_time_ratio":
                    vo, vi = (paired_results(data, name, field, flag, ac=ac) for ac in ("VO", "VI"))
                    paired = [50 * (vo[s] + vi[s]) for s in vo]
                else:
                    paired = list(paired_results(data, name, field, flag, percentage).values())
                outcomes.append(paired)
            averages = [mean(values) if values else float("nan") for values in outcomes]
            bounds = [[y-min(v) if v else 0 for y, v in zip(averages,outcomes)],
                      [max(v)-y if v else 0 for y,v in zip(averages,outcomes)]]
            axis.bar([i + (j - 1) * .24 for i in range(4)], averages, .22, yerr=bounds, capsize=2, color=color)
        axis.axhline(0, color="#777777", linewidth=.75)
        axis.set_xticks(range(4), ("关闭信道感知", "关闭 QoS", "关闭主动延迟", "关闭经济驻留"))
        axis.set_ylabel(label)
        axis.grid(False, axis="x")
    figure.legend(handles=[Patch(color=c, label=label) for _, label, c in cases], loc="outside lower center", ncols=3, frameon=False)
    return figure


def relative_energy(data):
    scenarios = dict(zip((c.name for c in RATE), ("低负载", "中负载", "高负载", "持续过载")))
    scenarios.update(zip((c.name for c in RF), RADIO_LABELS))
    scenarios.update({STEP.name: "1 秒阶梯", "periodic": "周期突发", "rate-mid-paper": "论文功率中载",
                      "joint-fixed": "理想光路", "joint-giant": "融合 PON", "rf-secondary-free": "免扫描费"})
    fig, axis = plt.subplots(figsize=(11.6, 4.15), layout="constrained")
    fig.suptitle("完整 ECQ-SDPS 相对固定阈值的观察窗能耗差", fontsize=16, fontweight="bold")
    points = []
    for name in scenarios:
        references = {r["seed"]: float(r["total_uj"]) for r in data[name, FIXED, "", "BOTH", "ALL"]}
        points.append([(float(r["total_uj"]) / references[r["seed"]] - 1) * 100 for r in data[name, FULL, "", "BOTH", "ALL"]])
    averages = [mean(run) for run in points]
    deviations = [[middle-min(run) for middle,run in zip(averages,points)], [max(run)-middle for middle,run in zip(averages,points)]]
    axis.errorbar(range(len(points)), averages, yerr=deviations, fmt="o", color=PALETTE[FULL], capsize=4)
    axis.axhline(0, linewidth=.8, color="#6a6a6a")
    axis.set_xticks(range(len(points)), tuple(scenarios.values()), rotation=28, ha="right")
    axis.set_ylabel("总能耗相对变化（%）")
    axis.grid(False, axis="x")
    return fig


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=OUT)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    properties, font = setup_font(resolve_font(args.font))
    main_rows = index(read_csv(args.input / "d5-performance.csv"))
    runs = index(read_csv(args.input / "d5-ablation.csv"))
    trace = read_csv(args.input / "d5-mode-events.csv")
    for name, figure in (("d5-load-efficiency", load_efficiency(main_rows)), ("d5-interference-qos", interference_qos(main_rows)),
                         ("d5-dynamic-modes", dynamic_modes(main_rows, trace)), ("d5-fttr-joint", joint_fttr(main_rows)),
                         ("d5-ablation", ablations(runs)), ("d5-relative-energy", relative_energy(main_rows))):
        save(figure, name, args.input, properties, font)


if __name__ == "__main__":
    main()
