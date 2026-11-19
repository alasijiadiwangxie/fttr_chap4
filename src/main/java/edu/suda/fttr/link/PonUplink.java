package edu.suda.fttr.link;

import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 各 SFU 的光发送端口共用一条上行链路。链路负责执行调度结果和交付报文。 */
public final class PonUplink {
    private final BigDecimal rateMbps;
    private final long propagationNs;
    private final long framePeriodNs;
    private final boolean reportEnabled;
    private final long bwmapPropagationNs;
    private final SimulationConfig.PonPolling polling;
    private final List<Port> pollingPorts = new ArrayList<>();
    private final Deque<PonReportPoll> polls = new ArrayDeque<>();
    private long nextPollFrame = 1;
    private long createdPolls;
    private long completedPolls;
    private long pollBusyNs;
    private final List<Port> reportPorts = new ArrayList<>();
    private final PonUplinkScheduler scheduler = new PonUplinkScheduler();
    private final Deque<Grant> grants = new ArrayDeque<>();
    private long nextSequence;
    private boolean framePending;
    private long sentPackets;
    private double totalQueueWaitTicks;
    private long busyTicks;
    private long frames;
    private long reportedFrames;
    private double totalGrantWaitTicks;
    private double totalReservedWaitTicks;
    private long generatedReports;
    private long reportsWithoutGrant;

    public PonUplink(BigDecimal rateMbps, long propagationNs) {
        this(rateMbps, propagationNs, 0);
    }

    public PonUplink(BigDecimal rateMbps, long propagationNs, long framePeriodNs) {
        this(rateMbps, propagationNs, framePeriodNs, false);
    }

    public PonUplink(BigDecimal rateMbps, long propagationNs, long framePeriodNs, boolean reportEnabled) {
        this(rateMbps, propagationNs, framePeriodNs, reportEnabled, -1);
    }

    public PonUplink(BigDecimal rateMbps, long propagationNs, long framePeriodNs, boolean reportEnabled,
                     long bwmapPropagationNs) {
        this(rateMbps, propagationNs, framePeriodNs, reportEnabled, bwmapPropagationNs, null);
    }

    public PonUplink(BigDecimal rateMbps, long propagationNs, long framePeriodNs, boolean reportEnabled,
                     long bwmapPropagationNs, SimulationConfig.PonPolling polling) {
        this.rateMbps = rateMbps;
        this.propagationNs = propagationNs;
        this.framePeriodNs = framePeriodNs;
        this.reportEnabled = reportEnabled;
        this.bwmapPropagationNs = bwmapPropagationNs;
        this.polling = polling;
    }

    public Port attachSfu(int sfuId) {
        Port port = new Port(sfuId);
        if (reportEnabled) {
            reportPorts.add(port);
        }
        return port;
    }

    /** 只根据固定关联和 SFU ID 建立轮询顺序，与队列是否有业务无关。 */
    public void startPolling(List<Integer> associatedSfuIds, SimulationContext context) {
        if (polling != null) {
            reportPorts.stream().filter(port -> associatedSfuIds.contains(port.sfuId))
                    .sorted((left, right) -> Integer.compare(left.sfuId, right.sfuId)).forEach(pollingPorts::add);
            if (!pollingPorts.isEmpty()) {
                requestFrame(context);
            }
        }
    }

    /** 每台 SFU 持有自己的端口和光侧 FIFO，队首由共享调度器统一安排。 */
    public final class Port {
        private final int sfuId;
        private final Deque<Request> queue = new ArrayDeque<>();
        private final Map<Packet, Grant> awaitingBwmap = new HashMap<>();
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

        /**
         * 复制本 SFU 此刻尚未授权的光侧 FIFO，供本地预算预演使用。
         * reserveHead 已将真实预约报文移出 queue，不能把 awaitingBwmap/grants 再并入此视图。
         * 同刻先后由现有事件顺序和本方法的同步调用位置决定，不增加新的装载事件。
         */
        public PonGrantPacketFiller.QueueView ungrantedPacketView(SimulationContext context) {
            if (context.microsecondsToTicks(1) != 1_000) {
                throw new IllegalStateException("PON grant preview requires a nanosecond clock");
            }
            List<PonGrantPacketFiller.PacketView> packets = new ArrayList<>(queue.size());
            for (Request request : queue) {
                // Packet 本身不外传，视图仅保留不可变数值，也不暴露队列或出队入口。
                packets.add(new PonGrantPacketFiller.PacketView(request.packet.id(), request.packet.bytes(),
                        request.enqueueTicks));
            }
            return new PonGrantPacketFiller.QueueView(sfuId, context.nowTicks(), packets);
        }

