package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonDownlink;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.link.PonReportPoll;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MFU 按固定的 STA–SFU 关联发出下行报文，并接收上行报文。 */
public final class Mfu {
    private final List<Integer> staSfuIds;
    private final PonDownlink ponDownlink;
    private final Map<Integer, ReceivedUplinkReport> latestUplinkReports = new HashMap<>();
    private final PonDemandLedger demandLedger;
    private final PonDbaShadowPlanner shadowPlanner;
    private long receivedUplinkReports;

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
