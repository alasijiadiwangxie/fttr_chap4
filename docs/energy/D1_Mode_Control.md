# D1 AP 模式控制与固定阈值基线

D1 提供固定 LCM、固定 HCM 和固定负载阈值三种策略。在 120 ms 阶梯实验中，三种策略均在观察窗内交付了全部 235 个报文。固定阈值策略每次完成两次切换，平均能耗为 963.78 mJ，与固定 LCM 的 964.21 mJ 接近。固定 HCM 的 P99 时延最低。

## 模式控制

每台 SFU 使用自己的 C1 下行入口账本。决策时使用相邻两次账本读数之差计算窗口到达率。每个新到达 AP 的下行报文计入一次，重传和上行业务不计入。首个窗口从 0 开始，包含时刻 0 的到达。后续窗口左开右闭，同刻的下行入队先于决策。

窗口到达率以 `到达字节数 × 8000 / 窗口纳秒数` 换算为 Mb/s。首窗实测值初始化 EWMA，后续用当前窗口乘以 `α`、上一估计乘以 `1−α`，再相加。估计严格大于阈值时选择 HCM，等于或小于阈值时选择 LCM。每台 SFU 独立更新 EWMA 和请求状态。

B4 负责安全执行。现有无线交换完成或 ACK 超时结算后，才可开始待执行的切换。多 AC 的退避计时先于同刻决策结算，发射仲裁排在决策之后。相同目标保留原待执行请求。新目标可取消尚未开始的旧请求，已经开始的切换完整执行。切换时 B4 独占该 AP 的无线资源，结束后先更新 PHY 能力，再恢复 EDCA。固定策略在启动时选定能力，没有切换开销。

| 配置 | 默认值 | 含义 |
|---|---|---|
| `wifi.ap.control.policy` | `off` | 全局策略。可选 `off`、`always-lcm`、`always-hcm`、`fixed-threshold` |
| `sfu.N.wifi.ap.control.policy` | 继承全局 | 覆盖单台 SFU 的策略 |
| `wifi.ap.control.period.us` | `5000` | 正整数纳秒精度的决策周期，配置单位为 μs |
| `wifi.ap.control.ewma.alpha` | `0.25` | 新窗口权重，取值大于 0 且不超过 1 |
| `wifi.ap.control.threshold.mbps` | `31` | 负载阈值，单位 Mb/s，允许非负有限值 |
| `sfu.N.wifi.ap.mode` | 阈值策略默认 LCM | 阈值策略的起始能力。固定策略直接选定启动能力 |
| `wifi.ap.activity.window.us` | 自然结束 | 固定窗只在终点前产生新决策。已发起的交换和切换可继续结算 |

启用阈值策略时会启用 C1 入口统计。C1 采样仍使用自己的周期。阈值策略使用 HE SU、B2/B3 状态与能耗账本及已配置双向参数的 B4。同一台 AP 采用一个请求来源，配置校验会拒绝同时启用 D1 策略和 B4 手动请求。自然窗口内，决策时钟随已有仿真事件推进，空载时钟不会单独延长运行。

## 实验与结果

主实验采用 1 个 AP、1 个 STA、1500 B BE 纯下行，光侧固定 0 μs，理想无误包无线信道。三段各 40 ms，按 1200、240、1200 μs 的间隔生成报文，实际输入分别为 10.20、50.10、10.20 Mb/s。HE SU 复用 LCM 20 MHz/1SS、HCM 80 MHz/2SS、MCS 7 与 GI 0.8 μs。A-MPDU 上限为 8，BE AIFS 为 43 μs，BE 的 CWmin 为 15，ACK 为 24 Mb/s。三个策略分别用 EDCA 种子 101、202、303，逐包校验确认输入一致。

功耗沿用 `RESEARCH_INITIAL_V1`。LCM 的 IDLE/TX/RX 功率是 7.38/9.20/8.20 W，HCM 是 7.62/10.50/8.70 W。B4 的升档为 40.125 μs × 11.50 W，降档为 25.250 μs × 8.75 W。完整往返切换消耗 682.375 μJ。

| 策略 | 窗内吞吐量（Mb/s） | AP 能耗（mJ） | 平均功率（W） | P95 / P99（ms） | 峰值待发（包） | 切换次数 | HCM 驻留 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Always-LCM | 23.50 | 964.21 | 8.0351 | 0.965 / 1.000 | 3 | 0 | 0% |
| Always-HCM | 23.50 | 972.90 | 8.1075 | 0.297 / 0.347 | 2 | 0 | 100% |
| Fixed-Threshold | 23.50 | 963.78 | 8.0315 | 0.773 / 0.985 | 3 | 2 | 29.03% |

表中能耗和时延为三种子的单次指标平均值。阈值策略的能耗范围为 963.61～964.08 mJ。三种策略的窗末积压、显式丢包和 BE 交付时限违约均为 0，BE 时限为 10 ms。切换时间与 LCM/HCM 驻留分别计量。这个场景下，阈值策略相较固定 LCM 的平均能耗差约 0.04%，P95 从 0.965 ms 降至 0.773 ms。固定 HCM 把 P99 降至约 0.347 ms，消耗了更多能量。

