package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonDownlink;
import edu.suda.fttr.link.PonUplinkReport;
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
    private long receivedUplinkReports;

    public Mfu(List<Integer> staSfuIds) {
        this(staSfuIds, null);
    }

    public Mfu(List<Integer> staSfuIds, PonDownlink ponDownlink) {
        this.staSfuIds = List.copyOf(staSfuIds);
        this.ponDownlink = ponDownlink;
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
        long receiveTimeNs = context.nowTicks();
        if (receiveTimeNs < report.snapshotTimeNs()) {
            throw new IllegalStateException("PON report reached MFU before its snapshot time");
        }

        // 队列需求只在接收时更新。原始帧号保证晚到或重复的报告不能替换新快照。
        ReceivedUplinkReport latest = latestUplinkReport(report.sfuId());
        boolean applied = latest == null || report.frameId() > latest.report().frameId();
        if (applied) {
            latestUplinkReports.put(report.sfuId(), new ReceivedUplinkReport(report, receiveTimeNs));
        }
        receivedUplinkReports++;
        context.recordPonUplinkReportReception(report, carrier.id(), applied);
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
