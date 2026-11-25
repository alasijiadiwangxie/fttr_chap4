"""Plot the D3 deadline, switching and energy results from the verified Java CSVs."""

from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path
from statistics import mean

from matplotlib.patches import Patch

from plot_c_phase import COLORS, plt, read_csv, resolve_font, save, setup_font
from verify_d3_qos_deferral import ACS, DEFER, MAIN, OUT, POLICIES


POLICY_ZH = dict(zip(POLICIES, ("固定阈值", "能耗与信道", "加入 QoS", "QoS 与主动延迟")))
CASE_ZH = dict(zip(MAIN, ("无干扰", "主信道干扰", "次信道干扰", "持续 85 Mb/s")))
POLICY_COLOR = dict(zip(POLICIES, ("#785daa", "#36795d", COLORS["LCM"], COLORS["HCM"])))
ACTIVE = "#006e9a"
PASSIVE = "#9b651d"
TRANSITION = "#c3460b"


def timeline(rows):
    cases = {"delay-be": "BE 到期提交", "early": "VO 提前提交", "passive": "等待现有 ACK", "cancel": "LCM 先发后撤销"}
    figure, axis = plt.subplots(figsize=(10.5, 3.8), layout="constrained")
    axis.set_title("主动延迟、被动等待与模式切换", fontsize=16, fontweight="bold", pad=16)
    micros = lambda row, key="t_ns": int(row[key]) / 1000
    for y, scenario in enumerate(cases):
        sample = [r for r in rows if r["scenario"] == scenario]
        start_plan = next((r for r in sample if r["event"] == DEFER and r["phase"] == "OPEN"), None)
        close_plan = next((r for r in sample if r["event"] == DEFER and r["phase"] in ("CANCEL", "RELEASE")), None)
        started = next((r for r in sample if r["event"] == "WIFI_AP_SWITCH_START"), None)
        finished = next((r for r in sample if r["event"] == "WIFI_AP_SWITCH_END"), None)
        if start_plan is not None:
            lo, hi = micros(start_plan), micros(close_plan)
            axis.broken_barh([(lo, hi - lo)], (y - .23, .46), facecolors=ACTIVE, linewidth=0)
            if close_plan["phase"] == "CANCEL":
                axis.plot(hi, y, marker="x", color="#25282b", markersize=7)
                x = next(micros(r) for r in sample if r["event"] == "WIFI_TX_START")
                axis.plot(x, y, marker="o", color="white", markeredgecolor="#343434", markersize=5)
                axis.annotate(f"{hi:.3f} μs 撤销", (hi, y), xytext=(9, 0), textcoords="offset points", va="center")
            if close_plan["phase"] == "RELEASE" and close_plan["trigger"] == "URGENT_QUEUE":
                axis.plot(micros(start_plan, "due_ns"), y + .31, marker="|", color=ACTIVE, markersize=8)
        if started is not None:
            request, start, end = micros(started, "request_ns"), micros(started), micros(finished)
            axis.plot(request, y, marker="|", color="#25282b", markersize=11)
            if start > request:
                axis.broken_barh([(request, start - request)], (y - .23, .46), facecolors=PASSIVE, linewidth=0)
                axis.text((request + start) / 2, y, f"{start-request:.1f} μs", color="white", ha="center", va="center")
            axis.broken_barh([(start, end - start)], (y - .23, .46), facecolors=TRANSITION, linewidth=0)
            axis.annotate(f"{end:.3f} μs 完成", (end, y), xytext=(8, 0), textcoords="offset points", va="center")
    axis.set(xlabel="仿真时间（μs）", xlim=(-3, 385), yticks=range(len(cases)), yticklabels=list(cases.values()),
             ylim=(-.57, len(cases) - .43))
    axis.invert_yaxis()
    axis.grid(False, axis="y")
    axis.legend(handles=[Patch(color=ACTIVE, label="主动延迟"), Patch(color=PASSIVE, label="B4 被动等待"),
                         Patch(color=TRANSITION, label="实际切换")], loc="upper center", bbox_to_anchor=(.5, -.25),
                ncol=3, frameon=False)
    return figure


