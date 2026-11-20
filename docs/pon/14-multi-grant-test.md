# 第14步：同帧多SFU DATA授权验收

新增可选 `fiber.uplink.dba.mode=rr-multi`：在同一目标帧内按RR顺序为多个已知正需求、且没有未结算预算的SFU安排相邻DATA窗口。原 `rr` 仍每帧至多一个DATA预算，`fcfs` 和 `shadow-rr` 保留。

完整构建实测 **261项全部通过，0失败、0错误、0跳过**，包括原250项和新增11项。专项脚本从5个自然Java场景生成 **41条授权/空计划记录、49条帧使用记录**，其中15笔真实预算全部完成接收、装载、执行与结算。CSV主场景没有预载REPORT，所有报文和报告均走实际Simulator流程。

## 1. 配置和扩展范围

```properties
fiber.uplink.dba.mode=rr-multi
sfu.count=2
sta.count=2
sta.1.sfu.id=1
sta.2.sfu.id=2
```

与原真实rr相同，需要 `pon-framed`、BWmap、REPORT、需求账本和有限REPORT-only轮询。仍支持1或2台SFU，每SFU一个逻辑allocationId、最多一个未结算预算；没有T-CONT队列或多对象服务等级。

有限授权创建窗口继续使用 `fiber.uplink.poll.end.us`。窗口内产生的授权在窗口外仍会完整执行和结算，未发出的积压明确保留。原模式名称、默认配置和执行入口保持兼容。

## 2. RR多授权策略及逐笔容量

新增 `MultiGrantRoundRobinDbaPolicy implements DbaPolicy`，没有修改策略基础接口、DbaPlan结构或原单授权RR算法。

策略保留完整、稳定排序的allocation列表，从当前游标起最多扫描一轮。UNKNOWN、已知0需求和 `budgetOutstanding=true` 对象分别跳过，不修改其原始状态。每个对象每帧至多一笔预算，按顺序消费剩余纳秒容量：

```text
remainingNs = framePeriodNs - controlReservedNs

对每个可授权对象：
    capacityBytes = floor(remainingNs × rateMbps / 8000)
    plannedBytes = min(effectiveKnownBytes, capacityBytes)
    plannedNs = ceil(plannedBytes × 8000 / rateMbps)
    remainingNs -= plannedNs

controlReservedNs + sum(各笔独立取整后的plannedNs) <= framePeriodNs
```

容量中间值使用精确 `BigDecimal` / `BigInteger`，时长复用 `PonLinkTiming`。**不能只计算一次总字节容量，再忽略每笔向上取整造成的差异。**

游标在整轮扫描后移到最后一个获预算对象的下一位；全部对象均获得服务时自然完成一轮，容量被某对象耗尽时，下轮从它之后继续。没有授权时保持游标，不在循环中改写起点。

这是顺序使用剩余容量的简单RR，不做等份预算、复杂公平性或远端队首大小探测。第一台SFU的需求若已占满本帧，另一台在后续帧轮转；DATA实际没有填满预算时，本轮不重新分配未用窗口。

## 3. 校验结果到真实BWmap

MFU继续只读取已接收REPORT和自身账本：

```text
MFU已知需求 + 独立未结算标志
  → 新RR多授权策略产生DbaPlan集合
  → DbaPlanValidator校验完整计划并生成Slot集合
  → 将Slot的真实位置物化到各Grant.startOffsetNs
  → 共享链路逐笔创建独立BWmap预算
  → SFU本地装载、DATA发送、窗口末REPORT和结算
```

校验发生在真实授权创建之前。MFU使用统一校验器给出的Slot位置，不在链路层另写一套窗口排放算法。链路在 `rr-multi` 中按显式偏移创建各预算，同时保留不重叠、帧尾和每对象资格检查；`rr` 的单预算守卫仍保留。

每笔预算有独立D编号、目标SFU、字节/时长和状态。两笔在同一时刻创建时，分别更新所属SFU账本；BWmap接收、装载和DATA完成不重复扣减。结算继续以本预算实际量和本SFU窗口末快照更新，不读取另一台队列。

