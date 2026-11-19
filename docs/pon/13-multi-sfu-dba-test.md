# 第13步：双SFU真实RR-DBA验收

`fiber.uplink.dba.mode=rr` 现支持1或2台SFU，每台SFU仍只有一个逻辑allocationId。每个目标帧最多创建一个DATA预算，两台SFU共享同一上行发送器；REPORT、有效需求、未结算状态和本地FIFO分别独立。

完整构建实测 **250项全部通过，0失败、0错误、0跳过**，包括原239项和新增11项。专项程序实际运行5个Java场景，生成 **34条逐帧决策、12笔真实预算、528条逐SFU状态/事件记录**。双方持续有需求时，真实授权顺序为 **1→2→1→2**。

## 1. 最小扩展原理

第12步MFU使用一个全局等待标志，只要存在未结算预算便停止所有DATA规划。本次把该状态移入每个不可变 `DbaContext.Allocation`：

```text
allocationId / sfuId
UNKNOWN 或 KNOWN
knownDemandBytes / REPORT frame / REPORT age
budgetOutstanding
```

原五参数构造保留，默认 `budgetOutstanding=false`，所以影子策略和原单对象调用继续兼容。新六参数构造独立携带授权状态，既不清空报告，也不把等待对象的需求改成0或UNKNOWN。

MFU在每个决策时刻，按SFU分别从已接收REPORT及需求账本构建完整、有序的对象列表。RR沿用原游标，只选择 **KNOWN、有效需求为正、没有未结算预算** 的对象。被跳过的对象仍留在列表中，避免成员变化使RR游标指向错误位置。正预算生成后推进游标，没有可选对象时保留游标。

若没有可选对象而有等待结算的对象，返回空计划 `AWAITING_BUDGET_SETTLEMENT`；这只是本轮无DATA授权的原因，不改变对象自身的KNOWN/UNKNOWN状态。UNKNOWN仍不产生普通DATA预算，已知0需求也独立跳过。

统一 `DbaPlanValidator` 额外拒绝给未结算对象发出正预算。实际链路同时显式检查每帧至多一个DATA计划、窗口不与之前预算重叠且不越过目标帧。其余容量和完整报文逻辑仍使用原组件：

```text
availableNs = framePeriodNs - controlReservedNs
capacityBytes = floor(availableNs × rateMbps / 8000)
budgetBytes = min(selectedSfuEffectiveDemand, capacityBytes)
budgetNs = ceil(budgetBytes × 8000 / rateMbps)
```

## 2. 拓扑、账本与共享发送资源

本阶段映射为 `allocation 2 → SFU1`、`allocation 4 → SFU2`，属于本地逻辑身份约定，不是真实Alloc-ID编码或T-CONT服务等级。`PonGrantPacketFiller` 的关联表改为按启动时注册的端口生成不可变副本，不再固定只有SFU1。

原 `PonDemandLedger.histories`、MFU最新REPORT和 `Port.queue` 已按SFU保存，本次无需改写账本算法或拆分队列：

- SFU1的预算创建、未用量释放和新REPORT只更新SFU1的历史。
- SFU2在SFU1等待结算期间仍可创建自己的未来预算。
- 预算ID继续由同一链路全局递增，D1、D2等不会因SFU不同而重复。
- 两台SFU可以同时存在未结算预算，但真实上行时隙仍全局串行。
- 每帧仍先预留REPORT-only控制窗口，再为一个SFU安排DATA窗口；未用预算不在同帧重新分配。

SFU收到其BWmap后，在指定起点使用本地FIFO和原装载器选择完整报文，再复用原逐包发送、传播、交付与窗口末结算。另一台SFU的队首大小不会用于修正本台预算，也不会被MFU直接读取。

## 3. 双方正需求的真实RR时序

主场景：2个SFU、2个STA，各STA固定关联一台SFU。四个20000 B上行报文在0 μs到达，每台SFU两包。帧125000 ns、上行2500 Mb/s、64 B控制占位205 ns、下行传播10000 ns、上行传播10025 ns，有限创建窗口到1250 μs。所有输入为手动确定性场景，不使用随机种子。

