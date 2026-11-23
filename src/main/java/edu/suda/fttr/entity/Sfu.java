package edu.suda.fttr.entity;

import edu.suda.fttr.link.AmpduConfig;
import edu.suda.fttr.link.PonUplink;
import edu.suda.fttr.link.PonReportPoll;
import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.mac.EdcaAccess;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaState;
import edu.suda.fttr.mac.WifiAccessMode;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每台 SFU 统一协调本信道上的下行和关联 STA 的上行。 */
public final class Sfu {
    private final int id;
    private WifiLink wifi;
    private ApModeSwitch modeSwitch;
    private final WifiChannel channel = new WifiChannel();
    private final Map<Integer, Sta> stations;
    private final EdcaState edcaState;
    private final EdcaParameters edcaParameters;
    private final EdcaAccess edcaAccess;
    private final boolean multiAc;
    private final WifiRetryPolicy retryPolicy;
    private final PonUplink.Port ponUplinkPort;
    // 以实际发送设备的 AC 状态为键。每个键独立维护 FIFO 与队首的接入计时。
    private final Map<EdcaState.AcState, Deque<QueuedPacket>> deviceQueues = new HashMap<>();
    // 同一次碰撞中仍在发射 DATA 的报文。最后一条结束时才释放公共信道。
    private final Set<Packet> collidedData = new HashSet<>();
    private final Set<Packet> awaitingAck = new HashSet<>();
    private AmpduConfig ampduConfig;
    private AmpduAttempt activeAggregate;
    private final Map<Packet, AmpduAttempt> collidedAggregates = new HashMap<>();
    private final Deque<QueuedPacket> voQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> viQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> beQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> bkQueue = new ArrayDeque<>();
    private QueuedPacket active;
    private Txop txop;
    private boolean selectionPending;
    private long txStartTicks;
    private long dataEndTicks;
    private long announcedNavEndTicks;
    private int sentPackets;
    private long sentBytes;
    private double queueWaitSumUs;

    public Sfu(int id, WifiLink wifi, Map<Integer, Sta> stations) {
        this(id, wifi, stations, EdcaParameters.defaults());
    }

    public Sfu(int id, WifiLink wifi, Map<Integer, Sta> stations, EdcaParameters edcaParameters) {
        this(id, wifi, stations, edcaParameters, false);
    }

    public Sfu(int id, WifiLink wifi, Map<Integer, Sta> stations, EdcaParameters edcaParameters, boolean edcaCountdown) {
        this(id, wifi, stations, edcaParameters,
                edcaCountdown ? WifiAccessMode.EDCA_COUNTDOWN : WifiAccessMode.FIXED_PRIORITY);
    }

    public Sfu(int id, WifiLink wifi, Map<Integer, Sta> stations, EdcaParameters edcaParameters,
               WifiAccessMode accessMode) {
        this(id, wifi, stations, edcaParameters, accessMode, null);
    }

    public Sfu(int id, WifiLink wifi, Map<Integer, Sta> stations, EdcaParameters edcaParameters,
               WifiAccessMode accessMode, WifiRetryPolicy retryPolicy) {
        this(id, wifi, stations, edcaParameters, accessMode, retryPolicy, null);
    }

    public Sfu(int id, WifiLink wifi, Map<Integer, Sta> stations, EdcaParameters edcaParameters,
               WifiAccessMode accessMode, WifiRetryPolicy retryPolicy, PonUplink.Port ponUplinkPort) {
        this.id = id;
        this.wifi = wifi;
        this.stations = Map.copyOf(stations);
        this.edcaParameters = edcaParameters;
        this.edcaState = EdcaState.forSfu(id, edcaParameters);
        this.stations.values().forEach(sta -> sta.attachChannel(id, channel));
        this.multiAc = accessMode == WifiAccessMode.EDCA_MULTI_AC;
        this.edcaAccess = accessMode == WifiAccessMode.FIXED_PRIORITY ? null
                : new EdcaAccess(id, channel, edcaParameters, multiAc);
        this.retryPolicy = retryPolicy;
        this.ponUplinkPort = ponUplinkPort;
    }

