# FTTR 仿真基础版

本项目使用 Java 和离散事件模拟 FTTR 的上下行单播。下行由 MFU 按目的 STA 选择 SFU，上行由 STA 经关联 SFU 发往 MFU。拓扑可配置为一个 MFU、多台 SFU 和多台 STA。

## 模型范围与假设

- 时间配置与日志以微秒为单位。HE SU 或 PON 下行共享模式使用整数纳秒事件时钟。光侧和无线侧都使用固定时延的场景采用整数微秒。报文按手动配置或泊松生成的时刻注入源节点，事件处理完毕后结束。
- 每台 STA 在一次仿真中固定关联一台 SFU。光侧默认采用双向固定时延。启用 PON 下行共享模式后，发往各 SFU 的报文共用 MFU 的一个 FIFO 队列和发送器。
- 每台 SFU 与关联 STA 共用一份无线资源。默认按 VO、VI、BE、BK 固定优先级选队。启用 `edca-multi-ac` 后，SFU 和 STA 各自按四类 FIFO 队列竞争信道，同一信道的多个设备同刻发送会发生碰撞。不同 SFU 可以并行收发。
- 等待队列不设容量上限。HE SU 模式根据包长、开销和 PHY 参数计算数据 PPDU 时长，可启用 DATA–SIFS–ACK 交换。固定时延模式可用于回归对照。
- SFU 和 STA 的吞吐量统一按从 0 到最后事件的时间窗口计算。日志同时记录逐包时序，以及按业务类别、设备和全局汇总的统计。

信道干扰、OFDMA、DBA 和 AP 节能机制可作为后续独立扩展。

## 构建与运行

使用 JDK 11 或更高版本和 Maven 3.9，从仓库根目录构建并运行。`mvn clean package` 会先运行自动化测试，再生成 JAR。

```powershell
mvn clean package
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar
```

JAR 无参数时运行随包附带的固定时延回归场景，其中包含一条 1200 字节的下行报文。关键轨迹如下：

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

手动场景需包含 `packet.arrival.us`，并提供共用 `packet.bytes` 或逐包长度。光侧采用固定时延时需配置 `fiber.delay.us`。报文长度必须为正整数，到达时刻和固定链路时延为非负整数。链路的零时延可用于检验同一时刻的事件顺序。

### 同一 SFU 的多报文串行发送

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/phase1-serial-downlink.properties
```

`packet.arrival.us` 也接受逗号分隔的 MFU 到达时刻，如 `0,0,250,1000`。程序按时间排序，同刻报文按配置顺序编号。报文共用两段固定链路时延，光链路仅对每条报文引入固定到达时差。

同一 SFU 按业务类型分别排队，类型内按到达顺序发送。固定时延场景的无线资源从 `WIFI_TX_START` 占用到 `WIFI_TX_END`，以 STA 完整接收为结束边界。前一包完成的同一微秒可以启动下一包。逐包轨迹列出排队时延、占用时长和端到端时延，多包汇总记录无线忙时与空闲时间。空闲时间以仿真时间 0 至最后事件为窗口，包括首次无线发送前的时间。

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

下行报文按业务类型进入同一个 SFU 的四类发送队列，并使用该 SFU 的无线资源。轨迹的 `destination_sta_id` 是目标 ID，`DELIVERED` 中的 `sta_id` 是实际接收终端。每台 STA 有一行 `summary=STA`，记录收包数、收到的字节数、有效吞吐量和平均端到端时延。吞吐量用有效交付比特数除以从 0 到最后事件的公共时间窗口，单位为 Mb/s。窗口为零时吞吐量记为 0。没有收包的终端平均时延记为 0。

### 多 SFU 独立下行发送

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/phase1-multi-sfu-downlink.properties
```

`sfu.count` 设置 SFU 数量，ID 从 1 连续编号。`sta.N.sfu.id` 把 STA N 固定关联到指定 SFU。例如 `sta.1.sfu.id=2` 表示发给 STA 1 的报文由 MFU 经第 2 条逻辑光链路转发。`sfu.count` 和未填写的关联均默认为 1，超出有效范围的关联在仿真启动前报错。

