# REPORT 驱动的 DBA 影子预算验收

设置 `fiber.uplink.dba.mode=shadow-rr` 后，MFU 在已有上行帧调度回调中计算下一帧的建议 DATA 字节预算。默认配置及显式 `fcfs` 继续执行原有流程。影子模式要求已经开启分帧上行、BWmap、REPORT、需求账本和有限轮询。

## 决策输入与计算

规划器只接收固定关联的 SFU ID 列表、链路速率、帧周期、MFU 的需求估计查询，以及目标帧已预留的控制时长。它通过 MFU 的已接收 REPORT 与历史真实授权取得 `effectiveKnownBytes`。输入接口没有 SFU FIFO、远端队首长度或真实预约写入能力。

在调度帧 `f`，先完成目标帧 `f+1` 的 REPORT-only 控制预约，再计算影子建议，最后继续原有 FCFS DATA 预约。这样建议能使用当刻已送达的报告和此前已创建的真实授权，并准确扣除该目标帧的控制时间。相同时刻尚未执行的后续 FCFS 授权尚不在当次需求估计内。没有新增定时事件，末尾仅取样的既有帧回调也会输出建议。

```text
availableDataNs = framePeriodNs - controlReservedNs
dataCapacityBytes = floor(availableDataNs × rateMbps / 8000)
plannedGrantBytes = min(effectiveKnownBytes, dataCapacityBytes)
plannedGrantNs = ceil(plannedGrantBytes × 8000 / rateMbps)
controlReservedNs + plannedGrantNs <= framePeriodNs
```

容量使用精确十进制运算后向下取整为整数 Byte，并以任意精度整数保存中间结果。预算为 long 字节数，发送时长复用上下行的纳秒向上取整公式。零字节建议的时长直接为零。所有计算都在整数 ns 网格上核验。

SFU ID 排序并去重，RR 从最小 ID 开始。每次跳过 UNKNOWN 和已知有效需求为 0 的 SFU，最多选择一台有正需求的 SFU。正预算产生后，游标移到该 SFU 的下一位置；零容量或没有可选需求时保留游标。只有一台具有正需求时，后续帧可以再次建议给它，每次都重新读取当时可见的账本估计。

| 输入状态或结果 | 日志标记 |
| --- | --- |
| 全部 UNKNOWN | `ALL_UNKNOWN`，零预算，保留未知 SFU 数 |
| 存在已知零需求，且无已知正需求 | `NO_KNOWN_POSITIVE_DEMAND`，零预算，分别记录未知和已知零需求数量 |
| 控制预留后容量不足 1 B | `NO_DATA_CAPACITY`，零预算 |
| 正需求可全部容纳 | `KNOWN_DEMAND` |
| 正需求超过当前字节容量 | `FRAME_CAPACITY_LIMIT` |

无选择时，`selected_sfu_id=0` 表示本轮未选 SFU，`report_frame_id=-1` 和 `report_age_ns=-1` 表示没有选定报告来源。该行的零预算不能用来推断某台未知 SFU 的真实队列为空。原有逐 SFU 账本和新增的 `unknown_sfus`、`known_zero_sfus` 共同保留状态区别。

## 主场景实测

沿用第 8 步的两台 SFU 场景。125000 ns 帧周期、2500 Mb/s 上行、10000 Mb/s 下行、64 B 控制占位，控制发送长 205 ns。下行传播 10 μs，上行传播 10.025 μs，轮询间隔 1 帧，授权创建窗口在 625 μs 截止。SFU 1 在 0 μs 收到一个 39062 B 用户报文，SFU 2 为空，固定无线时延为零。

| 决策时刻（ns） | 目标帧 | 选中 SFU | 可见有效需求（B） | 控制预留（ns） | 建议字节 | 建议时长（ns） | 原因 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 125000 | 2 | — | UNKNOWN | 205 | 0 | 0 | 全部未知 |
| 250000 | 3 | — | UNKNOWN | 205 | 0 | 0 | 全部未知 |
| 375000 | 4 | 1 | 39062 | 205 | 38998 | 124794 | 帧容量限制 |
| 500000 | 5 | 1 | 39062 | 205 | 38998 | 124794 | 帧容量限制 |
| 625000 | 6 | 1 | 39062 | 0 | 39062 | 124999 | 已知需求可容纳 |
| 750000 | 7 | — | 两台均已知为 0 | 0 | 0 | 0 | 无已知正需求 |

`floor((125000 - 205) × 2500 / 8000) = 38998 B`，复算时长为 `ceil(38998 × 8000 / 2500) = 124794 ns`。加控制预留后占用 124999 ns，剩余 **1 ns**。

前两次规划时 MFU 尚未收到 SFU 1 的轮询报告。375000 ns 时已知其 39062 B 需求，而 SFU 2 仍为 UNKNOWN；500000 ns 时，SFU 2 已收到 0 B 报告。625000 ns 的建议发生在同刻真实 FCFS 预约之前。之后真实授权入账，750000 ns 的有效需求为零。