    public int id() {
        return id;
    }

    public void configureModeSwitch(ApModeSwitch modeSwitch) {
        this.modeSwitch = modeSwitch;
    }

    public void configureAmpdu(AmpduConfig config) {
        ampduConfig = config;
    }

    public void receiveModeSwitchRequest(SimulationContext context) {
        modeSwitch.receiveRequest(context);
        tryStartModeSwitch(context);
    }

    public void finishModeSwitch(SimulationContext context) {
        // 新能力必须在释放接入资源之前生效。切换独占资源，但不计入已有 DATA/ACK 忙时。
        wifi = modeSwitch.finish(context);
        channel.release();
        tryStartModeSwitch(context);
        if (!multiAc) {
            requestSelection(context);
        }
    }

    private void tryStartModeSwitch(SimulationContext context) {
        if (modeSwitch != null && ccaState() == WifiChannel.State.IDLE && awaitingAck.isEmpty()
                && modeSwitch.startPending(context)) {
            channel.occupy();
        }
    }

    public void receivePonBwmap(Packet packet, SimulationContext context) {
        if (ponUplinkPort == null) {
            throw new IllegalStateException("SFU " + id + " has no PON uplink port for BWmap");
        }
        ponUplinkPort.receiveBwmap(packet, context);
    }

    public void receivePollBwmap(PonReportPoll poll, SimulationContext context) {
        if (ponUplinkPort == null) {
            throw new IllegalStateException("SFU has no PON uplink port for REPORT_ONLY");
        }
        ponUplinkPort.receivePollBwmap(poll, context);
    }

    public EdcaState edcaState() {
        return edcaState;
    }

    /** 查询本 SFU 与关联 STA 共用的信道。 */
    public WifiChannel.State ccaState() {
        return channel.state();
    }

    /** 设备键 0 表示本 SFU，正数表示关联 STA。返回该设备最近的 NAV 到期刻度。 */
    public long navUntilTicks(int listenerKey) {
        return edcaAccess == null ? 0 : edcaAccess.navUntilTicks(listenerKey);
    }

    /** 在指定旁听端处理一次成功解码的 MAC Duration，时长使用本次仿真的时钟刻度。 */
    public void receiveMacDuration(int listenerKey, int frameSenderKey, int frameRecipientKey, long durationTicks,
                                   SimulationContext context) {
        if (edcaAccess != null) {
            edcaAccess.receiveMacDuration(listenerKey, frameSenderKey, frameRecipientKey, durationTicks, context);
        }
    }

    public int sentPackets() {
        return sentPackets;
    }

    public long sentBytes() {
        return sentBytes;
    }

    public double averageQueueWaitUs() {
        return sentPackets == 0 ? 0.0 : queueWaitSumUs / sentPackets;
    }

    public void enqueueForWifi(Packet packet, SimulationContext context) {
        if (!stations.containsKey(packet.staId())) {
            throw new IllegalStateException("Packet " + packet.id() + " reached unassociated SFU " + id);
        }
        if (multiAc) {
            EdcaState.AcState ac = senderAc(packet);
            Deque<QueuedPacket> queue = deviceQueues.computeIfAbsent(ac, key -> new ArrayDeque<>());
            queue.addLast(new QueuedPacket(packet, context.nowTicks()));
            context.recordEnqueue(packet, id, queue.size());
            if (queue.size() == 1) {
                edcaAccess.start(packet, ac, context);
            }
            return;
        }
        // 上行报文仍在源 STA 等待。这里入队的是对同一无线资源的发送请求。
        Deque<QueuedPacket> queue = queueFor(packet.type());
        queue.addLast(new QueuedPacket(packet, context.nowTicks()));
        context.recordEnqueue(packet, id, queue.size());
        requestSelection(context);
    }

