# D3 QoS 筛选与自适应 HCM 请求延迟

**验收结论**　两种新策略已经接入 B4 切换执行器。ECQ-Aware 按截止时间筛选工作模式，ECQ-SDPS 还允许 AP 在时间预算内先用 LCM 处理业务。85 Mb/s 复现用例中，修正聚合估计后，目标 SFU 的切换从 7 次降到 1 次。无干扰混合业务中，主动延迟减少了切换和总能耗。主信道干扰下，它增加了切换和积压，这个结果保留在全部图表和数据中。

## 1. D2 往返切换的原因

重放 D2 的 40 ms 并行负载网格，检查其中 `sfu_id=6` 的 85 Mb/s 场景。该 SFU 服务 1 台 STA，承载 1500 B BE 泊松下行业务。种子为 101。10 ms 时，原模型用 LCM 的历史聚合深度 3 来估算当前模式，HCM 因缺少同模式历史按单包估算。原模型因此给出 LCM 54.805、HCM 51.702 Mb/s 的有效服务估计，此时选择降回 LCM。同一输入固定运行 HCM，8 个 5 ms 窗口的实际平均聚合深度为 1.75～2.71 包。

修正仅作用于模式控制的下行服务估计。已经排队的报文仍按本地 FIFO 中可见的 STA 和 AC 组合成批次。尚未到达的业务使用同一份到达率预测，按过去观测到的 STA 份额拆分流量。对每个候选模式，选择能够跟上该流量的最小合法聚合深度，并受同一 STA 连续出现的估计、A-MPDU 上限和 PPDU 长度限制。其他接入、信道、重传和能耗模型继续复用 D2。上行只使用本地历史。

| 85 Mb/s 用例项目 | 原 D2 | 修正后 |
| --- | ---: | ---: |
| 10 ms 的 HCM 预测聚合深度（包/PPDU） | 1 | 2 |
| 10 ms 的 HCM 有效服务估计（Mb/s） | 51.702 | 96.115 |
| 实际完成切换（次） | 7 | 1 |
| 窗口内交付（包） | 279 | 279 |
| 实际成功吞吐（Mb/s） | 83.700 | 83.700 |
| 实际端到端 P99（ms） | 2.704 | 1.499 |
| AP 总能耗（mJ） | 352.337 | 346.753 |

修正后的 HCM 在 10 ms 预测交付 71.4 Mb/s，这是 5 ms 内 357,000 bit 的预计业务量所支持的吞吐。`effective_service_mbps` 表示按预计业务形状、接入成本和切换前等待计算的有效服务估计。`saturated_service_mbps` 按持续积压时允许的聚合深度估计可用服务能力，仅用于辅助判断容量风险。该时刻的 HCM 积压容量估计为 267.782 Mb/s。三项指标可在决策 CSV 中分别查阅。切换下降来自容量估计和同业务量能耗比较，整个复现没有设置驻留时间。

## 2. 策略、事件和配置