每台 SFU 各自维护四类 FIFO 和无线发送状态。单台 SFU 串行、非抢占发送，不同 SFU 可以并行。此示例的逻辑光链路和无线链路均采用固定时延。轨迹从 `FIBER_TX_START` 起附带 `sfu_id`，可用 `packet_id` 串起 MFU、SFU 和实际收包的 STA。

`summary=SFU` 给出逐 SFU 的发送量、吞吐量和平均排队时延。与 STA 一样，吞吐量以公共仿真窗口计算。全局 `wifi_busy_us` 是至少一台 SFU 的无线资源处于占用状态的时长，重叠区间只计一次。`wifi_idle_us` 是所有 SFU 的无线资源均空闲的时长。

### PON 下行共享发送

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/pon-shared-downlink.properties
```

| 配置项 | 含义 | 默认值 |
| --- | --- | --- |
| `fiber.downlink.mode` | 下行光侧模式。设为 `pon-shared` 启用共享发送器 | `fixed` |
| `fiber.downlink.rate.mbps` | 共享发送器速率，使用正数 Mb/s | 10000 |
| `fiber.downlink.propagation.us` | 串行化完成后的传播时延，使用非负数 μs，精度至 0.001 μs | 0 |

两项共享模式专用参数仅用于 `pon-shared`。共享下行的传播时延由 `fiber.downlink.propagation.us` 独立指定。原有 `fiber.delay.us` 用于固定时延光路，在共享下行场景中仅供已有上行路径使用。

MFU 按入队顺序选取下行报文，手动配置中同刻到达的报文按列表顺序入队。发送器占用时间为 `packetBytes × 8 ÷ rateMbps` μs，直接按报文字节数计算。时长恰好落在纳秒刻度时保留原值，其余向上取到下一个纳秒。例如 10 Gbps 下，1 B 的理论发送时间为 0.8 ns，仿真中占用 1 ns。发送完毕即释放发送器，随后报文经过传播时延到达目标 SFU。目标 SFU 收齐报文后按所选无线接入模式调度。

示例中两个 1200 B 报文在 0 μs 同时到达 MFU，先发往 SFU 2，再发往 SFU 1。每个报文的发送时间为 0.96 μs，传播时延为 10 μs。

| 报文 | 目标 SFU | 发送开始（μs） | 发送完成（μs） | 到达 SFU（μs） |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 2 | 0 | 0.96 | 10.96 |
| 2 | 1 | 0.96 | 1.92 | 11.92 |

第二个报文在第一个报文的传播期间发送。共享 FIFO 的平均等待为 0.48 μs，发送器累计忙时为 1.92 μs。

逐包轨迹用 `PON_DOWNLINK_ENQUEUE`、`FIBER_TX_START` 和 `FIBER_TX_END` 记录入队、发送开始和发送完成，随后由目标 SFU 记录 `ARRIVAL`。可按 `packet_id` 和 `sfu_id` 核对各时刻及路径。

`summary=PON_DOWNLINK` 记录发送报文数 `sent_packets`、平均队列等待 `avg_queue_wait_us`、平均串行化时延 `avg_serialization_us`、累计忙时 `busy_us`、观察窗口 `window_us` 和利用率 `utilization`。累计忙时只计入串行化。观察窗口从时间 0 算到最后一个仿真事件，包含传播、无线传输及空闲时间。利用率为忙时除以观察窗口，范围为 0 至 1。

### 泊松下行的总速率和权重配置

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

流量生成与链路传输分开。配置层为每条流提供方向、源节点和候选目的节点，生成器输出携带报文的 `PACKET_GENERATED` 事件。`Packet` 记录生成时刻、业务类型及源和目的节点 ID。方向决定 ID 对应的节点类型，`DL` 表示 MFU → STA，`UL` 表示 STA → MFU。各 `traffic.sfu.N.*` 配置的是发往该 SFU 关联终端的下行流，报文源始终是 MFU 1。仿真器收到入口事件后，从 MFU 注入现有下行流程。

`summary=TRAFFIC_SFU` 和 `summary=TRAFFIC_CLASS` 分别给出 SFU 总量及其各业务类别的生成包数、字节数、实际到达率和平均输入负载。`actual_rate_pps` 按生成包数除以该 SFU 的窗口秒数计算，`offered_load_mbps` 按生成比特数除以该 SFU 的窗口微秒数计算。交付吞吐量仍按原有仿真窗口统计。

### STA 上行与双向业务

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/poisson-multi-sta-uplink.properties
```

