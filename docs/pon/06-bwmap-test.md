# PON 逻辑 BWmap 下发与提前一帧授权测试报告

在 `pon-framed` 上行模式设置 `fiber.uplink.bwmap.enabled=true`，即可在帧 `f` 的边界生成目标帧 `f+1` 的上行时隙。默认沿用原有当帧授权。仍由全局 FCFS 从各 SFU 的待授权队首分配容量，REPORT 和 MFU 已知缓存均用于观测。

## 工作原理与状态转换

- 帧周期为 `T` 时，`grantTxNs = f × T`，目标发送区间为 `[(f+1) × T, (f+2) × T)`。只选择实际已入队且 `enqueueNs < grantTxNs` 的报文。时隙长度为 `ceil(packetBytes × 8000 ÷ rateMbps)` ns。队首放不进帧尾时整包顺延。
- `PON_BWMAP_TX` 在调度边界记录该轮每条授权，包含目标 SFU、目标帧、报文、发送起止时刻和授权字节。该逻辑下行控制记录使用统一的 `fiber.downlink.propagation.us`。该值默认 0 μs，必须为非负整数纳秒并严格小于一个上行帧周期。共有的传播参数不消耗下行 DATA 发送资源。固定时延下行的 DATA 继续使用 `fiber.delay.us`。
- `grantRxNs = grantTxNs + downPropagationNs`。目标 SFU 处理 `PON_BWMAP_RX` 时核对本地预约，要求 `grantRxNs < txStartNs`。实际起发再检查该报文已收到授权、SFU 与报文身份匹配、当前时刻等于预约起点。前一条发送恰在下一条开始时结束的情况，先处理结束再起发。
- 报文依次经过未授权排队、预约、收到授权、开始发送、完成发送和 MFU 交付。预约时移出未授权 FIFO，原始入队时刻随报文保留。预约表记录的报文才可接收对应授权，收到重复或不匹配授权会报错。MFU 的 DATA 收齐时刻仍为 `txEndNs + upPropagationNs`。
- REPORT 保留调度帧 `f` 的本地快照，随对应 SFU 在目标帧 `f+1` 的首个实际时隙捎带。接收时刻由该时隙的实际发送完成加上行传播决定。预约已从未授权 FIFO 移除，下一份快照只统计仍待授权的报文。最后一个执行帧仍可记录快照，无待授权业务时自然结束。
- `PON_UPLINK_GRANT.frame_id` 记录调度帧，`PON_UPLINK_FRAME.frame_id` 记录实际分配时隙的目标帧。`PON_BWMAP` 按完成发送的报文分段统计 `grantTxNs − enqueueNs`、`txStartNs − grantTxNs`、`txEndNs − txStartNs`，分别为未授权等待、预约等待和串行化。上行帧利用率沿用从时间 0 到最后一个有发送时隙的帧结束为分母，仅 DATA 串行化计入忙时。

## 专项实测

两台 SFU 交替承载 35 个 1200 B 上行报文。前 33 包在 0 μs 光侧入队，最后两包在 250 μs、260 μs 入队。帧周期 125 μs，上行速率 2500 Mb/s、传播 10.025 μs，下行速率 10000 Mb/s、统一传播 10 μs。使用手动输入和零时延 Wi‑Fi 以隔离光侧时序。脚本从实际 Java 日志核对 **35 次唯一预约、35 次送达及 210 条状态转换**。

| 报文 | 调度帧 → 目标帧 | BWmap 下发（ns） | SFU 收到（ns） | 实际发送区间（ns） | MFU 收齐（ns） |
| ---: | --- | ---: | ---: | --- | ---: |
| 1 | 1 → 2 | 125000 | 135000 | [250000, 253840) | 263865 |
| 2 | 1 → 2 | 125000 | 135000 | [253840, 257680) | 267705 |
| 32 | 1 → 2 | 125000 | 135000 | [369040, 372880) | 382905 |
| 33 | 2 → 3 | 250000 | 260000 | [375000, 378840) | 388865 |
| 34 | 3 → 4 | 375000 | 385000 | [500000, 503840) | 513865 |
| 35 | 3 → 4 | 375000 | 385000 | [503840, 507680) | 517705 |

目标帧 2、3、4 分别发送 **32、1、2 包**。帧 0 和 1 没有上行发送，窗口为 625000 ns，忙时为 134400 ns，帧资源利用率 **21.504%**。平均未授权等待为 **128.285714 μs**，预约等待为 **179.528 μs**，串行化为 **3.84 μs**。

第 1 帧两台 SFU 共报告 33 包。第 2 帧的快照仅剩第 33 包，第 34 包在该时刻的帧处理之后入队。第 3 帧快照为各 1 包，第 4 帧均为零。MFU 实际收到 5 份 REPORT，第 2 帧边界仍为 UNKNOWN，第 3 帧边界已收到两台 SFU 原始第 1 帧快照。[BWmap 逐包授权记录](../../results/pon-bwmap/bwmap-trace.csv) · [逐包状态转换](../../results/pon-bwmap/packet-state-trace.csv) · [实际配置](../../results/pon-bwmap/test-config.properties)

