package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonDownlink;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.link.PonReportPoll;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.DbaPolicy;
import edu.suda.fttr.dba.RoundRobinDbaPolicy;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.TreeSet;
import java.math.BigDecimal;

/** MFU 按固定的 STA–SFU 关联发出下行报文，并接收上行报文。 */
public final class Mfu {
    private final List<Integer> staSfuIds;
    private final PonDownlink ponDownlink;
    private final Map<Integer, ReceivedUplinkReport> latestUplinkReports = new HashMap<>();
    private final PonDemandLedger demandLedger;
    private final PonDbaShadowPlanner shadowPlanner;
    private long receivedUplinkReports;
    private final DbaPolicy executionPolicy = new RoundRobinDbaPolicy();

    public Mfu(List<Integer> staSfuIds) {
        this(staSfuIds, null);
    }

    public Mfu(List<Integer> staSfuIds, PonDownlink ponDownlink) {
        this(staSfuIds, ponDownlink, false);
    }

    public Mfu(List<Integer> staSfuIds, PonDownlink ponDownlink, boolean demandLedgerEnabled) {
        this(staSfuIds, ponDownlink, demandLedgerEnabled, null);
    }

    public Mfu(List<Integer> staSfuIds, PonDownlink ponDownlink, boolean demandLedgerEnabled,
               PonDbaShadowPlanner shadowPlanner) {
        this.staSfuIds = List.copyOf(staSfuIds);
        this.ponDownlink = ponDownlink;
        this.demandLedger = demandLedgerEnabled ? new PonDemandLedger() : null;
        this.shadowPlanner = shadowPlanner;
    }

    public void receive(Packet packet, SimulationContext context) {
        int sfuId = staSfuIds.get(packet.destinationStaId() - 1);
        if (ponDownlink == null) {
            // 固定时延场景仍各自调度光链路起点，没有共享 FIFO。
            context.scheduleAfter(0, EventType.FIBER_TX_START, packet, sfuId);
        } else {
            ponDownlink.enqueue(packet, sfuId, context);
        }
    }

    public void receiveUplink(Packet packet, int sfuId, SimulationContext context) {
        if (packet.destinationNodeId() != Packet.DEFAULT_MFU_ID || staSfuIds.get(packet.staId() - 1) != sfuId) {
            throw new IllegalStateException("Uplink packet " + packet.id() + " reached MFU on the wrong route");
        }
        context.recordUplinkDelivery(packet, sfuId);
    }

    public void receiveUplinkReport(PonUplinkReport report, Packet carrier, SimulationContext context) {
        if (!carrier.isUplink() || carrier.destinationNodeId() != Packet.DEFAULT_MFU_ID
                || staSfuIds.get(carrier.staId() - 1) != report.sfuId()) {
            throw new IllegalStateException("PON report from SFU " + report.sfuId() + " reached MFU on the wrong route");
        }
        boolean applied = acceptUplinkReport(report, context.nowTicks());
        context.recordPonUplinkReportReception(report, carrier.id(), applied);
        recordAcceptedDemand(report, applied, context);
    }

    public void receivePolledReport(PonReportPoll poll, SimulationContext context) {
        PonUplinkReport report = poll.report();
        if (!staSfuIds.contains(poll.sfuId()) || report == null || report.sfuId() != poll.sfuId()) {
            throw new IllegalStateException("REPORT_ONLY reached MFU on the wrong route");
        }
        boolean applied = acceptUplinkReport(report, context.nowTicks());
        context.recordPollReception(poll, applied);
        recordAcceptedDemand(report, applied, context);
    }

    private boolean acceptUplinkReport(PonUplinkReport report, long receiveTimeNs) {
        if (receiveTimeNs < report.snapshotTimeNs()) {
            throw new IllegalStateException("PON report reached MFU before its snapshot time");
        }

        // 按真实采样时刻更新。轮询快照可能晚于同一帧的边界快照，旧采样不能覆盖新采样。
        ReceivedUplinkReport latest = latestUplinkReport(report.sfuId());
        boolean applied = latest == null || report.snapshotTimeNs() > latest.report().snapshotTimeNs()
                || (report.snapshotTimeNs() == latest.report().snapshotTimeNs()
                    && report.frameId() > latest.report().frameId());
        if (applied) {
            latestUplinkReports.put(report.sfuId(), new ReceivedUplinkReport(report, receiveTimeNs));
        }
        receivedUplinkReports++;
        return applied;
    }

    private void recordAcceptedDemand(PonUplinkReport report, boolean applied, SimulationContext context) {
        if (applied && demandLedger != null) {
            recordUplinkDemand(report.sfuId(), "REPORT_ACCEPTED", context);
        }
    }

    /** 一个完整报文只有一条预约。用其唯一 ID 识别授权，遵循预约创建时刻扣减。 */
    public void recordUplinkGrantCreated(int sfuId, Packet packet, SimulationContext context) {
        if (demandLedger != null
                && demandLedger.recordGrant(sfuId, packet.id(), context.nowTicks(), packet.bytes())) {
            context.recordDemandLedgerGrant(sfuId, packet.id(), packet.bytes());
            recordUplinkDemand(sfuId, "GRANT_CREATED", context);
        }
    }

    public PonDemandLedger.Estimate estimateUplinkDemand(int sfuId, long timeNs) {
        if (demandLedger == null) {
            throw new IllegalStateException("PON demand ledger is disabled");
        }
        return demandLedger.estimate(sfuId, timeNs, latestUplinkReport(sfuId));
    }

    public void recordUplinkDemand(int sfuId, String trigger, SimulationContext context) {
        context.recordDemandLedgerState(sfuId, trigger, estimateUplinkDemand(sfuId, context.nowTicks()));
    }