`traffic.direction` 支持 `DL`（默认）、`UL` 和 `BOTH`。泊松模式下，上行使用 `traffic.sta.N.*` 按源 STA 配置。使用总速率加权重时，字段与下行相同，即 `rate.pps`、`packet.bytes`、`seed`、`class.weights` 和可选的 `duration.us`。`BOTH` 可同时配置 `traffic.sfu.N.*` 和 `traffic.sta.N.*`。手动模式下，`packet.N.sta.id` 在上行表示源 STA，在下行表示目的 STA，双向模式还须为每包设置 `packet.N.direction=UL` 或 `DL`。`packet.arrival.us` 始终表示报文进入源节点的时刻。

上行报文在源 STA 等待关联 SFU 的无线资源，SFU 收齐报文后立即走固定时延光链路，抵达 MFU 时计为交付。默认接入模式的四类等待队列是集中式调度抽象。上下行共用同一台 SFU 的无线资源，其他 SFU 独立运行。

`TRAFFIC_UL_STA` 与 `TRAFFIC_UL_CLASS` 按源 STA 输出总量及各业务的生成包数、实际到达率和输入负载。`UL_STA` 与 `UL_GLOBAL` 统计 MFU 收到的上行报文，时延从 STA 生成算到 MFU 收齐。`CLASS`、`GLOBAL`、`SFU` 和 `STA` 汇总仍对应下行。最后一行的 `delivered_packets` 为两个方向的交付总数。