    public void transmit(Packet packet, SimulationContext context) {
        checkActive(packet);
        if (!multiAc && edcaAccess != null && edcaAccess.isWaiting()) {
            throw new IllegalStateException("Wi-Fi access wait is incomplete for packet " + packet.id());
        }
        channel.occupy();
        context.recordWifiStart();
        if (multiAc && wifi.hasAckExchange()) {
            long limitNs = edcaParameters.forCategory(packet.type()).txopLimitUs() * 1_000L;
            if (limitNs > 0 && wifi.exchangeDurationNs(packet) <= limitNs) {
                txop = new Txop(packet, context.nowTicks(), limitNs);
                context.recordWifiTxopStart(packet, id, limitNs);
            }
        }
        activeAggregate = selectAggregate(packet, context.nowTicks());
        if (activeAggregate == null) {
            startData(packet, context);
        } else {
            startAggregate(packet, activeAggregate, EventType.WIFI_AMPDU_DATA_END, context);
        }
    }

    public void transmitWithinTxop(Packet packet, SimulationContext context) {
        checkActive(packet);
        if (txop == null || ccaState() != WifiChannel.State.BUSY) {
            throw new IllegalStateException("No active TXOP for packet " + packet.id());
        }
        // 同一 AC 队首从 ACK 后的 SIFS 继续发送，信道和全局忙时均沿用原占用。
        startData(packet, context);
    }

    private void startData(Packet packet, SimulationContext context) {
        txStartTicks = context.nowTicks();
        // Duration 随发送的 MAC 帧确定。DATA 发送期间的新到报文不能改写已经发出的预约。
        announcedNavEndTicks = edcaAccess != null && wifi.hasAckExchange()
                ? dataReservationEndTicks(packet) : 0;
        if (retryPolicy != null) {
            context.recordWifiMacAttempt(packet, id, active.retries);
        }
        context.recordApDataBoundary(packet, id, true);
        context.recordApDownlinkAttempt(packet, id, null);
        wifi.transmit(packet, id, context);
    }

    public void receiveData(Packet packet, SimulationContext context) {
        checkActive(packet);
        dataEndTicks = context.nowTicks();
        context.recordApDataBoundary(packet, id, false);
        boolean ackPending = wifi.scheduleAck(packet, id, context);
        if (ackPending && edcaAccess != null) {
            publishMacDuration(packet, false, announcedNavEndTicks - dataEndTicks, context);
        }
        context.recordApUplinkReception(packet, id, 1);
        deliverData(active, txStartTicks, context);
        if (!ackPending) {
            completeTransmission(packet, context);
        }
    }

    private void deliverData(QueuedPacket queued, long startTicks, SimulationContext context) {
        Packet packet = queued.packet;
        context.recordApTrafficDelivery(packet, id);
        if (packet.isUplink()) {
            // 成功接收 DATA 后交给独立光路。无线侧照常完成 SIFS 和 ACK。
            if (ponUplinkPort == null) {
                context.scheduleAfter(0, EventType.FIBER_TX_START, packet, id);
            } else {
                ponUplinkPort.enqueue(packet, context);
            }
        } else {
            stations.get(packet.destinationStaId()).receive(packet, context);
            sentPackets++;
            sentBytes += packet.bytes();
            queueWaitSumUs += context.ticksToMicroseconds(startTicks - queued.arrivalTicks);
        }
    }

    public void transmitAck(Packet packet, SimulationContext context) {
        checkActive(packet);
        context.recordApAckBoundary(packet, id, true);
        wifi.transmitAck(packet, id, context);
    }