| 预算 | 决策 ns | 对象 / SFU | 预算B | DATA预约窗口 ns | 实际B | 未用B | MFU交付 ns | MFU结算 ns |
| --- | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: |
| D1 | 375000 | 2 / 1 | 38998 | 500205–624999 | 20000 | 18998 | 574230 | 635024 |
| D2 | 500000 | 4 / 2 | 38998 | 625205–749999 | 20000 | 18998 | 699230 | 760024 |
| D3 | 750000 | 2 / 1 | 20000 | 875205–939205 | 20000 | 0 | 949230 | 949230 |
| D4 | 875000 | 4 / 2 | 20000 | 1000205–1064205 | 20000 | 0 | 1074230 | 1074230 |

首次预算38998 B放不下两条20000 B完整报文，因此只装一包。实际DATA可以在窗口末之前交付，但未用窗口继续保留至预算结束，结算也按窗口结束加上行传播计算。

500000 ns时，SFU1仍为 **KNOWN=1002 B、budgetOutstanding=true**，SFU2为 **KNOWN=40000 B、budgetOutstanding=false**。本轮正确给SFU2创建D2；没有把SFU1伪装成0需求，也没有等待其635024 ns结算。

625000 ns时，两台SFU均等待结算，本轮空计划。此后依次选择SFU1、SFU2，形成1→2→1→2。起步阶段由于报告尚未送达，125000和250000 ns均为ALL_UNKNOWN，不提前授权。

## 4. 实际专项场景结果

| 场景 | 输入 | 预算数 | 实际交付 / 积压包数 | 实测结果 |
| --- | --- | ---: | --- | --- |
| `both_positive` | 每SFU两包20000 B | 4 | 4 / 0 | RR=1、2、1、2，完整FIFO顺序与时隙约束通过 |
| `outstanding_skip` | SFU1为38000+2000 B，SFU2为1200 B | 3 | 3 / 0 | SFU1等待且净需求1002 B时，SFU2仍在500000 ns获得预算 |
| `head_blocked` | SFU1队首39062 B，SFU2为1200 B，窗口625 μs | 2 | 1 / 1 | SFU1装载0 B，38998 B预算全未用；SFU2在639070 ns交付 |
| `known_zero` | SFU1无业务，SFU2为1200 B | 1 | 1 / 0 | 375000 ns时SFU1为KNOWN=0、SFU2仍UNKNOWN；只给SFU2 DATA预算 |
| `exact_boundary` | 2560 Mb/s，SFU1两包19968 B，SFU2为1200 B | 2 | 3 / 0 | SFU1预算正好在625000 ns帧尾结束，下一控制时隙同刻启动且无冲突 |

合计13个输入报文，12个交付、1个保留积压，报文守恒。每个预算的BWmap接收、装载、结束、结算均各一次；交付ID唯一，所有DATA与REPORT-only预约窗口全局不重叠。两份CSV和五份实际配置重复生成后逐字节一致。

**阻塞隔离**：SFU1队首39062 B遇到38998 B预算时不拆包、不越过队首。500000 ns时SFU1净需求64 B且仍等待结算，但SFU2可正常获得1200 B预算。SFU1在635024 ns结算后需求恢复为39062 B；有限窗口已停止创建新预算，因此保留这一包，不将其计为交付或丢弃。SFU2的已创建窗口仍继续执行到639070 ns交付。

**UNKNOWN与0 B**：UNKNOWN在CSV中使用状态加数值哨兵-1表示，不能按0 B解释。等待结算使用独立布尔列。迟到旧REPORT由JUnit实际调用MFU接收入口验证：新快照15 ns已接纳后，晚到的6 ns旧快照不能回退SFU2的1200 B已知需求，也不改变SFU1的0 B状态。固定传播实验没有伪造乱序接收结果。

## 5. 精确帧尾发现的问题与修复

检查多SFU共享链路时发现：如果最后DATA恰好在帧尾完成，下一帧控制开始事件可能更早插入队列。两者原来同优先级，控制开始会先看到仍未释放的旧DATA队首，误报资源冲突。

