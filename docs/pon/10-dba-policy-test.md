# 第 10 步：统一 DBA 策略接口与只读授权计划验收

本次将第 9 步 RR 算法迁入统一的 `DbaPolicy` 接口，并在策略输出后统一执行拓扑、需求、容量和时隙校验。`shadow-rr` 仍只提供建议，真实 FCFS、BWmap、REPORT-only 轮询和 DATA 执行继续使用原有流程。

验收结果来自实际 Java 仿真、生产校验器和 Maven 测试。主场景的 **6 次决策与第 9 步全部字段一致**；计划校验 CSV 的 **20 个场景全部符合预期**。完整回归为 **211 项通过，0 项失败、0 项错误、0 项跳过**，包括原有 197 项和新增 14 项。

## 1. 设计职责和调用关系

```java
public interface DbaPolicy {
    DbaPlan plan(DbaContext context);
}
```

调用顺序为：

```text
原 PonUplink 帧回调预留 REPORT-only 时间
  → Mfu 查询已收到 REPORT 与历史授权构成的需求账本
  → PonDbaShadowPlanner 复制输入为 DbaContext
  → DbaPolicy.plan（默认 RoundRobinDbaPolicy）
  → DbaPlanValidator.validateAndPreview
  → 保留 PON_DBA_SHADOW 日志，增加 PON_DBA_POLICY 观测日志
  → 原 FCFS 继续生成真实 BWmap / DATA 预约
```

| 类 | 职责与边界 |
| --- | --- |
| `DbaPolicy` | 统一的算法入口。参数中没有 SFU FIFO、调度器或账本写入接口。算法可以维护自己的 RR 游标 |
| `DbaContext` | 不可变的一轮输入，含决策时刻、目标帧、速率、帧周期、控制预约、可用时间和分配对象列表 |
| `DbaContext.Allocation` | 包含 `allocationId`、`sfuId`、可空有效需求、报告帧号和 Report Age。需求为 `null` 表示 UNKNOWN，0 表示已知零需求 |
| `DbaPlan` / `Grant` | 不可变的建议集合，含决策时间、目标帧、各对象的字节预算和时长、决策原因。允许空计划和零预算 |
| `RoundRobinDbaPolicy` | 排序、跳过未知和零需求、计算一个对象的预算、推进自身游标 |
| `DbaPlanValidator` / `Slot` | 拒绝非法计划，返回不可变的逻辑时隙预览，不生成执行事件 |
| `PonDbaShadowPlanner` | 兼容第 9 步的 MFU 适配层。负责输入转换、调用策略和校验器、保留旧 `Decision` 字段 |

`DbaContext`、`Allocation`、`DbaPlan`、`Grant` 和 `Slot` 都使用 final 字段，集合使用防御性复制。上下文只保存数值，没有 MFU、SFU 或 `PonDemandLedger` 对象引用，因此策略无法通过该上下文修改真实账本。

当前每台 SFU 建立一个稳定的逻辑对象，适配层采用 `allocationId = 2L × sfuId`，例如 allocation 2 属于 SFU 1、allocation 4 属于 SFU 2。该公式仅为本仿真的本地命名约定，**不代表真实 XG-PON Alloc-ID 编码或 T-CONT**。策略和校验器只检查上下文中的显式映射，不依赖该公式。模型及预览已经支持同一 SFU 的不同 allocationId，但当前生产拓扑仍是一台 SFU 一个对象。

原三参数适配器构造默认使用 RR；新增四参数构造接收 `DbaPolicy`，便于替换算法。集成测试注入了自定义纯策略，并确认它收到的净需求、UNKNOWN 状态及对象映射正确，自定义超额计划也会被统一拒绝。没有新增配置项、反射或策略注册框架。

## 2. 迁移内容和计算规则

原 `PonDbaShadowPlanner` 中的 RR 游标、排序选择和预算逻辑迁入 `RoundRobinDbaPolicy`。适配层保留原 MFU 入口、未知/零需求计数、所选报告元数据及第 9 步所有输出字段。精确容量计算移到 `DbaContext.capacityBytes()`，串行化继续复用 `PonLinkTiming`。