    public void completeTransmission(Packet packet, SimulationContext context) {
        checkActive(packet);
        if (wifi.hasAckExchange()) {
            context.recordApAckBoundary(packet, id, false);
        }
        if (announcedNavEndTicks > context.nowTicks()) {
            // 立即 ACK 沿用本次 DATA 宣布的预约，只报告完成 ACK 之后的剩余部分。
            publishMacDuration(packet, true, announcedNavEndTicks - context.nowTicks(), context);
        }
        EdcaState.AcState ac = edcaAccess == null ? null : senderAc(packet);
        QueuedPacket next = null;
        if (multiAc) {
            Deque<QueuedPacket> queue = deviceQueues.get(ac);
            queue.removeFirst();
            next = queue.peekFirst();
        }
        boolean continues = txop != null && next != null && txopContinuationNs(next.packet, context.nowTicks()) > 0;
        if (!continues) {
            channel.release();
        }
        // 逐包服务时长止于自己的 ACK。全局忙时跨过 TXOP 内的帧间 SIFS，直到最终释放。
        context.recordWifiService(packet, id, active.arrivalTicks, txStartTicks, dataEndTicks, !continues);
        if (retryPolicy != null) {
            context.recordWifiMacSuccess(packet, id, active.retries);
            active.retries = 0;
        }
        if (ac != null) {
            ac.onTransmissionSuccess();
        }
        if (txop != null) {
            txop.packetCount++;
            if (continues) {
                active = next;
                context.scheduleAfterNanoseconds(wifi.sifsNs(), EventType.WIFI_TXOP_DATA_START, next.packet, id);
                return;
            }
            context.recordWifiTxopEnd(txop.firstPacket, id, txop.startTicks, txop.limitNs, txop.packetCount);
            txop = null;
        }
        active = null;
        tryStartModeSwitch(context);
        if (multiAc) {
            startDeviceQueueHead(ac, context);
        } else {
            requestSelection(context);
        }
    }

    private AmpduAttempt selectAggregate(Packet head, long startTicks) {
        if (ampduConfig == null || ampduConfig.maxMpdus() == 1) {
            return null;
        }
        Deque<QueuedPacket> queue = deviceQueues.get(senderAc(head));
        List<QueuedPacket> selected = new ArrayList<>();
        List<Packet> packets = new ArrayList<>();
        int bytes = 0;
        long durationNs = 0;
        for (QueuedPacket candidate : queue) {
            Packet packet = candidate.packet;
            // 同一设备和 AC 共用 FIFO。只取同一对端的连续队首，保留其他接收端的顺序。
            if (selected.size() == ampduConfig.maxMpdus() || packet.staId() != head.staId()) {
                break;
            }
            packets.add(packet);
            try {
                int candidateBytes = wifi.ampduBytes(head, packets);
                if (candidateBytes > ampduConfig.maxBytes()) {
                    packets.remove(packets.size() - 1);
                    break;
                }
                long candidateDuration = wifi.ampduDurationNs(head, candidateBytes);
                bytes = candidateBytes;
                durationNs = candidateDuration;
                selected.add(candidate);
            } catch (IllegalArgumentException exceedsPhyCapacity) {
                // 已校验的单包仍可走原发送路径。PHY 限额之外的尾包留待下一轮。
                packets.remove(packets.size() - 1);
                break;
            }
        }
        return selected.size() < 2 ? null : new AmpduAttempt(selected, packets, bytes, durationNs, startTicks);
    }

    private void startAggregate(Packet head, AmpduAttempt attempt, EventType endEvent, SimulationContext context) {
        if (endEvent == EventType.WIFI_AMPDU_DATA_END) {
            context.recordApDataBoundary(head, id, true);
        }
        traceAggregate("DATA_START", head, attempt, context);
        context.recordApDownlinkAttempt(head, id, attempt.packets);
        for (QueuedPacket queued : attempt.members) {
            context.recordWifiMacAttempt(queued.packet, id, queued.retries);
        }
        context.scheduleAfterNanoseconds(attempt.ppduNs, endEvent, head, id);
    }

