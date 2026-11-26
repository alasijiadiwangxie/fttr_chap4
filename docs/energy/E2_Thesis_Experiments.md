# E2｜ECQ-SDPS 第四章正式仿真实验

基于 `b20968f` 的 `ecq-sdps-e1` 和 `giant-complete-recovery`。29 个代表场景均使用 10 组独立配对种子，共运行 **1,110 次正式 Java 仿真**，另有 32 次长短窗口预跑。表中的“固定阈值”始终为 31 Mb/s。

**主要结果。** 单 AP、31 Mb/s、1500 B 下行时，ECQ-SDPS 与固定阈值的准时率同为 100%，完整结算能耗下降 **0.60%**（95% CI 0.47%～0.73%）。阈值附近波动时，观察窗内平均切换从 37.3 次降至 4.0 次。多 SFU 光无线联合场景的 P99 有所改善，完整结算能耗差的区间跨过零。50 Mb/s 和多组干扰场景出现性能退化。主信道强干扰、占空比 35% 时，ECQ-SDPS 窗内能耗低 1.06%，算上排空后能耗反而高 30.61%。

## 1. 统一实验参数

| 项目 | 正式实验配置 |
| --- | --- |
| 主对照 | 固定 LCM、固定 HCM、31 Mb/s 固定阈值、ECQ-SDPS |
| 无线 PHY | 802.11ax HE SU，MCS 7，GI 0.8 μs。LCM 为 20 MHz/1 SS，HCM 为 80 MHz/2 SS |
| 无线 MAC | EDCA、多 AC、ACK/Block ACK，SIFS 16 μs，Slot 9 μs，ACK 24 Mb/s。BE AIFSN=3，即 AIFS=43 μs。A-MPDU 上限 8，TXOP 为 0，最多重试 2 次 |
| AP 控制 | 决策、C1 采样和 E1 CCA 历史窗口均为 5 ms。固定阈值按 31 Mb/s 比较业务到达率，预测沿用 E1 的 `load-aware` 配置 |
| 活动功率 | B3 `RESEARCH_INITIAL_V1`，按 Idle/TX/RX 排列，LCM 为 7.38/9.20/8.20 W，HCM 为 7.62/10.50/8.70 W。功率敏感性使用参考论文的 6.96/12.96/7.92 W 和 7.92/15.00/12.00 W |
| 切换参数 | LCM→HCM 为 40.125 μs、11.50 W。HCM→LCM 为 25.250 μs、8.75 W |
| PON 下行 | 共享 10,000 Mb/s，传播 10 μs。所有场景与所有策略使用同一光侧建模配置 |
| PON 上行 | 2,500 Mb/s、125 μs 帧周期、传播 10.025 μs，逻辑 REPORT/BWmap、需求账本、真实 `giant-complete-recovery`。窗口后继续提供 80 ms 的主动轮询机会 |
| PON 轮询与配额 | 每帧轮询 1 个 Alloc-ID，REPORT-only 占位 64 B、205 ns。VO→T1、VI→T2、BE→T3、BK→T4。每 SFU 的 T1 固定 1,200 B/帧，T2 保障 2,400 B/帧，T3 保障 2,400 B/2 帧并可申请 2,400 B/帧额外额度，T4 配额 12,000 B/帧 |
| 混合业务 | VO/VI/BE/BK 的目标比特量比例为 7/12/74/7%，包长 500/1,000/1,500/1,200 B，端到端截止时间 0.65/1.4/6/12 ms。独立泊松间隔，DL 的目的 STA 在本 SFU 下均匀抽取，UL 在关联 STA 之间均分 |
| 信道与观测 | 80 MHz 划成四个 20 MHz 子信道。干扰周期 4 ms，起始相位按种子随机。弱/强干扰到达 AP 为 −85/−74 dBm，到达 STA 为 −87/−81 dBm，CCA 阈值 −82 dBm。干净信道为 30 dB，噪声 −95 dBm。STA 逻辑反馈默认延迟 100 μs、有效期 6 ms，控制空口占用设为 0。辅助射频仅在 LCM 下观测次信道，每次 400 μs、额外 0.5 W |
| 正式配对种子 | 11317、14851、18233、22751、26479、30757、34963、38993、43801、47441 |
| 时间窗口 | 稳态与融合 500 ms，动态 800 ms，消融 600 ms。预跑种子 5219、7877 独立于正式种子 |

