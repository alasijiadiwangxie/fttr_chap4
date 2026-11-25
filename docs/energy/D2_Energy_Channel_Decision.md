# D2 AP 联合感知模式决策

D2 在三种 D1 基线之上增加 E-Aware 和 EC-Aware。两种新策略都先算同一批业务在 LCM、HCM 下的服务时间与能耗，再复用 B4 执行器。E-Aware 使用 AP 的业务到达、队列和聚合历史。EC-Aware 还使用收到的 STA 信道反馈、AP 本地忙闲侦听及有效辅助扫描。

## 决策口径

决策时刻读取 C1 的当前只读快照，包含已入队的报文和基于历史到达量平滑得到的后续业务估计。两种候选均使用同一份需求。C3 revised 按业务类别、目的 STA、报文长度和聚合情况估算服务。E-Aware 在预测中使用理想空口，EC-Aware 用 C2 观测估计 PER、重传及 CCA 等待。两者的实际传输均使用该场景配置的信道、EDCA 与重传。上行可纳入 AP 已接收的负载估计，STA 私有队列对决策不可见。

| 项目 | 比较方法 |
|---|---|
| 窗内业务和活动 | 对两种模式各估算成功位数、队列余量和 TX/RX/IDLE。C3 预计的有效空口工时包含 MAC 接入与重试。CCA 忙份额只作用于相关时间区间 |
| 已承诺的事件 | 窗内已开始的 DATA、ACK/Block ACK、反馈、已知超时或正在执行的 B4 切换为两方案共用。分别按原模式活动或原切换功率计费。在途报文单列，后续实际重传回到本地队列时可进入新的决策 |
| 新切换 | 仅切换方案计入新切换时长和方向功率。已知事务结束后若检测到外部忙碌，以最近可见忙份额估计额外等待。B4 按实际事件决定何时开始切换 |
| 剩余业务 | 缺少的成功位数为需求位数减预计成功位数，覆盖窗口外积压和预计失败。按同一候选带着接入、重传与忙闲条件的预计服务效率，为缺少的位数补计完整服务能耗。若有剩余但完全无预计成功能力，该候选的补价为无穷 |
| 选择 | 总分为 5 ms 等长窗口的能耗加剩余业务补价。备选成本加可配置裕量仍低于当前方案时才切换。信道信息不完整时，使用备选的最高成本对当前的最低成本做同样判断 |

窗口内所有预测时长均以 ns 记录，能量按 `功率 W × 时长 ns / 1000` 转为 μJ。剩余服务用包含 CCA 的单位成功位工时折算其预计 TX/RX 与等待，再按候选模式功率积分。剩余服务成本用于比较预测方案；真实丢弃、排空和实际能耗由仿真事件独立记录。

## 配置和实验设计

| 配置 | 设置 |
|---|---|
| `wifi.ap.control.policy` | `always-lcm`、`always-hcm`、`fixed-threshold`、`e-aware`、`ec-aware`。可以用 `sfu.N.wifi.ap.control.policy` 单独覆盖，默认 `off` |
| `wifi.ap.control.period.us` | 默认 `5000`。阈值策略沿用 `31 Mb/s` 和权重 `0.25` |
| `wifi.ap.prediction.window.us` | D2 默认 `5000`。由 D2 决策事件使用 C3 revised 计算 |
| `wifi.ap.observation.period.us` | D2 默认 `5000`，可独立配置。决策可读取当时开放窗口，C1 的发布周期照常推进 |
| `wifi.ap.control.energy.margin.uj` | 默认 `0`。可设置为非负值，候选至少要节省更多能量才会发起切换 |
| PHY、MAC 与起始模式 | HE SU，MCS 7，GI 0.8 μs，LCM 20 MHz/1SS，HCM 80 MHz/2SS。A-MPDU 上限 8，BE AIFS 43 μs，ACK 24 Mb/s，重试上限 2。动态策略默认从 LCM 开始 |
| 功率 | `RESEARCH_INITIAL_V1`。LCM IDLE/TX/RX 为 7.38/9.20/8.20 W，HCM 为 7.62/10.50/8.70 W。升档 40.125 μs、11.50 W，降档 25.250 μs、8.75 W |
| 信道 | 理想场景关闭 PER 与外部干扰。其余场景启用 C2 分块 PER。接收端干扰可低于 AP 的 CCA 门限。动态场景另配高于 −82 dBm 门限的 750 μs 忙脉冲 |
| 观测 | 有干扰实验每隔 5 ms 提供 STA 反馈，测量在决策前 250 μs，延迟 100 μs，有效 6 ms，采用零空口时长的逻辑报告。辅助射频每次扫描 400 μs、0.5 W，计入实际总能耗 |