SFU复用原 `PonGrantPacketFiller`，只选择本地FIFO的完整前缀，受字节和逐包时间双约束。相邻窗口即使完全贴合，也不允许DATA跨出本预算范围。

## 4. 自然REPORT主场景：同帧两笔1200 B

使用125000 ns帧、2500 Mb/s、64 B控制占位205 ns，下行传播10000 ns、上行传播10025 ns，有限创建窗口1250 μs。无线采用固定0时延以单独核对光侧时序。四条手动UL业务分别为：

| packet | SFU / STA | 长度B | 生成μs |
| --- | --- | ---: | ---: |
| 1 | 1 / 1 | 38000 | 0 |
| 2 | 1 / 1 | 1200 | 0 |
| 3 | 2 / 2 | 1200 | 0 |
| 4 | 2 / 2 | 1200 | 600 |

初始轮询报告是异步到达的，不能假定开始时双方需求都已知。375000 ns的第一笔预算给SFU1，38998 B预算只装入38000 B，保留1200 B；635024 ns结算后MFU知道余量。SFU2的首笔1200 B预算只发出packet3，后到的packet4保留在本地，639070 ns结算后也为已知1200 B。

因此750000 ns时，两台SFU自然形成 **KNOWN=1200 B、无未结算预算**，同一计划为目标帧7产生两笔授权：

| 预算 | 对象 / SFU | 创建ns | BWmap接收ns | DATA窗口ns | 实际B | 交付ns | 结算ns |
| --- | --- | ---: | ---: | --- | ---: | ---: | ---: |
| D3 | 2 / 1 | 750000 | 760000 | 875205–879045 | 1200 | 889070 | 889070 |
| D4 | 4 / 2 | 750000 | 760000 | 879045–882885 | 1200 | 892910 | 892910 |

帧7的控制窗口是875000–875205 ns，之后DATA连续占用7680 ns：

```text
控制205 ns + DATA3840 ns + DATA3840 ns + 空闲117115 ns = 125000 ns
```

四条源报文最终各交付一次，共41600 B。新策略没有为了展示同帧而提前读取SFU FIFO、预载报告或增加独立控制通道。

## 5. 相邻结束与开始的事件顺序

沿用第13步的真实DATA完成优先级，未增加新的全局排序规则：

```text
上一笔真实FIBER_TX_END（优先级-1）完成并释放逐包发送资源
  → 下一笔PON_DBA_SLOT_START（0）在自己的SFU装载
  → 下一笔DATA起发（0）
  → 上一预算PON_DBA_SLOT_END（1）生成其自身SFU的末快照
```

上一预算的结束处理只读取它所属SFU的队列与预算状态。即使此时全局逐包发送队列已装入下一SFU的数据，也不会把它计入上一预算。0 B装载仍保留自己的预算结束与结算，不阻止其他已分配窗口。

新增JUnit还以2560 Mb/s、两个19968 B预算验证精确满帧：控制200 ns、每笔62400 ns，窗口为250200–312600和312600–375000 ns。两笔报告和结算分别对应自己的SFU，DATA先释放后才能启动相邻发送。缺失第二笔BWmap时，第一笔成功不能绕过第二笔接收检查。

## 6. 逐笔取整的真实容量边界

专项 `rounding_capacity` 使用2560 Mb/s。经自然报告与前序执行，750000 ns时两台SFU分别具有1 B和39935 B有效需求，可用DATA时间124800 ns：

| 计算 | 结果 |
| --- | ---: |
| SFU1预算1 B | ceil(3.125)=4 ns |
| SFU1之后剩余时间 | 124796 ns |
| SFU2最大预算 | floor(124796×2560/8000)=39934 B |
| SFU2预算时长 | ceil(39934×8000/2560)=124794 ns |
| 总DATA预约 | 124798 ns |
| 控制＋DATA预约 | 124998 ns |
| 帧尾未预约时间 | 2 ns |

若直接使用整帧39936 B容量给出1 B＋39935 B两笔预算，时长会成为4＋124797=124801 ns，比可用时间多1 ns。程序实际没有这样超配。

