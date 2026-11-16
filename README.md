# FTTR 下行仿真基础版

本项目使用 Java 和离散事件模拟 FTTR 的下行单播流程。一个 MFU 根据目的 STA 选择对应的 SFU，SFU 按本地队列调度并交付报文。拓扑可配置为一个 MFU、多台 SFU 和多台 STA，各 SFU 可并行发送。

## 模型范围与假设

- 仿真以微秒为单位。报文按手动配置或泊松生成的到达时刻进入 MFU，全部事件处理完毕后结束。
- 每台 STA 在一次仿真中固定关联一台 SFU。光侧用固定时延表示，MFU 可立即转发，光路之间没有共享资源竞争或额外排队。
- 每台 SFU 独立维护 VO、VI、BE、BK 四类 FIFO 队列和一个无线发送端。业务类别按固定优先级选队，已经开始发送的报文会发完。不同 SFU 的无线发送互不干扰。
- 等待队列不设容量上限，无线传输按成功交付处理。**同一场景中的所有报文使用相同的 Wi-Fi 服务时长**。包长用于统计交付字节数和吞吐量。
- SFU 和 STA 的吞吐量统一按从 0 到最后事件的时间窗口计算。日志同时记录逐包时序，以及按业务类别、设备和全局汇总的统计。

上行、信道干扰、EDCA、OFDMA、DBA 和 AP 节能机制可作为后续独立扩展。

## 构建与运行

使用 JDK 11 或更高版本和 Maven 3.9，从仓库根目录构建并运行。`mvn clean package` 会先运行自动化测试，再生成 JAR。

```powershell
mvn clean package
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar
```

默认场景包含一条 1200 字节的下行报文。仿真时钟统一以微秒计，光纤和无线链路使用固定时延。默认轨迹的关键行如下：

```text
t_us=0 node=MFU event=ARRIVAL packet_id=1 bytes=1200 type=BE destination_sta_id=1
t_us=100 node=SFU event=WIFI_TX_START packet_id=1 bytes=1200 type=BE destination_sta_id=1 sfu_id=1
t_us=350 node=STA event=DELIVERED packet_id=1 bytes=1200 type=BE destination_sta_id=1 sta_id=1 sfu_id=1
summary=GLOBAL sent_packets=1 avg_queue_wait_us=0.00 avg_e2e_delay_us=350.00
summary=SFU sfu_id=1 sent_packets=1 sent_bytes=1200 throughput_mbps=27.43 avg_queue_wait_us=0.00
summary=STA sta_id=1 sfu_id=1 received_packets=1 received_bytes=1200 throughput_mbps=27.43 avg_e2e_delay_us=350.00
delivered_packets=1 delivered_bytes=1200 e2e_delay_us=350
```

端到端时延从报文进入 MFU 起算。100 μs 是抽象光链路固定时延，250 μs 是从 SFU 开始发送到 STA 完整接收的固定 Wi-Fi 服务时长。两项数值用于验收事件时序。

默认配置在 [configs/phase1-minimal-downlink.properties](configs/phase1-minimal-downlink.properties)，随 JAR 一起打包。可复制这份 UTF-8 配置，修改后将文件路径传给 JAR：

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar path/to/scenario.properties
```

手动场景需包含 `packet.arrival.us`、`fiber.delay.us` 和 `wifi.delay.us`，并提供共用 `packet.bytes` 或逐包长度。报文长度必须为正整数，到达时刻和链路时延为非负整数。链路的零时延可用于检验同一时刻的事件顺序。

### 同一 SFU 的多报文串行发送

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/phase1-serial-downlink.properties
```

`packet.arrival.us` 也接受逗号分隔的 MFU 到达时刻，如 `0,0,250,1000`。程序按时间排序，同刻报文按配置顺序编号。报文共用两段固定链路时延，光链路仅对每条报文引入固定到达时差。

同一 SFU 按业务类型分别排队，类型内按到达顺序发送。无线资源从 `WIFI_TX_START` 占用到 `WIFI_TX_END`，以 STA 完整接收为结束边界。前一包完成的同一微秒可以启动下一包。逐包轨迹列出排队时延、占用时长和端到端时延，多包汇总记录无线忙时与空闲时间。空闲时间以仿真时间 0 至最后交付为窗口，包括首次无线发送前的时间。

### 四类业务固定优先级

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/phase1-fixed-priority-downlink.properties
```

`packet.N.type` 指定业务类型，省略时为 BE。`packet.N.bytes` 覆盖该包的长度。`N` 对应 `packet.arrival.us` 列表中的原始位置，从 1 开始，与排序后的报文 ID 区分。

SFU 按 VO、VI、BE、BK 的顺序选择等待队列，正在发送的报文持续占用无线端直至完成。同一微秒先处理完到达、完成等报文事件，再选队。`SFU_ENQUEUE` 记录入队时刻，`QUEUE_SELECT` 记录选择前四条队列的长度。`summary=CLASS` 和 `summary=GLOBAL` 给出已完成的发送数量及平均时延，无样本类别显示计数 0 和均值 0.00。

### 单 SFU 多 STA 下行单播

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/phase1-multi-sta-downlink.properties
```

`sta.count` 设置终端数量，ID 从 1 到该数量连续编号。`packet.N.sta.id` 指定到达列表中第 N 个报文的目的终端。两项省略时均取 1，所以旧配置默认投递给 STA 1。超出范围的目的 ID 会在启动仿真前报错。

