# 第12步：真实 RR-DBA 最小授权与发送闭环

新增可选配置 `fiber.uplink.dba.mode=rr`。MFU 根据实际收到的 REPORT 和自身需求账本生成预算，SFU 收到真实逻辑 BWmap 后，在未来窗口内调用已有装载器选择并发送完整报文。默认 `fcfs` 与只读 `shadow-rr` 均保留。

本次完整构建 **239项测试通过，0失败、0错误、0跳过**，包括原有227项和新增12项。实际Java专项运行4个场景，输出 **231条闭环事件、4条真实预算对账记录**。单SFU、1200 B报文已完整交付；预算不足及逐包取整的未用量也经过执行与反馈验证。

## 1. 配置范围和执行窗口

```properties
fiber.uplink.dba.mode=rr
sfu.count=1
fiber.uplink.mode=pon-framed
fiber.uplink.report.enabled=true
fiber.uplink.bwmap.enabled=true
fiber.uplink.demand.ledger.enabled=true
fiber.uplink.poll.enabled=true
fiber.uplink.poll.end.us=625
```

当前真实模式限定单SFU，帧周期默认125 μs、上行默认2500 Mb/s。缺少REPORT、BWmap、账本或有限轮询时拒绝启动；`rr` 配置多个SFU时明确报错。旧模式可继续使用原有多SFU能力。

第12步复用 `poll.end.us` 作为真实 DATA **预算创建窗口的结束时间**。仅在该时刻之前的帧边界执行规划，已经创建的BWmap、DATA、轮询和结算继续执行至完成。这样头部大包持续装不下时不会无限产生空帧。窗口结束时尚未发送的报文保留在FIFO，并在 `summary=PON_DBA_EXECUTION pending_packets` 中明确列出，不将它们计为成功或已丢弃。

该模式适用于有界的最小闭环验收。后续长时间仿真可单独扩展DATA调度窗口与轮询窗口，当前不能把有限窗口内未交付的积压当作协议丢包率。

## 2. MFU与SFU职责分离

```text
REPORT-only 轮询 → MFU真正接收REPORT
  → 需求账本的已知有效需求
  → DbaContext / RoundRobinDbaPolicy / DbaPlanValidator
  → 创建独立DATA预算 D1，锁定一次需求
  → 逻辑BWmap下发并传播
  → SFU接收BWmap
  → 时隙起点用本地FIFO和PonGrantPacketFiller装载
  → 所选完整报文实际串行发送并交付MFU
  → 时隙末产生新REPORT及执行结算
  → 经过上行传播后，MFU幂等对账并接纳新REPORT
```

MFU规划入口没有SFU FIFO或远端报文长度参数。帧回调先预留目标帧的REPORT-only控制时间，再调用MFU规划；MFU复制当前已收REPORT/账本数值，复用现有策略和校验器。真实路径不使用原FCFS候选队首来纠正预算或选择报文。

SFU仅在预算窗口起点调用装载器，此时从其本地 `Port.queue` 取得新鲜视图。选择完整FIFO前缀后才实际移出所选报文，保留没有装入的队首。尚未到达的报文不会被提前选择。原光链路的逐包 `FIBER_TX_START/END`、传播与MFU接收继续复用。

当前每SFU一个逻辑allocationId，沿用本地映射 `allocationId=2×sfuId`。allocationId、sfuId和packetId仍是不同字段。真实预算使用独立long身份，日志标记为 **D1、D2**；轮询仍为 **R1、R2**，原FCFS逐包授权身份不变。

## 3. 预算、完整报文和实际占用

```text
availableNs = framePeriodNs - controlReservedNs
capacityBytes = floor(availableNs × rateMbps / 8000)
budgetBytes = min(effectiveKnownBytes, capacityBytes)
budgetNs = ceil(budgetBytes × 8000 / rateMbps)

selectedBytes = sum(装载器选择的完整报文长度)
selectedNs = sum(每个所选报文独立ceil后的发送时长)
selectedBytes <= budgetBytes
selectedNs <= budgetNs

actualBytes = sum(已完成发送的报文长度)
unusedBytes = budgetBytes - selectedBytes
unusedNs = budgetNs - selectedNs
```

预算通过统一校验后才产生BWmap。装载仍同时检查字节和逐包纳秒时长，不分片、不跳过队首。未用窗口不在本轮返还或重分配。

