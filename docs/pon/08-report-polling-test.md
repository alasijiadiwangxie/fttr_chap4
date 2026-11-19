# PON REPORT-only 轮询探测验收

本阶段增加有限窗口内的 Round Robin 主动轮询。MFU 仅按固定关联的 SFU ID 顺序选择目标，在下一帧开头预约 REPORT-only 控制时隙。SFU 收到 BWmap 后，等到实际时隙开始时才生成新的缓存快照，并经现有上行传播送到 MFU。FCFS 在控制时隙之后按原队首顺序分配剩余容量。

## 配置和时间规则

| 配置 | 作用 | 默认 |
| --- | --- | --- |
| `fiber.uplink.poll.enabled` | 开启主动轮询，要求分帧、BWmap、REPORT 同时启用 | false |
| `fiber.uplink.poll.bytes` | 控制时隙的简化占位字节数 | 64 |
| `fiber.uplink.poll.interval.frames` | 两次轮询调度相隔的帧数 | 1 |
| `fiber.uplink.poll.end.us` | 轮询授权创建窗口的结束时刻，单位 μs | 开启后必填 |

首次轮询在调度帧 1 边界执行，此后按配置间隔调度。授权创建必须严格早于结束时刻。窗口关闭后，已预约的控制发送、传播和剩余 DATA 继续完整执行。初始调度到末尾采样期间仅安排有限的帧事件。时隙过长、无效间隔、缺少结束时间、缺少 REPORT/BWmap 或非分帧模式均在配置加载时报错。

`pollDurationNs = ceil(pollBytes × 8000 / rateMbps)`。默认 64 B、2500 Mb/s 得到 `ceil(204.8) = 205 ns`。第 `f` 帧下发的控制授权用于目标帧 `f+1`，时隙从该目标帧起点开始。接收时刻为 `bwmapTxNs + downPropagationNs`，必须严格早于时隙开始。报告在 `snapshotTimeNs = pollTxStartNs` 采样，在 `pollTxEndNs + upPropagationNs` 接收。

控制预约使用独立的 `R1`、`R2` 等授权 ID 和 `REPORT_ONLY` 类型，事件载荷承载控制预约对象。用户 DATA 沿用原始 packet ID。轮询 SFU 的选择只依赖静态关联、SFU ID、轮次和配置。在预约创建和 BWmap 接收阶段无需读取目标 FIFO。只有实际开始控制发送时读取该 SFU 尚未授权的队列，已预约 DATA 因已移出该 FIFO 而排除。

MFU 复用 REPORT 接收和账本计算。新旧快照按真实采样时刻和原始帧号比较。即使一份旧 DATA 捎带报告较晚到达，MFU 也保留先收到的更新轮询快照。轮询位于目标帧最前，采样先于同刻的下一轮帧授权，因此账本继续包含采样同刻、随后创建的 DATA 预约。

## 主场景实际结果

两台 SFU 固定关联，采用 125 μs 帧周期、2500 Mb/s 上行和 10000 Mb/s 下行。下行传播 10 μs，上行传播 10.025 μs，控制长度 64 B，间隔 1 帧，授权窗口截止 625 μs。手动输入一个发往 SFU 1 的 39062 B 上行报文，固定无线时延为 0 μs，用于隔离光侧时序。

该 DATA 串行化需要 124999 ns。控制时隙预留 205 ns 后，剩余 124795 ns，整包按 FCFS 等待到轮询窗口结束后的帧。第一次控制采样发生时，SFU 尚未获 DATA 授权，MFU 可由轮询获得这笔需求。

| 轮询 | SFU | 下发（ns） | 收到 BWmap（ns） | 控制发送区间（ns） | 实际采样（ns） | 待授权（B） | REPORT 接收（ns） |
| ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| 1 | 1 | 125000 | 135000 | [250000, 250205) | 250000 | 39062 | 260230 |
| 2 | 2 | 250000 | 260000 | [375000, 375205) | 375000 | 0 | 385230 |
| 3 | 1 | 375000 | 385000 | [500000, 500205) | 500000 | 39062 | 510230 |
| 4 | 2 | 500000 | 510000 | [625000, 625205) | 625000 | 0 | 635230 |

SFU 1 在 260230 ns 从 UNKNOWN 更新为 KNOWN，已报告 39062 B、DATA 授权扣减 0 B、有效估计 39062 B。SFU 2 在 385230 ns 更新为已报告 0 B 的 KNOWN。625000 ns 时唯一 DATA 获得授权，SFU 1 的账本只扣这一笔 39062 B。DATA 在 [750000, 874999) ns 发送，在 885024 ns 唯一交付。

四次 REPORT-only 合计占用 **820 ns**，DATA 占用 **124999 ns**，总忙时 **125819 ns**。从帧 0 到末个有发送的帧 6，资源窗口为 **875000 ns**，总利用率为 **14.3793143%**。前两帧全空闲，帧 2–5 各用 205 ns 控制时长，帧 6 用 124999 ns DATA。该场景 DATA 包数仍为 1，交付字节仍为 39062，账本中只有一笔 DATA 授权。

实际导出 **4 条轮询、7 条逐帧资源记录和 18 条 MFU 账本状态**。CSV 逐项核对实际下发、接收、发送、采样、REPORT 接收事件以及 DATA 授权。[轮询轨迹](../../results/pon-polling/polling-trace.csv) · [帧资源占用](../../results/pon-polling/frame-usage.csv) · [MFU 状态](../../results/pon-polling/mfu-state-trace.csv) · [实际配置](../../results/pon-polling/test-config.properties)

