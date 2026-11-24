package edu.suda.fttr.phy;

import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.channel.ChannelTruth;
import edu.suda.fttr.channel.ExternalInterference;

import java.util.Arrays;

/** 下行 DATA 解码器。仿真真值只进入 PHY 快照和诊断记录。 */
public final class DownlinkDecoder {
    private final ChannelTruth truth;
    private final SinrPerModel model;
    private final double[] blockWeights;
    private final ExternalInterference interference;

    /** C2b-1 严格平坦模式。 */
    public DownlinkDecoder(ChannelTruth truth, SinrPerModel model) {
        this.truth = truth;
        this.model = model;
        this.blockWeights = null;
        this.interference = null;
    }

    /** C2b-2a 分块模式，配置中的相对权重按实际带宽所用子信道重新归一化。 */
    public DownlinkDecoder(ChannelTruth truth, SinrPerModel model, double[] blockWeights) {
        this(truth, model, blockWeights, null);
    }

    public DownlinkDecoder(ChannelTruth truth, SinrPerModel model, double[] blockWeights,
                           ExternalInterference interference) {
        this.truth = truth;
        this.model = model;
        if (blockWeights.length != ChannelReport.SUBCHANNEL_COUNT) {
            throw new IllegalArgumentException("SINR-PER requires four subchannel weights");
        }
        this.blockWeights = blockWeights.clone();
        this.interference = interference;
        for (double weight : this.blockWeights) {
            if (!(weight > 0.0) || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("SINR-PER subchannel weights must be finite and positive");
            }
        }
    }

    public boolean frequencyBlocks() {
        return blockWeights != null;
    }

    /** 复制给在线估算器的只有误包曲线及权重，完全不包含信道时间线。 */
    public ObservedPerCurve observedPerCurve() {
        return new ObservedPerCurve(model, blockWeights);
    }

    /** PPDU 起点冻结实际带宽所用的真值，后续逐 MPDU 解码复用同一快照。 */
    public ChannelSample snapshotAtStart(int sfuId, int staId, long startNs, int bandwidthMhz) {
        int count = subchannelCountForBandwidth(bandwidthMhz);
        double[] sinrDb = new double[count];
        if (!frequencyBlocks()) {
            Arrays.fill(sinrDb, sinrAtStart(sfuId, staId, startNs, bandwidthMhz));
            return new ChannelSample(sinrDb, null);
        }
        for (int index = 0; index < count; index++) {
            ChannelTruth.Change baseline = subchannelAtStart(sfuId, staId, index, startNs);
            sinrDb[index] = interference == null ? baseline.sinrDb() : interference.downlinkSinrDb(baseline, startNs);
        }
        return new ChannelSample(sinrDb, normalizedWeights(blockWeights, count));
    }

    public static int subchannelCountForBandwidth(int bandwidthMhz) {
        if (bandwidthMhz > 80) {
            throw new IllegalArgumentException("SINR-PER requires an actual DATA bandwidth of 80 MHz or less");
        }
        if (bandwidthMhz != 20 && bandwidthMhz != 40 && bandwidthMhz != 80) {
            throw new IllegalArgumentException("SINR-PER requires an actual DATA bandwidth of 20, 40 or 80 MHz");
        }
        return bandwidthMhz / 20;
    }

    /** C2b-1 严格平坦校验。四个子信道均须已知且一致。 */
    public double sinrAtStart(int sfuId, int staId, long startNs, int bandwidthMhz) {
        if (bandwidthMhz > 80) {
            throw new IllegalArgumentException("SINR-PER requires an actual DATA bandwidth of 80 MHz or less");
        }
        ChannelTruth.Change primary = subchannelAtStart(sfuId, staId, 0, startNs);
        for (int subchannel = 1; subchannel < ChannelReport.SUBCHANNEL_COUNT; subchannel++) {
            ChannelTruth.Change next = subchannelAtStart(sfuId, staId, subchannel, startNs);
            if (primary.sinrDb() != next.sinrDb() || primary.cqi() != next.cqi()
                    || primary.interfered() != next.interfered()) {
                throw new IllegalArgumentException("SINR-PER supports only a flat channel across all four subchannels"
                        + " (SFU " + sfuId + ", STA " + staId + ", PPDU start " + startNs + " ns)");
            }
        }
        return primary.sinrDb();
    }

    static double[] normalizedWeights(double[] blockWeights, int count) {
        double maxWeight = blockWeights[0];
        for (int index = 1; index < count; index++) {
            maxWeight = Math.max(maxWeight, blockWeights[index]);
        }
        double[] fractions = new double[count];
        double total = 0.0;
        for (int index = 0; index < count; index++) {
            fractions[index] = blockWeights[index] / maxWeight;
            total += fractions[index];
        }
        for (int index = 0; index < count; index++) {
            fractions[index] /= total;
            if (fractions[index] == 0.0) {
                throw new IllegalArgumentException("SINR-PER subchannel weights are too different to normalize");
            }
        }
        return fractions;
    }

    private ChannelTruth.Change subchannelAtStart(int sfuId, int staId, int subchannel, long startNs) {
        return truth.stateAt(sfuId, staId, subchannel, startNs).orElseThrow(() ->
                new IllegalArgumentException("Missing channel truth for SINR-PER (SFU " + sfuId + ", STA " + staId
                        + ", subchannel " + subchannel + ", PPDU start " + startNs + " ns)"));
    }

    public SinrPerModel.Decision evaluate(int mcs, double sinrDb, int mpduBytes, int sfuId, int staId,
                                         int packetId, int attemptIndex) {
        return model.evaluate(mcs, sinrDb, mpduBytes, sfuId, staId, packetId, attemptIndex);
    }

    public SinrPerModel.Decision evaluate(int mcs, ChannelSample sample, int mpduBytes, int sfuId, int staId,
                                         int packetId, int attemptIndex) {
        // 等 SINR 包括 20 MHz 单块。复用原计算和抽样，保留平坦场景的逐位结果。
        if (sample.uniformSinr()) {
            return evaluate(mcs, sample.sinrDb(0), mpduBytes, sfuId, staId, packetId, attemptIndex);
        }
        return model.evaluateFrequencyBlocks(mcs, sample.sinrDb, sample.fractions, mpduBytes,
                sfuId, staId, packetId, attemptIndex);
    }

    /** 子信道连续从主信道 0 开始。数组只由解码器创建和持有。 */
    public static final class ChannelSample {
        private final double[] sinrDb;
        private final double[] fractions;
        private final boolean uniformSinr;

        private ChannelSample(double[] sinrDb, double[] fractions) {
            this.sinrDb = sinrDb;
            this.fractions = fractions;
            this.uniformSinr = allEqual(sinrDb);
        }

        private static boolean allEqual(double[] values) {
            for (int index = 1; index < values.length; index++) {
                if (values[index] != values[0]) {
                    return false;
                }
            }
            return true;
        }

        public boolean frequencyBlocks() { return fractions != null; }
        public boolean uniformSinr() { return uniformSinr; }
        public int subchannelCount() { return sinrDb.length; }
        public double sinrDb(int index) { return sinrDb[index]; }
        public double weight(int index) { return fractions == null ? 1.0 / sinrDb.length : fractions[index]; }
    }
}
