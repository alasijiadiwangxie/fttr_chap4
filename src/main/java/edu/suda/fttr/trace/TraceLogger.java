package edu.suda.fttr.trace;

import edu.suda.fttr.channel.ApChannelObservations.LocalReception;
import edu.suda.fttr.channel.CcaHistory;
import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.channel.ChannelTruth;
import edu.suda.fttr.config.ChannelObservationInput;
import edu.suda.fttr.energy.ApModePredictor;
import edu.suda.fttr.energy.ApQosLedger;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApActivity;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeController;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.entity.PonDbaShadowPlanner;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.link.PonReportPoll;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.GiantDbaPolicy;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.phy.DownlinkDecoder.ChannelSample;
import edu.suda.fttr.phy.SinrPerModel;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.simulator.Event;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** 每行保留时刻、节点、动作和报文身份，便于逐事件核对。 */
public final class TraceLogger {
    private final PrintStream output;
    private final boolean nanosecondClock;

    public TraceLogger(PrintStream output) {
        this(output, false);
    }

    private TraceLogger(PrintStream output, boolean nanosecondClock) {
        this.output = output;
        this.nanosecondClock = nanosecondClock;
    }

    /** 共享输出流，但单次运行的时钟单位独立于其他运行。 */
    public TraceLogger withNanosecondClock(boolean nanosecondClock) {
        return this.nanosecondClock == nanosecondClock ? this : new TraceLogger(output, nanosecondClock);
    }

    private String formatMicroseconds(long ticks) {
        if (!nanosecondClock) {
            return Long.toString(ticks);
        }
        // 日志仍用 _us。整数微秒保持旧格式，小数部分则不经过 double。
        return ticks % 1_000 == 0 ? Long.toString(ticks / 1_000)
                : BigDecimal.valueOf(ticks, 3).stripTrailingZeros().toPlainString();
    }

    public void recordEvent(Event event) {
        Packet packet = event.packet();
        switch (event.type()) {
            case PACKET_GENERATED:
                write(event, packet.isUplink() ? "STA" : "MFU", "ARRIVAL");
                break;
            case FIBER_TX_START:
                write(event, packet.isUplink() ? "SFU" : "MFU", "FIBER_TX_START");
                break;
            case FIBER_TX_END:
                write(event, packet.isUplink() ? "SFU" : "MFU", "FIBER_TX_END");
                break;
            case PON_UPLINK_FRAME:
                // 光侧调度器单独记录每个授权及对应的帧统计。
                break;
            case PON_BWMAP_RX:
            case PON_POLL_BWMAP_RX:
            case PON_POLL_START:
            case PON_POLL_END:
            case PON_POLL_REPORT_RX:
            case PON_DBA_BWMAP_RX:
            case PON_DBA_SLOT_START:
            case PON_DBA_SLOT_END:
            case PON_DBA_SETTLEMENT_RX:
                // 目标 SFU 核对预约及接收时刻后，再记录 BWmap 接收。
                break;
            case PACKET_ARRIVAL_AT_SFU:
                write(event, "SFU", "ARRIVAL");
                break;
            case WIFI_TX_START:
            case WIFI_TXOP_DATA_START:
                write(event, packet.isUplink() ? "STA" : "SFU", "WIFI_TX_START");
                break;
            case WIFI_ACK_START:
                write(event, packet.isUplink() ? "SFU" : "STA", "WIFI_ACK_START");
                break;
            case WIFI_ACK_END:
                write(event, packet.isUplink() ? "STA" : "SFU", "WIFI_ACK_END");
                break;
            case WIFI_AMPDU_DATA_END:
            case WIFI_AMPDU_BA_START:
            case WIFI_AMPDU_BA_END:
                // 由 SFU 输出聚合边界、MPDU 列表及 Block ACK 位图。
                break;
            case PACKET_ARRIVAL_AT_STA:
            case PACKET_ARRIVAL_AT_MFU:
                // 接收端确认目的 ID 后，通过 recordDelivery 记录实际接收者。
                break;
            case PON_UPLINK_REPORT_RX:
                // MFU 校验收到的快照并判定是否更新状态后，再记录接收结果。
                break;
            case WIFI_ARRIVAL_AT_SFU:
                // SFU 收齐上行 DATA。链路随后单独记录 DATA 到达和无线资源释放。
                break;
            case WIFI_SELECT_NEXT:
                // SFU 实际选队时另记队列快照，此处的调度事件没有报文。
                break;
            case WIFI_ACCESS_TIMER:
            case WIFI_ACCESS_RESOLVE:
                // 内部接入事件。记录实际选队结果和虚拟碰撞。
                break;
            case WIFI_COLLISION_DATA_END:
            case WIFI_ACK_TIMEOUT:
            case WIFI_AP_SWITCH_REQUEST:
            case WIFI_AP_SWITCH_END:
            case WIFI_AP_MODE_DECISION:
            case WIFI_EXTERNAL_CCA_EDGE:
            case WIFI_CHANNEL_MANAGEMENT_START:
            case WIFI_CHANNEL_MANAGEMENT_END:
                // SFU 处理结果后记录失败、重传或信道释放日志。
                break;
            default:
                throw new IllegalStateException("Unhandled trace event: " + event.type());
        }
    }

    private void write(Event event, String node, String action) {
        write(event.packet(), event.timeTicks(), node, action, event.sfuId());
    }

