package edu.suda.fttr.link;

/** REPORT-only 的独立控制预约。没有 DATA 报文身份，时隙元数据在创建后固定。 */
public final class PonReportPoll {
    private final long id;
    private final int sfuId;
    private final long grantFrameId;
    private final long grantTxNs;
    private final long startNs;
    private final long endNs;
    private boolean received;
    private PonUplinkReport report;

    PonReportPoll(long id, int sfuId, long grantFrameId, long grantTxNs, long startNs, long endNs) {
        this.id = id;
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
        if (!received || nowNs != startNs || report != null) {
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