    public void receiveAggregate(Packet head, SimulationContext context) {
        checkActive(head);
        AmpduAttempt attempt = activeAggregate;
        dataEndTicks = context.nowTicks();
        context.recordApDataBoundary(head, id, false);
        for (int i = 0; i < attempt.members.size(); i++) {
            QueuedPacket queued = attempt.members.get(i);
            if (queued.retries == 0 && ampduConfig.failsFirstAttempt(queued.packet.id())) {
                continue; // 可复核的 MPDU 解码失败注入，只作用于被聚合的首次发送。
            }
            attempt.bitmap |= 1L << i;
        }
        traceAggregate("DATA_END", head, attempt, context);
        if (head.isUplink() && attempt.bitmap != 0) {
            Packet firstDecoded = attempt.packets.get(Long.numberOfTrailingZeros(attempt.bitmap));
            context.recordApUplinkReception(firstDecoded, id, Long.bitCount(attempt.bitmap));
        }
        for (int i = 0; i < attempt.members.size(); i++) {
            if (attempt.acknowledged(i)) {
                QueuedPacket queued = attempt.members.get(i);
                deliverData(queued, attempt.startTicks, context);
                context.recordAmpduMpdu(head, queued.packet, id, "DELIVERED", queued.retries);
            }
        }
        publishMacDuration(head, false, wifi.blockAckResponseDurationNs(), context);
        context.scheduleAfterNanoseconds(wifi.sifsNs(), EventType.WIFI_AMPDU_BA_START, head, id);
    }

    public void transmitBlockAck(Packet head, SimulationContext context) {
        checkActive(head);
        context.recordApAckBoundary(head, id, true);
        traceAggregate("BA_START", head, activeAggregate, context);
        context.scheduleAfterNanoseconds(wifi.blockAckNs(), EventType.WIFI_AMPDU_BA_END, head, id);
    }

    public void completeBlockAck(Packet head, SimulationContext context) {
        checkActive(head);
        context.recordApAckBoundary(head, id, false);
        traceAggregate("BA_END", head, activeAggregate, context);
        settleAggregate(head, activeAggregate, true, context);
        activeAggregate = null;
        active = null;
        channel.release();
        context.recordWifiAggregateEnd();
        tryStartModeSwitch(context);
        startDeviceQueueHead(senderAc(head), context);
    }

    private void traceAggregate(String phase, Packet head, AmpduAttempt attempt, SimulationContext context) {
        context.recordAmpduEvent(phase, head, id, attempt.packets, attempt.apepBytes, attempt.ppduNs,
                wifi.sifsNs(), wifi.blockAckNs(), attempt.bitmap);
    }

    /** BA bitmap only removes acknowledged members; retry survivors keep the FIFO prefix order. */
    private void settleAggregate(Packet head, AmpduAttempt attempt, boolean blockAckReceived, SimulationContext context) {
        EdcaState.AcState ac = senderAc(head);
        Deque<QueuedPacket> queue = deviceQueues.get(ac);
        List<QueuedPacket> survivors = new ArrayList<>();
        for (int i = 0; i < attempt.members.size(); i++) {
            QueuedPacket queued = attempt.members.get(i);
            if (queue.removeFirst() != queued) {
                throw new IllegalStateException("A-MPDU no longer matches the sending FIFO");
            }
            if (attempt.acknowledged(i)) {
                context.recordWifiService(queued.packet, id, queued.arrivalTicks, attempt.startTicks, dataEndTicks, false);
                context.recordWifiMacSuccess(queued.packet, id, queued.retries);
                context.recordAmpduMpdu(head, queued.packet, id, "ACKED", queued.retries);
                continue;
            }
            context.recordAmpduMpdu(head, queued.packet, id, blockAckReceived ? "NACK" : "NO_BA", queued.retries);
            if (queued.retries < retryPolicy.maxRetries()) {
                survivors.add(queued);
            } else {
                context.recordWifiRetryLimitDrop(queued.packet, id, queued.retries, !blockAckReceived);
                context.recordAmpduMpdu(head, queued.packet, id, "DROPPED", queued.retries);
            }
        }
        int cwBefore = ac.currentCw();
        if (attempt.bitmap != 0) {
            ac.onTransmissionSuccess();
        } else if (survivors.isEmpty()) {
            ac.resetContentionWindow();
        } else {
            ac.onContentionFailure();
        }
        if (!survivors.isEmpty()) {
            int backoff = ac.drawBackoff(); // 一个 AC 只安排一个新的竞争计数。
            for (QueuedPacket queued : survivors) {
                queued.retries++;
                context.recordWifiMacRetry(queued.packet, id, queued.retries, cwBefore, ac.currentCw(), backoff);
                context.recordAmpduMpdu(head, queued.packet, id, "RETRY", queued.retries);
            }
            for (int i = survivors.size() - 1; i >= 0; i--) {
                queue.addFirst(survivors.get(i));
            }
        }
    }

