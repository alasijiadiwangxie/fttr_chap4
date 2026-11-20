package edu.suda.fttr.link;

/** 帧边界生成的不可变队列快照。只保存计数，不持有 SFU 队列或报文引用。 */
public final class PonUplinkReport {
    private final long frameId;
    private final long allocationId;
    private final int sfuId;
    private final boolean allocationSpecific;
    private final long snapshotTimeNs;
    private final int pendingPackets;
    private final long pendingBytes;
    private final int eligiblePackets;
    private final long eligibleBytes;

    public PonUplinkReport(long frameId, int sfuId, long snapshotTimeNs, int pendingPackets, long pendingBytes,
                           int eligiblePackets, long eligibleBytes) {
        this(frameId, 2L * sfuId, sfuId, snapshotTimeNs, pendingPackets, pendingBytes,
                eligiblePackets, eligibleBytes, false);
    }

    /** T-CONT 快照只描述一个逻辑 FIFO；物理 SFU 身份仍单独保留。 */
    public PonUplinkReport(long frameId, long allocationId, int sfuId, long snapshotTimeNs,
                           int pendingPackets, long pendingBytes, int eligiblePackets, long eligibleBytes) {
        this(frameId, allocationId, sfuId, snapshotTimeNs, pendingPackets, pendingBytes,
                eligiblePackets, eligibleBytes, true);
    }

    private PonUplinkReport(long frameId, long allocationId, int sfuId, long snapshotTimeNs,
                            int pendingPackets, long pendingBytes, int eligiblePackets, long eligibleBytes,
                            boolean allocationSpecific) {
        this.frameId = frameId;
        this.allocationId = allocationId;
        this.sfuId = sfuId;
        this.allocationSpecific = allocationSpecific;
        this.snapshotTimeNs = snapshotTimeNs;
        this.pendingPackets = pendingPackets;
        this.pendingBytes = pendingBytes;
        this.eligiblePackets = eligiblePackets;
        this.eligibleBytes = eligibleBytes;
    }

    public long frameId() {
        return frameId;
    }

    public int sfuId() {
        return sfuId;
    }

    public long allocationId() {
        return allocationId;
    }

    /** 旧构造保留旧日志格式，不能仅凭 allocationId 的数值猜测是否开启多队列。 */
    public boolean allocationSpecific() {
        return allocationSpecific;
    }

    public long snapshotTimeNs() {
        return snapshotTimeNs;
    }

    public int pendingPackets() {
        return pendingPackets;
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    public int eligiblePackets() {
        return eligiblePackets;
    }

    public long eligibleBytes() {
        return eligibleBytes;
    }
}
