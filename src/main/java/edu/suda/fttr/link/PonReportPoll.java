package edu.suda.fttr.link;

/** REPORT-only 的独立控制预约。没有 DATA 报文身份，时隙元数据在创建后固定。 */
public final class PonReportPoll {
    private final long id;
    private final int sfuId;
    private final long allocationId;
    private final boolean allocationSpecific;
    private final long grantFrameId;
    private final long grantTxNs;
    private final long startNs;
    private final long endNs;
    private boolean received;
    private PonUplinkReport report;

    PonReportPoll(long id, int sfuId, long grantFrameId, long grantTxNs, long startNs, long endNs) {
        this(id, 2L * sfuId, sfuId, grantFrameId, grantTxNs, startNs, endNs, false);
    }

    PonReportPoll(long id, long allocationId, int sfuId, long grantFrameId, long grantTxNs,
                  long startNs, long endNs) {
        this(id, allocationId, sfuId, grantFrameId, grantTxNs, startNs, endNs, true);
    }

    private PonReportPoll(long id, long allocationId, int sfuId, long grantFrameId, long grantTxNs,
                          long startNs, long endNs, boolean allocationSpecific) {
        if (allocationId < 1 || sfuId < 1) {
            throw new IllegalArgumentException("Invalid REPORT-only allocation owner");
        }
        this.id = id;
        this.allocationId = allocationId;
        this.allocationSpecific = allocationSpecific;
        this.sfuId = sfuId;
        this.grantFrameId = grantFrameId;
        this.grantTxNs = grantTxNs;
        this.startNs = startNs;
        this.endNs = endNs;
    }

    void receive(long nowNs) {
        if (received || nowNs < grantTxNs || nowNs >= startNs) {
            throw new IllegalStateException("Invalid or late REPORT_ONLY BWmap R" + id);
        }
        received = true;
    }

    void start(long nowNs, PonUplinkReport snapshot) {
        if (!received || nowNs != startNs || report != null || snapshot.allocationId() != allocationId
                || snapshot.sfuId() != sfuId || snapshot.snapshotTimeNs() != nowNs) {
            throw new IllegalStateException("REPORT_ONLY R" + id + " requires received BWmap and its assigned slot");
        }
        report = snapshot;
    }

    public long id() {
        return id;
    }

    public int sfuId() {
        return sfuId;
    }

    public long allocationId() { return allocationId; }
    public boolean allocationSpecific() { return allocationSpecific; }

    public long grantFrameId() {
        return grantFrameId;
    }

    public long targetFrameId() {
        return grantFrameId + 1;
    }

    public long grantTxNs() {
        return grantTxNs;
    }

    public long startNs() {
        return startNs;
    }

    public long endNs() {
        return endNs;
    }

    public PonUplinkReport report() {
        return report;
    }
}