        /**
         * 每次都从当前 FIFO 创建新快照。结果只是本地预演，不创建 DATA 预约或更新 MFU 账本。
         * 旧 QueueView 可用于重放历史预演；核对当前可用报文时必须通过此入口重新取样。
         */
        public PonGrantPacketFiller.Result previewGrant(PonGrantPacketFiller filler, long allocationId,
                                                       long grantBytes, long grantDurationNs, long slotStartNs,
                                                       SimulationContext context) {
            if (rateMbps.compareTo(filler.rateMbps()) != 0) {
                throw new IllegalArgumentException("Grant filler rate does not match the local PON uplink");
            }
            return filler.fill(allocationId, sfuId, grantBytes, grantDurationNs, slotStartNs,
                    ungrantedPacketView(context));
        }

        public void receiveBwmap(Packet packet, SimulationContext context) {
            Grant grant = awaitingBwmap.get(packet);
            if (grant == null) {
                throw new IllegalStateException("Unexpected or duplicate BWmap for packet " + packet.id()
                        + " at SFU " + sfuId);
            }
            if (context.nowTicks() >= grant.startTicks) {
                throw new IllegalStateException("BWmap for packet " + packet.id()
                        + " must reach SFU " + sfuId + " before its slot starts at " + grant.startTicks + " ns");
            }
            awaitingBwmap.remove(packet);
            grant.bwmapReceived = true;
            recordBwmap(grant, true, context);
        }

        public void receivePollBwmap(PonReportPoll poll, SimulationContext context) {
            if (poll.sfuId() != sfuId || !polls.contains(poll)) {
                throw new IllegalStateException("REPORT_ONLY BWmap reached the wrong SFU or unknown reservation");
            }
            poll.receive(context.nowTicks());
            context.recordPollEvent(poll, "PON_POLL_BWMAP_RX");
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
        reserveHead(context.nowTicks(), serializationNs(head), null, context.nowTicks());
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
        boolean bwmapEnabled = bwmapPropagationNs >= 0;
        boolean hasScheduledGrant = !grants.isEmpty() || !polls.isEmpty();
        if (!bwmapEnabled && hasScheduledGrant) {
            throw new IllegalStateException("Previous PON uplink frame still has active grants");
        }
        framePending = false;
        long frameStart = context.nowTicks();
        long frameId = frameStart / framePeriodNs;
        Map<Integer, PonUplinkReport> frameReports = reportEnabled ? recordReports(frameId, frameStart, context) : null;
        Request head = scheduler.peekHead();
        boolean pollDue = polling != null && !pollingPorts.isEmpty() && frameId == nextPollFrame
                && frameStart < polling.endNs();
        if (bwmapEnabled && !pollDue && (head == null || head.enqueueTicks >= frameStart)) {
            context.planDbaShadow(frameId + 1, 0);
            if (frameReports != null) {
                reportsWithoutGrant += frameReports.size();
            }
            // 末尾仅取样的帧没有目标时隙，也不需要让时钟能表示一个更远的空帧。
            if (head != null || hasFuturePoll(frameStart)) {
                requestFrame(context);
            }
            return;
        }
        long targetStart = bwmapEnabled ? Math.addExact(frameStart, framePeriodNs) : frameStart;
        long targetEnd = Math.addExact(targetStart, framePeriodNs);
        long targetFrameId = targetStart / framePeriodNs;
        if ((!grants.isEmpty() && grants.getLast().endTicks > targetStart)
                || (!polls.isEmpty() && polls.getLast().endNs() > targetStart)) {
            throw new IllegalStateException("PON uplink reservation overlaps target frame " + targetFrameId);
        }
        long cursor = targetStart;
        long controlNs = 0;
        if (pollDue) {
            controlNs = PonLinkTiming.serializationNs(polling.bytes(), rateMbps);
            Port port = pollingPorts.get((int) (createdPolls % pollingPorts.size()));
            PonReportPoll poll = new PonReportPoll(++createdPolls, port.sfuId, frameId, frameStart,
                    cursor, Math.addExact(cursor, controlNs));
            polls.addLast(poll);
            context.recordPollEvent(poll, "PON_POLL_BWMAP_TX");
            context.schedulePollEvent(bwmapPropagationNs, EventType.PON_POLL_BWMAP_RX, poll);
            cursor = poll.endNs();
            long lastEligibleFrame = (polling.endNs() - 1) / framePeriodNs;
            nextPollFrame = lastEligibleFrame - frameId >= polling.intervalFrames()
                    ? frameId + polling.intervalFrames() : 0;
        }
        // 此刻控制预约已确定，真实 DATA 预约尚未创建。影子结果仅记录，后续 FCFS 继续原流程。
        context.planDbaShadow(targetFrameId, controlNs);
        int allocated = 0;
        while ((head = scheduler.peekHead()) != null && head.enqueueTicks < frameStart) {
            long durationNs = serializationNs(head);
            if (durationNs > framePeriodNs) {
                throw new IllegalArgumentException("PON uplink packet " + head.packet.id() + " exceeds a full frame");
            }
            if (durationNs > targetEnd - cursor) {
                break; // 严格 FCFS。帧尾容量留空，队首报文保留到下一帧。
            }
            // 移出这一台 SFU 的快照，确保仅其第一条上行授权携带 REPORT。
            PonUplinkReport report = frameReports == null ? null : frameReports.remove(head.port.sfuId);
            Grant grant = reserveHead(cursor, durationNs, report, frameStart);
            totalGrantWaitTicks += frameStart - head.enqueueTicks;
            context.recordPonUplinkGrant(head.packet, head.port.sfuId, frameId, head.enqueueTicks, cursor,
                    grant.endTicks);
            if (bwmapEnabled) {
                // 预约已离开待授权 FIFO。控制记录走下行传播，所占的 DATA 时隙仍在目标上行帧。
                head.port.awaitingBwmap.put(head.packet, grant);
                recordBwmap(grant, false, context);
                context.scheduleAfterNanoseconds(bwmapPropagationNs, EventType.PON_BWMAP_RX, head.packet,
                        head.port.sfuId);
            }
            allocated++;
            cursor = grant.endTicks;
        }
        if (frameReports != null) {
            reportsWithoutGrant += frameReports.size();
        }

        if (!bwmapEnabled || allocated > 0 || pollDue) {
            recordFrame(targetFrameId, targetStart, targetEnd, allocated, cursor - targetStart, controlNs, context);
        }
        // 可能正在执行上一轮预约。后续时隙仍由前一包结束事件启动。
        if (!hasScheduledGrant) {
            scheduleGrantedHead(context);
        }
        if (scheduler.peekHead() != null || hasFuturePoll(frameStart)
                || (bwmapEnabled && reportEnabled && (allocated > 0 || pollDue))) {
            requestFrame(context);
        }
    }