扫描 0、12、25、40、60、85 Mb/s，报文分别为 500、1000、1500 B。每种包长与四种干扰模式各运行五种策略，采用种子 101。6 台独立 SFU 各接 1 台 STA，对应 6 个负载。每个负载使用独立的泊松流，观察窗为 40 ms。边界图显示 10～35 ms 之间各点选择 HCM 的决策比例，保留离散采样和可能的往返切换。

动态场景观察 120 ms。两组单 AP/STA 使用 1500 B BE 流量，按 10→50→10 Mb/s 名义负载分三段各运行 40 ms，其中一组在 40～90 ms 叠加次信道干扰。第三组使用两台 SFU、每台两台 STA、1000 B 的 BE/VI 背景和突发流量，两台分别受次信道和主信道干扰。动态场景逐一用种子 101、202、303 配对。所有场景复用零时延固定光路，五种策略同组的生成事件逐包核对一致。

## 实测结果

动态实验下表为三个种子的平均值。能耗包括 B3 无线活动与 B4 切换，以及实际启动的辅助扫描。P95/P99 计算自观察窗内实际交付的报文，积压单列。图中的误差线表示三个种子的最小值和最大值。

| 场景 | 策略 | AP 能耗（mJ） | 成功吞吐（Mb/s） | P95 / P99（ms） | 切换次数 | HCM 驻留 |
|---|---|---:|---:|---:|---:|---:|
| 理想信道阶梯 | Always-LCM | 964.21 | 23.50 | 0.965 / 1.000 | 0 | 0% |
|  | Always-HCM | 972.90 | 23.50 | 0.297 / 0.347 | 0 | 100% |
|  | Fixed-Threshold | 963.78 | 23.50 | 0.773 / 0.985 | 2 | 29.03% |
|  | E-Aware | 965.01 | 23.50 | 0.737 / 0.967 | 2 | 41.50% |
|  | EC-Aware | 965.01 | 23.50 | 0.737 / 0.967 | 2 | 41.50% |
| 次信道干扰阶梯 | Always-LCM | 978.01 | 23.50 | 0.965 / 1.000 | 0 | 0% |
|  | Always-HCM | 966.63 | 23.50 | 0.949 / 1.170 | 0 | 100% |
|  | Fixed-Threshold | 969.80 | 23.50 | 0.961 / 1.107 | 2 | 29.03% |
|  | E-Aware | 968.14 | 23.50 | 0.977 / 1.249 | 2 | 41.50% |
|  | EC-Aware | 973.23 | 23.50 | 0.928 / 1.232 | 5.33 | 14.96% |
| 双 AP 突发 | Always-LCM | 1992.59 | 46.87 | 14.270 / 15.606 | 0 | 0% |
|  | Always-HCM | 1956.84 | 47.16 | 4.892 / 5.922 | 0 | 100% |
|  | Fixed-Threshold | 1963.75 | 46.93 | 9.683 / 11.753 | 8 | 22.61% |
|  | E-Aware | 1955.00 | 47.07 | 6.270 / 8.087 | 5.67 | 65.73% |
|  | EC-Aware | 1957.00 | 47.13 | 7.484 / 8.603 | 5 | 57.37% |

- **信道观测改变了实际决策。** 次信道阶梯的种子 101 在 50 ms 具有相同的队列、到达估计和 335.4 μs 已承诺无线等待。E-Aware 预测升到 HCM 的成本为 41.654 mJ、保持 LCM 为 41.765 mJ，因此请求升档。EC-Aware 估计 HCM 单次失败率为 11.21%，其 HCM 成本变为 42.013 mJ，于是保持 LCM。
- **节能和时延随场景变化。** 两组阶梯实验的所有策略都交付了全部 235 包。次信道干扰阶梯中，EC-Aware 的 P99 比 E-Aware 平均短 0.017 ms，能耗高 5.09 mJ。EC-Aware 平均多消耗 3.80 mJ 辅助扫描能量，同时平均多切换 3.33 次。种子 303 的 EC-Aware P99 为 1.424 ms，E-Aware 为 1.341 ms。双 AP 突发时 EC-Aware 平均单位成功比特能耗为 346.00 nJ/bit，E-Aware 为 346.14 nJ/bit。观察窗末各策略均余 2 包，后续交付；EC-Aware 平均显式丢弃 1 包，E-Aware 为 2 包。
- **模式边界依赖包长和干扰。** 1500 B、40 Mb/s 的主信道场景，EC-Aware 在六次边界采样中全部选择 HCM，E-Aware 选择三次。理想信道下，1500 B、85 Mb/s 的两种新策略在这 40 ms 完成七次切换。宽带干扰下的 1500 B、85 Mb/s 场景，EC-Aware 在窗口内生成 281 包、交付 262 包、丢弃 15 包，剩 4 包。持续高负载时的来回切换和丢弃均保留在 CSV。

