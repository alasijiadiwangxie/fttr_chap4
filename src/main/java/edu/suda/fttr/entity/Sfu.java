package edu.suda.fttr.entity;

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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每台 SFU 统一协调本信道上的下行和关联 STA 的上行。 */
public final class Sfu {
    private final int id;
    private final WifiLink wifi;
    private final WifiChannel channel = new WifiChannel();
    private final Map<Integer, Sta> stations;
    private final EdcaState edcaState;
    private final EdcaParameters edcaParameters;
    private final EdcaAccess edcaAccess;
    private final boolean multiAc;
    private final WifiRetryPolicy retryPolicy;
    // 以实际发送设备的 AC 状态为键。每个键独立维护 FIFO 与队首的接入计时。
    private final Map<EdcaState.AcState, Deque<QueuedPacket>> deviceQueues = new HashMap<>();
    // 同一次碰撞中仍在发射 DATA 的报文。最后一条结束时才释放公共信道。
    private final Set<Packet> collidedData = new HashSet<>();
    private final Set<Packet> awaitingAck = new HashSet<>();
    private final Deque<QueuedPacket> voQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> viQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> beQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> bkQueue = new ArrayDeque<>();
    private QueuedPacket active;
    private Txop txop;
    private boolean selectionPending;
    private long txStartTicks;
    private long dataEndTicks;
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
    }

    public int id() {
        return id;
    }

    public EdcaState edcaState() {
        return edcaState;
    }

    /** 查询本 SFU 与关联 STA 共用的信道。 */
    public WifiChannel.State ccaState() {
        return channel.state();
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
        startData(packet, context);
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
        if (retryPolicy != null) {
            context.recordWifiMacAttempt(packet, id, active.retries);
        }
        wifi.transmit(packet, id, context);
    }

    public void receiveData(Packet packet, SimulationContext context) {
        checkActive(packet);
        dataEndTicks = context.nowTicks();
        boolean ackPending = wifi.scheduleAck(packet, id, context);
        if (packet.isUplink()) {
            // SFU 已收齐上行 DATA，独立的光纤链路可立即转发。无线资源继续留给本次 ACK。
            context.scheduleAfter(0, EventType.FIBER_TX_START, packet, id);
        } else {
            stations.get(packet.destinationStaId()).receive(packet, context);
            sentPackets++;
            sentBytes += packet.bytes();
            queueWaitSumUs += context.ticksToMicroseconds(txStartTicks - active.arrivalTicks);
        }
        if (!ackPending) {
            completeTransmission(packet, context);
        }
    }

    public void transmitAck(Packet packet, SimulationContext context) {
        checkActive(packet);
        wifi.transmitAck(packet, id, context);
    }

    public void completeTransmission(Packet packet, SimulationContext context) {
        checkActive(packet);
        EdcaState.AcState ac = edcaAccess == null ? null : senderAc(packet);
        QueuedPacket next = null;
        if (multiAc) {
            Deque<QueuedPacket> queue = deviceQueues.get(ac);
            queue.removeFirst();
            next = queue.peekFirst();
        }
        boolean continues = txop != null && next != null && fitsInTxop(next.packet, context.nowTicks());
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
        if (multiAc) {
            startDeviceQueueHead(ac, context);
        } else {
            requestSelection(context);
        }
    }

    private boolean fitsInTxop(Packet packet, long nowTicks) {
        long remainingNs = txop.limitNs - (nowTicks - txop.startTicks);
        long sifsNs = wifi.sifsNs();
        return remainingNs >= sifsNs && wifi.exchangeDurationNs(packet) <= remainingNs - sifsNs;
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
                if (retryPolicy != null) {
                    edcaAccess.suspendSender(packet);
                    context.recordWifiMacAttempt(packet, id, deviceQueues.get(senderAc(packet)).getFirst().retries);
                }
                wifi.transmitCollidedData(packet, id, context);
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
        if (retryPolicy == null) {
            context.recordWifiCollisionDrop(packet, id);
            EdcaState.AcState ac = senderAc(packet);
            ac.clearBackoff();
            advanceDeviceQueue(ac, context);
        } else {
            // ACK 从本设备的 DATA 结束起等待。射频占用和发送设备的等待状态分别管理。
            awaitingAck.add(packet);
            context.recordWifiCollisionDataEnd(packet, id);
            context.scheduleAfter(retryPolicy.ackTimeoutUs(), EventType.WIFI_ACK_TIMEOUT, packet, id);
        }
        if (collidedData.isEmpty()) {
            channel.release();
            context.recordWifiCollisionEnd(id);
        }
    }

    public void onAckTimeout(Packet packet, SimulationContext context) {
        if (!awaitingAck.remove(packet)) {
            throw new IllegalStateException("No ACK pending for packet " + packet.id());
        }
        EdcaState.AcState ac = senderAc(packet);
        QueuedPacket queued = deviceQueues.get(ac).getFirst();
        context.recordWifiAckTimeout(packet, id, queued.retries);
        if (queued.retries < retryPolicy.maxRetries()) {
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
