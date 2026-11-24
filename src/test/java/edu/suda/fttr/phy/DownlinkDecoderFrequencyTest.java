package edu.suda.fttr.phy;

import edu.suda.fttr.channel.ChannelTruth;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownlinkDecoderFrequencyTest {
    private static final int MCS = 7;
    private static final int REFERENCE_BYTES = 1530;
    private static final double[] EQUAL_WEIGHTS = {1, 1, 1, 1};
    private static final SinrPerModel MODEL = new SinrPerModel(77, REFERENCE_BYTES, Map.of(MCS, List.of(
            new SinrPerModel.Knot(0, 1), new SinrPerModel.Knot(10, .5), new SinrPerModel.Knot(20, 0))));

    @Test
    void uniformBlocksPreserveFlatPerDrawAndOutcomeBitForBit() {
        for (double sinr : new double[] {-5, 10, 13.7, 25}) {
            ChannelTruth truth = truth(sinr, sinr, sinr, sinr);
            DownlinkDecoder flat = new DownlinkDecoder(truth, MODEL);
            DownlinkDecoder blocks = new DownlinkDecoder(truth, MODEL, new double[] {5, 3, 1, 2});
            for (int width : new int[] {20, 40, 80}) {
                DownlinkDecoder.ChannelSample original = flat.snapshotAtStart(1, 1, 0, width);
                DownlinkDecoder.ChannelSample current = blocks.snapshotAtStart(1, 1, 0, width);
                assertTrue(current.uniformSinr());
                for (int bytes : new int[] {230, REFERENCE_BYTES, 2300}) {
                    for (int attempt : new int[] {0, 1}) {
                        SinrPerModel.Decision expected = flat.evaluate(MCS, original, bytes, 1, 1, 1, attempt);
                        SinrPerModel.Decision actual = blocks.evaluate(MCS, current, bytes, 1, 1, 1, attempt);
                        assertEquals(Double.doubleToRawLongBits(expected.per()), Double.doubleToRawLongBits(actual.per()));
                        assertEquals(Double.doubleToRawLongBits(expected.draw()), Double.doubleToRawLongBits(actual.draw()));
                        assertEquals(expected.decoded(), actual.decoded());
                    }
                }
            }
        }
    }

    @Test
    void secondaryDegradationChangesWidePerAndKeepsPrimaryOnlyPer() {
        DownlinkDecoder decoder = new DownlinkDecoder(truth(20, 10, 10, 10), MODEL, EQUAL_WEIGHTS);
        DownlinkDecoder.ChannelSample primary = decoder.snapshotAtStart(1, 1, 0, 20);
        DownlinkDecoder.ChannelSample pair = decoder.snapshotAtStart(1, 1, 0, 40);
        DownlinkDecoder.ChannelSample wide = decoder.snapshotAtStart(1, 1, 0, 80);
        assertEquals(1, primary.subchannelCount());
        assertEquals(2, pair.subchannelCount());
        assertEquals(4, wide.subchannelCount());
        assertFalse(wide.uniformSinr());
        assertEquals(0.0, decision(decoder, primary, REFERENCE_BYTES).per());
        assertEquals(1 - StrictMath.pow(.5, .5), decision(decoder, pair, REFERENCE_BYTES).per(), 1e-15);
        assertEquals(1 - StrictMath.pow(.5, .75), decision(decoder, wide, REFERENCE_BYTES).per(), 1e-15);
        assertEquals(1 - StrictMath.pow(.5, 1.5), decision(decoder, wide, 2 * REFERENCE_BYTES).per(), 1e-15);
        assertEquals(decision(decoder, primary, REFERENCE_BYTES).draw(),
                decision(decoder, wide, REFERENCE_BYTES).draw());
    }

    @Test
    void primaryDegradationAffectsBothBandsAndUsesConfiguredFractions() {
        double[] weights = {3, 1, 1, 1};
        DownlinkDecoder decoder = new DownlinkDecoder(truth(10, 20, 20, 20), MODEL, weights);
        weights[0] = 0; // 配置被复制，调用者后续修改不改变模型。
        DownlinkDecoder.ChannelSample primary = decoder.snapshotAtStart(1, 1, 0, 20);
        DownlinkDecoder.ChannelSample pair = decoder.snapshotAtStart(1, 1, 0, 40);
        DownlinkDecoder.ChannelSample wide = decoder.snapshotAtStart(1, 1, 0, 80);
        assertEquals(1.0, primary.weight(0));
        assertEquals(.75, pair.weight(0));
        assertEquals(.25, pair.weight(1));
        assertEquals(.5, wide.weight(0), 1e-15);
        assertEquals(.5, decision(decoder, primary, REFERENCE_BYTES).per());
        assertEquals(1 - StrictMath.pow(.5, .75), decision(decoder, pair, REFERENCE_BYTES).per(), 1e-15);
        assertEquals(1 - StrictMath.pow(.5, .5), decision(decoder, wide, REFERENCE_BYTES).per(), 1e-15);
    }

    @Test
    void validatesOnlyTheOccupiedTruthAndDoesNotReadAFutureChange() {
        ChannelTruth truth = new ChannelTruth(List.of(change(0, 0, 20), change(1, 10, 10)));
        DownlinkDecoder decoder = new DownlinkDecoder(truth, MODEL, EQUAL_WEIGHTS);
        assertEquals(20.0, decoder.snapshotAtStart(1, 1, 0, 20).sinrDb(0));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> decoder.snapshotAtStart(1, 1, 9, 40)).getMessage().contains("subchannel 1"));
        DownlinkDecoder.ChannelSample sample = decoder.snapshotAtStart(1, 1, 10, 40);
        assertEquals(10.0, sample.sinrDb(1));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> decoder.snapshotAtStart(1, 1, 10, 80)).getMessage().contains("subchannel 2"));
        assertThrows(IllegalArgumentException.class,
                () -> new DownlinkDecoder(truth, MODEL).snapshotAtStart(1, 1, 10, 20));
    }

    @Test
    void handlesCertainFailureAndRejectsUnsupportedBandwidthAndWeights() {
        ChannelTruth truth = truth(20, 20, 0, 20);
        DownlinkDecoder decoder = new DownlinkDecoder(truth, MODEL, EQUAL_WEIGHTS);
        assertEquals(1.0, decision(decoder, decoder.snapshotAtStart(1, 1, 0, 80), REFERENCE_BYTES).per());
        assertThrows(IllegalArgumentException.class, () -> decoder.snapshotAtStart(1, 1, 0, 160));
        assertThrows(IllegalArgumentException.class, () -> decoder.snapshotAtStart(1, 1, 0, 60));
        assertThrows(IllegalArgumentException.class, () -> new DownlinkDecoder(truth, MODEL, new double[] {1, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> new DownlinkDecoder(truth, MODEL, new double[] {1, 0, 1, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> new DownlinkDecoder(truth, MODEL, new double[] {1, Double.NaN, 1, 1}));
        DownlinkDecoder unrepresentable = new DownlinkDecoder(truth, MODEL,
                new double[] {Double.MIN_VALUE, Double.MAX_VALUE, 1, 1});
        assertThrows(IllegalArgumentException.class, () -> unrepresentable.snapshotAtStart(1, 1, 0, 80));
    }

    private static SinrPerModel.Decision decision(DownlinkDecoder decoder, DownlinkDecoder.ChannelSample sample, int bytes) {
        return decoder.evaluate(MCS, sample, bytes, 1, 1, 1, 0);
    }

    private static ChannelTruth truth(double... sinr) {
        List<ChannelTruth.Change> changes = new ArrayList<>();
        for (int index = 0; index < sinr.length; index++) {
            changes.add(change(index, 0, sinr[index]));
        }
        return new ChannelTruth(changes);
    }

    private static ChannelTruth.Change change(int index, long timeNs, double sinr) {
        return new ChannelTruth.Change(1, 1, index, timeNs, sinr, -1, false);
    }
}
