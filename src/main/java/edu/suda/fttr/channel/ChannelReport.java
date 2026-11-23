package edu.suda.fttr.channel;

/** 一份已经在 STA 测量的逻辑反馈。有效期从测量时刻起算，包含开始时刻。 */
public final class ChannelReport {
    public static final int SUBCHANNEL_COUNT = 4;
    /** 未提供 CQI 时只报告 SINR。不根据 SINR 推断 CQI。 */
    public static final int UNKNOWN_CQI = -1;

    private final int sfuId;
    private final int staId;
    private final int subchannel;
    private final long measuredAtNs;
    private final long arrivedAtNs;
    private final long validForNs;
    private final long expiresAtNs;
    private final double sinrDb;
    private final int cqi;

    public ChannelReport(int sfuId, int staId, int subchannel, long measuredAtNs, long arrivedAtNs,
                         long validForNs, double sinrDb, int cqi) {
        requireLink(sfuId, staId, subchannel);
        requireQuality(sinrDb, cqi);
        if (measuredAtNs < 0 || arrivedAtNs < measuredAtNs || validForNs <= 0) {
            throw new IllegalArgumentException("Channel report requires ordered times and a positive validity interval");
        }
        try {
            expiresAtNs = Math.addExact(measuredAtNs, validForNs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Channel report validity exceeds the nanosecond clock range", e);
        }
        this.sfuId = sfuId;
        this.staId = staId;
        this.subchannel = subchannel;
        this.measuredAtNs = measuredAtNs;
        this.arrivedAtNs = arrivedAtNs;
        this.validForNs = validForNs;
        this.sinrDb = sinrDb;
        this.cqi = cqi;
    }

    public int sfuId() { return sfuId; }
    public int staId() { return staId; }
    /** 主 20 MHz 为索引 0，其余依次为 1 到 3。 */
    public int subchannel() { return subchannel; }
    public long measuredAtNs() { return measuredAtNs; }
    public long arrivedAtNs() { return arrivedAtNs; }
    public long validForNs() { return validForNs; }
    public long expiresAtNs() { return expiresAtNs; }
    public double sinrDb() { return sinrDb; }
    public int cqi() { return cqi; }

    /** 查询时必须已到达，并且从测量到查询的时长小于报告有效期。 */
    public boolean isUsableAt(long nowNs) {
        return arrivedAtNs <= nowNs && nowNs < expiresAtNs;
    }

    static void requireLink(int sfuId, int staId, int subchannel) {
        if (sfuId <= 0 || staId <= 0) {
            throw new IllegalArgumentException("Channel link identifiers must be positive");
        }
        requireSubchannel(subchannel);
    }

    static void requireSubchannel(int subchannel) {
        if (subchannel < 0 || subchannel >= SUBCHANNEL_COUNT) {
            throw new IllegalArgumentException("Channel subchannel index must be in [0, 3]");
        }
    }

    static void requireQuality(double sinrDb, int cqi) {
        if (!Double.isFinite(sinrDb) || cqi < UNKNOWN_CQI || cqi > 15) {
            throw new IllegalArgumentException("Channel quality requires finite SINR and CQI in [0, 15] or -1");
        }
    }
}