- **ECQ-Aware** 对两种模式分别估算已知报文能否及时交付。每包时限从原始生成时间起算，经过的光侧和无线等待都已经消耗时限。只有一个模式通过筛选时选择它。两者都可行时，复用 D2 的同业务量能耗比较。没有可行模式时，依次比较 VO、VI、BE、BK 的预计违约数和迟到量，再比较容量缺口和信息不确定性，风险相同才比较能耗。
- **ECQ-SDPS** 使用同一筛选。已有的 B4 等待和正在执行的切换在两个候选中分别入账。每个已知报文的可用余量等于其剩余时限减去本地已知无线占用、在途切换、外部 CCA 等待估计、新切换时长、队列服务时长和安全裕量。以所有已知报文的最小余量为上限，向下取整到纳秒，并限制在配置允许的区间。只有 HCM 通过筛选、AP 仍在 LCM 且还未向 B4 提交升档时才可主动延迟。信息不足时主动延迟取零。
- **请求执行** 期间 LCM 照常发包。周期、紧急到达和重传回队可复查暂缓的请求。新截止时间可将同一请求提前，期限只能保持或缩短。无需升档时取消。到期用当前信息再决定是否提交 B4。VO/VI 及剩余时限短于决策周期的其他业务可触发当前时刻重判。同一 AP 同刻的到达与周期决策合并，先完成已发生的传输事件，再计算模式，随后才仲裁新的无线发送。历史服务样本仅在周期推进。
- **计时与实际能耗** `WIFI_AP_HCM_DEFER` 记录主动等待。`WIFI_AP_SWITCH_REQUEST/START/END` 记录 B4 申请、等待旧交换安全结束及实际切换。两者按 SFU 和切换 ID 关联。主动等待期间仍按实际 LCM 活动积分，只有实际 SWITCH 时段使用切换功率。C4 的逐包交付只用于实测对账。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `wifi.ap.control.policy` | `off` | 新增 `ecq-aware` 和 `ecq-sdps`，可用 `sfu.N.wifi.ap.control.policy` 覆盖单台 AP |
| `wifi.ap.control.service.model` | `auto` | ECQ 系列默认使用修正模型，原 E/EC 默认沿用 D2。`d2-original` 和 `load-aware` 可显式重放 |
| `wifi.ap.control.period.us` / `wifi.ap.prediction.window.us` | 5000 / 5000 μs | 模式控制的决策周期与预测窗口 |
| `wifi.ap.control.qos.margin.us` / `wifi.ap.control.defer.max.us` | 50 / 2000 μs | 安全裕量与主动延迟上限，延迟上限为 0 时立即提交 |
| `wifi.ap.qos.<AC>.deadline.us` | 10000 / 30000 / 100000 / 200000 μs | VO / VI / BE / BK 的默认端到端时限 |
| `wifi.ap.qos.<AC>.min-on-time` | 0.99 / 0.98 / 0.95 / 0.90 | 四类已知报文队列的预测准时条件 |

## 3. 事件验收

单包校验使用理想信道、1500 B 和零初始退避。BE 进入 HCM 后，预测服务时间为 `1.25 × 43 + 77.6 + 16 + 28 = 175.350 μs`，分别对应保守接入等待、DATA、SIFS 和 ACK。BE 时限 295 μs，扣除升档 40.125 μs 和安全裕量 50 μs 后，主动延迟为 **29.525 μs**。

| 用例 | Java 事件结果 |
| --- | --- |
| 紧急 VO / VI | 时限分别为 254.225 / 265.475 μs，均于 0 μs 提交 B4，跳过主动延迟 |
| 可延迟 BE / BK | 分别于 29.525 / 17.025 μs 提交，到 69.650 / 57.150 μs 完成升档 |
| 10 μs 到达新包 | 宽松 BK 与旧请求合并，仍于 29.525 μs 提交。较紧 BK 将同一请求提前至 21.675 μs。紧急 VO 在 10 μs 即提交 |
| 10 μs 周期复查 | 10 μs 打开请求，20 μs 合并，29.525 μs 提交同一笔升档 |
| 短突发自行消退 | LCM 在 43 μs 开始 DATA。54.525 μs 复查时撤销升档，报文在 235.800 μs 送达 |
| 旧交换占用 | VO 于 50 μs 提交，已有 BE 交换完成后 279.800 μs 开始切换，319.925 μs 完成。主动等待为 0，被动等待为 229.800 μs，实际切换 40.125 μs |
| 已耗光纤时间 | 经过 100 μs 光纤时延的 VO 到达 AP 后立即提交。时限仍从 MFU 生成时间起算 |
| CCA 忙且预期无误包 | 用零 PER 曲线隔离 CCA。主信道忙至 200 μs，100 μs 到达的 VO 触发决策，预测中计入 CCA 等待，实际 DATA 在信道空闲后发送 |

156 项 Maven 定向和兼容性测试全部通过，包含 23 项新增用例。Java 专项运行 61 个场景，包括 3 次容量诊断、10 个时序用例及 48 次性能对照。脚本从队列、逐包、切换和活动事件独立重算，核查请求唯一、同刻顺序、未完成量、QoS、功率积分与有限窗时间守恒。独立复跑抽取的 29 个场景，两份 CSV 对应记录一致。另重放 D2 的 25 个基线场景，已提交的 1356 条决策事件和 95 条性能记录与重放逐字段一致。

## 4. 性能对照与口径