主场景功率沿用 B3 设备级研究参数。论文功率组替换活动功率表，采用同一仿真 PHY/MAC 和切换参数进行敏感性分析。参考功率的换算沿用已有记录。[D5 功率参数依据](D5_ECQ_SDPS_Validation.md) · [逐场景参数和种子](../../results/e2-thesis/scenario-parameters.csv)

### 配对与统计口径

- **配对。** 先生成一份逐包到达清单，再把同一清单载入各策略。Java 事件逐一核对了报文 ID、生成时刻、包长、AC、方向和归属 STA/SFU。同一干扰组内对比策略时使用同一外部时间线与种子。切换和重传改变后续竞争顺序，实际成功、重传及干扰暴露允许不同。比较始终使用冻结的 31 Mb/s 阈值。
- **能耗。** 主窗口对全部 AP 的 Idle、TX、RX、Switch 实际积分，再加辅助扫描附加能耗。排空能耗从窗末积分到这批报文最后交付、显式丢弃或最后一次关联无线活动结束。N 台 AP 共同使用这个截止时刻，先结束传输的 AP 按 Idle 计费。单纯等待有限轮询计划结束的尾段不计入已结清业务的排空能耗。完整结算能耗为窗内加排空。
- **吞吐和功率。** 全网吞吐是窗口内所有最终接收端的有效载荷比特数除以窗口时长，每 AP 吞吐再除以 AP 数。图中的 AP 平均功率为全网 AP 窗口能耗除以 AP 数和窗口长度。单位成功比特能耗同时保存窗内和完整结算口径。空载时该指标留空。
- **QoS 和未完成。** 每个在窗口内生成的报文都按生成时刻加 AC 截止时间结算，晚到、显式丢弃、最终仍未交付均计作违约。P95/P99 用同一种子下已交付报文的最近秩，计入排空期间真正交付的报文，并与窗末积压及最终未交付并列。Jitter 是同一 SFU、STA、方向及 AC 的连续交付报文端到端时延绝对差的均值。上行光侧时延从 SFU 入 T-CONT 到 MFU 接收，下行从 MFU 注入到 SFU 收齐。剩余端到端时延记为无线侧，包含等待和帧交换。
- **不确定性。** 各种子等权。每个指标保存均值、样本标准差及双侧 95% Student t 置信区间。主要结论使用同一种子先做差、再求区间的配对方法。相对能耗先按各对照种子的能耗归一化。区间跨过零时写作“该组未检出明确差异”。多个场景的区间用于描述各自结果。

### 窗口长度与基本验收

31 Mb/s、次信道强干扰 35%、四 SFU 融合分别比较 0.5/1 s，阶梯流量比较 0.8/1.6 s。两种策略和两个独立种子的 16 个长短配对使用相同到达前缀。吞吐相对变化最大 4.50%，平均功率最大 0.41%，均落在预设的 5% 和 1% 工程检查线内。次信道强干扰 35% 时，四个长短配对的 P99 从 126.6–203.5 ms 增至 263.3–451.2 ms。该场景持续积压，时延和积压结论对应固定观察窗。其余场景的 P99 对窗长也有波动，尤其是阶梯流量的短窗尾部样本。[长短窗口实测](../../results/e2-thesis/duration-check.csv)

7 项实验框架定向测试通过，包括配置配对、窗口前缀、置信区间、截止时间与积压口径、实际 Java 重放和时间截断。同一份联合配置重放两次，所选事件日志逐字节一致。1,110 次正式仿真累计注入 3,490,619 个报文，最终交付 3,489,450 个，显式丢弃 1,169 个，最终未交付为 0。窗末未完成的 389,244 个计数保留在各场景原始表。逐包因果、无重复交付、B4 切换安全、B2 状态时长、B3 能量、PON 授权容量与收发对账全部通过。

## 2. 六组正式结果

### 2.1 负载、功率和交付

1 SFU/1 STA，1500 B 纯 BE 下行。以下功率和吞吐均在观察窗内测量，能耗差采用完整结算值。

