# FTTR 仿真 C 阶段联合验收

## 实现与假设

- C2b-2 使用 PPDU 启动时的 C2a 真值。LCM 使用主 20 MHz，HCM 在验收场景使用固定 80 MHz。终端能力较窄时按现有 PHY 的实际带宽取用连续子信道。频域分块按配置权重分配 MPDU 长度，合并分块成功率。参考曲线的误包率为 pᵢ、归一化块权重为 wᵢ、当前与参考 MPDU 长度之比为 r 时，PER = 1 − exp(r Σ wᵢ log(1 − pᵢ))。平坦输入走 C2b-1 的精确计算和同一抽样键。
- 外部干扰时间线分别配置 AP 和 STA 接收功率。STA 处使用 SINR_eff = SINR_base − 10 log₁₀(1 + Σ I_sta / N_base)，其中基准噪声功率是该近似使用的接收端基准分母。已经列入外部时间线的干扰源只计这一次。AP 从本地接收功率判断 CCA。LCM 主信道或 HCM 任一占用子信道检测忙碌时冻结接入，空闲后按现有 AIFS 和退避机制恢复。已发起的交换按原时序结束。
- C2c 提供本地 CCA、确认及重传的被动观测，支持 STA 定时测量的 CQI/SINR 报告及辅助射频接收扫描。报告可延迟、丢失或在到达前已过期。正空口时长的报告使用原共享信道，AP 的 RX 已进入 B2/B3。辅助扫描测量 AP 本地的能量忙份额，独立射频的监测时长和额外能量单独记录。总能量仅将辅助射频额外能量加到 B3 总能量一次。简化测量和报告的射频开销有待按实际波束训练或测量反馈流程校准。
- C3 从当前 C1 窗口和已到达的观测估计短时服务、成功吞吐、队列与能耗。候选模式使用其配置的 PHY 和功率剖面。实测窗口仅用于复核当时正在运行的模式。另一个模式的预测作为候选值保存，切换成本单列。C4 根据 FTTR 入口与端点事件统计 QoS，将预测可行性和已经发生的业务结果分别记录。
- C3 用配置的 AIFS 和以 CWmin 除以 2 得到的平均退避估计接入时间。最多重传 R 次时，单 MPDU 预计成功概率为 1 − p^(R+1)，预计尝试数为从 0 到 R 的 p^k 之和。新鲜 STA 反馈优先，缺失时使用目标模式已有的历史估计，仍缺数据则标明低可信度。候选宽带忙时可结合已有辅助扫描近似，缺失时忙时字段为 −1，结果采用空闲预算。辅助扫描可跨现有手动切换结束，扫描起点仍须处于 LCM。C3 的上行负载输入是 AP 已成功接收的流量代理。
- 验收用人工 SINR–PER 点为 MCS 7 的 −12 dB → 1、10 dB → 0.5、30 dB → 0。分块独立性、长度换算与 PPDU 起点冻结均为仿真假设。ACK 和 Block ACK 延续 B6 的响应模型。本文数值表达模型内部行为，真实设备曲线及物理干扰性能有待链路级或硬件测量校准。

## 验收结果