四种策略共享同种子、业务输入和信道配置，脚本逐包比较输入指纹。种子为 101、202、303，正式观察窗统一为 120 ms。混合场景为 2 SFU、每台 2 STA、四 AC，共 946 包。每台 AP 的常驻业务叠加两个 BE 突发段，分别从约 18.5 和 67 ms 开始。持续 85 Mb/s 场景为 1 SFU、1 STA，四 AC 独立泊松速率依次为 8/15/50/12 Mb/s。正式场景的 VO/VI/BE/BK 时限为 650/1400/6000/12000 μs。全组使用 HE SU、MCS 7、GI 0.8 μs、A-MPDU 上限 8、BE AIFS 43 μs、原 B3/B4 功耗和原有接入机制。此组用 0 μs 固定光侧传输，已耗光纤时间另在时序用例验证。正式对照的 EC-Aware 显式使用修正容量模型，使对照只增加 QoS 或主动延迟这一项。

理想信道组关闭误包、干扰和扫描，可直接用无误包服务模型。主、次信道组分别干扰子信道 0 和 3，16～97 ms 有低于 CCA 门限的持续干扰，并以 4 ms 为周期产生 750 μs 的可侦听脉冲。使用人工 SINR–PER 曲线，STA 逻辑反馈延迟 100 μs、有效 6 ms，空口开销配置为 0。辅助扫描每次 400 μs、额外功率 0.5 W，由真实扫描结束事件累加。AP 总能耗包含 B3 活动、B4 切换和辅助扫描。反馈和扫描只从已到达的观测读入。

表内为三个种子的平均值。每格依次为**成功吞吐 Mb/s、AP 总能耗 mJ、完成切换次数、窗末未完成包数**。

| 场景 | Fixed-Threshold | EC-Aware | ECQ-Aware | ECQ-SDPS |
| --- | --- | --- | --- | --- |
| 无干扰突发 | 75.60 / 2040.75 / 6 / 0 | 75.60 / 2041.77 / 8 / 0 | 75.60 / 2045.22 / 32 / 0 | 75.60 / 2041.85 / 14.67 / 0 |
| 主信道干扰 | 67.76 / 2047.20 / 6 / 76.67 | 73.63 / 2054.53 / 6 / 20 | 67.77 / 2049.93 / 58 / 80.33 | 62.53 / 2045.81 / 98.67 / 131 |
| 次信道干扰 | 70.37 / 2051.11 / 6 / 53.33 | 73.22 / 2055.45 / 16 / 24.67 | 69.43 / 2065.30 / 43.33 / 63 | 69.18 / 2064.21 / 32.67 / 65 |
| 持续 85 Mb/s | 85.27 / 1060.12 / 1 / 2.33 | 85.27 / 1060.12 / 1 / 2.33 | 85.30 / 1060.01 / 1 / 2 | 85.30 / 1059.92 / 1 / 2 |

- **无干扰突发**　ECQ-SDPS 平均打开 29.67 笔延迟，其中取消 22.33 笔。相对 ECQ-Aware，交付量同为 946 包，切换从 32 降到 14.67，少耗 3.37 mJ。平均主动延迟累计 4.184 ms，被动等待累计 0.668 ms，均为两台 AP 合计。固定阈值策略在该组消耗 2040.75 mJ，BE 的准时率为 63.42%，ECQ-SDPS 为 77.59%。
- **主信道干扰**　相对 ECQ-Aware，ECQ-SDPS 切换从 58 增至 98.67 次，BE P99 从 37.95 增至 51.84 ms，单位成功比特能耗从 252.14 增至 272.77 nJ/bit。种子 101 的 ECQ-SDPS 有 391 个决策，其中 325 个由紧急队列触发，315 个进入两档都不满足时的风险比较。反复陷入违约风险是频繁重判的直接证据。该组较低的总能耗伴随较少交付，不能作为节能收益。
- **次信道干扰**　ECQ-SDPS 的 VO 准时率为 97.22%，EC-Aware 为 89.17%。同时，ECQ-SDPS 的窗末未完包数为 65，EC-Aware 为 24.67。四种策略在持续 85 Mb/s 组都只切换一次。该组 ECQ-SDPS 的 VO P99 为 0.715 ms，固定阈值为 1.185 ms。

