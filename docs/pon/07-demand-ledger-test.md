# PON REPORT–Grant 需求账本测试报告

在 `pon-framed` 模式设置 `fiber.uplink.demand.ledger.enabled=true` 即可启用 MFU 需求账本，默认关闭。账本基于实际收到的 REPORT 和 MFU 已创建的授权估计各 SFU 的待授权需求。FCFS 继续从原有队首产生授权。即时分帧和提前一帧 BWmap 都可提供预约记录，未启用 REPORT 时账本保持 `UNKNOWN`。

## 估计口径与事件顺序

对 SFU 的最新已接收快照，设 `s` 为原始快照时刻、`q` 为当前查询时刻。计算步骤如下。

1. 只纳入该 SFU 已创建且已记录的预约，从 `s` 至 `q` 两端都包含。帧事件先生成不可变 SFU 快照，再创建该帧授权，因此在 `s` 同刻创建的授权应纳入扣减。对于同一个查询时刻，尚未执行的预约仍不可见。
2. `grantedBytesSinceSnapshot = Σ grantBytes`。当前采用整包授权，以唯一的报文 ID 作为授权 ID。只在创建预约时入账，BWmap 到达、DATA 发送和 DATA 交付沿用这笔预约。
3. `effectiveKnownBytes = max(0, reportedPendingBytes − grantedBytesSinceSnapshot)`。`reportAgeNs = q − s`，从需求生成时刻起算。原始报告字节数及接收时间保留。
4. REPORT 接收后沿用现有的按原始帧号更新规则。新基准到达时按其原始时刻重新查询授权历史，连同报告在途期间的授权一起扣减。旧报告与重复报告沿用已有状态。授权历史不因切换基准而清空。

账本只存 MFU 自己接收的报告和生成的授权，按 SFU 独立维护时间有序的授权及累计字节数。帧边界观测在分配首条授权前记录，随后在预约创建和接收新报告时再次记录。首份 REPORT 到达前为 `UNKNOWN`，CSV 的估计、余额和年龄等字段保持空白。`KNOWN` 下报告为 0 B 与正需求已扣尽均可得到 0 B，可通过原始报告字节列区分。观测无需新增仿真事件。

## 专项场景实测

两台 SFU 共发送 8 包。帧周期 125 μs，上下行速率分别为 2500 和 10000 Mb/s，传播时延分别为 60 和 10 μs。手动业务使用零时延 Wi‑Fi，REPORT 与 BWmap 同时开启。SFU 1 的报文长 1200 B，SFU 2 的两包分别为 36000 B 和 38400 B。混合包长使 FCFS 在连续几帧中分批授权，报告会在下一批授权已创建后到达。

| 查询时刻（ns） | SFU | 报告原始帧 | 原始需求（B） | 已授权扣减（B） | 已知需求估计（B） |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 125000，首次调度 | 1、2 | — | — | — | UNKNOWN |
| 313840，报告到达 | 1 | 1 | 6000 | 2400 | 3600 |
| 375000，三次连续授权 | 1 | 1 | 6000 | 3600 → 4800 → 6000 | 2400 → 1200 → 0 |
| 432880，迟到报告到达 | 2 | 1 | 74400 | 74400 | 0 |
| 557880，新基准到达 | 2 | 2 | 38400 | 38400 | 0 |
| 563840，新基准到达 | 1 | 3 | 3600 | 4800 | 0 |

SFU 1 的首份报告在 125000 ns 生成，随下一帧首个时隙回传。125000 ns 同刻创建的两笔 1200 B 授权在接收时已入账。SFU 2 的首份报告同时扣减了 125000 ns 与 250000 ns 创建的两笔预约。其第 2 帧报告到达后，125000 ns 的旧预约保留在历史中，这次只纳入 250000 ns 的预约。SFU 1 有一包在 390000 ns 新入队，MFU 此时仍沿用旧报告的 0 B 估计。

专项脚本从 Java 输出核对 8 次唯一授权、23 个查询时点和 53 行对账。对账表按 `checkpoint_id` 分组，同一授权可出现在多个查询时点的核对记录中，在单次查询中最多贡献一次。

[估计轨迹 CSV](../../results/pon-demand-ledger/demand-ledger-trace.csv) · [逐授权对账 CSV](../../results/pon-demand-ledger/grant-reconciliation.csv) · [实际配置](../../results/pon-demand-ledger/test-config.properties)

