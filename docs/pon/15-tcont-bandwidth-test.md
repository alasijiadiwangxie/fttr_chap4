# 第15步：T-CONT / Alloc-ID逻辑队列与带宽参数验收

本阶段将真实 `rr` / `rr-multi` DBA 从“每台SFU一个逻辑对象”扩展为“每台SFU可有多个Alloc-ID”。各逻辑对象分别维护FIFO、REPORT、MFU已知需求、未结算预算与执行结果，仍共用同一条PON上行链路。

完整 Maven 回归 **281 项全部通过**，含原有 261 项和新增 20 项。专项脚本运行 7 个 Java 场景，导出 2211 条事件和 28 条参数校验记录。T-CONT 类型、SI 和 AB 以不可变参数传入 `DbaContext`，实际 DATA 授权继续采用 RR。

## 1. 逻辑队列与物理设备的职责

`sfuId` 标识物理SFU，`allocationId` 标识MFU授权和SFU缓存对应的逻辑对象。两者是独立字段，不能根据编号相等、倍数或除法推断启用T-CONT后的归属。一台SFU可同时拥有101、102等多个对象；它们具有独立FIFO，但没有独立的2.5 Gbps物理链路。

启动时，`TcontTopology` 保存显式的所有权和业务映射。每台已配置SFU的VO、VI、BE、BK必须恰好映射一次：同一业务不能落入两个对象，四类业务也不能遗漏。列表按SFU ID、Alloc-ID排序，避免依赖哈希容器遍历顺序。

```text
STA生成UL报文
  → 原无线PHY / MAC流程成功交给SFU
  → 按(sfuId, packet.type)查找allocationId
  → 对应SFU端口下的独立逻辑FIFO
  → 获得并收到该Alloc-ID的BWmap预算
  → SFU在本对象FIFO装载完整报文
  → 共享PON上行串行发送
  → MFU交付与本对象预算结算
```

业务类型只用于选择光侧逻辑FIFO，不修改原无线AC队列、EDCA优先级或STA–SFU关联。不同AC可以映射到同一个T-CONT；进入同一逻辑FIFO后，按实际光侧入队顺序发送，不在其中重新按无线优先级排列。

默认 `fiber.uplink.tcont.enabled=false`，继续使用原单FIFO模式与稳定编号 `allocationId = 2 × sfuId`。只有关闭T-CONT的兼容路径使用该编号规则。

## 2. 配置与参数校验

新增键如下。`<id>` 是全局唯一的正 `long`，不是SFU下标。

| 配置键 | 含义与限制 |
| --- | --- |
| `fiber.uplink.tcont.enabled` | 默认为false；只接受true或false |
| `fiber.uplink.tcont.alloc.ids` | 启用时的完整Alloc-ID列表，不允许重复、零或负数 |
| `fiber.uplink.tcont.alloc.<id>.sfu.id` | 所属SFU，必须在当前配置拓扑内 |
| `fiber.uplink.tcont.alloc.<id>.type` | 当前支持2、3、4 |
| `fiber.uplink.tcont.alloc.<id>.classes` | 非空VO/VI/BE/BK列表，SFU内必须完整覆盖且不重叠 |
| `fiber.uplink.tcont.alloc.<id>.si.max.frames` | SImax，以整数上行帧数表示 |
| `fiber.uplink.tcont.alloc.<id>.si.min.frames` | SImin，以整数上行帧数表示 |
| `fiber.uplink.tcont.alloc.<id>.ab.min.bytes` | ABmin，整数Byte |
| `fiber.uplink.tcont.alloc.<id>.ab.sur.bytes` | ABsur，整数Byte |

启用T-CONT要求真实 `rr` 或 `rr-multi` 模式，并沿用其 `pon-framed`、BWmap、REPORT、需求账本及有限REPORT-only轮询前提。`rr` 每目标帧仍至多一笔DATA预算；`rr-multi` 可以在同一帧中服务多个逻辑对象，包括属于同一SFU的不同对象。

当前不将多队列路径接入 `fcfs` 或 `shadow-rr`。这些模式在T-CONT关闭时保留原行为；显式开启不受支持的组合会报错。关闭T-CONT却保留其他T-CONT参数也会报错，避免参数被静默忽略。只有启用T-CONT的新拓扑路径解除此前真实RR最多两台SFU的限制。

下面是两台SFU、三个逻辑对象的配置示例。测试将无线设为固定0时延，以单独核对光侧事件；这不改变生产无线机制。