该帧SFU2本地队首39935 B放不进39934 B预算，因此装载0 B；124794 ns预留时间全未用，不在本帧返还。随后结算恢复需求，再于1125000 ns创建单独39935 B足额预算，1385022 ns完成交付。

## 7. 场景、测试与回归

五个实际Java场景的结果如下，所有输入都是确定性的手动业务，没有随机种子：

| 场景 | 真实预算数 | 交付 / 积压包数 | 关键结果 |
| --- | ---: | --- | --- |
| `same_frame_1200` | 4 | 4 / 0 | 目标帧7两笔1200 B相邻发送，分别独立交付和结算 |
| `outstanding_skip` | 3 | 3 / 0 | SFU1等待结算、需求仍为正时，SFU2继续获得预算 |
| `head_blocked` | 2 | 1 / 1 | SFU1大头装载0，SFU2仍正常交付；积压明确保留 |
| `rounding_capacity` | 5 | 4 / 0 | 两笔独立取整不越界，较小预算不能拆分队首，之后足额交付 |
| `known_zero` | 1 | 1 / 0 | KNOWN=0与UNKNOWN保持区别，零需求不获DATA |

每笔预算的BWmap接收、装载、完成、结算均只执行一次；实际DATA ID唯一。控制和DATA预约窗口全局不重叠，逐帧容量核验全部为True。

新增 `PonMultiGrantDbaTest` 共11项：

| 测试 | 预期与实测 |
| --- | --- |
| 双1200 B策略与预览 | 两笔3840 ns，窗口相邻且在帧内，通过 |
| 逐笔ceil边界 | 7 ns只能容纳一笔1 B/4 ns；8 ns可容纳两笔，下一轮游标正确，通过 |
| 状态跳过 | UNKNOWN、KNOWN0、未结算分别跳过，原数值不被改写，通过 |
| 容量耗尽游标 | 一笔耗尽帧后下轮选择另一SFU，通过 |
| MFU执行偏移 | 从校验器得到205、4045 ns偏移；规划不扣真实需求，通过 |
| 最简双包执行 | 已接收REPORT充分初态下，两包同目标帧实际发送、各交付一次，通过 |
| 精确满帧相邻窗口 | 2560 Mb/s下两个19968 B预算填满DATA容量，各自末快照正确，通过 |
| 自然REPORT同帧 | 主场景目标帧7同发两个1200 B；旧rr仍分别用目标帧7、8，通过 |
| 大队首阻塞隔离 | 一台0装载不导致另一台全局等待，通过 |
| 缺失BWmap与重复事件 | 漏掉第二笔接收不能发送；重复接收、装载、完成拒绝，结算幂等，通过 |
| 配置与旧模式 | 原默认/FCFS/shadow一致，缺少前置或3台SFU真实执行拒绝，通过 |

其中最简双包JUnit明确通过实际MFU接收方法建立“双方REPORT已收到”的充分初态，再执行真实帧、BWmap、装载、发送和结算；专项CSV采用上述完全自然Simulator场景，不使用这个初始化捷径。

`mvn -o clean package` **261项，261通过，失败0、错误0、跳过0**。原250项全部继续执行，没有修改或删除其断言。专项脚本重复实际Java运行，并核对第12、13步所有归档CSV字段、原始日志和事件索引，以及FCFS、shadow和轮询输出。两份CSV及五份配置重复生成后逐字节一致。

另从第13步提交 `5a245a7` 独立导出并以Java11重新编译，对照全部5份原rr配置的完整输出，无需过滤任何行：

| 原rr配置 | 旧 / 新行数 | 逐行对照 |
| --- | --- | --- |
| exact_boundary | 169 / 169 | 一致 |
| head_blocked | 121 / 121 | 一致 |
| known_zero | 138 / 138 | 一致 |
| outstanding_skip | 226 / 226 | 一致 |
| test-config | 246 / 246 | 一致 |

## 8. CSV统计口径