| 项目 | 实测与检查 |
| --- | --- |
| 开关兼容与平坦信道 | 关闭新增功能时 C2b-1 事件及统计逐行一致。分块模式在平坦输入下与旧模式逐项匹配 PER、抽样值和解码。缺少正在使用的子信道真值会明确报错 |
| 频域、CCA 与重传 | 96 个 Java 场景的输入、有效 SINR、逐 MPDU PER、抽样、确认位图与超时均独立对账。CCA 阈值和实际占用掩码逐边界复核，实际 DATA 从空闲状态启动。定向测试中次信道忙区间为 43–90 μs 时，LCM 在 43 μs 启动，HCM 在空闲并重等 AIFS 后于 133 μs 启动 |
| 观测时效与开销 | STA 报告按测量真值取样并核对到达后才可见。延迟组实际接收 235 份，过期组 237 份到达后过期，丢失组记录 258 份配置丢失。同期竞争产生的忙碌丢弃单列。LCM 扫描试验的 2 AP 合计额外耗能 1200 μJ，HCM 试验按模式跳过。所有实际反馈空口均进入 B2 RX，扫描和反馈开销可从轨迹独立对账 |
| 报文与 QoS | 58,792 个报文实例完成入队、在途、确认和最终丢弃独立对账，B2 全程时长守恒。60 ms 截止的 QoS 与生成／交付／丢弃记录重算一致。UL 可以统计 MFU 实际交付及未完成，AP 侧无法获取 STA 私有待发队列 |
| 解析预测 | 单包确定性 HCM 用例中，1 ms 内 1500 B 的成功吞吐为 12 Mb/s，TX 77.6 μs，RX 28 μs，空闲 894.4 μs。含 AIFS 的解析服务时间为 164.6 μs。该时间、分状态能耗与 Java 预测一致 |
| 矩阵预测 | 评估 2112 个本模式完整窗口。保留 3630 个候选模式预测和 1518 个本模式超出截止点的窗口，后两类单独标记。未删除估计误差偏大的场景，信道未知的 17 个匹配窗口也进入误差统计 |
| 定向回归与重放 | Maven 83 项通过，0 失败、0 错误、0 跳过。仅运行本阶段及必要兼容性测试。独立重跑全部 96 个场景，三份 CSV 和 98 份配置逐字节一致 |

### 信道、QoS 与功率

中负载表中的吞吐是两个 SFU 的下行合计，PER 是窗口内有 SINR 估计的 MPDU 尝试的平均值。表项为三个种子的均值，配置负载是泊松过程的期望值，单次有限窗口的实际输入可在 CSV 查询。

| 干扰 | LCM／HCM 平均 PER | LCM／HCM 下行成功吞吐（Mb/s） | 插值交点（Mb/s） |
| --- | ---: | ---: | ---: |
| 无干扰 | 15.0%／15.0% | 66.7／101.9 | 34.24 |
| 主信道 | 43.3%／23.2% | 40.7／96.5 | 共同实测吞吐范围内无交点 |
| 次信道 | 15.0%／23.2% | 66.7／96.5 | 37.70 |
| 宽带 | 43.3%／43.3% | 40.7／73.1 | 23.65 |

三个交点均未通过共同区间的低积压筛选，不能据此提出 31 Mb/s 或其他固定切换阈值。高负载无干扰时，LCM／HCM 的 P99 分别为 51.73／35.89 ms，截止时间违约率为 66.2%／41.6%，平均未完成量为 768／473.3 包。宽带干扰下，P99 为 50.10／46.17 ms，但未完成量升至 875.7／699.3 包，须结合未完成量解释已交付样本的时延。

### C3 预测偏差

误差为预测减实测。成功吞吐对照使用无线本地成功交付的 DL 和 UL 字节。服务速率图使用起点已有积压的窗口，横轴为该窗口实际成功交付速率，实际饱和容量无法从短窗直接测得。能耗对照使用相同 AP 下一窗口的 B2/B3 状态能耗，辅助射频扫描单独对账。MAPE 的分母是相应实测值，完整匹配窗口中的实测值均非零。

| 模式 | 成功吞吐偏差／MAE（Mb/s） | 成功吞吐 MAPE | 能耗偏差／MAE（μJ） | 能耗 MAPE |
| --- | ---: | ---: | ---: | ---: |
| LCM | +9.82／11.10 | 43.0% | +1155.5／1289.4 | 3.05% |
| HCM | +36.63／38.01 | 83.9% | +547.3／899.6 | 2.10% |

三个种子分别计算时，LCM 成功吞吐 MAPE 为 40.7%–46.8%，HCM 为 79.0%–90.3%。负载波动、多 AC/多终端竞争与目的终端一致性都会限制实际聚合。当前预测可作为 D 阶段接口和保守性验证的输入，尤其 HCM 的吞吐高估在决策实验前仍需独立数据校验。

