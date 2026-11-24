package edu.suda.fttr.channel;

import java.util.Optional;

/** 辅助射频的 AP 本地忙时观测。这里没有 STA 信道质量字段。 */
public final class AuxChannelScan {
    private final Result[] latest = new Result[ChannelReport.SUBCHANNEL_COUNT];

    public void complete(int subchannel, long fromNs, long untilNs, long busyNs, long expiresNs) {
        ChannelReport.requireSubchannel(subchannel);
        if (subchannel == 0 || fromNs < 0 || untilNs <= fromNs || busyNs < 0 || busyNs > untilNs - fromNs
                || expiresNs <= untilNs) {
            throw new IllegalArgumentException("Invalid completed auxiliary channel scan");
        }
        latest[subchannel] = new Result(fromNs, untilNs, busyNs, expiresNs);
    }

    public Optional<Result> latest(int subchannel, long nowNs) {
        ChannelReport.requireSubchannel(subchannel);
        Result result = latest[subchannel];
        return result != null && result.untilNs <= nowNs && nowNs < result.expiresNs
                ? Optional.of(result) : Optional.empty();
    }

    public static final class Result {
        private final long fromNs;
        private final long untilNs;
        private final long busyNs;
        private final long expiresNs;

        private Result(long fromNs, long untilNs, long busyNs, long expiresNs) {
            this.fromNs = fromNs;
            this.untilNs = untilNs;
            this.busyNs = busyNs;
            this.expiresNs = expiresNs;
        }

        public double busyFraction() { return (double) busyNs / (untilNs - fromNs); }
        public long measuredAtNs() { return untilNs; }
    }
}
