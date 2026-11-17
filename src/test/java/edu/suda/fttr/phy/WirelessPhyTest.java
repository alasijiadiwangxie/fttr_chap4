package edu.suda.fttr.phy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void addsAWholeSymbolWhenLdpcExtraSegmentCrossesTheSymbolBoundary() {
        // 80 MHz/MCS 11/1 NSS 时，三段短符号可容纳 6000 位。714 B 加上开销和 SERVICE 恰好达到它。
        // 715 B 越过这一界限，初始 a=4；LDPC 的截短和打孔判定要求额外一段，因此需再发一个符号。
        WirelessPhy phy = phy(80, 11, 1, 800);
        assertEquals(56_800, phy.ppduDurationNs(714));
        assertEquals(70_400, phy.ppduDurationNs(715));
        assertEquals(70_400, new WirelessPhy(80, 11, 1, 800, 35).ppduDurationNs(714));
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
    }

    private static WirelessPhy phy(int bandwidthMhz, int mcs, int nss, int giNs) {
        return new WirelessPhy(bandwidthMhz, mcs, nss, giNs, MAC_OVERHEAD_BYTES);
    }
}
