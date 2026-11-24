# C2b‑1 下行 SINR、MPDU 解码与重传

## 启用与参数

| 配置键 | 默认值 | 含义 |
| --- | --- | --- |
| `wifi.phy.per.enabled` | `false` | 开启 C2b‑1 的下行 DATA 解码。使用 HE SU、`edca-multi-ac` 和 DATA–SIFS–ACK。 |
| `wifi.phy.per.seed` | `1` | 误包抽样的种子，独立于 EDCA 及业务生成器。 |
| `wifi.phy.per.reference.mpdu.bytes` | `1530` | 配置曲线对应的参考 MPDU 长度。 |
| `wifi.phy.per.curves` | 由场景提供 | 每条记录为 `mcs,sinrDb,per`，以分号分隔。同 MCS 至少两个点，SINR 严格递增，PER 非递增，范围为 0 到 1。 |
| `wifi.phy.per.source` | 由场景提供 | 启用时填写曲线来源。验收用例填写 `SYNTHETIC_TEST`。 |
| `wifi.channel.truth` | 由场景提供 | 沿用 C2a 的 `sfu,sta,subchannel,effectiveUs,sinrDb,cqi,interfered` 时序。 |

开启 C2b‑1 后，PHY 在每次非碰撞下行 PPDU 的起点读取对应链路的 C2a 真值。四个子信道须已有质量记录，且 SINR、CQI 和干扰布尔值一致。有效数据带宽限于 80 MHz 及以下。起点开始生效的值计入该次 PPDU，途中发生的变化留给后续尝试。实际 MCS 使用该 STA 的 PHY 配置。AP 接收 C2a 报告的流程独立，信道真值仅用于仿真端解码和诊断。

## 误包模型与边界

参考长度的概率 `P_ref` 由已配置 MCS 对应的 SINR–PER 折线给出，曲线外取最近端点。令 `L` 为实际 MPDU 字节数，`L_ref` 为参考字节数，则

```text
PER(L) = 1 − (1 − P_ref)^(L / L_ref)
```

实际 MPDU 使用报文长度加配置的 MAC 开销，减去 4 B delimiter。默认配置下，1500 B 业务报文对应 1530 B MPDU。聚合填充和分隔符仍计入 PPDU 时长。长度换算假设错误机会随长度同比增长。模型按种子、SFU、STA、报文 ID 与从 0 起算的尝试序号混合出 `[0,1)` 的抽样值，抽样值小于 PER 时该 MPDU 解码失败。各成员分别抽样，重传根据新的 PPDU 起点状态再次计算。

专项场景的 MCS 7 参考曲线使用 −12 dB → 1、10 dB → 0.5、30 dB → 0 三个人工点，分别覆盖必败、部分失败和必定成功。它们用于功能审计，未经硬件测量或链路级校准。条件独立的 MPDU 抽样和 PPDU 起点冻结是仿真假设。ACK、Block ACK 和上行 DATA 的射频误包仍沿用原模型。现阶段不考虑频率选择性、PPDU 内的时变解码、速率自适应或新增干扰竞争，也不叠加 B6 的首次失败测试注入。

## DATA 失败、确认和能耗

- 成功的单个 MPDU 在 DATA 结束时交付，随后通过原有 SIFS 和 ACK 确认。单包解码失败时，发送端等待 ACK 超时，进入原有重试或丢弃流程。
- 聚合中存在成功成员时，Block ACK 位图仅确认解码成功的成员。未确认成员保留原入队时刻并按原 FIFO 顺序重试。聚合成员全部失败时等待 Block ACK 超时。达到重试上限后产生最终丢弃。
- MAC 碰撞沿用已有竞争和超时路径。逐 MPDU 事件保留碰撞类别，并将 SINR、PER 和随机抽样字段置为 `-`。
- C1 在 DATA 开始时将 MPDU 标记为在途。成功数据从交付到确认仍属于在途，失败 MPDU 在结算时回到待发队列或从在途转为丢弃，重试只添加实际发送尝试。队列复核采用 **已到达 = 待发 + 在途 + 已确认 + 最终丢弃**。
- B2 按实际 DATA 或响应帧区间计 RX/TX。失败的下行 DATA 仍占 AP TX，DATA 发完即释放实体信道。该次发送的超时等待没有对应的 RX 区间，其它设备的实际收发照常记账。B3 使用相同 B2 状态区间积分。

## 验证和复现

