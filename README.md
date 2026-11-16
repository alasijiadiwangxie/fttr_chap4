# FTTR 第四章节能仿真

本仓库用于实现硕士论文第四章的 Java 仿真代码，研究 FTTR 网络中无线接入点（AP）的节能策略。

## 研究方向

在业务负载、无线信道条件和业务时延约束变化时，研究 AP 如何在低容量模式（LCM）与高容量模式（HCM）之间选择，以及何时切换、在各模式停留多久。仿真计划计入模式切换开销，并用总能耗、吞吐量、时延和超期比例评估调度策略。

## 第 1 阶段 · 最小下行仿真

使用 JDK 11 或更高版本和 Maven 3.9，从仓库根目录构建并运行：

```powershell
mvn clean test
mvn package
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar
```

默认场景包含一条 1200 字节的下行报文。仿真时钟统一以微秒计，光纤和无线链路使用固定时延。默认运行应输出：

```text
t_us=0 node=MFU event=ARRIVAL packet_id=1 bytes=1200
t_us=0 node=MFU event=FIBER_TX_START packet_id=1 bytes=1200
t_us=100 node=SFU event=ARRIVAL packet_id=1 bytes=1200
t_us=100 node=SFU event=WIFI_TX_START packet_id=1 bytes=1200
t_us=350 node=STA event=DELIVERED packet_id=1 bytes=1200
delivered_packets=1 delivered_bytes=1200 e2e_delay_us=350
```

端到端时延从报文进入 MFU 起算。100 μs 是抽象光链路固定时延，250 μs 是从 SFU 开始发送到 STA 完整接收的固定 Wi-Fi 服务时长。两项数值用于验收事件时序。

默认配置在 [configs/phase1-minimal-downlink.properties](configs/phase1-minimal-downlink.properties)，随 JAR 一起打包。可复制这份 UTF-8 配置，修改后将文件路径传给 JAR：

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar path/to/scenario.properties
```

自定义场景需包含 `packet.bytes`、`packet.arrival.us`、`fiber.delay.us` 和 `wifi.delay.us`。报文长度必须为正整数，到达时刻和链路时延为非负整数。链路的零时延可用于检验同一时刻的事件顺序。

Java 代码按职责分为入口 `App`、配置 `config`、事件执行 `simulator`、设备与报文 `entity`、链路 `link` 和轨迹 `trace`。可复查各阶段的[验收用例](docs/04-validation-cases.md)。

## 实施阶段

| 阶段 | 模块和核对项 |
| --- | --- |
| 1. 仿真框架 | 读取配置，以固定时延验证 MFU 到 SFU 再到 STA 的事件顺序和 350 μs 端到端时延 |
| 2. AP 能耗 | 加入 LCM/HCM 和无线状态，核对状态时长、能耗分项及手算结果 |
| 3. 信道与干扰 | 加入次信道占用、带宽回落、误包与重传，区分等待和重传的代价 |
| 4. QoS 与切换 | 先检查截止时间，再比较预测能耗与切换代价，记录决策原因 |
| 5. 信道观测 | 比较关闭信道信息、仅用历史、历史加按需探测三种方式 |
| 6. 实验与结果 | 扫描负载与次信道占用，对比固定模式、阈值和 ECQ-SDPS |

## 实验文件

`output/` 用于存放本地生成的仿真结果和图表。需要纳入版本控制的实验配置、参考数据和代表性结果可放在该目录之外。