[grant-trace.csv](../../results/pon-multi-grant/grant-trace.csv) 共41行：每笔实际DATA预算一行，另保留空计划行 `EMPTY_PLAN`。字段包括目标帧、计划授权数、RR次序、SFU、预算/实际/未用量、窗口、BWmap、交付和结算，以及各SFU自身账本前后值。

[frame-usage.csv](../../results/pon-multi-grant/frame-usage.csv) 共49行，从各场景帧0记到最后目标帧。数据从实际REPORT-only事件、真实预算窗口和DATA开始/结束事件重建，不将预算直接当作物理忙时：

```text
unusedReservedNs = dataReservedNs - dataActualNs
unreservedIdleNs = framePeriodNs - controlReservedNs - dataReservedNs
physicalIdleNs = framePeriodNs - controlActualNs - dataActualNs
```

当前控制窗口全部完整执行，因此 `physicalIdle = unreservedIdle + unusedReserved`。不能把未用预算再额外加一次到物理空闲时间中。

主场景帧7的控制205 ns、DATA预约/实际均7680 ns，空闲117115 ns。取整场景帧7的控制200 ns、DATA预约124798 ns、实际仅4 ns：未用预约124794 ns、未预约2 ns、物理空闲124796 ns。传播时延不算上行发送器占用。

帧表的分母是完整125000 ns帧，不是0到最后接收事件的仿真窗口；逐帧累计已与原帧汇总的frame数、window、busy、idle核对。最后帧的发送/报告接收可能在帧末之前结束，仍应保留该完整帧的剩余空闲。

## 9. 修改文件

| 文件 | 修改 |
| --- | --- |
| `MultiGrantRoundRobinDbaPolicy.java` | 独立多授权RR策略、逐笔剩余时间计算 |
| `Mfu.java` | 启动时选择策略，完整校验后物化执行偏移 |
| `PonUplink.java` | 新重载启用多授权，逐笔按校验偏移创建预算；旧单授权守卫保留 |
| `ConfigLoader.java`、`SimulationConfig.java`、`Simulator.java` | rr-multi可选配置及启动接线 |
| `TraceLogger.java`、`SimulationContext.java` | 整帧多计划与逐预算决策日志，旧rr日志不变 |
| `PonMultiGrantDbaTest.java` | 新增11项JUnit |
| `tools/run_pon_multi_grant_test.py` | 实际Java场景、容量/守恒/回归核验和CSV导出 |
| `results/pon-multi-grant/`、本报告 | 两份CSV、五份实际配置及验收记录 |

未修改DbaPolicy基础接口、DbaPlan数据结构、DbaPlanValidator校验规则、原RoundRobinDbaPolicy、需求账本、装载器、预算状态机或无线EDCA核心。相邻时隙继续复用第13步已建立的事件顺序。

## 10. 复现命令与当前边界

从仓库根目录执行，使用JDK11或更高版本、Maven和Python3标准库。

```powershell
# 完整回归并构建JAR
mvn -o clean package

# 第14步专项JUnit
mvn -o "-Dtest=PonMultiGrantDbaTest" test

# 5个自然Java场景、CSV生成及所有旧模式归档对照
python -B tools/run_pon_multi_grant_test.py

# 单独运行同帧双1200 B主场景
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar results/pon-multi-grant/test-config.properties
```

实际配置：[主场景](../../results/pon-multi-grant/test-config.properties)、[等待结算隔离](../../results/pon-multi-grant/outstanding_skip.properties)、[队首阻塞](../../results/pon-multi-grant/head_blocked.properties)、[逐笔取整容量](../../results/pon-multi-grant/rounding_capacity.properties)、[KNOWN0与UNKNOWN](../../results/pon-multi-grant/known_zero.properties)。所有参数均是实际传给Java的配置。

本阶段仍只支持1/2台SFU、每SFU一个对象。RR按已知需求顺序消耗剩余容量，并不保证每帧一定有两笔授权；异步REPORT、未结算状态和容量不足都可能使计划仅一笔或为空。有限窗口与逻辑控制信息的简化继续沿用前阶段，不实现T-CONT、GIANT、分片、复杂公平性或未用预算当帧重分配。