    private boolean hasFuturePoll(long frameStart) {
        return polling != null && !pollingPorts.isEmpty()
                && nextPollFrame <= (polling.endNs() - 1) / framePeriodNs
                && nextPollFrame > frameStart / framePeriodNs;
    }

    private Map<Integer, PonUplinkReport> recordReports(long frameId, long frameStart, SimulationContext context) {
        // 空帧由现有的按积压唤醒机制跳过。补记其中各 SFU 的零需求，不额外安排事件。
        for (long id = reportedFrames; id < frameId; id++) {
            for (Port port : reportPorts) {
                context.recordPonUplinkReport(new PonUplinkReport(id, port.sfuId, id * framePeriodNs, 0, 0, 0, 0));
                generatedReports++;
                reportsWithoutGrant++;
            }
        }
        // 在任何授权消费队首之前读取全部 SFU 的快照。FCFS 仍只从原有候选队首取报文。
        Map<Integer, PonUplinkReport> frameReports = new HashMap<>();
        for (Port port : reportPorts) {
            PonUplinkReport report = port.snapshot(frameId, frameStart);
            frameReports.put(port.sfuId, report);
            context.recordPonUplinkReport(report);
            generatedReports++;
        }
        reportedFrames = frameId + 1;
        return frameReports;
    }

    private void recordFrame(long frameId, long frameStart, long frameEnd, int packets, long usedNs,
                             long controlNs, SimulationContext context) {
        // 按帧号补记未唤醒的空帧，无需逐个空帧推进仿真时钟。
        for (long id = frames; id < frameId; id++) {
            long start = id * framePeriodNs;
            context.recordPonUplinkFrame(id, start, start + framePeriodNs, 0, 0);
            if (polling != null) {
                context.recordPollFrame(id, start, start + framePeriodNs, 0, 0);
            }
        }
        context.recordPonUplinkFrame(frameId, frameStart, frameEnd, packets, usedNs);
        if (polling != null) {
            context.recordPollFrame(frameId, frameStart, frameEnd, usedNs - controlNs, controlNs);
        }
        frames = frameId + 1;
    }

    private long serializationNs(Request request) {
        return PonLinkTiming.serializationNs(request.packet.bytes(), rateMbps);
    }