```properties
traffic.direction=UL
traffic.mode=manual
sfu.count=2
sta.count=2
sta.1.sfu.id=1
sta.2.sfu.id=2
wifi.model=fixed
wifi.delay.us=0
wifi.mac.access=fixed-priority

packet.bytes=1200
packet.arrival.us=0,0,0
packet.1.sta.id=1
packet.1.type=VO
packet.2.sta.id=1
packet.2.type=BE
packet.3.sta.id=2
packet.3.type=BE

fiber.downlink.mode=pon-shared
fiber.downlink.rate.mbps=10000
fiber.downlink.propagation.us=10
fiber.uplink.mode=pon-framed
fiber.uplink.rate.mbps=2500
fiber.uplink.frame.period.us=125
fiber.uplink.propagation.us=10.025
fiber.uplink.report.enabled=true
fiber.uplink.bwmap.enabled=true
fiber.uplink.demand.ledger.enabled=true
fiber.uplink.poll.enabled=true
fiber.uplink.poll.bytes=64
fiber.uplink.poll.interval.frames=1
fiber.uplink.poll.end.us=2500
fiber.uplink.dba.mode=rr-multi

fiber.uplink.tcont.enabled=true
fiber.uplink.tcont.alloc.ids=101,102,201

fiber.uplink.tcont.alloc.101.sfu.id=1
fiber.uplink.tcont.alloc.101.type=2
fiber.uplink.tcont.alloc.101.classes=VO,VI
fiber.uplink.tcont.alloc.101.si.max.frames=1
fiber.uplink.tcont.alloc.101.si.min.frames=0
fiber.uplink.tcont.alloc.101.ab.min.bytes=2400
fiber.uplink.tcont.alloc.101.ab.sur.bytes=0

fiber.uplink.tcont.alloc.102.sfu.id=1
fiber.uplink.tcont.alloc.102.type=3
fiber.uplink.tcont.alloc.102.classes=BE,BK
fiber.uplink.tcont.alloc.102.si.max.frames=1
fiber.uplink.tcont.alloc.102.si.min.frames=2
fiber.uplink.tcont.alloc.102.ab.min.bytes=2400
fiber.uplink.tcont.alloc.102.ab.sur.bytes=1200

fiber.uplink.tcont.alloc.201.sfu.id=2
fiber.uplink.tcont.alloc.201.type=4
fiber.uplink.tcont.alloc.201.classes=VO,VI,BE,BK
fiber.uplink.tcont.alloc.201.si.max.frames=1
fiber.uplink.tcont.alloc.201.si.min.frames=1
fiber.uplink.tcont.alloc.201.ab.min.bytes=0
fiber.uplink.tcont.alloc.201.ab.sur.bytes=1200
```

该示例解释字段和映射。专项脚本实际生成并运行的各场景配置保存在 `results/pon-tcont/`，最终验收以保存的实际配置和Java事件为准。

## 3. Type 2/3/4元数据的单位与边界

