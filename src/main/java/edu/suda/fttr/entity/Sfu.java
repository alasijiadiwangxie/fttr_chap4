package edu.suda.fttr.entity;

import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.mac.EdcaAccess;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaState;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** 每台 SFU 按四类 FIFO 统一调度下行发送和上行接收。 */
public final class Sfu {
    private final int id;
    private final WifiLink wifi;
    private final WifiChannel channel = new WifiChannel();
    private final Map<Integer, Sta> stations;
    private final EdcaState edcaState;
    private final EdcaAccess edcaAccess;
    private final Deque<QueuedPacket> voQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> viQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> beQueue = new ArrayDeque<>();
    private final Deque<QueuedPacket> bkQueue = new ArrayDeque<>();
    private QueuedPacket active;
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
        this.id = id;
        this.wifi = wifi;
        this.stations = Map.copyOf(stations);
        this.edcaState = EdcaState.forSfu(id, edcaParameters);
        this.stations.values().forEach(sta -> sta.attachChannel(id, channel));
        this.edcaAccess = edcaCountdown ? new EdcaAccess(id, channel, edcaParameters) : null;
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
        // 上行报文仍在源 STA 等待。这里入队的是对同一无线资源的发送请求。
        Deque<QueuedPacket> queue = queueFor(packet.type());
        queue.addLast(new QueuedPacket(packet, context.nowTicks()));
        context.recordEnqueue(packet, id, queue.size());
        requestSelection(context);
    }

    public void transmit(Packet packet, SimulationContext context) {
        checkActive(packet);
        if (edcaAccess != null && edcaAccess.isWaiting()) {
            throw new IllegalStateException("Wi-Fi access wait is incomplete for packet " + packet.id());
        }
        channel.occupy();
        txStartTicks = context.nowTicks();
        context.recordWifiStart();
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
        // DATA–SIFS–ACK 共用同一段占用。只在 ACK 完成时释放，仅 DATA 模式在 DATA 完成时释放。
        channel.release();
        // 记录半开区间 [开始, 完成)。同刻启动的下一条报文不会与这一条重叠。
        context.recordWifiService(packet, id, active.arrivalTicks, txStartTicks, dataEndTicks);
        if (edcaAccess != null) {
            senderAc(packet).onTransmissionSuccess();
        }
        active = null;
        requestSelection(context);
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

    private EdcaState.AcState senderAc(Packet packet) {
        // 上行消耗实际发送端 STA 的随机流，虽然选队仍由 SFU 统一完成。
        EdcaState sender = packet.isUplink() ? stations.get(packet.staId()).edcaState() : edcaState;
        return sender.forCategory(packet.type());
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

        private QueuedPacket(Packet packet, long arrivalTicks) {
            this.packet = packet;
            this.arrivalTicks = arrivalTicks;
        }
    }
}
