# D5 完整 ECQ-SDPS 集成与最终验收

## 1. 原 D4 的问题和局部修正

`wifi.ap.control.policy=ecq-sdps-complete` 启用完整策略，`ecq-sdps-d4` 保留 D4。两者复用 B4 执行器、C1/C2 观测和 C3 修正版预测。已过期报文照常排队和传输。在线策略只能取得已经到达 AP 的报文、有效反馈和过去的活动。光侧在途报文、STA 的私有队列及未来的信道真值不进入选择。

D4 在两档均有风险时，只接受「预计准时交付至少增加半包」的换档。次信道回归场景中，种子 101 的 SFU 1 在 **31.581200 ms** 预测两档对当时所有待观察报文的准时概率均为 0。LCM 在同一 5 ms 窗内预计交付 181408 bit，HCM 约 137016 bit。D4 即便发现降档也有预计能耗收益，仍停在 HCM。D5 增加一个 `SAFE_ECONOMIC_EXIT` 分支。只有 LCM 对每个未到期报文的预计准时概率均不下降、纳入 B4 后同窗预计成功量不下降、未来容量缺口不增加，且信道和切换时序信息充分，才进入原有回本、滞回和驻留检查。该分支专用于 HCM→LCM，仍按 B4 的安全时刻执行。原最小驻留 500 μs 和回切额外裕量 25 μJ 保持原值。

D4 与 D3 的同输入逐包重放中，种子 101 有 11 个 VO/VI 在 D3 准时、到 D4 迟到。其中 10 个没有信道解码失败。例子是 VO 282，30.000 ms 生成。D3 在 30.309600 ms 交付。D4 在 30.200–30.950 ms 受次信道忙碌阻塞，到 30.993000 ms 才开始发送，已超过 650 μs 时限。四子信道 DATA 首次解码成功，31.057000 ms 交付。VO 467 则有一次实际解码失败和一次 LCM 重传成功。两种原因可从 CCA、PPDU 及逐包时序分别核对。

完整策略的默认四项开关都是 `true`。消融通过 `wifi.ap.control.complete.<feature>.enabled=false` 每次关闭一个功能。

| `feature` | 单独关闭后 |
| --- | --- |
| `channel` | 服务预测使用理想接入/解码假设。实际 EDCA、PER、CCA 和扫描开销照常发生 |
| `qos` | 选择只走经济比较，取消截止时间筛选和紧急触发。主动延迟仍要求能在 LCM 完成整批业务且成本更低，使用配置上限 |
| `deferral` | 缩到零主动等待，仍保留 B4 实际安全等待 |
| `residency` | 取消最小驻留、回本窗口与反向收益裕量。B4 实际耗费和禁止同刻反向切换的规则仍生效 |

生产改动位于 `ApResidencyModePolicy`、`ApModeController`、`ConfigLoader`、`SimulationContext` 和 `TraceLogger`。消融和原 D4 均显式用 `wifi.ap.control.service.model=load-aware`。静态策略直接固定能力，固定阈值仍只用实际入队业务的 EWMA。实验复用原 PHY、EDCA、A-MPDU 和冻结的 PON/DBA。

## 2. 配置和测量口径