    private long dataReservationEndTicks(Packet packet) {
        long expectedAckEnd = Math.addExact(txStartTicks, wifi.exchangeDurationNs(packet));
        if (txop == null) {
            return expectedAckEnd;
        }
        // DATA 可同时预约已在队列且能放进 TXOP 的下一次完整交换。当前 DATA 仍在队首。
        Iterator<QueuedPacket> queue = deviceQueues.get(senderAc(packet)).iterator();
        queue.next();
        if (!queue.hasNext()) {
            return expectedAckEnd;
        }
        return Math.addExact(expectedAckEnd, txopContinuationNs(queue.next().packet, expectedAckEnd));
    }

    private long txopContinuationNs(Packet next, long ackEndTicks) {
        long remainingNs = txop.limitNs - (ackEndTicks - txop.startTicks);
        long sifsNs = wifi.sifsNs();
        if (remainingNs < sifsNs) {
            return 0;
        }
        long exchangeNs = wifi.exchangeDurationNs(next);
        return exchangeNs <= remainingNs - sifsNs ? sifsNs + exchangeNs : 0;
    }

    private void publishMacDuration(Packet packet, boolean ack, long durationNs, SimulationContext context) {
        int dataSender = packet.isUplink() ? packet.staId() : 0;
        int dataRecipient = packet.isUplink() ? 0 : packet.staId();
        int frameSender = ack ? dataRecipient : dataSender;
        int frameRecipient = ack ? dataSender : dataRecipient;
        // 只在本 SFU 所属的信道中发布。当前模型假定所有关联设备都能听到成功的帧。
        receiveMacDuration(0, frameSender, frameRecipient, durationNs, context);
        for (int staId : stations.keySet()) {
            receiveMacDuration(staId, frameSender, frameRecipient, durationNs, context);
        }
    }

    private void requestSelection(SimulationContext context) {
        if (ccaState() == WifiChannel.State.BUSY || active != null || selectionPending
                || (voQueue.isEmpty() && viQueue.isEmpty() && beQueue.isEmpty() && bkQueue.isEmpty())) {
            return;
        }
        // 同刻到达和完成事件处理完毕后统一选队。一次只安排一个选队事件。
        selectionPending = true;
        context.scheduleWifiSelection(id);
    }

    public void selectNext(SimulationContext context) {
        selectionPending = false;
        if (modeSwitch != null && ccaState() == WifiChannel.State.BUSY) {
            return; // 同刻请求先封锁接入，切换结束后可重新安排选队。
        }
        Deque<QueuedPacket> queue = highestPriorityQueue();
        context.recordQueueSelection(queue.getFirst().packet, id,
                voQueue.size(), viQueue.size(), beQueue.size(), bkQueue.size());
        // 固定优先级只在这里选包。选中的请求在接入等待期间一直保留，信道到 DATA 开始才被占用。
        active = queue.removeFirst();
        if (edcaAccess == null) {
            context.scheduleAfter(0, EventType.WIFI_TX_START, active.packet, id);
        } else {
            edcaAccess.start(active.packet, senderAc(active.packet), context);
        }
    }