    public void recordPollEvent(PonReportPoll poll, String action, long nowNs) {
        if (poll.allocationSpecific()) {
            output.printf(Locale.ROOT, "node=PON event=%s time_ns=%d poll_id=%d allocation_id=%d sfu_id=%d "
                            + "grant_frame_id=%d target_frame_id=%d bwmap_tx_ns=%d tx_start_ns=%d tx_end_ns=%d%n",
                    action, nowNs, poll.id(), poll.allocationId(), poll.sfuId(), poll.grantFrameId(),
                    poll.targetFrameId(), poll.grantTxNs(), poll.startNs(), poll.endNs());
            return;
        }
        String receiver = action.equals("PON_POLL_BWMAP_TX") ? "MFU" : "SFU";
        String rx = action.equals("PON_POLL_BWMAP_RX") ? " bwmap_rx_ns=" + nowNs : "";
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s poll_id=%d grant_id=R%d grant_type=REPORT_ONLY "
                        + "sfu_id=%d grant_frame_id=%d target_frame_id=%d bwmap_tx_ns=%d%s tx_start_ns=%d tx_end_ns=%d%n",
                formatMicroseconds(nowNs), receiver, action, poll.id(), poll.id(), poll.sfuId(), poll.grantFrameId(),
                poll.targetFrameId(), poll.grantTxNs(), rx, poll.startNs(), poll.endNs());
    }

    public void recordPollSnapshot(PonReportPoll poll) {
        PonUplinkReport r = poll.report();
        if (poll.allocationSpecific()) {
            recordPonUplinkReport(r);
            return;
        }
        output.printf(Locale.ROOT, "node=SFU event=PON_POLL_REPORT poll_id=%d sfu_id=%d frame_id=%d snapshot_time_ns=%d "
                        + "pending_packets=%d pending_bytes=%d eligible_packets=%d eligible_bytes=%d%n",
                poll.id(), r.sfuId(), r.frameId(), r.snapshotTimeNs(), r.pendingPackets(), r.pendingBytes(),
                r.eligiblePackets(), r.eligibleBytes());
    }

    public void recordPollReception(PonReportPoll poll, long nowNs, boolean applied) {
        PonUplinkReport r = poll.report();
        if (poll.allocationSpecific()) {
            // MFU 接收路径另记一次 TCONT_REPORT_RX，避免同一报告出现两条接收记录。
            return;
        }
        output.printf(Locale.ROOT, "t_us=%s node=MFU event=PON_POLL_REPORT_RX poll_id=%d grant_id=R%d "
                        + "grant_type=REPORT_ONLY sfu_id=%d grant_frame_id=%d target_frame_id=%d bwmap_tx_ns=%d "
                        + "tx_start_ns=%d tx_end_ns=%d snapshot_time_ns=%d pending_packets=%d pending_bytes=%d "
                        + "eligible_bytes=%d receive_time_ns=%d applied=%b%n",
                formatMicroseconds(nowNs), poll.id(), poll.id(), poll.sfuId(), poll.grantFrameId(), poll.targetFrameId(),
                poll.grantTxNs(), poll.startNs(), poll.endNs(), r.snapshotTimeNs(), r.pendingPackets(), r.pendingBytes(),
                r.eligibleBytes(), nowNs, applied);
    }

    public void recordPollFrame(long id, long startNs, long endNs, long dataNs, long reportNs) {
        long busyNs = dataNs + reportNs;
        output.printf(Locale.ROOT, "summary=PON_POLL_FRAME frame_id=%d frame_start_ns=%d frame_end_ns=%d "
                        + "data_busy_ns=%d report_busy_ns=%d busy_ns=%d idle_ns=%d utilization=%.9f%n",
                id, startNs, endNs, dataNs, reportNs, busyNs, endNs - startNs - busyNs,
                utilization(busyNs, endNs - startNs));
    }

    public void recordPollingSummary(long polls, long dataNs, long reportNs) {
        output.printf(Locale.ROOT, "summary=PON_POLLING polls=%d data_busy_ns=%d report_busy_ns=%d total_busy_ns=%d%n",
                polls, dataNs, reportNs, dataNs + reportNs);
    }

    public void recordPonDownlinkEnqueue(Packet packet, int sfuId, long timeTicks) {
        write(packet, timeTicks, "MFU", "PON_DOWNLINK_ENQUEUE", sfuId);
    }

    public void recordPonUplinkEnqueue(Packet packet, int sfuId, long timeTicks) {
        write(packet, timeTicks, "SFU", "PON_UPLINK_ENQUEUE", sfuId);
    }

    public void recordTcontEnqueue(Packet packet, long allocationId, int sfuId, long timeNs) {
        output.printf(Locale.ROOT, "node=SFU event=TCONT_ENQUEUE time_ns=%d allocation_id=%d sfu_id=%d "
                        + "packet_id=%d packet_bytes=%d ac=%s%n",
                timeNs, allocationId, sfuId, packet.id(), packet.bytes(), packet.type());
    }

    public void recordPonUplinkReport(PonUplinkReport report) {
        if (report.allocationSpecific()) {
            output.printf(Locale.ROOT, "node=SFU event=TCONT_REPORT frame_id=%d allocation_id=%d sfu_id=%d "
                            + "snapshot_time_ns=%d pending_packets=%d pending_bytes=%d eligible_packets=%d eligible_bytes=%d%n",
                    report.frameId(), report.allocationId(), report.sfuId(), report.snapshotTimeNs(), report.pendingPackets(),
                    report.pendingBytes(), report.eligiblePackets(), report.eligibleBytes());
            return;
        }
        output.printf(Locale.ROOT, "node=SFU event=PON_UPLINK_REPORT frame_id=%d sfu_id=%d snapshot_time_ns=%d "
                        + "pending_packets=%d pending_bytes=%d eligible_packets=%d eligible_bytes=%d%n",
                report.frameId(), report.sfuId(), report.snapshotTimeNs(), report.pendingPackets(),
                report.pendingBytes(), report.eligiblePackets(), report.eligibleBytes());
    }

    public void recordPonUplinkReportReception(PonUplinkReport report, int packetId, long receiveNs, boolean applied) {
        output.printf(Locale.ROOT, "t_us=%s node=MFU event=PON_UPLINK_REPORT_RX frame_id=%d sfu_id=%d packet_id=%d "
                        + "snapshot_time_ns=%d receive_time_ns=%d pending_bytes=%d eligible_bytes=%d applied=%b%n",
                formatMicroseconds(receiveNs), report.frameId(), report.sfuId(), packetId, report.snapshotTimeNs(),
                receiveNs, report.pendingBytes(), report.eligibleBytes(), applied);
    }

    public void recordMfuReportState(long frameId, int sfuId, long boundaryNs, Mfu.ReceivedUplinkReport latest) {
        String prefix = String.format(Locale.ROOT, "node=MFU event=PON_UPLINK_REPORT_STATE frame_id=%d "
                + "sfu_id=%d boundary_time_ns=%d", frameId, sfuId, boundaryNs);
        if (latest == null) {
            output.println(prefix + " status=UNKNOWN");
            return;
        }
        PonUplinkReport report = latest.report();
        output.printf(Locale.ROOT, "%s status=KNOWN latest_report_frame_id=%d latest_snapshot_time_ns=%d "
                        + "latest_receive_time_ns=%d known_pending_bytes=%d known_eligible_bytes=%d report_age_ns=%d%n",
                prefix, report.frameId(), report.snapshotTimeNs(), latest.receiveTimeNs(),
                report.pendingBytes(), report.eligibleBytes(), boundaryNs - report.snapshotTimeNs());
    }

    public void recordPonUplinkReportSummary(long generated, long received, long withoutGrant) {
        output.printf(Locale.ROOT, "summary=PON_UPLINK_REPORTS generated=%d received=%d without_grant=%d%n",
                generated, received, withoutGrant);
    }

    public void recordPonUplinkGrant(Packet packet, int sfuId, long frameId, long enqueueNs, long grantNs,
                                     long startNs, long endNs) {
        output.printf(Locale.ROOT, "t_us=%s node=MFU event=PON_UPLINK_GRANT frame_id=%d packet_id=%d sfu_id=%d "
                        + "enqueue_time_ns=%d grant_time_ns=%d tx_start_ns=%d tx_end_ns=%d%n",
                formatMicroseconds(grantNs), frameId, packet.id(), sfuId, enqueueNs, grantNs, startNs, endNs);
    }

    public void recordDemandLedgerGrant(int sfuId, int grantId, long createNs, int grantBytes) {
        output.printf(Locale.ROOT, "node=MFU event=PON_DEMAND_LEDGER_GRANT sfu_id=%d grant_id=%d "
                        + "grant_create_ns=%d grant_bytes=%d%n", sfuId, grantId, createNs, grantBytes);
    }

    public void recordDemandLedgerState(int sfuId, long timeNs, String trigger, PonDemandLedger.Estimate estimate) {
        String prefix = String.format(Locale.ROOT, "node=MFU event=PON_DEMAND_LEDGER time_ns=%d sfu_id=%d trigger=%s",
                timeNs, sfuId, trigger);
        if (!estimate.known()) {
            output.println(prefix + " state=UNKNOWN");
            return;
        }
        Mfu.ReceivedUplinkReport received = estimate.report();
        PonUplinkReport report = received.report();
        output.printf(Locale.ROOT, "%s state=KNOWN latest_report_frame_id=%d report_snapshot_ns=%d report_receive_ns=%d "
                        + "reported_pending_bytes=%d granted_bytes_since_snapshot=%d effective_known_bytes=%d "
                        + "report_age_ns=%d%n",
                prefix, report.frameId(), report.snapshotTimeNs(), received.receiveTimeNs(), report.pendingBytes(),
                estimate.grantedBytesSinceSnapshot(), estimate.effectiveKnownBytes(), estimate.reportAgeNs());
    }

    public void recordTcontDemandState(long allocationId, int sfuId, long timeNs, String trigger,
                                      PonDemandLedger.Estimate estimate) {
        String prefix = String.format(Locale.ROOT,
                "node=MFU event=TCONT_DEMAND time_ns=%d allocation_id=%d sfu_id=%d trigger=%s",
                timeNs, allocationId, sfuId, trigger);
        if (!estimate.known()) {
            output.println(prefix + " state=UNKNOWN");
            return;
        }
        Mfu.ReceivedUplinkReport received = estimate.report();
        PonUplinkReport report = received.report();
        output.printf(Locale.ROOT, "%s state=KNOWN latest_report_frame_id=%d report_snapshot_ns=%d report_receive_ns=%d "
                        + "reported_pending_bytes=%d granted_bytes_since_snapshot=%d effective_known_bytes=%d report_age_ns=%d%n",
                prefix, report.frameId(), report.snapshotTimeNs(), received.receiveTimeNs(), report.pendingBytes(),
                estimate.grantedBytesSinceSnapshot(), estimate.effectiveKnownBytes(), estimate.reportAgeNs());
    }

    public void recordTcontReportReceived(PonUplinkReport report, long nowNs, boolean applied, String kind) {
        output.printf(Locale.ROOT, "node=MFU event=TCONT_REPORT_RX time_ns=%d frame_id=%d allocation_id=%d sfu_id=%d "
                        + "snapshot_time_ns=%d pending_bytes=%d eligible_bytes=%d applied=%b report_kind=%s%n",
                nowNs, report.frameId(), report.allocationId(), report.sfuId(), report.snapshotTimeNs(),
                report.pendingBytes(), report.eligibleBytes(), applied, kind);
    }

    public void recordDbaShadow(PonDbaShadowPlanner.Decision decision) {
        output.printf(Locale.ROOT, "node=MFU event=PON_DBA_SHADOW decision_time_ns=%d target_frame_id=%d "
                        + "selected_sfu_id=%d report_frame_id=%d report_age_ns=%d effective_known_bytes=%d "
                        + "control_reserved_ns=%d available_data_ns=%d data_capacity_bytes=%s planned_grant_bytes=%d "
                        + "planned_grant_ns=%d decision_reason=%s unknown_sfus=%d known_zero_sfus=%d%n",
                decision.decisionTimeNs(), decision.targetFrameId(), decision.selectedSfuId(),
                decision.reportFrameId() == null ? -1 : decision.reportFrameId(),
                decision.reportAgeNs() == null ? -1 : decision.reportAgeNs(), decision.effectiveKnownBytes(),
                decision.controlReservedNs(), decision.availableDataNs(), decision.dataCapacityBytes(),
                decision.plannedGrantBytes(), decision.plannedGrantNs(), decision.decisionReason(),
                decision.unknownSfus(), decision.knownZeroSfus());
        // 独立观测行保留第 9 步原始日志逐字兼容；预览时间不是实际 BWmap 或 DATA 时隙。
        output.printf(Locale.ROOT, "node=MFU event=PON_DBA_POLICY decision_time_ns=%d target_frame_id=%d "
                        + "policy_name=shadow-rr allocation_id=%d sfu_id=%d known_demand_bytes=%d planned_bytes=%d "
                        + "planned_duration_ns=%d decision_reason=%s preview_start_ns=%d preview_end_ns=%d%n",
                decision.decisionTimeNs(), decision.targetFrameId(), decision.allocationId(), decision.selectedSfuId(),
                decision.effectiveKnownBytes(), decision.plannedGrantBytes(), decision.plannedGrantNs(),
                decision.decisionReason(), decision.preview().isEmpty() ? -1 : decision.preview().get(0).startNs(),
                decision.preview().isEmpty() ? -1 : decision.preview().get(0).endNs());
    }

    public void recordDbaDecision(DbaPlan plan, long controlNs) {
        DbaPlan.Grant choice = plan.grants().isEmpty() ? null : plan.grants().get(0);
        output.printf(Locale.ROOT, "node=MFU event=PON_DBA_DECISION time_ns=%d target_frame_id=%d "
                        + "allocation_id=%d sfu_id=%d planned_bytes=%d planned_duration_ns=%d "
                        + "control_reserved_ns=%d decision_reason=%s%n",
                plan.decisionTimeNs(), plan.targetFrameId(), choice == null ? 0 : choice.allocationId(),
                choice == null ? 0 : choice.sfuId(), choice == null ? 0 : choice.plannedBytes(),
                choice == null ? 0 : choice.plannedDurationNs(), controlNs, plan.decisionReason());
    }

    /** 多授权模式同时记录整帧计划和逐笔预算，不能用原单授权标量日志隐去其他SFU。 */
    public void recordDbaMultiPlan(DbaPlan plan, long controlNs) {
        java.math.BigInteger bytes = java.math.BigInteger.ZERO;
        long durationNs = 0;
        for (DbaPlan.Grant grant : plan.grants()) {
            bytes = bytes.add(java.math.BigInteger.valueOf(grant.plannedBytes()));
            durationNs = Math.addExact(durationNs, grant.plannedDurationNs());
        }
        output.printf(Locale.ROOT, "node=MFU event=PON_DBA_MULTI_PLAN time_ns=%d target_frame_id=%d "
                        + "grant_count=%d planned_bytes=%s planned_duration_ns=%d control_reserved_ns=%d "
                        + "decision_reason=%s%n", plan.decisionTimeNs(), plan.targetFrameId(), plan.grants().size(),
                bytes, durationNs, controlNs, plan.decisionReason());
        int index = 0;
        for (DbaPlan.Grant grant : plan.grants()) {
            output.printf(Locale.ROOT, "node=MFU event=PON_DBA_MULTI_DECISION time_ns=%d target_frame_id=%d "
                            + "allocation_id=%d sfu_id=%d planned_bytes=%d planned_duration_ns=%d "
                            + "grant_index=%d slot_offset_ns=%d decision_reason=%s%n",
                    plan.decisionTimeNs(), plan.targetFrameId(), grant.allocationId(), grant.sfuId(),
                    grant.plannedBytes(), grant.plannedDurationNs(), ++index, grant.startOffsetNs(), plan.decisionReason());
        }
    }

    /** 每个对象独立显示已知需求和授权阻塞；等待结算不改变KNOWN/UNKNOWN状态。 */
    public void recordDbaObjectState(DbaContext input, DbaContext.Allocation allocation) {
        output.printf(Locale.ROOT, "node=MFU event=PON_DBA_OBJECT_STATE time_ns=%d target_frame_id=%d "
                        + "allocation_id=%d sfu_id=%d state=%s known_demand_bytes=%d report_frame_id=%d "
                        + "report_age_ns=%d budget_outstanding=%s%n",
                input.decisionTimeNs(), input.targetFrameId(), allocation.allocationId(), allocation.sfuId(),
                allocation.known() ? "KNOWN" : "UNKNOWN", allocation.known() ? allocation.knownDemandBytes() : -1,
                allocation.reportFrameId() == null ? -1 : allocation.reportFrameId(),
                allocation.reportAgeNs() == null ? -1 : allocation.reportAgeNs(), allocation.budgetOutstanding());
    }

    /** 即使没有预算，也保留本帧每个 Alloc-ID 的到期判断和各阶段的原因。 */
    public void recordGiantDecision(DbaContext input, GiantDbaPolicy.Decision decision) {
        DbaContext.Allocation allocation = decision.allocation();
        TcontProfile profile = allocation.profile();
        output.printf(Locale.ROOT, "node=MFU event=PON_GIANT_DECISION time_ns=%d target_frame_id=%d "
                        + "allocation_id=%d sfu_id=%d type=%d si_max_frames=%d si_min_frames=%d "
                        + "si_max_before=%d si_max_after=%d si_min_before=%d si_min_after=%d "
                        + "ab_min_bytes=%d ab_sur_bytes=%d assured_bytes=%d surplus_bytes=%d "
                        + "budget_bytes=%d duration_ns=%d assured_reason=%s surplus_reason=%s "
                        + "state=%s known_demand_bytes=%d budget_outstanding=%s report_frame_id=%d "
                        + "report_age_ns=%d control_reserved_ns=%d%s%n",
                input.decisionTimeNs(), input.targetFrameId(), allocation.allocationId(), allocation.sfuId(),
                profile.type(), profile.siMaxFrames(), profile.siMinFrames(), decision.siMaxBefore(),
                decision.siMaxAfter(), decision.siMinBefore(), decision.siMinAfter(), profile.abMinBytes(),
                profile.abSurBytes(), decision.assuredBytes(), decision.surplusBytes(), decision.budgetBytes(),
                decision.durationNs(), decision.assuredReason(), decision.surplusReason(),
                allocation.known() ? "KNOWN" : "UNKNOWN", allocation.known() ? allocation.knownDemandBytes() : -1,
                allocation.budgetOutstanding(), allocation.reportFrameId() == null ? -1 : allocation.reportFrameId(),
                allocation.reportAgeNs() == null ? -1 : allocation.reportAgeNs(), input.controlReservedNs(),
                decision.complete() ? completeGiantDetails(profile, decision) + recoveryDetails(decision) : "");
    }

    private static String recoveryDetails(GiantDbaPolicy.Decision decision) {
        DbaContext.Allocation allocation = decision.allocation();
        if (allocation.failedBudgetBytes() == 0) {
            return "";
        }
        return " recovery_failed_bytes=" + allocation.failedBudgetBytes() + " recovery_ceiling_bytes="
                + decision.recoveryCeiling() + " recovery_waiting=" + allocation.recoveryWaiting()
                + " recovery_limit=" + (decision.recoveryCeiling() <= allocation.failedBudgetBytes());
    }

    private static String completeGiantDetails(TcontProfile profile, GiantDbaPolicy.Decision decision) {
        return String.format(Locale.ROOT,
                " si_fixed_frames=%d si_fixed_before=%d si_fixed_after=%d ab_fixed_bytes=%d "
                        + "si_be_frames=%d si_be_before=%d si_be_after=%d ab_be_bytes=%d "
                        + "fixed_bytes=%d fixed_reason=%s non_assured_bytes=%d non_assured_reason=%s "
                        + "best_effort_bytes=%d best_effort_reason=%s assured_wanted_bytes=%d "
                        + "fixed_order=%d assured_order=%d non_assured_order=%d best_effort_order=%d",
                profile.siFixedFrames(), decision.siFixedBefore(), decision.siFixedAfter(), profile.abFixedBytes(),
                profile.siBeFrames(), decision.siBeBefore(), decision.siBeAfter(), profile.abBeBytes(),
                decision.fixedBytes(), decision.fixedReason(), decision.nonAssuredBytes(), decision.nonAssuredReason(),
                decision.bestEffortBytes(), decision.bestEffortReason(), decision.assuredWantedBytes(),
                decision.fixedOrder(), decision.assuredOrder(), decision.nonAssuredOrder(), decision.bestEffortOrder());
    }

    /** 真实预算日志显式保留 D 前缀，不与原逐包 grant_id 或轮询 R 前缀混用。 */
    public void recordDbaExecution(String action, PonDbaGrant grant, long nowNs, String details) {
        output.printf(Locale.ROOT, "node=%s event=%s time_ns=%d grant_id=D%d allocation_id=%d sfu_id=%d "
                        + "grant_create_ns=%d target_frame_id=%d tx_start_ns=%d tx_end_ns=%d "
                        + "budget_bytes=%d selected_bytes=%d actual_bytes=%d unused_bytes=%d execution_state=%s%s%s%n",
                action.endsWith("_TX") || action.endsWith("_RX") && action.contains("SETTLEMENT")
                        || action.contains("LEDGER") ? "MFU" : "SFU",
                action, nowNs, grant.id(), grant.allocationId(), grant.sfuId(), grant.createNs(),
                grant.targetFrameId(), grant.startNs(), grant.endNs(), grant.budgetBytes(),
                grant.selectedBytes(), grant.actualBytes(), grant.unusedBytes(),
                grant.finished() ? "COMPLETED" : grant.started() ? "LOADED" : grant.received() ? "RECEIVED" : "CREATED",
                grant.fixedBytes() == 0 ? "" : " fixed_bytes=" + grant.fixedBytes(),
                details.isEmpty() ? "" : " " + details);
    }

    public void recordDbaSummary(long budgets, long grantedBytes, long actualBytes, long unusedBytes,
                                  long reservedNs, long actualNs, long pendingPackets) {
        output.printf(Locale.ROOT, "summary=PON_DBA_EXECUTION grants=%d budget_bytes=%d actual_bytes=%d "
                        + "unused_bytes=%d reserved_ns=%d actual_data_ns=%d unused_ns=%d pending_packets=%d%n",
                budgets, grantedBytes, actualBytes, unusedBytes, reservedNs, actualNs, reservedNs - actualNs,
                pendingPackets);
    }

    public void recordPonBwmap(Packet packet, int sfuId, long grantFrameId, long targetFrameId, long grantTxNs,
                               long startNs, long endNs, long nowNs, boolean received) {
        String reception = received ? " grant_rx_ns=" + nowNs : "";
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s grant_frame_id=%d target_frame_id=%d packet_id=%d "
                        + "sfu_id=%d grant_tx_ns=%d%s tx_start_ns=%d tx_end_ns=%d granted_bytes=%d%n",
                formatMicroseconds(nowNs), received ? "SFU" : "MFU", received ? "PON_BWMAP_RX" : "PON_BWMAP_TX",
                grantFrameId, targetFrameId, packet.id(), sfuId, grantTxNs, reception, startNs, endNs, packet.bytes());
    }

    public void recordPonBwmapSummary(long packets, double ungrantedWaitUs, double reservedWaitUs,
                                      double serializationUs) {
        output.printf(Locale.ROOT, "summary=PON_BWMAP packets=%d avg_ungranted_wait_us=%.6f "
                        + "avg_reserved_wait_us=%.6f avg_serialization_us=%.6f%n",
                packets, ungrantedWaitUs, reservedWaitUs, serializationUs);
    }

    public void recordPonUplinkFrame(long frameId, long frameStartNs, long frameEndNs, int packets, long busyNs) {
        long periodNs = frameEndNs - frameStartNs;
        output.printf(Locale.ROOT, "summary=PON_UPLINK_FRAME frame_id=%d frame_start_ns=%d frame_end_ns=%d "
                        + "allocated_packets=%d busy_ns=%d idle_ns=%d utilization=%.9f%n",
                frameId, frameStartNs, frameEndNs, packets, busyNs, periodNs - busyNs, utilization(busyNs, periodNs));
    }

    public void recordPonUplinkFrames(long frames, long periodNs, long packets, long busyNs, double grantWaitUs) {
        long windowNs = frames * periodNs;
        output.printf(Locale.ROOT, "summary=PON_UPLINK_FRAMES frames=%d frame_period_ns=%d window_ns=%d "
                        + "allocated_packets=%d busy_ns=%d idle_ns=%d avg_grant_wait_us=%.6f utilization=%.9f%n",
                frames, periodNs, windowNs, packets, busyNs, windowNs - busyNs, grantWaitUs, utilization(busyNs, windowNs));
    }

    private static double utilization(long busyNs, long windowNs) {
        return windowNs == 0 ? 0.0 : (double) busyNs / windowNs;
    }

    private void write(Packet packet, long timeTicks, String node, String action, int sfuId) {
        if (packet.isUplink()) {
            writeUplink(packet, timeTicks, node, action, sfuId, "");
            return;
        }
        String route = sfuId == 0 ? "" : " sfu_id=" + sfuId;
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s packet_id=%d bytes=%d type=%s destination_sta_id=%d%s%n",
                formatMicroseconds(timeTicks), node, action, packet.id(), packet.bytes(), packet.type(),
                packet.destinationStaId(), route);
    }

    public void recordWifiDataEnd(Packet packet, int sfuId, long timeTicks) {
        String receiver = packet.isUplink() ? "SFU" : "STA";
        write(packet, timeTicks, receiver, "WIFI_DATA_END", sfuId);
        write(packet, timeTicks, receiver, "WIFI_SIFS_START", sfuId);
    }

    public void recordWifiMpduDecode(long endNs, long startNs, Packet head, Packet packet, int sfuId, int attempt,
                                     int mcs, int mpduBytes, ChannelSample sample, int frequencyBlocks,
                                     SinrPerModel.Decision decision) {
        String outcome = decision == null ? "MAC_COLLISION" : decision.decoded() ? "DECODED" : "SINR_ERROR";
        String scalarSinr = sample != null && sample.uniformSinr() ? Double.toString(sample.sinrDb(0)) : "-";
        output.printf(Locale.ROOT, "event=WIFI_PHY_MPDU_DECODE t_ns=%d ppdu_start_ns=%d sfu_id=%d sta_id=%d "
                        + "head_id=%d packet_id=%d attempt=%d mcs=%d mpdu_bytes=%d sinr_db=%s per=%s draw=%s outcome=%s%s%n",
                endNs, startNs, sfuId, packet.staId(), head.id(), packet.id(), attempt, mcs, mpduBytes,
                scalarSinr, decision == null ? "-" : Double.toString(decision.per()),
                decision == null ? "-" : Double.toString(decision.draw()), outcome,
                frequencyBlocks == 0 ? "" : frequencyBlockDetails(sample, frequencyBlocks));
    }

    private static String frequencyBlockDetails(ChannelSample sample, int blocks) {
        StringJoiner channels = new StringJoiner(",");
        StringJoiner sinrDb = new StringJoiner(",");
        StringJoiner weights = new StringJoiner(",");
        for (int subchannel = 0; subchannel < blocks; subchannel++) {
            channels.add(Integer.toString(subchannel));
            if (sample != null) {
                sinrDb.add(Double.toString(sample.sinrDb(subchannel)));
                weights.add(Double.toString(sample.weight(subchannel)));
            }
        }
        return " frequency_model=subchannel-blocks subchannels=" + channels
                + " subchannel_sinr_db=" + (sample == null ? "-" : sinrDb)
                + " block_weights=" + (sample == null ? "-" : weights);
    }

    public void recordAmpduEvent(long nowNs, String phase, Packet head, int sfuId, List<Packet> members, int bytes,
                                  long ppduNs, long sifsNs, long baNs, long bitmap) {
        StringBuilder packetIds = new StringBuilder();
        for (Packet packet : members) {
            if (packetIds.length() > 0) {
                packetIds.append(',');
            }
            packetIds.append(packet.id());
        }
        output.printf(Locale.ROOT, "t_us=%s t_ns=%d node=SFU event=WIFI_AMPDU_%s sfu_id=%d packet_id=%d "
                        + "sta_id=%d type=%s direction=%s count=%d apep_bytes=%d ppdu_ns=%d sifs_ns=%d ba_ns=%d "
                        + "packet_ids=%s bitmap=%s%n",
                formatMicroseconds(nowNs), nowNs, phase, sfuId, head.id(), head.staId(), head.type(), head.direction(),
                members.size(), bytes, ppduNs, sifsNs, baNs, packetIds, Long.toUnsignedString(bitmap, 16));
    }

    public void recordAmpduMpdu(long nowNs, Packet head, Packet member, int sfuId, String status, int retries) {
        output.printf(Locale.ROOT, "t_us=%s t_ns=%d node=SFU event=WIFI_AMPDU_MPDU sfu_id=%d packet_id=%d "
                        + "head_id=%d sta_id=%d type=%s direction=%s status=%s retry_count=%d%n",
                formatMicroseconds(nowNs), nowNs, sfuId, member.id(), head.id(), head.staId(), head.type(),
                head.direction(), status, retries);
    }

    private void writeUplink(Packet packet, long timeTicks, String node, String action, int sfuId, String details) {
        String route = sfuId == 0 ? "" : " sfu_id=" + sfuId;
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s packet_id=%d bytes=%d type=%s "
                        + "direction=UL source_sta_id=%d destination_mfu_id=%d%s%s%n",
                formatMicroseconds(timeTicks), node, action, packet.id(), packet.bytes(), packet.type(), packet.staId(),
                packet.destinationNodeId(), route, details);
    }

    public void recordDelivery(Packet packet, Sta sta, long timeTicks) {
        output.printf(Locale.ROOT,
                "t_us=%s node=STA event=DELIVERED packet_id=%d bytes=%d type=%s destination_sta_id=%d sta_id=%d sfu_id=%d%n",
                formatMicroseconds(timeTicks), packet.id(), packet.bytes(), packet.type(), packet.destinationStaId(),
                sta.id(), sta.sfuId());
    }

    public void recordUplinkDelivery(Packet packet, int sfuId, long timeTicks, long endToEndTicks) {
        writeUplink(packet, timeTicks, "MFU", "DELIVERED", sfuId,
                " e2e_delay_us=" + formatMicroseconds(endToEndTicks));
    }

    public void recordEnqueue(Packet packet, long timeTicks, int sfuId, int queueDepth) {
        if (packet.isUplink()) {
            writeUplink(packet, timeTicks, "STA", "SFU_ENQUEUE", sfuId,
                    " generation_us=" + packet.generationTimeUs() + " queue_depth=" + queueDepth);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%s node=SFU event=SFU_ENQUEUE packet_id=%d bytes=%d type=%s mfu_arrival_us=%d queue_depth=%d destination_sta_id=%d sfu_id=%d%n",
                formatMicroseconds(timeTicks), packet.id(), packet.bytes(), packet.type(), packet.mfuArrivalUs(),
                queueDepth, packet.destinationStaId(), sfuId);
    }

    public void recordQueueSelection(Packet packet, long timeTicks, int sfuId, int vo, int vi, int be, int bk) {
        if (packet.isUplink()) {
            writeUplink(packet, timeTicks, "SFU", "QUEUE_SELECT", sfuId,
                    " vo_queue=" + vo + " vi_queue=" + vi + " be_queue=" + be + " bk_queue=" + bk);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%s node=SFU event=QUEUE_SELECT packet_id=%d type=%s vo_queue=%d vi_queue=%d be_queue=%d bk_queue=%d destination_sta_id=%d sfu_id=%d%n",
                formatMicroseconds(timeTicks), packet.id(), packet.type(), vo, vi, be, bk, packet.destinationStaId(),
                sfuId);
    }

    public void recordVirtualCollision(Packet loser, Packet winner, int sfuId, long ticks, int cwBefore, int cwAfter,
                                       int backoff) {
        output.printf(Locale.ROOT,
                "t_us=%s node=%s event=EDCA_VIRTUAL_COLLISION packet_id=%d type=%s direction=%s sfu_id=%d sta_id=%d "
                        + "winner_packet_id=%d cw_before=%d cw_after=%d backoff=%d%n",
                formatMicroseconds(ticks), loser.isUplink() ? "STA" : "SFU", loser.id(), loser.type(), loser.direction(),
                sfuId, loser.staId(), winner.id(), cwBefore, cwAfter, backoff);
    }

    public void recordCollisionPacket(Packet packet, int sfuId, long ticks, String action) {
        writeMacPacket(packet, sfuId, ticks, action, "");
    }

    public void recordMacRetryPacket(Packet packet, int sfuId, long ticks, String action, int retries) {
        writeMacPacket(packet, sfuId, ticks, action, " retry_count=" + retries);
    }

    public void recordMacRetry(Packet packet, int sfuId, long ticks, int retries, int cwBefore, int cwAfter, int backoff) {
        String detail = " retry_count=" + retries + " cw_before=" + cwBefore + " cw_after=" + cwAfter + " backoff=" + backoff;
        writeMacPacket(packet, sfuId, ticks, "WIFI_MAC_RETRY", detail);
    }

    private void writeMacPacket(Packet packet, int sfuId, long ticks, String action, String detail) {
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s packet_id=%d bytes=%d type=%s direction=%s sfu_id=%d sta_id=%d%s%n",
                formatMicroseconds(ticks), packet.isUplink() ? "STA" : "SFU", action, packet.id(), packet.bytes(),
                packet.type(), packet.direction(), sfuId, packet.staId(), detail);
    }

    public void recordCollisionEnd(int sfuId, long ticks) {
        output.printf(Locale.ROOT, "t_us=%s node=SFU event=WIFI_COLLISION_END sfu_id=%d%n",
                formatMicroseconds(ticks), sfuId);
    }

    public void recordCollisionSummary(long collisionEvents, int droppedPackets, long droppedBytes) {
        output.printf(Locale.ROOT, "summary=WIFI_COLLISION collision_events=%d dropped_packets=%d dropped_bytes=%d%n",
                collisionEvents, droppedPackets, droppedBytes);
    }

    public void recordRetrySummary(long retransmissions, long ackTimeouts, int retryLimitDrops) {
        output.printf(Locale.ROOT, "summary=WIFI_RETRY retransmissions=%d ack_timeouts=%d retry_limit_drops=%d%n",
                retransmissions, ackTimeouts, retryLimitDrops);
    }

    public void recordWifiTxopStart(Packet packet, int sfuId, long ticks, long limitNs) {
        writeMacPacket(packet, sfuId, ticks, "WIFI_TXOP_START", " limit_us=" + formatMicroseconds(limitNs));
    }

    public void recordWifiTxopEnd(Packet packet, int sfuId, long ticks, long limitNs, long durationTicks,
                                  int packetCount) {
        String detail = " limit_us=" + formatMicroseconds(limitNs) + " duration_us=" + formatMicroseconds(durationTicks)
                + " packet_count=" + packetCount;
        writeMacPacket(packet, sfuId, ticks, "WIFI_TXOP_END", detail);
    }

    public void recordTxopSummary(int txops, double durationUs, int transmittedPackets) {
        output.printf(Locale.ROOT, "summary=WIFI_TXOP txops=%d total_duration_us=%.3f transmitted_packets=%d%n",
                txops, durationUs, transmittedPackets);
    }

    public void recordWifiService(Packet packet, int sfuId, long queueEntryTicks, long startTicks, long endTicks,
                                  long endToEndTicks) {
        String startUs = formatMicroseconds(startTicks);
        String waitUs = formatMicroseconds(startTicks - queueEntryTicks);
        String serviceUs = formatMicroseconds(endTicks - startTicks);
        if (packet.isUplink()) {
            writeUplink(packet, endTicks, "SFU", "WIFI_TX_END", sfuId, " wifi_tx_start_us=" + startUs
                    + " wifi_queue_wait_us=" + waitUs + " wifi_service_us=" + serviceUs);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%s node=SFU event=WIFI_TX_END packet_id=%d wifi_tx_start_us=%s wifi_queue_wait_us=%s "
                        + "wifi_service_us=%s e2e_delay_us=%s type=%s destination_sta_id=%d sfu_id=%d%n",
                formatMicroseconds(endTicks), packet.id(), startUs, waitUs, serviceUs,
                formatMicroseconds(endToEndTicks), packet.type(), packet.destinationStaId(), sfuId);
    }

    public void recordWirelessPhyRate(double rateMbps) {
        output.printf(Locale.ROOT, "summary=WIFI_PHY model=HE_SU phy_rate_mbps=%.6f%n", rateMbps);
    }

    public void recordApCapability(int sfuId, ApCapabilityMode mode) {
        output.printf(Locale.ROOT, "summary=WIFI_AP_CAPABILITY sfu_id=%d ap_mode=%s bandwidth_mhz=%d nss=%d "
                        + "mcs=%d gi_us=%.1f%n", sfuId, mode, mode.bandwidthMhz(), mode.nss(),
                ApCapabilityMode.MCS, ApCapabilityMode.GI_NS / 1_000.0);
    }

    public void recordStationPhy(Sta sta, ApCapabilityMode mode, WirelessPhy phy) {
        output.printf(Locale.ROOT, "summary=WIFI_STA_PHY sfu_id=%d sta_id=%d ap_mode=%s bandwidth_mhz=%d nss=%d "
                        + "phy_rate_mbps=%.6f%n", sta.sfuId(), sta.id(), mode, phy.bandwidthMhz(), phy.nss(),
                phy.phyRateMbps());
    }

    public void recordChannelTruth(ChannelTruth.Change change) {
        output.printf(Locale.ROOT, "event=WIFI_CHANNEL_TRUTH t_ns=%d sfu_id=%d sta_id=%d subchannel=%d "
                        + "sinr_db=%s cqi=%d interfered=%s%n",
                change.effectiveAtNs(), change.sfuId(), change.staId(), change.subchannel(),
                Double.toString(change.sinrDb()), change.cqi(), change.interfered());
    }

    public void recordChannelFeedback(long timeNs, ChannelObservationInput.Feedback request, String phase,
                                      ChannelReport report) {
        output.printf(Locale.ROOT, "event=WIFI_CHANNEL_FEEDBACK t_ns=%d report_id=%d sfu_id=%d sta_id=%d "
                        + "subchannel=%d measured_ns=%d arrival_ns=%d expires_ns=%d phase=%s sinr_db=%s cqi=%s%s%n",
                timeNs, request.id(), request.sfuId(), request.staId(), request.subchannel(),
                request.measuredNs(), request.arrivalNs(), request.expiresNs(), phase,
                report == null ? "-" : Double.toString(report.sinrDb()), report == null ? "-" : report.cqi(),
                request.airtimeNs() == 0 ? "" : " airtime_ns=" + request.airtimeNs());
    }

    public void recordChannelScan(long timeNs, ChannelObservationInput.Scan scan, String phase, long busyNs,
                                  BigDecimal energyUj) {
        output.printf(Locale.ROOT, "event=WIFI_CHANNEL_SCAN t_ns=%d sfu_id=%d subchannel=%d phase=%s "
                        + "started_ns=%d ended_ns=%d busy_ns=%d busy_fraction=%.9f expires_ns=%d extra_energy_uj=%s%n",
                timeNs, scan.sfuId(), scan.subchannel(), phase, scan.startNs(), scan.endNs(), busyNs,
                (double) busyNs / (scan.endNs() - scan.startNs()), scan.expiresNs(), energyUj.toPlainString());
    }

    public void recordPassiveChannel(long timeNs, Sfu sfu, int busyMask, long confirmed, long retries, long dropped) {
        long settled = confirmed + dropped;
        output.printf(Locale.ROOT, "event=WIFI_CHANNEL_PASSIVE t_ns=%d sfu_id=%d cca=%s visible_busy_mask=%d "
                        + "confirmed=%d retransmissions=%d dropped=%d success_ratio=%.9f%n",
                timeNs, sfu.id(), sfu.ccaState(), busyMask, confirmed, retries, dropped,
                settled == 0 ? -1.0 : (double) confirmed / settled);
    }

    public void recordChannelOverhead(int sfuId, long feedbackNs, long scanNs, BigDecimal scanEnergyUj,
                                      BigDecimal feedbackRxEnergyUj, BigDecimal feedbackExtraEnergyUj) {
        output.printf(Locale.ROOT, "summary=WIFI_CHANNEL_OVERHEAD sfu_id=%d feedback_airtime_ns=%d "
                        + "scan_airtime_ns=%d scan_energy_uj=%s feedback_rx_energy_uj=%s feedback_extra_energy_uj=%s%n",
                sfuId, feedbackNs, scanNs, scanEnergyUj.toPlainString(), feedbackRxEnergyUj.toPlainString(),
                feedbackExtraEnergyUj.toPlainString());
    }

    public void recordChannelObservation(long timeNs, Sfu sfu, int staId, int subchannel) {
        ChannelReport report = sfu.channelReport(staId, subchannel, timeNs).orElse(null);
        output.printf(Locale.ROOT, "event=WIFI_CHANNEL_OBSERVATION t_ns=%d sfu_id=%d sta_id=%d subchannel=%d "
                        + "status=%s measured_ns=%s arrival_ns=%s expires_ns=%s sinr_db=%s cqi=%s "
                        + "local_ul_data_ns=%s local_dl_ack_ns=%s local_dl_ba_ns=%s%n",
                timeNs, sfu.id(), staId, subchannel, sfu.channelVisibility(staId, subchannel, timeNs),
                report == null ? "-" : report.measuredAtNs(), report == null ? "-" : report.arrivedAtNs(),
                report == null ? "-" : report.expiresAtNs(), report == null ? "-" : Double.toString(report.sinrDb()),
                report == null ? "-" : report.cqi(), localChannelTime(sfu, staId, LocalReception.UL_DATA, timeNs),
                localChannelTime(sfu, staId, LocalReception.DL_ACK, timeNs),
                localChannelTime(sfu, staId, LocalReception.DL_BLOCK_ACK, timeNs));
    }

    private static String localChannelTime(Sfu sfu, int staId, LocalReception kind, long timeNs) {
        return sfu.localChannelReception(staId, kind, timeNs).map(value -> Long.toString(value.receivedAtNs()))
                .orElse("-");
    }

    public void recordChannelLocal(long timeNs, int sfuId, int staId, LocalReception kind, int mpdus) {
        output.printf(Locale.ROOT, "event=WIFI_CHANNEL_LOCAL t_ns=%d sfu_id=%d sta_id=%d kind=%s mpdus=%d%n",
                timeNs, sfuId, staId, kind, mpdus);
    }

    public void recordExternalCca(long timeNs, int sfuId, int observedMask, int accessMask, boolean busy) {
        output.printf(Locale.ROOT, "event=WIFI_EXTERNAL_CCA t_ns=%d sfu_id=%d observed_mask=%d access_mask=%d busy=%b%n",
                timeNs, sfuId, observedMask, accessMask, busy);
    }

    public void recordApActivityChange(int sfuId, ApCapabilityMode mode, long timeNs, ApActivity.Transition change,
                                       Packet packet, String reason) {
        String packetFields = packet == null ? "" : " packet_id=" + packet.id() + " direction=" + packet.direction();
        output.printf(Locale.ROOT, "t_us=%s t_ns=%d node=SFU event=WIFI_AP_ACTIVITY_CHANGE sfu_id=%d ap_mode=%s "
                        + "from=%s to=%s reason=%s%s%n",
                formatMicroseconds(timeNs), timeNs, sfuId, mode == null ? "LEGACY" : mode.name(),
                change.from(), change.to(), reason, packetFields);
    }

    public void recordApTrafficObservation(int sfuId, ApTrafficObserver.Observation observation) {
        output.printf(Locale.ROOT, "event=WIFI_AP_OBSERVATION t_ns=%d sfu_id=%d ac=%s direction=%s "
                        + "window_start_ns=%d window_end_ns=%d pending_packets=%d pending_bytes=%d head_wait_ns=%d "
                        + "inflight_packets=%d inflight_bytes=%d awaiting_confirm_packets=%d "
                        + "arrival_packets=%d arrival_bytes=%d delivered_packets=%d delivered_bytes=%d "
                        + "confirmed_packets=%d confirmed_bytes=%d retry_attempt_mpdus=%d retry_attempt_bytes=%d "
                        + "requeued_packets=%d dropped_packets=%d dropped_bytes=%d "
                        + "tx_ns=%d rx_ns=%d ppdus=%d mpdus=%d avg_depth=%.12g "
                        + "arrival_pps=%.12g arrival_mbps=%.12g delivery_pps=%.12g delivery_mbps=%.12g "
                        + "ewma_arrival_pps=%.12g ewma_arrival_mbps=%.12g delivery_success_ratio=%.12g%n",
                observation.endNs(), sfuId, observation.category(), observation.direction(),
                observation.startNs(), observation.endNs(), observation.queuePackets(), observation.queueBytes(),
                observation.headWaitNs(), observation.inFlightPackets(), observation.inFlightBytes(),
                observation.awaitingConfirmPackets(), observation.arrivalPackets(), observation.arrivalBytes(),
                observation.deliveredPackets(), observation.deliveredBytes(), observation.confirmedPackets(),
                observation.confirmedBytes(), observation.retriedPackets(), observation.retriedBytes(),
                observation.requeuedPackets(), observation.droppedPackets(), observation.droppedBytes(),
                observation.txNs(), observation.rxNs(), observation.dataPpdus(), observation.ppduMembers(),
                observation.averageDepth(), observation.arrivalPps(), observation.arrivalMbps(),
                observation.deliveredPps(), observation.deliveredMbps(), observation.ewmaArrivalPps(),
                observation.ewmaArrivalMbps(), observation.deliverySuccessRatio());
    }

    public void recordApQueueEvent(int sfuId, ApTrafficObserver.QueueEvent event) {
        Packet packet = event.packet();
        output.printf(Locale.ROOT, "event=WIFI_AP_QUEUE_EVENT t_ns=%d sfu_id=%d ac=%s direction=%s "
                        + "packet_id=%d bytes=%d action=%s pending_packets=%d pending_bytes=%d "
                        + "inflight_packets=%d inflight_bytes=%d awaiting_confirm_packets=%d%n",
                event.timeNs(), sfuId, packet.type(), packet.direction(), packet.id(), packet.bytes(), event.kind(),
                event.queuePackets(), event.queueBytes(), event.inFlightPackets(), event.inFlightBytes(),
                event.awaitingConfirmPackets());
    }

    public void recordApCcaHistory(int sfuId, long timeNs, CcaHistory.Snapshot snapshot) {
        recordApCcaBand(sfuId, timeNs, snapshot.fromNs, "PRIMARY", snapshot.primary);
        recordApCcaBand(sfuId, timeNs, snapshot.fromNs, "WIDE", snapshot.wide);
    }

    private void recordApCcaBand(int sfuId, long timeNs, long fromNs, String band, CcaHistory.Estimate estimate) {
        output.printf(Locale.ROOT, "event=WIFI_AP_CCA_HISTORY t_ns=%d sfu_id=%d band=%s from_ns=%d window_ns=%d "
                        + "busy_ns=%d known_ns=%d fully_sensed_ns=%d age_ns=%d oldest_band_age_ns=%d "
                        + "lower=%.12g expected=%.12g conservative=%.12g upper=%.12g current=%s "
                        + "ongoing_busy_ns=%d longest_busy_ns=%d%n",
                timeNs, sfuId, band, fromNs, estimate.windowNs, estimate.busyNs, estimate.knownNs,
                estimate.fullySensedNs, estimate.ageNs, estimate.oldestBandAgeNs, estimate.lower(), estimate.expected(),
                estimate.conservative(), estimate.upper(), estimate.current, estimate.ongoingBusyNs, estimate.longestBusyNs);
    }

    public void recordApPrediction(int sfuId, ApModePredictor.Forecast forecast) {
        String revised = forecast.revised ? String.format(Locale.ROOT,
                " prediction_model=revised phy_mbps=%.12g mac_mbps=%.12g conservative_mbps=%.12g"
                        + " optimistic_mbps=%.12g incremental_energy_uj=%.12g input_mbps=%.12g",
                forecast.phyMbps, forecast.macMbps, forecast.conservativeMbps, forecast.optimisticMbps,
                forecast.incrementalEnergyUj, forecast.inputMbps) : "";
        output.printf(Locale.ROOT, "event=WIFI_AP_PREDICTION t_ns=%d window_end_ns=%d sfu_id=%d current_mode=%s "
                        + "mode=%s confidence=%s busy_fraction=%.12g service_mbps=%.12g success_mbps=%.12g "
                        + "agg_depth=%.12g retry_fraction=%.12g queue_packets=%.12g ending_queue_packets=%.12g "
                        + "workload_ns=%.12g tx_ns=%.12g rx_ns=%.12g idle_ns=%.12g tx_uj=%.12g rx_uj=%.12g "
                        + "idle_uj=%.12g energy_uj=%.12g energy_per_bit_uj=%.12g switch_ns=%d switch_uj=%.12g%s%n",
                forecast.startNs, forecast.endNs, sfuId, forecast.currentMode, forecast.mode, forecast.confidence,
                forecast.busyFraction, forecast.serviceMbps, forecast.successMbps, forecast.aggDepth,
                forecast.retryFraction, forecast.queuePackets, forecast.endingQueuePackets, forecast.workloadNs,
                forecast.txNs, forecast.rxNs, forecast.idleNs, forecast.txUj, forecast.rxUj, forecast.idleUj,
                forecast.energyUj, forecast.energyPerBitUj, forecast.switchNs, forecast.switchUj, revised);
        for (ApModePredictor.QosForecast qos : forecast.qos) {
            output.printf(Locale.ROOT, "event=WIFI_AP_QOS_FEASIBILITY t_ns=%d window_end_ns=%d sfu_id=%d "
                            + "mode=%s ac=%s status=%s waiting_ns=%.12g service_ns=%.12g e2e_ns=%.12g "
                            + "success_probability=%.12g deadline_ns=%d min_on_time=%.12g%s%n",
                    forecast.startNs, forecast.endNs, sfuId, forecast.mode, qos.ac, qos.status, qos.waitingNs,
                    qos.serviceNs, qos.e2eNs, qos.successProbability, qos.requirement.deadlineNs(),
                    qos.requirement.minOnTime(), forecast.revised ? " conservative_status=" + qos.conservativeStatus
                            + " optimistic_status=" + qos.optimisticStatus : "");
        }
    }

    public void recordApMacEvidence(int sfuId, long nowNs, ApModePredictor.Evidence evidence, long aifsNs,
                                    long backoffNs, long frozenNs, List<ApTrafficObserver.Observation> traffic) {
        long[] ppdus = new long[2];
        long[] attempts = new long[2];
        long[] confirmations = new long[2];
        long[] dataNs = new long[2];
        long[] responseNs = new long[2];
        for (ApModePredictor.Transmission ppdu : evidence.completedPpdus()) {
            int direction = ppdu.direction().ordinal();
            ppdus[direction]++;
            attempts[direction] += ppdu.attemptedMpdus();
            confirmations[direction] += ppdu.confirmedMpdus();
            dataNs[direction] += ppdu.dataNs();
            responseNs[direction] += ppdu.responseNs();
        }
        double ingressMbps = traffic.stream().mapToDouble(ApTrafficObserver.Observation::arrivalMbps).sum();
        output.printf(Locale.ROOT, "event=WIFI_AP_MAC_EVIDENCE t_ns=%d sfu_id=%d eligible_ns=%d occupied_ns=%d "
                        + "blocked_ns=%d aifs_ns=%d backoff_ns=%d frozen_ns=%d ppdus=%d dl_ppdus=%d ul_ppdus=%d "
                        + "dl_attempted=%d dl_confirmed=%d dl_depth=%.12g ul_depth=%.12g arrival_mbps=%.12g "
                        + "dl_data_ns=%d ul_data_ns=%d dl_response_ns=%d ul_response_ns=%d%n",
                nowNs, sfuId, evidence.eligibleNs(), evidence.occupiedNs(), evidence.blockedNs(), aifsNs,
                backoffNs, frozenNs, ppdus[0] + ppdus[1], ppdus[0], ppdus[1], attempts[0], confirmations[0],
                ppdus[0] == 0 ? 0 : (double) attempts[0] / ppdus[0],
                ppdus[1] == 0 ? 0 : (double) attempts[1] / ppdus[1], ingressMbps, dataNs[0], dataNs[1],
                responseNs[0], responseNs[1]);
    }

    public void recordApQos(ApQosLedger.Snapshot qos) {
        output.printf(Locale.ROOT, "event=WIFI_AP_QOS t_ns=%d sfu_id=%d direction=%s ac=%s deadline_ns=%d "
                        + "arrived=%d delivered=%d dropped=%d incomplete=%d overdue=%d on_time=%d violations=%d "
                        + "decided=%d avg_ns=%.12g p95_ns=%d p99_ns=%d jitter_ns=%.12g on_time_rate=%.12g "
                        + "violation_rate=%.12g loss_rate=%.12g queue_packets=%d inflight_packets=%d "
                        + "awaiting_confirm_packets=%d head_wait_ns=%d backlog_delta=%d%n",
                qos.cutoffNs, qos.sfuId, qos.direction, qos.ac, qos.deadlineNs, qos.arrived, qos.delivered,
                qos.dropped, qos.incomplete, qos.overdue, qos.onTime, qos.violations, qos.decided, qos.avgNs,
                qos.p95Ns, qos.p99Ns, qos.jitterNs, qos.onTimeRate, qos.violationRate, qos.lossRate, qos.queuePackets,
                qos.inflightPackets, qos.awaitingConfirmPackets, qos.headWaitNs, qos.backlogDelta);
    }

    public void recordApObservedPpdu(Packet packet, int sfuId, int depth, long timeNs) {
        output.printf(Locale.ROOT, "event=WIFI_AP_PPDU_OBSERVED t_ns=%d sfu_id=%d ac=%s direction=%s "
                        + "packet_id=%d depth=%d%n",
                timeNs, sfuId, packet.type(), packet.direction(), packet.id(), depth);
    }

    public void recordApActivityTotals(int sfuId, String mode, ApActivity.Totals totals, boolean switchingEnabled) {
        String switchField = switchingEnabled ? " switch_ns=" + totals.switchNs() : "";
        output.printf(Locale.ROOT, "summary=WIFI_AP_ACTIVITY sfu_id=%d ap_mode=%s observation_start_ns=0 "
                        + "observation_end_ns=%d idle_ns=%d tx_ns=%d rx_ns=%d%s%n",
                sfuId, mode, totals.observationEndNs(), totals.idleNs(), totals.txNs(), totals.rxNs(), switchField);
    }

    public void recordInitialApMode(int sfuId, ApModeController.Policy policy, ApCapabilityMode mode) {
        output.printf(Locale.ROOT, "t_us=0 t_ns=0 node=SFU event=WIFI_AP_MODE_INITIAL sfu_id=%d policy=%s "
                + "current_mode=%s target_mode=%s%n", sfuId, policy.key(), mode, mode);
    }

    public void recordApModeDecision(int sfuId, ApCapabilityMode current, ApModeController.Decision decision,
                                     ApModeSwitch.Outcome outcome) {
        output.printf(Locale.ROOT, "t_us=%s t_ns=%d node=SFU event=WIFI_AP_MODE_DECISION sfu_id=%d policy=%s "
                        + "window_start_ns=%d arrival_bytes=%d arrival_mbps=%s ewma_mbps=%s current_mode=%s "
                        + "target_mode=%s action=%s switch_id=%d%n",
                formatMicroseconds(decision.timeNs()), decision.timeNs(), sfuId,
                decision.policy().key(), decision.windowStartNs(), decision.arrivedBytes(),
                decision.arrivalMbps(), decision.estimateMbps(), current, decision.target(), outcome.action(),
                outcome.requestId());
    }

    public void recordApSwitchEvent(long timeNs, String event, int sfuId, ApModeSwitch.Request request,
                                    ApCapabilityMode from, ApModeSwitch.Profile profile, long startNs, long observedNs,
                                    BigDecimal energyUj) {
        String duration = profile == null ? "" : " duration_ns=" + profile.delayNs()
                + " power_w=" + profile.powerW().toPlainString();
        String accounting = energyUj == null ? "" : " observed_ns=" + observedNs
                + " switch_energy_uj=" + energyUj.toPlainString();
        String online = "";
        if (request.policy() != null) {
            online = " policy=" + request.policy().key();
            if (startNs >= 0) {
                online += " switch_start_ns=" + startNs + " wait_ns=" + (startNs - request.timeNs());
            }
            if (energyUj != null) {
                online += " switch_end_ns=" + timeNs;
            }
        }
        output.printf(Locale.ROOT, "t_us=%s t_ns=%d node=SFU event=%s sfu_id=%d switch_id=%d from_mode=%s to_mode=%s "
                        + "request_ns=%d%s%s%s%n",
                formatMicroseconds(timeNs), timeNs, event, sfuId, request.id(), from, request.target(),
                request.timeNs(), duration, accounting, online);
    }

    public void recordApEnergy(int sfuId, ApCapabilityMode mode, long observationEndNs, ApEnergyModel.Power power,
                               ApEnergyModel.Energy energy) {
        output.printf(Locale.ROOT, "summary=WIFI_AP_ENERGY model=%s sfu_id=%d ap_mode=%s observation_start_ns=0 "
                        + "observation_end_ns=%d idle_power_w=%s tx_power_w=%s rx_power_w=%s %s%n",
                ApEnergyModel.PROFILE, sfuId, mode.name(), observationEndNs, power.idleW().toPlainString(),
                power.txW().toPlainString(), power.rxW().toPlainString(), formatApEnergy(energy, observationEndNs, false));
    }

    public void recordApNetworkEnergy(long observationEndNs, int modeledApCount, int legacyApCount,
                                      ApEnergyModel.Energy energy, boolean switchingEnabled) {
        output.printf(Locale.ROOT, "summary=WIFI_AP_ENERGY_NETWORK model=%s observation_start_ns=0 "
                        + "observation_end_ns=%d modeled_ap_count=%d legacy_ap_count=%d %s%n",
                ApEnergyModel.PROFILE, observationEndNs, modeledApCount, legacyApCount,
                formatApEnergy(energy, observationEndNs, switchingEnabled));
    }

    public void recordSwitchableApEnergy(int sfuId, String mode, long observationEndNs, ApEnergyModel.Energy energy) {
        output.printf(Locale.ROOT, "summary=WIFI_AP_ENERGY model=%s sfu_id=%d ap_mode=%s observation_start_ns=0 "
                        + "observation_end_ns=%d %s%n",
                ApEnergyModel.PROFILE, sfuId, mode, observationEndNs, formatApEnergy(energy, observationEndNs, true));
    }

    public void recordApEnergyByMode(int sfuId, ApCapabilityMode mode, ApActivity.Durations durations,
                                     ApEnergyModel.Power power, ApEnergyModel.Energy energy) {
        output.printf(Locale.ROOT, "summary=WIFI_AP_ENERGY_MODE sfu_id=%d ap_mode=%s idle_ns=%d tx_ns=%d rx_ns=%d "
                        + "idle_power_w=%s tx_power_w=%s rx_power_w=%s %s%n",
                sfuId, mode, durations.idleNs(), durations.txNs(), durations.rxNs(),
                power.idleW().toPlainString(), power.txW().toPlainString(), power.rxW().toPlainString(),
                formatApStateEnergy(energy));
    }

    private static String formatApStateEnergy(ApEnergyModel.Energy energy) {
        return "idle_energy_uj=" + energy.idleUj().toPlainString() + " tx_energy_uj=" + energy.txUj().toPlainString()
                + " rx_energy_uj=" + energy.rxUj().toPlainString();
    }

    private static String formatApEnergy(ApEnergyModel.Energy energy, long observationEndNs, boolean switchingEnabled) {
        String switching = switchingEnabled ? " switch_energy_uj=" + energy.switchUj().toPlainString() : "";
        return formatApStateEnergy(energy) + switching + " total_energy_uj=" + energy.totalUj().toPlainString()
                + " avg_power_w=" + energy.averagePowerW(observationEndNs).toPlainString();
    }

    public void recordPonSummary(Packet.Direction direction, long sentPackets, double averageWaitUs,
                                 double averageSerializationUs, long busyTicks, long windowTicks, double utilization) {
        String link = direction == Packet.Direction.UL ? "UPLINK" : "DOWNLINK";
        output.printf(Locale.ROOT, "summary=PON_%s sent_packets=%d avg_queue_wait_us=%.6f "
                        + "avg_serialization_us=%.6f busy_us=%s window_us=%s utilization=%.6f%n",
                link, sentPackets, averageWaitUs, averageSerializationUs, formatMicroseconds(busyTicks),
                formatMicroseconds(windowTicks), utilization);
    }

    public void recordClassSummary(AccessCategory type, int sentPackets, double waitUs, double delayUs) {
        output.printf(Locale.ROOT,
                "summary=CLASS type=%s sent_packets=%d avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                type, sentPackets, waitUs, delayUs);
    }

    /** 汇总泊松输入。type 为空时统计该 SFU 的全部业务。 */
    public void recordTrafficSummary(int sfuId, AccessCategory type, int packets, long bytes, long windowUs) {
        String group = type == null ? "summary=TRAFFIC_SFU sfu_id=" + sfuId
                : "summary=TRAFFIC_CLASS sfu_id=" + sfuId + " type=" + type;
        writeTrafficSummary(group, packets, bytes, windowUs);
    }

    public void recordUplinkTrafficSummary(int staId, int sfuId, AccessCategory type, int packets, long bytes,
                                           long windowUs) {
        String group = (type == null ? "summary=TRAFFIC_UL_STA" : "summary=TRAFFIC_UL_CLASS")
                + " sta_id=" + staId + " sfu_id=" + sfuId + (type == null ? "" : " type=" + type);
        writeTrafficSummary(group, packets, bytes, windowUs);
    }

    private void writeTrafficSummary(String group, int packets, long bytes, long windowUs) {
        output.printf(Locale.ROOT, "%s generated_packets=%d generated_bytes=%d window_us=%d "
                        + "actual_rate_pps=%.6f offered_load_mbps=%.6f%n",
                group, packets, bytes, windowUs, packets * 1_000_000.0 / windowUs, bytes * 8.0 / windowUs);
    }

    public void recordGlobalDelay(int sentPackets, double waitUs, double delayUs) {
        output.printf(Locale.ROOT,
                "summary=GLOBAL sent_packets=%d avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                sentPackets, waitUs, delayUs);
    }

    public void recordStaSummary(Sta sta, double throughputMbps) {
        output.printf(Locale.ROOT,
                "summary=STA sta_id=%d sfu_id=%d received_packets=%d received_bytes=%d throughput_mbps=%.2f avg_e2e_delay_us=%.2f%n",
                sta.id(), sta.sfuId(), sta.receivedPackets(), sta.receivedBytes(), throughputMbps, sta.averageDelayUs());
    }

    public void recordSfuSummary(Sfu sfu, double throughputMbps) {
        output.printf(Locale.ROOT,
                "summary=SFU sfu_id=%d sent_packets=%d sent_bytes=%d throughput_mbps=%.2f avg_queue_wait_us=%.2f%n",
                sfu.id(), sfu.sentPackets(), sfu.sentBytes(), throughputMbps, sfu.averageQueueWaitUs());
    }

    public void recordUplinkStaSummary(Sta sta, int packets, long bytes, double throughputMbps, double waitUs,
                                       double delayUs) {
        output.printf(Locale.ROOT, "summary=UL_STA sta_id=%d sfu_id=%d delivered_packets=%d delivered_bytes=%d "
                        + "throughput_mbps=%.2f avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                sta.id(), sta.sfuId(), packets, bytes, throughputMbps, waitUs, delayUs);
    }

    public void recordUplinkGlobalSummary(int packets, long bytes, double delayUs) {
        output.printf(Locale.ROOT, "summary=UL_GLOBAL delivered_packets=%d delivered_bytes=%d avg_e2e_delay_us=%.2f%n",
                packets, bytes, delayUs);
    }

    public void recordSinglePacketSummary(int deliveredPackets, long deliveredBytes, long delayTicks) {
        output.printf(Locale.ROOT, "delivered_packets=%d delivered_bytes=%d e2e_delay_us=%s%n",
                deliveredPackets, deliveredBytes, formatMicroseconds(delayTicks));
    }

    public void recordRunSummary(int deliveredPackets, long deliveredBytes, long busyTicks, long endTicks) {
        output.printf(Locale.ROOT,
                "delivered_packets=%d delivered_bytes=%d wifi_busy_us=%s wifi_idle_us=%s simulation_end_us=%s%n",
                deliveredPackets, deliveredBytes, formatMicroseconds(busyTicks),
                formatMicroseconds(endTicks - busyTicks), formatMicroseconds(endTicks));
    }
}
