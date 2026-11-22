# B2 AP 无线活动状态与时长验收

B2 为每台 SFU 的 AP 建立独立的活动账本。一次仿真内能力模式沿用 B1 的静态设置，该 AP 的活动时长归入 LCM、HCM 或原有全局无线配置对应的 `LEGACY`。时长和状态边界采用整数纳秒，报告中的微秒由纳秒换算。

## 记账口径

| 事件区间 | 下行 DATA 交换 | 上行 DATA 交换 |
| --- | --- | --- |
| DATA 开始至结束 | AP TX | AP RX |
| DATA 结束至 ACK 开始的 SIFS | AP IDLE | AP IDLE |
| ACK 开始至结束 | AP RX | AP TX |
| AIFS、Backoff、TXOP 内帧间等待 | 无实际帧活动时为 AP IDLE | 无实际帧活动时为 AP IDLE |

活动状态与 CCA 分别记录。即使信道在 SIFS 期间仍保持占用，AP 在这段等待中也记作 IDLE。ACK 超时是 MAC 定时事件，超时等待期间若还有指向 AP 的 DATA 在空中，继续按实际帧活动记账。重传的每次实际发送单独纳入活动时长。

同一碰撞中的重叠上行 DATA 以 RX 区间的并集计时。AP 自身参与碰撞发射时，半双工 AP 优先记录 TX。若本端 DATA 结束时仍有指向 AP 的上行 DATA，则剩余区间记作 RX。物理忙闲感知、NAV 及对非目标帧的被动侦听未纳入 AP RX。当前无线拓扑每台 SFU 使用独立信道，数据交换按关联 AP 和 STA 建模。

## 配置与观察窗口

| 配置 | 含义 |
| --- | --- |
| `wifi.ap.activity.enabled=true` | 启用逐 AP 活动记录。默认关闭，兼容原配置和日志。 |
| `wifi.ap.activity.window.us=1000` | 可选的统一观察终点，单位 μs。接受可精确转换为正整数纳秒的值。 |

所有 AP 在仿真时刻 0 从 IDLE 开始。省略观察终点时，每台 AP 都使用同一个自然仿真结束时刻结算。指定固定观察终点时，只累计与半开区间 `[0, 观察终点)` 相交的时长。较短窗口可以截断 DATA 或 ACK 的观测时长，协议仍执行到自然结束。较长窗口的末尾计为 IDLE，事件队列按原有时钟结束。同刻事件按现有调度顺序处理，相邻边界在同一纳秒的间隔为 0。

## B1 两包对照

四份配置均发送同批两条 1200 B VO 下行报文，复用 B1 的 HE SU 和 EDCA 参数，种子为 0。链路参数、PPDU 推导及 ACK 时长见 [B1 验证报告](ECQ_SDPS_B1_Static_AP.md)。

| 模式 | 自然窗口配置 | 1000 μs 窗口配置 |
| --- | --- | --- |
| LCM | [LCM 自然结束](../configs/b2-ap-activity-lcm-natural.properties) | [LCM 1000 μs](../configs/b2-ap-activity-lcm-1000us.properties) |
| HCM | [HCM 自然结束](../configs/b2-ap-activity-hcm-natural.properties) | [HCM 1000 μs](../configs/b2-ap-activity-hcm-1000us.properties) |

每包 DATA 的 TX 分别为 LCM 的 165.6 μs 与 HCM 的 77.6 μs。每包 ACK 的 RX 为 28 μs。两次 AIFS 和退避分别占 34 μs 与 52 μs，两次 SIFS 各占 16 μs。自然窗口的 IDLE 为 `34 + 52 + 2 × 16 = 118 μs`。固定窗口尾部的 IDLE 分别增加 `1000 − 505.2 = 494.8 μs` 和 `1000 − 329.2 = 670.8 μs`。

| 场景 | 观察时长（μs） | 手算 IDLE／TX／RX（μs） | 实测 IDLE／TX／RX（μs） |
| --- | ---: | ---: | ---: |
| LCM 自然结束 | 505.2 | 118／331.2／56 | 118／331.2／56 |
| HCM 自然结束 | 329.2 | 118／155.2／56 | 118／155.2／56 |
| LCM 固定窗口 | 1000 | 612.8／331.2／56 | 612.8／331.2／56 |
| HCM 固定窗口 | 1000 | 788.8／155.2／56 | 788.8／155.2／56 |

