# 第 16 步 · 基础 GIANT DBA 验收

`fiber.uplink.dba.mode=giant` 在原有 T-CONT/Alloc-ID 上执行两阶段上行调度。每帧先处理 Type 2、Type 3 的保障额度，再用剩余资源处理 Type 3、Type 4 的额外额度。将模式切换为 `rr-multi` 即可用同一份业务、拓扑和 REPORT 配置做对照。

## 算法依据

以 Leligou、Linardakis、Kanonakis、Angelopoulos 和 Orphanoudakis 发表的 GPON GIANT MAC 为依据，采用图 2 的保障计时，以及图 3 和第 4 节说明的剩余计时和同类型轮转。论文为 *Efficient medium arbitration of FSAN-compliant GPONs*（2006，19:603–617）。[出版页](https://onlinelibrary.wiley.com/doi/10.1002/dac.761) · [作者提供的论文 PDF](https://users.uop.gr/~fanis/html_files/pdf_files/papers/Journals/J8_IJCS-04-GPON_MAC.pdf)

原文图 3 对 Type 3 和 Type 4 的计数到期判断写法不一致。本实现依据第 4 节对两类剩余服务的文字说明，统一采用同一到期语义。进行本轮判断时，`SImin_before = 1` 表示可考虑授权，确保相邻两次额外授权至少相隔配置的 SImin 帧。计数更新与授权判断使用下表定义的顺序。

## 服务计数器

每个 Alloc-ID 独立保存计数。首次规划时，以配置的服务间隔初始化生效的计数器。策略对每个连续目标帧执行一次，先判断到期，再计算下一帧的计数值。即使没有可授权需求也推进计数。日志中的 0 表示该阶段对当前类型不适用。

| 阶段 | 适用类型 | 本帧开始时 >1 | 本帧开始时 =1 | 本帧结束状态 |
| --- | --- | --- | --- | --- |
| 保障 | Type 2、Type 3 | 未到期 | 进入保障判断 | 未到期减 1；到期重置为 SImax |
| 剩余 | Type 3、Type 4 | 未到期 | 进入剩余判断 | 未到期减 1；获得正额外预算时重置为 SImin，否则保持 1 |

例如从首次规划开始，在持续有合格需求及足够容量的前提下，SI 为 1 对应第 1、2、3…轮，SI 为 2 对应第 2、4、6…轮，SI 为 4 对应第 4、8、12…轮。Type 3 的两个计数器独立更新。保障到期遇到 UNKNOWN、零需求、待结算或容量不足时仍按期重置。剩余阶段遇到这些情况会保留到期状态。计数器在 MFU 作出授权决策时更新，实际传输结束后由既有账本处理结算。

## 帧容量、授权和反馈

保障阶段按 Type 2、Type 3 排序，同类型按 Alloc-ID 升序。剩余阶段先 Type 3 再 Type 4，每个类型有独立的轮转位置，下一轮从最后获该阶段额度的对象之后开始。授权只使用 `DbaContext` 内的有效需求、状态和服务参数。

```text
T(B) = ceil(B × 8000 / rateMbps) ns，T(0) = 0
初始 R = 帧周期 − REPORT-only 预留时间
C = floor((R + T(当前对象已分配字节)) × rateMbps / 8000) − 当前对象已分配字节
本阶段额度 = min(本阶段 AB 上限, 已知有效需求 − 当前对象已分配字节, C)
R -= T(本对象新增额度后的总字节) − T(本对象原分配字节)
```

保障阶段使用 ABmin，剩余阶段使用 ABsur。Type 3 的两个正额度按首次获批的位置合并为一个 grant。按合并后的实际纳秒增量扣帧容量，可保留同一预算内的取整余量。不同预算各自取整。统一验证器再次检查需求、未结算状态、重复身份和窗口边界。

MFU 从已到达的 REPORT 和现有账本获取需求。SFU 收到 BWmap 后，才从对应 FIFO 装载完整报文。结算区分预算、实际发送与未使用字节。一个 Alloc-ID 等待结算期间暂停新的 DATA 预算，它的服务计数继续推进，其他对象仍可获授权。

## 本项目的建模边界

- 原文中的 Type 4 周期控制机会由已有 REPORT-only 轮询承接，轮询按配置及拓扑轮转。Type 4 的 SImax 和 ABmin 保留为参数字段，其 DATA 预算只使用 SImin 和 ABsur。
- 保障同类的稳定顺序、帧容量不足时裁剪额度、Type 3 合并授权、提前一帧的 BWmap，以及结算后允许下一笔预算，均是本项目的实现规则。在队列信息迟到、帧容量不足或报文放不进时，保障额度属于本帧的调度上限。
- 本阶段范围为 Type 2/3/4 的两阶段调度。Type 1、IA、EBU、分片、准入控制及额外物理开销留在当前模型之外。完整报文装载导致的未用时间随本帧结束。

## 实测结果

增量 Maven 运行 9 项 GIANT 测试和 80 项 DBA/REPORT/BWmap 关键回归，共 **89 项通过**。功能完成后运行一次 `mvn -o clean package`，**290 项全部通过，0 失败、0 错误、0 跳过**，覆盖原 RR、FCFS、EDCA 与其他既有场景。

用最终 JAR 重放第 15 步的 7 个场景，2211 条 T-CONT 轨迹与归档逐字节一致。11 个非法配置仍均被拒绝，其中 FCFS 搭配 T-CONT 的错误消息新增了 `giant` 候选模式。第 14 步及更早的 RR、FCFS、shadow、REPORT 和 BWmap 归档对照通过。

| 验收项 | 实测 |
| --- | --- |
| SI 为 1、2、4 的计数和机会 | 在 8 轮持续有效需求的策略测试中，两类计数独立按预期变化。到期遇阻时 SImax 重置，SImin 保持。 |
| 保障与剩余顺序 | 容量紧张时按阶段与类型分配。14 ns 边界测试中，Type 2 获 2 B，Type 3 将 1 B 保障与 1 B 额外合为 7 ns，Type 4 因无容量跳过。 |
| Type 4 同类型轮转 | 每帧仅能容纳一笔预算时，Alloc-ID 21 和 22 交替获授权。 |
| 迟到 REPORT 和预算结算 | 仿真验证未知状态、待结算期间禁发新预算、整包装载失败后的需求恢复和其他 Alloc-ID 的正常发送。 |
| 对照与配置 | 两种策略在相同业务下做出不同预算；缺少 T-CONT 或必需 REPORT 路径时配置被拒绝。 |

四组 Java 仿真使用手动上行业务，固定无线时延为 0，便于单独核对光侧。控制占位 64 B，帧周期 125 μs，下行传播 10 μs。主场景在 2500 Mb/s 下为控制时隙预留 205 ns，上行传播 10.025 μs。逐阶段日志记录 190 条 Alloc-ID 决策，执行日志汇总 27 笔 BWmap 预算。

| 同一业务下的策略 | Alloc-ID 101（Type 2） | 102（Type 3） | 201（Type 4） | 交付 |
| --- | --- | --- | --- | --- |
| GIANT | 帧 5、7、9 各 1200 B | 帧 5 合并 2400 B，帧 7 再发 1200 B | 帧 6、10、14 各 1200 B | 9 包，10800 B |
| RR 多授权 | 帧 4 分配 3600 B | 帧 5 分配 3600 B | 帧 6 分配 3600 B | 9 包，10800 B |

在 GIANT 帧 5，101 的保障窗口为 `[625205, 629045)` ns，随后 102 从 629045 ns 发起一笔合并授权，由 1200 B 保障和 1200 B 额外组成，在 636725 ns 结束。同样的业务最终交付量一致，区别体现在每轮额度和服务时机。

将速率降为 144 Mb/s 后，帧 11 的控制时隙占 3556 ns。Type 3 得到 1200 B 保障与裁剪后的 985 B 额外额度，合并窗口为 121389 ns。总预留 124945 ns，剩余 55 ns 不足以容纳另一个字节，Type 4 等待后续帧。Type 3 在该窗口实际发送一包 1200 B，结算记录 985 B 未用。

在 135 μs 上行传播场景中，101 在目标帧 2–4 仍为 UNKNOWN。目标帧 5 可见 1800 B 需求，获得 1000 B 预算，队首 1200 B 报文无法装载。目标帧 7 仍等待结算，保障计数从 1 重置为 2。MFU 于 763405 ns 收到结算，恢复到 1800 B 已知有效需求。101 的报文保留，102 独立交付一包。

## 复现

从仓库根目录执行。Maven 的 `-o` 选项依赖本地已缓存的依赖包。

```powershell
# 开发期间只跑专项及关键回归，并打包供仿真脚本使用
mvn -o "-Dtest=GiantDbaPolicyTest,GiantDbaExecutionTest,DbaPolicyTest,TcontBandwidthTest,PonDbaExecutionTest,PonDemandLedgerTest,PonMultiGrantDbaTest,PonReportPollingTest,PonUplinkBwmapTest" package

# 功能完成后进行一次完整回归
mvn -o clean package

# 重新运行四组 Java 仿真并导出 CSV
python -B tools/run_pon_giant_test.py

# 用同一构建重放旧场景，将重放结果写到 target
python -B tools/run_pon_tcont_test.py --check-legacy --output target/giant-legacy-check

# 单独观察 GIANT 与 RR 主场景的原始日志
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar results/pon-giant/test-config.properties
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar results/pon-giant/mixed_rr.properties
```

`results/pon-giant/giant-decision-trace.csv` 按目标帧和 Alloc-ID 列出前后计数、已知需求、两阶段额度、合并预算和原因。状态为 UNKNOWN 时报告帧号、报告年龄和需求字段用 `-1` 表示缺失。`grant-execution.csv` 按预算记录 BWmap 收发、选包、DATA 窗口、交付、结算及实际和未用字节，包含 RR 对照。其余 `.properties` 保存容量和 REPORT 延迟场景。脚本也在本地保留原始 `*-events.log`，可用预算 ID 与两份 CSV 核对。