三次升档请求均在 55 ms，EWMA 为 33.381 Mb/s。实际等待分别为 210、2.4、174.8 μs。三次降档请求均在 90 ms，EWMA 为 30.363 Mb/s，并在同刻开始执行。时间线展示种子 101 的实际切换。曲线的最后一个入口窗口仍有观测，120 ms 截止时不产生新的决策。

| 用例 | 实测结果 |
|---|---|
| 阈值边界 | 31.00 / 31.02 / 30.98 / 31.02 Mb/s 分别选择 LCM / HCM / LCM / HCM，实际切换 3 次 |
| 聚合部分确认 | 100 μs 请求升档，等到 569.400 μs 收完 Block ACK 才切换。位图为 `0x5`，只有报文 2 重传。3 包最终唯一交付 |
| 正在重传 | 报文 2 在 238.800 μs 重传，300 μs 收到降档请求。切换始于重传的 ACK 完成时刻 360.400 μs |
| ACK 超时 | 140 μs 请求降档，180.600 μs 在超时和重传状态结算后开始切换。降档结束后，唯一报文成功重传 |
| 陈旧请求 | 聚合在途时取消待执行升档，实际切换 0 次。另一场景中取消尚未开始的反向请求，已开始的升档在 340 μs 完成 |
| 双 SFU | 约 10 Mb/s 的 AP 保持 LCM。约 50 Mb/s 的 AP 独立升档，窗末余 1 包随后交付 |
| 人工干扰与多 AC | 1 AP、2 STA 同时包含 VO/VI/BE/BK。窗口内 126 包入、123 包交付、2 包到达重试上限、1 包待交付，随后正常交付。窗内有 27 次重传，已判定报文的违约率为 2/125 |
| 静态兼容 | 六次 Always 运行与对应旧静态配置逐行一致，D1 启动记录单独输出 |

截止时间指标按各 AC 统计。VO/VI/BE/BK 的试验时限分别为 3/8/10/35 ms。到期尚未交付、超时交付及显式丢弃都算违约。观察窗末仍未到期的未完成报文单列。吞吐量按 `[0, 观察终点]` 内实际交付的有效报文位数计算。脚本同时对账窗末的排队、在途和已交付待确认，并逐包核对自然排空后的最终交付与丢弃。

## 定向验收与复现

Maven 执行 D1 和相关兼容性测试共 176 项，其中 D1 新增 23 项，通过 176 项。专项脚本逐条重算 C1 到达、EWMA 和目标模式，核对 ACK 或 Block ACK 结算、模式请求唯一性、B2 活动时间、B3 能耗、C4 时限统计以及报文守恒。两次独立重跑的 17 个 Java 场景生成了逐字节一致的两份 CSV。

`ApModeController` 保存单 AP 的阈值策略状态。`ApTrafficObserver` 提供 C1 累计入口，`ApModeSwitch` 和 `Sfu` 负责 B4 请求及安全执行。`ConfigLoader`、`SimulationConfig` 接入配置，`Simulator`、`SimulationContext`、`EventQueue` 和 `EventType` 接入决策时钟，`TraceLogger` 输出可关联的决策与切换记录。`ApModeControlSimulationTest` 覆盖阈值、同刻处理、部分确认、重传、ACK 超时和兼容性场景。

在项目根目录运行以下命令。绘图使用 Python 3.10 或更新版本、Matplotlib 和微软雅黑或 Noto Sans CJK。运行脚本会在 `target/d1-mode-control` 写出实际 `.properties` 文件及各场景的 Java 原始日志。

```powershell
mvn '-Dtest=ApModeControlSimulationTest,ApModeSwitchSimulationTest,StaticApCapabilityTest,ApActivitySimulationTest,ApEnergySimulationTest,AmpduSimulationTest,ApTrafficObserverTest,ApTrafficObservationSimulationTest,EdcaRetryTest,EdcaMultiAcTest,SubchannelPerSimulationTest,ExternalCcaSimulationTest,ChannelManagementTest,ApAnalysisSimulationTest' package
python -B tools/verify_d1_mode_control.py
python -B tools/plot_d1_mode_control.py
```

- 模式决策、请求、取消、开始、结束及关键无线事件。[事件 CSV](../../results/d1-mode-control/d1-mode-events.csv)
- 每个种子、AP 和业务类别的窗内指标。单 AP 的能耗和 HCM 驻留在 `ac=ALL` 汇总行。[性能 CSV](../../results/d1-mode-control/d1-performance.csv)
- 业务入口、EWMA 与真实模式时间线。[PNG](../../results/d1-mode-control/d1-load-mode-timeline.png) · [SVG](../../results/d1-mode-control/d1-load-mode-timeline.svg)
- 三种策略的能耗、尾时延、违约率和切换次数。[PNG](../../results/d1-mode-control/d1-policy-comparison.png) · [SVG](../../results/d1-mode-control/d1-policy-comparison.svg)

D1 的阈值只反映 AP 已收到的下行业务。靠近阈值的流量可以频繁切换，定向用例已展示这一点。干扰用例使用人工 SINR–PER 曲线，功耗仍是研究参数。本次结果可作为后续策略对比的可复现基线。