可复现场景使用2560 Mb/s：

```text
REPORT-only 64 B = 200 ns
19968 B DATA = 62400 ns
两包总DATA = 124800 ns
控制200 + DATA124800 = 125000 ns
DATA窗口 = [500200, 625000)
下一控制窗口从625000 ns开始
```

修复只针对真实DBA的DATA完成：事件类型仍为 `FIBER_TX_END`，保留原packet和sfuId，同时携带所属预算。事件队列仅对这种完成事件使用优先级-1，使其先完成/释放，再执行同刻下一窗口。原FCFS事件没有预算负载，排序仍保持原样；无线EDCA优先级和代码没有改变。

专项实测确认625000 ns的DATA完成日志先于625000 ns的下一控制开始日志，三个报文均唯一交付。窗口采用半开区间，精确相邻合法，不能为了避开此问题人为增加1 ns保护间隔。

## 6. 自动化测试与回归结果

新增 `PonMultiSfuDbaTest` 共11项，实际结果如下：

| 测试 | 预期 | 实测 |
| --- | --- | --- |
| 策略输入与统一校验 | blocked对象保持6000 B KNOWN，跳过并拒绝其正预算 | 通过；原5参构造默认未阻塞 |
| MFU双方已知需求 | RR选择1、2、1，授权1后不能全局阻塞2 | 通过；SFU1净需求6000 B仍保留 |
| 双SFU真实闭环 | 第二预算可在第一结算前创建 | 通过；两包分别经所属SFU交付，完整日志可重放 |
| 持续积压与FIFO | 80包、每SFU40包1200 B，真实预算1、2、1、2 | 通过；80包全部交付、每SFU原FIFO顺序不变 |
| 大队首阻塞隔离 | SFU1不能超发，SFU2继续交付 | 通过；无SFU1 DATA起发，SFU2正常交付 |
| 精确帧尾 | 625000 ns先完成旧DATA再启动控制 | 通过；没有资源冲突或重复释放 |
| UNKNOWN/0/迟到REPORT | 状态分别维护，旧快照不回退新状态 | 通过；SFU2更新不改变SFU1 |
| 独立预算账本 | 结算SFU1不释放或扣减SFU2 | 通过；重复创建/结算均幂等 |
| 错接BWmap与重复事件 | 错SFU接收拒绝，重复接收/装载/完成拒绝，结算不重复计数 | 通过；两个DATA各交付一次，另一账本不变 |
| 原模式 | 双SFU默认=FCFS，影子仅增加原观测行 | 完整真实事件输出一致 |
| 有限UNKNOWN窗口 | 没有已知需求时不授权，无无限空帧 | 通过；260230 ns结束，明确保留两包积压 |

`mvn -o clean package` **250项全部通过，失败0、错误0、跳过0**。原239项继续执行；第12步“rr拒绝sfu.count=2”的旧范围断言依新需求更新为“拒绝3”，其余前置条件检查保留。没有删除测试或跳过回归。

专项脚本同时重跑第12步四个已归档配置。只去除本次新增 `PON_DBA_OBJECT_STATE` 行后，第12步两份CSV的全部字段、原始日志、事件索引和对账值保持一致。原FCFS、shadow-rr、轮询归档与第9/10步决策也通过对照。

第12步原专项导出命令也增加相同的观测行过滤，保留原231条执行记录与4条对账值，避免用户重跑旧命令后把新增观测混入旧归档。过滤只发生在旧版CSV导出入口；第13步完整保留这些状态行，实际DATA与结算事件没有过滤。

本次新增观测行在真实rr每次规划前逐对象记录KNOWN/UNKNOWN、原净需求、报告元数据与未结算标志。它不修改队列、REPORT或账本。FCFS与shadow-rr不调用该真实规划入口，因此没有新增这些行。

## 7. 修改文件与复核材料

