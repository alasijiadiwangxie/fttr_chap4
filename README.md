# FTTR 第四章节能仿真

本仓库用于实现硕士论文第四章的 Java 仿真代码，研究 FTTR 网络中无线接入点（AP）的节能策略。

## 研究方向

在业务负载、无线信道条件和业务时延约束变化时，研究 AP 如何在低容量模式（LCM）与高容量模式（HCM）之间选择，以及何时切换、在各模式停留多久。仿真计划计入模式切换开销，并用总能耗、吞吐量、时延和超期比例评估调度策略。

## 第 1 阶段 · 最小下行仿真

使用 JDK 11 或更高版本和 Maven 3.9，从仓库根目录构建并运行：

```powershell
mvn package
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar
```

默认场景包含一条 1200 字节的下行报文。仿真时钟统一以微秒计，光纤和无线链路使用固定时延。默认运行应输出：

```text
t_us=0 node=MFU event=ARRIVAL packet_id=1 bytes=1200
t_us=100 node=SFU event=ARRIVAL packet_id=1 bytes=1200
t_us=350 node=STA event=DELIVERED packet_id=1 bytes=1200
delivered_packets=1 delivered_bytes=1200 e2e_delay_us=350
```

端到端时延从报文进入 MFU 起算。默认配置在 `src/main/resources/scenarios/minimal.properties`，随 JAR 一起打包。可复制这份 UTF-8 配置，修改后将文件路径传给 JAR：

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar path/to/scenario.properties
```

自定义场景需包含 `packet.bytes`、`packet.arrival.us`、`fiber.delay.us` 和 `wifi.delay.us`。报文长度必须为正整数，到达时刻和链路时延为非负整数。链路的零时延可用于检验同一时刻的事件顺序。

## 实验文件

`output/` 用于存放本地生成的仿真结果和图表。需要纳入版本控制的实验配置、参考数据和代表性结果可放在该目录之外。
