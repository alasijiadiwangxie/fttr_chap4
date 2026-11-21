package edu.suda.fttr.link;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SFU 本地的完整报文预算装载预演。只接收不可变的 FIFO 数值视图，不持有真实队列、
 * MFU 需求账本或事件调度器，因此装载结果不会自动变成出队、授权或发送动作。
 */
public final class PonGrantPacketFiller {
    private final BigDecimal rateMbps;
    private final Map<Long, Integer> allocationOwners;

    public PonGrantPacketFiller(BigDecimal rateMbps, Map<Long, Integer> allocationOwners) {
        this.rateMbps = Objects.requireNonNull(rateMbps, "PON uplink rate");
        if (rateMbps.signum() <= 0) {
            throw new IllegalArgumentException("PON grant filler rate must be positive");
        }
        Objects.requireNonNull(allocationOwners, "Allocation topology");
        for (Map.Entry<Long, Integer> entry : allocationOwners.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 1
                    || entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("Allocation and SFU IDs must be positive");
            }
        }
        // 关联关系只取自显式拓扑；不能通过 allocationId 的数值推断它属于哪台 SFU。
        this.allocationOwners = Map.copyOf(allocationOwners);
    }

    /** 由本地端口核对链路配置；BigDecimal 本身不可变，不会泄露可修改状态。 */
    public BigDecimal rateMbps() {
        return rateMbps;
    }

    /**
     * 使用视图捕获时刻的本地队列预演一个窗口。slotStartNs 仅标识未来窗口，不会让
     * 捕获时刻之后的报文提前变成可用报文。同刻入队是否已完成，由调用点的事件顺序决定。
     */
    public Result fill(long allocationId, int sfuId, long grantBytes, long grantDurationNs,
                       long slotStartNs, QueueView view) {
        Objects.requireNonNull(view, "SFU queue view");
        Integer owner = allocationOwners.get(allocationId);
        if (owner == null || owner != sfuId || view.sfuId() != sfuId
                || (view.allocationId() != 0 && view.allocationId() != allocationId)) {
            throw new IllegalArgumentException("Unknown allocationId or mismatched SFU queue owner");
        }
        if (grantBytes < 0 || grantDurationNs < 0 || slotStartNs < view.snapshotTimeNs()) {
            throw new IllegalArgumentException("Grant budgets must be nonnegative and slot cannot precede queue view");
        }
        try {
            // 即使只是预演，也不能接受超过整数纳秒时钟范围的窗口。
            Math.addExact(slotStartNs, grantDurationNs);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Grant window exceeds the nanosecond clock range", exception);
        }

        List<Integer> selectedIds = new ArrayList<>();
        List<PacketCheck> checks = new ArrayList<>();
        long usedBytes = 0;
        long usedNs = 0;
        FillReason reason = view.packets().isEmpty() ? FillReason.QUEUE_EMPTY : FillReason.QUEUE_DRAINED;
        boolean blocked = false;
        int position = 0;
        for (PacketView packet : view.packets()) {
            position++;
            // 每包独立向上取整，不能把总字节一次取整后的时长当成多包实际占用。
            long packetNs = PonLinkTiming.serializationNs(packet.packetBytes(), rateMbps);
            if (blocked) {
                // 仅保留诊断记录，不再尝试选择后续小包，避免穿越无法装入的队首。
                checks.add(new PacketCheck(packet, packetNs, position, false, "NOT_REACHED"));
                continue;
            }

            FillReason stop = null;
            long remainingBytes = grantBytes - usedBytes;
            long remainingNs = grantDurationNs - usedNs;
            if (packet.enqueueTimeNs() > view.snapshotTimeNs()) {
                stop = FillReason.HEAD_NOT_ARRIVED;
            } else if (remainingBytes == 0) {
                stop = FillReason.BUDGET_EXHAUSTED;
            } else if (packet.packetBytes() > remainingBytes) {
                stop = FillReason.HEAD_BYTES_EXCEED_BUDGET;
            } else if (packetNs > remainingNs) {
                stop = FillReason.HEAD_TIME_EXCEEDS_BUDGET;
            }
            if (stop != null) {
                blocked = true;
                reason = stop;
                checks.add(new PacketCheck(packet, packetNs, position, false, stop.name()));
                continue;
            }

            // 与剩余容量比较后再累加，保证即使预算是 Long.MAX_VALUE 也不会溢出。
            selectedIds.add(packet.packetId());
            usedBytes += packet.packetBytes();
            usedNs += packetNs;
            checks.add(new PacketCheck(packet, packetNs, position, true, ""));
        }
        if (!blocked && !view.packets().isEmpty() && usedBytes == grantBytes) {
            reason = FillReason.BUDGET_EXHAUSTED;
        }
        return new Result(allocationId, sfuId, grantBytes, grantDurationNs, slotStartNs,
                view.snapshotTimeNs(), selectedIds, usedBytes, usedNs, grantBytes - usedBytes,
                grantDurationNs - usedNs, view.packets().size() - selectedIds.size(), reason, checks);
    }

    /** 第一项不能继续装入的原因；QUEUE_DRAINED 表示本视图已无候选，但预算仍有余量。 */
    public enum FillReason {
        QUEUE_EMPTY,
        BUDGET_EXHAUSTED,
        QUEUE_DRAINED,
        HEAD_BYTES_EXCEED_BUDGET,
        HEAD_TIME_EXCEEDS_BUDGET,
        HEAD_NOT_ARRIVED
    }

    /** 一个报文的不可变数值副本，不保存可被后续传输改变的 Packet 或 Request 引用。 */
    public static final class PacketView {
        private final int packetId;
        private final int packetBytes;
        private final long enqueueTimeNs;

        public PacketView(int packetId, int packetBytes, long enqueueTimeNs) {
            if (packetId < 1 || packetBytes < 1 || enqueueTimeNs < 0) {
                throw new IllegalArgumentException("Queue packet ID and bytes must be positive, enqueue time nonnegative");
            }
            this.packetId = packetId;
            this.packetBytes = packetBytes;
            this.enqueueTimeNs = enqueueTimeNs;
        }

        public int packetId() { return packetId; }
        public int packetBytes() { return packetBytes; }
        public long enqueueTimeNs() { return enqueueTimeNs; }
    }

    /**
     * 本地待授权 FIFO 的只读快照。生产入口应只复制未授权队列，排除已预约、待 BWmap
     * 或正在发送的报文。历史视图保留历史事实；需要当前状态时应重新向 Port 获取视图。
     */
    public static final class QueueView {
        private final long allocationId;
        private final int sfuId;
        private final long snapshotTimeNs;
        private final List<PacketView> packets;

        public QueueView(int sfuId, long snapshotTimeNs, List<PacketView> packets) {
            this(0, sfuId, snapshotTimeNs, packets);
        }

        /** 带 Alloc-ID 的生产视图，防止同一 SFU 内其他逻辑 FIFO 被误用于当前授权。 */
        public QueueView(long allocationId, int sfuId, long snapshotTimeNs, List<PacketView> packets) {
            if (allocationId < 0) {
                throw new IllegalArgumentException("Queue allocationId must be nonnegative");
            }
            if (sfuId < 1 || snapshotTimeNs < 0) {
                throw new IllegalArgumentException("SFU queue owner must be positive and snapshot time nonnegative");
            }
            this.packets = List.copyOf(Objects.requireNonNull(packets, "FIFO packets"));
            Set<Integer> seenIds = new HashSet<>();
            long previousNs = -1;
            for (PacketView packet : this.packets) {
                if (!seenIds.add(packet.packetId())) {
                    throw new IllegalArgumentException("Duplicate packet ID in SFU queue view");
                }
                if (packet.enqueueTimeNs() < previousNs) {
                    throw new IllegalArgumentException("SFU queue view must preserve FIFO enqueue order");
                }
                previousNs = packet.enqueueTimeNs();
            }
            // 同刻报文沿用原列表顺序，禁止按 ID 或其他容器遍历顺序重新排序。
            this.sfuId = sfuId;
            this.allocationId = allocationId;
            this.snapshotTimeNs = snapshotTimeNs;
        }

        public int sfuId() { return sfuId; }
        public long allocationId() { return allocationId; }
        public long snapshotTimeNs() { return snapshotTimeNs; }
        public List<PacketView> packets() { return packets; }
    }

    /** 每个候选报文的复核记录。阻塞点之后只标记 NOT_REACHED，不进行越过队首的装载。 */
    public static final class PacketCheck {
        private final int packetId;
        private final int packetBytes;
        private final long packetDurationNs;
        private final int fifoPosition;
        private final boolean selected;
        private final String rejectReason;

        private PacketCheck(PacketView packet, long packetDurationNs, int fifoPosition,
                            boolean selected, String rejectReason) {
            this.packetId = packet.packetId();
            this.packetBytes = packet.packetBytes();
            this.packetDurationNs = packetDurationNs;
            this.fifoPosition = fifoPosition;
            this.selected = selected;
            this.rejectReason = rejectReason;
        }

        public int packetId() { return packetId; }
        public int packetBytes() { return packetBytes; }
        public long packetDurationNs() { return packetDurationNs; }
        public int fifoPosition() { return fifoPosition; }
        public boolean selected() { return selected; }
        public String rejectReason() { return rejectReason; }
    }

    /** 完全只读的预演结果。未用预算仅记录，不返还、不重新分配，也不更新实际链路统计。 */
    public static final class Result {
        private final long allocationId;
        private final int sfuId;
        private final long grantBytes;
        private final long grantDurationNs;
        private final long slotStartNs;
        private final long snapshotTimeNs;
        private final List<Integer> selectedPacketIds;
        private final long selectedBytes;
        private final long selectedDurationNs;
        private final long unusedBytes;
        private final long unusedDurationNs;
        private final int remainingPacketCount;
        private final FillReason fillReason;
        private final List<PacketCheck> packetChecks;

        private Result(long allocationId, int sfuId, long grantBytes, long grantDurationNs,
                       long slotStartNs, long snapshotTimeNs, List<Integer> selectedPacketIds,
                       long selectedBytes, long selectedDurationNs, long unusedBytes, long unusedDurationNs,
                       int remainingPacketCount, FillReason fillReason, List<PacketCheck> packetChecks) {
            this.allocationId = allocationId;
            this.sfuId = sfuId;
            this.grantBytes = grantBytes;
            this.grantDurationNs = grantDurationNs;
            this.slotStartNs = slotStartNs;
            this.snapshotTimeNs = snapshotTimeNs;
            this.selectedPacketIds = List.copyOf(selectedPacketIds);
            this.selectedBytes = selectedBytes;
            this.selectedDurationNs = selectedDurationNs;
            this.unusedBytes = unusedBytes;
            this.unusedDurationNs = unusedDurationNs;
            this.remainingPacketCount = remainingPacketCount;
            this.fillReason = fillReason;
            this.packetChecks = List.copyOf(packetChecks);
        }

        public long allocationId() { return allocationId; }
        public int sfuId() { return sfuId; }
        public long grantBytes() { return grantBytes; }
        public long grantDurationNs() { return grantDurationNs; }
        public long slotStartNs() { return slotStartNs; }
        public long snapshotTimeNs() { return snapshotTimeNs; }
        public List<Integer> selectedPacketIds() { return selectedPacketIds; }
        public long selectedBytes() { return selectedBytes; }
        public long selectedDurationNs() { return selectedDurationNs; }
        public long unusedBytes() { return unusedBytes; }
        public long unusedDurationNs() { return unusedDurationNs; }
        public int remainingPacketCount() { return remainingPacketCount; }
        public FillReason fillReason() { return fillReason; }
        public List<PacketCheck> packetChecks() { return packetChecks; }
    }
}
