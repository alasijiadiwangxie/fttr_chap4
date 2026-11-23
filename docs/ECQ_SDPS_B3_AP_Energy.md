# B3 AP 分模式功耗与能耗积分验证

B3 在窗口结算时读取 B2 的逐 SFU 活动时长，用该 AP 的静态 LCM 或 HCM 功率计算分状态能耗。功率配置标识为 `RESEARCH_INITIAL_V1`，按等效 AP 设备级功率计入仿真。

## 功率参数和来源

| 活动状态 | LCM（20 MHz／1 SS，W） | HCM（80 MHz／2 SS，W） | 参数来源 |
| --- | ---: | ---: | --- |
| IDLE | 7.38 | 7.62 | 参考另一款 AP 的空闲测量值 |
| TX | 9.20 | 10.50 | 初始研究假设 |
| RX | 8.20 | 8.70 | 初始研究假设 |

IDLE 数值取自 Hamidou Dembélé 在 IEEE Access（2025）第 VII-A 节图 10 中的 20 MHz 和 80 MHz 测量结果。源设备为 Cambium X7-35X，测试运行的是 Wi-Fi 7 的 6 GHz 射频，关闭了 2.4 GHz 和 5 GHz 射频。B1 模拟的是 HE SU 的 20 MHz／1 SS 和 80 MHz／2 SS，设备和无线配置均有差异。TX、RX 是研究假设，当前六个参数组成一套待校准的仿真输入。 [论文和 DOI](https://doi.org/10.1109/ACCESS.2025.3590176)

## 配置与计算口径

| 配置 | 作用 |
| --- | --- |
| `wifi.ap.activity.enabled=true` | 启用 B2 账本，是 B3 启用的前提。 |
| `wifi.ap.energy.enabled=true` | 启用 B3，默认关闭。 |
| `wifi.ap.energy.lcm.idle.w`、`wifi.ap.energy.lcm.tx.w`、`wifi.ap.energy.lcm.rx.w` | 独立覆盖 LCM 的三种功率。 |
| `wifi.ap.energy.hcm.idle.w`、`wifi.ap.energy.hcm.tx.w`、`wifi.ap.energy.hcm.rx.w` | 独立覆盖 HCM 的三种功率。 |
| `wifi.ap.activity.window.us` | 继承 B2 的统一窗口终点，默认在最后一个仿真事件结束时结算。 |

功率覆盖值使用正有限十进制数，缺省项各自采用表中的初始值。配置中的覆盖值在读取时校验，B3 关闭时只执行原有仿真和输出。

B2 先结算窗口内 `idle_ns`、`tx_ns` 和 `rx_ns`，B3 每台 AP 读取一次这些时长。自然窗口的终点是本次仿真的自然结束时刻。固定窗口采用半开区间 `[0, end)`，包括尾部 IDLE，也可以在正在收发的状态中截止。固定窗口仅改变统计口径。

`E_s (μJ) = P_s (W) × T_s (ns) ÷ 1000`，`E_total = E_IDLE + E_TX + E_RX`，`P_avg (W) = E_total (μJ) ÷ T_obs (μs)`。整数纳秒转换为十进制微秒后再相乘，分量和总能耗均保留精确十进制值。只有平均功率的除法使用 DECIMAL128 舍入。零时长窗口输出 0 W 作为统计约定。

全网行按状态相加已配置 LCM/HCM 的各 AP 能耗。统一窗口长度作为合计平均功率的分母。LEGACY AP 继续输出 B2 活动统计，能耗覆盖范围通过 `modeled_ap_count` 和 `legacy_ap_count` 标识。

## 对照结果

下表以两条 1200 B VO 下行为基准，复用 B2 的 MCS 7、GI 0.8 μs、EDCA 种子 0。所有状态能耗和总能耗的手算值都与 Java 仿真一致。

| 场景 | 窗口（μs） | IDLE（μJ） | TX（μJ） | RX（μJ） | 总能耗（μJ） | 平均功率（W） |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| LCM 自然 | 505.2 | 870.840 | 3047.040 | 459.200 | 4377.080 | 8.664054 |
| HCM 自然 | 329.2 | 899.160 | 1629.600 | 487.200 | 3015.960 | 9.161482 |
| LCM 固定 | 1000 | 4522.464 | 3047.040 | 459.200 | 8028.704 | 8.028704 |
| HCM 固定 | 1000 | 6010.656 | 1629.600 | 487.200 | 8127.456 | 8.127456 |

以 LCM 自然窗口为例，B2 输出 118000、331200、56000 ns。总能耗为 `7.38 × 118 + 9.20 × 331.2 + 8.20 × 56 = 4377.08 μJ`，平均功率为 `4377.08 ÷ 505.2 ≈ 8.664054 W`。同模式固定 1000 μs 时，尾部 IDLE 共 494.8 μs，增加 `494.8 × 7.38 = 3651.624 μJ`。

| 扩展场景 | 实际验证 |
| --- | --- |
| 多 SFU、双方向、1000 μs | SFU 1 的 LCM 下行总能耗为 7704.352 μJ。SFU 2 的 HCM 上行总能耗为 7784.448 μJ，ACK 计入 TX 的 294 μJ，DATA 计入 RX 的 675.12 μJ。SFU 3 为 LEGACY，全网已建模 AP 合计 15488.8 μJ、15.4888 W。 |
| HCM 亚微秒截止、自定义功率 | 50.125 μs 窗口覆盖 34 μs IDLE 和 16.125 μs TX。把 HCM TX 配成 11.125 W，TX 能耗为 179.390625 μJ，总能耗为 438.470625 μJ。完整协议继续执行至 329.2 μs。 |
| LCM 零业务、1000 μs | 整个窗口均为 IDLE，总能耗为 7380 μJ，平均功率为 7.38 W。 |
| HCM 上行碰撞与成功重传 | 两包首次 DATA 重叠的 77.6 μs 只计一次 RX。加上两次成功重传的 RX 和 ACK 的 TX，累计 IDLE／TX／RX 时长为 196／56／232.8 μs，总能耗为 4106.88 μJ。 |

八个场景均从实际 Java 事件重建逐状态区间，与 B2 时长、B3 汇总及手算结果一致。每个场景同时复跑 B3 关闭的基线，去除 B3 汇总行后，其余已有日志逐行相同。

## 测试、配置与复现

本次运行 B1、B2、B3 专项 Maven 测试，共 49 项通过，其中 B3 新增 18 项，失败、错误及跳过均为 0。B3 覆盖自然窗口、固定窗口和裁剪，零业务与零时长，多个 SFU 的上下行帧交换，碰撞和重传，功率参数校验，以及开启统计的协议轨迹核对。

```powershell
mvn -o "-Dtest=ApEnergySimulationTest,ApActivitySimulationTest,StaticApCapabilityTest" package
python -B tools/run_b3_ap_energy.py
```

- 四个基准配置 [LCM 自然](../configs/b3-ap-energy-lcm-natural.properties)、[HCM 自然](../configs/b3-ap-energy-hcm-natural.properties)、[LCM 1000 μs](../configs/b3-ap-energy-lcm-1000us.properties)、[HCM 1000 μs](../configs/b3-ap-energy-hcm-1000us.properties)。
- 四个扩展配置 [多 SFU 混合](../configs/b3-ap-energy-mixed.properties)、[短窗口和自定义功率](../configs/b3-ap-energy-hcm-clipped.properties)、[零业务](../configs/b3-ap-energy-zero-traffic.properties)、[上行碰撞与重传](../configs/b3-ap-energy-hcm-retry.properties)。
- [能耗汇总 CSV](../results/b3-ap-energy/energy-summary.csv)包含逐 SFU、能力模式和全网各状态能耗、平均功率、窗口及核对结果。
- [逐区间能耗轨迹 CSV](../results/b3-ap-energy/energy-intervals.csv)由 B2 实际状态转换重建，含起止事件、关联报文、功率、区间能耗及累计能耗。 [独立复核脚本](../tools/run_b3_ap_energy.py)

## 修改文件与建模限制

- `ApEnergyModel.java` 提供功耗配置和独立的十进制积分及汇总方法。
- `ConfigLoader.java`、`SimulationConfig.java` 解析并保存 B3 参数。
- `Simulator.java`、`SimulationContext.java` 接通结算流程，`TraceLogger.java` 输出能耗结果。
- `ApEnergySimulationTest.java` 覆盖积分、参数、多个 AP 与协议轨迹，场景配置和复核脚本生成以上 CSV。

每个 AP 在一次仿真内使用固定能力配置和各状态常数功率。实际设备的频段、射频链数量、硬件设计、负载及处理需求可能改变功率，需要目标设备数据校准。积分以 B2 的 AP 空口活动分类为基础，沿用对重叠、碰撞和控制等待的统计口径，结果的设备范围为已建模的 AP。
