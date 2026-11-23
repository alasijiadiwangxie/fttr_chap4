# C2a SFU–STA 信道状态与 AP 观测

C2a 在每条 SFU–STA 链路上建立独立的信道时间线。80 MHz 按四个 20 MHz 子信道组织，编号 0 是主信道。仿真内部的时间线描述信道质量与干扰状态，AP 的只读视图来自本地已经发生的事件及已到达的 STA 反馈。

## 配置与时间口径

| 配置键 | 默认值 | 输入格式 |
| --- | --- | --- |
| `wifi.channel.observation.enabled` | `false` | `true` 开启本阶段的观测和日志。 |
| `wifi.channel.truth` | 空 | `sfu,sta,subchannel,effectiveUs,sinrDb,cqi,interfered`，分号分隔多条记录。 |
| `wifi.channel.feedback` | 空 | `sfu,sta,subchannel,measuredUs,delayUs,validUs`，分号分隔多条请求。报告 ID 按输入记录从 1 编号。 |
| `wifi.channel.observe.us` | 空 | 逗号分隔的显式观测时刻。 |

各条记录通过 SFU、STA 和子信道三元组索引。SINR 单位为 dB，CQI 可为 0–15 或 `-1`（未知），干扰标志取 `true` 或 `false`。同一子信道的真值从生效时刻（含该时刻）保持到下一次变化。首条记录之前的真值未知。配置使用 μs，内部时序和结果使用整数 ns。

## 观测因果关系

一次逻辑反馈包含关联的 SFU、STA 和子信道，测量时刻、报告到达时刻及有效期限，以及测得的 SINR/CQI。测量时读取该时刻生效的信道真值，报告保存该次测量结果。AP 只有在到达事件处理后才能读取有效的报告。等待期间发生的信道变化不改写在途报告。

报告的有效范围为 `[measured + delay, measured + valid)`。若到达时已达有效期终点，则记录 `EXPIRED`。相同或更早测量时刻的报告迟到时记录 `STALE`，保留最新的已接收测量时刻。最新报告过期后该子信道观测变为 `EXPIRED`。没有生效真值的测量记录为 `NO_TRUTH`，尚未接受任何反馈的子信道保持 `UNKNOWN`。

LCM 的三个次信道返回 `MODE_LIMIT`，主 20 MHz 的报告按正常有效期读取。HCM 可以分别读取四个子信道的有效观测。已有 B4 手动切换在实际完成后更新可见范围。旧版固定时延 Wi-Fi 只开放主信道，旧版 HE SU 按配置带宽开放 1、2 或 4 个子信道，最多对应本模型的 80 MHz。资料字段在 `VISIBLE` 时提供，并带原测量、到达和过期时间。

本地证据来自 AP 解码成功的 UL DATA、收到的 DL ACK 或 DL Block ACK，按链路记录最近时刻和观察到的 MPDU 数。它与 CQI/SINR 报告分开。信道真值由 `ChannelTruth` 保存在仿真层，`ApChannelObservations` 只保留实际接收的报告及本地证据。SFU 通过 `channelReport`、`channelVisibility` 和 `localChannelReception` 提供只读查询。

同刻的顺序为信道真值生效、STA 测量、反馈到达，再执行协议事件，最后发布显式观测。因此在同一时刻生成的反馈可以在零延迟下立即可见，真实 DATA/ACK 也可进入同刻快照。观测调度独立推进已配置的有限时刻，原有协议运行结束时间和统计窗口仍由原协议决定。

本阶段使用脚本触发的理想 STA 测量和报告，量测可用范围由脚本指定。报告到达时刻用于检验因果关系和时效，帧传输仍使用原有 PHY 速率、EDCA 信道访问、A‑MPDU 和 AP 活动计时。信道真值与 PHY 的误包、MCS 及能耗调度解耦。

## 验证设计

在两台 SFU 和三台 STA 的混合上下行业务上交换 HCM/LCM 配置。各组 Java 对照的配置差异只有观测开关。第一台 SFU 关联两台 STA，可同时检查同一 SFU 内的链路隔离和跨 SFU 隔离。固定 1500 B 报文、EDCA 随机种子 101，并启用 B3 能耗、C1 本地队列采样和 B6 A‑MPDU。首个下行聚合中的第 2、4 包使用 B6 的确定性首次失败注入。

对账脚本独立重放配置中的真值、测量和到达时序，按链路计算每个观测的可读状态与值。它从原协议日志重新计算 AP 的 DATA/ACK/Block ACK 证据，并逐行比较开启与关闭时的全部原有输出。

## 实测结果

| 对照场景（SFU 1／SFU 2） | 真值变化 | 反馈事件 | AP 观测 | 本地证据 | 逐行匹配的原有输出 |
| --- | ---: | ---: | ---: | ---: | ---: |
| HCM／LCM | 16 | 35 | 504 | 7 | 427 |
| LCM／HCM | 16 | 35 | 504 | 7 | 571 |

每组的 18 次测量请求产生 17 次有效测量、1 次 `NO_TRUTH`。到达的报告中 14 次被接受、2 次过旧、1 次到达时已过期。两个场景分别通过逐事件对账。原有输出中包含 B6 聚合与重传、C1 窗口采样、吞吐量、时延和 B3 能耗。

以下时序为 HCM／LCM 场景的 SFU 1–STA 1 主信道。100.250 μs 生效的新真值将 SINR 从 28.5 dB 改为 6.5 dB，直到新报告实际到达后 AP 才可读取 6.5 dB。

| 读时刻（μs） | AP 状态 | 报告测量时刻（μs） | 可见 SINR（dB） |
| ---: | --- | ---: | ---: |
| 159.999 | `UNKNOWN` | — | — |
| 160 | `VISIBLE` | 40 | 28.5 |
| 200.249 | `VISIBLE` | 40 | 28.5 |
| 200.250 | `VISIBLE` | 100.250 | 6.5 |
| 215 | `VISIBLE` | 100.250 | 6.5 |
| 250.249 | `VISIBLE` | 100.250 | 6.5 |
| 250.250 | `EXPIRED` | — | — |
| 295 | `EXPIRED` | — | — |
| 300 | `VISIBLE` | 300 | 24.0 |

在 70 μs，SFU 2–STA 3 的子信道 1 报告已到达。HCM／LCM 场景返回 `MODE_LIMIT`，调换 AP 模式的场景返回 `VISIBLE` 和 30 dB。4600 μs 的报告到达和 4700 μs 的显式观测在原协议时间线结束后仍可独立复核。二次运行生成的三份 CSV 和四份实际配置逐字节一致。

完整 Maven 回归 **460 项通过，0 失败、0 错误、0 跳过**，包含 C2a 的 6 项模型测试和 18 项集成测试。冻结的 9 个 PON 用例均匹配。B4 同刻测试核对了模式切换实际完成时对次信道可见范围的更新。

## 复现

在项目根目录运行下列命令。

```powershell
mvn -o verify
python -B tools/verify_c2a_channel_observation.py
python -B tools/verify_pon_final.py
```

- [信道真值事件](../../results/c2a-channel-observation/c2a-channel-truth.csv)
- [AP 可见观测](../../results/c2a-channel-observation/c2a-ap-observations.csv)
- [反馈事件时序](../../results/c2a-channel-observation/c2a-feedback-events.csv)
- [实际运行配置](../../results/c2a-channel-observation/configs)
- [配置生成与独立对账脚本](../../tools/verify_c2a_channel_observation.py)

脚本按实际 Java 输出生成三份 CSV。每行带场景名和原始输出序号，`t_ns` 为事件或显式检查的时间。