```text
effectiveKnownBytes = max(0, reportedPendingBytes - grantedBytesSinceSnapshot)
availableNs = framePeriodNs - controlReservedNs
capacityBytes = floor(availableNs × rateMbps / 8000)
plannedBytes = min(effectiveKnownBytes, capacityBytes)
plannedDurationNs = ceil(plannedBytes × 8000 / rateMbps)   // 正预算
plannedDurationNs = 0                                     // 零预算
sum(plannedDurationNs) + controlReservedNs <= framePeriodNs
```

时间和字节使用整数，链路速率使用精确 `BigDecimal`，容量中间值使用 `BigInteger`。多个对象各自计算向上取整后的时长，再核对总容量，不能以一次聚合取整代替。

RR 按 `sfuId`、`allocationId` 排序。从当前游标开始跳过 UNKNOWN 和 KNOWN=0，每帧至多生成一个正预算；正预算后推进到该对象下一位置，没有容量或没有已知正需求时保留游标。`ALL_UNKNOWN`、`NO_KNOWN_POSITIVE_DEMAND`、`NO_DATA_CAPACITY`、`KNOWN_DEMAND` 和 `FRAME_CAPACITY_LIMIT` 原因字段保持不变。

本阶段影子预算没有真实授权作用，多帧建议不能累加当作已授出字节，也不会反过来扣减 `effectiveKnownBytes`。

## 3. 统一校验和预览

校验器逐项检查：

1. 决策时间和目标帧与输入一致，目标帧严格晚于当前决策帧，绝对纳秒时刻没有 long 溢出。
2. allocationId 位于输入拓扑中，且 sfuId 与该对象的关联一致。
3. 同一计划不得重复使用一个 allocationId。
4. 字节数和时长非负，正预算只能分给 KNOWN 对象，且不得超过其有效需求。
5. 声明时长必须精确等于公共串行化公式的结果，0 B 必须对应 0 ns。
6. DATA 逐对象时长之和加控制预约不得超过帧周期。
7. 预览窗口不得占用控制预约、不得与前一 DATA 窗口重叠、不得跨出目标帧。

计划中的 `Grant.startOffsetNs` 是可选的目标帧内偏移。未指定时，预览器在控制预约之后按计划集合顺序连续安排；指定时，要求窗口按时间有序并通过相同重叠和容量检查。非法计划直接抛出异常，不裁剪预算或移动冲突窗口。零预算通过校验，但不生成虚构的发送窗口。

当前 REPORT-only 预约是目标帧开头的连续前缀，所以预览从 `targetFrameId × framePeriodNs + controlReservedNs` 开始。若以后控制时隙变为分散窗口，应扩展上下文提供窗口集合。预览 `Slot` 没有真实 grantId、packetId 或事件句柄，也不会占用链路、出队、创建 BWmap 或修改需求账本。

## 4. 实际主场景及第 9 步逐决策对照

复用第 9 步归档配置，文件逐字节相同。两台 SFU、125000 ns 帧、2500 Mb/s 上行、10000 Mb/s 下行、64 B 控制占位、下行传播 10000 ns、上行传播 10025 ns。每帧轮询一台 SFU，有限授权窗口到 625 μs，SFU 1 有一条 39062 B 报文，SFU 2 为空，无线采用零固定时延。该手动场景不使用随机数。

| 决策时间 ns | 目标帧 | allocation / SFU | 已知有效需求 B | 控制 ns | 第 9 步字节 / ns | 第 10 步字节 / ns | 对照 |
| ---: | ---: | --- | --- | ---: | --- | --- | --- |
| 125000 | 2 | 无 | 全部 UNKNOWN | 205 | 0 / 0 | 0 / 0 | 完全一致 |
| 250000 | 3 | 无 | 全部 UNKNOWN | 205 | 0 / 0 | 0 / 0 | 完全一致 |
| 375000 | 4 | 2 / 1 | 39062 | 205 | 38998 / 124794 | 38998 / 124794 | 完全一致 |
| 500000 | 5 | 2 / 1 | 39062 | 205 | 38998 / 124794 | 38998 / 124794 | 完全一致 |
| 625000 | 6 | 2 / 1 | 39062 | 0 | 39062 / 124999 | 39062 / 124999 | 完全一致 |
| 750000 | 7 | 无 | 两台均已知为 0 | 0 | 0 / 0 | 0 / 0 | 完全一致 |

