# 第 11 步：SFU 授权预算装载与只读预演验收

本次新增 SFU 本地完整报文装载组件 `PonGrantPacketFiller`。它只读取本地待授权 FIFO 的不可变数值视图，决定一个给定字节/时间窗口可以容纳哪些完整报文。真实 DATA 仍由原 FCFS 授权和发送；装载组件不自动参与帧回调，不创建事件、不出队、不扣减 MFU 账本、不改变链路统计。

完整构建实测 **227 项通过、0 项失败、0 项错误、0 项跳过**，包括原有211项和新增16项。Java专项入口生成17个场景和24条逐候选记录，15次合法预演、2次非法映射拒绝，结果符合预期。第10步完整127行输出保持一致。

## 1. 输入、输出与本地视图

```java
PonGrantPacketFiller filler = new PonGrantPacketFiller(
        rateMbps, allocationOwners);
PonGrantPacketFiller.Result result = port.previewGrant(
        filler, allocationId, grantBytes, grantDurationNs, slotStartNs, context);
```

组件构造参数是精确的上行速率和只读 `allocationId → sfuId` 映射。每次装载显式传入 allocationId、SFU ID、字节预算、时长预算、窗口起点与队列视图。独立调用 `fill(...)` 可重放同一历史视图；需要当前状态时使用 `Port.previewGrant(...)` 同步重新取样。

| 对象 | 内容与职责 |
| --- | --- |
| `PacketView` | 报文 ID、长度、真实光侧入队时间，不保存 Packet 或 Request 引用 |
| `QueueView` | SFU ID、捕获时间、按原 FIFO 顺序复制的 PacketView 列表 |
| `Result` | 所选 ID、总字节、逐包总时长、未用字节/时间、预演后剩余数量与原因 |
| `PacketCheck` | 每个候选的 FIFO 位置、单包时长、是否选择及拒绝原因 |

所有值对象字段不可变，集合与映射防御性复制。填充组件没有 MFU、需求账本、调度器或真实队列的引用，也没有对应写入接口。

SFU 的光侧 FIFO 实际保存在 `PonUplink.Port.queue`。现有 `reserveHead(...)` 在创建真实 FCFS 预约时立即移出队首，已预约报文另存在 `grants` 和 `awaitingBwmap` 中。新增 `ungrantedPacketView(...)` **只复制 queue**，不会把已预约、已收到 BWmap、正在发送的报文重新放回候选集。

一个历史快照不会随着后续预约而变化。它只能复核捕获时刻的状态，不能作为以后时刻的真实发送许可；当前可用内容必须重新通过 Port 取样。本次预演没有任何执行授权效力。

## 2. 严格 FIFO 与事件时间

按原列表顺序从队首检查完整报文，不排序、不拆包。第一条装不下的报文会阻塞本次窗口，后续小包只记录 `NOT_REACHED`，不尝试越过队首填空。

候选必须满足 `enqueueTimeNs <= snapshotTimeNs`。即使 `slotStartNs` 晚于捕获时间，也不会预知窗口开始前可能到达的新业务。独立诊断输入若含未来队首，会返回 `HEAD_NOT_ARRIVED`；生产 Port 的新鲜视图本身只包含已执行入队的报文。

预演是同步方法调用，不新增事件类型。同刻先后沿用现有 `EventQueue` 的时间、事件优先级和插入流水号。调用前已执行的入队可见，调用后发生的入队不可见；同刻报文保持真实入队顺序。原帧调度的 `enqueueNs < frameStartNs` 资格规则保持不变。该规则与本地视图的“已执行入队”判定属于不同层次。

Port 入口显式检查纳秒时钟及填充器速率与本地上行速率一致，避免把微秒刻度或不同链路速率混入装载计算。

## 3. 字节和时间双约束

```text
packetTxDurationNs = ceil(packetBytes × 8000 / rateMbps)
usedBytes + packetBytes <= grantBytes
usedDurationNs + packetTxDurationNs <= grantDurationNs

selectedDurationNs = sum(每个已选报文独立向上取整的发送时长)
unusedBytes = grantBytes - selectedBytes
unusedDurationNs = grantDurationNs - selectedDurationNs
remainingPacketCount = 视图报文数 - 已选报文数
```

