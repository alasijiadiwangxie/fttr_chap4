# CA-SDPS 开发底座验收

`codex/ca-sdps` 从 `3ce93c2`（D1）签出。底座复用 FTTR、HE SU、EDCA、A-MPDU、干扰与误包、AP 能耗积分及帧交换结束后的安全切换。`power` 的旧算法和实验可用原目录重放。

## 配置入口

| 功能 | 配置与来源 |
| --- | --- |
| 关闭控制或锁定两档 | `wifi.ap.control.policy=off`、`always-lcm` 或 `always-hcm` |
| 按流量切换 | `fixed-threshold` 默认 31 Mb/s。`hysteresis` 低于 21 Mb/s 降档、高于 33 Mb/s 升档，区间内和两个边界保持上次目标。可同时覆盖 `wifi.ap.control.hysteresis.lower.mbps` 和 `wifi.ap.control.hysteresis.upper.mbps`。双阈值取自 `4fa3148`。 |
| GIANT 授权恢复 | `fiber.uplink.dba.mode=giant-complete-recovery`，从 `b20968f` 移植。相关在途预算结算后重新判断有效需求，避免反复预约不足以装载整包的剩余额度。 |
| 只读 C3 预测 | 启用 `wifi.ap.observation.enabled=true`、`wifi.ap.prediction.enabled=true` 和 `wifi.ap.prediction.model=revised`。用 `wifi.ap.observation.period.us=5000` 和 `wifi.ap.prediction.window.us=5000` 设置采样周期和向前预测的时长。外部干扰启用且 `wifi.channel.passive.enabled=true` 时，`WIFI_AP_CCA_HISTORY` 记录主信道和宽带最近 5 ms 的忙时、覆盖、新鲜度与估计区间。忙时统计逻辑源自 `b20968f`。 |

按流量切换采用 5 ms 周期和 0.25 的 EWMA 权重。功率可通过六项配置覆盖，两个配置片段分别给出论文参考功率与 B3 初始研究功率，使用时把所选片段的参数合入场景配置。 [参考功率](../../configs/e5-paper-power.properties) · [B3 功率](../../configs/e5-b3-power.properties)

当前 C3 能比较两档的短窗服务预测。后续 CA-SDPS 预测将使用队列加 5 ms 预估到达作为共同需求，分别算容量、成功交付和未完成业务，再把排空能耗与切换开销算到同一结束时刻。本交付的切换策略仍由表中的基线规则决定。

## 实测核对

182 项 Maven 定向测试全部通过，覆盖光侧授权、帧交换、切换、报文守恒、能耗、CCA、A-MPDU、误包与只读预测。CCA 测试用 1 ms 的业务采样核对独立的 5 ms 忙时窗口，并检查次信道扫描只有完成后才进入 AP 的观测。9 项原 PON 场景的轨迹指纹及 B6 部分确认轨迹均与保留数据一致。

基线短场景使用 90 ms 的 10 → 50 → 10 Mb/s 阶梯，固定种子 101 和 1500 B 报文。各策略与 `power` 的同配置重放逐包匹配，切换时间与能耗也一致。不同策略之间的时序可以不同。

| 策略 | 唯一交付 | 切换次数 | AP 能耗（J） |
| --- | ---: | ---: | ---: |
| Always-LCM | 175 | 0 | 0.722576 |
| Always-HCM | 175 | 0 | 0.729151 |
| 31 Mb/s 单阈值 | 175 | 2 | 0.723036 |
| 21/33 Mb/s 双阈值 | 175 | 2 | 0.724475 |

双向短场景使用 2 台 SFU 和 4 台 STA。真实 HE SU、次信道干扰、A-MPDU 与 GIANT 恢复同时启用。8 个下行包和 10 个上行包各成功交付一次，49 笔光侧 DATA 预算的时隙和结算完成对账。两个下行聚合成员因次信道误包各重传一次。两台 AP 的 10 ms 活动时长分别守恒，合计能耗为 0.156275 J。此用例用人工 SINR–PER 曲线检查仿真机制。

## 保留数据与复现

- 保留 PON 最终验收、EDCA 综合结果、A-MPDU 部分确认轨迹和 PON 不可行保障配置。 [PON 验收](../pon/PON_DBA_Final_Review.md) · [EDCA 结果](../../results/edca-acceptance) · [聚合轨迹](../../results/b6-ampdu/ampdu-event-trace.csv)
- 从 `682324a` 保留 E5 的静态交叉原始种子结果、汇总、原图、干扰强度和阈值标定。参考功率下，无干扰交点约 20.91 Mb/s，强次信道干扰场景约 17.63 Mb/s。这些数据来自 E5 静态模式，适合继续核对交叉机理。E5 的历史重放工具在 `power`。 [静态交叉结果](../../results/e5-interference/01-crossover) · [原图](../../results/e5-interference/figures/01-power-crossover.svg) · [标定](../../results/e5-interference/threshold-calibration.csv)
- 新目录移除 15 份阶段文档和 406 份历史结果文件。D1 及之前的阶段材料可在 `3ce93c2` 查阅，后续材料留在 `power`。

在本仓库根目录运行，生成的配置和逐事件日志位于 `target/ca-sdps-base`。

```powershell
mvn -o '-Dtest=ApModeControlSimulationTest,ApModeSwitchSimulationTest,ApActivitySimulationTest,ApEnergySimulationTest,ApTrafficObservationSimulationTest,AmpduSimulationTest,ExternalCcaSimulationTest,SubchannelPerSimulationTest,CcaHistoryTest,ChannelManagementTest,ApAnalysisSimulationTest,ApServicePredictionTest,GiantRecoveryTest,GiantPipelinedLedgerTest,GiantCompletionTest' package
python -B tools/verify_ca_sdps_base.py
python -B tools/verify_pon_final.py
python -B tools/verify_b6_ampdu_trace.py --output target/b6-ampdu-verification
```

与旧分支进行逐包对照时，先在 `power` 目录构建 JAR，再从新目录运行下面的命令。

```powershell
python -B tools/verify_ca_sdps_base.py --reference-jar ../fttr_chap4/target/fttr-chap4-0.1.0-SNAPSHOT.jar
```
