package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/** 各 SFU 的光发送端口共用一条上行链路。链路负责执行调度结果和交付报文。 */
public final class PonUplink {
    private final BigDecimal rateMbps;
    private final long propagationNs;
    private final long framePeriodNs;
    private final boolean reportEnabled;
    private final List<Port> reportPorts = new ArrayList<>();
    private final PonUplinkScheduler scheduler = new PonUplinkScheduler();
    private final Deque<Grant> grants = new ArrayDeque<>();
    private long nextSequence;
    private boolean framePending;
    private long sentPackets;
    private double totalQueueWaitTicks;
    private long busyTicks;
    private long frames;
    private double totalGrantWaitTicks;

    public PonUplink(BigDecimal rateMbps, long propagationNs) {
        this(rateMbps, propagationNs, 0);
    }

    public PonUplink(BigDecimal rateMbps, long propagationNs, long framePeriodNs) {
        this(rateMbps, propagationNs, framePeriodNs, false);
    }

    public PonUplink(BigDecimal rateMbps, long propagationNs, long framePeriodNs, boolean reportEnabled) {
        this.rateMbps = rateMbps;
        this.propagationNs = propagationNs;
        this.framePeriodNs = framePeriodNs;
        this.reportEnabled = reportEnabled;
    }

    public Port attachSfu(int sfuId) {
        Port port = new Port(sfuId);
        if (reportEnabled) {
            reportPorts.add(port);
        }
        return port;
    }

    /** 每台 SFU 持有自己的端口和光侧 FIFO，队首由共享调度器统一安排。 */
    public final class Port {
        private final int sfuId;
        private final Deque<Request> queue = new ArrayDeque<>();
        private long pendingBytes;

        private Port(int sfuId) {
            this.sfuId = sfuId;
        }

        public void enqueue(Packet packet, SimulationContext context) {
            Request request = new Request(this, packet, context.nowTicks(), nextSequence++);
            queue.addLast(request);
            pendingBytes += packet.bytes();
            context.recordPonUplinkEnqueue(packet, sfuId);
            if (queue.size() == 1) {
                scheduler.submitHead(request);
                if (framePeriodNs == 0) {
                    scheduleShared(context);
                } else {
                    requestFrame(context);
                }
            }
        }

        private PonUplinkReport snapshot(long frameId, long frameStartNs) {
            int pendingPackets = queue.size();
            int eligiblePackets = pendingPackets;
            long eligibleBytes = pendingBytes;
            // FIFO 按入队时间增长。只需从队尾剔除已入队、但恰逢本帧边界的报文。
            Iterator<Request> newestFirst = queue.descendingIterator();
            while (newestFirst.hasNext()) {
                Request request = newestFirst.next();
                if (request.enqueueTicks < frameStartNs) {
                    break;
                }
                eligiblePackets--;
                eligibleBytes -= request.packet.bytes();
            }
            return new PonUplinkReport(frameId, sfuId, frameStartNs, pendingPackets, pendingBytes,
                    eligiblePackets, eligibleBytes);
        }
    }

    private void scheduleShared(SimulationContext context) {
        Request head = scheduler.peekHead();
        if (!grants.isEmpty() || head == null) {
            return;
        }
        reserveHead(context.nowTicks(), serializationNs(head));
        scheduleGrantedHead(context);
    }

    private void requestFrame(SimulationContext context) {
        // 只为积压业务唤醒调度。入队正好落在边界时，下一次可用边界仍在一整帧之后。
        if (!framePending) {
            long delayNs = framePeriodNs - context.nowTicks() % framePeriodNs;
            context.scheduleAfterNanoseconds(delayNs, EventType.PON_UPLINK_FRAME, null, 0);
            framePending = true;
        }
    }

    public void scheduleFrame(SimulationContext context) {
        if (!grants.isEmpty()) {
            throw new IllegalStateException("Previous PON uplink frame still has active grants");
        }
        framePending = false;
        long frameStart = context.nowTicks();
        long frameId = frameStart / framePeriodNs;
        long frameEnd = Math.addExact(frameStart, framePeriodNs);
        if (reportEnabled) {
            recordReports(frameId, frameStart, context);
        }
        long cursor = frameStart;
        Request head;
        while ((head = scheduler.peekHead()) != null && head.enqueueTicks < frameStart) {
            long durationNs = serializationNs(head);
            if (durationNs > framePeriodNs) {
                throw new IllegalArgumentException("PON uplink packet " + head.packet.id() + " exceeds a full frame");
            }
            if (durationNs > frameEnd - cursor) {
                break; // 严格 FCFS。帧尾容量留空，队首报文保留到下一帧。
            }
            Grant grant = reserveHead(cursor, durationNs);
            totalGrantWaitTicks += frameStart - head.enqueueTicks;
            context.recordPonUplinkGrant(head.packet, head.port.sfuId, frameId, head.enqueueTicks, cursor,
                    grant.endTicks);
            cursor = grant.endTicks;
        }

        // 只有前一帧的结束事件处理完，才轮到本帧调度。此处的授权均属于本帧。
        recordFrame(frameId, frameStart, frameEnd, grants.size(), cursor - frameStart, context);
        scheduleGrantedHead(context);
        if (scheduler.peekHead() != null) {
            requestFrame(context);
        }
    }

