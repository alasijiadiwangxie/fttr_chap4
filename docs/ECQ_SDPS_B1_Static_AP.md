# B1 静态 AP 能力与 HE SU 发送时长核对

B1 将每台 SFU 的 AP 能力在启动时固定为 LCM 或 HCM，并将 AP 能力与目标 STA 能力取交集后生成该链路的 HE SU PHY。模式贯穿整次仿真。后续的动态模式选择、切换时间和能耗记账以这一静态能力边界为基础。

在 HE SU 场景中用 `sfu.N.wifi.ap.mode=LCM` 或 `sfu.N.wifi.ap.mode=HCM` 为各 SFU 启用 B1。关联 STA 的带宽和空间流上限默认是 80 MHz、2 NSS，能力交集同时用于上下行 DATA。未指定静态模式的 SFU 继续使用原有全局无线时长配置。同一场景可以混用不同模式。

## 对照场景

[LCM 配置](../configs/b1-static-ap-lcm.properties)与 [HCM 配置](../configs/b1-static-ap-hcm.properties)均使用 1 MFU、1 SFU 和 1 STA，两条 1200 B 的 VO 下行报文在 0 μs 生成。光侧使用 0 μs 固定时延。两种模式复用同一份 EDCA 参数、种子 0 和 DATA–SIFS–ACK，TXOP Limit 保持默认值 0。

| 参数 | LCM | HCM |
| --- | ---: | ---: |
| AP 带宽上限（MHz） | 20 | 80 |
| AP NSS 上限 | 1 | 2 |
| STA 带宽／NSS 上限 | 80 MHz／2 | 80 MHz／2 |
| 有效带宽／NSS | 20 MHz／1 | 80 MHz／2 |
| MCS | 7 | 7 |
| GI（μs） | 0.8 | 0.8 |
| HE-LTF | 2× | 2× |
| 标称 PE（μs） | 0 | 0 |
| MAC 封装开销（B） | 34 | 34 |
| SIFS（μs） | 16 | 16 |
| ACK 速率（Mb/s） | 24 | 24 |
| ACK PPDU（μs） | 28 | 28 |

34 B 开销沿用现有单 MPDU 模型，由 QoS MAC 首部、FCS 和单 MPDU delimiter 合成。STA 的带宽与 NSS 上限可用 `sta.N.wifi.max.bandwidth.mhz` 和 `sta.N.wifi.max.nss` 调整。实际有效值分别取 AP 与 STA 上限的较小值。

## 逐步核算

两条配置的 APEP 都为 `1200 + 34 = 1234 B`，含 SERVICE 的信息位需求为 `8 × 1234 + 16 = 9888 bit`。MCS 7 使用 64-QAM 和 5/6 编码率，数据符号长度为 `12.8 + 0.8 = 13.6 μs`。公式和 LDPC 分段判定复用 [WirelessPhy](../src/main/java/edu/suda/fttr/phy/WirelessPhy.java)。

| 核算量 | LCM | HCM |
| --- | ---: | ---: |
| 数据子载波数 | 234 | 980 |
| 每符号编码位数 `N_CBPS` | 1404 | 11760 |
| 每符号数据位数 `N_DBPS` | 1170 | 9800 |
| PHY Rate，`N_DBPS / 13.6`（Mb/s） | 86.0294118 | 720.5882353 |
| HE-LTF 数量 | 1 | 2 |
| 前导码（μs） | 43.2 | 50.4 |
| 初始完整数据符号数 | 9 | 2 |
| 初始填充因子 `a` | 2 | 1 |
| LDPC 额外分段 | 追加一段，最终 `a=3` | 无追加，最终 `a=1` |
| 最终完整数据符号数 | 9 | 2 |
| 实际 PE（μs） | 0 | 0 |
| DATA PPDU（μs） | 165.6 | 77.6 |
| DATA–SIFS–ACK（μs） | 209.6 | 121.6 |
| 两包无线忙时（μs） | 419.2 | 243.2 |

LCM 的最后一符号在初始 `a=2` 时补齐到 9960 个 LDPC 输入位，可用编码位数为 11952。码字缩短和打孔判定要求追加一个分段，最终 `a=3`，该分段占用原符号的后 FEC 填充空间，因此完整数据符号数仍为 9。LCM 总长为 `43.2 + 9 × 13.6 = 165.6 μs`。HCM 总长为 `50.4 + 2 × 13.6 = 77.6 μs`。

