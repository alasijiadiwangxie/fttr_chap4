package edu.suda.fttr.phy;

/** 只持有配置曲线和分块权重。调用方提供已收到的观测值。 */
public final class ObservedPerCurve {
    private final SinrPerModel model;
    private final double[] weights;

    ObservedPerCurve(SinrPerModel model, double[] weights) {
        this.model = model;
        this.weights = weights == null ? null : weights.clone();
    }

    public double estimate(int mcs, double[] observedSinr, int mpduBytes) {
        if (observedSinr.length != 1 && observedSinr.length != 2 && observedSinr.length != 4) {
            throw new IllegalArgumentException("Observed PER requires 1, 2 or 4 subchannels");
        }
        boolean uniform = true;
        for (double sinr : observedSinr) {
            if (!Double.isFinite(sinr)) {
                throw new IllegalArgumentException("Observed SINR must be finite");
            }
            uniform &= sinr == observedSinr[0];
        }
        if (uniform) {
            return model.packetErrorRate(mcs, observedSinr[0], mpduBytes);
        }
        if (weights == null) {
            // 严格平坦模型不能从不一致的报告推断另一模式的误包。
            return Double.NaN;
        }
        double[] fractions = DownlinkDecoder.normalizedWeights(weights, observedSinr.length);
        return model.frequencyBlockErrorRate(mcs, observedSinr, fractions, mpduBytes);
    }
}