| 输入（Mb/s） | 固定 LCM / 固定 HCM 吞吐（Mb/s） | ECQ-SDPS 吞吐（Mb/s） | ECQ-SDPS / 固定阈值功率（W） | ECQ-SDPS 相对固定阈值完整能耗差（95% CI） |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 0 / 0 | 0 | 7.38 / 7.38 | 0 |
| 12 | 11.83 / 11.84 | 11.83 | 7.74 / 7.74 | 0 |
| 31 | 31.33 / 31.37 | 31.37 | 8.15 / 8.20 | −0.60% [−0.73%, −0.47%] |
| 50 | 50.22 / 50.31 | 50.28 | 8.46 / 8.36 | +1.25% [+1.09%, +1.42%] |
| 85 | 70.54 / 85.65 | 85.65 | 8.64 / 8.64 | 0 |
| 130 | 70.61 / 129.50 | 129.50 | 8.86 / 8.86 | 0 |

12 和 31 Mb/s 的固定 LCM/HCM 功率排序发生反转，交叉只定位在这两个采样点之间。31 Mb/s 时 ECQ-SDPS 的单位成功比特能耗比固定阈值低 0.62%（95% CI 0.47%～0.77%），P99 从 1.20 降到 0.66 ms。50 Mb/s 时两者均准时交付，ECQ-SDPS 平均切换 29 次，固定阈值 1 次。种子 11317 的日志记录了 45 ms 请求降档、50 ms 请求重新升档，两次的两档 QoS 预测均为可行且原因均为 `ECONOMICAL_SWITCH`。这给出了短期能耗决策引起反向动作的实例。85/130 Mb/s 时本场景固定 LCM 的成功吞吐在约 70.6 Mb/s 饱和，积压随负载增长。该容量限于单 STA、单 AC、1500 B 的配置。  
[图 1 PNG](../../results/e2-thesis/figures/load-performance.png) · [SVG](../../results/e2-thesis/figures/load-performance.svg) · [小幅相对能耗图 PNG](../../results/e2-thesis/figures/relative-energy.png) · [SVG](../../results/e2-thesis/figures/relative-energy.svg)

### 2.2 主、次和宽带干扰

1 SFU/2 STA，混合业务 62 Mb/s。主信道、次信道和宽带分别测试弱 15%、强 15%、强 35%，并与相同业务输入的无干扰场景配对。弱干扰低于 AP 的 CCA 阈值，仍会造成解码失败。以主信道弱干扰为例，固定 LCM 的外部 CCA 忙碌率为 0，平均记录 53.3 次 SINR 解码失败。强干扰时，固定 HCM 的 CCA 忙碌比例约等于配置占空比。次信道强干扰 35% 时，固定 LCM 的 CCA 忙碌率和 SINR 失败均为零，成功吞吐仍只有 40.95 Mb/s，原因是该混合负载下 LCM 的服务能力先达到上限。

| 场景 | 固定阈值 / ECQ-SDPS 吞吐（Mb/s） | 固定阈值 / ECQ-SDPS 准时率（%） | ECQ-SDPS 相对固定阈值完整能耗差（95% CI） |
| --- | ---: | ---: | ---: |
| 无干扰 | 61.88 / 61.88 | 99.95 / 99.95 | +0.01% [−0.01%, +0.04%] |
| 主信道强 · 35% | 47.35 / 33.52 | 26.97 / 20.52 | +30.61% [+13.44%, +47.78%] |
| 次信道强 · 15% | 61.43 / 51.40 | 77.78 / 51.38 | +21.80% [+9.76%, +33.83%] |
| 次信道强 · 35% | 47.51 / 40.84 | 28.15 / 29.62 | +28.30% [+25.24%, +31.36%] |
| 宽带强 · 35% | 47.35 / 33.52 | 26.97 / 20.52 | +30.61% [+13.44%, +47.78%] |