脚本比较了旧 CSV 的全部字段，包括 report frame、Report Age、UNKNOWN/零需求计数、容量和原因，并比较了原逐帧容量 CSV，不只检查上表数值。[第 9 步历史决策](../../results/pon-dba-shadow/dba-decision-trace.csv) · [本次实际策略决策](../../results/pon-dba-policy/policy-decision-trace.csv)

三个正预算的预览区间依次为 **500205–624999 ns、625205–749999 ns、750000–874999 ns**。前两个区间只是较小字节预算的假设窗口，没有产生真实发送。实际仍只有一条 39062 B DATA 在 **750000–874999 ns** 发送、**885024 ns** 到达 MFU；DATA 忙时 124999 ns、轮询忙时 820 ns 保持原值。

容量边界实测为 `floor((125000 - 205) × 2500 / 8000) = 38998 B`，时长 `ceil(38998 × 8000 / 2500) = 124794 ns`，含控制预约共 124999 ns，剩余 **1 ns**。

新 `policy-decision-trace.csv` 增加 `policy_name`、`allocation_id` 和只读的 `preview_start_ns`、`preview_end_ns`。没有选中对象时 allocation/sfu 为 0、窗口为 -1，这些是空计划标记，**不能据此把 UNKNOWN 当作已知 0 B**；应结合 `decision_reason` 和原逐 SFU 账本状态判读。原 `PON_DBA_SHADOW` 行及第 9 步 CSV 字段没有改名或删除。

## 5. 自动化测试的预期与实测

新增 `DbaPolicyTest` 的 14 项 JUnit 测试覆盖以下行为。原有第 9 步混合上下行、不同种子和 EDCA 回归继续保留。

| 验证内容 | 预期 | 实测 |
| --- | --- | --- |
| 六次历史决策 | 对象、原因、预算、时长一致 | JUnit 和实际 JAR 导出均通过，全部旧字段一致 |
| 6000 B 正需求 | 6000 B、19200 ns | 预览 250205–269405 ns |
| 控制 205 ns 容量边界 | 38998 B、124794 ns | 剩余 1 ns，预览结束 374999 ns |
| RR | 有序轮转，跳过 UNKNOWN 和 0 | 正需求 SFU 选择 1、3、1、3、1、3 |
| 无容量游标 | 不推进 | 恢复容量后仍选择 SFU 1 |
| 不可变性 | 外部清空原列表不影响快照，禁止修改返回列表 | Context、Plan 和 preview 均通过 |
| 多对象窗口 | 自动连续放置、不重叠 | 250205–254045 ns、254045–261725 ns |
| 同 SFU 多逻辑对象 | 不把 allocationId 当 sfuId | allocation 101、102 均属于 SFU 1，合法预览 |
| 显式窗口 | 重叠拒绝，合法间隔保留 | 相同 offset=205 被拒；第二窗口 offset=10000 时通过 |
| 非未来帧 | 不能向当前决策帧放置计划 | target=1、decision=125000 ns 被拒 |
| 真实账本只读 | 6000−2400=3600 B，重复规划不再扣减 | 3 次建议后真实授权仍2400 B，净需求仍3600 B，事件队列为空 |
| 策略注入 | 自定义策略能读取正确输入，输出仍受校验 | 空计划原因被采纳；3601 B 超额预算被拒；账本不变 |
| 可执行 CSV | 每行来自生产校验器实际调用 | 20 行，0 条与预期不符；自动重建通过 |