## 验收

| 核查内容 | 证据 |
|---|---|
| 同一批业务和 B4 能耗 | 空载 HCM 保持 5 ms 为 38,100 μJ，降至 LCM 的预测总成本为 `25.250 × 8.75 + 4974.750 × 7.38 = 36,934.5925 μJ`，实际降档时序与 B4 一致。配置 1200 μJ 裕量时保持 HCM |
| 未完成业务补价 | 解析用例中，慢候选窗口内能耗较低，但补计未完成业务后总分更高。候选成功率为零时要么选另一可服务模式，要么保持当前模式并记录缺少有效服务模型 |
| 信道感知与因果关系 | 两候选均需处理 264,000 bit 的确定性场景中，E-Aware 选择 HCM，EC-Aware 用已收到的次信道反馈预测 HCM 单次失败率为 100%，选择 LCM。更改首次决策之后的输入和信道，首次决策保持一致。另验证延迟、到达和过期的反馈状态 |
| 实际等待、安全和守恒 | 正在收发时保留前缀中的对应 TX/RX，ACK 超时等待按 IDLE 结算。测试覆盖已开始的反馈、在途切换、CCA 预测与实际释放的差异。每个运行核对请求与执行、逐包交付、观察窗末积压、活动时长和能耗 |
| 回归 | Maven 定向及兼容测试 133 项全部通过，其中新增 D2 测试 21 项。D1 的 17 个验收场景重放后，两份 CSV 与原产物逐字节一致 |

专项脚本共执行 105 个 Java 场景。输入指纹按同场景、同种子配对。脚本从 C1 入队事件重算每个采样窗口到达率，根据日志中的 C3 工时重新计算候选成本和决策，核对真实无线交换、切换与活动时间，逐包检查自然排空后交付和丢弃。窗末尚未完成的报文单独计数。独立重放 25 个场景，两份 CSV 中的对应行逐字段一致。

## 模型边界

C3 在短窗口内沿用当前观测到的信道分布。辅助扫描到达后可补充候选宽带的忙份额，并通过子信道空闲比例的乘积近似联合占用。报告、扫描和历史的缺失会扩大预测范围。范围反映模型假设的变化，真实设备误包曲线仍待校准。外部忙碌结束时刻由实际仿真事件确定，D2 只用过去的忙份额作等待估计。C3 的预测能耗包含无线活动和切换，辅助扫描耗电单列于实测结果。EDCA 竞争与链路状况会随模式变化，少量历史样本可带来来回切换。D2 用于建立可重放的比较基线，试验网格上的选择边界随模型和场景而变。

## 复现

在项目根目录运行下列命令。脚本将实际 `.properties` 写入 `target/d2-energy-decision`，并默认保存代表性 EC-Aware 时间线的完整 Java 日志。用 `--keep-logs` 保存所有原始日志。绘图使用 Python 3.10 或更新版本、Matplotlib 3.7 或更新版本及微软雅黑或 Noto Sans CJK 字体。

```powershell
mvn '-Dtest=ApEnergyModePolicyTest,ApEnergyModeSimulationTest,ApModeControlSimulationTest,ApModeSwitchSimulationTest,ApServicePredictionTest,ApAnalysisSimulationTest,ApTrafficObserverTest,AmpduSimulationTest,ChannelManagementTest,ChannelObservationSimulationTest,ExternalCcaSimulationTest,SubchannelPerSimulationTest' package
python -B tools/verify_d2_energy_decision.py
python -B tools/plot_d2_energy_decision.py
```

- 两档预测、置信度、成本与模式切换。[决策事件 CSV](../../results/d2-energy-decision/d2-decision-events.csv)
- 各种子、各 AP 与双 AP 汇总的实际吞吐、能耗、P95/P99、积压、丢弃、切换与驻留。[性能对照 CSV](../../results/d2-energy-decision/d2-performance.csv)
- 模式选择边界。[PNG](../../results/d2-energy-decision/d2-mode-boundary.png) · [SVG](../../results/d2-energy-decision/d2-mode-boundary.svg)
- 负载、信道与模式时间线。[PNG](../../results/d2-energy-decision/d2-channel-mode-timeline.png) · [SVG](../../results/d2-energy-decision/d2-channel-mode-timeline.svg)
- 五策略性能对照。[PNG](../../results/d2-energy-decision/d2-policy-comparison.png) · [SVG](../../results/d2-energy-decision/d2-policy-comparison.svg)
