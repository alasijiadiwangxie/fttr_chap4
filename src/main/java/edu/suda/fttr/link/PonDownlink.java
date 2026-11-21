package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Queue;

/** MFU 的共享下行发送器。队首按目的 SFU 投递，多个 SFU 共用同一个 FIFO。 */
public final class PonDownlink {
    private final BigDecimal rateMbps;
    private final long propagationNs;
    private final Queue<QueuedPacket> waiting = new ArrayDeque<>();
    private QueuedPacket active;
    private long startTicks;
    private long sentPackets;
    private double totalQueueWaitTicks;
    private long busyTicks;

    public PonDownlink(BigDecimal rateMbps, long propagationNs) {
        this.rateMbps = rateMbps;
        this.propagationNs = propagationNs;
    }

    public void enqueue(Packet packet, int sfuId, SimulationContext context) {
        waiting.add(new QueuedPacket(packet, sfuId, context.nowTicks()));
        context.recordPonDownlinkEnqueue(packet, sfuId);
        if (active == null) {
            scheduleNext(context);
        }
    }

    private void scheduleNext(SimulationContext context) {
        active = waiting.poll();
        if (active != null) {
            // 先占用发送器，再排入同刻的开始事件。同刻后续入队的报文只会排到队尾。
            context.scheduleAfter(0, EventType.FIBER_TX_START, active.packet, active.sfuId);
        }
    }

    public void start(Packet packet, int sfuId, SimulationContext context) {
        requireActive(packet, sfuId);
        startTicks = context.nowTicks();
        context.scheduleAfterNanoseconds(PonLinkTiming.serializationNs(packet.bytes(), rateMbps),
                EventType.FIBER_TX_END, packet, sfuId);
    }

    public void finish(Packet packet, int sfuId, SimulationContext context) {
        requireActive(packet, sfuId);
        sentPackets++;
        totalQueueWaitTicks += startTicks - active.enqueueTicks;
        busyTicks += context.nowTicks() - startTicks;

        // 串行化结束后，传播和下一次发送可以同时进行。只向路由选中的 SFU 安排一次到达。
        context.scheduleAfterNanoseconds(propagationNs, EventType.PACKET_ARRIVAL_AT_SFU, packet, sfuId);
        scheduleNext(context);
    }

    public void recordSummary(SimulationContext context) {
        context.recordPonSummary(Packet.Direction.DL, sentPackets, totalQueueWaitTicks, busyTicks);
    }

    private void requireActive(Packet packet, int sfuId) {
        if (active == null || active.packet != packet || active.sfuId != sfuId) {
            throw new IllegalStateException("PON downlink event does not match the active transmission");
        }
    }

    private static final class QueuedPacket {
        private final Packet packet;
        private final int sfuId;
        private final long enqueueTicks;

        private QueuedPacket(Packet packet, int sfuId, long enqueueTicks) {
            this.packet = packet;
            this.sfuId = sfuId;
            this.enqueueTicks = enqueueTicks;
        }
    }
}