ACK 使用独立 non-HT OFDM 模型。DATA 时长差异进入每次交换占用，也影响后续报文的排队时间。两次运行的 VO AIFS 和退避抽取序列应相同，逐包接入等待可从 CSV 复核。

## 复现与输出

从仓库根目录执行以下命令。

```powershell
mvn -o clean verify
python tools/run_b1_ap_capability.py
```

[运行脚本](../tools/run_b1_ap_capability.py)仅使用 Python 标准库，并复用现有实验 I/O 辅助代码。脚本实际启动 JAR、解析事件和逐 STA PHY 汇总，然后检查模式、有效带宽／NSS、DATA 时长、SIFS／ACK 边界、两次运行的 EDCA 接入等待以及交付报文和字节守恒。

默认在 `results/b1-static-ap/` 输出两份 UTF-8 CSV。[comparison.csv](../results/b1-static-ap/comparison.csv) 包含有效 PHY、每包 DATA 和交换时长、无线忙闲时长、总观察窗口、交付量与有效吞吐量，并附上实际 JAR 和配置文件的 SHA-256。[event_timing.csv](../results/b1-static-ap/event_timing.csv) 每行保留原始事件的时间、节点、动作和报文 ID，可用于复核 DATA 结束、ACK 完成及下一包启动的因果顺序。使用 `--jar` 和 `--output` 可指定其他 JAR 与输出目录。

## 验收记录

本阶段 `mvn -o clean verify` 通过 313 项测试，包含 300 项原有测试和 13 项 B1 测试。`python -B tools/verify_pon_final.py` 的 9 份光侧冻结用例结果匹配。实际执行 `python -B tools/run_b1_ap_capability.py` 后，两个模式均通过 PHY 能力、时长、事件边界和交付守恒检查。

另将光侧最终用例临时接入 HE SU、EDCA 多 AC 和 DATA–SIFS–ACK，并分别为 SFU 1、SFU 2 配置 LCM、HCM，原 12 条报文均交付，其中上行 10 条、下行 2 条。

| 实际运行结果 | LCM | HCM |
| --- | ---: | ---: |
| PHY Rate（Mb/s） | 86.029412 | 720.588235 |
| 首包 DATA 开始／结束（μs） | 34／199.6 | 34／111.6 |
| 首包 ACK 开始／结束（μs） | 215.6／243.6 | 127.6／155.6 |
| 次包 DATA 开始／结束（μs） | 295.6／461.2 | 207.6／285.2 |
| 次包 ACK 开始／结束（μs） | 477.2／505.2 | 301.2／329.2 |
| 两次接入等待（μs） | 34／52 | 34／52 |
| 无线忙时（μs） | 419.2 | 243.2 |
| 无线空闲时（μs） | 86 | 86 |
| 观察窗口（μs） | 505.2 | 329.2 |
| 交付报文数／字节数 | 2／2400 | 2／2400 |
| 平均端到端时延（μs） | 330.40 | 198.40 |
| 各自观察窗口内有效吞吐量（Mb/s） | 38.004751 | 58.323208 |

两次抽取的退避为 0 和 2 个时隙，接入等待分别为 `34 + 0 × 9 = 34 μs`、`34 + 2 × 9 = 52 μs`。HCM 每次 DATA 减少 88 μs，两包总忙时和观察窗口各减少 176 μs。有效吞吐量按 `2400 × 8 / 观察窗口微秒数` 计算，PHY Rate 表示调制编码所能提供的比特速率。

本次实际 JAR 的 SHA-256 为 `1959956de796ae20c945f9f6ef131399c46801d5d95f43e381b82a8e5162fcfb`。CSV 同时记录两份配置的哈希，重新构建后再次执行脚本即可生成与新 JAR 对应的记录。JAR 内构建时间等元数据会影响文件哈希，固定配置的度量和事件时序可逐项复现。

## 本阶段文件

- `ApCapabilityMode.java` 定义 LCM／HCM 静态上限，`WirelessPhy.java` 提供有效 PHY 参数。
- `ConfigLoader.java`、`SimulationConfig.java`、`WifiLink.java`、`Simulator.java` 和 `TraceLogger.java` 接入逐 SFU 模式、STA 能力交集和逐链路日志。
- `StaticApCapabilityTest.java` 验证参数、时长、转发及兼容场景。
- 两份 `configs/b1-static-ap-*.properties`、`tools/run_b1_ap_capability.py`、本报告和 `results/b1-static-ap/` 中两份 CSV 提供可复现的对照材料。
