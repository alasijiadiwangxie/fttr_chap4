# PON 与 DBA 最终验收和冻结记录

## 结论

当前光侧逻辑模型验收通过，作为 FTTR 光无线联合仿真的冻结基线。范围包括固定及共享的 PON 上下行、分帧 FCFS、REPORT、BWmap、需求账本、T-CONT Type 1–5、影子 RR、真实 RR 和两档 GIANT。验收针对仓库实现的逻辑时序、整包队列和资源账目。 [光侧建模报告](PON_DBA_Model.md)

## 完整回归与短场景复验

| 步骤 | 命令 | 实际结果 |
| --- | --- | --- |
| 清理前完整构建 | `mvn -o clean verify` | 301 项通过，0 失败、0 错误、0 跳过 |
| 清理前固定轨迹 | `python -B tools/verify_pon_final.py --record` | 8 个成功场景和 1 个预期拒绝场景的 stdout/stderr 指纹 |
| 清理后完整重编译 | `mvn -o clean verify` | 47 个生产源文件、42 个测试源文件编译，300 项通过，0 失败、0 错误、0 跳过 |
| 清理后短场景复验 | `python -B tools/verify_pon_final.py` | 九项结果逐项匹配，规范化换行后的事件与诊断输出 SHA-256 一致 |

减少的是一项只检查阶段 CSV 文件生成的 JUnit 测试。原有 20 个授权合法性表驱动场景仍由 JUnit 执行。整包装载的零预算、重复预演与第 33 包容量边界纳入既有 JUnit 断言。

| 核对范围 | 回归与实测证据 |
| --- | --- |
| 固定 PON、共享下行与共享上行 | 原有单包与多设备回归均通过。共享双向短场景交付下行 1 包、上行 2 包。既有测试检查光资源独立、时间精度与 FCFS 顺序。 |
| 分帧、REPORT、BWmap | FCFS 短场景交付下行 2 包、上行 10 包。既有回归检查 125 μs 帧边界、时隙内发送、REPORT 形成和到达、迟到拒绝及控制时隙容量。 |
| 需求账本及计划校验 | 既有回归覆盖旧报告过滤、在途授权扣减、结算、超额计划与对象归属。影子 RR 短场景只输出影子建议，沿用 FCFS 交付。 |
| RR、GIANT 与 Type 1–5 | 真实 `rr`、`rr-multi`、`giant`、`giant-complete` 四种短场景各交付上行 10 包和下行 2 包，真实 DATA 预算均与结算一一对应。完整 GIANT 不可行配置仍以退出码 2 拒绝，诊断字节与清理前一致。 |
| 多 SFU、多 STA 与无线交接 | 保留无线和跨模块 JUnit。短场景包含 2 SFU、4 STA。既有实验在 4/8 SFU、每 SFU 2 STA 下完成 60 组实际运行，并逐事件校验输入、上行时隙、报文守恒及结算。 |

[短场景指纹和计数](../../results/pon-final/smoke-summary.csv) · [可直接运行的 Type 1–5 配置](../../configs/pon-final-dba.properties) · [多 SFU 已有实验](multi-sfu-overload-test.md)

## 已知边界和后续修改位置

- **先查看能否装入整包。** 审核时复核 39062 B 队首报文。2.5 Gb/s 与 125 μs 下，它可装入没有控制占用的整帧。205 ns 控制时隙之后只能授权 38998 B，整包装载为零，结算记录 38998 B 未用，最终仍有 1 包积压。需要分片或帧尾动态回收时，涉及 SFU 装载、共享窗口和账本结算。 [装载器](../../src/main/java/edu/suda/fttr/link/PonGrantPacketFiller.java) · [PON 上行执行](../../src/main/java/edu/suda/fttr/link/PonUplink.java)
- **授权截止与报告到达是两个时钟。** 审核时复核首份队列报告在 260230 ns 才到达、授权窗口在 250000 ns 已关闭的场景，最终未交付量为 1 包。实验复核应分别登记发流截止、建立授权截止、最后交付与报告事件。长期自动收尾需要在轮询和授权截止策略中独立设计。 [REPORT 与授权计时](../../src/main/java/edu/suda/fttr/link/PonUplink.java)
- **超容量 SLA 在启动时拒绝。** 保留的实测拒绝样例需要 134605 ns 的同帧固定、保障和控制资源，超过 125000 ns。该错误在最终短场景中再次验证。准入通过的场景仍可能因报告尚未知晓、固定预约空置或整包限制而产生排队。 [实际拒绝配置](../../results/pon-giant-completion/configs/infeasible-sla.properties) · [准入校验](../../src/main/java/edu/suda/fttr/dba/GiantSlaAdmission.java)
- **长窗账本成本。** 已结算的授权历史随运行保留，计算需求时遍历对象的历史预算，执行层也保留授权记录。稀疏 FCFS 的日志可能补记空帧。现有多 SFU 数据涵盖 100 ms 观测窗口。更长窗的性能扩展可从安全截断历史、保留最后可见 REPORT 的扣减基准入手。 [需求账本](../../src/main/java/edu/suda/fttr/entity/PonDemandLedger.java#L42) · [帧记录](../../src/main/java/edu/suda/fttr/link/PonUplink.java#L613)

上述案例符合当前配置和建模边界。审查没有发现需要为本次冻结改动生产语义的问题。生产 Java 与 Maven 配置相对验收起点逐文件一致。

## 清理与复现

| 清理对象 | 实际范围 |
| --- | --- |
| 历史阶段材料 | 移除第 03–17 步的 15 份阶段验收文档、15 个阶段结果目录中的 102 个受版本控制的文件，以及 16 个阶段工具。完整历史可从清理前提交 `cb1be83` 按原路径读取。 |
| Java 临时入口 | 删除 `PonGrantPacketFillCases.java`，移除 `DbaPlanValidationCases`、`TcontBandwidthTest` 的独立 CSV 导出入口和对应死代码。构建用 JUnit、无线测试和跨模块检查仍在。 |
| 可重建和重复日志 | 清除 6 份与 T2 尾时延实验逐内容哈希相同的原始日志，以及 4 份规模试跑日志和 4 份试跑回执。保留试跑统计与实际配置。T2 重放可从尾时延实验目录找到同一份日志。 |
| 最终工具和证据 | 最终实验仍用原来的事件解析逻辑，共用函数迁入 `pon_experiment_io.py`、`pon_execution_trace.py`。保留完整 GIANT、专项 QoS、T2 两组及多 SFU 的统计、配置、图表与索引。 |

清单记录 148 个删除文件、约 38.2 MB。新汇总图从已有 CSV 复现，本轮没有重复运行旧性能实验。 [逐文件清理清单](../../results/pon-final/cleanup-manifest.csv) · [性能总览图](../../results/pon-final/pon-performance-summary.svg) · [README](../../README.md)

在仓库根目录运行下列命令。Maven 依赖未缓存时可去掉 `-o`。短场景与图表工具使用 Python 3.10 或更高版本。

```powershell
mvn -o clean verify
python -B tools/verify_pon_final.py
java -jar target/fttr-chap4-0.1.0-SNAPSHOT.jar configs/pon-final-dba.properties
python -B tools/plot_pon_final_summary.py
```