主信道强 35% 时，窗内能耗下降 1.06%，每成功比特能耗增加 48.69%，窗内交付量也明显减少。次信道强 35% 时，ECQ-SDPS 的 HCM 驻留仅 0.43%，固定阈值为 98.96%。种子 11317 中，ECQ-SDPS 在 25 ms 以预计 QoS 改善升档，随后于 25.257 ms 降回 LCM，余下决策日志大量出现 `HOLD_UNPROVEN_QOS`，即模型没有证明切换能挽救当前截止时间。这与该档下容量不足、积压增长同时出现。在 35% 占空比下，主信道和宽带强干扰的表中结果相同。主信道已经在脉冲期间阻止两档接入，额外的次信道干扰在该配置下未形成可分辨的成功吞吐差异。  
[图 2 PNG](../../results/e2-thesis/figures/interference-performance.png) · [SVG](../../results/e2-thesis/figures/interference-performance.svg)

### 2.3 四 AC 的准时率与积压

1 SFU/4 STA，无干扰，45 和 100 Mb/s 混合下行。45 Mb/s 时 ECQ-SDPS 与固定阈值的全业务准时率均约 99.95%，固定 LCM 为 34.28%。100 Mb/s 时，三种可使用 HCM 的策略窗口吞吐约 53 Mb/s，总体准时率约 32%。A-MPDU 聚合要求相同 STA 和 TID，四类队列同时参与竞争。相较第 2.1 节，本组同时改变了终端数、包长和业务组成，单项因素的贡献需另设对照。

| 100 Mb/s，ECQ-SDPS | 准时率（%） | P95 / P99（ms） | Jitter（ms） | 窗末未完成（包/次仿真） |
| --- | ---: | ---: | ---: | ---: |
| VO | 99.73 | 0.410 / 0.532 | 0.099 | 0.5 |
| VI | 97.12 | 1.178 / 1.833 | 0.306 | 0.4 |
| BE | 0.68 | 342.902 / 345.273 | 0.614 | 1,803.6 |
| BK | 2.61 | 227.703 / 231.996 | 4.357 | 169.4 |

两档负载各 AC 均无显式丢弃、最终未交付为零。过载组的大量排空报文依然计入截止时间违约。  
[图 3 PNG](../../results/e2-thesis/figures/mixed-qos.png) · [SVG](../../results/e2-thesis/figures/mixed-qos.svg)

### 2.4 动态负载与切换

1 SFU/2 STA，无干扰。阶梯为 10→84→10→74→0 Mb/s，时段长度分别为 160/160/210/130/140 ms。周期突发以 80 ms 为周期，15 ms 空闲、18 ms 为 92 Mb/s、47 ms 为 14 Mb/s。随机突发独立抽取 9–57 ms 的 9 Mb/s 间隔和 8–25 ms 的 92 Mb/s 突发。阈值附近每 25 ms 在 27 和 35 Mb/s 间交替。

| 模式 | 固定阈值 / ECQ-SDPS 切换数 | ECQ-SDPS 准时率差（百分点，95% CI） | ECQ-SDPS 完整能耗差（95% CI） |
| --- | ---: | ---: | ---: |
| 阶梯 | 4.0 / 4.0 | +8.04 [4.67, 11.41] | +0.03% [−0.01%, +0.07%] |
| 周期突发 | 20.0 / 20.0 | +17.94 [15.99, 19.90] | +0.16% [+0.13%, +0.19%] |
| 随机突发 | 25.7 / 19.8 | +11.54 [8.57, 14.51] | +0.08% [−0.01%, +0.17%] |
| 阈值附近 | 37.3 / 4.0 | +0.25 [0.12, 0.37] | −0.08% [−0.12%, −0.04%] |

阈值附近的 HCM 驻留从 45.48% 提高到 93.87%。减少振荡主要来自模式的持续使用，绝对能耗收益只有约 0.08%。阶梯图展示了种子 11317 的实际状态轨迹，表格采用全部 10 个种子。  
[图 4 PNG](../../results/e2-thesis/figures/dynamic-modes.png) · [SVG](../../results/e2-thesis/figures/dynamic-modes.svg)

### 2.5 多 SFU 光无线融合

2 或 4 SFU，每台 2 STA、55 Mb/s，UL/DL 比特量为 30/70%。每种策略均执行无线竞争、共享 PON、REPORT、BWmap 和 GIANT 恢复版授权。

