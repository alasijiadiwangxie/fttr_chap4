package edu.suda.fttr.phy;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.link.WifiLink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmpduAirtimeTest {
    private final WirelessPhy lcm = new WirelessPhy(20, 7, 1, 800, 34);
    private final WirelessPhy hcm = new WirelessPhy(80, 7, 2, 800, 34);

    @Test
    void alignsEveryNonfinalSubframeAndIncludesExactlyOneDelimiterPerMpdu() {
        WifiLink link = new WifiLink(lcm);
        Packet first = new Packet(1, 1_500, 0);
        Packet second = new Packet(2, 1_501, 0);
        Packet third = new Packet(3, 1_500, 0);

        assertEquals(1_534, link.ampduBytes(first, List.of(first)));
        assertEquals(1_536 + 1_535, link.ampduBytes(first, List.of(first, second)));
        assertEquals(1_536 + 1_536 + 1_534, link.ampduBytes(first, List.of(first, second, third)));
        assertEquals(11_458, lcm.ampduSubframeBytes(11_424));
        assertThrows(IllegalArgumentException.class, () -> lcm.ampduSubframeBytes(11_425));
        assertThrows(IllegalArgumentException.class, () -> lcm.ampduSubframeBytes(0));
    }

    @Test
    void appliesOnePreambleAndOneOfdmRoundingForTheWholeAggregate() {
        List<Packet> packets = IntStream.rangeClosed(1, 8).mapToObj(id -> new Packet(id, 1_500, 0))
                .collect(Collectors.toList());
        Packet head = packets.get(0);
        WifiLink lowCapability = new WifiLink(lcm);
        int twoBytes = lowCapability.ampduBytes(head, packets.subList(0, 2));
        int eightBytes = lowCapability.ampduBytes(head, packets);

        assertEquals(3_070, twoBytes);
        assertEquals(12_286, eightBytes);
        assertEquals(22, lcm.ampduPpduTiming(twoBytes).dataSymbols());
        assertEquals(342_400, lowCapability.ampduDurationNs(head, twoBytes));
        assertEquals(1_199_200, lowCapability.ampduDurationNs(head, eightBytes));
        assertEquals(lcm.ppduDurationNs(head.bytes()), lowCapability.ampduDurationNs(head, 1_534));
        assertThrows(IllegalArgumentException.class, () -> lcm.ppduDurationNs(eightBytes - 34));

        WifiLink associationPhy = new WifiLink(lcm, null, Map.of(head.staId(), hcm));
        assertEquals(91_200, associationPhy.ampduDurationNs(head, twoBytes));
        assertEquals(200_000, associationPhy.ampduDurationNs(head, eightBytes));
    }

    @Test
    void respectsThePpduDurationAndApepLimits() {
        assertThrows(IllegalArgumentException.class, () -> lcm.ampduPpduTiming(0));
        assertThrows(IllegalArgumentException.class,
                () -> lcm.ampduPpduTiming(WirelessPhy.MAX_AMPDU_APEP_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> lcm.ampduPpduTiming(65_535));
    }

    @Test
    void roundsNormalAckAndSixtyFourBitCompressedBlockAckIndependently() {
        WifiFrameExchange standardRate = new WifiFrameExchange(16, 24);
        assertEquals(28_000, standardRate.ackNs());
        assertEquals(44_000, standardRate.responseDurationNs());
        assertEquals(32_000, standardRate.blockAckNs());
        assertEquals(48_000, standardRate.blockAckResponseDurationNs());

        // 6 Mb/s 每 Legacy OFDM 符号传输 24 bit。32 B 加 SERVICE 和 Tail 共 278 bit，使用 12 个符号。
        WifiFrameExchange basicRate = new WifiFrameExchange(16, 6);
        assertEquals(68_000, basicRate.blockAckNs());
        assertEquals(84_000, basicRate.blockAckResponseDurationNs());
    }
}