分 AC 的平均时延、P95、P99、抖动、准时率、违约率、丢弃和积压见性能 CSV。P95/P99 仅对已交付包按最近秩计算。抖动取每个 AP、同 AC 的相邻交付端到端时延绝对差的平均。准时和违约率的分母为已交付、显式丢弃和已到期未完成，未到期积压单列。`predicted_feasible` 和 `predicted_risk` 是决策次数，实际违约是逐包统计。图中能耗差按同一种子减去固定阈值结果，误差线为三种子的最小到最大值。

## 5. 代码与模型边界

| 模块 | 代码改动 |
| --- | --- |
| 筛选与预测 | `ApDeadlineModePolicy` 计算期限和候选风险，`ObservedServicePrediction` 与 `ApModePredictor` 提供两档服务估计 |
| 配置与观测 | `ApModeController` 和 `ConfigLoader` 接收策略配置，`ApTrafficObserver` 提供在线队列与历史快照 |
| 执行与记录 | `SimulationContext`、`Simulator`、`EventType` 和 `EventQueue` 接入周期、紧急和到期事件。`Sfu` 与 `ApModeSwitch` 提供 B4 申请状态，`TraceLogger` 输出决策与时间字段 |

PON/DBA、PHY 帧时长、EDCA 和 A-MPDU 协议规则沿用原实现。经济驻留和滞回属于 D4。

截止时间筛选只覆盖到达 AP 的本地下行队列，光侧尚未到达的报文及 STA 私有队列不可见。队列服务时长使用含预计重传和 CCA 影响的保守串行近似。已承诺的本地交换有明确等待，外部干扰的剩余持续时间只能估计。聚合容量受实际到达顺序、退避和信道变化影响，预测准时和允许延迟不能当作硬时限保证。人工 SINR–PER 曲线用于仿真验证，未做硬件校准。

## 6. 复现与产物

在项目根目录执行。脚本将实际 `.properties` 和代表性 Java 原始日志保存在 `target/d3-qos-deferral`。绘图使用 Python 3.10+、Matplotlib 3.7+ 和微软雅黑或 Noto Sans CJK。要核查任一场景的完整事件，可以用生成的配置单独运行 JAR。

```powershell
mvn '-Dtest=ApDeadlineModePolicyTest,ApQosModeSimulationTest,ApServicePredictionTest,ApEnergyModePolicyTest,ApEnergyModeSimulationTest,ApModeControlSimulationTest,ApModeSwitchSimulationTest,ApAnalysisSimulationTest,ApTrafficObserverTest,AmpduSimulationTest,ChannelManagementTest,ChannelObservationSimulationTest,ExternalCcaSimulationTest,SubchannelPerSimulationTest' package
python -B tools/verify_d3_qos_deferral.py
python -B tools/plot_d3_qos_deferral.py
python -B tools/verify_d3_qos_deferral.py --smoke --work target/d3-replay/configs --output target/d3-replay/results
python -B tools/verify_d2_energy_decision.py --smoke --work target/d3-d2-replay/configs --output target/d3-d2-replay/results
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar target/d3-qos-deferral/passive-101-ecq-sdps-load-aware.properties
```

- 原 D2、修正模型及固定 HCM 的诊断数据，各策略的全部请求与候选 QoS 选择，以及正式场景种子 101 的详细预测。[决策与请求 CSV](../../results/d3-qos-deferral/d3-decision-requests.csv)
- 各种子、各 AP 和全网分 AC 的实测结果。[性能 CSV](../../results/d3-qos-deferral/d3-performance.csv)
- 主动延迟与实际切换时间线。[PNG](../../results/d3-qos-deferral/d3-deferral-timeline.png) · [SVG](../../results/d3-qos-deferral/d3-deferral-timeline.svg)
- 各 AC 的实际准时率与 P99。[PNG](../../results/d3-qos-deferral/d3-qos-by-ac.png) · [SVG](../../results/d3-qos-deferral/d3-qos-by-ac.svg)
- 切换和能耗对照。[PNG](../../results/d3-qos-deferral/d3-switch-energy.png) · [SVG](../../results/d3-qos-deferral/d3-switch-energy.svg)