def grouped_results(rows):
    grouped = defaultdict(list)
    for row in rows:
        if row["scenario"] in MAIN and row["sfu_id"] == "ALL":
            grouped[row["scenario"], row["policy"], row["ac"]].append(row)
    return grouped


def plot_groups(axis, grouped, ac, field, unit, error_bars=False):
    for i, policy in enumerate(POLICIES):
        points = [[float(row[field]) * unit for row in grouped[scenario, policy, ac] if row[field] != ""] for scenario in MAIN]
        means = [mean(p) if p else float("nan") for p in points]
        error = [[middle - min(p) if p else 0 for middle, p in zip(means, points)],
                 [max(p) - middle if p else 0 for middle, p in zip(means, points)]] if error_bars else None
        axis.bar([x + (i - 1.5) * .185 for x in range(len(MAIN))], means, .174, color=POLICY_COLOR[policy],
                 yerr=error, error_kw={"elinewidth": .75, "capsize": 1.8, "capthick": .75})
    axis.set_xticks(range(len(MAIN)), [CASE_ZH[c] for c in MAIN])
    axis.tick_params(axis="x", labelsize=9)
    axis.grid(False, axis="x")


def legend(figure):
    figure.legend(handles=[Patch(color=POLICY_COLOR[p], label=POLICY_ZH[p]) for p in POLICIES],
                  loc="outside lower center", ncol=len(POLICIES), frameon=False)


def qos_by_class(grouped):
    figure, axes = plt.subplots(2, 4, figsize=(15.2, 6.1), layout="constrained")
    figure.suptitle("四类业务的实际截止交付率与尾时延", fontsize=16, fontweight="bold")
    for column, ac in enumerate(ACS):
        ratio, tail = axes[:, column]
        plot_groups(ratio, grouped, ac, "on_time_ratio", 100)
        plot_groups(tail, grouped, ac, "p99_ns", 1e-6)
        ratio.set_ylim(0, 105)
        ratio.set_title(ac, fontweight="bold")
        ratio.tick_params(labelbottom=False)
        if column == 0:
            ratio.set_ylabel("截止交付率（%）")
            tail.set_ylabel("端到端 P99（ms）")
    legend(figure)
    return figure


def comparison(grouped):
    figure, axes = plt.subplots(1, 3, figsize=(14.5, 4.6), layout="constrained")
    figure.suptitle("四种策略的实际能耗与切换次数", fontsize=16, fontweight="bold")
    energy_field = "total_energy_uj"
    energy_diff = {}
    for scenario in MAIN:
        reference = {row["seed"]: float(row[energy_field]) for row in grouped[scenario, POLICIES[0], "ALL"]}
        for policy in POLICIES:
            group = (scenario, policy, "ALL")
            energy_diff[group] = [{energy_field: float(row[energy_field]) - reference[row["seed"]]} for row in grouped[group]]
    plot_groups(axes[0], grouped, "ALL", energy_field, 1e-3, error_bars=True)
    axes[0].set_ylabel("AP 总能耗（mJ）")
    plot_groups(axes[1], energy_diff, "ALL", energy_field, 1e-3, error_bars=True)
    axes[1].set_ylabel("相对固定阈值的能耗差（mJ）")
    axes[1].axhline(0, color="#6b6b6b", linewidth=.8)
    plot_groups(axes[2], grouped, "ALL", "switches_completed", 1, error_bars=True)
    axes[2].set_ylabel("完成切换（次）")
    legend(figure)
    return figure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=OUT)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    rows = read_csv(args.input / "d3-decision-requests.csv")
    grouped = grouped_results(read_csv(args.input / "d3-performance.csv"))
    properties, font = setup_font(resolve_font(args.font))
    save(timeline(rows), "d3-deferral-timeline", args.input, properties, font)
    save(qos_by_class(grouped), "d3-qos-by-ac", args.input, properties, font)
    save(comparison(grouped), "d3-switch-energy", args.input, properties, font)


if __name__ == "__main__":
    main()
