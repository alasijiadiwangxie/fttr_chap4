package edu.suda.fttr.entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** 一台 AP 的在线观测。记录已发生的本地事件，队列镜像仅供统计使用。 */
public final class ApTrafficObserver {
    public static final long DEFAULT_PERIOD_NS = 1_000_000L;
    public static final double DEFAULT_EWMA_ALPHA = 0.25;

    public enum Kind {
        ENQUEUED, TX_START, RETRANSMIT_START, DELIVERED, CONFIRMED, REQUEUED, DROPPED, UL_RECEIVED
    }

    private enum Phase { QUEUED, IN_FLIGHT, DELIVERED }

    private final long periodNs;
    private final double ewmaAlpha;
    private final Map<Packet, Entry> activeDownlink = new HashMap<>();
    private final EnumMap<Packet.Direction, EnumMap<AccessCategory, Traffic>> traffic =
            new EnumMap<>(Packet.Direction.class);
    private long nextSequence;
    private long windowStartNs;
    private long lastEventNs;
    private long previousTxNs;
    private long previousRxNs;
    private boolean sampled;
    private List<Observation> latest = List.of();

    public ApTrafficObserver(long periodNs, double ewmaAlpha) {
        if (periodNs <= 0 || !(ewmaAlpha > 0.0 && ewmaAlpha <= 1.0)) {
            throw new IllegalArgumentException("AP traffic observation needs a positive period and 0 < alpha <= 1");
        }
        this.periodNs = periodNs;
        this.ewmaAlpha = ewmaAlpha;
        for (Packet.Direction direction : Packet.Direction.values()) {
            EnumMap<AccessCategory, Traffic> categories = new EnumMap<>(AccessCategory.class);
            for (AccessCategory category : AccessCategory.values()) {
                categories.put(category, new Traffic());
            }
            traffic.put(direction, categories);
        }
    }

    public long periodNs() { return periodNs; }

    /** 最近一次已经结算的快照，窗口中的后续事件不会修改它。 */
    public List<Observation> latest() { return latest; }

    /** 当前 AP 可见的下行待发 FIFO。每个 AC 内保留实际队首顺序，在途成员不在此列。 */
    public List<Packet> waitingDownlinkPackets() {
        List<Packet> packets = new ArrayList<>();
        for (Traffic local : traffic.get(Packet.Direction.DL).values()) {
            for (Entry entry : local.waiting.values()) {
                packets.add(entry.packet);
            }
        }
        return List.copyOf(packets);
    }

    public QueueEvent enqueue(Packet packet, long nowNs) {
        if (packet.isUplink()) {
            return null; // STA 的发送请求不是 AP 接收到的用户数据。
        }
        requireEventTime(nowNs);
        if (activeDownlink.containsKey(packet)) {
            throw invalidTransition(packet, "enqueue");
        }
        Traffic local = trafficFor(packet);
        Entry entry = new Entry(packet, nextSequence++, nowNs);
        activeDownlink.put(packet, entry);
        returnToQueue(local, entry);
        local.window.arrivalPackets++;
        local.window.arrivalBytes += packet.bytes();
        return event(packet, nowNs, Kind.ENQUEUED, local);
    }

    /** DATA 开始后成员移入在途集合，包含每一个聚合成员的实际发送尝试。 */
    public QueueEvent start(Packet packet, long nowNs) {
        if (packet.isUplink()) {
            return null;
        }
        requireEventTime(nowNs);
        Entry entry = requirePhase(packet, Phase.QUEUED);
        Traffic local = trafficFor(packet);
        local.waiting.remove(entry.sequence);
        local.waitingBytes -= packet.bytes();
        local.inFlightPackets++;
        local.inFlightBytes += packet.bytes();
        entry.phase = Phase.IN_FLIGHT;
        Kind kind = Kind.TX_START;
        if (entry.attempted) {
            local.window.retriedPackets++;
            local.window.retriedBytes += packet.bytes();
            kind = Kind.RETRANSMIT_START;
        }
        entry.attempted = true;
        return event(packet, nowNs, kind, local);
    }