日志分别输出 `budget_bytes`、`selected_bytes`、`actual_bytes`、`unused_bytes` 和 `execution_state`。CREATED/RECEIVED阶段的实际发送量为0，未用量尚是装载前的占位值；LOADED阶段的actual只累计已经完成的发送；COMPLETED阶段满足 `budget=actual+unused`。最终对账CSV采用完成及MFU结算值，不把创建阶段的占位值当作最终未用量。

`reserved_ns` 表示预算预留，真实光链路忙时只累计DATA实际串行化与原REPORT-only占用。预算空置时不增加用户吞吐量或物理忙时。新执行汇总分别列出reserved、actual、unused；原 `PON_UPLINK_FRAMES` 汇总以帧窗口为分母，`PON_UPLINK` 仍以0到最后事件为观察窗口，两者分母不同。

真实模式用新决策、预算和执行事件展示窗口；没有将尚未装载的字节预算伪装成旧逐包FCFS授权。旧FCFS逐帧记录在旧模式下保持原样。

## 4. REPORT–预算账本对账

每个SFU同时最多有一个未结算预算。MFU等待反馈期间，后续帧返回 `AWAITING_BUDGET_SETTLEMENT`，不创建碎小重复预算；REPORT原始状态仍保留为UNKNOWN或KNOWN，没有将等待状态伪装成0 B队列。

设REPORT采样时间为S，查询时间为Q，预算创建/装载起点/结算接收时间为C/L/R，预算为B、实际发送为A。该真实预算对当前REPORT的扣减为：

```text
Q < C                     → 0
S >= L                    → 0       // 报告的待授权队列已不含装载出的报文
S < L 且尚未收到结算        → B
S < L 且 Q >= R            → A
effectiveKnownBytes = max(0, reportedPendingBytes - 各有效扣减之和)
```

因此，**预算创建后、装载前生成的新REPORT仍须扣除在途预算**，不能只检查 `createNs >= snapshotNs`。装载后生成的REPORT已经排除所选数据，则不能再次扣实际发送量。旧FCFS授权仍按原前缀历史计算，真实预算存在独立历史中。

MFU只在 `PON_DBA_SETTLEMENT_RX` 实际执行时读取SFU执行结果：先按grantId幂等结算，把预算扣减替换为实际量，再接纳时隙末的新REPORT。创建重复调用、BWmap接收、DATA完成均不重复扣预算；重复结算不增加REPORT接收计数，内容冲突的结算会拒绝。

新REPORT不是在预算创建时提前读取：SFU在窗口结束时取本地队列快照，接收时刻严格为 `slotEndNs + upPropagationNs`。控制反馈采用逻辑REPORT/执行结算，无额外物理编码和控制字节开销，这是本阶段的简化假设。即使DATA装载0包，也通过已预约窗口末的逻辑反馈告知未用量和缓存量；没有增加独立无限控制通道。

当前可传递的轮询快照在控制时隙起点采样，装载在后续DATA窗口起点执行；时隙末快照则发生在装载之后。这个采样次序支持上述时间边界。若将来允许同刻、不同事件顺序的其他REPORT，应增加采样水位/事件序号，而不能不加区分地复用本阶段时间判定。

## 5. 单SFU、1200 B关键实测时序

主配置为1个SFU/1个STA、手动UL、无线固定0延迟、125000 ns帧、2500 Mb/s上行、64 B轮询占位205 ns；下行传播10000 ns、上行传播10025 ns，预算创建窗口结束625 μs。

| 动作 | 实测时间 ns | 状态 |
| --- | ---: | --- |
| 报文进入本地光侧FIFO | 0 | 1200 B未授权 |
| 第一轮询BWmap下发 | 125000 | 目标帧2 |
| SFU轮询采样 | 250000 | pending=1200 B |
| MFU收到第一份REPORT | 260230 | 首次KNOWN=1200 B |
| MFU规划并创建D1 | 375000 | 预算1200 B、3840 ns，账本有效需求1200→0 |
| SFU收到DATA BWmap | 385000 | 只接收，不再次扣账本 |
| SFU装载并开始DATA | 500205 | 选择packet1，1200 B |
| DATA发送完成 | 504045 | actual=1200 B |
| 时隙末新REPORT | 504045 | 本地pending=0 B |
| MFU交付DATA | 514070 | packet1唯一交付 |
| MFU收到结算及新REPORT | 514070 | actual1200、unused0、known0 |

