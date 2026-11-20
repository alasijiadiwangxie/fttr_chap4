package edu.suda.fttr.link;

/**
 * 一个真实字节预算窗口。身份独立于 DATA packetId，窗口创建后不可改变。
 * 接收、装载和执行完成分别检查，MFU 只在结算接收事件中读取执行结果。
 */
public final class PonDbaGrant {
    private final long id;
    private final long allocationId;
    private final int sfuId;
    private final long createNs;
    private final long targetFrameId;
    private final long startNs;
    private final long endNs;
    private final long budgetBytes;
    private boolean received;
    private PonGrantPacketFiller.Result result;
    private long completedBytes;
    private PonUplinkReport report;

    public PonDbaGrant(long id, long allocationId, int sfuId, long createNs, long targetFrameId,
                       long startNs, long endNs, long budgetBytes) {
        if (id < 1 || allocationId < 1 || sfuId < 1 || createNs < 0 || targetFrameId < 1
                || startNs <= createNs || endNs <= startNs || budgetBytes <= 0) {
            throw new IllegalArgumentException("Invalid real DBA budget identity, window or bytes");
        }
        this.id = id;
        this.allocationId = allocationId;
        this.sfuId = sfuId;
        this.createNs = createNs;
        this.targetFrameId = targetFrameId;
        this.startNs = startNs;
        this.endNs = endNs;
        this.budgetBytes = budgetBytes;
    }

    void receive(long nowNs) {
        if (received || nowNs < createNs || nowNs >= startNs) {
            throw new IllegalStateException("Duplicate, late or premature DATA BWmap D" + id);
        }
        received = true;
    }

    void load(long nowNs, PonGrantPacketFiller.Result selected) {
        if (!received || result != null || nowNs != startNs || selected.allocationId() != allocationId
                || selected.sfuId() != sfuId || selected.grantBytes() != budgetBytes
                || selected.grantDurationNs() != endNs - startNs || selected.slotStartNs() != startNs) {
            throw new IllegalStateException("DATA budget requires its received BWmap and exact assigned slot");
        }
        result = selected;
    }

    void recordSent(int bytes) {
        if (result == null || report != null || bytes <= 0 || bytes > selectedBytes() - completedBytes) {
            throw new IllegalStateException("Invalid or duplicate DATA completion for budget D" + id);
        }
        completedBytes += bytes;
    }

    void finish(long nowNs, PonUplinkReport snapshot) {
        if (result == null || report != null || nowNs != endNs || completedBytes != selectedBytes()
                || snapshot.sfuId() != sfuId || snapshot.allocationId() != allocationId
                || snapshot.snapshotTimeNs() != endNs) {
            throw new IllegalStateException("DATA budget cannot settle before all selected DATA completes");
        }
        report = snapshot;
    }

    public long id() { return id; }
    public long allocationId() { return allocationId; }
    public int sfuId() { return sfuId; }
    public long createNs() { return createNs; }
    public long targetFrameId() { return targetFrameId; }
    public long startNs() { return startNs; }
    public long endNs() { return endNs; }
    public long budgetBytes() { return budgetBytes; }
    public boolean received() { return received; }
    public boolean started() { return result != null; }
    public boolean finished() { return report != null; }
    public long selectedBytes() { return result == null ? 0 : result.selectedBytes(); }
    public long actualBytes() { return completedBytes; }
    public long unusedBytes() { return budgetBytes - selectedBytes(); }
    public PonGrantPacketFiller.Result result() { return result; }
    public PonUplinkReport report() { return report; }
}