- **输入。** 正式种子为 1201、2411。每个阶段对方向、设备和业务类别使用独立泊松输入，按包写入 Java 的手动入口。同场景各策略的生成时刻、长度、业务类别、方向和目标均逐包一致。四类业务按配置输入比 7%／12%／74%／7%，报文为 500／1000／1500／1200 B，端到端时限为 650／1400／6000／12000 μs。常值 12／35／85／270 Mb/s 各 250 ms。多 AP 无干扰、主信道、次信道和宽带为同一 200 ms 输入。其余包括周期突发 300 ms、阶梯与信道恢复 1 s、相同首周期的 2 s 扩展、250 ms 光无线融合，以及相同 35 Mb/s 输入的论文功率敏感性。
- **固定阈值预选。** 先在种子 407、509 的独立 300 ms 阶梯上测试 20、31、45 Mb/s。按最终违约总数、观察窗每成功比特能耗、阈值大小依次择小。违约数为 9／9／29，单位能耗为 268.49／268.12／268.53 nJ/bit，冻结阈值 **31 Mb/s**。正式测试使用同一阈值。
- **信道与扫描。** 理想无干扰组无误包、无扫描。干扰组用 C2 的人工 SINR–PER 曲线、100 μs 反馈延迟、6 ms 有效期及 400 μs 的辅助扫描，每次扫描额外功率 0.5 W。另用相同次信道业务与扫描时间、0 W 额外扫描功率作理想免成本观测对照。两者都遵守信息到达时间。实际扫描只在能耗账上加一次，预测值来自过去扫描机会。
- **功率与协议。** B3 研究参数按 IDLE/TX/RX 为 LCM 7.38/9.20/8.20 W、HCM 7.62/10.50/8.70 W。论文对照采用 12 V 乘原测量电流，得到 LCM 6.96/12.96/7.92 W、HCM 7.92/15.00/12.00 W。两组使用相同 HE SU MCS 7、GI 0.8 μs、A-MPDU 上限 8、BE AIFS 43 μs 及 B4 切换参数。论文值作为敏感性参数，仿真 PHY 和硬件条件沿用项目设置。[Sánchez-Vital 等，正式发表版本第 IV 节](https://upcommons.upc.edu/server/api/core/bitstreams/ed00c19a-d7e7-404b-8b75-b586d828c6c5/content)
- **两个统计窗口。** `goodput_mbps`、`backlog_window`、平均功率和 `nj_per_success_bit` 使用同一个观察窗。时限结算覆盖窗内生成的**全部**报文，观察窗后正常送达仍采用真实端到端时延判断，丢弃和已超期但仿真结束仍未交付的包计违约。最终时延分位数按最近秩，Jitter 为同 AP、方向、AC 相邻交付时延的平均绝对差。排空时间和 AP 能耗独立记录在 `drain_ns`、`drain_uj`。最后一个交付或相关无线交换完成时关闭排空账。光侧在仿真结束时仍存在队首阻塞的试验结算到最后一个 PON 事件，并单列 `unsettled_final`。

## 3. 最终结果

以下为两个测试种子的平均值，能耗差用同种子逐对计算。P95、P99、Jitter、丢弃、逾期、观察窗队列余额和最终未交付均可按 AC 查阅。

| 场景 | 固定阈值／完整策略成功吞吐（Mb/s） | 最终准时率（%） | 完成切换（次） | 完整策略观察窗能耗差 |
| --- | ---: | ---: | ---: | ---: |
| 低载 12 Mb/s | 11.89／11.89 | 100.00／100.00 | 0／0 | 0.00% |
| 中载 35 Mb/s，B3 | 33.15／33.22 | 99.88／99.94 | 8／1 | **−0.20%** |
| 中载 35 Mb/s，论文功率 | 33.15／33.22 | 99.88／100.00 | 8／1 | **−1.13%** |
| 高载 85 Mb/s | 81.58／81.55 | 99.76／99.98 | 1／1 | +0.01% |
| 持续过载 270 Mb/s | 123.15／121.44 | 31.61／32.32 | 1／1 | −0.01% |
| 主信道干扰 | 61.30／61.70 | 58.90／80.39 | 9／7 | −0.31% |
| 次信道干扰 | 61.76／61.82 | 79.08／70.88 | 9／43 | +0.66% |
| 宽带干扰 | 59.83／59.61 | 37.82／43.68 | 9／8 | −0.65% |
| 周期突发 | 26.07／26.09 | 78.45／99.25 | 10／12 | +0.14% |
| 1 s 阶梯与恢复 | 34.56／34.57 | 98.36／97.66 | 4／28 | +0.16% |

**可以归为节能收益的场景。** 35 Mb/s 配置下，同种子两策略均最终交付全部报文。D5 窗内交付量相同或更多，VO/VI/BE/BK 的最终准时率均未降低，P99 均缩短。B3 的两个种子分别少耗 0.169% 和 0.226%，改用论文功率后分别少耗 0.817% 和 1.446%。这说明收益取决于功率口径和场景。过载组虽有很小的平均总能耗下降，D5 窗内成功吞吐更低，单位成功比特能耗从 74.38 升到 75.35 nJ/bit。宽带组也伴随成功量下降或分业务取舍。这两组的能耗差保留在图中，单独解释其交付差异。

**降档和较长窗口。** 低载组全程 LCM。1 s 阶梯在 400 ms 降载，D5 在 410.025 和 465.025 ms 首次返回 LCM。780 ms 停流后两种子均在 820.025 ms 完成降档。信道在 570 ms 恢复后，策略继续按实际负载和队列决定模式。2 s 重复周期使用相同首周期逐包输入，首周期上述降档时刻一致。1 s 与 2 s 的 D5 平均功率为 8.1473／8.1493 W，成功吞吐为 34.568／34.613 Mb/s，最终准时率为 97.66%／97.25%。切换从 28 次变为 58 次，约 28／29 次每秒。窗口拉长后仍可观察到动态干扰时的额外切换。恒定高载和过载都只有一次实际升档。

**次信道修正的边界。** 原 D4 种子 101 回归中，D5 分别在 SFU 1 的 31.581200 ms 和 SFU 2 的 32.110000 ms 成功提交允许的经济降档。VO/VI 最终准时率由 93.33%／98.00% 改善至 96.67%／99.33%，窗末未完成却从 64 增到 89 包。新的独立次信道场景里，D4 和 D5 均最终交付全部业务，D5 降低 HCM 占比 39.33%→34.32%，VO/VI 准时率由 92.06%／95.00% 轻微降至 91.37%／94.73%，切换由 41 增至 43 次。D5 修正了已知降档拒绝，但次信道干扰下的切换与高优先级 QoS 仍未达到普遍改善。相同信道的免扫描费对照使 D5 平均切换为 30 次、平均功率为 8.2662 W/AP。计入扫描的结果为 43 次、8.3417 W/AP，实际策略会随着估计成本变化。

**容量与预测证据。** 270 Mb/s 组的实到输入为 270.32 Mb/s。同输入的固定 HCM 在窗内只交付 126.19 Mb/s，窗末平均余 3070 包，固定 LCM 吞吐为 57.34 Mb/s。这是该 PHY/MAC 配置在给定负载下的服务瓶颈。过载 D5 的固定模式预测回放中，5 ms 到达速率 MAE 为 20.30 Mb/s，有效成功吞吐 MAE 为 119.20 Mb/s。1 s 阶梯相同回放口径为 9.02／13.72 Mb/s。回放只选周期时点、随后 5 ms 内实际模式不变的切片，用后来日志计算实绩，在线选择看不到这些数据。误差包含在途前缀与真实竞争/聚合变化。它支持区分估计误差和第 1 节的逻辑拒绝，不能当作未来绝对服务能力上界。

### FTTR 光无线融合

两台 SFU、每台两台 STA，输入约四分之一为上行。两种光路逐包共享输入。融合使用 10 Gb/s 共享下行、2.5 Gb/s 上行、125 μs 帧、REPORT/BWmap、GIANT Complete 和 VO→T1、VI→T2、BE→T3、BK→T4。光传播为下行 10 μs、上行 10.025 μs，轮询持续到业务观察窗后 60 ms。脚本逐包核查串行化时长、传播、共享时隙无重叠及 DATA 落在已接收的 BWmap 内。

| 完整策略的实测方向 | 理想光路吞吐（Mb/s） | 融合吞吐（Mb/s） | 理想／融合端到端 P99（ms） | 融合光侧平均耗时（μs） |
| --- | ---: | ---: | ---: | ---: |
| 下行 | 42.20 | 42.20 | 4.302／3.617 | 10.984 |
| 上行 | 14.14 | 14.03 | 2.977／13.575 | 1614.537 |

正式融合中，D5 两种子都留有 2 个无法排空的上行 BE 报文，两个都超过时限。原额度 1200 B 的 `joint-quantum-limit` 也独立保留同样未交付现象。把 T3 配置额度提高到 2400 B 后，SFU 1 的包 1403 仍对应 Alloc-ID 103 的交替授权 600/900 B，实际装载均为 0 B，队首需求 1500 B。窗口末和最终待交付、显式丢弃分开列。D5 正式融合的平均排空阶段 AP 能耗为 885.95 mJ，包含仍在等待光侧事件时的 AP 空闲，和观察窗能耗分账。该现象属于冻结光侧的授权与完整包装载限制，与无线节能策略并存。报告保留全部配对记录和两笔最后授权证据。

## 4. 消融和验收结论

每一列是只关闭一项后的「平均切换差／平均 VO、VI 准时率差」，符号均为关闭项减完整策略。成功吞吐、能耗与全部业务的配对值在消融 CSV 和图中。

| 单独关闭 | 次信道干扰（次／百分点） | 阶梯与恢复（次／百分点） |
| --- | ---: | ---: |
| 信道感知 | −40.5／+1.35 | −23／+3.40 |
| QoS 筛选及紧急触发 | −21.5／+0.37 | −19／+2.11 |
| 主动延迟 | 0／0 | 0／0 |
| 经济驻留 | +5／+0.79 | +2／−0.63 |

关闭信道感知只改变预测，实际干扰与扫描仍发生。在这两组，信道感知和紧急重判确实带来更多切换，关闭它们的部分高优先级指标有改善。QoS 不能由策略名称推定。主动延迟在这两组未实际触发，另用两个独立种子的原主信道轨迹补测。完整策略分别释放 3、2 次延迟，主动等待共 1.5294、0.6198 ms，关闭延迟后切换次数和成功吞吐相同。完整策略相对关闭项的能耗差分别为 −11.728、+82.528 μJ，收益方向不一致。

**工程验收。** 196 项定向和兼容测试通过，其中本次新增 8 项。先做 5 次 1 s 试跑，再完成 141 次正式及消融 Java 仿真，73 次独立重放的重叠记录一致。校准的 6 次运行独立于正式数据。核对了同输入、逐包守恒、窗口 C4 计数、最终截止时间、B2 活动时间、B3/B4/扫描能耗、非法同时切换和光侧授权。对照测试扰动后来到达的业务，首个在线决策保持一致。原 D4 的主信道和次信道回归继续可复现。可作为完整策略的实验候选，结论适用于这些配置和两个正式种子，次信道稳定性、过载预测和 PON 尾包交付仍是已观察到的边界。

## 5. 复现和文件

从仓库根目录运行。实际 `.properties` 和代表性 Java 原始日志生成在 `target/d5-ecq-sdps`。配置清单含冻结阈值、场景、输入指纹与实际配置文件校验值。绘图使用 Python 3.10+、Matplotlib 3.7+ 及微软雅黑或 Noto Sans CJK，支持 `--font`。图中的区间为两个种子的范围。

```powershell
mvn '-Dtest=ApResidencyModePolicyTest,ApDeadlineModePolicyTest,ApQosModeSimulationTest,ApEnergyModePolicyTest,ApEnergyModeSimulationTest,ApModeControlSimulationTest,ApModeSwitchSimulationTest,ApServicePredictionTest,ApAnalysisSimulationTest,ApTrafficObserverTest,AmpduSimulationTest,ChannelManagementTest,ChannelObservationSimulationTest,ExternalCcaSimulationTest,SubchannelPerSimulationTest,GiantCompletionTest,PonDownlinkTest,UplinkSimulationTest' package
python -B tools/verify_d5_ecq_sdps.py --phase calibrate
python -B tools/verify_d5_ecq_sdps.py --phase pilot --work target/d5-pilot --output target/d5-pilot/results --frozen target/d5-ecq-sdps/frozen-threshold.json
python -B tools/verify_d5_ecq_sdps.py --phase formal --workers 3
python -B tools/plot_d5_ecq_sdps.py
python -B tools/verify_d5_ecq_sdps.py --phase replay --work target/d5-replay --output target/d5-replay/results --frozen target/d5-ecq-sdps/frozen-threshold.json
```

第 1 节的逐包诊断额外重放一次 D3。以下命令生成 `d3-mixed-secondary-101.log`，可和同一目录下 `d4-mixed-secondary-101-ecq-sdps-d4.log` 的 `ARRIVAL`、`WIFI_EXTERNAL_CCA`、`WIFI_PHY_MPDU_DECODE` 及 `DELIVERED` 并排核对。

```powershell
$env:PYTHONPATH = (Resolve-Path tools).Path
@'
from verify_d3_qos_deferral import scenario_config
from verify_d5_ecq_sdps import JAR, WORK, run_java, trace_runner
settings = scenario_config("mixed-secondary", 101, "ecq-sdps")
run_java("d3-mixed-secondary-101", settings, trace_runner(JAR, WORK), WORK, True)
'@ | python -B -
```

- [实际配置与冻结阈值](../../results/d5-ecq-sdps/experiment-config.json) · [综合性能 CSV](../../results/d5-ecq-sdps/d5-performance.csv) · [配对消融 CSV](../../results/d5-ecq-sdps/d5-ablation.csv) · [模式、预测回放与光侧证据 CSV](../../results/d5-ecq-sdps/d5-mode-events.csv)
- 负载、功率、能效与实际吞吐。[PNG](../../results/d5-ecq-sdps/d5-load-efficiency.png) · [SVG](../../results/d5-ecq-sdps/d5-load-efficiency.svg)
- 干扰条件下的逐业务时限、P99 和积压。[PNG](../../results/d5-ecq-sdps/d5-interference-qos.png) · [SVG](../../results/d5-ecq-sdps/d5-interference-qos.svg)
- 动态模式时间线、驻留和切换。[PNG](../../results/d5-ecq-sdps/d5-dynamic-modes.png) · [SVG](../../results/d5-ecq-sdps/d5-dynamic-modes.svg)
- FTTR 光无线融合的端到端性能。[PNG](../../results/d5-ecq-sdps/d5-fttr-joint.png) · [SVG](../../results/d5-ecq-sdps/d5-fttr-joint.svg)
- 四机制配对消融。[PNG](../../results/d5-ecq-sdps/d5-ablation.png) · [SVG](../../results/d5-ecq-sdps/d5-ablation.svg)
- 同观察窗相对固定阈值的能耗差。[PNG](../../results/d5-ecq-sdps/d5-relative-energy.png) · [SVG](../../results/d5-ecq-sdps/d5-relative-energy.svg)
