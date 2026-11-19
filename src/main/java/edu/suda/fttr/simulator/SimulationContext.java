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

/** 每次运行使用独立的事件队列、整数时钟和日志。 */
public final class SimulationContext {
    private final EventQueue events = new EventQueue();
    private final TraceLogger trace;
    private final boolean multiPacket;
    private final boolean nanosecondClock;
    private final boolean wifiRetryEnabled;
    private final EnumMap<AccessCategory, DelayStats> byClass = new EnumMap<>(AccessCategory.class);
    private final DelayStats overallDelay = new DelayStats();
    private final Map<Integer, UplinkStats> uplinkByStation = new HashMap<>();
    private long nowTicks;
    private int deliveredPackets;
    private long deliveredBytes;
    private long lastDeliveryDelayTicks;
    private long wifiBusyTicks;
    private long wifiBusySinceTicks;
    private int activeRadios;
    private long wifiCollisionEvents;
    private int wifiCollisionDroppedPackets;
    private long wifiCollisionDroppedBytes;
    private long wifiRetransmissions;
    private long wifiAckTimeouts;
    private int wifiRetryLimitDrops;
    private int wifiTxops;
    private int wifiTxopPackets;
    private double totalWifiTxopDurationUs;

    SimulationContext(TraceLogger trace, boolean multiPacket, boolean nanosecondClock) {
        this(trace, multiPacket, nanosecondClock, false);
    }

    SimulationContext(TraceLogger trace, boolean multiPacket, boolean nanosecondClock, boolean wifiRetryEnabled) {
        this.trace = trace.withNanosecondClock(nanosecondClock);
        this.multiPacket = multiPacket;
        this.nanosecondClock = nanosecondClock;
        this.wifiRetryEnabled = wifiRetryEnabled;
        for (AccessCategory type : AccessCategory.values()) {
            byClass.put(type, new DelayStats());
        }
    }

    public long nowTicks() {
        return nowTicks;
    }

    /** 先用整数刻度做时间差，再换算到统计所用的微秒。 */
    public double ticksToMicroseconds(long ticks) {
        return nanosecondClock ? ticks / 1_000.0 : (double) ticks;
    }

    public long elapsedSinceGenerationTicks(Packet packet) {
        return nowTicks - microsecondsToTicks(packet.generationTimeUs());
    }

    void scheduleInputEvent(Event event) {
        long timeTicks = microsecondsToTicks(event.timeUs());
        if (timeTicks < nowTicks) {
            throw new IllegalArgumentException("Input event time must not precede the simulation clock");
        }
        // 手动和泊松输入仍用整数微秒，入队时才统一到本次运行的时钟刻度。
        events.add(nanosecondClock ? Event.atTicks(timeTicks, true, event.type(), event.packet(), event.sfuId()) : event);
    }

    /** 将整数微秒转换为当前运行使用的整数时钟刻度。 */
    public long microsecondsToTicks(long timeUs) {
        try {
            return nanosecondClock ? Math.multiplyExact(timeUs, 1_000) : timeUs;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Time in microseconds exceeds the nanosecond clock range", ex);
        }
    }

    public Event scheduleAfter(long delayUs, EventType type, Packet packet, int sfuId) {
        return scheduleAfterTicks(microsecondsToTicks(delayUs), type, packet, sfuId);
    }

    /** HE PPDU 和 PON 共享链路的时间直接使用纳秒刻度，保留小数微秒部分。 */
    public void scheduleAfterNanoseconds(long delayNs, EventType type, Packet packet, int sfuId) {
        if (!nanosecondClock) {
            throw new IllegalStateException("Nanosecond delays require a nanosecond clock");
        }
        scheduleAfterTicks(delayNs, type, packet, sfuId);
    }

