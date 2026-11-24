package edu.suda.fttr.phy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 用户配置的参考 MPDU 误包曲线。该模型不持有信道真值、观测或 MAC 状态。 */
public final class SinrPerModel {
    private final long seed;
    private final int referenceMpduBytes;
    private final Map<Integer, List<Knot>> curves;

    public SinrPerModel(long seed, int referenceMpduBytes, Map<Integer, List<Knot>> curves) {
        if (referenceMpduBytes <= 0 || curves.isEmpty()) {
            throw new IllegalArgumentException("SINR–PER requires a positive reference MPDU length and MCS curves");
        }
        this.seed = seed;
        this.referenceMpduBytes = referenceMpduBytes;
        Map<Integer, List<Knot>> validated = new HashMap<>();
        curves.forEach((mcs, knots) -> {
            List<Knot> curve = List.copyOf(knots);
            validateCurve(mcs, curve);
            validated.put(mcs, curve);
        });
        this.curves = Map.copyOf(validated);
    }

    private static void validateCurve(int mcs, List<Knot> curve) {
        if (mcs < 0 || mcs > 11 || curve.size() < 2) {
            throw new IllegalArgumentException("SINR–PER requires MCS 0..11 and at least two knots per MCS");
        }
        for (int i = 1; i < curve.size(); i++) {
            Knot previous = curve.get(i - 1);
            Knot next = curve.get(i);
            if (next.sinrDb <= previous.sinrDb || next.per > previous.per) {
                throw new IllegalArgumentException("SINR–PER knots must have increasing SINR and nonincreasing PER"
                        + " for MCS " + mcs);
            }
        }
    }

    public boolean supportsMcs(int mcs) {
        return curves.containsKey(mcs);
    }

    public double packetErrorRate(int mcs, double sinrDb, int mpduBytes) {
        if (!Double.isFinite(sinrDb) || mpduBytes <= 0) {
            throw new IllegalArgumentException("SINR–PER requires finite SINR and a positive MPDU length");
        }
        double referencePer = referencePer(curveFor(mcs), sinrDb);
        if (mpduBytes == referenceMpduBytes || referencePer == 0.0 || referencePer == 1.0) {
            return referencePer;
        }
        // 将参考长度的成功率换算到实际 MPDU 长度。这里假设错误机会随长度同比增长。
        double lengthRatio = (double) mpduBytes / referenceMpduBytes;
        return -StrictMath.expm1(lengthRatio * StrictMath.log1p(-referencePer));
    }

    private List<Knot> curveFor(int mcs) {
        List<Knot> curve = curves.get(mcs);
        if (curve == null) {
            throw new IllegalArgumentException("No SINR–PER curve configured for MCS " + mcs);
        }
        return curve;
    }

    /** 将整条 MPDU 的成功率近似为各频域分块成功率的乘积。输入来自解码器校验后的快照。 */
    double frequencyBlockErrorRate(int mcs, double[] sinrDb, double[] fractions, int mpduBytes) {
        if (mpduBytes <= 0) {
            throw new IllegalArgumentException("SINR–PER requires a positive MPDU length");
        }
        List<Knot> curve = curveFor(mcs);
        double logSuccess = 0.0;
        for (int index = 0; index < sinrDb.length; index++) {
            double per = referencePer(curve, sinrDb[index]);
            if (per == 1.0) {
                return 1.0;
            }
            logSuccess += fractions[index] * StrictMath.log1p(-per);
        }
        double lengthRatio = (double) mpduBytes / referenceMpduBytes;
        return logSuccess == 0.0 ? 0.0 : -StrictMath.expm1(lengthRatio * logSuccess);
    }

    private static double referencePer(List<Knot> curve, double sinrDb) {
        Knot first = curve.get(0);
        if (sinrDb <= first.sinrDb) {
            return first.per;
        }
        for (int i = 1; i < curve.size(); i++) {
            Knot right = curve.get(i);
            if (sinrDb <= right.sinrDb) {
                if (sinrDb == right.sinrDb) {
                    return right.per;
                }
                Knot left = curve.get(i - 1);
                double span = right.sinrDb - left.sinrDb;
                double fraction = Double.isFinite(span) ? (sinrDb - left.sinrDb) / span
                        : (sinrDb / 2 - left.sinrDb / 2) / (right.sinrDb / 2 - left.sinrDb / 2);
                return left.per + fraction * (right.per - left.per);
            }
        }
        return curve.get(curve.size() - 1).per;
    }

    /** 每个 MPDU、每次发射独立取样，避免消耗 EDCA 或流量发生器的随机序列。 */
    public Decision evaluate(int mcs, double sinrDb, int mpduBytes, int sfuId, int staId, int packetId,
                             int attemptIndex) {
        double draw = draw(sfuId, staId, packetId, attemptIndex);
        return new Decision(packetErrorRate(mcs, sinrDb, mpduBytes), draw);
    }

    Decision evaluateFrequencyBlocks(int mcs, double[] sinrDb, double[] fractions, int mpduBytes,
                                     int sfuId, int staId, int packetId, int attemptIndex) {
        return new Decision(frequencyBlockErrorRate(mcs, sinrDb, fractions, mpduBytes),
                draw(sfuId, staId, packetId, attemptIndex));
    }

    private double draw(int sfuId, int staId, int packetId, int attemptIndex) {
        if (sfuId <= 0 || staId <= 0 || attemptIndex < 0) {
            throw new IllegalArgumentException("SINR–PER requires a valid link and nonnegative attempt index");
        }
        long key = mix64(seed ^ 0x9e3779b97f4a7c15L);
        key = mix64(key ^ sfuId);
        key = mix64(key ^ staId);
        key = mix64(key ^ Integer.toUnsignedLong(packetId));
        key = mix64(key ^ attemptIndex);
        return (key >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 混合函数，使用 long 自然溢出。 */
    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    public static final class Knot {
        private final double sinrDb;
        private final double per;

        public Knot(double sinrDb, double per) {
            if (!Double.isFinite(sinrDb) || !(per >= 0.0 && per <= 1.0)) {
                throw new IllegalArgumentException("SINR–PER knot requires finite SINR and PER in [0, 1]");
            }
            this.sinrDb = sinrDb;
            this.per = per;
        }
    }

    public static final class Decision {
        private final double per;
        private final double draw;

        private Decision(double per, double draw) {
            this.per = per;
            this.draw = draw;
        }

        public double per() { return per; }
        public double draw() { return draw; }
        public boolean decoded() { return draw >= per; }
    }
}