    /** DATA 解码成功与 ACK/BA 确认是两个时间点。成功的 DL 成员仍在途，直到发送侧收到确认。 */
    public QueueEvent deliver(Packet packet, long nowNs) {
        requireEventTime(nowNs);
        Traffic local = trafficFor(packet);
        Kind kind;
        if (packet.isUplink()) {
            // AP 只观察已解码业务，无权提前知道 STA 积压或碰撞中未解码的 MPDU。
            local.window.arrivalPackets++;
            local.window.arrivalBytes += packet.bytes();
            kind = Kind.UL_RECEIVED;
        } else {
            Entry entry = requirePhase(packet, Phase.IN_FLIGHT);
            entry.phase = Phase.DELIVERED;
            local.awaitingConfirmPackets++;
            kind = Kind.DELIVERED;
        }
        local.window.deliveredPackets++;
        local.window.deliveredBytes += packet.bytes();
        return event(packet, nowNs, kind, local);
    }

    public QueueEvent confirm(Packet packet, long nowNs) {
        if (packet.isUplink()) {
            return null;
        }
        requireEventTime(nowNs);
        requirePhase(packet, Phase.DELIVERED);
        Traffic local = trafficFor(packet);
        removeInFlight(local, packet);
        activeDownlink.remove(packet);
        local.awaitingConfirmPackets--;
        local.window.confirmedPackets++;
        local.window.confirmedBytes += packet.bytes();
        return event(packet, nowNs, Kind.CONFIRMED, local);
    }

    public QueueEvent retry(Packet packet, long nowNs) {
        if (packet.isUplink()) {
            return null;
        }
        requireEventTime(nowNs);
        Entry entry = requirePhase(packet, Phase.IN_FLIGHT);
        Traffic local = trafficFor(packet);
        removeInFlight(local, packet);
        returnToQueue(local, entry);
        local.window.requeuedPackets++;
        return event(packet, nowNs, Kind.REQUEUED, local);
    }

    public QueueEvent drop(Packet packet, long nowNs) {
        if (packet.isUplink()) {
            return null;
        }
        requireEventTime(nowNs);
        requirePhase(packet, Phase.IN_FLIGHT);
        Traffic local = trafficFor(packet);
        removeInFlight(local, packet);
        activeDownlink.remove(packet);
        local.window.droppedPackets++;
        local.window.droppedBytes += packet.bytes();
        return event(packet, nowNs, Kind.DROPPED, local);
    }

    /** DL 统计实际启动的 PPDU 成员数，UL 统计成功解码的 PPDU 及其已解码成员。 */
    public void recordPpdu(Packet head, int depth, long nowNs) {
        requireEventTime(nowNs);
        if (depth <= 0) {
            throw new IllegalArgumentException("AP traffic observation requires a nonempty PPDU");
        }
        Window window = trafficFor(head).window;
        window.dataPpdus++;
        window.ppduMembers += depth;
    }

    /** 在处理完边界同刻的事件后采样。首窗包含 0，其余窗口为 (start, end]。 */
    public List<Observation> sample(long endNs, long totalTxNs, long totalRxNs) {
        long durationNs = endNs - windowStartNs;
        long txNs = totalTxNs - previousTxNs;
        long rxNs = totalRxNs - previousRxNs;
        if (endNs < lastEventNs || durationNs <= 0 || durationNs > periodNs
                || txNs < 0 || rxNs < 0 || txNs > durationNs - rxNs) {
            throw new IllegalArgumentException("AP observation boundary or cumulative airtime is inconsistent");
        }
        List<Observation> rows = new ArrayList<>();
        for (Packet.Direction direction : Packet.Direction.values()) {
            for (AccessCategory category : AccessCategory.values()) {
                Traffic local = traffic.get(direction).get(category);
                Window window = local.window;
                double arrivalPps = pps(window.arrivalPackets, durationNs);
                double arrivalMbps = mbps(window.arrivalBytes, durationNs);
                local.ewmaPps = updateEwma(local.ewmaPps, arrivalPps);
                local.ewmaMbps = updateEwma(local.ewmaMbps, arrivalMbps);
                rows.add(new Observation(windowStartNs, endNs, direction, category, local, txNs, rxNs));
                local.window = new Window();
            }
        }
        windowStartNs = endNs;
        previousTxNs = totalTxNs;
        previousRxNs = totalRxNs;
        sampled = true;
        latest = List.copyOf(rows);
        return latest;
    }