    public void advanceAccess(Event event) {
        edcaAccess.onTimer(event);
    }

    public void resolveAccess(Event event, SimulationContext context) {
        List<Packet> contenders = edcaAccess.resolve(event);
        if (contenders.isEmpty()) {
            return;
        }
        if (active != null || ccaState() != WifiChannel.State.IDLE) {
            throw new IllegalStateException("SFU " + id + " cannot start access on a busy channel");
        }
        if (contenders.size() > 1) {
            // 所有设备在同一个时刻开始 DATA。占用一次信道，再分别安排各自的 DATA 结束事件。
            channel.occupy();
            collidedData.addAll(contenders);
            context.recordWifiCollisionStart(contenders, id);
            for (Packet packet : contenders) {
                AmpduAttempt attempt = selectAggregate(packet, context.nowTicks());
                if (retryPolicy != null) {
                    edcaAccess.suspendSender(packet);
                    if (attempt == null) {
                        context.recordWifiMacAttempt(packet, id, deviceQueues.get(senderAc(packet)).getFirst().retries);
                    }
                }
                if (attempt == null) {
                    context.recordApDownlinkAttempt(packet, id, null);
                    wifi.transmitCollidedData(packet, id, context);
                } else {
                    collidedAggregates.put(packet, attempt);
                    startAggregate(packet, attempt, EventType.WIFI_COLLISION_DATA_END, context);
                }
            }
            return;
        }
        Packet packet = contenders.get(0);
        EdcaState sender = senderEdcaState(packet);
        active = deviceQueues.get(sender.forCategory(packet.type())).getFirst();
        context.recordQueueSelection(packet, id, deviceQueueDepth(sender, AccessCategory.VO),
                deviceQueueDepth(sender, AccessCategory.VI), deviceQueueDepth(sender, AccessCategory.BE),
                deviceQueueDepth(sender, AccessCategory.BK));
        context.scheduleAfter(0, EventType.WIFI_TX_START, packet, id);
    }

    public void finishCollidedData(Packet packet, SimulationContext context) {
        if (!collidedData.remove(packet)) {
            throw new IllegalStateException("No collided DATA pending for packet " + packet.id());
        }
        context.recordApCollisionDataEnd(packet, id);
        if (retryPolicy == null) {
            context.recordWifiCollisionDrop(packet, id);
            EdcaState.AcState ac = senderAc(packet);
            ac.clearBackoff();
            advanceDeviceQueue(ac, context);
        } else {
            // ACK 从本设备的 DATA 结束起等待。射频占用和发送设备的等待状态分别管理。
            awaitingAck.add(packet);
            context.recordWifiCollisionDataEnd(packet, id);
            AmpduAttempt attempt = collidedAggregates.get(packet);
            if (attempt != null) {
                traceAggregate("COLLISION_DATA_END", packet, attempt, context);
            }
            context.scheduleAfter(retryPolicy.ackTimeoutUs(), EventType.WIFI_ACK_TIMEOUT, packet, id);
        }
        if (collidedData.isEmpty()) {
            channel.release();
            context.recordWifiCollisionEnd(id);
            tryStartModeSwitch(context);
        }
    }

    public void onAckTimeout(Packet packet, SimulationContext context) {
        if (!awaitingAck.remove(packet)) {
            throw new IllegalStateException("No ACK pending for packet " + packet.id());
        }
        EdcaState.AcState ac = senderAc(packet);
        QueuedPacket queued = deviceQueues.get(ac).getFirst();
        context.recordWifiAckTimeout(packet, id, queued.retries);
        AmpduAttempt attempt = collidedAggregates.remove(packet);
        if (attempt != null) {
            traceAggregate("BA_TIMEOUT", packet, attempt, context);
            settleAggregate(packet, attempt, false, context);
            startDeviceQueueHead(ac, context);
        } else if (queued.retries < retryPolicy.maxRetries()) {
            int cwBefore = ac.currentCw();
            ac.onContentionFailure();
            int backoff = ac.drawBackoff();
            queued.retries++;
            context.recordWifiMacRetry(packet, id, queued.retries, cwBefore, ac.currentCw(), backoff);
            // 继续使用同一个队首报文和原入队时间。恢复发送端后，计时器按 CCA 等待 AIFS。
            edcaAccess.start(packet, ac, context);
        } else {
            context.recordWifiRetryLimitDrop(packet, id, queued.retries);
            ac.resetContentionWindow();
            queued.retries = 0;
            advanceDeviceQueue(ac, context);
        }
        edcaAccess.resumeSender(packet);
        tryStartModeSwitch(context);
    }