    private Grant reserveHead(long startTicks, long durationNs, PonUplinkReport report, long grantTicks) {
        Request request = scheduler.pollHead();
        Grant grant = new Grant(request, startTicks, Math.addExact(startTicks, durationNs), report, grantTicks);
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

    private void recordBwmap(Grant grant, boolean received, SimulationContext context) {
        context.recordPonBwmap(grant.request.packet, grant.request.port.sfuId, grant.grantTicks / framePeriodNs,
                grant.startTicks / framePeriodNs, grant.grantTicks, grant.startTicks, grant.endTicks, received);
    }

    private void scheduleGrantedHead(SimulationContext context) {
        Grant grant = grants.peekFirst();
        PonReportPoll poll = polls.peekFirst();
        if (poll != null && (grant == null || poll.startNs() < grant.startTicks)) {
            context.schedulePollEvent(poll.startNs() - context.nowTicks(), EventType.PON_POLL_START, poll);
            return;
        }
        if (grant != null) {
            context.scheduleAfterTicks(grant.startTicks - context.nowTicks(), EventType.FIBER_TX_START,
                    grant.request.packet, grant.request.port.sfuId);
        }
    }

    public void startPoll(PonReportPoll poll, SimulationContext context) {
        requirePollHead(poll);
        if (!grants.isEmpty() && grants.peekFirst().startTicks < poll.endNs()) {
            throw new IllegalStateException("REPORT_ONLY overlaps a DATA slot");
        }
        Port port = pollingPorts.stream().filter(candidate -> candidate.sfuId == poll.sfuId()).findFirst().orElseThrow();
        poll.start(context.nowTicks(), port.snapshot(poll.targetFrameId(), context.nowTicks()));
        generatedReports++;
        context.recordPollEvent(poll, "PON_POLL_TX_START");
        context.recordPollSnapshot(poll);
        context.schedulePollEvent(poll.endNs() - context.nowTicks(), EventType.PON_POLL_END, poll);
    }

    public void finishPoll(PonReportPoll poll, SimulationContext context) {
        requirePollHead(poll);
        if (context.nowTicks() != poll.endNs() || poll.report() == null) {
            throw new IllegalStateException("REPORT_ONLY finished outside its assigned slot");
        }
        polls.removeFirst();
        completedPolls++;
        pollBusyNs += poll.endNs() - poll.startNs();
        context.recordPollEvent(poll, "PON_POLL_TX_END");
        context.schedulePollEvent(propagationNs, EventType.PON_POLL_REPORT_RX, poll);
        scheduleGrantedHead(context);
    }

    private void requirePollHead(PonReportPoll poll) {
        if (polls.peekFirst() != poll) {
            throw new IllegalStateException("REPORT_ONLY event does not match the current reservation");
        }
    }

    public void start(Packet packet, int sfuId, SimulationContext context) {
        Grant grant = requireGrant(packet, sfuId);
        if (context.nowTicks() != grant.startTicks) {
            throw new IllegalStateException("PON uplink transmission started outside the granted slot");
        }
        if (bwmapPropagationNs >= 0 && !grant.bwmapReceived) {
            throw new IllegalStateException("SFU " + sfuId + " cannot transmit packet " + packet.id()
                    + " before receiving its BWmap");
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
        if (bwmapPropagationNs >= 0) {
            totalReservedWaitTicks += grant.startTicks - grant.grantTicks;
        }
        busyTicks += grant.endTicks - grant.startTicks;
        // 同刻按插入序先交付 DATA，再处理所携带的 REPORT。两者使用同一传播时延。
        context.scheduleAfterNanoseconds(propagationNs, EventType.PACKET_ARRIVAL_AT_MFU, packet, sfuId);
        if (grant.report != null) {
            context.schedulePonUplinkReportReception(propagationNs, packet, grant.report);
        }

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
            context.recordPonUplinkFrames(frames, framePeriodNs, sentPackets, busyTicks + pollBusyNs, grantWaitUs);
        }
        if (polling != null) {
            context.recordPollingSummary(completedPolls, busyTicks, pollBusyNs);
        }
        if (bwmapPropagationNs >= 0) {
            double reservedWaitUs = sentPackets == 0 ? 0.0 : totalReservedWaitTicks / sentPackets / 1_000.0;
            double ungrantedWaitUs = sentPackets == 0 ? 0.0 : totalGrantWaitTicks / sentPackets / 1_000.0;
            double serializationUs = sentPackets == 0 ? 0.0 : context.ticksToMicroseconds(busyTicks) / sentPackets;
            context.recordPonBwmapSummary(sentPackets, ungrantedWaitUs, reservedWaitUs, serializationUs);
        }
    }

    public void recordReportSummary(long receivedReports, SimulationContext context) {
        if (reportEnabled) {
            context.recordPonUplinkReportSummary(generatedReports, receivedReports, reportsWithoutGrant);
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
        private final PonUplinkReport report;
        private final long grantTicks;
        private boolean bwmapReceived;

        private Grant(Request request, long startTicks, long endTicks, PonUplinkReport report, long grantTicks) {
            this.request = request;
            this.startTicks = startTicks;
            this.endTicks = endTicks;
            this.report = report;
            this.grantTicks = grantTicks;
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