单包时长直接复用 `PonLinkTiming.serializationNs(...)` 的 `BigDecimal` 精确计算与整数纳秒取整。装载先比较剩余预算再累加，避免 `used + packet` 中间溢出。负预算、负时长、非法映射、窗口起点早于捕获时间、窗口结束时间溢出均显式拒绝。

**逐包取整边界**：2500 Mb/s 下，1 B 的理论发送时间为3.2 ns，仿真单包为4 ns；两个1 B报文需8 ns。2 B聚合只取整一次是7 ns，所以2 B/7 ns授权不能装入两包。本组件保留1 B、4 ns结果及1 B、3 ns未用预算。

第10步预算校验针对一个字节预算计算理论窗口；本阶段进一步验证该窗口内具体完整报文的逐包时长。不会通过修改原计划、延长窗口或跨越队首来强行填满。

| fillReason | 含义 |
| --- | --- |
| `QUEUE_EMPTY` | 输入队列为空，全部预算未使用 |
| `BUDGET_EXHAUSTED` | 字节预算恰好耗尽，不能再装入报文 |
| `QUEUE_DRAINED` | 视图中的候选均已选完，预算仍有余量 |
| `HEAD_BYTES_EXCEED_BUDGET` | 剩余字节预算放不下当前队首 |
| `HEAD_TIME_EXCEEDS_BUDGET` | 字节预算足够，但剩余纳秒时长放不下当前队首 |
| `HEAD_NOT_ARRIVED` | 当前候选入队时间晚于视图捕获时间，不能提前选择 |

未用时间仅记录，本次不返还、不重分配给其他 SFU，也不据此延长、移动或缩短任何真实 FCFS 时隙。

## 4. allocationId 与扩展边界

映射由输入拓扑明确给出，不根据 allocationId 的数值猜测 SFU。当前生产命名沿用第10步的每SFU一个逻辑对象，例如 allocation 2 属于 SFU 1、allocation 4 属于 SFU 2。装载器校验授权 SFU 与本地视图拥有者均匹配拓扑映射。

接口允许不同 allocationId 关联同一 SFU，以便以后向指定本地队列传入视图。本次仍只有原来的每SFU一个光侧FIFO，不建立真正的T-CONT队列、服务等级或多个对象之间的调度策略。

## 5. 实际测试结果

执行 `mvn -o clean package` 通过全部 **227 项测试**。新增 `PonGrantPacketFillerTest` 的16项测试均通过，原有211项继续通过。CSV场景是专项Java入口逐项调用生产装载器的实际结果，不额外计入JUnit项数。

以下表格汇总实际 [grant-fill-trace.csv](../../results/pon-grant-fill/grant-fill-trace.csv)。字节/纳秒预算及本地队列的完整输入保存于 [test-config.properties](../../results/pon-grant-fill/test-config.properties)。