| 文件 | 修改 |
| --- | --- |
| `DbaContext.java` | 可选的不可变budgetOutstanding字段，旧构造兼容 |
| `RoundRobinDbaPolicy.java` | 稳定列表中逐对象跳过未结算者，保留RR游标规则 |
| `DbaPlanValidator.java` | 拒绝给等待结算对象追加正预算 |
| `Mfu.java` | 移除全局等待开关，按每SFU真实账本构建输入和观测 |
| `ConfigLoader.java` | rr允许1或2个SFU，保留依赖检查 |
| `PonUplink.java` | 按端口生成filler映射、单预算与不重叠守卫、真实DATA完成入口 |
| `Event.java`、`EventQueue.java`、`SimulationContext.java` | 携带预算的DATA完成顺序，仅作用于真实rr |
| `TraceLogger.java` | 新增逐对象状态日志 |
| `PonDbaExecutionTest.java` | 按当前范围更新旧非法拓扑用例为3个SFU |
| `PonMultiSfuDbaTest.java` | 新增11项JUnit |
| `tools/run_pon_multi_sfu_dba_test.py` | 实际Java运行、RR重建、窗口/守恒校核和导出 |
| `tools/run_pon_dba_execution_test.py` | 第12步旧导出入口过滤新增观测，保持历史复现兼容 |
| `results/pon-multi-sfu-dba/`、本报告 | 实际CSV、配置与验收记录 |

未修改 `DbaPolicy` 接口、`DbaPlan` 格式、`PonDemandLedger` 扣减算法、REPORT/BWmap结算字段、`PonGrantPacketFiller`、原FCFS调度器或无线PHY/EDCA核心。

### 输出字段说明

- [rr-grant-trace.csv](../../results/pon-multi-sfu-dba/rr-grant-trace.csv)：34条逐帧决策，包括没有授权的空计划。每行同时保留两台SFU的状态、净需求、等待标志，并汇合所选预算的BWmap接收、装载、实际发送、交付和结算结果。没有预算时对应字段留空，不伪造DATA。
- [per-sfu-ledger.csv](../../results/pon-multi-sfu-dba/per-sfu-ledger.csv)：528条按实际事件顺序记录的SFU状态、报告、账本和预算执行事件。每种事件只填它实际提供的字段，其他字段留空，不把另一SFU的值前向填充；原始日志完整保留。`PON_DBA_OBJECT_STATE` 给出决策时完整的授权状态。
- 五份properties均为实际传给Java的参数，不是手工填写的预期结果。所有场景使用手动输入，无随机数或随机种子。

## 8. 复现命令

从仓库根目录执行，使用JDK11或更高版本、Maven和Python3标准库。

```powershell
# 完整回归及JAR构建
mvn -o clean package

# 第13步专项JUnit
mvn -o "-Dtest=PonMultiSfuDbaTest" test

# 实际Java运行5场景，自动重建两份CSV及五份配置，并核对旧阶段
python -B tools/run_pon_multi_sfu_dba_test.py

# 单独运行双方正需求的主场景
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar results/pon-multi-sfu-dba/test-config.properties
```

实际配置：[主场景](../../results/pon-multi-sfu-dba/test-config.properties)、[等待结算隔离](../../results/pon-multi-sfu-dba/outstanding_skip.properties)、[队首阻塞](../../results/pon-multi-sfu-dba/head_blocked.properties)、[已知0与未知](../../results/pon-multi-sfu-dba/known_zero.properties)、[精确帧尾](../../results/pon-multi-sfu-dba/exact_boundary.properties)。

## 9. 当前边界

当前rr支持1/2个SFU，每SFU一个逻辑对象，每帧一个DATA预算、每SFU一个未结算预算。有限调度创建窗口继续使用 `poll.end.us`，窗口外积压显式保留。已知需求受REPORT延迟影响，RR公平性只针对当时已知且可授权的对象。

仍不实现同帧多DATA授权、T-CONT、GIANT、分片或未用预算当帧重分配。控制信息继续采用既有逻辑传输与传播时延抽象，没有新增真实DBRu/BWmap物理开销、测距或不同SFU传播时延。第12步其余建模限制继续适用，其历史文档中的“真实rr仅单SFU”范围由本阶段替代。
