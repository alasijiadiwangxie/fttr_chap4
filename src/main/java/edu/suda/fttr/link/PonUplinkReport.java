package edu.suda.fttr.link;

/** 帧边界生成的不可变队列快照。只保存计数，不持有 SFU 队列或报文引用。 */
public final class PonUplinkReport {
    private final long frameId;
    private final int sfuId;
    private final long snapshotTimeNs;
    private final int pendingPackets;
    private final long pendingBytes;
    private final int eligiblePackets;
    private final long eligibleBytes;

    PonUplinkReport(long frameId, int sfuId, long snapshotTimeNs, int pendingPackets, long pendingBytes,
                    int eligiblePackets, long eligibleBytes) {
        this.frameId = frameId;
        this.sfuId = sfuId;
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