| 场景 | 预期 | 实际所选ID | 实际字节 / ns | 未用字节 / ns | 结果 |
| --- | --- | --- | --- | --- | --- |
| `three_of_four_1200` | 3600 B预算，从四个1200 B包中选前三包 | 1、2、3 | 3600 / 11520 | 0 / 0 | 通过，预算耗尽，余1包 |
| `partial_1500` | 两个1200 B包，1500 B预算只容纳一包 | 11 | 1200 / 3840 | 300 / 960 | 通过，队首字节不足 |
| `head_39062` | 38998 B预算不能容纳39062 B包 | 无 | 0 / 0 | 38998 / 124794 | 通过，队首字节不足 |
| `fifo_no_skip` | 大队首阻塞，不得选择后面1200 B包 | 无 | 0 / 0 | 38998 / 124794 | 通过，后包为NOT_REACHED |
| `empty_queue` | 空队列保留全部预算 | 无 | 0 / 0 | 3600 / 11520 | 通过，QUEUE_EMPTY |
| `per_packet_ns_rounding` | 2 B/7 ns只能装一条1 B报文 | 41 | 1 / 4 | 1 / 3 | 通过，HEAD_TIME_EXCEEDS_BUDGET |
| `independent_sfu_1` | SFU1独立选择本地两包 | 101、102 | 2400 / 7680 | 0 / 0 | 通过，SFU2队列不受影响 |
| `independent_sfu_2` | SFU2只选择本地第一包 | 201 | 1200 / 3840 | 0 / 0 | 通过，余1包 |
| `future_packet_not_arrived` | 快照100 ns不能选择500 ns到达包，即使窗口在1000 ns | 无 | 0 / 0 | 1200 / 3840 | 通过，HEAD_NOT_ARRIVED |
| `same_time_already_enqueued` | 快照100 ns可见此前已执行的同刻入队 | 61 | 1200 / 3840 | 0 / 0 | 通过，预算耗尽 |
| `queue_drained_with_budget_left` | 只有一包时不虚构剩余数据 | 71 | 1200 / 3840 | 2400 / 7680 | 通过，QUEUE_DRAINED |
| `zero_budget` | 零预算不选择数据 | 无 | 0 / 0 | 0 / 0 | 通过，余1包 |
| `repeat_preview_first` | 第一次预演选择一个1200 B包 | 91 | 1200 / 3840 | 300 / 960 | 通过 |
| `repeat_preview_second` | 相同输入再次预演，结果不变 | 91 | 1200 / 3840 | 300 / 960 | 通过，FIFO未消费 |
| `fcfs_reserved_packets_excluded` | 真实FCFS已预约1–32后，只能预演第33包 | 33 | 1200 / 3840 | 0 / 0 | 通过，视图不含已预约包 |
| `invalid_allocation` | allocation99不属于拓扑 | 无Result | — | — | 通过，实际抛出IllegalArgumentException |
| `allocation_sfu_mismatch` | allocation2不能交给SFU2 | 无Result | — | — | 通过，实际抛出IllegalArgumentException |

非法映射会在产生装载结果之前被拒绝，CSV选择量留空并记录实际异常，`status=REJECTED`。合法结果均为 `status=PREVIEW`，不表示实际DATA发送成功。逐候选CSV中的选中行 `reject_reason` 为空；首个阻塞点保留具体原因，后续行只记录 `NOT_REACHED`。

真实预约排除场景使用原 `PonUplink`，33条1200 B报文在0 ns进入FIFO。125000 ns边界的原FCFS为目标帧预约前32包；之后取得的新视图只有第33包，使用1200 B/3840 ns预算预演时只选中33。这里没有以新预算重做旧预约，也没有实际发送预演选择的第33包。

新增JUnit另外核验了以下集成与边界行为，均实际通过：

| 核验 | 预期 | 实际 |
| --- | --- | --- |
| 同刻FIFO身份顺序 | 不按报文ID排序 | 输入9、3、7，预算容纳两包，选择9、3 |
| 当前Port与两个SFU | 同步复制本地queue；预演不出队 | 预演前后队列ID列表相同，另一SFU独立 |
| 同刻入队与帧授权 | 本地已执行入队可见，原FCFS边界资格不变 | 边界新包可被本地预演观察；同刻FCFS只预约严格早于边界的旧包 |
| 不可变与重放 | 修改外部原列表不影响视图，不能修改返回集合 | 视图、selected ID与check列表均不可变；重复预演结果相同 |
| allocation映射 | 拒绝未知ID、错SFU、错视图；不依赖2×SFU公式 | 非法映射拒绝；101与102显式关联SFU1时均可独立预演 |
| 参数与窗口 | 负预算/时长、过去窗口、时钟溢出拒绝 | 均抛出异常，未静默修正 |
| 非法FIFO | 重复ID、入队时间逆序、零包长拒绝 | 均拒绝；正常同刻FIFO保留输入顺序 |
| Port时钟/速率 | 拒绝微秒时钟或错误速率 | 均拒绝；2500与2500.00的等值速率正常接受 |
| 逐事件只读集成 | 每个事件前后重复预演，不修改真实输出或账本 | 两SFU、4包完整FCFS/BWmap/REPORT/交付及汇总日志逐字一致，账本净需求/授权扣减不变 |