## 自动化测试与旧模式对照

| 新增 JUnit 用例 | 预期 | 实际 |
| --- | --- | --- |
| 首个授权 | 125000 ns 下发，传播 10000 ns，目标帧 2 起发 | 135000 ns 收到，250000–253840 ns 发送。测试中 4025 ns 上行传播后在 257865 ns 交付 |
| 35 包同时入队 | 两台 SFU 严格 FCFS，目标帧 2 最多 32 包，剩余 3 包进入帧 3 | 32 + 3 包，授权、接收、发送与交付均各一次且区间无重叠。第 2 帧仅报告 3 个未预约包，第 3 帧为零。REPORT 仅由首包 1、2、33、34 捎带 |
| 同边界及未来入队 | 在 125000 ns 或稍后入队的报文无法参与当轮分配 | 只有此前入队的包进入目标帧 2。125000 和 126000 ns 入队的两包进入目标帧 3 |
| 相邻满帧与 1 ns 接收裕度 | 124999 ns 的下行传播仍早于发送时隙，前帧结束后立即释放 | 前两包在 250000 和 375000 ns 起发，各占满一帧。375000 ns 处先结束前包，再启动下一包 |
| 异常启动与晚到授权 | 跳过实际接收时禁止起发，接收时刻等于起点也应拒绝 | 250000 ns 的人工启动和同刻接收都抛出明确 BWmap 错误 |
| 关闭与默认 | 包含 REPORT 时，显式关闭与省略 BWmap 的完整轨迹一致 | 无新增 BWmap 日志，原有三包发送、交付和 REPORT 时序一致 |
| 非法配置 | 传播等于或高于周期、负传播和非法开关应拒绝 | 125、125.001、−0.001 μs 及 `yes` 均在配置加载时拒绝 |
| 与下行 DATA、EDCA 并行 | 同刻逻辑下发与下行串行化各自进行 | 125000 ns 同时下发 BWmap 和启动下行 DATA。下行 135960 ns 到达 SFU，DATA 与 ACK 轨迹同关闭时一致 |
| 末帧时钟边界 | 末帧仅取样时，无需计算超出整数范围的未来帧 | 目标帧在 6000000000000000000 ns 起发并完成交付。末帧记录零需求，只有一次 BWmap 下发 |

完整 Maven 构建 **175 项全部通过，0 项失败、0 项错误、0 项跳过**。包括原有 166 项和新增 9 项。[PonUplinkBwmapTest.java](../../src/test/java/edu/suda/fttr/simulator/PonUplinkBwmapTest.java)

专项脚本复核了此前已提交的 FCFS、REPORT 送达与 MFU 状态数据。另编译第 5 步提交 `2279718`，复跑两份历史配置。当前显式关闭 BWmap 后，**389 行和 407 行完整日志**分别与该提交逐字节一致。[第 5 步测试报告](05-report-delivery-test.md)

## 修改文件

| 文件 | 变更 |
| --- | --- |
| `ConfigLoader.java`、`SimulationConfig.java` | BWmap 开关、统一下行传播、周期约束和时钟范围预检 |
| `PonUplink.java` | 未来帧预约、按 SFU 绑定接收、实际发送校验、等待分段统计 |
| `Sfu.java`、`EventType.java`、`Simulator.java` | BWmap 接收事件投递到目标 SFU 端口 |
| `SimulationContext.java`、`TraceLogger.java` | BWmap 逻辑下发、接收和等待统计输出 |
| `PonUplinkBwmapTest.java`、`tools/run_pon_bwmap_test.py` | JUnit、实际 JAR 验证和结果导出 |
| `docs/pon/06-bwmap-test.md`、`results/pon-bwmap/` | 本报告、两份实际轨迹和实际配置 |

## 与真实 XG-PON BWmap 的差异

ITU-T G.987.3 在下行 XGTC 帧头中定义了 BWmap 分区。真实授权用 Alloc-ID 标识 T-CONT 等流量实体，StartTime 表示突发在上行 PHY 帧内的位置，GrantSize 按字量化分配。多个分配可组成同一 ONU 的上行突发。此处模型直接预约 SFU 的完整报文，使用全局纳秒时刻和统一传播。物理字段编码、封装开销、测距均衡、突发前导和保护间隔属于后续建模范围。固定提前一帧是本仿真的时序设定。[ITU-T G.987.3（05/2025），§8.1.2、§13](https://www.itu.int/rec/dologin_pub.asp?id=T-REC-G.987.3-202505-I%21%21PDF-E&lang=e&type=items)

## 复现

从仓库根目录运行。专项脚本调用实际 Java JAR，并重建逻辑授权与逐包状态 CSV。

```powershell
mvn -o clean package
mvn -o "-Dtest=PonUplinkBwmapTest" test
python -B tools/run_pon_bwmap_test.py
```