## 自动化测试

| 用例 | 预期 | 实际 |
| --- | --- | --- |
| 6000 B 报告迟到与同刻授权 | 快照前预约排除，同刻和稍后各 1200 B 纳入，重放授权只计一次 | 接收前为 UNKNOWN。接收后已授权 2400 B、已知需求 3600 B，原始报告仍为 6000 B |
| 基准切换、旧报告与独立 SFU | 新基准排除快照前的历史预约，旧报告或重复报告无权覆盖新基准 | SFU 1 切换到 3600 B 报告，仅扣 1200 B，剩 2400 B。SFU 2 独立由 1000 B 扣 400 B，剩 600 B |
| 已报告零、已知耗尽与未知 | 已报告 0 B 和正报告扣尽可区分，UNKNOWN 的估计不可用，估计下限为零 | 依次分为报告 0 B 的 KNOWN、报告 1200 B 后耗尽的 KNOWN，以及无快照的 UNKNOWN。再授权后估计仍为 0 B |
| 实际 BWmap、报告在途与未报告业务 | 每包只在预约时入账，接收报告时立即扣减在途预约，新的本地报文须等报告 | 8 次实际授权、接收和发送各对应一笔入账。333840 ns 接收时估计为 2400 B，375000 ns 本地为 3400 B 而 MFU 仍为 2400 B。随后采用新的报告基准 |
| 混合上下行 EDCA 开关对照 | 开关对照保持 FCFS、BWmap、REPORT、DATA 和 ACK 的完整原轨迹 | 过滤新日志后逐行一致。4 次交付、4 次 ACK、3 次 BWmap 接收和 3 次 REPORT 接收 |
| REPORT 关闭 | 即使创建预约，MFU 无报告基准时维持未知 | 一笔预约入账，帧边界及授权后的状态均为 UNKNOWN |

`mvn -o clean package` 共 **181 项通过、0 项失败、0 项错误、0 项跳过**。其中 175 项来自既有测试，6 项为本阶段新增。[PonDemandLedgerTest.java](../../src/test/java/edu/suda/fttr/simulator/PonDemandLedgerTest.java)

## 第 6 步轨迹对照

专项脚本比较账本开关两侧的完整 Java 输出，仅过滤新增的需求状态及授权入账行。编译第 6 步提交 `387a38d` 后复跑其 35 包配置，当前开启账本的输出经过同样过滤，剩余 **466 行逐字节一致**。授权、BWmap、REPORT、DATA 交付和原有统计保持同一时序。[第 6 步验证报告](06-bwmap-test.md)

## 修改文件

| 文件 | 内容 |
| --- | --- |
| `Mfu.java`、`PonDemandLedger.java` | 连接现有报告状态，维护独立的 SFU 授权历史和有效已知需求估计 |
| `SimulationContext.java`、`Simulator.java`、`TraceLogger.java` | 只读观察授权创建、帧边界和报告接收，输出对账数据 |
| `ConfigLoader.java`、`SimulationConfig.java` | 增加分帧模式的账本开关 |
| `PonDemandLedgerTest.java`、`tools/run_pon_demand_ledger_test.py` | 自动化测试、实际轨迹对照及 CSV 导出 |
| `docs/pon/07-demand-ledger-test.md`、`results/pon-demand-ledger/` | 本报告、实际配置和两份 CSV |

## 简化与估计误差

估计值代表某次已送达快照所能支撑的需求认知。之后进入 SFU 的报文要等更新的 REPORT，传播、等待时隙及缺少捎带机会都会增加信息年龄。每条预约按整包有效字节扣减。新到达的业务随后获授权时，历史扣减可能超过旧报告字节数，估计下限为 0 B。真实 0 B 报告可由账本表达，但目前空队列报告通常缺少 DATA 时隙，需要由接收层测试验证这一输入。撤销授权、重发授权和 T-CONT 分流仍属于模型扩展范围。

## 复现

从仓库根目录运行。专项脚本读取真实 JAR 输出，按事件顺序核对报告和授权，再生成实际配置及两份 CSV。

```powershell
mvn -o clean package
mvn -o "-Dtest=PonDemandLedgerTest" test
python -B tools/run_pon_demand_ledger_test.py
```