500000 ns轮询快照仍含1200 B，其在510230 ns到达MFU时，预算创建已早于该快照，但装载时间为500205 ns，所以账本仍正确扣1200 B、净需求保持0。500000 ns的下一次规划只返回等待结算，不会再授权同一需求。

DATA末事件使用原优先级0，新增预算结束事件使用优先级1，保证同刻先完成DATA、再生成执行结算。DATA交付和结算同刻到MFU时，DATA接收事件先执行。零传播专项测试也验证了该顺序。

## 6. 实际场景和预算对账

[grant-accounting.csv](../../results/pon-dba-execution/grant-accounting.csv) 按 `scenario_id + grant_id` 识别独立运行中的预算。下表数据来自实际Java事件：

| 场景/预算 | 预算B / ns | 实际B / ns | 未用B / ns | 创建前→创建后需求 | 结算前→结算后需求 |
| --- | --- | --- | --- | --- | --- |
| success_1200 / D1 | 1200 / 3840 | 1200 / 3840 | 0 / 0 | 1200→0 | 0→0 |
| head_blocked / D1 | 38998 / 124794 | 0 / 0 | 38998 / 124794 | 39062→64 | 64→39062 |
| partial_rounding / D1 | 2 / 7 | 1 / 4 | 1 / 3 | 2→0 | 0→1 |
| partial_rounding / D2 | 1 / 4 | 1 / 4 | 0 / 0 | 1→0 | 0→0 |

**队首阻塞**：39062 B单包本身在一完整帧内需要124999 ns；含205 ns控制预约的帧只给38998 B预算。SFU选择0包，不跳过或拆分；窗口500205–624999 ns保持预约，635024 ns结算后已知需求恢复为39062 B。本测试不在625 μs之后继续创建预算，最终明确保留1包积压。

**逐包取整**：两个1 B报文各需4 ns；2 B预算按聚合字节算出7 ns，只装入第一包。510237 ns结算后余1 B需求，625000 ns创建下一预算，750205–750209 ns发送第二包，760234 ns结算。最终预算合计3 B、实际2 B、未用1 B；DATA实际忙时8 ns，没有重复交付或重复扣减。

**UNKNOWN窗口**：把创建窗口设为250 μs，唯一规划时刻125000 ns尚未收到REPORT，输出ALL_UNKNOWN，不产生任何DATA预算。轮询报告仍在260230 ns正常送达后结束，待发送1包被明确记录。

[execution-trace.csv](../../results/pon-dba-execution/execution-trace.csv) 保留实际日志顺序、规范化纳秒时刻、REPORT采样、预算/装载/发送/交付及原始日志。无独立事件时刻的本地快照使用当前处理时钟，采样时刻另列，避免把补记的历史快照误当成时钟倒流。

## 7. 自动化测试与回归

| 新增JUnit内容 | 预期 | 实测 |
| --- | --- | --- |
| 默认与显式FCFS | 完整输出相同 | 通过，仍唯一逐包授权/交付 |
| 单包真实闭环与重放 | 1200 B足额授权、时序正确、每次只交付一次 | 通过，514070 ns交付，重复日志一致 |
| 不足预算 | 不超发、不分片、正确未用量和积压 | 通过，38998 B全未用，需求恢复39062 B |
| 装载前新REPORT | 创建后采样也要扣在途预算 | 通过，510230 ns仍扣1200 B、净需求0 |
| UNKNOWN与有限结束 | 未知不授权，无无限空事件 | 通过，260230 ns结束、保留1包 |
| 真实逐包取整及后续预算 | 一次装不完则下次继续，不能重复交付 | 通过，两包各交付一次，actual2 B、unused1 B |
| 缺失BWmap | 不得装载或发送 | 手动跳过RX，slotStart实际抛异常，FIFO仍保留1包 |
| 重复事件 | 重复BWmap/装载/完成拒绝，结算幂等 | 通过，收到报告数和DATA交付数不增加 |
| 账本历史和反馈可见性 | 重复创建去重，收到结算前不能提前按actual修正 | 通过，预算1200、actual600时，反馈前0需求、反馈后600需求 |
| 零传播同刻 | DATA交付先于结算 | 通过，同刻执行顺序正确 |
| 配置依赖及范围 | 缺少前置或多SFU rr配置明确报错 | 通过 |
| MFU创建入口边界 | UNKNOWN/超已知需求不能授权，重复创建不重复扣 | 通过，合法1200 B重复创建只扣一次 |

