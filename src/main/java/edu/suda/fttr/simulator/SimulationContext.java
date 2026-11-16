package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.trace.TraceLogger;

import java.util.EnumMap;

/** 每次运行使用独立的事件队列、微秒时钟和日志。 */
public final class SimulationContext {
    private final EventQueue events = new EventQueue();
    private final TraceLogger trace;
    private final boolean multiPacket;
    private final EnumMap<AccessCategory, DelayStats> byClass = new EnumMap<>(AccessCategory.class);
    private final DelayStats overallDelay = new DelayStats();
    private long nowUs;
    private int deliveredPackets;
    private long deliveredBytes;
    private long lastDeliveryDelayUs;
    private long wifiBusyUs;

    SimulationContext(TraceLogger trace, boolean multiPacket) {
        this.trace = trace;
        this.multiPacket = multiPacket;
        for (AccessCategory type : AccessCategory.values()) {
            byClass.put(type, new DelayStats());
        }
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

    public void scheduleWifiSelection() {
        events.add(new Event(nowUs, EventType.WIFI_SELECT_NEXT, null));
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
        long queueWaitUs = startUs - sfuArrivalUs;
        long delayUs = nowUs - packet.mfuArrivalUs();
        byClass.get(packet.type()).add(queueWaitUs, delayUs);
        overallDelay.add(queueWaitUs, delayUs);
        trace.recordWifiService(packet, sfuArrivalUs, startUs, nowUs);
    }

    public void recordEnqueue(Packet packet, int queueDepth) {
        trace.recordEnqueue(packet, nowUs, queueDepth);
    }

    public void recordQueueSelection(Packet packet, int vo, int vi, int be, int bk) {
        trace.recordQueueSelection(packet, nowUs, vo, vi, be, bk);
    }

    void recordSummary() {
        byClass.forEach((type, stats) -> trace.recordClassSummary(
                type, stats.count, stats.averageWaitUs(), stats.averageDelayUs()));
        trace.recordGlobalDelay(overallDelay.count, overallDelay.averageWaitUs(), overallDelay.averageDelayUs());
        if (multiPacket) {
            trace.recordRunSummary(deliveredPackets, deliveredBytes, wifiBusyUs, nowUs);
        } else {
            trace.recordSinglePacketSummary(deliveredPackets, deliveredBytes, lastDeliveryDelayUs);
        }
    }

    private static final class DelayStats {
        private int count;
        // 时刻仍用 long，累计值用于求均值，避免多包时延相加超过时钟整数范围。
        private double waitSumUs;
        private double delaySumUs;

        void add(long waitUs, long delayUs) {
            count++;
            waitSumUs += waitUs;
            delaySumUs += delayUs;
        }

        double averageWaitUs() {
            return count == 0 ? 0.0 : waitSumUs / count;
        }

        double averageDelayUs() {
            return count == 0 ? 0.0 : delaySumUs / count;
        }
    }
}
