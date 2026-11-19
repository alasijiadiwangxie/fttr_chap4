# PON 上行队列快照与逻辑 REPORT 测试报告

本阶段在 `pon-framed` 上行模式增加逐 SFU 队列报告。配置 `fiber.uplink.report.enabled=true` 时，已有的上行帧事件在分配首个授权前读取各 SFU 的光侧 FIFO，并输出一条 `PON_UPLINK_REPORT`。开关默认关闭。报告保存独立的不可变数值，FCFS 继续使用原有队首、入队时间和事件顺序。

## 快照口径

对帧 `f`，快照时间为 `snapshotTimeNs = f × framePeriodNs`。设 `Q` 为处理该帧事件时某台 SFU 尚未授权的 FIFO，设 `E` 为其中满足 `enqueueNs < snapshotTimeNs` 的报文集合。

| 字段 | 计算方式 |
| --- | --- |
| `frameId`、`sfuId`、`snapshotTimeNs` | 帧号、SFU ID 和整数纳秒帧边界 |
| `pendingPackets`、`pendingBytes` | `Q` 内的报文数及其字节数之和 |
| `eligiblePackets`、`eligibleBytes` | `E` 内的报文数及其字节数之和 |

REPORT 观察的是仿真在同刻执行到帧处理时的实际队列。同一时刻已先完成光侧入队的报文会计入 `pending`，它从下一帧才有资格计入 `eligible`。同刻尚未执行光侧入队的报文在当时的快照中不可见。授权后原报文立即退出待授权 FIFO，因此下一帧的快照会排除它。仍在等待授权的报文可出现在多份时点快照中，业务到达量依然按独立的 `PON_UPLINK_ENQUEUE` 记录统计。

报告沿用第 3 步的观测窗口，从帧 0 到最后一个有授权的帧。跳过的空帧随原有帧统计补记全零快照，`snapshot_time_ns` 表示逻辑帧边界。空闲片段和无业务场景沿用按积压唤醒机制，生成 REPORT 无需安排额外事件。

## 实测缓存量

主场景复用第 3 步的 **35 个 1200 B 上行报文**，两台 SFU、125 μs 帧周期、2500 Mb/s 上行速率和 10.025 μs 统一传播。采用手动输入和零无线时延，配置无需随机种子。下表每格为包数与字节数，本场景的 `pending` 和 `eligible` 实测相同。

| 帧 | 时刻（ns） | SFU 1（包 / B） | SFU 2（包 / B） | 核对结果 |
| ---: | ---: | ---: | ---: | --- |
| 0 | 0 | 0 / 0 | 0 / 0 | 两台 SFU 均为空帧快照 |
| 1 | 125000 | 16 / 19200 | 17 / 20400 | 授权前共 33 包、39600 B |
| 2 | 250000 | 0 / 0 | 1 / 1200 | 只有第 33 包仍待授权。第 34 包的光侧入队事件稍后执行 |
| 3 | 375000 | 1 / 1200 | 1 / 1200 | 第 34、35 包此时具备资格 |

补充场景使用 25 μs 固定无线时延。SFU 2 的 1600 B 报文恰在 125000 ns 完成光侧入队，事件顺序排在帧处理之前。该 SFU 第 1 帧实测 `pending=1 包 / 1600 B`、`eligible=0 包 / 0 B`，到第 2 帧两者均为 1 包 / 1600 B。另一台 SFU 第 1 帧的 3 个不同长度报文共 2400 B，独立统计。脚本用实际入队和授权日志重建队列，**28 组包数和字节数对照全部一致**。[逐帧快照](../../results/pon-report/report-trace.csv) · [预期与实测对照](../../results/pon-report/report-check.csv) · [主场景实际配置](../../results/pon-report/test-config.properties)

## 测试与 FCFS 回归

| JUnit 用例 | 预期 | 实际 | 结果 |
| --- | --- | --- | --- |
| 单 SFU 的 0、1、3 包与授权前快照 | 逐帧统计真实包长，先记录再授权，旧快照保留原值 | 依次为 0 / 0 B、1 / 1100 B、3 / 2951 B，日志顺序正确 | 通过 |
| 多 SFU 独立快照 | 活跃 SFU 各自计数，同时为无业务 SFU 报告零需求 | 第 1 帧三台 SFU 分别为 2 / 2000 B、1 / 1450 B、0 / 0 B，均先于授权 | 通过 |
| 同刻先入队 | 125000 ns 的 900 B 报文计入待授权，下一帧才具备资格 | 当帧为 1 / 900 B 与 0 / 0 B，250000 ns 两者均为 1 / 900 B | 通过 |
| 35 包跨帧及 REPORT 开关 | 第 1、2 帧总待授权数为 33 和 1，第 33 包只入队一次，开关前后传输轨迹相同 | 33 → 1，35 包均交付一次，第 33 包的入队和授权各一次，过滤 REPORT 后日志一致 | 通过 |
| 稀疏业务 | 375 μs 入队的首包在第 4 帧获授权，前 4 个空帧逐个记录 | 帧 0–3 为零，帧 4 为 1 / 1200 B，仿真结束仍为 503.84 μs | 通过 |
| 零业务 | 生成窗口内无报文时自然结束 | 0 个帧和报告，仿真结束时刻为 0 μs | 通过 |

Maven 共 **161 项通过，0 项失败、0 项错误、0 项跳过**，其中 155 项为原有测试，6 项为本阶段新增。[PonUplinkReportTest.java](../../src/test/java/edu/suda/fttr/simulator/PonUplinkReportTest.java)

两组专项场景均比较了 REPORT 开、关的完整输出。去掉新增 REPORT 行后，授权、发送、接收、日志事件顺序及统计均一致。主场景的逐包与逐帧数据还与第 3 步已提交的 CSV 逐项一致。另从第 3 步提交 `85322f9` 编译源码，复跑同一主场景，与当前 REPORT 关的 **369 行日志逐字节一致**。[第 3 步逐包轨迹](../../results/pon-frame/event-trace.csv) · [第 3 步逐帧统计](../../results/pon-frame/frame-summary.csv)

## 修改文件与模型边界

| 文件 | 改动 |
| --- | --- |
| `ConfigLoader.java`、`SimulationConfig.java`、`Simulator.java` | 读取、验证并接入分帧上行的 REPORT 开关 |
| `PonUplink.java`、`PonUplinkReport.java` | 在授权前为各 SFU 取样，输出不可变的包数和字节数 |
| `SimulationContext.java`、`TraceLogger.java` | 记录 REPORT 字段 |
| `PonUplinkReportTest.java`、`tools/run_pon_report_test.py` | 自动化回归、真实日志核对和 CSV 导出 |
| `docs/pon/04-upstream-report-test.md`、`results/pon-report/` | 本报告、实测轨迹、对照表和配置 |

本阶段把报告建模为帧边界的本地观测。真实 DBRu 的字段编码、在上行突发中的传输与开销、传播和接收时序，以及 BWmap 的下发、解析和授权生效过程属于后续建模范围。

## 复现

从仓库根目录运行。最后一条命令运行 REPORT 开关对照，核对第 3 步的 CSV，并重新生成本阶段的实际配置与两份 CSV。

```powershell
mvn -o clean package
mvn -o "-Dtest=PonUplinkReportTest" test
python -B tools/run_pon_report_test.py
```