SI为上行帧数，默认一帧125 μs；AB为该服务间隔的整数Byte，而非bit/s。参考论文将SImax/ABmin用于assured阶段，将SImin/ABsur用于surplus或best-effort阶段。Type 2为assured，Type 3同时包含assured与surplus，Type 4为best-effort。[IFIP ONDM 2021论文，第III节、表III/IV](https://dl.ifip.org/db/conf/ondm/ondm2021/1570714811.pdf)

本实现采用以下明确的本地支持范围：

| 类型 | SImax | SImin | ABmin | ABsur | 当前解释 |
| --- | ---: | ---: | ---: | ---: | --- |
| Type 2 | ≥1 | =0 | >0 | =0 | 仅保存assured参数，不使用剩余阶段 |
| Type 3 | ≥1 | ≥1 | >0 | >0 | 分别保存assured与surplus参数 |
| Type 4 | ≥1 | ≥1 | ≥0 | >0 | SImin/ABsur为BE参数；SImax/ABmin为本地统一profile预留字段 |

Type 2的 `si.min.frames`、`ab.sur.bytes` 可省略，明确按0处理；其他适用字段要求显式配置。SI必须在Java正整数范围内，AB使用非负 `long` 范围并进一步按类型校验。

**Type 4的SImax/ABmin预留字段属于本项目的统一参数模型，不是上述文献表III/IV定义的Type 4带宽保证规则。** ABmin即使取正数，也不表示Type 4已经获得assured DATA带宽。当前轮询仍由独立的 `fiber.uplink.poll.*` 控制，不由该预留字段驱动。

不增加 `SImin <= SImax` 或 `ABsur >= ABmin` 约束：两个SI与两个AB分属不同服务角色，名字不构成数值排序依据。主文献的Type 3示例也存在ABmin大于ABsur的情况。上述支持范围只说明当前参数解析和元数据模型，不声明完整实现IEEE/ITU-T标准或GIANT。

## 4. 按Alloc-ID隔离REPORT、账本与真实预算

每份逻辑REPORT显式保存allocationId和sfuId，内容仅来自对应SFU本地FIFO。帧边界快照仍是本地观测，不直接更新MFU；只有合法的REPORT接收事件才改变MFU已知状态。

MFU按Alloc-ID分别保存最新已收报告。首份到达前为UNKNOWN；收到0 B报告后为KNOWN=0，不能混同。较旧或重复报告不覆盖新快照，同一SFU其他对象的报告也不改变本对象状态。

需求估计继续使用REPORT–Grant账本，而非读取远端FIFO：

```text
effectiveKnownBytes = max(0, reportedPendingBytes - applicableGrantDebitBytes)
```

其中保留原有真实预算语义：创建时锁定预算；在SFU实际装载之前生成的报告可能仍包含这些报文，账本必须继续计入相应在途预算。装载后快照已排除选中的数据，不能再次扣减。MFU收到结算之前使用预算量，结算之后才知道实际发送量和未用量。原始REPORT保持不变，授权创建、BWmap接收、DATA完成与结算不能各扣一次。

每个Alloc-ID最多有一笔未结算DATA预算。等待结算是独立标志，不将需求伪装为0或UNKNOWN；同一SFU另一个对象仍可参与RR。BWmap和预算身份分别核对allocationId与sfuId，不能使用另一逻辑FIFO的视图装载。

REPORT-only 轮询按配置拓扑中的逻辑对象顺序轮转，包括没有关联 STA 的 SFU。一次轮询仍使用一个控制窗口。控制授权携带 Alloc-ID，SFU 在该窗口起点生成快照，MFU 在控制时隙结束并经过传播后接收。64 B 占位在 2500 Mb/s 下为 205 ns，计入共享链路忙时。

DATA窗口开始时，`PonGrantPacketFiller` 只选择该对象FIFO的完整前缀：

```text
selectedBytes + packetBytes <= grantBytes
selectedDurationNs + ceil(packetBytes × 8000 / rateMbps) <= grantDurationNs
```

逐包纳秒向上取整使用原 `PonLinkTiming`。队首放不下时不跳过、不分片；其他Alloc-ID仍能使用自己的预约窗口。已装载报文原子移出本FIFO，后续快照不能再次包含它们。未用预算只记录，不在本帧自动重分配。

## 5. 同一SFU相邻窗口的事件顺序

不增加另一套光侧时钟。时隙使用整数纳秒和半开区间 `[startNs, endNs)`；所有控制与DATA窗口共用一条上行发送资源，传播阶段不继续占用发送器。

沿用已建立的同刻优先级：

```text
前一报文FIBER_TX_END（真实DBA DATA，优先级-1）
  → 从共享逐包发送队列移除，并登记完成字节
下一预算PON_DBA_SLOT_START（优先级0）
  → 在它所属allocationId的FIFO装载并开始后续DATA
前一预算PON_DBA_SLOT_END（优先级1）
  → 对前一allocationId单独取窗口末快照并安排结算
```

下一对象即使与前一对象属于同一物理SFU，也只能修改自己的FIFO；前一窗口末快照不得包含下一对象的数据。日志可能显示下一预算已装载、前一预算才输出窗口末快照，这是确定性的处理顺序，不代表前一DATA仍占用信道。

计划校验器继续验证独立对象身份、正预算不超过已知需求、逐笔时长及帧容量；同一帧不能重复给同一个对象创建预算。对不同对象的相邻授权不再施加“一台SFU只能一个窗口”的错误限制。

## 6. 测试设计与实测状态

`TcontBandwidthTest` 直接调用实际配置器、不可变模型、RR策略、统一校验器和Simulator。专项脚本从真实Java运行日志重建CSV；参数CSV另经Java `ConfigLoader` 实际加载，不将Python中的配置文本当作Java已接受的参数。

| 测试内容 | 预期结果 | 实际结果 |
| --- | --- | --- |
| 同 SFU 两个 Alloc-ID 入队 | 各 FIFO 保持入队顺序 | 101 依次发送报文 1、3，102 依次发送 2、4 |
| REPORT、预算和结算隔离 | 按逻辑对象更新，拒绝错误归属 | 101 与 102 首份 REPORT 分别为 1800 B 和 2000 B，独立结算至 0 B。重复扣减、迟到报告及错误归属的测试通过 |
| 同 SFU 两个对象同帧授权 | 相邻窗口互不重叠 | 目标帧 7 同时安排 101 和 102，各完成 1200 B |
| 两 SFU 三个对象 | 共用同一条上行链路 | 101、102、201 共交付 5 包，时隙无冲突 |
| 三 SFU 四个对象 | 新配置可扩展到 3 台 SFU | 101、102、201、301 各交付 1 包，时隙无冲突 |
| 队首阻塞 | 保持 FIFO，另一个对象仍可发送 | 101 的 39062 B 队首超出 38998 B 预算，两包保留。102 独立交付报文 3 |
| UNKNOWN、KNOWN=0 和未结算 | 按对象区分状态与分配资格 | 断言通过，一个对象等待结算时，另一个已知正需求对象可获授权。无 STA 的已配置 SFU 能返回 0 B REPORT |
| Type 2/3/4 加载 | SI 和 AB 按声明值加载 | Type 3 接受 SImax=1、SImin=2、ABmin=2400 B、ABsur=1200 B |
| 非法配置 | Java 配置器拒绝错误映射、类型和参数组合 | 11 个非法配置全部拒绝 |
| 不可变上下文及装载视图 | 源集合变更不影响快照，装载匹配 Alloc-ID | 断言通过。跨对象传入 FIFO 视图会被拒绝 |
| SI/AB 参数变更与确定性 | RR 时序一致，相同输入日志可重放 | DATA、BWmap、REPORT 与结算轨迹一致，同配置 Java 日志相同 |
| 旧配置与归档对照 | 旧单对象路径兼容 | 第 14 步 41 条授权及空计划记录、49 条帧统计逐字段一致。此前 RR、FCFS、shadow、REPORT、BWmap 对照通过 |

本阶段验收统计：

| 运行阶段 | 测试数 | 通过 | 失败/错误/跳过 | 状态 |
| --- | ---: | ---: | --- | --- |
| 首轮增量（15 项专项、64 项关键回归） | 79 | 78 | 0 / 1 / 0 | 一项专项的日志筛选遇到无事件名的统计行 |
| 修正筛选并补齐边界验证后复测 T-CONT | 20 | 20 | 0 / 0 / 0 | 通过 |
| 最终完整 Maven 回归 | 281 | 281 | 0 / 0 / 0 | 构建成功 |

开发阶段只运行新增专项与受影响关键回归；功能完成后运行一次完整Maven构建。历史场景对照使用当前构建重放已保存配置和归档结果，不重新编译历史Git版本。

## 7. CSV与复核材料

输出目录为 `results/pon-tcont/`：

| 文件 | 来源与用途 | 实际计数/结论 |
| --- | --- | --- |
| `tcont-trace.csv` | 逐对象入队、REPORT、授权、装载、DATA、交付与结算 | 2211 条事件，其中 22 笔 DATA 预算均结算 |
| `bandwidth-profile.csv` | Java 实际加载的配置及拒绝原因 | 17 条有效参数记录，11 条拒绝记录 |
| `test-config.properties` | 实际运行的两 SFU 三对象场景 | 2500 Mb/s，125 μs 帧，上下行传播分别为 10.025 μs 和 10 μs |
| 各场景 `.properties` 和本地 `*-events.log` | 保存专项脚本输入及原始 Java 事件 | 7 个有效场景，11 个非法配置。手动报文源无需随机种子 |
| 本地 `profile-validation.log` | Java 配置加载辅助入口输出 | 7 个配置接受，11 个配置拒绝 |

预算、实际发送和未用量必须分别核对；不能把授权预算全部视为DATA吞吐量或发送器忙时。REPORT-only控制时间计入共享资源占用，传播时间不计入发送器占用；各Alloc-ID的数据发送计数加总必须与唯一MFU交付结果一致。

在 `same_sfu_same_frame` 场景中，MFU 于 750000 ns 创建目标帧 7 的两笔授权。SFU 1 于 760000 ns 收到 BWmap，先使用 875000–875205 ns 的轮询窗口，再按下表发送 DATA。

| Alloc-ID | 预算 | DATA 时隙（ns） | 完成字节 | MFU 结算（ns） |
| --- | --- | --- | ---: | ---: |
| 101 | D3 | 875205–879045 | 1200 | 889070 |
| 102 | D4 | 879045–882885 | 1200 | 892910 |

该帧使用 205 ns 控制时间和 7680 ns DATA 时间，剩余 117115 ns。7 个场景共入队 28 包，交付 26 包且无重复。剩余两包来自预期的队首阻塞场景。22 笔预算均满足实际发送字节加未用字节等于预留字节。

## 8. 修改文件与职责

| 文件 | 本阶段修改 |
| --- | --- |
| `src/main/java/edu/suda/fttr/dba/TcontProfile.java` | 不可变类型、业务映射与SI/AB参数及本地合法性检查 |
| `src/main/java/edu/suda/fttr/dba/TcontTopology.java` | 显式所有权、SFU业务映射、稳定遍历及旧编号兼容 |
| `src/main/java/edu/suda/fttr/dba/DbaContext.java` | Allocation增加不可变profile，保留旧构造 |
| `src/main/java/edu/suda/fttr/config/ConfigLoader.java` | T-CONT配置、类型参数、拓扑和模式依赖校验 |
| `src/main/java/edu/suda/fttr/config/SimulationConfig.java` | 保存已校验逻辑拓扑 |
| `src/main/java/edu/suda/fttr/entity/Mfu.java` | 按Alloc-ID保存已收REPORT、构造策略输入与处理预算结算 |
| `src/main/java/edu/suda/fttr/entity/PonDemandLedger.java` | 各对象独立授权历史、需求扣减与未结算预算 |
| `src/main/java/edu/suda/fttr/link/PonUplink.java` | 每端口多FIFO、按对象快照/装载、轮询与共享资源执行 |
| `src/main/java/edu/suda/fttr/link/PonUplinkReport.java` | REPORT显式携带Alloc-ID，保留旧构造和日志标志 |
| `src/main/java/edu/suda/fttr/link/PonReportPoll.java` | 控制授权显式关联逻辑对象并校验快照身份 |
| `src/main/java/edu/suda/fttr/link/PonDbaGrant.java` | 预算完成时核对同一对象的快照 |
| `src/main/java/edu/suda/fttr/link/PonGrantPacketFiller.java` | FIFO只读视图增加Alloc-ID及装载归属检查 |
| `src/main/java/edu/suda/fttr/simulator/Simulator.java` | 启动拓扑接线、按对象观测帧边界需求 |
| `src/main/java/edu/suda/fttr/simulator/SimulationContext.java`、`src/main/java/edu/suda/fttr/trace/TraceLogger.java` | 新对象日志与旧模式格式兼容 |
| `src/test/java/edu/suda/fttr/simulator/TcontBandwidthTest.java` | 新专项JUnit与Java配置加载辅助入口 |
| `tools/run_pon_tcont_test.py` | 实际Java场景、身份/守恒/容量/兼容性核验和CSV导出 |
| `results/pon-tcont/`、本报告 | 可重复生成的配置、原始日志、CSV与验收说明 |
| `README.md` | T-CONT 入口与参数验收说明 |

继续复用 `DbaPolicy`、`DbaPlan`、`DbaPlanValidator`、原单授权/多授权RR和纳秒级链路公式。本阶段没有新增GIANT算法，没有修改无线PHY、EDCA、TXOP或NAV核心。

## 9. 复现命令

从仓库根目录执行，环境要求JDK11或更高版本、Maven，以及Python 3标准库。`-o` 依赖本机已缓存所需Maven依赖。

```powershell
# 开发阶段：只跑新增T-CONT专项
mvn -o "-Dtest=TcontBandwidthTest" test

# 开发阶段：仅运行 64 项受影响的关键回归
mvn -o "-Dtest=PonMultiGrantDbaTest,PonMultiSfuDbaTest,PonDbaExecutionTest,PonGrantPacketFillerTest,PonDemandLedgerTest,PonReportPollingTest" test

# 所有功能完成后：一次完整回归并构建JAR
mvn -o clean package

# 使用已构建的Java程序生成专项CSV，并完成一次旧场景归档对照
python -B tools/run_pon_tcont_test.py --check-legacy

# 后续仅重新生成本阶段结果时，可省略历史归档核验
python -B tools/run_pon_tcont_test.py

# 单独观察实际保存的两SFU三对象场景
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar results/pon-tcont/test-config.properties
```

Python脚本运行真实Java程序与Java参数加载辅助入口，不能替代JUnit或完整回归。所有CSV必须由上述实际执行重新生成，不能手工填写预期值代替实测。

## 10. 当前边界与下一步

本阶段完成的是逻辑队列身份和带宽参数模型，不代表已有T-CONT服务等级保障。暂不实现GIANT的服务间隔计数器、assured/surplus分阶段授权、真实XGEM/DBRu编码、突发前导、保护间隔、分片或未用预算当帧回收。

严格完整报文FIFO可能留下预算空洞或队首阻塞；RR按已知需求及剩余容量分配，不能据此承诺各类型的时延、最小速率或公平份额。后续GIANT可读取同一 `DbaContext` 中的profile并替换策略，而不用改变光侧共享链路、授权接收和SFU装载接口。