    private double updateEwma(double previous, double rate) {
        return sampled ? ewmaAlpha * rate + (1.0 - ewmaAlpha) * previous : rate;
    }

    private Traffic trafficFor(Packet packet) {
        return traffic.get(packet.direction()).get(packet.type());
    }

    private void requireEventTime(long nowNs) {
        if (nowNs < lastEventNs || (sampled && nowNs <= windowStartNs) || nowNs - windowStartNs > periodNs) {
            throw new IllegalStateException("AP traffic observation requires chronological events within the open window");
        }
        lastEventNs = nowNs;
    }

    private Entry requirePhase(Packet packet, Phase phase) {
        Entry entry = activeDownlink.get(packet);
        if (entry == null || entry.phase != phase) {
            throw invalidTransition(packet, phase.name());
        }
        return entry;
    }

    private static IllegalStateException invalidTransition(Packet packet, String expected) {
        return new IllegalStateException("Unexpected AP observation state for packet " + packet.id() + ": " + expected);
    }

    private static void returnToQueue(Traffic local, Entry entry) {
        // BA 失败成员回到原 FIFO 前部。用原始入队序号保持同刻顺序和队首等待时间。
        entry.phase = Phase.QUEUED;
        local.waiting.put(entry.sequence, entry);
        local.waitingBytes += entry.packet.bytes();
    }

    private static void removeInFlight(Traffic local, Packet packet) {
        local.inFlightPackets--;
        local.inFlightBytes -= packet.bytes();
    }

    private static QueueEvent event(Packet packet, long nowNs, Kind kind, Traffic local) {
        return new QueueEvent(nowNs, packet, kind, new QueueState(local));
    }

    private static double pps(long packets, long durationNs) { return packets * 1_000_000_000.0 / durationNs; }
    private static double mbps(long bytes, long durationNs) { return bytes * 8_000.0 / durationNs; }

    private static final class Entry {
        private final Packet packet;
        private final long sequence;
        private final long arrivalNs;
        private Phase phase;
        private boolean attempted;

        private Entry(Packet packet, long sequence, long arrivalNs) {
            this.packet = packet;
            this.sequence = sequence;
            this.arrivalNs = arrivalNs;
        }
    }

    private static final class Traffic {
        private final NavigableMap<Long, Entry> waiting = new TreeMap<>();
        private long waitingBytes;
        private long inFlightPackets;
        private long inFlightBytes;
        private long awaitingConfirmPackets;
        private double ewmaPps;
        private double ewmaMbps;
        private Window window = new Window();
    }

    /** 采样时移交给快照，后续事件只写下一个窗口。 */
    private static final class Window {
        private long arrivalPackets;
        private long arrivalBytes;
        private long deliveredPackets;
        private long deliveredBytes;
        private long confirmedPackets;
        private long confirmedBytes;
        private long retriedPackets;
        private long retriedBytes;
        private long requeuedPackets;
        private long droppedPackets;
        private long droppedBytes;
        private long dataPpdus;
        private long ppduMembers;
    }

    private static final class QueueState {
        private final long queuePackets;
        private final long queueBytes;
        private final long inFlightPackets;
        private final long inFlightBytes;
        private final long awaitingConfirmPackets;

        private QueueState(Traffic local) {
            queuePackets = local.waiting.size();
            queueBytes = local.waitingBytes;
            inFlightPackets = local.inFlightPackets;
            inFlightBytes = local.inFlightBytes;
            awaitingConfirmPackets = local.awaitingConfirmPackets;
        }
    }

    public static final class QueueEvent {
        private final long timeNs;
        private final Packet packet;
        private final Kind kind;
        private final QueueState queue;