所有终端共用同一个 SFU 的四类队列和无线发送资源。轨迹的 `destination_sta_id` 是目标 ID，`DELIVERED` 中的 `sta_id` 是实际接收终端。每台 STA 有一行 `summary=STA`，记录收包数、收到的字节数、有效吞吐量和平均端到端时延。吞吐量用有效交付比特数除以从 0 到最后事件的公共时间窗口，单位为 Mb/s。窗口为零时吞吐量记为 0。没有收包的终端平均时延记为 0。

### 多 SFU 独立下行发送

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/phase1-multi-sfu-downlink.properties
```

`sfu.count` 设置 SFU 数量，ID 从 1 连续编号。`sta.N.sfu.id` 把 STA N 固定关联到指定 SFU。例如 `sta.1.sfu.id=2` 表示发给 STA 1 的报文由 MFU 经第 2 条逻辑光链路转发。`sfu.count` 和未填写的关联均默认为 1，超出有效范围的关联在仿真启动前报错。

每台 SFU 各自维护四类 FIFO 和无线发送状态。单台 SFU 串行、非抢占发送，不同 SFU 可以并行。各条逻辑光链路和无线链路使用同一组固定时延参数。轨迹从 `FIBER_TX_START` 起附带 `sfu_id`，可用 `packet_id` 串起 MFU、SFU 和实际收包的 STA。

`summary=SFU` 给出逐 SFU 的发送量、吞吐量和平均排队时延。与 STA 一样，吞吐量以公共仿真窗口计算。全局 `wifi_busy_us` 是**至少一台 SFU 正在发送**的时长，重叠区间只计一次。`wifi_idle_us` 是所有 SFU 均未发送的时长。

### 泊松下行业务

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/poisson-multi-sfu-downlink.properties
```

省略 `traffic.mode` 或设为 `manual` 时沿用手动报文列表。使用 `traffic.mode=poisson` 时，保留拓扑和链路配置，用下表中的参数替换 `packet.arrival.us` 和 `packet.*` 字段。`N` 是从 1 开始的 SFU ID。

| 配置项 | 含义 |
| --- | --- |
| `traffic.duration.us` | 必填。所有 SFU 共用的最长生成窗口，单位为 μs，取正整数。 |
| `traffic.sfu.N.rate.pps` | 该 SFU 的平均到达率，单位为包/s，可为小数。省略或设为 0 时该 SFU 不生成报文。 |
| `traffic.sfu.N.packet.bytes` | 固定报文长度，正整数。到达率为正时必填。 |
| `traffic.sfu.N.seed` | 随机种子，有符号 64 位整数。到达率为正时必填。 |
| `traffic.sfu.N.duration.us` | 可选。该 SFU 的生成窗口，取不大于公共窗口的正整数。 |
| `traffic.sfu.N.class.weights` | 可选。VO、VI、BE、BK 的四个非负相对权重，至少有一个为正。`1,2,6,1` 表示 10%、20%、60%、10%，默认 `0,0,1,0`。 |

各 SFU 独立采样指数到达间隔。程序累计连续时刻后向下取整到微秒，同一微秒允许生成多包。报文按到达时刻全局编号，同一时刻按 SFU ID 排序。每包均匀随机选择该 SFU 关联的一台 STA，并按权重抽取业务类别。到达率为正但没有关联 STA 时，配置加载会报错并指出 SFU ID。

每台 SFU 在自己的 `[0, duration)` 窗口内生成报文。窗口结束后仿真继续完成已生成的报文，`simulation_end_us` 记录最后的仿真事件时刻。固定种子和相同配置可复现报文序列。

`summary=TRAFFIC_SFU` 和 `summary=TRAFFIC_CLASS` 分别给出 SFU 总量及其各业务类别的生成包数、字节数、实际到达率和平均输入负载。`actual_rate_pps` 按生成包数除以该 SFU 的窗口秒数计算，`offered_load_mbps` 按生成比特数除以该 SFU 的窗口微秒数计算。交付吞吐量仍按原有仿真窗口统计。

Java 代码按职责分为入口 `App`、配置 `config`、事件执行 `simulator`、设备与报文 `entity`、链路 `link` 和轨迹 `trace`。基础版的自动化验收覆盖单包时序、同一 SFU 串行发送、多 STA 转发、多 SFU 并行及报文和字节守恒。各场景的预期结果见[仿真验收用例](docs/04-validation-cases.md)。

## 后续研究计划

第四章计划研究在业务负载、无线信道条件和业务时延约束变化时，AP 如何在低容量模式（LCM）与高容量模式（HCM）之间选择，并用总能耗、吞吐量、时延和超期比例评估策略。

| 阶段 | 模块和核对项 |
| --- | --- |
| 2. AP 能耗 | 加入 LCM/HCM 和无线状态，核对状态时长、能耗分项及手算结果 |
| 3. 信道与干扰 | 加入次信道占用、带宽回落、误包与重传，区分等待和重传的代价 |
| 4. QoS 与切换 | 先检查截止时间，再比较预测能耗与切换代价，记录决策原因 |
| 5. 信道观测 | 比较关闭信道信息、仅用历史、历史加按需探测三种方式 |
| 6. 实验与结果 | 扫描负载与次信道占用，对比固定模式、阈值和 ECQ-SDPS |

## 实验文件

`output/` 用于存放本地生成的仿真结果和图表。需要纳入版本控制的实验配置、参考数据和代表性结果可放在该目录之外。
