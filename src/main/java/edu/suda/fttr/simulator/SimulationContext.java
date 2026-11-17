package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.trace.TraceLogger;

import java.util.EnumMap;
import java.util.List;

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
    private long wifiBusySinceUs;
    private int transmittingSfus;

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

    void scheduleInputEvent(Event event) {
        if (event.timeUs() < nowUs) {
            throw new IllegalArgumentException("Input event time must not precede the simulation clock");
        }
        events.add(event);
    }

    public void scheduleAfter(long delayUs, EventType type, Packet packet, int sfuId) {
        if (delayUs < 0) {
            throw new IllegalArgumentException("Event delay must be non-negative");
        }
        // 以当前事件发生的时刻为基准安排下一跳。零时延允许，但时钟不能倒退。
        events.add(new Event(Math.addExact(nowUs, delayUs), type, packet, sfuId));
    }

    public void scheduleWifiSelection(int sfuId) {
        events.add(new Event(nowUs, EventType.WIFI_SELECT_NEXT, null, sfuId));
    }

    Event nextEvent() {
        Event event = events.poll();
        if (event != null) {
            nowUs = event.timeUs();
            trace.recordEvent(event);
        }
        return event;
    }

    public void recordDelivery(Packet packet, Sta sta) {
        deliveredPackets++;
        deliveredBytes += packet.bytes();
        lastDeliveryDelayUs = nowUs - packet.mfuArrivalUs();
        trace.recordDelivery(packet, sta, nowUs);
    }

    public void recordWifiStart() {
        if (transmittingSfus++ == 0) {
            wifiBusySinceUs = nowUs;
        }
    }

    public void recordWifiService(Packet packet, int sfuId, long sfuArrivalUs, long startUs) {
        // 全局忙时按至少一台 SFU 正在发送的时间计算。并行区间只计一次。
        if (--transmittingSfus == 0) {
            wifiBusyUs += nowUs - wifiBusySinceUs;
        }
        long queueWaitUs = startUs - sfuArrivalUs;
        long delayUs = nowUs - packet.mfuArrivalUs();
        byClass.get(packet.type()).add(queueWaitUs, delayUs);
        overallDelay.add(queueWaitUs, delayUs);
        trace.recordWifiService(packet, sfuId, sfuArrivalUs, startUs, nowUs);
    }

    public void recordEnqueue(Packet packet, int sfuId, int queueDepth) {
        trace.recordEnqueue(packet, nowUs, sfuId, queueDepth);
    }

    public void recordQueueSelection(Packet packet, int sfuId, int vo, int vi, int be, int bk) {
        trace.recordQueueSelection(packet, nowUs, sfuId, vo, vi, be, bk);
    }

    void recordSummary(List<Sfu> sfus, List<Sta> stations) {
        byClass.forEach((type, stats) -> trace.recordClassSummary(
                type, stats.count, stats.averageWaitUs(), stats.averageDelayUs()));
        trace.recordGlobalDelay(overallDelay.count, overallDelay.averageWaitUs(), overallDelay.averageDelayUs());
        for (Sfu sfu : sfus) {
            trace.recordSfuSummary(sfu, throughputMbps(sfu.sentBytes()));
        }
        for (Sta sta : stations) {
            trace.recordStaSummary(sta, throughputMbps(sta.receivedBytes()));
        }
        if (multiPacket) {
            trace.recordRunSummary(deliveredPackets, deliveredBytes, wifiBusyUs, nowUs);
        } else {
            trace.recordSinglePacketSummary(deliveredPackets, deliveredBytes, lastDeliveryDelayUs);
        }
    }

    private double throughputMbps(long bytes) {
        // SFU 和 STA 统一采用 0 至最后事件的观察窗口。bit/μs 与 Mb/s 等值。
        return nowUs == 0 ? 0.0 : bytes * 8.0 / nowUs;
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