    private void advanceDeviceQueue(EdcaState.AcState ac, SimulationContext context) {
        // 队首保留到成功或失败结束，随后才允许下一条报文参与竞争。
        deviceQueues.get(ac).removeFirst();
        startDeviceQueueHead(ac, context);
    }

    private void startDeviceQueueHead(EdcaState.AcState ac, SimulationContext context) {
        Deque<QueuedPacket> queue = deviceQueues.get(ac);
        if (queue.isEmpty()) {
            deviceQueues.remove(ac);
        } else {
            edcaAccess.start(queue.getFirst().packet, ac, context);
        }
    }

    private int deviceQueueDepth(EdcaState sender, AccessCategory category) {
        Deque<QueuedPacket> queue = deviceQueues.get(sender.forCategory(category));
        return queue == null ? 0 : queue.size();
    }

    private EdcaState.AcState senderAc(Packet packet) {
        return senderEdcaState(packet).forCategory(packet.type());
    }

    private EdcaState senderEdcaState(Packet packet) {
        // 即使由 SFU 统一处理事件，上行队列与竞争状态仍按实际发送 STA 区分。
        return packet.isUplink() ? stations.get(packet.staId()).edcaState() : edcaState;
    }

    private Deque<QueuedPacket> highestPriorityQueue() {
        if (!voQueue.isEmpty()) {
            return voQueue;
        }
        if (!viQueue.isEmpty()) {
            return viQueue;
        }
        return beQueue.isEmpty() ? bkQueue : beQueue;
    }

    private Deque<QueuedPacket> queueFor(AccessCategory type) {
        switch (type) {
            case VO:
                return voQueue;
            case VI:
                return viQueue;
            case BE:
                return beQueue;
            case BK:
                return bkQueue;
            default:
                throw new IllegalArgumentException("Unknown access category: " + type);
        }
    }

    private void checkActive(Packet packet) {
        if (active == null || active.packet != packet) {
            throw new IllegalStateException("Wi-Fi event does not match the reserved packet: " + packet.id());
        }
    }

    private static final class QueuedPacket {
        private final Packet packet;
        private final long arrivalTicks;
        private int retries;

        private QueuedPacket(Packet packet, long arrivalTicks) {
            this.packet = packet;
            this.arrivalTicks = arrivalTicks;
        }
    }

    private static final class AmpduAttempt {
        private final List<QueuedPacket> members;
        private final List<Packet> packets;
        private final int apepBytes;
        private final long ppduNs;
        private final long startTicks;
        private long bitmap;

        private AmpduAttempt(List<QueuedPacket> members, List<Packet> packets, int apepBytes, long ppduNs, long startTicks) {
            this.members = List.copyOf(members);
            this.packets = List.copyOf(packets);
            this.apepBytes = apepBytes;
            this.ppduNs = ppduNs;
            this.startTicks = startTicks;
        }

        private boolean acknowledged(int index) {
            return (bitmap & (1L << index)) != 0;
        }
    }

    private static final class Txop {
        private final Packet firstPacket;
        private final long startTicks;
        private final long limitNs;
        private int packetCount;

        private Txop(Packet firstPacket, long startTicks, long limitNs) {
            this.firstPacket = firstPacket;
            this.startTicks = startTicks;
            this.limitNs = limitNs;
        }
    }
}