额外的零 DATA 场景使用相同拓扑与轮询参数、零速率泊松业务。四次均收到 0 B，用户包数和 DATA 授权数为 0，控制占用 820 ns。最后一份报告在 635230 ns 到达后，仿真自然结束。

## 统计口径

`PON_POLL_FRAME` 和 `frame-usage.csv` 将每帧的 DATA、REPORT-only 和总忙时逐项列出。总忙时等于两类时长之和，空闲等于帧周期减总忙时，利用率分母为完整帧周期。区间核对同时保证两类发送无重叠。

原有 `PON_UPLINK`、`PON_BWMAP` 的报文数、平均串行化与等待继续统计用户 DATA。`PON_UPLINK_FRAMES` 的忙时和利用率包含真实控制发送，包数列仍为 DATA 包数。`PON_POLLING` 明确列出完成轮询数、DATA 忙时、控制忙时和两者总和。传播时间进入端到端时序及报告年龄，发送忙时只包含串行化。

每次轮询额外生成一份实际发送时采样的 REPORT，计入生成和接收总数。原有帧边界快照仍按已有规则记录，其未获 DATA 时隙的快照计入未送达数量。需求账本只订阅 DATA 预约创建，控制授权无需调用 DATA 扣减接口。

## 自动化验收

| 用例 | 预期 | 实测 |
| --- | --- | --- |
| 无业务 RR 与有限结束 | 顺序 1、2、1、2，均为 0 B，用户包数与 DATA 授权为零 | 四份报告均更新 MFU，控制占用 820 ns，最后事件 635.230 μs |
| 有缓存但无 DATA 授权 | 控制预留后完整 DATA 无法装入帧，先由轮询反馈 39062 B | 首次接收 260230 ns，首个 DATA 授权 625000 ns。接收时已知估计 39062 B，扣减为零 |
| 控制与多个 DATA 共帧 | 205 ns 控制在先，两个 1200 B 时隙顺延，授权字节只计 DATA | DATA 起点 250205、254045 ns。帧内 DATA 7680 ns、控制 205 ns、总忙时 7885 ns。旧 DATA 快照被拒绝覆盖新轮询快照 |
| 真实发送时采样 | 125 μs 下发后新入队的报文可在 250 μs 实际采样时被报告 | 200 μs 入队的 1200 B 报文出现在 250000 ns 快照中 |
| 间隔、长度和静态关联 | 仅轮询已关联 SFU，间隔 2 帧，125 B 按 400 ns 发送 | 已关联 2、3 两台 SFU，轮询顺序 2、3、2，下发时刻 125000、375000、625000 ns，控制总忙时 1200 ns |
| 缺少 BWmap 接收 | 即使达到预约时刻，也必须先处理匹配控制授权 | 跳过接收事件后，发送端明确抛出错误 |
| 显式关闭与默认 | 第 7 步模式继续运行，开关关闭与省略时日志相同 | 完整日志一致，DATA 起点 250000、253840、500000 ns，没有控制时隙 |
| 无效配置 | 缺少必要模块或结束时间、容量超限、零间隔和非法开关应拒绝 | REPORT 关闭、BWmap 关闭、0/39063 B、零间隔、结束时刻不合法、缺少窗口与专用参数误用均在加载时拒绝 |

Maven 全套 **189 项测试通过，0 项失败、0 项错误、0 项跳过**。包括原有 181 项及新增 8 项。专项脚本重复运行完整日志，验证控制事件时序、资源占用和 MFU 估计。[PonReportPollingTest.java](../../src/test/java/edu/suda/fttr/simulator/PonReportPollingTest.java)

另从第 7 步提交 `5a545d6` 编译源码，运行既有需求账本配置。当前显式关闭轮询后的 **178 行完整日志逐字节一致**。专项脚本还与已提交的需求账本 CSV 逐值对照。[第 7 步验收](07-demand-ledger-test.md)

## 修改文件

| 文件 | 内容 |
| --- | --- |
| `ConfigLoader.java`、`SimulationConfig.java` | 有限轮询参数、模块依赖、帧容量及时间范围检查 |
| `PonUplink.java`、`PonReportPoll.java` | 静态 RR、控制预约、共享上行时隙执行和实际时刻采样 |
| `Mfu.java`、`Sfu.java` | SFU 接收控制授权，MFU 复用报告接受和需求估计 |
| `Event.java`、`EventType.java`、`SimulationContext.java`、`Simulator.java` | 独立控制载荷、控制接收与发送事件以及有界帧唤醒 |
| `TraceLogger.java` | 轮询因果链与分类资源统计 |
| `PonReportPollingTest.java`、`tools/run_pon_polling_test.py` | JUnit、实际 Java 专项运行及 CSV 导出 |
| `docs/pon/08-report-polling-test.md`、`results/pon-polling/` | 本报告、实际参数和三份 CSV |

## 与真实 DBRu 的差异

64 B 是本次配置的简化占位容量。当前控制快照直接描述 SFU 的未授权报文，真实 XG-PON 中的 Alloc-ID、T-CONT、DBRu 字段编码和量化、控制信息在突发中的封装、突发前导及保护间隔仍需逐项建模。当前 BWmap 按逻辑下行传播，REPORT-only 确实占用上行时长。后续可替换控制长度模型，并使用已收到的报告驱动授权策略。[ITU-T G.987.3，§8.1.2、§8.2.2](https://www.itu.int/rec/T-REC-G.987.3/en)

## 复现

从仓库根目录执行。Python 脚本只使用标准库，实际调用打包的 Java 程序，核对确定性轨迹后重新生成配置和三份 CSV。

```powershell
mvn -o clean package
mvn -o "-Dtest=PonReportPollingTest" test
python -B tools/run_pon_polling_test.py
```