验收包含固定 LCM/HCM 的单包高、低 SINR，真值在 PPDU 启动同刻和开始后 1 ns 变化，两组种子的部分确认，不同实际 MPDU 字节数，全失败聚合，真实 MAC 碰撞，以及关掉 C2b‑1 的 B6 兼容场景。Python 从真实 Java 事件重新核对曲线插值、长度换算、抽样值、ACK/Block ACK 或超时、C1 队列和 B2/B3 能耗守恒。

所有场景使用 1 SFU、1 STA。B3 固定观察窗为 2000 μs，C1 采样周期为 50 μs，ACK 超时为 60 μs，EDCA 种子为 101。除了真实碰撞试验将重试上限设为 0，其余场景设为 1。高 SINR 单包场景为 LCM，其余为 HCM。表中交付数为下行唯一报文数，重传按实际 MPDU 尝试计数，超时按等待响应的 PPDU 计数。

| 场景 | 解码成功（唯一交付） | SINR 失败 | MAC 碰撞 MPDU（DL） | 重传 | 超时 | 最终丢弃（DL） | AP TX / RX（μs） | B3 能耗（μJ） |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 高 SINR 30 dB | 2 | 0 | 0 | 0 | 0 | 0 | 385.6 / 56 | 15507.712 |
| 低 SINR −12 dB | 0 | 4 | 0 | 2 | 4 | 2 | 310.4 / 0 | 16133.952 |
| 同刻 SINR 变化 | 1 | 2 | 0 | 1 | 2 | 1 | 232.8 / 28 | 15940.704 |
| 部分聚合失败，种子 77 | 4 | 2 | 0 | 2 | 0 | 0 | 223.2 / 64 | 15951.936 |
| 部分聚合失败，种子 101 | 4 | 1 | 0 | 1 | 0 | 0 | 209.6 / 60 | 15908.448 |
| 230 B 和 830 B MPDU | 2 | 1 | 0 | 1 | 1 | 0 | 192 / 56 | 15853.440 |
| 整个聚合失败 | 0 | 8 | 0 | 4 | 2 | 4 | 264 / 0 | 16000.320 |
| 同 AP 下行与上行真实碰撞 | 0 | 0 | 1 | 0 | 2 | 1 | 77.6 / 0 | 15463.488 |

种子 77 的首个聚合在 43 μs 开始、175 μs 完成 DATA。AP 当时持有此前测得的 30 dB 报告，解码器读取起点真值 10 dB，ID 1、4 的抽样值低于 0.5。尽管真值在 43.001 μs 再次变化，首个 Block ACK 在 223 μs 仍返回位图 `0x6`，只确认 ID 2、3。C1 在 200 μs 显示 4 个在途成员、2 个已交付待确认，在 250 μs 显示余下的 2 个待发。266 μs 的重传使用当时真值 30 dB，405.2 μs 的位图 `0x3` 确认剩余成员。固定同一配置重放时完整 Java 输出逐字节一致。

长度试验在相同的 12 dB 和 MCS 7 下，230 B、830 B 的 MPDU 分别得到 PER 约 0.08595、0.27698。低 SINR 的单包用例和全失败聚合均记录实际 DATA TX，未产生 ACK 或 Block ACK 接收时长。真实碰撞用例单独记录 1 个下行碰撞 MPDU，另有 1 个上行碰撞 MPDU；两者都沿用 MAC 超时结算。关闭 C2b‑1 的兼容场景保留 B6 的测试注入，原有事件和摘要逐字节匹配。两份反例配置分别因四个子信道质量不一致和缺失子信道真值而被 Java 明确拒绝。

本阶段定向 Maven 共 **105 项通过，0 失败、0 错误、0 跳过**。其中 6 个模型测试和 13 个端到端测试覆盖实际 MCS、抽样、误包重传、非平坦输入、能耗和 TXOP 失败后重争用。独立重放核对了 9 个运行场景和 2 个预期拒绝场景，生成的 32 条逐 MPDU 事件、534 条重传及队列事件和 12 份实际配置逐字节一致。

- [各 MPDU、各尝试的解码事件](../../results/c2b1-sinr-per/c2b1-mpdu-decode.csv)
- [重传、队列窗口及能耗对账](../../results/c2b1-sinr-per/c2b1-retry-queue.csv)
- [实际执行的场景配置](../../results/c2b1-sinr-per/configs)
- [验证及 CSV 生成脚本](../../tools/verify_c2b1_sinr_per.py)

在项目根目录运行定向测试和数据复现。

```powershell
mvn -o '-Dtest=SinrPerModelTest,SinrPerSimulationTest,AmpduSimulationTest,EdcaRetryTest,EdcaTxopTest,ChannelObservationSimulationTest,ApTrafficObservationSimulationTest,ApEnergySimulationTest' package
python -B tools/verify_c2b1_sinr_per.py
```