## 配置与复现

| 配置 | 本次验收设置 |
| --- | --- |
| 拓扑与链路 | 2 台 SFU，每台 2 台 STA。共享 10 Gb/s 下行及 2.5 Gb/s 上行，传播时延 10 μs |
| 业务与窗口 | 独立泊松流，生成与比较窗口 60 ms，1500 B。每 SFU 下行 12、50、110 Mb/s 三档，四 AC 混合。每 STA 上行 2 Mb/s。种子 101、202、303 |
| EDCA 与 B6 | BE AIFS 43 μs，A‑MPDU 最多 8 MPDU，最多 2 次重传，ACK 超时 60 μs |
| 频域误包与外部干扰 | `wifi.phy.per.enabled=true`，`wifi.phy.per.frequency.model=subchannel-blocks`，四块等权。基准 −95 dBm、24 dB。CCA 阈值 −82 dBm。无、主、次和宽带四种配置 |
| 观测与预测 | `wifi.channel.observation.enabled=true`，`wifi.channel.passive.enabled=true`。STA 报告 8 μs，另对照延迟、过期、丢失及扫描。C1 和 C3 的窗口均为 5 ms |
| B4 成本 | 候选 LCM→HCM 配置 40 μs、400 μJ，HCM→LCM 配置 25 μs、200 μJ。Always 试验没有发出切换请求 |
| QoS | VO／VI／BE／BK 截止时间为 3／8／20／35 ms。时延 P95/P99 采用最近秩，仅统计已交付。Jitter 为同类同方向同 AP 的连续实际交付时延差绝对值的平均。违约分母为已交付、已丢弃及已到期仍未完成。仍未到期的积压单列 |

相同负载和种子的 Always-LCM/HCM 与干扰控制组使用相同的输入报文。报文生成和 AP 能耗统计截至 60 ms。协议随后自然排空，以核对端点守恒。综合表的单位成功比特能耗以全体 AP 的能耗除以下行成功交付比特，包含混合业务中实际发生的上行活动。功率交点使用两个模式观测吞吐公共范围内的相邻实测均值插值。低积压标记要求形成插值的观测点在三个种子下均未完成率不高于 5%。三档样本只能约束已测试区间。

```powershell
mvn -o '-Dtest=DownlinkDecoderFrequencyTest,SubchannelPerSimulationTest,ExternalInterferenceTest,ExternalCcaSimulationTest,ChannelManagementTest,SinrPerModelTest,SinrPerSimulationTest,ChannelObservationSimulationTest,AmpduSimulationTest,ApAnalysisTest,ApAnalysisSimulationTest' package
python -B tools/verify_c2b2a_subchannel_per.py --output target/c-phase-flat-compat
python -B tools/verify_c_phase.py
python -m pip install --target target/c-phase-plot-deps matplotlib
$env:PYTHONPATH = "target/c-phase-plot-deps"
python -B tools/plot_c_phase.py
```

- [信道、反馈与扫描事件](../../results/c-phase/channel-events.csv)
- [预测与实际窗口对照](../../results/c-phase/prediction-vs-actual.csv)
- [联合 QoS 与能耗结果](../../results/c-phase/qos-energy.csv)
- [实际配置](../../results/c-phase/configs)
- 子信道误包和吞吐量 [PNG](../../results/c-phase/figures/01-subchannel-performance.png) · [SVG](../../results/c-phase/figures/01-subchannel-performance.svg)
- 功率与交叉 [PNG](../../results/c-phase/figures/02-power-crossover.png) · [SVG](../../results/c-phase/figures/02-power-crossover.svg)
- 时延、违约与积压 [PNG](../../results/c-phase/figures/03-qos-and-backlog.png) · [SVG](../../results/c-phase/figures/03-qos-and-backlog.svg)
- 预测与实际 [PNG](../../results/c-phase/figures/04-prediction-validation.png) · [SVG](../../results/c-phase/figures/04-prediction-validation.svg)