| 拓扑 | ECQ-SDPS 全网 / 每 AP 吞吐（Mb/s） | 固定阈值 / ECQ-SDPS 全网 P99（ms） | ECQ-SDPS 上行平均光侧时延（μs） | ECQ-SDPS 完整能耗差（95% CI） |
| --- | ---: | ---: | ---: | ---: |
| 2 SFU | 109.70 / 54.85 | 6.72 / 6.18 | 658.76 | +0.004% [−0.071%, +0.080%] |
| 4 SFU | 218.51 / 54.63 | 7.02 / 6.05 | 946.46 | +0.006% [−0.101%, +0.113%] |

4 SFU 的 ECQ-SDPS 全网平均功率为 34.65 W，每 AP 8.66 W。上行 P99 为 6.09 ms，下行为 6.04 ms。固定阈值为 6.45/7.26 ms。同一 Alloc-ID 的轮询周期随拓扑从 8 帧增加到 16 帧，上行等待随之拉长。上行 DATA 资源占用平均 1.31% 和 2.61%，该结果反映共享帧和反馈条件下的服务等待。ECQ-SDPS 在 4 SFU 每次仿真平均显式丢弃 9.1 包，固定阈值为 8.0 包。整体准时与尾时延改善，完整能耗差区间跨零。  
[图 5 PNG](../../results/e2-thesis/figures/joint-fttr.png) · [SVG](../../results/e2-thesis/figures/joint-fttr.svg)

### 2.6 消融、功率与观测敏感性

消融场景使用周期突发、主信道强干扰 15%，保持信道和业务相同，每次只关闭一个控制机制。下表的差值为完整策略减去相应关闭版本。

| 关闭的机制 | 完整能耗差（95% CI） | 全业务准时率差（百分点，95% CI） |
| --- | ---: | ---: |
| 信道感知 | +3.04% [2.13%, 3.96%] | −17.68 [−21.47, −13.90] |
| QoS | +2.92% [2.03%, 3.80%] | −14.49 [−18.42, −10.55] |
| 主动延迟 | 0 | 0 |
| 经济驻留 | −0.76% [−1.42%, −0.10%] | +5.43 [1.93, 8.94] |

关闭信道感知或 QoS 后，HCM 驻留从完整策略的 41.07% 提高到约 90%，在该场景获得更早的服务。完整策略对恶劣条件下的切换要求更强的预计改善，种子 11317 的大量 `HOLD_UNPROVEN_QOS` 为这一原因提供事件证据。此消融场景下完整策略的主动延迟开启数为零，因此只能报告该组无可分辨的延迟贡献。其他正式场景仍有实际执行的延迟，按种子记录在 CSV。

使用论文活动功率时，31 Mb/s 的完整策略相对固定阈值节能 2.18% [1.82%, 2.54%]，同任务下相对差异对功率参数敏感。次信道强干扰 15% 的观测对照中，反馈延迟由 100 增至 1,500 μs，ECQ-SDPS 准时率由 51.38% 降至 42.16%，配对差的区间跨零。关闭扫描后准时率降至 29.64%，配对减少 21.74 个百分点 [8.21, 35.26]。保留扫描并把附加功耗设为零时，吞吐、准时率和切换均与参考组相同，完整能耗下降约 0.52%。  
[图 6 PNG](../../results/e2-thesis/figures/mechanism-sensitivity.png) · [SVG](../../results/e2-thesis/figures/mechanism-sensitivity.svg)

## 3. 适用边界

- 论文结论应区分节能、QoS 改善和切换减少。当前数据支持 31 Mb/s 单 STA 场景的节能，以及部分突发场景的准时率提升。观察窗功率下降、单位比特能耗下降和完整交付节能分别计算。
- 真实硬件的敏感性需要另行校准。本实验使用人工 MCS7 SINR–PER 曲线，参考 MPDU 1,530 B 时折点为 −12/10/30 dB，对应 PER 1/0.5/0。干扰按设定的脉冲注入，HCM 固定使用 80 MHz。实验中的观察能力来自带延迟的简化 STA 反馈及辅助射频，辅助扫描有明确功耗。扫描计划对所有策略相同，仅当该 AP 在 LCM 时执行，固定策略也使用这一观测条件。
- AP 能耗模型覆盖等效 AP 设备级功耗。整网设备用电还需计入 STA、PON 和外围硬件。切换时长、切换功耗及 TX/RX 功率保留为研究参数。联合实验覆盖 2/4 SFU 和同一光侧参数，全部不达标场景继续保存在原始表中。

