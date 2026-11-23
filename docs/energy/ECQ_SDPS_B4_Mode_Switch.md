# B4 AP 手动模式切换验证

B4 为每台 SFU 的 AP 增加一份手动模式请求表。请求按指定的仿真时刻提交，可以排队至无线事务安全结束。切换启动与完成均使用整数纳秒，完成时先更新 AP 的 PHY 能力，再恢复本地无线接入。

## 配置和执行规则

| 配置入口 | 含义 |
| --- | --- |
| `wifi.ap.switch.enabled=true` | 启用手动切换，默认关闭。启用时沿用 B2 活动账本及 B3 功率模型。 |
| `sfu.N.wifi.ap.mode=LCM` 或 `HCM` | SFU 的初始模式。有切换请求的 AP 必须显式设置初始模式。 |
| `sfu.N.wifi.ap.switch.requests=100:HCM,600:LCM` | 按非递减顺序配置“请求时刻 μs、目标模式”。同刻请求按列表顺序进入该 AP 的队列。轮到执行时若已在目标模式，记录跳过。 |
| `wifi.ap.switch.lcm-to-hcm.delay.us`、`wifi.ap.switch.hcm-to-lcm.delay.us` | 两个方向各自的时延。单位 μs，可配置到整数纳秒并须大于零。 |
| `wifi.ap.switch.lcm-to-hcm.power.w`、`wifi.ap.switch.hcm-to-lcm.power.w` | 两个方向各自的等效 AP 设备级功率。单位 W，值须为正有限数。 |
| `wifi.ap.activity.window.us` | 沿用 B2 的统一观察窗口。缺省时结算到仿真自然结束。 |

有切换请求时，两个方向的时延和功率均须显式配置。空请求表可省略方向参数。B1 PHY 汇总记录初始配置，运行中的模式变化以切换完成事件为准。

切换请求独立于业务队列。DATA、SIFS、ACK 构成完整的无线交换，活动 TXOP 须执行到结束。发生碰撞时，切换还要等待所有相关 DATA 和 ACK 超时事务结束。已安全释放无线接入资源的 AP 可以直接开始切换。请求与竞争许可同刻时，先登记切换请求，再处理尚未开始的无线发送。

切换占用本 SFU 的无线接入资源，关联 STA 的 EDCA 倒计时按原有信道忙闲处理冻结、恢复，待发送报文留在队列中。资源释放后的竞争重新经过 AIFS，沿用剩余退避计数。各 SFU 的无线接入独立，其他 SFU 可并行收发或切换。切换期间的时间单独归入 `SWITCH`，原有无线帧服务忙时口径继续用于统计 DATA／ACK 交换。

## 时长和能耗口径

在任一时刻，每台 AP 的活动状态为 IDLE、TX、RX 或 SWITCH。IDLE、TX 和 RX 按当时实际生效的 LCM 或 HCM 归档，切换时间按方向单独记录。窗口按半开区间 `[0, T_obs)` 结算，切换能耗计入实际切换区间与窗口的交集。

```text
T_switch,obs = max(0, min(t_finish, T_obs) − t_start)
E_state (μJ) = P_state (W) × T_state (ns) ÷ 1000
T_IDLE + T_TX + T_RX + T_SWITCH = T_obs
E_total = E_IDLE + E_TX + E_RX + E_SWITCH
```

功率和能耗积分沿用 B3 的十进制计算。全网总能耗为已建模 AP 的能耗之和，合计平均功率以统一观察窗口为分母。各 AP 同时活动时，全网汇总的各状态时长按设备相加。LEGACY AP 的活动仍可观察。

本报告场景选用 LCM→HCM 为 40.125 μs 和 11.50 W，HCM→LCM 为 25.250 μs 和 8.75 W。两组方向参数是用于复核的示例，实际实验应由配置给定。LCM/HCM 正常活动功率仍来自 [B3 初始研究配置](../ECQ_SDPS_B3_AP_Energy.md)。

## 验收场景

- 三个 1200 B 下行报文在 0、260、640 μs 到达。首个切换请求在 100 μs 到达，待首包 ACK 完成，于 243.600 μs 开始切换，283.725 μs 恢复接入。第二包以 HCM 发送，完成后于 600.000 μs 切回 LCM。三包 DATA PPDU 依次为 165.6、77.6、165.6 μs。[往返切换配置](../../configs/b4-ap-switch-round-trip.properties)
- 固定观察窗在 615.125 μs 结束。第一次切换完整计入。第二次切换观察到 15.125 μs，计入 132.34375 μJ，此次切换仍于 625.250 μs 完成，第三包继续执行。[固定窗截断配置](../../configs/b4-ap-switch-clipped.properties)
- AP 1 在 0 μs 起切为 HCM。AP 2 的 HCM 上行 DATA 在 34 μs 开始，随后于 155.600 μs 完成完整交换并切至 LCM。AP 1 自 74.125 μs 起发送 HCM 下行，两台 AP 的切换和帧交换可重叠。[多 AP 配置](../../configs/b4-ap-switch-multi-ap.properties)
- 零请求场景以初始 LCM 处理两包，并对照默认关闭和显式关闭的原有输出。[零切换配置](../../configs/b4-ap-switch-zero-switch.properties)

