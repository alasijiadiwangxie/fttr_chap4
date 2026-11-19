# PON 上行 REPORT 传输与 MFU 可见性测试报告

本阶段沿用 `pon-framed` 下的 `fiber.uplink.report.enabled=true`。SFU 仍在帧授权前生成不可变快照。只有当该 SFU 获得时隙时，快照才随其本帧第一条获授权的数据上行。MFU 只在执行收到报告的事件时更新已知队列状态，FCFS 仍以原有待授权队首为输入。

## 事件、状态与统计

1. `PON_UPLINK_REPORT` 保存帧号、SFU、快照时刻和原始缓存量。同一帧每台 SFU 仅第一条授权获配该快照。没有授权的报告留在本地记录中。
2. 首个时隙结束时，仿真安排 DATA 交付和 `PON_UPLINK_REPORT_RX` 两个事件。两者的接收时刻均为 `firstTxEndNs + propagationNs`。在同一时刻，按插入顺序先完成 DATA 交付，再接收其 REPORT。若恰好碰上帧边界，再记录该边界的 MFU 已知状态。
3. MFU 按 SFU 保存原始报告与接收时刻。首个报告到达前，状态为 `UNKNOWN`，CSV 对应的已知值为空。之后只接受原始帧号更大的报告更新状态，迟到的旧报告和同帧重复报告保留实际接收记录并标记 `applied=false`。
4. 帧边界的 `report_age_ns = boundaryNs − latestSnapshotNs`，统计的是需求信息年龄。有效帧由 Java 直接输出 MFU 状态。依旧按积压唤醒，跳过的空帧由专项脚本使用真实接收事件重建，使用 `receiveNs <= boundaryNs` 的已应用报告，并对有帧事件的行逐项与 Java 状态核对。
5. `summary=PON_UPLINK_REPORTS` 分别统计本地生成、实际收到和未获授权的报告。生成量包括在现有观测窗口内补记的空帧零需求报告。REPORT 作为逻辑捎带，使用其 DATA 的同一传播过程，控制接收事件不会额外延长仿真时长、占用发送资源或计入 DATA 吞吐量。

## 专项场景实测

两台 SFU、36 个 1200 B 报文，采用 125 μs 帧周期、2500 Mb/s 和 **121.160 μs** 统一传播时延。业务手动注入，固定无线时延为 0 μs，便于直接复核光侧时序。六帧共生成 **12 份** REPORT，其中 **5 份**实际收到，**7 份**因未获授权留在本地。

| 原始帧 | SFU | 首包 ID | 首时隙结束（ns） | REPORT 到达（ns） | 原始待授权量（B） |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 2 | 1 | 128840 | 250000 | 39600 |
| 2 | 2 | 33 | 253840 | 375000 | 1200 |
| 2 | 1 | 34 | 257680 | 378840 | 1200 |
| 3 | 2 | 35 | 378840 | 500000 | 1200 |
| 5 | 1 | 36 | 628840 | 750000 | 1200 |

第 1 帧 SFU 1 有 1200 B 需求，但整帧都未获得时隙。它的该帧记录为 `NOT_DELIVERED_NO_GRANT`，发送和接收字段为空。MFU 在第 3 帧边界（375000 ns）仍认为该 SFU 为 `UNKNOWN`，首次到达的是第 2 帧报告（378840 ns）。

第 2 帧边界，SFU 2 的本地队列剩 1200 B，MFU 刚收到的第 1 帧原始快照仍为 **39600 B**。在跳过的第 4 帧边界（500000 ns），根据实际接收事件可知，MFU 保存的是 SFU 1 的第 2 帧报告和 SFU 2 的第 3 帧报告。共核对两台 SFU 的 **12 条边界状态**。[实际运行配置](../../results/pon-report-delivery/test-config.properties) · [报告送达轨迹](../../results/pon-report-delivery/report-delivery-trace.csv) · [MFU 帧边界状态](../../results/pon-report-delivery/mfu-report-state.csv)

## 测试与第 4 步对照

