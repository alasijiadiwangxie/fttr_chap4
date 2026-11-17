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
    private long txStartUs;
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
        queue.addLast(new QueuedPacket(packet, context.nowUs()));
        context.recordEnqueue(packet, id, queue.size());
        requestSelection(context);
    }

    public void transmit(Packet packet, SimulationContext context) {
        checkActive(packet);
        txStartUs = context.nowUs();
        context.recordWifiStart();
        wifi.transmit(packet, id, context);
    }

    public void completeTransmission(Packet packet, SimulationContext context) {
        checkActive(packet);
        if (packet.isUplink()) {
            // SFU 此时才收齐上行报文。光侧作为独立的一跳开始转发，无线资源可以立即接力。
            context.scheduleAfter(0, EventType.FIBER_TX_START, packet, id);
        } else {
            stations.get(packet.destinationStaId()).receive(packet, context);
            sentPackets++;
            sentBytes += packet.bytes();
            queueWaitSumUs += txStartUs - active.arrivalUs;
        }
        // 记录半开区间 [开始, 完成)。同刻启动的下一条报文不会与这一条重叠。
        context.recordWifiService(packet, id, active.arrivalUs, txStartUs);
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
        // 先出队并预留资源，再安排发送。预留持续到无线接收端完整收到该报文。
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
        private final long arrivalUs;

        private QueuedPacket(Packet packet, long arrivalUs) {
            this.packet = packet;
            this.arrivalUs = arrivalUs;
        }
    }
}