`DbaPlanValidationCases` 在实际 Java 进程中执行 20 个场景，[完整实测 CSV](../../results/pon-dba-policy/plan-validation.csv) 的 `validation_reason` 保存生产校验器的返回或异常信息。

| scenario_id | 关键输入或预期 | 实际结果 | 符合预期 |
| --- | --- | --- | --- |
| normal_6000 | 6000 B / 19200 ns | ACCEPTED，1 个预览窗口 | 是 |
| capacity_38998 | 38998 B / 124794 ns | ACCEPTED，1 个预览窗口 | 是 |
| invalid_allocation | 不存在的 allocation 99 | REJECTED，非法对象 | 是 |
| mapping_mismatch | allocation 2 错配 SFU 2 | REJECTED，关联不一致 | 是 |
| negative_bytes | -1 B | REJECTED，负预算 | 是 |
| negative_duration | -1 ns | REJECTED，负时长 | 是 |
| demand_exceeded | 已知6000 B却提议6001 B | REJECTED，超出需求 | 是 |
| unknown_positive | UNKNOWN 提议1200 B | REJECTED，未知需求 | 是 |
| frame_capacity_exceeded | 38999 B / 124797 ns，控制205 ns | REJECTED，超帧容量 | 是 |
| duplicate_allocation | 同对象重复分配两次 | REJECTED，重复对象 | 是 |
| duration_mismatch | 1200 B却声明3839 ns | REJECTED，应为3840 ns | 是 |
| zero_bytes_nonzero_duration | 0 B / 1 ns | REJECTED，时长不一致 | 是 |
| target_frame_mismatch | context目标2、计划目标3 | REJECTED，目标不一致 | 是 |
| decision_time_mismatch | context时刻125000、计划125001 ns | REJECTED，时刻不一致 | 是 |
| two_allocations_nonoverlap | 两对象合计3600 B / 11520 ns | ACCEPTED，2 个连续窗口 | 是 |
| explicit_overlapping_windows | 两个窗口均从offset205开始 | REJECTED，窗口重叠 | 是 |
| explicit_window_outside_frame | offset124000，发送3840 ns | REJECTED，跨出目标帧 | 是 |
| same_sfu_two_allocations | 同SFU的101、102两个对象 | ACCEPTED，2 个窗口 | 是 |
| empty_plan | 空集合 | ACCEPTED，0 个窗口 | 是 |
| zero_budget | 0 B / 0 ns | ACCEPTED，0 个窗口 | 是 |

合计 **6 个合法场景接受、14 个非法场景拒绝**。Maven 测试计数为 211，20 行校验场景是在部分 JUnit 和专项入口中执行的子场景，不额外加到 JUnit 总数中。

## 6. 旧模式和事件回归

专项脚本实际运行默认配置、显式 `fcfs`、`shadow-rr` 三种模式，各自重复两次。默认和 FCFS 的完整输出一致；从影子输出去除 `PON_DBA_SHADOW`、`PON_DBA_POLICY` 两类观测行后，其余真实事件和全部汇总统计与默认模式逐字节一致。

同时重算 REPORT-only 轮询、帧占用和 MFU 状态，对照第 8 步的三份历史 CSV。6 次原始影子决策和容量表与第 9 步历史 CSV 逐项一致。连续重新运行专项脚本后，本次三份结果文件逐字节一致。

还从第 9 步提交 `a778f1b` 单独导出并用 Java 11 重新编译源码，以相同配置运行。历史输出 **121 行**，本次 **127 行**；仅移除新增的6条 `PON_DBA_POLICY` 后，剩余121行完全一致，连旧影子决策行也未变化。

`PonDbaShadowTest` 与第9步专项脚本的日志过滤器只补充忽略新增观测行。旧实验参数、预期预算、历史 CSV 及真实事件比较逻辑没有修改。原有不同随机种子 11、29、47 的混合上下行 EDCA 回归仍通过。

## 7. 修改文件

