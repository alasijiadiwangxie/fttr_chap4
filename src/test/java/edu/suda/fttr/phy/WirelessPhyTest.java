package edu.suda.fttr.phy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WirelessPhyTest {
    private static final int MAC_OVERHEAD_BYTES = 34;

    @Test
    void matchesHeDataRatesAcrossBandwidthMcsNssAndGi() {
        // 各行按 IEEE 802.11ax 数据子载波、调制位数和码率计算，每符号数据位数为整数。
        assertEquals(117.0 / 13.6, phy(20, 0, 1, 800).phyRateMbps(), 1e-9);
        assertEquals(260.0, phy(40, 5, 2, 1600).phyRateMbps(), 1e-9);
        assertEquals(1225.0, phy(80, 9, 3, 3200).phyRateMbps(), 1e-9);
        assertEquals(32666.0 / 13.6, phy(160, 11, 2, 800).phyRateMbps(), 1e-9);
    }

    @Test
    void countsPreambleAndWholeOfdmSymbolsForKnownPayloads() {
        // 默认配置：36 + 2×7.2 μs 前导码，1500 B 的报文占一个 13.6 μs 数据符号。
        assertEquals(64_000, phy(160, 11, 2, 800).ppduDurationNs(1500));

        // 20 MHz MCS 0 单流在 1500 B 下使用 106 个数据符号，GI 同时改变训练字段和数据时长。
        assertEquals(1_484_800, phy(20, 0, 1, 800).ppduDurationNs(1500));
        assertEquals(1_570_400, phy(20, 0, 1, 1600).ppduDurationNs(1500));
        assertEquals(1_748_000, phy(20, 0, 1, 3200).ppduDurationNs(1500));

        // MathWorks transmitTime 的 40 MHz HE SU 示例使用 100 B APEP、MCS 0、LDPC 和 3.2 μs GI。
        assertEquals(116_000, new WirelessPhy(40, 0, 1, 3200, 0).ppduDurationNs(100));
    }

    @Test
    void showsTheNssTradeoffAndTheDataSymbolStepAt160Mhz() {
        WirelessPhy oneStream = phy(160, 11, 1, 800);
        WirelessPhy twoStreams = phy(160, 11, 2, 800);
        assertEquals(16333.0 / 13.6, oneStream.phyRateMbps(), 1e-9);
        assertEquals(2 * oneStream.phyRateMbps(), twoStreams.phyRateMbps(), 1e-9);

        // 两档都只用一个数据符号，因此第二根流的额外 HE-LTF 使 1500 B 的 PPDU 反而更长。
        var one = oneStream.ppduTiming(1500);
        var two = twoStreams.ppduTiming(1500);
        assertEquals(1, one.dataSymbols());
        assertEquals(1, two.dataSymbols());
        assertEquals(43_200, one.preambleNs());
        assertEquals(50_400, two.preambleNs());
        assertEquals(56_800, one.durationNs());
        assertEquals(64_000, two.durationNs());

        // 1 NSS 容量是 16333 数据位；含 34 B 开销和 16 位 SERVICE 后，两种长度分别需要 16328 位、16336 位。
        assertEquals(1, oneStream.ppduTiming(2005).dataSymbols());
        assertEquals(56_800, oneStream.ppduDurationNs(2005));
        assertEquals(2, oneStream.ppduTiming(2006).dataSymbols());
        assertEquals(70_400, oneStream.ppduDurationNs(2006));
    }

    @Test
    void accountsForTheAllowedLtfGiPairsAndLtfSymbolCount() {
        assertPreamble(800, 1, 1, 40_000);
        assertPreamble(800, 2, 3, 64_800); // 3 NSS 按 4 个 HE-LTF 符号计算。
        assertPreamble(1600, 2, 1, 44_000);
        assertPreamble(800, 4, 1, 49_600);
        assertPreamble(3200, 4, 1, 52_000);
        assertThrows(IllegalArgumentException.class, () -> new WirelessPhy(80, 11, 1, 1600, 34, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new WirelessPhy(80, 11, 1, 3200, 34, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new WirelessPhy(80, 11, 1, 1600, 34, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new WirelessPhy(80, 11, 1, 800, 34, 3, 0));
    }

    @Test
    void addsAWholeSymbolWhenLdpcExtraSegmentCrossesTheSymbolBoundary() {
        // 80 MHz/MCS 11/1 NSS 时，三段短符号可容纳 6000 位。714 B 加上开销和 SERVICE 恰好达到它。
        // 715 B 越过这一界限，初始 a=4；LDPC 的截短和打孔判定要求额外一段，因此需再发一个符号。
        WirelessPhy phy = phy(80, 11, 1, 800);
        var below = phy.ppduTiming(714);
        var above = phy.ppduTiming(715);
        assertEquals(56_800, below.durationNs());
        assertEquals(1, below.dataSymbols());
        assertEquals(70_400, above.durationNs());
        assertEquals(2, above.dataSymbols());
        assertEquals(4, above.initialPaddingFactor());
        assertEquals(1, above.paddingFactor());
        assertTrue(above.hasExtraLdpcSegment());
        assertEquals(2158, above.preFecPaddingBits()); // 初始 N_PLD−B = 8166−6008。
        assertEquals(7400, above.postFecPaddingBits()); // 新末符号的后填充为 9800−2400。
        assertEquals(70_400, new WirelessPhy(80, 11, 1, 800, 35).ppduDurationNs(714));
    }

    @Test
    void usesFinalPaddingFactorForPacketExtensionEvenWithoutAnExtraWholeSymbol() {
        // 215 B 的第三段来自 LDPC 额外分段，仍只有一个数据符号。16 μs nominal padding 时，PE 由 8 变为 12 μs。
        var timing = new WirelessPhy(80, 11, 1, 800, 34, 2, 16).ppduTiming(215);
        assertTrue(timing.hasExtraLdpcSegment());
        assertEquals(2, timing.initialPaddingFactor());
        assertEquals(3, timing.paddingFactor());
        assertEquals(1, timing.dataSymbols());
        assertEquals(1992, timing.preFecPaddingBits()); // 初始两段 4000 位减去 2008 位。
        assertEquals(2600, timing.postFecPaddingBits()); // 最终三段占 7200 个编码位。
        assertEquals(12_000, timing.packetExtensionNs());
        assertEquals(68_800, timing.durationNs());

        var fillsLastSymbol = phy(20, 0, 1, 800).ppduTiming(16);
        assertTrue(fillsLastSymbol.hasExtraLdpcSegment());
        assertEquals(3, fillsLastSymbol.initialPaddingFactor());
        assertEquals(4, fillsLastSymbol.paddingFactor());
        assertEquals(4, fillsLastSymbol.dataSymbols());
        assertEquals(25, fillsLastSymbol.preFecPaddingBits());
        assertEquals(0, fillsLastSymbol.postFecPaddingBits());

        var noExtraSegment = phy(160, 11, 1, 800).ppduTiming(1500);
        assertFalse(noExtraSegment.hasExtraLdpcSegment());
        assertEquals(0, noExtraSegment.packetExtensionNs());
    }

    @Test
    void matchesTheNominalPacketPaddingTable() {
        // 四个已知场景的最终 a 分别为 1、2、3、4。对照 nominal padding 0、8、16 μs 的三列。
        assertPacketExtension(80, 1, 715, 1, 0, 0, 4_000);
        assertPacketExtension(160, 2, 1500, 2, 0, 0, 8_000);
        assertPacketExtension(160, 1, 1500, 3, 0, 4_000, 12_000);
        assertPacketExtension(160, 1, 2005, 4, 0, 8_000, 16_000);
        assertThrows(IllegalArgumentException.class, () -> new WirelessPhy(80, 11, 1, 800, 34, 2, 4));
    }

    @Test
    void rejectsUnsupportedPhyValuesAndOversizedPpdu() {
        assertThrows(IllegalArgumentException.class, () -> phy(10, 0, 1, 800));
        assertThrows(IllegalArgumentException.class, () -> phy(80, 12, 1, 800));
        assertThrows(IllegalArgumentException.class, () -> phy(80, 0, 0, 800));
        assertThrows(IllegalArgumentException.class, () -> phy(160, 11, 9, 800));
        assertThrows(IllegalArgumentException.class, () -> phy(20, 0, 1, 400));
        assertThrows(IllegalArgumentException.class, () -> new WirelessPhy(20, 0, 1, 800, -1));
        assertThrows(IllegalArgumentException.class, () -> phy(20, 0, 1, 800).ppduDurationNs(0));
        // 低速链路无法在 HE PPDU 最大时长内完成这个报文。
        assertThrows(IllegalArgumentException.class, () -> phy(20, 0, 1, 800).ppduDurationNs(10_000));
        // 校验针对整个 PPDU。同一包在 PE=0 时合法，加上实际 PE 后越过 5484 μs。
        assertEquals(5_483_200, phy(20, 0, 1, 800).ppduDurationNs(5808));
        assertThrows(IllegalArgumentException.class,
                () -> new WirelessPhy(20, 0, 1, 800, MAC_OVERHEAD_BYTES, 2, 16).ppduDurationNs(5808));
    }

    private static WirelessPhy phy(int bandwidthMhz, int mcs, int nss, int giNs) {
        return new WirelessPhy(bandwidthMhz, mcs, nss, giNs, MAC_OVERHEAD_BYTES);
    }

    private static void assertPreamble(int giNs, int ltfType, int nss, long expectedPreambleNs) {
        assertEquals(expectedPreambleNs, new WirelessPhy(160, 11, nss, giNs, 34, ltfType, 0)
                .ppduTiming(1500).preambleNs());
    }

    private static void assertPacketExtension(int bandwidthMhz, int nss, int payloadBytes, int paddingFactor,
                                              long... expectedPeNs) {
        for (int i = 0; i < expectedPeNs.length; i++) {
            var timing = new WirelessPhy(bandwidthMhz, 11, nss, 800, 34, 2, i * 8).ppduTiming(payloadBytes);
            assertEquals(paddingFactor, timing.paddingFactor());
            assertEquals(expectedPeNs[i], timing.packetExtensionNs());
        }
    }
}
