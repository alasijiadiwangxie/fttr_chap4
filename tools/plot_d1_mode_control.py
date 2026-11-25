"""Draw the two Chinese D1 figures from the Java-derived CSV files."""

from __future__ import annotations

import argparse
from pathlib import Path
from statistics import mean

from matplotlib.patches import Patch

from plot_c_phase import COLORS, read_csv, resolve_font, save, setup_font, plt
from verify_d1_mode_control import DECISION, FIXED, INITIAL, POLICIES, RESULTS, SEEDS, SWITCH, THRESHOLD


POLICY_LABELS = ("固定 LCM", "固定 HCM", "负载阈值")
POLICY_COLORS = (COLORS["LCM"], COLORS["HCM"], "#6b54a1")


def primary_rows(rows):
    return [row for row in rows if row["scenario"] == "step" and row["sfu_id"] == "1"]


def timeline(rows, stop_ms, seed):
    rows = [row for row in primary_rows(rows) if row["seed"] == str(seed) and row["policy"] == FIXED]
    decisions = [row for row in rows if row["event"] == DECISION]
    boundaries = [int(decisions[0]["window_start_ns"]) / 1e6] + [int(row["t_ns"]) / 1e6 for row in decisions]
    measured = [float(row["arrival_mbps"]) for row in decisions]
    estimated = [float(row["ewma_mbps"]) for row in decisions]
    final_ingress = sum(int(row["arrival_bytes"]) for row in rows if row["event"] == "WIFI_AP_QUEUE_EVENT")
    final_rate = final_ingress * .008 / (stop_ms - boundaries[-1])

    figure, (load, mode) = plt.subplots(2, 1, figsize=(9.3, 5.2), sharex=True, layout="constrained",
                                       gridspec_kw={"height_ratios": (3.6, 1.25)})
    figure.suptitle("阶梯下行负载与 AP 实际工作模式", fontsize=16, fontweight="bold")
    load.stairs(measured + [final_rate], boundaries + [stop_ms], baseline=None, color="#53878c", linewidth=1.8,
                label="窗口内实际到达率")
    load.step(boundaries[1:] + [stop_ms], estimated + [estimated[-1]], where="post", color="#6b54a1",
              linewidth=2, label="到达率的指数加权估计")
    load.plot(boundaries[1:], estimated, ".", color="#6b54a1", markersize=4)
    load.axhline(THRESHOLD, color="#55585e", linewidth=1.1, linestyle="--", label="切换阈值 31 Mb/s")
    load.set(ylabel="下行速率（Mb/s）", ylim=(0, 59))
    load.legend(loc="lower center", ncol=3, frameon=False, fontsize=9)

    state = next(row["current_mode"] for row in rows if row["event"] == INITIAL)
    start_ms = 0.0
    for row in rows:
        if row["event"] == SWITCH + "START":
            now_ms = int(row["t_ns"]) / 1e6
            mode.axvspan(start_ms, now_ms, ymin=.16, ymax=.83, color=COLORS[state], linewidth=0)
            mode.axvline(now_ms, color="#333333", linewidth=.9)
            load.axvline(now_ms, color="#333333", linewidth=.85, alpha=.46)
            mode.annotate(f"{now_ms:.3f} ms", (now_ms, .88), xycoords=("data", "axes fraction"),
                          ha="center", va="bottom", fontsize=9)
            start_ms = now_ms
        elif row["event"] == SWITCH + "END":
            end_ms = int(row["t_ns"]) / 1e6
            mode.axvspan(start_ms, end_ms, ymin=.16, ymax=.83, color="#434343", linewidth=0)
            state, start_ms = row["to_mode"], end_ms
    mode.axvspan(start_ms, stop_ms, ymin=.16, ymax=.83, color=COLORS[state], linewidth=0)
    mode.set(xlim=(0, stop_ms), ylabel="AP 模式", yticks=[], xlabel="仿真时间（ms）")
    mode.spines[["top", "left", "right"]].set_visible(False)
    mode.grid(False)
    mode.legend(handles=[Patch(color=COLORS["LCM"], label="LCM 低能力"),
                         Patch(color=COLORS["HCM"], label="HCM 高能力"),
                         Patch(color="#434343", label="实际切换")],
                loc="upper center", bbox_to_anchor=(.5, -.48), ncol=3, frameon=False)
    return figure


def comparison(rows):
    groups = [[row for row in primary_rows(rows) if row["ac"] == "ALL" and row["policy"] == policy]
              for policy in POLICIES]

    def values(field, divisor=1):
        return [mean(float(row[field]) / divisor for row in group) for group in groups]

    def label_bars(axis, bars, fmt):
        axis.bar_label(bars, fmt=fmt, padding=3, fontsize=9)
        axis.margins(y=.19)

    figure, axes = plt.subplots(2, 2, figsize=(9.8, 6.6), layout="constrained")
    figure.suptitle("三种策略在相同阶梯业务下的表现", fontsize=16, fontweight="bold")
    energy, delay, late, switching = axes.flat
    xs = list(range(3))
    for axis in axes.flat:
        axis.set_xticks(xs, POLICY_LABELS)
        axis.grid(False, axis="x")

    label_bars(energy, energy.bar(xs, values("total_energy_uj", 1000), width=.58, color=POLICY_COLORS), "%.2f")
    energy.set(title="AP 总能耗", ylabel="能耗（mJ）")

    for p, offset, color in ((95, -.18, "#52899e"), (99, .18, "#855e9f")):
        bars = delay.bar([x + offset for x in xs], values(f"p{p}_ns", 1e6), width=.32, label=f"P{p}", color=color)
        label_bars(delay, bars, "%.3f")
    delay.set(title="端到端尾时延", ylabel="时延（ms）")
    delay.set_ylim(0, max(values("p99_ns", 1e6)) * 1.34)
    delay.legend(loc="upper center", ncol=2, frameon=False)

    pct = values("violation_ratio", .01)
    label_bars(late, late.bar(xs, pct, width=.58, color=POLICY_COLORS), "%.2f%%")
    if max(pct) == 0:
        late.set_ylim(0, 1)
    late.set(title="超过交付时限的比例", ylabel="违约率（%）")

    label_bars(switching, switching.bar(xs, values("switches_completed"), width=.58, color=POLICY_COLORS), "%.0f")
    switching.set(title="实际完成的模式切换", ylabel="切换次数（次）", yticks=(0, 1, 2))
    return figure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=RESULTS)
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    event_rows = read_csv(args.input / "d1-mode-events.csv")
    metrics = read_csv(args.input / "d1-performance.csv")
    stop_ms = int(primary_rows(metrics)[0]["observation_end_ns"]) / 1e6
    properties, font = setup_font(resolve_font(args.font))
    save(timeline(event_rows, stop_ms, SEEDS[0]), "d1-load-mode-timeline", args.input, properties, font)
    save(comparison(metrics), "d1-policy-comparison", args.input, properties, font)


if __name__ == "__main__":
    main()