        private QueueEvent(long timeNs, Packet packet, Kind kind, QueueState queue) {
            this.timeNs = timeNs;
            this.packet = packet;
            this.kind = kind;
            this.queue = queue;
        }

        public long timeNs() { return timeNs; }
        public Packet packet() { return packet; }
        public Kind kind() { return kind; }
        public long queuePackets() { return queue.queuePackets; }
        public long queueBytes() { return queue.queueBytes; }
        public long inFlightPackets() { return queue.inFlightPackets; }
        public long inFlightBytes() { return queue.inFlightBytes; }
        public long awaitingConfirmPackets() { return queue.awaitingConfirmPackets; }
    }

    public static final class Observation {
        private final long startNs;
        private final long endNs;
        private final Packet.Direction direction;
        private final AccessCategory category;
        private final QueueState queue;
        private final long headWaitNs;
        private final Window window;
        private final double ewmaArrivalPps;
        private final double ewmaArrivalMbps;
        private final long txNs;
        private final long rxNs;

        private Observation(long startNs, long endNs, Packet.Direction direction, AccessCategory category,
                            Traffic local, long txNs, long rxNs) {
            this.startNs = startNs;
            this.endNs = endNs;
            this.direction = direction;
            this.category = category;
            queue = new QueueState(local);
            headWaitNs = local.waiting.isEmpty() ? -1 : endNs - local.waiting.firstEntry().getValue().arrivalNs;
            window = local.window;
            ewmaArrivalPps = local.ewmaPps;
            ewmaArrivalMbps = local.ewmaMbps;
            this.txNs = txNs;
            this.rxNs = rxNs;
        }

        public long startNs() { return startNs; }
        public long endNs() { return endNs; }
        public Packet.Direction direction() { return direction; }
        public AccessCategory category() { return category; }
        public long queuePackets() { return queue.queuePackets; }
        public long queueBytes() { return queue.queueBytes; }
        public long headWaitNs() { return headWaitNs; }
        public long inFlightPackets() { return queue.inFlightPackets; }
        public long inFlightBytes() { return queue.inFlightBytes; }
        public long awaitingConfirmPackets() { return queue.awaitingConfirmPackets; }
        public long arrivalPackets() { return window.arrivalPackets; }
        public long arrivalBytes() { return window.arrivalBytes; }
        public long deliveredPackets() { return window.deliveredPackets; }
        public long deliveredBytes() { return window.deliveredBytes; }
        public long confirmedPackets() { return window.confirmedPackets; }
        public long confirmedBytes() { return window.confirmedBytes; }
        public long retriedPackets() { return window.retriedPackets; }
        public long retriedBytes() { return window.retriedBytes; }
        public long requeuedPackets() { return window.requeuedPackets; }
        public long droppedPackets() { return window.droppedPackets; }
        public long droppedBytes() { return window.droppedBytes; }
        public long dataPpdus() { return window.dataPpdus; }
        public long ppduMembers() { return window.ppduMembers; }
        public double averageDepth() { return window.dataPpdus == 0 ? 0.0 : (double) window.ppduMembers / window.dataPpdus; }
        public double arrivalPps() { return pps(window.arrivalPackets, endNs - startNs); }
        public double arrivalMbps() { return mbps(window.arrivalBytes, endNs - startNs); }
        public double deliveredPps() { return pps(window.deliveredPackets, endNs - startNs); }
        public double deliveredMbps() { return mbps(window.deliveredBytes, endNs - startNs); }
        /** 当前窗口内已结算下行业务的成功比例。没有结算样本或上行时返回 -1。 */
        public double deliverySuccessRatio() {
            long terminal = window.confirmedPackets + window.droppedPackets;
            return direction == Packet.Direction.UL || terminal == 0 ? -1.0
                    : (double) window.confirmedPackets / terminal;
        }
        public double ewmaArrivalPps() { return ewmaArrivalPps; }
        public double ewmaArrivalMbps() { return ewmaArrivalMbps; }
        public long txNs() { return txNs; }
        public long rxNs() { return rxNs; }
    }
}