### 为每类业务设置独立的泊松流

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/poisson-per-class-uplink.properties
```

同一台 STA 的 VO、VI、BE、BK 可以分别使用不同包长和速率。配置 `traffic.sta.N.class.C.*` 即为该 STA 启用逐类模式。下行按目标 SFU 配置，前缀用 `traffic.sfu.N.class.C.*`。将 `C` 替换为大写的 `VO`、`VI`、`BE` 或 `BK`。

| 字段 | 含义 |
| --- | --- |
| `class.C.rate.mbps` 或 `class.C.rate.pps` | 分别以 Mb/s 或包/s 指定该类别的平均输入负载。每类只填一个速率字段。省略或设为 0 时停用该类。 |
| `class.C.packet.bytes` | 该类报文的固定字节数，速率为正时必填。 |
| `seed` | STA 或 SFU 的种子，有类别启用时必填。各类使用独立的随机流。 |
| `duration.us` | 可选。该 STA 或 SFU 所有类别共用的生成窗口，省略时使用 `traffic.duration.us`。 |

逐类模式使用 `class.C.*` 代替同一前缀下的总量字段 `rate.pps`、`packet.bytes` 和 `class.weights`。同一场景的其他 STA 或 SFU 仍可用总速率加权重的配置。

示例中，STA 1 的 VO、VI、BE、BK 分别为 5、10、3、2 Mb/s，包长分别为 200、1000、1500、750 B。STA 2 仅生成 10 Mb/s 的 VO。指定 Mb/s 时，程序按 `Mb/s × 1,000,000 ÷ (8 × 包长)` 换算为平均包/s，然后为各类独立采样到达间隔。不同包长下，按包数和按字节计算的业务比例可能不同，可在 `TRAFFIC_UL_CLASS` 和 `TRAFFIC_CLASS` 中核对。

### 静态 802.11ax HE SU PHY

```powershell
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/he-su-phy-downlink.properties
```

新场景省略 `wifi.delay.us` 时默认使用 HE SU，也可显式设为 `wifi.model=he-su`。旧配置只要给出 `wifi.delay.us` 就保留固定时延行为，也可显式设为 `wifi.model=fixed`。两种模式的专用字段不可混用。

| HE SU 配置项 | 取值 | 默认值 |
| --- | --- | --- |
| `wifi.phy.bandwidth.mhz` | 20、40、80、160 MHz | 160 |
| `wifi.phy.mcs` | 0–11 | 11 |
| `wifi.phy.nss` | 1–8 条空间流 | 2 |
| `wifi.phy.gi.us` | 0.8、1.6、3.2 μs | 0.8 |
| `wifi.phy.he-ltf.type` | 1、2、4，表示 HE-LTF 类型，须与 GI 匹配 | GI 为 0.8/1.6 时取 2，GI 为 3.2 时取 4 |
| `wifi.phy.mac.overhead.bytes` | 加在业务包长上的封装字节数 | 34 |
| `wifi.phy.nominal.packet.padding.us` | 接收端标称填充需求 0、8、16 μs，用于推导实际 PE | 0 |

一个仿真报文对应一个 HE SU 数据 PPDU。默认封装 34 B 对应 26 B QoS MAC 头、4 B FCS 和 4 B MPDU delimiter，业务包长之外的额外封装可计入此字段。静态配置使用 LDPC，按 MCS 的调制阶数、码率、全带宽数据子载波数和 NSS 计算每个符号的数据位数。PHY 速率为每个数据符号的位数除以 `12.8 μs + GI`。

**前导码。** `T_preamble` 为固定的 36 μs 加 HE-LTF 训练字段。1×、2×、4× HE-LTF 的每个符号分别为 `3.2 + GI`、`6.4 + GI`、`12.8 + GI` μs。普通 HE SU 允许 `1×/0.8`、`2×/0.8`、`2×/1.6`、`4×/3.2`。`4×/0.8` 也可用，它对应 HE-SIG-A 特殊信令，DCM 和 STBC 位均置 1，但两种编码操作都不应用。这里假定收发端支持所选的 HE-LTF/GI 组合，未模拟能力协商。无 STBC 时 `NSTS = NSS`，1 至 8 条空间流对应 `1, 2, 4, 4, 6, 6, 8, 8` 个训练符号。见 [IEEE 802.11ax-2021 图 27-8 与 §27.3.11](https://ptacts.uspto.gov/ptacts/public-informations/petitions/1550280/download-documents?artifactId=UHWJ2oZgTs7zxpCXsgW00tuhUMqbKMe159a9qxSLKRDsqJiIOFfQids) 和 [HE-SIG-A 特殊信令对照](https://data.epo.org/publication-server/rest/v1.2/publication-dates/2025-12-03/patents/EP4657975NWA2/document.pdf#page=23)。

**数据符号。** 按封装后的字节数和 16 位 SERVICE 计算初始符号数及最后一个符号的 pre-FEC 填充因子 `a`。Pre-FEC 填充补齐 LDPC 的初始输入边界。模型用码字容量、截短和打孔阈值判断是否需要 LDPC 额外符号段。若需要且 `a < 4`，只增大 `a`，完整数据符号数不变。若 `a = 4`，增加一个完整数据符号，并将最终 `a` 设为 1。Post-FEC 填充只补齐最终符号剩余的编码位，已经包含在该符号的时长中。计算参照 [IEEE 802.11ax-2021 §27.3.12](https://standards.ieee.org/ieee/802.11ax/7180/)。

**PE。** 模型使用未启用 PPE Thresholds 的标称填充规则。下表按**最终** `a` 确定实际添加的 PE，单位为 μs。参见 [MathWorks WLAN Toolbox 的 HE SU PE 表](https://www.mathworks.com/help/wlan/ref/wlanhesuconfig.html)。

| 标称填充 | `a=1` | `a=2` | `a=3` | `a=4` |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 0 | 0 | 0 | 0 |
| 8 | 0 | 0 | 4 | 8 |
| 16 | 4 | 8 | 12 | 16 |

总时长为 `T_preamble + N_DATA × T_SYM + T_PE`，并包含 PE 一起校验 5484 μs 的上限。模型只计算单 MPDU 的 airtime，无多报文聚合、实际 LDPC 编解码、波形、PPE 阈值协商、midamble 或 2.4 GHz 信号延长。默认的 HE-LTF 选择和 0 μs PE 保留了旧场景的时长。

HE SU 模式用 `summary=WIFI_PHY` 输出 DATA 的 PHY 速率。`wifi_queue_wait_us` 从报文进入无线发送队列算到 DATA 开始。`wifi_service_us` 的范围由下节的交换模式决定。光侧等待和传输按所选光链路模式单独计算。

### DATA–SIFS–ACK 单播交换

在 HE SU 场景中设置 `wifi.mac.exchange=data-sifs-ack`，启用理想信道下的双向单播交换。省略该项或设为 `data-only` 时，`wifi_service_us` 只包含 DATA。固定时延场景沿用固定无线服务时长。

| 配置项 | 含义 | 默认值 |
| --- | --- | --- |
| `wifi.mac.sifs.us` | SIFS 时长，取正整数微秒，供 ACK 和 AIFS 计算共用 | 16 |
| `wifi.mac.ack.rate.mbps` | ACK 的固定基础速率，可取 6、9、12、18、24、36、48、54 Mb/s | 24 |

ACK 速率仅用于 `data-sifs-ack`。DATA 继续使用 HE SU PHY。ACK 按 5 GHz 场景的 20 MHz non-HT OFDM 计算，14 B 包含 FCS。ACK PPDU 时长为 `20 + 4 × ceil((16 + 8 × 14 + 6) / (4 × rate))` μs，其中 20 μs 包含前导码和 L-SIG，分子中的 16 位和 6 位分别对应 SERVICE 和 BCC 尾位。24 Mb/s 对应 28 μs。模型假定所配置的速率已可用，省略 BSS 控制响应速率选择。参见 [non-HT PPDU 结构](https://www.mathworks.com/help/wlan/gs/non-ht-ppdu-structure.html)和 [mac80211 的 OFDM 时长计算](https://cos.googlesource.com/third_party/kernel/+/5e7779f0395833d80d8fa15933737c8dc48759ec/net/mac80211/util.c#137)。

日志依次记录 `WIFI_TX_START`、`WIFI_DATA_END`、`WIFI_SIFS_START`、`WIFI_ACK_START`、`WIFI_ACK_END` 和 `WIFI_TX_END`。DATA 由原发送端发出，ACK 由 DATA 接收端反向发出，日志中的报文字段均指原 DATA。DATA 收齐时，下行交付到 STA，上行由 SFU 发往光链路。SIFS 和 ACK 计入无线资源占用，端到端时延仍按 DATA 到达最终目的节点计算。

`WIFI_TX_END` 记录单帧交换完成，`wifi_service_us` 包含 DATA、SIFS 和 ACK。单帧接入在该时刻释放资源，TXOP 的释放时刻由 `WIFI_TXOP_END` 标记。示例按默认接入模式运行时，首个 1500 B 报文在 100 μs 开始无线发送，在 164 μs 交付 DATA，在 180 μs 开始 ACK，并在 208 μs 释放资源。该次交换占用 108 μs，下一包可在 208 μs 启动。模型采用理想信道和零无线传播时延。

### EDCA 参数与 AIFS

`wifi.mac.sifs.us` 和 `wifi.mac.slot.us` 分别配置 SIFS 和 Slot Time，均取正整数微秒，默认分别为 16 和 9。`data-only` 场景也可通过这两个参数计算 AIFS。每类业务可单独覆盖 `wifi.mac.edca.<AC>.aifsn`、`wifi.mac.edca.<AC>.cwmin` 和 `wifi.mac.edca.<AC>.cwmax`，其中 `<AC>` 取大写的 `VO`、`VI`、`BE` 或 `BK`。省略的字段取下表中的场景默认值。

| AC | AIFSN | CWmin | CWmax | AIFS（μs） |
| --- | ---: | ---: | ---: | ---: |
| VO | 2 | 3 | 7 | 34 |
| VI | 3 | 7 | 15 | 43 |
| BE | 6 | 15 | 1023 | 70 |
| BK | 9 | 15 | 1023 | 97 |

`AIFS = SIFS + AIFSN × SlotTime`，结果须在 64 位整数纳秒的时钟范围内。上下行共用的参数中，AIFSN 取 2–15，CWmin 和 CWmax 均取 `2^k − 1`（`k` 为 0–15），且 CWmin ≤ CWmax。[IEEE 802.11-2012 §8.4.2.31 和 §9.19.2.3](https://mrncciew.com/wp-content/uploads/2014/10/ieee-802-11-2012.pdf)

#### 独立 CW 与退避计数器

每台 SFU 和 STA 都为 VO、VI、BE、BK 分别维护独立的 CW 和退避计数器。`wifi.mac.edca.seed` 取有符号 64 位整数，默认值为 `0`。每个随机序列由该种子、设备类型、设备 ID 和 AC 共同确定，与流量生成器的种子独立。相同配置与抽取顺序可以复现各 AC 的退避值。

通过 `edcaState().forCategory(ac)` 获取某个 AC 的状态。CW 初始为 CWmin，计数器初始为空，调用 `drawBackoff()` 时从 `[0, CW]` 均匀抽取并保存整数。竞争失败时调用 `onContentionFailure()`，将 CW 更新为 `min(2 × CW + 1, CWmax)`。发送成功时调用 `onTransmissionSuccess()`，将 CW 恢复为 CWmin。两种更新均清空本次计数器，下一次抽取沿用该 AC 的随机序列。

### CCA 信道忙闲检测

每台 SFU 拥有一个独立信道，SFU 和所有关联 STA 通过 `ccaState()` 查询同一份 `WifiChannel.State`。信道初始为 `IDLE`。单帧成功交换从 DATA 开始到 ACK 完成保持 `BUSY`，TXOP 则持续占用到最后一个 ACK 完成。固定时延和 `data-only` 模式在 DATA 完成后恢复 `IDLE`。跨设备碰撞时，信道在最后一个碰撞 DATA 结束后释放。

报文到达和信道释放先于同一时刻的选队或接入倒计时。同一优先顺序的报文事件按入队顺序处理。不同 SFU 的信道独立，可并行发送。

### AIFS 等待与随机退避倒计时

在 HE SU 下行示例中设置 `wifi.mac.access=edca-countdown`，即可在 DATA–SIFS–ACK 前为选中的单个请求加入接入等待。该接入模式也适用于固定时延和 `data-only` 场景。默认模式为 `fixed-priority`，选中的报文在信道空闲时直接开始发送。

每台 SFU 仍按 VO、VI、BE、BK 的固定优先级选队，一次只有一个请求执行 AIFS 与退避。请求选定后会等到发送，新到达的报文参与下一次选队。下行使用 SFU 的 AC 计数器，上行使用发送 STA 的 AC 计数器，首次需要计数器时抽取退避值。信道连续空闲达到 AIFS 后，每完成一个空闲时隙递减一次。若退避值为零，等完 AIFS 即可发送。无中断时，从选队到 DATA 开始的时间为 `AIFS + 退避值 × Slot Time`。

信道转为 `BUSY` 时保存剩余计数，恢复 `IDLE` 后重新等待完整 AIFS，再沿用剩余计数。同一时刻先处理信道状态变化，再处理倒计时事件。如果时隙结束时信道转为 `BUSY`，该时隙不计入退避。计数归零且信道空闲时开始发送，成功完成交换后恢复发送设备的 CWmin 并清空计数。`wifi_queue_wait_us` 包含接入等待，`wifi_service_us` 从 DATA 开始计时。

### 四类 AC 独立竞争与碰撞处理

取消 HE SU 下行示例中 `wifi.mac.access=edca-multi-ac` 的注释可启用该模式。下行按 SFU 的 AC 队列排队，上行按源 STA 的 AC 队列排队。每个设备的 VO、VI、BE、BK 各有一个 FIFO，队首报文按对应 AC 的 AIFS、CW 和退避计数独立竞争。最先完成倒计时的请求获得发送机会，高优先级只用于同一设备内的同刻仲裁。

同一设备的多个 AC 同刻就绪时，按 VO、VI、BE、BK 的顺序选出获胜 AC。其余就绪 AC 触发虚拟碰撞，更新 CW 后重抽退避计数。`EDCA_VIRTUAL_COLLISION` 日志记录 `winner_packet_id`、`cw_before`、`cw_after` 和 `backoff`。未参与碰撞的 AC 保留剩余计数，在信道恢复空闲后重新等待完整 AIFS。

同刻就绪的 AC 先按设备完成虚拟碰撞仲裁，再由每个设备的获胜 AC 同刻发送。同一信道上的多个设备同刻发送时，所有参与发送的报文均发生真实碰撞。SIFS、ACK、下行交付和上行光侧转发均以 DATA 接收成功为前提。碰撞期间信道保持一个连续忙区间，由最晚结束的 DATA 决定释放时刻。仍在竞争的 AC 在信道恢复空闲后重新等待完整 AIFS。

`WIFI_COLLISION_START` 记录每个碰撞报文的发送开始，`WIFI_COLLISION_END` 记录整次碰撞占用结束。在仅发送 DATA 的场景中，碰撞报文在各自的 DATA 结束时丢弃并记录 `WIFI_COLLISION_DROP`。发生碰撞时，`summary=WIFI_COLLISION` 记录同刻碰撞的组数 `collision_events`，以及最终丢弃的报文数 `dropped_packets` 和字节数 `dropped_bytes`。

### ACK 超时与 MAC 重传

同时设置 `wifi.mac.access=edca-multi-ac` 和 `wifi.mac.exchange=data-sifs-ack` 即可启用。以下配置项适用于这两个模式同时开启的场景。

| 配置项 | 含义 | 默认值 |
| --- | --- | --- |
| `wifi.mac.ack.timeout.us` | 从发送端自己的碰撞 DATA 结束起算的缺失 ACK 等待时间，取正整数微秒 | SIFS + ACK PPDU 时长 + Slot Time，默认参数下为 53 μs |
| `wifi.mac.retry.max` | 每个报文允许的重传次数，取非负整数，首次发送另计 | 3 |

从碰撞开始到自身 ACK 超时，发送设备的四类 AC 暂停接入倒计时。信道在最晚一个碰撞 DATA 结束时恢复空闲。其他设备此时可以开始 AIFS，发生碰撞的设备仍各自等待超时。本模型直接从碰撞 DATA 结束起安排缺失 ACK 计时，正常接收仍按 DATA–SIFS–ACK 处理。

超时后，若报文还有重试额度，则保留原报文和队首位置，将该报文的重试次数加一，由对应 AC 更新 `CW = min(2 × CW + 1, CWmax)` 并重新抽取退避。重试设备恢复接入后再次检查 CCA，从完整 AIFS 开始竞争。`wifi.mac.retry.max=0` 时，首次发送失败后仍等待超时，再丢弃报文。正常收到 ACK 或达到重试上限丢弃时，队首退出且该 AC 的 CW 恢复到 CWmin，下一报文从零开始计数。每个报文沿用原始 `packet_id`，最终只计一次交付或丢弃。

`WIFI_MAC_ATTEMPT` 用 `retry_count=0` 标识首次发送，正值表示重传。发生碰撞时，`WIFI_COLLISION_DATA_END` 和 `WIFI_ACK_TIMEOUT` 分别标记碰撞 DATA 结束和 ACK 超时。`WIFI_MAC_RETRY` 记录新重试次数、更新前后的 CW 和新抽取的退避值。`WIFI_MAC_SUCCESS` 标记 ACK 完成，`WIFI_RETRY_LIMIT_DROP` 标记最终丢弃。`summary=WIFI_RETRY` 输出实际发出的重传次数 `retransmissions`、发送端超时次数 `ack_timeouts` 和重试超限丢包数 `retry_limit_drops`。

### EDCA TXOP

`wifi.mac.edca.<AC>.txop.limit.us` 为每类业务设置 TXOP Limit，取非负整数微秒，VO、VI、BE、BK 默认均为 `0`。取 `0` 时，每帧单独竞争信道。同时启用 `edca-multi-ac` 与 `data-sifs-ack` 后，正值允许获胜设备在同一 TXOP 内继续发送当前 AC 的 FIFO 报文。

TXOP 从首帧 DATA 开始计时，到最后一帧 ACK 完成释放信道。每次收到 ACK 后，只有本设备同一 AC 的下一队首能够在剩余时间内完成帧间 SIFS 及完整的 DATA–SIFS–ACK 时，才按 `ACK → SIFS → DATA` 继续发送。正好用完预算也可发送。整个 TXOP 含帧间 SIFS 都保持 `BUSY`，其他设备和 AC 的计时器按现有 CCA 规则冻结。队列为空或预算不足时在当前 ACK 完成后立即释放。若首帧交换已超过正值上限，该帧仍可进行一次单帧交换，按普通发送统计。争用时发生碰撞的尝试不建立 TXOP，重传按原有 ACK 超时与退避规则重新竞争。

`WIFI_TXOP_START` 记录配置上限 `limit_us`。`WIFI_TXOP_END` 记录实际占用 `duration_us`、成功报文数 `packet_count` 和配置上限。`summary=WIFI_TXOP` 汇总次数 `txops`、各 SFU 的 TXOP 持续时间之和 `total_duration_us` 和成功报文总数 `transmitted_packets`。全局 `wifi_busy_us` 按信道忙区间的并集计时。

在 HE SU 下行示例中启用 `edca-multi-ac`，将 `packet.2.bytes` 改为 `1500`，并设置 `wifi.mac.edca.BE.txop.limit.us=232`。两包的完整交换各为 108 μs，中间间隔 16 μs，正好在一次 232 μs 的 TXOP 内完成。

### NAV 虚拟载波侦听

在 EDCA 的 DATA–SIFS–ACK 场景中，每台 SFU 和 STA 各自维护一个 NAV 到期时刻。有效帧接收结束时，由同一信道上成功旁听的设备更新 NAV，帧的发送端和目的接收端除外。正值 Duration 将到期时刻延至 `max(当前到期时刻, 接收结束时刻 + Duration)`，零值跳过更新。不同 SFU 的信道分别处理各自的预约。[IEEE 802.11-2012 §9.3.2.4](https://mrncciew.com/wp-content/uploads/2014/10/ieee-802-11-2012.pdf)

DATA 的 Duration 在发送开始时确定，覆盖本次 SIFS 和 ACK。TXOP 中，若同一 AC 已排队的下一次交换能放入剩余预算，DATA 的 Duration 还包含帧间 SIFS 和该次完整交换。ACK 通告此次预约的剩余时间。若报文在 DATA 开始后入队并获准续发，原有 TXOP 仍连续占用信道，后续 DATA 再通告预约。Duration 以整数仿真时钟刻度表示。

设备须同时满足物理 CCA 为 `IDLE`、NAV 已到期、ACK 等待已结束，才能开始完整 AIFS 并继续退避。NAV 生效时保留剩余退避计数，既有 ACK 响应仍按 SIFS 调度。实际无线占用由帧交换和 TXOP 决定，NAV 只约束设备何时竞争。回归测试独立构造了物理信道空闲但 NAV 有效的场景，以核对冻结和恢复时序。

Java 代码按职责分为入口 `App`、配置 `config`、事件执行 `simulator`、设备与报文 `entity`、链路 `link` 和轨迹 `trace`。基础版的自动化验收覆盖单包时序、同一 SFU 串行发送、多 STA 转发、多 SFU 并行及报文和字节守恒。各场景的预期结果见[仿真验收用例](docs/04-validation-cases.md)。

EDCA 综合验收见 [测试报告](docs/05-edca-acceptance.md)。构建后运行 `python tools/validate_edca.py`，可复现多 STA 双向业务的三档负载、五个种子和两档 TXOP 对照，输出各 AC 的时延、吞吐量、碰撞、重传、丢包及占用统计。该工具从逐事件轨迹合并上下行，分别计算纯排队与队首接入时延，并核对生成、交付、丢弃和信道忙时。

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