| 新增用例 | 预期 | 实际 |
| --- | --- | --- |
| 首时隙捎带与原始快照 | 130 μs 传播期间 MFU 保持未知。SFU 一帧多包时仅首包携带报告，后续入队和发送与原值独立 | 两台 SFU 在第 1、2 帧边界均为 UNKNOWN。SFU 1 的首包报告在 258840 ns 到达，第 3 帧边界仍保存 2000 B，第 2 帧发送的 600 B 报告在 381920 ns 才到达。8 份生成、4 份收到、4 份无授权 |
| 同刻 DATA、REPORT 与帧边界 | 128840 + 121160 = 250000 ns，依次交付 DATA、接收 REPORT、记录帧状态 | 三条记录顺序一致，第 2 帧边界已知第 1 帧的 1200 B，报告年龄 125000 ns |
| 未获时隙及零传播 | 39062 B 的首包占 124999 ns，另一 SFU 的 1200 B 无法放入帧尾。零传播时 DATA 和其报告同刻到达 | 首包 DATA 及 REPORT 在 249999 ns 相邻记录。另一 SFU 在第 2 帧边界仍未知，直至 253840 ns 收到报告 |
| EDCA 与双向流量回归 | 去除报告日志后，开关与省略配置的原有日志一致 | 4 次交付、4 次 Wi‑Fi ACK、3 次上行授权的轨迹完全一致 |
| 拒绝回退 | 较早或同帧重复报告均保留当前已知状态 | 三次接收的 `applied` 依次为 true、false、false。已知值保留 3600 B 待授权、2400 B 有资格；另一台 SFU 仍未知 |

`mvn -o clean package` 运行 **166 项测试，166 项通过，0 项失败、0 项错误、0 项跳过**。其中 161 项来自既有测试，5 项为本阶段新增。第 4 步原有 REPORT 开关回归只扩展了过滤范围，继续逐行比较完整的数据与授权轨迹。[新增 JUnit 测试](../../src/test/java/edu/suda/fttr/simulator/PonUplinkReportDeliveryTest.java)

专项脚本复跑了第 4 步 35 包对照和本阶段 36 包场景。开启、关闭 REPORT 后，过滤 REPORT 观测行的完整 DATA 与统计日志一致。第 4 步已提交的 FCFS、DATA 和本地快照 CSV 逐项匹配。另从提交 `02efdfd` 重新编译 Java 源码并运行其实际配置，当前开启 REPORT 的输出仅过滤本阶段新增接收、状态和计数行，剩余 **377 行与第 4 步逐字节一致**。[第 4 步测试报告](04-upstream-report-test.md)

## 修改文件

| 文件 | 内容 |
| --- | --- |
| `PonUplink.java`、`PonUplinkReport.java` | 快照绑定首个时隙、同传播接收和报告计数 |
| `Mfu.java` | 保存每台 SFU 的最新已接收快照、接收时刻和更新判定 |
| `Event.java`、`EventType.java`、`SimulationContext.java`、`Simulator.java` | 接收事件载荷、分发，以及帧边界 MFU 状态记录 |
| `TraceLogger.java` | 接收、已知状态与报告汇总日志 |
| `PonUplinkReportDeliveryTest.java`、`PonUplinkReportTest.java` | 新增传输测试，并保留第 4 步的 DATA 与授权轨迹对照 |
| `tools/run_pon_report_delivery_test.py`、`tools/run_pon_report_test.py` | 基于实际日志的报告送达与状态核验、CSV 生成 |
| `docs/pon/05-report-delivery-test.md`、`results/pon-report-delivery/` | 本报告、实际配置与复核 CSV |

## 模型边界与复现

当前模型将 REPORT 视为随首个有效 DATA 到达的逻辑载荷。真实 DBRu 字段编码、控制信息的串行化开销、BWmap 下发及传播、独立授权或轮询，以及基于已知队列状态的 DBA 策略仍属于后续建模范围。

在仓库根目录执行。最后一条命令运行实际 Java JAR，比较历史数据，重新生成本阶段的配置和两份 CSV。

```powershell
mvn -o clean package
mvn -o "-Dtest=PonUplinkReportDeliveryTest" test
python -B tools/run_pon_report_delivery_test.py
```