    public void planUplinkBudget(long targetFrameId, long controlReservedNs, SimulationContext context) {
        if (shadowPlanner != null) {
            PonDbaShadowPlanner.Decision decision = shadowPlanner.plan(context.nowTicks(), targetFrameId,
                    controlReservedNs, sfuId -> estimateUplinkDemand(sfuId, context.nowTicks()));
            context.recordDbaShadow(decision);
        }
    }

    /** 真正的 MFU 决策入口，仅复制已接收 REPORT 和需求账本中的数值，不读取远端 FIFO。 */
    public DbaPlan planDbaExecution(long targetFrameId, long controlNs, BigDecimal rateMbps,
                                    long periodNs, SimulationContext simulation) {
        List<DbaContext.Allocation> allocations = new ArrayList<>();
        boolean outstanding = false;
        for (int sfuId : new TreeSet<>(staSfuIds)) {
            PonDemandLedger.Estimate estimate = estimateUplinkDemand(sfuId, simulation.nowTicks());
            allocations.add(new DbaContext.Allocation(2L * sfuId, sfuId,
                    estimate.known() ? estimate.effectiveKnownBytes() : null,
                    estimate.known() ? estimate.report().report().frameId() : null,
                    estimate.known() ? estimate.reportAgeNs() : null));
            outstanding |= demandLedger.hasOutstandingBudget(sfuId, simulation.nowTicks());
        }
        DbaContext input = new DbaContext(simulation.nowTicks(), targetFrameId, rateMbps, periodNs,
                controlNs, allocations);
        // 第12步仅单SFU。未结算预算尚占用已知需求，不创建下一预算，也不清空原报告。
        DbaPlan plan = outstanding ? new DbaPlan(simulation.nowTicks(), targetFrameId, List.of(),
                "AWAITING_BUDGET_SETTLEMENT") : executionPolicy.plan(input);
        new DbaPlanValidator().validateAndPreview(input, plan);
        simulation.recordDbaDecision(plan, controlNs);
        return plan;
    }

    public void recordDbaBudgetCreated(PonDbaGrant grant, SimulationContext context) {
        if (demandLedger == null || !staSfuIds.contains(grant.sfuId())
                || grant.allocationId() != 2L * grant.sfuId() || context.nowTicks() != grant.createNs()) {
            throw new IllegalArgumentException("DATA budget does not match MFU topology or creation time");
        }
        PonDemandLedger.Estimate before = estimateUplinkDemand(grant.sfuId(), context.nowTicks());
        if (!demandLedger.hasBudgetGrant(grant.sfuId(), grant.id())
                && (!before.known() || grant.budgetBytes() > before.effectiveKnownBytes())) {
            throw new IllegalArgumentException("DATA budget exceeds MFU known effective demand");
        }
        if (demandLedger.recordBudgetGrant(grant.sfuId(), grant.id(), grant.createNs(), grant.startNs(),
                grant.endNs(), grant.budgetBytes())) {
            context.recordDbaExecution("PON_DBA_LEDGER_CREATED", grant, "ledger_debit_bytes=" + grant.budgetBytes()
                    + " effective_before=" + before.effectiveKnownBytes() + " effective_after="
                    + estimateUplinkDemand(grant.sfuId(), context.nowTicks()).effectiveKnownBytes());
            recordUplinkDemand(grant.sfuId(), "DBA_BUDGET_CREATED", context);
        }
    }

    public void receiveDbaSettlement(PonDbaGrant grant, SimulationContext context) {
        if (demandLedger == null || !grant.finished() || !staSfuIds.contains(grant.sfuId())) {
            throw new IllegalStateException("Invalid DATA execution settlement at MFU");
        }
        PonDemandLedger.Estimate before = estimateUplinkDemand(grant.sfuId(), context.nowTicks());
        if (!demandLedger.settleBudgetGrant(grant.sfuId(), grant.id(), context.nowTicks(), grant.actualBytes())) {
            return; // 重复反馈不再收报告、不再累计字节或更新账本。
        }
        boolean applied = acceptUplinkReport(grant.report(), context.nowTicks());
        PonDemandLedger.Estimate after = estimateUplinkDemand(grant.sfuId(), context.nowTicks());
        context.recordDbaExecution("PON_DBA_SETTLEMENT_RX", grant,
                "snapshot_time_ns=" + grant.report().snapshotTimeNs()
                        + " pending_bytes=" + grant.report().pendingBytes() + " report_applied=" + applied
                        + " effective_before=" + (before.known() ? before.effectiveKnownBytes() : -1)
                        + " effective_after=" + (after.known() ? after.effectiveKnownBytes() : -1)
                        + " released_unused_bytes=" + grant.unusedBytes());
        recordUplinkDemand(grant.sfuId(), "DBA_SETTLED", context);
    }

    /** 返回该 SFU 最新的已接收快照。首份快照到达前返回 null，表示未知。 */
    public ReceivedUplinkReport latestUplinkReport(int sfuId) {
        return latestUplinkReports.get(sfuId);
    }

    public long receivedUplinkReports() {
        return receivedUplinkReports;
    }

    /** 已接收状态包含原始快照和实际接收时间，与 SFU 队列独立。 */
    public static final class ReceivedUplinkReport {
        private final PonUplinkReport report;
        private final long receiveTimeNs;

        private ReceivedUplinkReport(PonUplinkReport report, long receiveTimeNs) {
            this.report = report;
            this.receiveTimeNs = receiveTimeNs;
        }

        public PonUplinkReport report() {
            return report;
        }

        public long receiveTimeNs() {
            return receiveTimeNs;
        }
    }
}
