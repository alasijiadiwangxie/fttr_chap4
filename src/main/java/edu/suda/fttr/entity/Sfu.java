package edu.suda.fttr.entity;

import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayDeque;
import java.util.Deque;

/** 单个 SFU 的 FIFO 下行队列和独占的无线发送资源。 */
public final class Sfu {
    private final WifiLink wifi;
    private final Deque<QueuedPacket> waiting = new ArrayDeque<>();
    private QueuedPacket active;
    private long txStartUs;

    public Sfu(WifiLink wifi) {
        this.wifi = wifi;
    }

    public void receive(Packet packet, SimulationContext context) {
        waiting.addLast(new QueuedPacket(packet, context.nowUs()));
        scheduleNext(context);
    }

    public void transmit(Packet packet, SimulationContext context) {
        checkActive(packet);
        txStartUs = context.nowUs();
        wifi.transmit(packet, context);
    }

    public void completeTransmission(Packet packet, SimulationContext context) {
        checkActive(packet);
        // 记录半开区间 [开始, 完成)。同刻启动的下一条报文不会与这一条重叠。
        context.recordWifiService(packet, active.arrivalUs, txStartUs);
        active = null;
        scheduleNext(context);
    }

    private void scheduleNext(SimulationContext context) {
        if (active != null || waiting.isEmpty()) {
            return;
        }
        // 先预留资源，再安排开始事件。其他同刻到达会看到已有报文占用，只能排队。
        active = waiting.removeFirst();
        context.scheduleAfter(0, EventType.WIFI_TX_START, active.packet);
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