两次 38998 B 建议只是对两个不同目标帧的独立评估，原始需求依然由实际授权来消耗。它们不能相加视作已经授出 77996 B。实际仍只有一次 39062 B DATA 授权，在 **750000–874999 ns** 发送，**885024 ns** 交付。真实链路忙时仍为 DATA 124999 ns 加轮询 820 ns，原有吞吐量和需求扣减值均相同。[建议决策 CSV](../../results/pon-dba-shadow/dba-decision-trace.csv) · [逐帧容量检查](../../results/pon-dba-shadow/frame-budget-check.csv) · [完整实际配置](../../results/pon-dba-shadow/test-config.properties)

专项脚本从实际 REPORT 接收和真实预约事件重建 MFU 可见状态，核对了 **6 次决策和 6 条容量检查**。目标帧 7 是既有末帧回调中的零预算评估，日志没有由此创建新的实际帧或发送事件。

## 自动化测试

| 用例 | 预期 | 实测结果 |
| --- | --- | --- |
| 已知 6000 B | 有足够容量时预算为 6000 B，重复建议继续基于同一未消费需求 | 6000 B 对应 19200 ns，再次规划仍为 6000 B，账本真实扣减保持零 |
| 205 ns 控制容量 | 最大 38998 B，重新串行化后仍在帧内 | 124795 ns 可用时间，38998 B 对应 124794 ns，含控制后余 1 ns |
| 多 SFU RR | ID 排序去重，跳过未知与已知零需求，有两个正需求时轮转 | 输入 ID 4、2、1、3、2，正需求选择顺序 1、3、1、3；未知与零需求计数独立 |
| 迟到 REPORT 和历史授权 | 报告到达前零预算，到达后采用有效值而非原始总量 | 6000 B 报告扣除已创建的 2400 B，预算为 3600 B，重复真实授权只扣一次 |
| 无可用容量 | 控制占满一帧时建议为零，不推进正需求 RR | `NO_DATA_CAPACITY`、0 B、0 ns，下一次仍从第一台正需求 SFU 选择 |
| 完整报文风险和实际回归 | 39062 B 报文得到较小预算时只能记录建议 | 建议 38998 B，真实 DATA 仍只在 750 μs 起发一次，唯一 DATA 授权账本值为 39062 B |
| 不同种子与混合 EDCA | 每个种子自身可重放，影子开关只增加决策日志 | 种子 11、29、47 的混合上下行泊松业务均完整重放一致，过滤影子行后开关输出相同 |
| 配置依赖 | 缺少 REPORT、BWmap、账本、轮询，或非法策略名称时拒绝 | 对应配置均在加载阶段抛出明确异常 |

`mvn -o clean package` **197 项测试全部通过，0 项失败、0 项错误、0 项跳过**。其中原有 189 项和新增 8 项。[PonDbaShadowTest.java](../../src/test/java/edu/suda/fttr/simulator/PonDbaShadowTest.java)

## 修改文件与旧模式对照

| 文件 | 修改 |
| --- | --- |
| `PonDbaShadowPlanner.java` | 独立的 MFU 只读需求查询、RR 和预算计算 |
| `Mfu.java` | 在 MFU 端查询账本并记录影子决策 |
| `ConfigLoader.java`、`SimulationConfig.java`、`Simulator.java` | 可选策略配置、依赖检查和每次仿真的独立规划器 |
| `PonUplink.java`、`SimulationContext.java`、`TraceLogger.java` | 控制预约后、真实 DATA 预约前的观测调用与日志 |
| `PonLinkTiming.java` | 同一串行化规则增加 long 字节预算入口 |
| `PonDbaShadowTest.java`、`tools/run_pon_dba_shadow_test.py` | JUnit、真实事件校核和可重放导出 |
| `docs/pon/09-dba-shadow-test.md`、`results/pon-dba-shadow/` | 本报告、实际参数和复核 CSV |

专项脚本比较了 `shadow-rr`、显式 `fcfs` 和默认配置。过滤唯一新增的 `PON_DBA_SHADOW` 行后，完整实际事件日志相同。轮询轨迹、帧占用和 MFU 状态三份数据与第 8 步已提交 CSV 逐项一致。另从提交 `811ee04` 重新编译源码运行原配置，开启影子后的输出经过同样过滤，剩余 **115 行与第 8 步逐字节一致**。[第 8 步验收](08-report-polling-test.md)

## 当前边界

REPORT 只有未授权字节总量。影子规划器没有远端报文大小或队首信息，因此 38998 B 的建议不代表能放入一条 39062 B 的完整报文。SFU 端按预算选完整报文、未使用预算返还、跨帧分片都留待后续。

当前建议只修改规划器自身的 RR 游标。实际 BWmap、DATA 预约和 MFU 授权历史继续由既有 FCFS 产生。过时 REPORT、未报告的新流量及报告缺失造成的估计误差依然存在，建议值不能直接作为真实链路吞吐量或已分配带宽统计。

## 复现

从仓库根目录执行。Python 专项脚本只用标准库，调用实际 Java JAR，核对开关回归后重新生成配置与两份 CSV。

```powershell
mvn -o clean package
mvn -o "-Dtest=PonDbaShadowTest" test
python -B tools/run_pon_dba_shadow_test.py
```
