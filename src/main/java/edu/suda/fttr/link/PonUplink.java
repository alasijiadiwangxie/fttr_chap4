package edu.suda.fttr.link;

import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.dba.TcontTopology;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
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
    private Mfu dbaMfu;
    private PonGrantPacketFiller dbaFiller;
    private final Map<Long, PonDbaGrant> budgetGrants = new HashMap<>();
    private long budgetSequence;
    private long budgetBytes;
    private long budgetActualBytes;
    private long budgetReservedNs;
    private long budgetActualNs;
    private long lastBudgetEndNs;
    private boolean multiGrantEnabled;
    private TcontTopology tcontTopology;
    private final List<Long> pollingAllocationIds = new ArrayList<>();

    /** 仅在启动阶段设置逻辑队列拓扑；不改变原有单队列 FCFS 路径。 */
    public void configureTcont(TcontTopology topology) {
        if (!reportPorts.isEmpty() || dbaMfu != null || tcontTopology != null) {
            throw new IllegalStateException("T-CONT topology must be configured before ports and DBA start");
        }
        if (topology.enabled()) {
            tcontTopology = topology;
        }
    }

    private boolean tcontEnabled() { return tcontTopology != null; }

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
        if (dbaMfu != null && (sfuId < 1 || (!tcontEnabled() && sfuId > 2)
                || reportPorts.stream().anyMatch(port -> port.sfuId == sfuId))) {
            throw new IllegalArgumentException("Real rr DBA requires distinct SFU IDs in the range 1 to 2");
        }
        Port port = new Port(sfuId);
        if (reportEnabled) {
            reportPorts.add(port);
        }
        if (dbaMfu != null) {
            refreshDbaFiller();
        }
        return port;
    }

    /** 可选真实路径；原构造和 FCFS 执行完全保持原样。 */
    public void enableDbaExecution(Mfu mfu) {
        enableDbaExecution(mfu, false);
    }

    public void enableDbaExecution(Mfu mfu, boolean multiGrant) {
        if (dbaMfu != null || mfu == null || framePeriodNs <= 0 || bwmapPropagationNs < 0
                || bwmapPropagationNs >= framePeriodNs || !reportEnabled || polling == null) {
            throw new IllegalArgumentException("Real rr DBA requires framed/report/BWmap/finite polling");
        }
        dbaMfu = mfu;
        multiGrantEnabled = multiGrant;
        if (multiGrant) {
            mfu.enableMultiGrantDba();
        }
        refreshDbaFiller();
    }

    /** 拓扑在启动时注册；装载器持有映射副本，不再把所有预算假定属于SFU1。 */
    private void refreshDbaFiller() {
        if (tcontEnabled()) {
            dbaFiller = new PonGrantPacketFiller(rateMbps, tcontTopology.allocationOwners());
            return;
        }
        Map<Long, Integer> owners = new HashMap<>();
        for (Port port : reportPorts) {
            if (port.sfuId < 1 || port.sfuId > 2 || owners.put(2L * port.sfuId, port.sfuId) != null) {
                throw new IllegalArgumentException("Invalid real rr SFU topology");
            }
        }
        dbaFiller = new PonGrantPacketFiller(rateMbps, owners);
    }

    /** 只根据固定关联和 SFU ID 建立轮询顺序，与队列是否有业务无关。 */
    public void startPolling(List<Integer> associatedSfuIds, SimulationContext context) {
        if (polling != null) {
            reportPorts.stream().filter(port -> tcontEnabled() || associatedSfuIds.contains(port.sfuId))
                    .sorted((left, right) -> Integer.compare(left.sfuId, right.sfuId)).forEach(pollingPorts::add);
            if (tcontEnabled()) {
                for (Port port : pollingPorts) {
                    for (TcontProfile profile : tcontTopology.profilesForSfu(port.sfuId)) {
                        pollingAllocationIds.add(profile.allocationId());
                    }
                }
            }
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
        private final Map<Long, LocalQueue> allocationQueues = new LinkedHashMap<>();

        private Port(int sfuId) {
            this.sfuId = sfuId;
            if (tcontEnabled()) {
                for (TcontProfile profile : tcontTopology.profilesForSfu(sfuId)) {
                    allocationQueues.put(profile.allocationId(), new LocalQueue());
                }
            }
        }

        public void enqueue(Packet packet, SimulationContext context) {
            Request request = new Request(this, packet, context.nowTicks(), nextSequence++);
            if (tcontEnabled()) {
                long allocationId = tcontTopology.allocationFor(sfuId, packet.type());
                LocalQueue local = allocationQueue(allocationId);
                local.queue.addLast(request);
                local.pendingBytes = Math.addExact(local.pendingBytes, packet.bytes());
                context.recordTcontEnqueue(packet, allocationId, sfuId);
                requestFrame(context);
                return;
            }
            queue.addLast(request);
            pendingBytes += packet.bytes();
            context.recordPonUplinkEnqueue(packet, sfuId);
            if (queue.size() == 1) {
                if (dbaMfu == null) {
                    scheduler.submitHead(request);
                }
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
            if (tcontEnabled()) {
                throw new IllegalStateException("A T-CONT queue view requires an explicit allocationId");
            }
            return queueView(0, queue, context);
        }

        public PonGrantPacketFiller.QueueView ungrantedPacketView(long allocationId, SimulationContext context) {
            if (!tcontEnabled()) {
                if (allocationId != 2L * sfuId) {
                    throw new IllegalArgumentException("Allocation does not belong to this SFU");
                }
                return ungrantedPacketView(context);
            }
            return queueView(allocationId, allocationQueue(allocationId).queue, context);
        }

        private PonGrantPacketFiller.QueueView queueView(long allocationId, Deque<Request> requests,
                                                       SimulationContext context) {
            if (context.microsecondsToTicks(1) != 1_000) {
                throw new IllegalStateException("PON grant preview requires a nanosecond clock");
            }
            List<PonGrantPacketFiller.PacketView> packets = new ArrayList<>(requests.size());
            for (Request request : requests) {
                // Packet 本身不外传，视图仅保留不可变数值，也不暴露队列或出队入口。
                packets.add(new PonGrantPacketFiller.PacketView(request.packet.id(), request.packet.bytes(),
                        request.enqueueTicks));
            }
            return allocationId == 0 ? new PonGrantPacketFiller.QueueView(sfuId, context.nowTicks(), packets)
                    : new PonGrantPacketFiller.QueueView(allocationId, sfuId, context.nowTicks(), packets);
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
                    ungrantedPacketView(allocationId, context));
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

        public void receiveDbaBwmap(PonDbaGrant grant, SimulationContext context) {
            requireBudget(grant);
            if (grant.sfuId() != sfuId) {
                throw new IllegalStateException("DATA budget BWmap reached the wrong SFU");
            }
            grant.receive(context.nowTicks());
            context.recordDbaExecution("PON_DBA_BWMAP_RX", grant, "");
        }

        private PonUplinkReport snapshot(long frameId, long frameStartNs) {
            return snapshotQueue(0, queue, pendingBytes, frameId, frameStartNs);
        }

        /** 只统计当前逻辑 FIFO；已装载的报文已经离队，不会被重复报告。 */
        public PonUplinkReport snapshot(long allocationId, long frameId, long frameStartNs) {
            if (!tcontEnabled()) {
                if (allocationId != 2L * sfuId) {
                    throw new IllegalArgumentException("Allocation does not belong to this SFU");
                }
                return snapshot(frameId, frameStartNs);
            }
            LocalQueue local = allocationQueue(allocationId);
            return snapshotQueue(allocationId, local.queue, local.pendingBytes, frameId, frameStartNs);
        }

        private LocalQueue allocationQueue(long allocationId) {
            LocalQueue local = allocationQueues.get(allocationId);
            if (local == null) {
                throw new IllegalArgumentException("Allocation " + allocationId + " does not belong to SFU " + sfuId);
            }
            return local;
        }

        private PonUplinkReport snapshotQueue(long allocationId, Deque<Request> requests, long bytes,
                                             long frameId, long frameStartNs) {
            int pendingPackets = requests.size();
            int eligiblePackets = pendingPackets;
            long eligibleBytes = bytes;
            // FIFO 按入队时间增长。只需从队尾剔除已入队、但恰逢本帧边界的报文。
            Iterator<Request> newestFirst = requests.descendingIterator();
            while (newestFirst.hasNext()) {
                Request request = newestFirst.next();
                if (request.enqueueTicks < frameStartNs) {
                    break;
                }
                eligiblePackets--;
                eligibleBytes -= request.packet.bytes();
            }
            return allocationId == 0
                    ? new PonUplinkReport(frameId, sfuId, frameStartNs, pendingPackets, bytes, eligiblePackets, eligibleBytes)
                    : new PonUplinkReport(frameId, allocationId, sfuId, frameStartNs, pendingPackets, bytes,
                            eligiblePackets, eligibleBytes);
        }
    }

    /** 物理端口下的独立待授权 FIFO；授权账本仍仅在 MFU 维护。 */
    private static final class LocalQueue {
        private final Deque<Request> queue = new ArrayDeque<>();
        private long pendingBytes;
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
            // 真实模式只在现有有限轮询创建窗口内调度。队首始终装不下时也不会无限空转。
            if (dbaMfu != null && Math.addExact(context.nowTicks(), delayNs) >= polling.endNs()) {
                return;
            }
            context.scheduleAfterNanoseconds(delayNs, EventType.PON_UPLINK_FRAME, null, 0);
            framePending = true;
        }
    }

    public void scheduleFrame(SimulationContext context) {
        if (dbaMfu != null) {
            scheduleDbaFrame(context);
            return;
        }
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

    /**
     * 真实模式不查看 scheduler/远端FIFO来决定 DATA 预算。控制预约确定后，
     * 唯一的 DATA 决策输入来自 MFU 已接收的 REPORT 和预算账本。
     */
    private void scheduleDbaFrame(SimulationContext context) {
        framePending = false;
        long now = context.nowTicks();
        if (now >= polling.endNs()) {
            return;
        }
        long frameId = now / framePeriodNs;
        if (tcontEnabled()) {
            recordAllocationReports(frameId, now, context);
            reportsWithoutGrant += tcontTopology.profiles().size();
        } else {
            recordReports(frameId, now, context);
            reportsWithoutGrant += reportPorts.size();
        } // 边界快照仅本地保存，预算执行另发时隙末快照。
        long targetStart = Math.addExact(now, framePeriodNs);
        long targetFrame = frameId + 1;
        long controlNs = 0;
        if (!pollingPorts.isEmpty() && frameId == nextPollFrame) {
            controlNs = PonLinkTiming.serializationNs(polling.bytes(), rateMbps);
            PonReportPoll poll;
            if (tcontEnabled()) {
                // 轮询顺序只由配置拓扑决定，MFU 不借此观察远端 FIFO。
                long allocationId = pollingAllocationIds.get((int) (createdPolls % pollingAllocationIds.size()));
                int sfuId = tcontTopology.profile(allocationId).sfuId();
                poll = new PonReportPoll(++createdPolls, allocationId, sfuId, frameId, now,
                        targetStart, Math.addExact(targetStart, controlNs));
            } else {
                Port port = pollingPorts.get((int) (createdPolls % pollingPorts.size()));
                poll = new PonReportPoll(++createdPolls, port.sfuId, frameId, now,
                        targetStart, Math.addExact(targetStart, controlNs));
            }
            polls.addLast(poll);
            context.recordPollEvent(poll, "PON_POLL_BWMAP_TX");
            context.schedulePollEvent(bwmapPropagationNs, EventType.PON_POLL_BWMAP_RX, poll);
            context.schedulePollEvent(targetStart - now, EventType.PON_POLL_START, poll);
            long last = (polling.endNs() - 1) / framePeriodNs;
            nextPollFrame = last - frameId >= polling.intervalFrames() ? frameId + polling.intervalFrames() : 0;
        }
        DbaPlan plan = dbaMfu.planDbaExecution(targetFrame, controlNs, rateMbps, framePeriodNs, context);
        if (!multiGrantEnabled && plan.grants().size() > 1) {
            throw new IllegalStateException("Real rr DBA supports at most one DATA budget per target frame");
        }
        for (DbaPlan.Grant proposed : plan.grants()) {
            if (multiGrantEnabled && proposed.startOffsetNs() == null) {
                throw new IllegalStateException("Multi-grant execution requires validated slot offsets");
            }
            long start = Math.addExact(targetStart, multiGrantEnabled ? proposed.startOffsetNs() : controlNs);
            long end = Math.addExact(start, proposed.plannedDurationNs());
            if (start < lastBudgetEndNs || end > Math.addExact(targetStart, framePeriodNs)) {
                throw new IllegalStateException("Real DATA budget overlaps another slot or exceeds its frame");
            }
            PonDbaGrant grant = new PonDbaGrant(++budgetSequence, proposed.allocationId(), proposed.sfuId(),
                    now, targetFrame, start, end, proposed.plannedBytes());
            dbaMfu.recordDbaBudgetCreated(grant, context);
            budgetGrants.put(grant.id(), grant);
            lastBudgetEndNs = end;
            budgetBytes = Math.addExact(budgetBytes, grant.budgetBytes());
            budgetReservedNs = Math.addExact(budgetReservedNs, end - start);
            context.recordDbaExecution("PON_DBA_BWMAP_TX", grant, "");
            context.scheduleDbaEvent(bwmapPropagationNs, EventType.PON_DBA_BWMAP_RX, grant);
            context.scheduleDbaEvent(start - now, EventType.PON_DBA_SLOT_START, grant);
            context.scheduleDbaEvent(end - now, EventType.PON_DBA_SLOT_END, grant);
        }
        frames = Math.max(frames, targetFrame + 1);
        requestFrame(context);
    }

    public void receiveDbaBwmap(PonDbaGrant grant, SimulationContext context) {
        budgetPort(grant).receiveDbaBwmap(grant, context);
    }

    /** SFU 收到预算后，在窗口起点用本地完整报文装载器选择并原子移出队首前缀。 */
    public void startDbaGrant(PonDbaGrant grant, SimulationContext context) {
        Port port = budgetPort(grant);
        if (!grant.received() || grant.started() || context.nowTicks() != grant.startNs() || !grants.isEmpty()) {
            throw new IllegalStateException("DATA cannot start without BWmap or outside its exclusive budget slot");
        }
        PonGrantPacketFiller.Result filled = port.previewGrant(dbaFiller, grant.allocationId(), grant.budgetBytes(),
                grant.endNs() - grant.startNs(), grant.startNs(), context);
        grant.load(context.nowTicks(), filled);
        long cursor = grant.startNs();
        for (int packetId : filled.selectedPacketIds()) {
            Request request = tcontEnabled() ? port.allocationQueue(grant.allocationId()).queue.removeFirst()
                    : port.queue.removeFirst();
            if (request.packet.id() != packetId) {
                throw new IllegalStateException("SFU FIFO changed during atomic budget loading");
            }
            if (tcontEnabled()) {
                port.allocationQueue(grant.allocationId()).pendingBytes -= request.packet.bytes();
            } else {
                port.pendingBytes -= request.packet.bytes();
            }
            long end = Math.addExact(cursor, serializationNs(request));
            Grant child = new Grant(request, cursor, end, null, grant.createNs(), grant);
            child.bwmapReceived = true;
            grants.addLast(child);
            totalGrantWaitTicks += Math.max(0, grant.createNs() - request.enqueueTicks);
            cursor = end;
        }
        String ids = filled.selectedPacketIds().isEmpty() ? "-" : filled.selectedPacketIds().stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(";"));
        context.recordDbaExecution("PON_DBA_FILL", grant,
                "selected_packet_ids=" + ids + " selected_duration_ns=" + filled.selectedDurationNs()
                        + " unused_duration_ns=" + filled.unusedDurationNs() + " remaining_packets="
                        + filled.remainingPacketCount() + " fill_reason=" + filled.fillReason());
        scheduleDbaPacketHead(context);
    }

    public void finishDbaGrant(PonDbaGrant grant, SimulationContext context) {
        Port port = budgetPort(grant);
        PonUplinkReport report = port.snapshot(grant.allocationId(), grant.targetFrameId(), context.nowTicks());
        grant.finish(context.nowTicks(), report);
        generatedReports++;
        context.recordPonUplinkReport(report);
        context.recordDbaExecution("PON_DBA_SLOT_END", grant,
                "snapshot_time_ns=" + report.snapshotTimeNs() + " pending_bytes=" + report.pendingBytes());
        context.scheduleDbaEvent(propagationNs, EventType.PON_DBA_SETTLEMENT_RX, grant);
    }

    private void requireBudget(PonDbaGrant grant) {
        if (dbaMfu == null || grant == null || budgetGrants.get(grant.id()) != grant) {
            throw new IllegalStateException("Unknown real DATA budget");
        }
    }

    private Port budgetPort(PonDbaGrant grant) {
        requireBudget(grant);
        if (tcontEnabled() && tcontTopology.profile(grant.allocationId()).sfuId() != grant.sfuId()) {
            throw new IllegalStateException("DATA budget allocation owner mismatch");
        }
        return reportPorts.stream().filter(port -> port.sfuId == grant.sfuId()).findFirst().orElseThrow();
    }

    private void scheduleDbaPacketHead(SimulationContext context) {
        Grant child = grants.peekFirst();
        if (child != null) {
            context.scheduleAfterTicks(child.startTicks - context.nowTicks(), EventType.FIBER_TX_START,
                    child.request.packet, child.request.port.sfuId);
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

    /** 每个逻辑对象独立取样；边界快照不反馈给 MFU，只有后续合法 REPORT 接收才更新账本。 */
    private void recordAllocationReports(long frameId, long nowNs, SimulationContext context) {
        for (Port port : reportPorts) {
            for (long allocationId : port.allocationQueues.keySet()) {
                context.recordPonUplinkReport(port.snapshot(allocationId, frameId, nowNs));
                generatedReports++;
            }
        }
        reportedFrames = frameId + 1;
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
        poll.start(context.nowTicks(), port.snapshot(poll.allocationId(), poll.targetFrameId(), context.nowTicks()));
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
        if (dbaMfu == null) {
            scheduleGrantedHead(context);
        }
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
        if (grant.budget != null) {
            context.recordDbaExecution("PON_DBA_DATA_START", grant.budget, "packet_id=" + packet.id());
        }
        if (grant.budget != null) {
            // 半开时隙可精确相邻。只让真实预算DATA先完成，避免下一帧START看见旧发送资源未释放。
            context.scheduleDbaDataEnd(grant.endTicks - grant.startTicks, packet, grant.budget);
        } else {
            context.scheduleAfterTicks(grant.endTicks - grant.startTicks, EventType.FIBER_TX_END, packet, sfuId);
        }
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
            totalReservedWaitTicks += grant.startTicks - (grant.budget == null ? grant.grantTicks
                    : Math.max(grant.grantTicks, grant.request.enqueueTicks));
        }
        busyTicks += grant.endTicks - grant.startTicks;
        if (grant.budget != null) {
            grant.budget.recordSent(packet.bytes());
            budgetActualBytes = Math.addExact(budgetActualBytes, packet.bytes());
            budgetActualNs = Math.addExact(budgetActualNs, grant.endTicks - grant.startTicks);
            context.recordDbaExecution("PON_DBA_DATA_END", grant.budget, "packet_id=" + packet.id());
        }
        // 同刻按插入序先交付 DATA，再处理所携带的 REPORT。两者使用同一传播时延。
        context.scheduleAfterNanoseconds(propagationNs, EventType.PACKET_ARRIVAL_AT_MFU, packet, sfuId);
        if (grant.report != null) {
            context.schedulePonUplinkReportReception(propagationNs, packet, grant.report);
        }

        if (dbaMfu != null) {
            scheduleDbaPacketHead(context);
        } else if (framePeriodNs == 0) {
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
        if (dbaMfu != null) {
            long pendingPackets = reportPorts.stream().mapToLong(port -> tcontEnabled()
                    ? port.allocationQueues.values().stream().mapToLong(local -> local.queue.size()).sum()
                    : port.queue.size()).sum();
            context.recordDbaSummary(budgetSequence, budgetBytes, budgetActualBytes, budgetBytes - budgetActualBytes,
                    budgetReservedNs, budgetActualNs, pendingPackets);
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
        private final PonDbaGrant budget;

        private Grant(Request request, long startTicks, long endTicks, PonUplinkReport report, long grantTicks) {
            this(request, startTicks, endTicks, report, grantTicks, null);
        }

        private Grant(Request request, long startTicks, long endTicks, PonUplinkReport report, long grantTicks,
                      PonDbaGrant budget) {
            this.request = request;
            this.startTicks = startTicks;
            this.endTicks = endTicks;
            this.report = report;
            this.grantTicks = grantTicks;
            this.budget = budget;
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
