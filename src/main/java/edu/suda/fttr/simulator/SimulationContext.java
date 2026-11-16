package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.trace.TraceLogger;

/** 每次运行使用独立的事件队列、微秒时钟和日志。 */
public final class SimulationContext {
    private final EventQueue events = new EventQueue();
    private final TraceLogger trace;
    private final boolean multiPacket;
    private long nowUs;
    private int deliveredPackets;
    private long deliveredBytes;
    private long lastDeliveryDelayUs;
    private long wifiBusyUs;

    SimulationContext(TraceLogger trace, boolean multiPacket) {
        this.trace = trace;
        this.multiPacket = multiPacket;
    }

    public long nowUs() {
        return nowUs;
    }

    public void scheduleAfter(long delayUs, EventType type, Packet packet) {
        if (delayUs < 0) {
            throw new IllegalArgumentException("Event delay must be non-negative");
        }
        // 以当前事件发生的时刻为基准安排下一跳。零时延允许，但时钟不能倒退。
        events.add(new Event(Math.addExact(nowUs, delayUs), type, packet));
    }

    Event nextEvent() {
        Event event = events.poll();
        if (event != null) {
            nowUs = event.timeUs();
            trace.recordEvent(event);
        }
        return event;
    }

    public void recordDelivery(Packet packet) {
        deliveredPackets++;
        deliveredBytes += packet.bytes();
        lastDeliveryDelayUs = nowUs - packet.mfuArrivalUs();
    }

    public void recordWifiService(Packet packet, long sfuArrivalUs, long startUs) {
        wifiBusyUs += nowUs - startUs;
        if (multiPacket) {
            trace.recordWifiService(packet, sfuArrivalUs, startUs, nowUs);
        }
    }

    void recordSummary() {
        if (multiPacket) {
            trace.recordRunSummary(deliveredPackets, deliveredBytes, wifiBusyUs, nowUs);
        } else {
            trace.recordSinglePacketSummary(deliveredPackets, deliveredBytes, lastDeliveryDelayUs);
        }
    }
}