`mvn -o clean package` 合计 **239项，239通过，0失败，0错误，0跳过**。其中原227项包括既有PHY、EDCA、PON、REPORT、BWmap、影子规划和只读装载回归，均通过。

Python专项脚本实际运行4个Java场景，每个配置重复两次，核对字节/时长守恒、BWmap先于装载、时隙边界、唯一交付和唯一结算，再生成CSV。脚本同时复核旧默认/FCFS/shadow完整事件以及第9、10步决策和轮询归档。

另独立导出第11步提交 `aee08e1` 并以Java11重新编译，运行原两SFU影子场景。旧程序127行、新程序127行，**无需过滤任何行，完整输出相同**。原第11步专项脚本也重新执行通过，17个场景和24条报文选择结果保持不变。

## 8. 修改文件

| 文件 | 作用 |
| --- | --- |
| `ConfigLoader.java`、`SimulationConfig.java` | rr可选模式、依赖检查和单SFU范围 |
| `Mfu.java` | 仅MFU已知需求的真实规划、预算创建、结算与新REPORT接纳 |
| `PonDemandLedger.java` | 独立预算历史、未结算检查、延迟REPORT扣减和幂等反馈 |
| `PonDbaGrant.java` | 独立预算身份、接收/装载/完成状态与执行结果 |
| `PonUplink.java` | 有界真实帧调度、本地装载出队、复用逐包发送与预算末反馈 |
| `Event.java`、`EventType.java`、`EventQueue.java` | 预算事件负载和同刻DATA完成先于预算结束 |
| `SimulationContext.java`、`Simulator.java` | 新事件派发、MFU观测和执行日志入口 |
| `TraceLogger.java` | 决策、预算、执行/结算及分离后的统计 |
| `PonDbaExecutionTest.java` | 新增12项专项JUnit |
| `tools/run_pon_dba_execution_test.py` | 实际JAR专项运行、回归、CSV自动生成 |
| `results/pon-dba-execution/`、本报告 | 实际轨迹、账本对照及4份实际配置 |

未改动无线PHY/EDCA核心、DbaPolicy接口、RR算法、DbaPlanValidator、PonGrantPacketFiller或原FCFS调度器。新模式显式绕开FCFS DATA候选，旧模式继续走原路径。

## 9. 已知限制

- 单SFU、每SFU一个对象，每次最多一个未结算预算；当前RR只是统一RR算法在单对象上的最小执行验证，不代表多SFU公平性或GIANT DBA已经实现。
- REPORT、BWmap和执行结算仍为逻辑控制信息，不模拟真实DBRu/BWmap编码、保护间隔、突发前导和反馈字节开销。反馈必须经历配置的上行传播，没有即时窥读远端状态。
- 没有跨帧分片、未用窗口当帧重分配、T-CONT服务等级和复杂对象调度。预算不足的大包可能持续队首阻塞。
- 调度窗口结束会保留积压，不自动重试到无限时长。等待结算和下一帧会降低利用率，本阶段不做性能最优声明。
- 时间区间账本依赖本阶段明确的采样/装载次序。未来多对象、同刻其他报告或授权取消，应扩展控制水位与对账，而不能只增加算法名称。

## 10. 复现命令

从仓库根目录执行，使用JDK11或更高版本、Maven和Python3标准库。

```powershell
# 完整回归并构建实际JAR
mvn -o clean package

# 第12步专项JUnit
mvn -o "-Dtest=PonDbaExecutionTest" test

# 重跑4个Java场景，自动生成执行/对账CSV和实际配置
python -B tools/run_pon_dba_execution_test.py

# 单独运行1200 B真实闭环
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar results/pon-dba-execution/test-config.properties

# 上一阶段原专项回归
python -B tools/run_pon_grant_fill_test.py
```

实际配置：[1200 B闭环](../../results/pon-dba-execution/test-config.properties)、[队首阻塞](../../results/pon-dba-execution/head_blocked.properties)、[逐包取整](../../results/pon-dba-execution/partial_rounding.properties)、[UNKNOWN窗口](../../results/pon-dba-execution/unknown_window.properties)。两份CSV均由真实Java事件导出，没有手工填入实际发送或对账结果。
