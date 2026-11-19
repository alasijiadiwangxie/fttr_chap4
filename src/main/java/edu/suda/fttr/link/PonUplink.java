package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/** 各 SFU 的光发送端口共用一条上行链路。链路负责执行调度结果和交付报文。 */
public final class PonUplink {
    private final BigDecimal rateMbps;
    private final long propagationNs;
    private final PonUplinkScheduler scheduler = new PonUplinkScheduler();
    private long nextSequence;
    private Request active;
    private long startTicks;
    private long sentPackets;
    private double totalQueueWaitTicks;
    private long busyTicks;

    public PonUplink(BigDecimal rateMbps, long propagationNs) {
        this.rateMbps = rateMbps;
        this.propagationNs = propagationNs;
    }

    public Port attachSfu(int sfuId) {
        return new Port(sfuId);
    }

    /** 每台 SFU 持有自己的端口和光侧 FIFO，队首由共享调度器统一安排。 */
    public final class Port {
        private final int sfuId;
        private final Deque<Request> queue = new ArrayDeque<>();

        private Port(int sfuId) {
            this.sfuId = sfuId;
        }

        public void enqueue(Packet packet, SimulationContext context) {
            Request request = new Request(this, packet, context.nowTicks(), nextSequence++);
            queue.addLast(request);
            context.recordPonUplinkEnqueue(packet, sfuId);
            if (queue.size() == 1) {
                scheduler.submitHead(request);
                scheduleNext(context);
            }
        }
    }

    private void scheduleNext(SimulationContext context) {
        if (active != null) {
            return;
        }
        active = scheduler.pollHead();
        if (active != null) {
            // 先保留共享资源。其他 SFU 即使在同一刻度入队，也只能等待当前授权结束。
            context.scheduleAfter(0, EventType.FIBER_TX_START, active.packet, active.port.sfuId);
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
        context.scheduleAfterNanoseconds(propagationNs, EventType.PACKET_ARRIVAL_AT_MFU, packet, sfuId);

        Port port = active.port;
        port.queue.removeFirst();
        active = null;
        Request head = port.queue.peekFirst();
        if (head != null) {
            // 新队首沿用实际入队时间和流水号，而不是成为队首时重新排到其他请求之后。
            scheduler.submitHead(head);
        }
        scheduleNext(context);
    }

    public void recordSummary(SimulationContext context) {
        context.recordPonSummary(Packet.Direction.UL, sentPackets, totalQueueWaitTicks, busyTicks);
    }

    private void requireActive(Packet packet, int sfuId) {
        if (active == null || active.packet != packet || active.port.sfuId != sfuId) {
            throw new IllegalStateException("PON uplink event does not match the granted transmission");
        }
    }

    static final class Request {
        private final Port port;
        private final Packet packet;
        private final long enqueueTicks;
        private final long sequence;

        private Request(Port port, Packet packet, long enqueueTicks, long sequence) {
            this.port = port;
            this.packet = packet;
            this.enqueueTicks = enqueueTicks;
            this.sequence = sequence;
        }

        long enqueueTicks() {
            return enqueueTicks;
        }

        long sequence() {
            return sequence;
        }
    }
}
