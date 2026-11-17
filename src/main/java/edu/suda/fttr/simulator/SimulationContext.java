package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.trace.TraceLogger;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 每次运行使用独立的事件队列、微秒时钟和日志。 */
public final class SimulationContext {
    private final EventQueue events = new EventQueue();
    private final TraceLogger trace;
    private final boolean multiPacket;
    private final EnumMap<AccessCategory, DelayStats> byClass = new EnumMap<>(AccessCategory.class);
    private final DelayStats overallDelay = new DelayStats();
    private final Map<Integer, UplinkStats> uplinkByStation = new HashMap<>();
    private long nowUs;
    private int deliveredPackets;
    private long deliveredBytes;
    private long lastDeliveryDelayUs;
    private long wifiBusyUs;
    private long wifiBusySinceUs;
    private int activeRadios;

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
        try {
            events.add(new Event(Math.addExact(nowUs, delayUs), type, packet, sfuId));
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Event time exceeds the clock range: " + type, ex);
        }
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
        recordDelivered(packet);
        trace.recordDelivery(packet, sta, nowUs);
    }

    public void recordUplinkDelivery(Packet packet, int sfuId) {
        recordDelivered(packet);
        UplinkStats stats = uplinkByStation.computeIfAbsent(packet.staId(), id -> new UplinkStats());
        stats.packets++;
        stats.bytes += packet.bytes();
        stats.delaySumUs += lastDeliveryDelayUs;
        trace.recordUplinkDelivery(packet, sfuId, nowUs);
    }

    private void recordDelivered(Packet packet) {
        deliveredPackets++;
        deliveredBytes += packet.bytes();
        lastDeliveryDelayUs = nowUs - packet.generationTimeUs();
    }

    public void recordWifiStart() {
        if (activeRadios++ == 0) {
            wifiBusySinceUs = nowUs;
        }
    }

    public void recordWifiService(Packet packet, int sfuId, long queueEntryUs, long startUs) {
        // 至少一台 SFU 正在无线发送或接收时计为忙。不同 SFU 的并行区间只计一次。
        if (--activeRadios == 0) {
            wifiBusyUs += nowUs - wifiBusySinceUs;
        }
        long queueWaitUs = startUs - queueEntryUs;
        if (packet.isUplink()) {
            // 上行此时只完成无线一跳，端到端时延等 MFU 接收后再统计。
            uplinkByStation.computeIfAbsent(packet.staId(), id -> new UplinkStats()).waitSumUs += queueWaitUs;
        } else {
            long delayUs = nowUs - packet.generationTimeUs();
            byClass.get(packet.type()).add(queueWaitUs, delayUs);
            overallDelay.add(queueWaitUs, delayUs);
        }
        trace.recordWifiService(packet, sfuId, queueEntryUs, startUs, nowUs);
    }

    public void recordEnqueue(Packet packet, int sfuId, int queueDepth) {
        trace.recordEnqueue(packet, nowUs, sfuId, queueDepth);
    }

    public void recordQueueSelection(Packet packet, int sfuId, int vo, int vi, int be, int bk) {
        trace.recordQueueSelection(packet, nowUs, sfuId, vo, vi, be, bk);
    }

    void recordSummary(List<Sfu> sfus, List<Sta> stations, boolean uplinkEnabled) {
        byClass.forEach((type, stats) -> trace.recordClassSummary(
                type, stats.count, stats.averageWaitUs(), stats.averageDelayUs()));
        trace.recordGlobalDelay(overallDelay.count, overallDelay.averageWaitUs(), overallDelay.averageDelayUs());
        for (Sfu sfu : sfus) {
            trace.recordSfuSummary(sfu, throughputMbps(sfu.sentBytes()));
        }
        for (Sta sta : stations) {
            trace.recordStaSummary(sta, throughputMbps(sta.receivedBytes()));
        }
        if (uplinkEnabled) {
            recordUplinkSummary(stations);
        }
        if (multiPacket) {
            trace.recordRunSummary(deliveredPackets, deliveredBytes, wifiBusyUs, nowUs);
        } else {
            trace.recordSinglePacketSummary(deliveredPackets, deliveredBytes, lastDeliveryDelayUs);
        }
    }

    private void recordUplinkSummary(List<Sta> stations) {
        int packets = 0;
        long bytes = 0;
        double delaySumUs = 0.0;
        UplinkStats empty = new UplinkStats();
        for (Sta sta : stations) {
            UplinkStats stats = uplinkByStation.getOrDefault(sta.id(), empty);
            trace.recordUplinkStaSummary(sta, stats.packets, stats.bytes, throughputMbps(stats.bytes),
                    average(stats.waitSumUs, stats.packets), average(stats.delaySumUs, stats.packets));
            packets += stats.packets;
            bytes += stats.bytes;
            delaySumUs += stats.delaySumUs;
        }
        trace.recordUplinkGlobalSummary(packets, bytes, average(delaySumUs, packets));
    }

    private static double average(double sum, int count) {
        return count == 0 ? 0.0 : sum / count;
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

    private static final class UplinkStats {
        private int packets;
        private long bytes;
        private double waitSumUs;
        private double delaySumUs;
    }
}