两种模式首包都在 34 μs 从 IDLE 进入 TX。LCM 的后续边界分别在 199.6 μs、215.6 μs、243.6 μs，HCM 分别在 111.6 μs、127.6 μs、155.6 μs。它们依次对应 DATA 结束进入 IDLE、ACK 开始进入 RX 和 ACK 结束返回 IDLE。次包和所有状态的原始纳秒边界见逐状态 CSV。

## 上行和多 SFU 验收

[混合方向配置](../configs/b2-ap-activity-mixed.properties)使用两个独立信道，SFU 1 采用 LCM 下行，SFU 2 采用 HCM 上行。两条报文在 34 μs 同时进入 DATA，SFU 1 进入 TX，SFU 2 进入 RX。两包均交付。配置使用相同的 1000 μs 窗口，实测与下表的手算值一致。

| SFU／业务方向 | DATA 状态和时长（μs） | ACK 状态和时长（μs） | IDLE／TX／RX（μs） |
| --- | --- | --- | ---: |
| SFU 1／下行 | TX 165.6 | RX 28 | 806.4／165.6／28 |
| SFU 2／上行 | RX 77.6 | TX 28 | 894.4／28／77.6 |

## 回归用例

`mvn -o clean verify` 共执行 331 项测试，包含 313 项已有测试及 18 项新增 B2 测试，全部通过。9 份光侧冻结用例与原有结果一致，B1 两份对照配置也通过重放验证。

| 用例 | 核对口径 |
| --- | --- |
| B1 两包与固定尾段 | 四组预期值和实际逐包协议轨迹一致。 |
| 截断 HCM 的首个 DATA | 50.125 μs 窗口得到 IDLE 34 μs、TX 16.125 μs、RX 0 μs，协议轨迹继续到 329.2 μs。 |
| 不同时长的两条上行 DATA 碰撞 | AP 整段 RX 91.2 μs。较短 DATA 对应的 123 μs 超时落在较长 DATA 的 RX 区间内。 |
| AP 与 STA 的混合方向碰撞 | AP TX 77.6 μs，其后记录 6.4 μs 上行 RX 尾段。 |
| 碰撞后成功重传 | 失败的重叠 DATA 和后续两次成功 DATA 共记 RX 232.8 μs，两次 ACK 共记 TX 56 μs。 |
| TXOP 帧间 SIFS | 即使无线资源连续被占用，两个 DATA 之间的 SIFS 仍计 IDLE。 |
| 同刻结束与下一帧开始 | 半开区间无重复时长，零长度等待计为 0。 |
| 无业务 SFU 与旧配置 | 无业务 AP 的整段窗口计 IDLE。关闭 B2 后旧版轨迹保持一致。 |

另以 `configs/pon-final-dba.properties` 为基础启用 HE SU、EDCA 多 AC 和 DATA–SIFS–ACK，设置 SFU 1 为 LCM、SFU 2 为 HCM。在同一批 12 条双向报文上比较两次运行，分别关闭活动统计与启用 5000 μs 活动窗口。去除新增活动日志后，1944 行原有轨迹逐行一致，12 条报文均交付。两台 AP 的 IDLE／TX／RX 分别为 3672.8／471.2／856.0 μs 和 4366.4／217.6／416.0 μs。

## 复现与文件

从仓库根目录执行以下命令。

```powershell
mvn -o clean verify
python -B tools/verify_pon_final.py
python -B tools/run_b2_ap_activity.py
```

[对照脚本](../tools/run_b2_ap_activity.py)逐个启动实际 Java 仿真，核对 DATA 与 ACK 的原始事件、活动转换及各 AP 汇总，再从转换时间独立累加各状态时长。每个 AP 的 IDLE、TX、RX 之和均等于观察窗口。脚本支持 `--jar` 和 `--output`。

- [逐状态转换 CSV](../results/b2-ap-activity/activity-transitions.csv)保留实际状态边界、SFU、模式、包号、方向和转换原因。
- [各 SFU 活动汇总 CSV](../results/b2-ap-activity/activity-summary.csv)包含实际仿真结束、固定或自然观察终点、各状态累计时长和手算对照。
- `ApActivity.java` 新增活动状态机与纳秒累计。`Sfu.java`、`Simulator.java` 和 `SimulationContext.java` 将现有帧事件接入账本。`ConfigLoader.java`、`SimulationConfig.java` 和 `TraceLogger.java` 提供开关、观察窗口与输出。`ApActivitySimulationTest.java` 覆盖双向帧交换、异常时序、旧配置及时间守恒。
