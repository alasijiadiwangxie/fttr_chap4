package edu.suda.fttr.config;

import edu.suda.fttr.channel.ChannelTruth;

import java.util.List;

/** 只供仿真控制层使用的 C2a 输入。SFU 只接收生成后的反馈。 */
public final class ChannelObservationInput {
    private final ChannelTruth truth;
    private final List<Feedback> feedback;
    private final List<Long> readTimesNs;

    ChannelObservationInput(ChannelTruth truth, List<Feedback> feedback, List<Long> readTimesNs) {
        this.truth = truth;
        this.feedback = List.copyOf(feedback);
        this.readTimesNs = List.copyOf(readTimesNs);
    }

    public ChannelTruth truth() { return truth; }
    public List<Feedback> feedback() { return feedback; }
    public List<Long> readTimesNs() { return readTimesNs; }

    /** STA 在指定时刻读取一个子信道。报告延迟和时效独立于 MAC。 */
    public static final class Feedback {
        private final int id;
        private final int sfuId;
        private final int staId;
        private final int subchannel;
        private final long measuredNs;
        private final long arrivalNs;
        private final long validNs;
        private final long expiresNs;

        Feedback(int id, int sfuId, int staId, int subchannel, long measuredNs, long delayNs, long validNs) {
            this.id = id;
            this.sfuId = sfuId;
            this.staId = staId;
            this.subchannel = subchannel;
            this.measuredNs = measuredNs;
            this.validNs = validNs;
            try {
                arrivalNs = Math.addExact(measuredNs, delayNs);
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
        public long arrivalNs() { return arrivalNs; }
        public long validNs() { return validNs; }
        public long expiresNs() { return expiresNs; }
    }
}