    private void recordReports(long frameId, long frameStart, SimulationContext context) {
        // 空帧由现有的按积压唤醒机制跳过。补记其中各 SFU 的零需求，不额外安排事件。
        for (long id = frames; id < frameId; id++) {
            for (Port port : reportPorts) {
                context.recordPonUplinkReport(new PonUplinkReport(id, port.sfuId, id * framePeriodNs, 0, 0, 0, 0));
            }
        }
        // 在任何授权消费队首之前读取全部 SFU 的快照。FCFS 仍只从原有候选队首取报文。
        for (Port port : reportPorts) {
            context.recordPonUplinkReport(port.snapshot(frameId, frameStart));
        }
    }

    private void recordFrame(long frameId, long frameStart, long frameEnd, int packets, long usedNs,
                             SimulationContext context) {
        // 按帧号补记未唤醒的空帧，无需逐个空帧推进仿真时钟。
        for (long id = frames; id < frameId; id++) {
            long start = id * framePeriodNs;
            context.recordPonUplinkFrame(id, start, start + framePeriodNs, 0, 0);
        }
        context.recordPonUplinkFrame(frameId, frameStart, frameEnd, packets, usedNs);
        frames = frameId + 1;
    }

    private long serializationNs(Request request) {
        return PonLinkTiming.serializationNs(request.packet.bytes(), rateMbps);
    }

    private Grant reserveHead(long startTicks, long durationNs) {
        Request request = scheduler.pollHead();
        Grant grant = new Grant(request, startTicks, Math.addExact(startTicks, durationNs));
        grants.addLast(grant);
        Port port = request.port;
        port.queue.removeFirst();
        port.pendingBytes -= request.packet.bytes();
        Request head = port.queue.peekFirst();
        if (head != null) {
            // 连续授权同一 SFU 时仍将下一队首放回全局 FCFS，沿用原始入队时间和流水号。
            scheduler.submitHead(head);
        }
        return grant;
    }

    private void scheduleGrantedHead(SimulationContext context) {
        Grant grant = grants.peekFirst();
        if (grant != null) {
            context.scheduleAfterTicks(grant.startTicks - context.nowTicks(), EventType.FIBER_TX_START,
                    grant.request.packet, grant.request.port.sfuId);
        }
    }

    public void start(Packet packet, int sfuId, SimulationContext context) {
        Grant grant = requireGrant(packet, sfuId);
        if (context.nowTicks() != grant.startTicks) {
            throw new IllegalStateException("PON uplink transmission started outside the granted slot");
        }
        context.scheduleAfterTicks(grant.endTicks - grant.startTicks,
                EventType.FIBER_TX_END, packet, sfuId);
    }

    public void finish(Packet packet, int sfuId, SimulationContext context) {
        Grant grant = requireGrant(packet, sfuId);
        if (context.nowTicks() != grant.endTicks) {
            throw new IllegalStateException("PON uplink transmission finished outside the granted slot");
        }
        grants.removeFirst();
        sentPackets++;
        totalQueueWaitTicks += grant.startTicks - grant.request.enqueueTicks;
        busyTicks += grant.endTicks - grant.startTicks;
        context.scheduleAfterNanoseconds(propagationNs, EventType.PACKET_ARRIVAL_AT_MFU, packet, sfuId);

        if (framePeriodNs == 0) {
            scheduleShared(context);
        } else {
            // 只启动已经获授权的报文。当前帧中途收到的新报文仍等待帧边界。
            scheduleGrantedHead(context);
        }
    }

    public void recordSummary(SimulationContext context) {
        context.recordPonSummary(Packet.Direction.UL, sentPackets, totalQueueWaitTicks, busyTicks);
        if (framePeriodNs != 0) {
            double grantWaitUs = sentPackets == 0 ? 0.0 : totalGrantWaitTicks / sentPackets / 1_000.0;
            context.recordPonUplinkFrames(frames, framePeriodNs, sentPackets, busyTicks, grantWaitUs);
        }
    }

    private Grant requireGrant(Packet packet, int sfuId) {
        Grant grant = grants.peekFirst();
        if (grant == null || grant.request.packet != packet || grant.request.port.sfuId != sfuId) {
            throw new IllegalStateException("PON uplink event does not match the granted transmission");
        }
        return grant;
    }

    private static final class Grant {
        private final Request request;
        private final long startTicks;
        private final long endTicks;

        private Grant(Request request, long startTicks, long endTicks) {
            this.request = request;
            this.startTicks = startTicks;
            this.endTicks = endTicks;
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
