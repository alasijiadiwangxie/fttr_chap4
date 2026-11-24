package edu.suda.fttr.config;

import edu.suda.fttr.channel.ChannelTruth;

import java.math.BigDecimal;
import java.util.List;

/** 只供仿真控制层使用的 C2a 输入。SFU 只接收生成后的反馈。 */
public final class ChannelObservationInput {
    private final ChannelTruth truth;
    private final List<Feedback> feedback;
    private final List<Long> readTimesNs;
    private final List<Scan> scans;
    private final boolean passive;
    private final BigDecimal scanPowerW;

    ChannelObservationInput(ChannelTruth truth, List<Feedback> feedback, List<Long> readTimesNs,
                            List<Scan> scans, boolean passive, BigDecimal scanPowerW) {
        this.truth = truth;
        this.feedback = List.copyOf(feedback);
        this.readTimesNs = List.copyOf(readTimesNs);
        this.scans = List.copyOf(scans);
        this.passive = passive;
        this.scanPowerW = scanPowerW;
    }

    public ChannelTruth truth() { return truth; }
    public List<Feedback> feedback() { return feedback; }
    public List<Long> readTimesNs() { return readTimesNs; }
    public List<Scan> scans() { return scans; }
    public boolean passive() { return passive; }
    public BigDecimal scanPowerW() { return scanPowerW; }

    public boolean usesTimedReceiver() {
        return !scans.isEmpty() || feedback.stream().anyMatch(request -> request.airtimeNs() > 0);
    }

    public boolean hasManagementObservations() {
        return passive || usesTimedReceiver() || feedback.stream().anyMatch(Feedback::lost);
    }

    public static final class Scan {
        private final int sfuId;
        private final int subchannel;
        private final long startNs;
        private final long endNs;
        private final long expiresNs;

        Scan(int sfuId, int subchannel, long startNs, long durationNs, long validityNs) {
            this.sfuId = sfuId;
            this.subchannel = subchannel;
            this.startNs = startNs;
            try {
                endNs = Math.addExact(startNs, durationNs);
                expiresNs = Math.addExact(endNs, validityNs);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Auxiliary scan time exceeds the nanosecond range", e);
            }
        }

        public int sfuId() { return sfuId; }
        public int subchannel() { return subchannel; }
        public long startNs() { return startNs; }
        public long endNs() { return endNs; }
        public long expiresNs() { return expiresNs; }
    }

    /** STA 在指定时刻读取一个子信道。报告延迟和时效独立于 MAC。 */
    public static final class Feedback {
        private final int id;
        private final int sfuId;
        private final int staId;
        private final int subchannel;
        private final long measuredNs;
        private final long txStartNs;
        private final long arrivalNs;
        private final long validNs;
        private final long expiresNs;
        private final boolean lost;

        Feedback(int id, int sfuId, int staId, int subchannel, long measuredNs, long delayNs, long validNs,
                 long airtimeNs, boolean lost) {
            this.id = id;
            this.sfuId = sfuId;
            this.staId = staId;
            this.subchannel = subchannel;
            this.measuredNs = measuredNs;
            this.validNs = validNs;
            this.lost = lost;
            try {
                txStartNs = Math.addExact(measuredNs, delayNs);
                arrivalNs = Math.addExact(txStartNs, airtimeNs);
                expiresNs = Math.addExact(measuredNs, validNs);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("wifi.channel.feedback time exceeds the nanosecond range", e);
            }
        }

        public int id() { return id; }
        public int sfuId() { return sfuId; }
        public int staId() { return staId; }
        public int subchannel() { return subchannel; }
        public long measuredNs() { return measuredNs; }
        public long txStartNs() { return txStartNs; }
        public long arrivalNs() { return arrivalNs; }
        public long validNs() { return validNs; }
        public long expiresNs() { return expiresNs; }
        public long airtimeNs() { return arrivalNs - txStartNs; }
        public boolean lost() { return lost; }
    }
}