### 第10步和协议执行回归

专项脚本运行第10步原归档配置，分别重复运行默认、显式FCFS和shadow-rr。检查结果如下：

- 第9步六次原始决策与容量CSV全部字段一致。
- 第10步六次策略决策与只读时隙预览CSV全部字段一致，预算依次仍为0、0、38998、38998、39062、0 B。
- 默认与FCFS完整输出相同；shadow-rr去除其原有两类观测行后，实际事件和全部统计与默认相同。
- REPORT-only轮询、逐帧使用和MFU状态仍与已归档历史CSV一致。
- 两次独立Java装载导出的两份CSV和配置逐字节相同，专项Python脚本完整重跑两次也一致。

另从第10步提交 `c8b372a` 独立导出并用Java11重新编译源码，以同一配置运行。历史输出127行、当前输出127行，**无需过滤任何日志，逐行完全一致**。没有新增生产预演日志、事件或统计。

原有EDCA、上下行PON和混合业务回归全部通过。本次直接预演开关的逐事件对照运行在真实光侧Port测试中；生产Simulator未增加预演模式或改变无线流程。

## 6. 修改文件

| 文件 | 修改 |
| --- | --- |
| `src/main/java/edu/suda/fttr/link/PonGrantPacketFiller.java` | 独立装载逻辑、不可变视图/结果、双预算检查及逐候选诊断 |
| `src/main/java/edu/suda/fttr/link/PonUplink.java` | Port 新增当前未授权队列视图与只读预演方法 |
| `src/test/java/edu/suda/fttr/simulator/PonGrantPacketFillerTest.java` | 装载、输入边界、真实Port与事件回归JUnit |
| `src/test/java/edu/suda/fttr/simulator/PonGrantPacketFillCases.java` | 实际Java组件运行及两份CSV/实际配置导出 |
| `tools/run_pon_grant_fill_test.py` | 自动运行Java用例、重复性和第10步历史回归核对 |
| `results/pon-grant-fill/` | 装载结果、逐候选选择和实际测试配置 |
| `docs/pon/11-grant-packet-fill-test.md` | 本报告 |

未改动DbaPolicy、DbaPlan、DbaPlanValidator、MFU账本、原FCFS调度器、BWmap/REPORT执行流程或无线PHY/EDCA代码。Port新增方法只在显式测试调用时运行，没有挂入现有Simulator和帧事件回调。

## 7. 当前边界

本次选择结果只是本地候选集合，不表示这些报文已获真实DATA授权。`remainingPacketCount` 是在输入视图上假设选择后的数量，真实FIFO并未减少。所有预演累计量不进入真实吞吐量、忙时、利用率或REPORT需求统计。

队首39062 B放不进38998 B预算时，即使队尾有小报文也不会选择任何报文。没有报文分片、跨帧传输、未使用预算返还或重新分配。后续正式接管DATA前，还需单独建立预算到真实预约的提交、队列一致性和账本扣减机制。

## 8. 复现命令

从仓库根目录执行，使用JDK11或更高版本、Maven和Python3标准库。测试配置包含逐场景速率、帧周期、预算、窗口和报文列表，是专项Java入口保存的实际输入清单，不是新增的生产仿真配置模式。

```powershell
# 全部回归并构建JAR/test-classes
mvn -o clean package

# 第11步专项JUnit
mvn -o "-Dtest=PonGrantPacketFillerTest" test

# 实际Java装载用例、CSV生成、重复运行和第10步回归核对
python -B tools/run_pon_grant_fill_test.py

# 单独由Java装载入口重新生成两份CSV及配置
java -cp "target/test-classes;target/classes" edu.suda.fttr.simulator.PonGrantPacketFillCases results/pon-grant-fill
```

输出文件为 [grant-fill-trace.csv](../../results/pon-grant-fill/grant-fill-trace.csv)、[packet-selection-check.csv](../../results/pon-grant-fill/packet-selection-check.csv)、[test-config.properties](../../results/pon-grant-fill/test-config.properties)。CSV中的实际选择、时长与拒绝原因来自生产Java装载组件。Python只检查和复制输出，不替代Java选择报文。