| 文件 | 内容 |
| --- | --- |
| `src/main/java/edu/suda/fttr/dba/DbaPolicy.java` | 统一策略接口 |
| `src/main/java/edu/suda/fttr/dba/DbaContext.java` | 不可变输入、逻辑对象和精确容量 |
| `src/main/java/edu/suda/fttr/dba/DbaPlan.java` | 不可变建议集合、可选预览偏移 |
| `src/main/java/edu/suda/fttr/dba/RoundRobinDbaPolicy.java` | 从第9步迁移的RR算法 |
| `src/main/java/edu/suda/fttr/dba/DbaPlanValidator.java` | 公共校验、只读Slot预览 |
| `src/main/java/edu/suda/fttr/entity/PonDbaShadowPlanner.java` | 输入和旧日志适配、策略注入 |
| `src/main/java/edu/suda/fttr/trace/TraceLogger.java` | 增加独立PON_DBA_POLICY行 |
| `src/test/java/edu/suda/fttr/simulator/DbaPolicyTest.java` | 14项新增JUnit |
| `src/test/java/edu/suda/fttr/simulator/DbaPlanValidationCases.java` | 实际Java校验和CSV入口 |
| `src/test/java/edu/suda/fttr/simulator/PonDbaShadowTest.java` | 原回归过滤新增观测行 |
| `tools/run_pon_dba_shadow_test.py` | 保持第9步复现命令兼容 |
| `tools/run_pon_dba_policy_test.py` | 实际仿真、历史对照和CSV导出 |
| `results/pon-dba-policy/` | 6行决策、20行校验、实际配置 |
| `docs/pon/10-dba-policy-test.md` | 本验收报告 |

`PonDemandLedger`、`PonUplinkScheduler`、`PonUplink`、`Mfu`、`SimulationConfig`、BWmap和轮询执行代码均复用，无需修改；无线PHY和EDCA代码也未修改。

## 8. 当前边界和后续工作

真实DATA仍由FCFS逐包授权。新增策略不创建DATA BWmap、不替SFU选择报文、不执行预算、不分片、不返还未用预算。REPORT只有总字节需求，因此38998 B预算仍不能宣称能发送一条39062 B完整报文。

报告信息延迟、尚未报告的新业务、未知状态仍是需求估计误差来源。当前生产适配器仍每SFU一个对象，旧标量Decision日志面向当前单对象RR；集合模型与统一校验已为未来多对象算法预留接口。尚未实现GIANT、T-CONT服务等级、真实Alloc-ID/DBRu编码或新增PON协议机制。

后续可单独实现经校验预算到SFU端完整报文执行的适配层，再接入真正的DATA授权。该阶段还需要明确报文边界、未用预算与账本扣减的关系，本次不提前改变这些语义。

## 9. 复现命令

从仓库根目录执行，使用JDK11或更高版本、Maven和Python3标准库。`-o` 表示使用已有本地Maven依赖缓存。

```powershell
# 完整回归并重新构建实际仿真JAR与test-classes
mvn -o clean package

# 第10步专项JUnit
mvn -o "-Dtest=DbaPolicyTest" test

# 自动重跑Java仿真与Java校验入口，覆盖更新三份第10步结果文件
python -B tools/run_pon_dba_policy_test.py

# 第9步原复现命令仍兼容，可用于核对历史预算
python -B tools/run_pon_dba_shadow_test.py
```

专项脚本原样读取第9步已归档配置，实际调用JAR，检查旧决策和真实事件，再运行Java校验入口输出`plan-validation.csv`；任一检查失败时不会把预期值伪装成成功结果。

如需独立生成20个校验场景的CSV：

```powershell
java -cp "target/test-classes;target/classes" edu.suda.fttr.simulator.DbaPlanValidationCases results/pon-dba-policy/plan-validation.csv
```

实测产物：[policy-decision-trace.csv](../../results/pon-dba-policy/policy-decision-trace.csv)、[plan-validation.csv](../../results/pon-dba-policy/plan-validation.csv)、[test-config.properties](../../results/pon-dba-policy/test-config.properties)。