    /** 使用当前仿真时钟的原始刻度安排事件。 */
    public Event scheduleAfterTicks(long delayTicks, EventType type, Packet packet, int sfuId) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Event delay must be non-negative");
        }
        // 以当前事件发生的时刻为基准安排下一跳。零时延允许，但时钟不能倒退。
        try {
            Event event = Event.atTicks(Math.addExact(nowTicks, delayTicks), nanosecondClock, type, packet, sfuId);
            events.add(event);
            return event;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Event time exceeds the clock range: " + type, ex);
        }
    }

    public void cancel(Event event) {
        events.cancel(event);
    }

    public void scheduleWifiSelection(int sfuId) {
        events.add(Event.atTicks(nowTicks, nanosecondClock, EventType.WIFI_SELECT_NEXT, null, sfuId));
    }

    Event nextEvent() {
        Event event = events.poll();
        if (event != null) {
            nowTicks = event.timeTicks();
            trace.recordEvent(event);
        }
        return event;
    }

    public void recordDelivery(Packet packet, Sta sta) {
        recordDelivered(packet);
        trace.recordDelivery(packet, sta, nowTicks);
    }

    public void recordUplinkDelivery(Packet packet, int sfuId) {
        recordDelivered(packet);
        UplinkStats stats = uplinkByStation.computeIfAbsent(packet.staId(), id -> new UplinkStats());
        stats.packets++;
        stats.bytes += packet.bytes();
        stats.delaySumUs += ticksToMicroseconds(lastDeliveryDelayTicks);
        trace.recordUplinkDelivery(packet, sfuId, nowTicks, lastDeliveryDelayTicks);
    }

    private void recordDelivered(Packet packet) {
        deliveredPackets++;
        deliveredBytes += packet.bytes();
        lastDeliveryDelayTicks = elapsedSinceGenerationTicks(packet);
    }

    public void recordPonDownlinkEnqueue(Packet packet, int sfuId) {
        trace.recordPonDownlinkEnqueue(packet, sfuId, nowTicks);
    }

    public void recordPonUplinkEnqueue(Packet packet, int sfuId) {
        trace.recordPonUplinkEnqueue(packet, sfuId, nowTicks);
    }

    public void recordPonUplinkGrant(Packet packet, int sfuId, long frameId, long enqueueNs, long startNs, long endNs) {
        trace.recordPonUplinkGrant(packet, sfuId, frameId, enqueueNs, nowTicks, startNs, endNs);
    }

    public void recordPonUplinkFrame(long frameId, long frameStartNs, long frameEndNs, int packets, long busyNs) {
        trace.recordPonUplinkFrame(frameId, frameStartNs, frameEndNs, packets, busyNs);
    }

    public void recordPonUplinkFrames(long frames, long periodNs, long packets, long busyNs, double grantWaitUs) {
        trace.recordPonUplinkFrames(frames, periodNs, packets, busyNs, grantWaitUs);
    }

    public void recordPonSummary(Packet.Direction direction, long sentPackets, double totalQueueWaitTicks,
                                 long busyTicks) {
        // 分母沿用其他全局统计，以仿真起点到最后一个事件为窗口。
        double averageWaitUs = sentPackets == 0 ? 0.0 : totalQueueWaitTicks / sentPackets / 1_000.0;
        double averageSerializationUs = sentPackets == 0 ? 0.0 : ticksToMicroseconds(busyTicks) / sentPackets;
        double utilization = nowTicks == 0 ? 0.0 : (double) busyTicks / nowTicks;
        trace.recordPonSummary(direction, sentPackets, averageWaitUs, averageSerializationUs, busyTicks, nowTicks,
                utilization);
    }

    public void recordWifiStart() {
        if (activeRadios++ == 0) {
            wifiBusySinceTicks = nowTicks;
        }
    }

    public void recordWifiDataEnd(Packet packet, int sfuId) {
        trace.recordWifiDataEnd(packet, sfuId, nowTicks);
    }

    public void recordWifiService(Packet packet, int sfuId, long queueEntryTicks, long startTicks, long dataEndTicks,
                                  boolean channelReleased) {
        if (channelReleased) {
            recordWifiEnd();
        }
        double queueWaitUs = ticksToMicroseconds(startTicks - queueEntryTicks);
        long endToEndTicks = 0;
        if (packet.isUplink()) {
            // 上行端到端时延由 MFU 的收包时刻统计，无线一跳只记录排队时延。
            uplinkByStation.computeIfAbsent(packet.staId(), id -> new UplinkStats()).waitSumUs += queueWaitUs;
        } else {
            // 下行端到端时延止于 STA 收齐 DATA，服务时长止于无线资源释放。
            endToEndTicks = dataEndTicks - microsecondsToTicks(packet.generationTimeUs());
            double delayUs = ticksToMicroseconds(endToEndTicks);
            byClass.get(packet.type()).add(queueWaitUs, delayUs);
            overallDelay.add(queueWaitUs, delayUs);
        }
        trace.recordWifiService(packet, sfuId, queueEntryTicks, startTicks, nowTicks, endToEndTicks);
    }

    public void recordWifiTxopStart(Packet packet, int sfuId, long limitNs) {
        trace.recordWifiTxopStart(packet, sfuId, nowTicks, limitNs);
    }

    public void recordWifiTxopEnd(Packet packet, int sfuId, long startTicks, long limitNs, int packetCount) {
        long durationTicks = nowTicks - startTicks;
        wifiTxops++;
        wifiTxopPackets += packetCount;
        totalWifiTxopDurationUs += ticksToMicroseconds(durationTicks);
        trace.recordWifiTxopEnd(packet, sfuId, nowTicks, limitNs, durationTicks, packetCount);
    }

    public void recordWifiCollisionStart(List<Packet> packets, int sfuId) {
        // 碰撞中的多次发射仍只占用一份 SFU 信道，忙时从共同起点算到最后一个 DATA 结束。
        recordWifiStart();
        wifiCollisionEvents++;
        for (Packet packet : packets) {
            trace.recordCollisionPacket(packet, sfuId, nowTicks, "WIFI_COLLISION_START");
        }
    }

    public void recordWifiCollisionDrop(Packet packet, int sfuId) {
        recordCollisionLoss(packet);
        trace.recordCollisionPacket(packet, sfuId, nowTicks, "WIFI_COLLISION_DROP");
    }

    public void recordWifiCollisionDataEnd(Packet packet, int sfuId) {
        trace.recordCollisionPacket(packet, sfuId, nowTicks, "WIFI_COLLISION_DATA_END");
    }

    public void recordWifiMacAttempt(Packet packet, int sfuId, int retries) {
        if (retries > 0) {
            wifiRetransmissions++;
        }
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_MAC_ATTEMPT", retries);
    }

    public void recordWifiAckTimeout(Packet packet, int sfuId, int retries) {
        wifiAckTimeouts++;
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_ACK_TIMEOUT", retries);
    }

    public void recordWifiMacRetry(Packet packet, int sfuId, int retries, int cwBefore, int cwAfter, int backoff) {
        trace.recordMacRetry(packet, sfuId, nowTicks, retries, cwBefore, cwAfter, backoff);
    }

    public void recordWifiRetryLimitDrop(Packet packet, int sfuId, int retries) {
        wifiRetryLimitDrops++;
        recordCollisionLoss(packet);
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_RETRY_LIMIT_DROP", retries);
    }

    private void recordCollisionLoss(Packet packet) {
        wifiCollisionDroppedPackets++;
        wifiCollisionDroppedBytes += packet.bytes();
    }

    public void recordWifiMacSuccess(Packet packet, int sfuId, int retries) {
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_MAC_SUCCESS", retries);
    }

    public void recordWifiCollisionEnd(int sfuId) {
        recordWifiEnd();
        trace.recordCollisionEnd(sfuId, nowTicks);
    }

    private void recordWifiEnd() {
        // 不同 SFU 的忙区间可以重叠，按区间并集计入全局忙时。
        if (--activeRadios == 0) {
            wifiBusyTicks += nowTicks - wifiBusySinceTicks;
        }
    }

    public void recordEnqueue(Packet packet, int sfuId, int queueDepth) {
        trace.recordEnqueue(packet, nowTicks, sfuId, queueDepth);
    }

    public void recordQueueSelection(Packet packet, int sfuId, int vo, int vi, int be, int bk) {
        trace.recordQueueSelection(packet, nowTicks, sfuId, vo, vi, be, bk);
    }

    public void recordVirtualCollision(Packet loser, Packet winner, int sfuId, int cwBefore, int cwAfter, int backoff) {
        trace.recordVirtualCollision(loser, winner, sfuId, nowTicks, cwBefore, cwAfter, backoff);
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
        if (wifiCollisionEvents > 0) {
            trace.recordCollisionSummary(wifiCollisionEvents, wifiCollisionDroppedPackets, wifiCollisionDroppedBytes);
        }
        if (wifiRetryEnabled) {
            trace.recordRetrySummary(wifiRetransmissions, wifiAckTimeouts, wifiRetryLimitDrops);
        }
        if (wifiTxops > 0) {
            trace.recordTxopSummary(wifiTxops, totalWifiTxopDurationUs, wifiTxopPackets);
        }
        if (multiPacket) {
            trace.recordRunSummary(deliveredPackets, deliveredBytes, wifiBusyTicks, nowTicks);
        } else {
            trace.recordSinglePacketSummary(deliveredPackets, deliveredBytes, lastDeliveryDelayTicks);
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
        return nowTicks == 0 ? 0.0 : bytes * 8.0 / ticksToMicroseconds(nowTicks);
    }

    private static final class DelayStats {
        private int count;
        // 时刻仍用 long，累计值用于求均值，避免多包时延相加超过时钟整数范围。
        private double waitSumUs;
        private double delaySumUs;

        void add(double waitUs, double delayUs) {
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