## 4. 数据和复现

| 实验组 | 分种子数据 | 汇总均值、标准差及区间 | 配对差值 |
| --- | --- | --- | --- |
| 负载 | [CSV](../../results/e2-thesis/01-load/seeds.csv) | [CSV](../../results/e2-thesis/01-load/summary.csv) | [CSV](../../results/e2-thesis/01-load/paired.csv) |
| 干扰 | [CSV](../../results/e2-thesis/02-interference/seeds.csv) | [CSV](../../results/e2-thesis/02-interference/summary.csv) | [CSV](../../results/e2-thesis/02-interference/paired.csv) |
| QoS | [CSV](../../results/e2-thesis/03-qos/seeds.csv) | [CSV](../../results/e2-thesis/03-qos/summary.csv) | [CSV](../../results/e2-thesis/03-qos/paired.csv) |
| 动态 | [CSV](../../results/e2-thesis/04-dynamics/seeds.csv) | [CSV](../../results/e2-thesis/04-dynamics/summary.csv) | [CSV](../../results/e2-thesis/04-dynamics/paired.csv) |
| 融合 | [CSV](../../results/e2-thesis/05-joint/seeds.csv) | [CSV](../../results/e2-thesis/05-joint/summary.csv) | [CSV](../../results/e2-thesis/05-joint/paired.csv) |
| 消融和敏感性 | [CSV](../../results/e2-thesis/06-sensitivity/seeds.csv) | [CSV](../../results/e2-thesis/06-sensitivity/summary.csv) | [CSV](../../results/e2-thesis/06-sensitivity/paired.csv) |

`sfu_id=ALL/direction=BOTH/ac=ALL` 这一行表示全网总体。范围更细的行用于每台 AP、上下行和各 AC。差值按“策略减对照”记录，`baseline_scenario` 指明同场景的策略比较或跨场景的敏感性参照。动态轨迹另见 [动态模式 CSV](../../results/e2-thesis/04-dynamics/mode-timeline.csv) 和 [消融模式 CSV](../../results/e2-thesis/06-sensitivity/mode-timeline.csv)。

复现环境为 Java 21.0.10、Python 3.14.2、Matplotlib 3.11.2 和微软雅黑。绘图可使用本地 Matplotlib，Linux 可指定 Noto Sans CJK。以下命令从仓库根目录执行。一次正式重放用 6 个并行 Java 进程约 9.4 分钟，完整日志约 6.6 GB，生成至 `target/e2-thesis/`。各组的 `seeds.csv` 索引实际配置、原始事件、配置哈希及逐包输入哈希。

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
mvn -q '-DskipTests' package
python -m unittest discover -s tools -p test_e2_thesis.py -v
python tools/e2_run.py --pilot --workers 6
python tools/e2_analyze.py --pilot --workers 4
python tools/e2_run.py --workers 6
python tools/e2_analyze.py --workers 6
python tools/plot_e2_thesis.py
```

单个对照可用 `python tools/e2_run.py --scenario load-050 --seed 11317` 重放。逐包事件核对使用 `tools/java/E2Trace.java`，它通过原 JAR 调用 `Simulator`，只抽取日志并核对授权容量。需要审阅切换原因时，可以用原有 `E0Trace` 读取同一配置。

```powershell
New-Item -ItemType Directory -Force target/e2-thesis/decision-evidence | Out-Null
javac --release 11 -encoding UTF-8 -cp target/fttr-chap4-0.1.0-SNAPSHOT.jar -d target/e2-thesis/decision-evidence tools/java/E0Trace.java
java -cp 'target/fttr-chap4-0.1.0-SNAPSHOT.jar;target/e2-thesis/decision-evidence' edu.suda.fttr.simulator.E0Trace target/e2-thesis/01-load/load-050/500000/11317/ecq-sdps-e1.properties > target/e2-thesis/decision-evidence/load-050.log
rg 'event=WIFI_AP_D4_DECISION|event=WIFI_AP_SWITCH_END' target/e2-thesis/decision-evidence/load-050.log
```