| 场景 | AP | 观察窗（μs） | IDLE（μs） | TX（μs） | RX（μs） | SWITCH（μs） | 切换能耗（μJ） | 总能耗（μJ） |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 往返切换 | 1 | 883.6 | 325.425 | 408.8 | 84 | 65.375 | 682.375 | 7699.2135 |
| 固定窗截断 | 1 | 615.125 | 260.675 | 243.2 | 56 | 55.25 | 593.78125 | 5379.64475 |
| 多 AP | 1 | 800 | 654.275 | 77.6 | 28 | 40.125 | 461.4375 | 6505.413 |
| 多 AP | 2 | 800 | 475.55 | 56 | 243.2 | 25.25 | 220.9375 | 6327.1365 |
| 零切换 | 1 | 1000 | 612.8 | 331.2 | 56 | 0 | 0 | 8028.704 |

往返切换的手算为 `40.125 × 11.50 + 25.250 × 8.75 = 682.375 μJ`。固定窗的第二项改为 `15.125 × 8.75`，两个方向合计 `593.78125 μJ`。常规活动的分模式时长与功率乘积、每个方向的截断区间和窗内累计值见对账表。

## 测试与复现

四份 Java 场景的事件与状态汇总均和表中的手算值一致。全量 Maven 回归执行 373 项测试，全部通过，失败、错误和跳过均为 0。B1～B4 的专项集合执行 73 项，其中新增 B4 测试 24 项，覆盖同刻竞争、退避冻结、跨 TXOP 和碰撞 ACK 超时的延迟、条件校验、非法帧收发检查，以及固定窗前后时长和能耗守恒。9 组光侧冻结用例亦通过。

将当前 JAR 与 B3 提交 `2604e0c` 的已有 JAR 逐行比较，默认关闭 B4 的 15 份 B1～B3 配置中，原协议和能耗输出均完全一致。单独打开 B4 而保留空请求表时，观察到 0 次切换和 0 切换能耗，原协议事件也与默认关闭一致。

另在既有 `configs/pon-final-dba.properties` 上临时启用 HE SU、EDCA 和 DATA–SIFS–ACK，配置重试上限 7、ACK 超时 50 μs、5000 μs 观察窗和两台 AP 的往返切换。联通验证中 4 次切换正常完成，12 包全部交付且各包仅交付一次。每台 AP 的 SWITCH 为 65.375 μs，四状态之和为 5000 μs，切换窗口内的同 SFU DATA、ACK 和碰撞启动事件数均为 0。

在仓库根目录执行以下命令。

```powershell
mvn -o clean verify
python -B tools/run_b4_ap_switch.py
python -B tools/verify_pon_final.py
```

脚本逐条比对实际 Java 的切换请求、开始和结束时间，检查相同 SFU 的完整 DATA–SIFS–ACK 交换与切换区间互斥。再按 B2 活动事件重建 IDLE、TX、RX、SWITCH 区间，核对配置功率、两个切换方向时长、窗口截断、每个模式的功率积分和 B3 全网汇总。两份 CSV 生成于同一批实际仿真。

再次重放后，两份 CSV 逐字节匹配。B1、B2、B3 的既有复核脚本也重新生成了相同的事件时序、活动时长和能耗 CSV。

- 切换逐事件 CSV 保留请求、开始、完成、状态转换、DATA 和 ACK 事件及能耗信息。[逐事件记录](../../results/b4-ap-switch/mode-switch-events.csv)
- 时长与能耗表包含逐 SFU、逐常规能力模式、逐切换方向和全网层级。[对账 CSV](../../results/b4-ap-switch/time-energy-reconciliation.csv)
- 验证脚本支持 `--jar`、`--output`，以及用 `--baseline-jar` 提供 B3 构建产物以逐行对比默认关闭的完整输出。[重放脚本](../../tools/run_b4_ap_switch.py)

## 修改文件

- `ApModeSwitch.java`、`Sfu.java` 处理逐 AP 手动请求、安全启动、PHY 链路切换和恢复接入。`WifiChannel.java` 说明接入资源的忙态口径。
- `ConfigLoader.java`、`SimulationConfig.java` 读取、校验并保存方向参数和请求表。
- `EventType.java`、`EventQueue.java`、`Simulator.java`、`SimulationContext.java` 接入请求、完成及状态记账边界，`TraceLogger.java` 输出切换轨迹与分模式汇总。
- `ApActivity.java` 将 SWITCH 与常规状态分开结算，`ApEnergyModel.java` 沿用 B3 计算方向能耗和窗内合计。
- `ApModeSwitchSimulationTest.java` 覆盖安全边界、调度、积分及兼容性。四份验收配置和重放脚本生成逐事件记录及对账表。

## 建模边界

两个方向的重配置均视为单个连续的 AP 射频不可用区间，参数在每次运行内固定。切换服务由手动请求表触发，既有光路继续按独立事件推进。B2 的 IDLE、TX 和 RX 分类沿用原有活动记账口径，B4 的 SWITCH 行只使用对应方向的完整设备级功率。
