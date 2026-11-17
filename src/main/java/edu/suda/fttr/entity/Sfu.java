package edu.suda.fttr.entity;

import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** 每台 SFU 按四类 FIFO 统一调度下行发送和上行接收。 */
public final class Sfu {
    private final int id;
    private final WifiLink wifi;
    private final Map<Integer, Sta> stations;
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
        this.id = id;
        this.wifi = wifi;
        this.stations = Map.copyOf(stations);
    }

    public int id() {
        return id;
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
        // 记录半开区间 [开始, 完成)。同刻启动的下一条报文不会与这一条重叠。
        context.recordWifiService(packet, id, active.arrivalTicks, txStartTicks, dataEndTicks);
        active = null;
        requestSelection(context);
    }

    private void requestSelection(SimulationContext context) {
        if (active != null || selectionPending
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
        // 先出队并预留资源，再安排发送。启用立即 ACK 时，预留包括 DATA、SIFS、ACK。
        active = queue.removeFirst();
        context.scheduleAfter(0, EventType.WIFI_TX_START, active.packet, id);
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
