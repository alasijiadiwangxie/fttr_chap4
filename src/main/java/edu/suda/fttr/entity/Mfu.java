package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonDownlink;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.link.PonReportPoll;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.DbaPolicy;
import edu.suda.fttr.dba.GiantDbaPolicy;
import edu.suda.fttr.link.PonGrantPacketFiller.FillReason;
import edu.suda.fttr.dba.RoundRobinDbaPolicy;
import edu.suda.fttr.dba.MultiGrantRoundRobinDbaPolicy;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.dba.TcontTopology;
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
    private final Map<Long, ReceivedUplinkReport> latestAllocationReports = new HashMap<>();
    private final PonDemandLedger demandLedger;
    private final PonDbaShadowPlanner shadowPlanner;
    private long receivedUplinkReports;
    private DbaPolicy executionPolicy = new RoundRobinDbaPolicy();
    private boolean multiGrantEnabled;
    private boolean giantCompleteEnabled;
    private boolean giantRecoveryEnabled;
    private TcontTopology tcontTopology;

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

    /** 仅在仿真启动时配置逻辑拓扑；关闭时继续使用原有单 FIFO / SFU 的全部路径。 */
    public void configureTcont(TcontTopology topology) {
        if (topology == null || !topology.enabled()) {
            return;
        }
        if (tcontTopology != null || receivedUplinkReports != 0 || demandLedger == null) {
            throw new IllegalStateException("Configure T-CONT once before receiving REPORTs, with demand ledger enabled");
        }
        tcontTopology = topology;
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
        if (tcontTopology != null) {
            context.recordTcontReportReceived(report, applied, "DATA");
        }
        recordAcceptedDemand(report, applied, context);
    }

    public void receivePolledReport(PonReportPoll poll, SimulationContext context) {
        PonUplinkReport report = poll.report();
        if ((tcontTopology == null && !staSfuIds.contains(poll.sfuId())) || report == null
                || report.sfuId() != poll.sfuId()
                || (tcontTopology != null && (report.allocationId() != poll.allocationId()
                    || allocationOwner(poll.allocationId()) != poll.sfuId()))) {
            throw new IllegalStateException("REPORT_ONLY reached MFU on the wrong route");
        }
        boolean applied = acceptUplinkReport(report, context.nowTicks());
        context.recordPollReception(poll, applied);
        if (tcontTopology != null) {
            context.recordTcontReportReceived(report, applied, "REPORT_ONLY");
        }
        recordAcceptedDemand(report, applied, context);
    }

    private boolean acceptUplinkReport(PonUplinkReport report, long receiveTimeNs) {
        if (receiveTimeNs < report.snapshotTimeNs()) {
            throw new IllegalStateException("PON report reached MFU before its snapshot time");
        }

        // 按真实采样时刻更新。轮询快照可能晚于同一帧的边界快照，旧采样不能覆盖新采样。
        if (allocationOwner(report.allocationId()) != report.sfuId()) {
            throw new IllegalArgumentException("REPORT allocation belongs to another SFU");
        }
        ReceivedUplinkReport latest = tcontTopology == null ? latestUplinkReport(report.sfuId())
                : latestAllocationReport(report.allocationId());
        boolean applied = latest == null || report.snapshotTimeNs() > latest.report().snapshotTimeNs()
                || (report.snapshotTimeNs() == latest.report().snapshotTimeNs()
                    && report.frameId() > latest.report().frameId());
        if (applied) {
            ReceivedUplinkReport received = new ReceivedUplinkReport(report, receiveTimeNs);
            if (tcontTopology == null) {
                latestUplinkReports.put(report.sfuId(), received);
            } else {
                latestAllocationReports.put(report.allocationId(), received);
            }
        }
        receivedUplinkReports++;
        return applied;
    }

    private void recordAcceptedDemand(PonUplinkReport report, boolean applied, SimulationContext context) {
        if (applied && demandLedger != null) {
            if (tcontTopology == null) {
                recordUplinkDemand(report.sfuId(), "REPORT_ACCEPTED", context);
            } else {
                recordAllocationDemand(report.allocationId(), "REPORT_ACCEPTED", context);
            }
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
        if (tcontTopology != null) {
            return estimateAllocationDemand(uniqueAllocationForSfu(sfuId), timeNs);
        }
        return demandLedger.estimate(sfuId, timeNs, latestUplinkReport(sfuId));
    }

    /** 对一个逻辑队列的已知需求估计；不会从物理 SFU 聚合或窥读本地 FIFO。 */
    public PonDemandLedger.Estimate estimateAllocationDemand(long allocationId, long timeNs) {
        if (demandLedger == null) {
            throw new IllegalStateException("PON demand ledger is disabled");
        }
        return demandLedger.estimateAllocation(allocationId, allocationOwner(allocationId), timeNs,
                latestAllocationReport(allocationId));
    }

    public void recordAllocationDemand(long allocationId, String trigger, SimulationContext context) {
        context.recordTcontDemandState(allocationId, allocationOwner(allocationId), trigger,
                estimateAllocationDemand(allocationId, context.nowTicks()));
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

    /** 启动配置时选择独立的多授权RR；重复配置不重置游标，原单授权RR不变。 */
    public void enableMultiGrantDba() {
        if (!multiGrantEnabled) {
            executionPolicy = new MultiGrantRoundRobinDbaPolicy();
            multiGrantEnabled = true;
        }
    }

    /** 每轮仿真有自己的 GIANT 服务计数器，授权仍走同一条多预算执行路径。 */
    public void enableGiantDba() {
        enableGiantDba(false);
    }

    public void enableGiantDba(boolean complete) {
        enableGiantDba(complete, false);
    }

    public void enableGiantDba(boolean complete, boolean recovery) {
        if (tcontTopology == null) {
            throw new IllegalStateException("GIANT DBA requires configured T-CONT profiles");
        }
        if (recovery && !complete) {
            throw new IllegalArgumentException("Pipelined recovery requires GIANT Complete");
        }
        if (!(executionPolicy instanceof GiantDbaPolicy)) {
            executionPolicy = new GiantDbaPolicy(complete);
            multiGrantEnabled = true;
            giantCompleteEnabled = complete;
            giantRecoveryEnabled = recovery;
        }
    }

    /** 真正的 MFU 决策入口，仅复制已接收 REPORT 和需求账本中的数值，不读取远端 FIFO。 */
    public DbaPlan planDbaExecution(long targetFrameId, long controlNs, BigDecimal rateMbps,
                                    long periodNs, SimulationContext simulation) {
        List<DbaContext.Allocation> allocations = new ArrayList<>();
        if (tcontTopology == null) {
            for (int sfuId : new TreeSet<>(staSfuIds)) {
                PonDemandLedger.Estimate estimate = estimateUplinkDemand(sfuId, simulation.nowTicks());
                allocations.add(new DbaContext.Allocation(2L * sfuId, sfuId,
                        estimate.known() ? estimate.effectiveKnownBytes() : null,
                        estimate.known() ? estimate.report().report().frameId() : null,
                        estimate.known() ? estimate.reportAgeNs() : null,
                        demandLedger.hasOutstandingBudget(sfuId, simulation.nowTicks())));
            }
        } else {
            // 复制每个逻辑对象的已知需求、结算状态和不可变带宽配置。
            for (TcontProfile profile : tcontTopology.profiles()) {
                PonDemandLedger.Estimate estimate = estimateAllocationDemand(profile.allocationId(),
                        simulation.nowTicks());
                allocations.add(new DbaContext.Allocation(profile.allocationId(), profile.sfuId(),
                        estimate.known() ? estimate.effectiveKnownBytes() : null,
                        estimate.known() ? estimate.report().report().frameId() : null,
                        estimate.known() ? estimate.reportAgeNs() : null,
                        demandLedger.hasOutstandingAllocationBudget(profile.allocationId(), simulation.nowTicks()),
                        profile, demandLedger.failedBudgetBytes(profile.allocationId()),
                        demandLedger.awaitingRecoverySettlement(profile.allocationId(), simulation.nowTicks())));
            }
        }
        DbaContext input = new DbaContext(simulation.nowTicks(), targetFrameId, rateMbps, periodNs,
                controlNs, allocations);
        // 保留完整、稳定的对象列表，由同一 RR 游标逐对象跳过未结算者，不能过滤后改变索引。
        // 每个对象的原始已知状态继续保留，不用零需求或 UNKNOWN 伪装授权阻塞。
        for (DbaContext.Allocation allocation : allocations) {
            simulation.recordDbaObjectState(input, allocation);
        }
        DbaPlan plan = executionPolicy.plan(input);
        List<DbaPlanValidator.Slot> slots = new DbaPlanValidator().validateAndPreview(input, plan, giantCompleteEnabled);
        if (executionPolicy instanceof GiantDbaPolicy) {
            for (GiantDbaPolicy.Decision decision : ((GiantDbaPolicy) executionPolicy).lastDecisions()) {
                simulation.recordGiantDecision(input, decision);
            }
        }
        if (multiGrantEnabled) {
            // 把统一校验器的只读窗口物化为执行偏移，不在链路层另写一套多窗口排放公式。
            long frameStart = Math.multiplyExact(targetFrameId, periodNs);
            List<DbaPlan.Grant> executable = new ArrayList<>();
            for (DbaPlanValidator.Slot slot : slots) {
                executable.add(new DbaPlan.Grant(slot.allocationId(), slot.sfuId(), slot.plannedBytes(),
                        slot.endNs() - slot.startNs(), slot.startNs() - frameStart, slot.fixedBytes()));
            }
            plan = new DbaPlan(plan.decisionTimeNs(), plan.targetFrameId(), executable, plan.decisionReason());
            simulation.recordDbaMultiPlan(plan, controlNs);
        } else {
            simulation.recordDbaDecision(plan, controlNs);
        }
        return plan;
    }

    public void recordDbaBudgetCreated(PonDbaGrant grant, SimulationContext context) {
        if (demandLedger == null || allocationOwner(grant.allocationId()) != grant.sfuId()
                || context.nowTicks() != grant.createNs()) {
            throw new IllegalArgumentException("DATA budget does not match MFU topology or creation time");
        }
        PonDemandLedger.Estimate before = estimateAllocationDemand(grant.allocationId(), context.nowTicks());
        if (grant.fixedBytes() > 0 && (!giantCompleteEnabled
                || grant.fixedBytes() > tcontTopology.profile(grant.allocationId()).abFixedBytes())) {
            throw new IllegalArgumentException("DATA budget exceeds configured fixed bandwidth quota");
        }
        if (!demandLedger.hasAllocationBudget(grant.allocationId(), grant.id())
                && grant.budgetBytes() > grant.fixedBytes()) {
            if (!before.known() || grant.budgetBytes() > before.effectiveKnownBytes()) {
                throw new IllegalArgumentException("DATA budget exceeds MFU known effective demand");
            }
            if (grant.budgetBytes() <= demandLedger.failedBudgetBytes(grant.allocationId())
                    || demandLedger.awaitingRecoverySettlement(grant.allocationId(), context.nowTicks())) {
                throw new IllegalArgumentException("DATA budget conflicts with allocation recovery");
            }
        }
        boolean created = giantCompleteEnabled
                ? demandLedger.recordPipelinedAllocationBudgetGrant(grant.allocationId(), grant.sfuId(), grant.id(),
                    grant.createNs(), grant.targetFrameId(), grant.startNs(), grant.endNs(), grant.budgetBytes(),
                    grant.fixedBytes())
                : demandLedger.recordAllocationBudgetGrant(grant.allocationId(), grant.sfuId(), grant.id(),
                    grant.createNs(), grant.startNs(), grant.endNs(), grant.budgetBytes());
        if (created) {
            PonDemandLedger.Estimate after = estimateAllocationDemand(grant.allocationId(), context.nowTicks());
            context.recordDbaExecution("PON_DBA_LEDGER_CREATED", grant, "ledger_debit_bytes=" + grant.budgetBytes()
                    + " effective_before=" + (before.known() ? before.effectiveKnownBytes() : -1)
                    + " effective_after=" + (after.known() ? after.effectiveKnownBytes() : -1));
            recordGrantDemand(grant, "DBA_BUDGET_CREATED", context);
        }
    }

    public void receiveDbaSettlement(PonDbaGrant grant, SimulationContext context) {
        if (demandLedger == null || !grant.finished() || allocationOwner(grant.allocationId()) != grant.sfuId()
                || grant.report() == null || grant.report().allocationId() != grant.allocationId()
                || grant.report().sfuId() != grant.sfuId()) {
            throw new IllegalStateException("Invalid DATA execution settlement at MFU");
        }
        PonDemandLedger.Estimate before = estimateAllocationDemand(grant.allocationId(), context.nowTicks());
        // MFU 只使用到达的结算结果和 REPORT。判断失败不读取 SFU 队列或装载器的逐包清单。
        boolean headBlocked = giantRecoveryEnabled && grant.actualBytes() == 0 && grant.report().pendingBytes() > 0
                && grant.result().fillReason() == FillReason.HEAD_BYTES_EXCEED_BUDGET;
        long allocationId = grant.allocationId();
        long oldFailure = demandLedger.failedBudgetBytes(allocationId);
        if (!demandLedger.settleAllocationBudgetGrant(grant.allocationId(), grant.sfuId(), grant.id(),
                context.nowTicks(), grant.actualBytes(), headBlocked)) {
            return; // 重复反馈不再收报告、不再累计字节或更新账本。
        }
        boolean applied = acceptUplinkReport(grant.report(), context.nowTicks());
        if (tcontTopology != null) {
            context.recordTcontReportReceived(grant.report(), applied, "SETTLEMENT");
        }
        PonDemandLedger.Estimate after = estimateAllocationDemand(grant.allocationId(), context.nowTicks());
        context.recordDbaExecution("PON_DBA_SETTLEMENT_RX", grant,
                "snapshot_time_ns=" + grant.report().snapshotTimeNs()
                        + " pending_bytes=" + grant.report().pendingBytes() + " report_applied=" + applied
                        + " effective_before=" + (before.known() ? before.effectiveKnownBytes() : -1)
                        + " effective_after=" + (after.known() ? after.effectiveKnownBytes() : -1)
                        + " released_unused_bytes=" + grant.unusedBytes()
                        + (giantRecoveryEnabled ? " head_blocked=" + headBlocked + " recovery_failed_before=" + oldFailure
                        + " recovery_failed_after=" + demandLedger.failedBudgetBytes(allocationId) : ""));
        recordGrantDemand(grant, "DBA_SETTLED", context);
    }

    private void recordGrantDemand(PonDbaGrant grant, String trigger, SimulationContext context) {
        if (tcontTopology == null) {
            recordUplinkDemand(grant.sfuId(), trigger, context);
        } else {
            recordAllocationDemand(grant.allocationId(), trigger, context);
        }
    }

    /** 显式检查逻辑对象归属，不把 allocationId 数值当作物理 SFU 编号。 */
    private int allocationOwner(long allocationId) {
        if (tcontTopology != null) {
            return tcontTopology.profile(allocationId).sfuId();
        }
        if (allocationId < 2 || allocationId % 2 != 0 || allocationId / 2 > Integer.MAX_VALUE
                || !staSfuIds.contains((int) (allocationId / 2))) {
            throw new IllegalArgumentException("Unknown default allocation ID: " + allocationId);
        }
        return (int) (allocationId / 2);
    }

    private long uniqueAllocationForSfu(int sfuId) {
        List<TcontProfile> profiles = tcontTopology.profilesForSfu(sfuId);
        if (profiles.size() != 1) {
            throw new IllegalStateException("SFU has multiple logical queues; query demand / REPORT by allocationId");
        }
        return profiles.get(0).allocationId();
    }

    public ReceivedUplinkReport latestAllocationReport(long allocationId) {
        int sfuId = allocationOwner(allocationId);
        return tcontTopology == null ? latestUplinkReports.get(sfuId) : latestAllocationReports.get(allocationId);
    }

    /** 返回该 SFU 最新的已接收快照。首份快照到达前返回 null，表示未知。 */
    public ReceivedUplinkReport latestUplinkReport(int sfuId) {
        if (tcontTopology != null) {
            return latestAllocationReport(uniqueAllocationForSfu(sfuId));
        }
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
