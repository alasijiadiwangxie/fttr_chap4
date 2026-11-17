package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static edu.suda.fttr.entity.AccessCategory.BE;
import static edu.suda.fttr.entity.AccessCategory.BK;
import static edu.suda.fttr.entity.AccessCategory.VI;
import static edu.suda.fttr.entity.AccessCategory.VO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerClassPoissonTest {
    @Test
    void configuresDifferentSizesAndRatesAndReportsBytesForEachClass(@TempDir Path directory) throws IOException {
        long staWindowUs = 2_000_000;
        SimulationConfig config = load(directory, List.of(
                "traffic.sta.1.seed=42", "traffic.sta.1.duration.us=" + staWindowUs,
                "traffic.sta.1.class.VO.rate.mbps=2", "traffic.sta.1.class.VO.packet.bytes=200",
                "traffic.sta.1.class.VI.rate.mbps=2", "traffic.sta.1.class.VI.packet.bytes=1000",
                "traffic.sta.1.class.BK.rate.pps=75", "traffic.sta.1.class.BK.packet.bytes=400",
                "traffic.sta.2.seed=42", "traffic.sta.2.rate.pps=90", "traffic.sta.2.packet.bytes=450",
                "traffic.sfu.1.seed=42", "traffic.sfu.1.class.BE.rate.mbps=0.8", "traffic.sfu.1.class.BE.packet.bytes=1000"));
        List<Packet> packets = config.packets();

        // 同样是 2 Mb/s，200 B 的 VO 需要 1250 包/s，1000 B 的 VI 只需 250 包/s。
        List<Packet> vo = uplink(packets, 1, VO).collect(Collectors.toList());
        List<Packet> vi = uplink(packets, 1, VI).collect(Collectors.toList());
        List<Packet> bk = uplink(packets, 1, BK).collect(Collectors.toList());
        assertSource(vo, 200, 1250, staWindowUs);
        assertSource(vi, 1000, 250, staWindowUs);
        assertSource(bk, 400, 75, staWindowUs);
        assertEquals(0, uplink(packets, 1, BE).count());
        List<Packet> downlink = packets.stream().filter(packet -> !packet.isUplink()).collect(Collectors.toList());
        assertTrue(downlink.stream().allMatch(packet -> packet.type() == BE));
        assertSource(downlink, 1000, 100, 3_000_000);

        // 同一场景也允许另一台 STA 使用原有总速率与包长配置。
        List<Packet> legacy = uplink(packets, 2, BE).collect(Collectors.toList());
        assertSource(legacy, 450, 90, 3_000_000);
        assertEquals(legacy.size(), packets.stream().filter(packet -> packet.isUplink() && packet.staId() == 2).count());

        List<String> trace = run(config);
        assertTraffic(trace, "TRAFFIC_UL_CLASS sta_id=1 sfu_id=1 type=VO", vo, staWindowUs);
        assertTraffic(trace, "TRAFFIC_UL_CLASS sta_id=1 sfu_id=1 type=VI", vi, staWindowUs);
        assertTraffic(trace, "TRAFFIC_UL_CLASS sta_id=1 sfu_id=1 type=BK", bk, staWindowUs);
        assertTraffic(trace, "TRAFFIC_UL_CLASS sta_id=1 sfu_id=1 type=BE", List.of(), staWindowUs);
        assertTraffic(trace, "TRAFFIC_CLASS sfu_id=1 type=BE", downlink, 3_000_000);
        List<Packet> sta1 = packets.stream().filter(packet -> packet.isUplink() && packet.staId() == 1)
                .collect(Collectors.toList());
        assertTraffic(trace, "TRAFFIC_UL_STA sta_id=1 sfu_id=1", sta1, staWindowUs);
        Map<String, String> delivery = summary(trace, "UL_GLOBAL");
        assertEquals(sta1.size() + legacy.size(), Long.parseLong(delivery.get("delivered_packets")));
        assertEquals(bytes(sta1) + bytes(legacy), Long.parseLong(delivery.get("delivered_bytes")));
    }

    @Test
    void preservesOtherStreamsWhenTrafficIsAddedOrChanged(@TempDir Path directory) throws IOException {
        List<String> sources = List.of(
                "traffic.sta.1.seed=42", "traffic.sta.2.seed=42",
                "traffic.sta.1.class.VO.rate.pps=300", "traffic.sta.1.class.VO.packet.bytes=200",
                "traffic.sta.1.class.VI.rate.pps=300", "traffic.sta.1.class.VI.packet.bytes=1000",
                "traffic.sta.2.class.VI.rate.pps=300", "traffic.sta.2.class.VI.packet.bytes=1000");
        List<Packet> first = load(directory, sources).packets();
        assertEquals(signature(first), signature(load(directory, sources).packets()));
        // 同源同速率和同种子仍须分别采样，不会同步产生 VO、VI。
        assertNotEquals(times(first, 1, VO), times(first, 1, VI));
        assertNotEquals(times(first, 1, VI), times(first, 2, VI));

        List<String> changed = new ArrayList<>(sources);
        changed.set(2, "traffic.sta.1.class.VO.rate.pps=600");
        changed.addAll(List.of(
                "traffic.sfu.1.seed=42", "traffic.sfu.1.class.VI.rate.pps=300", "traffic.sfu.1.class.VI.packet.bytes=250",
                "traffic.sta.1.class.BE.rate.pps=100", "traffic.sta.1.class.BE.packet.bytes=750"));
        List<Packet> second = load(directory, changed).packets();
        assertNotEquals(times(first, 1, VO), times(second, 1, VO));
        assertEquals(times(first, 1, VI), times(second, 1, VI));
        assertEquals(times(first, 2, VI), times(second, 2, VI));
        List<Long> downlinkTimes = second.stream().filter(packet -> !packet.isUplink() && packet.type() == VI)
                .map(Packet::generationTimeUs).collect(Collectors.toList());
        assertFalse(downlinkTimes.isEmpty());
        assertNotEquals(times(second, 1, VI), downlinkTimes);
        assertTrue(first.stream().filter(packet -> packet.staId() == 2).allMatch(packet -> packet.type() == VI));
    }

    @Test
    void rejectsConflictingClassConfiguration(@TempDir Path directory) {
        List<String> valid = List.of("traffic.sta.1.seed=42", "traffic.sta.1.class.VO.rate.mbps=2",
                "traffic.sta.1.class.VO.packet.bytes=200");
        for (String conflict : List.of("traffic.sta.1.class.VO.rate.pps=1200", "traffic.sta.1.rate.pps=1200",
                "traffic.sta.1.packet.bytes=200", "traffic.sta.1.class.weights=1,0,0,0")) {
            List<String> properties = new ArrayList<>(valid);
            properties.add(conflict);
            assertThrows(IllegalArgumentException.class, () -> load(directory, properties), conflict);
        }
        assertThrows(IllegalArgumentException.class, () -> load(directory, valid.subList(0, 2)));
        assertThrows(IllegalArgumentException.class, () -> load(directory, valid.subList(1, 3)));
    }

    private static SimulationConfig load(Path directory, List<String> sources) throws IOException {
        List<String> properties = new ArrayList<>(List.of("traffic.mode=poisson", "traffic.direction=BOTH",
                "traffic.duration.us=3000000", "sfu.count=1", "sta.count=2", "fiber.delay.us=10", "wifi.delay.us=20"));
        properties.addAll(sources);
        Path path = Files.write(directory.resolve("class-traffic.properties"), properties, StandardCharsets.UTF_8);
        return ConfigLoader.load(path);
    }

    private static Stream<Packet> uplink(List<Packet> packets, int staId, AccessCategory type) {
        return packets.stream().filter(packet -> packet.isUplink() && packet.staId() == staId && packet.type() == type);
    }

    private static List<Long> times(List<Packet> packets, int staId, AccessCategory type) {
        return uplink(packets, staId, type).map(Packet::generationTimeUs).collect(Collectors.toList());
    }

    private static List<String> signature(List<Packet> packets) {
        return packets.stream().map(packet -> packet.id() + ":" + packet.direction() + ":" + packet.sourceNodeId()
                + ":" + packet.destinationNodeId() + ":" + packet.type() + ":" + packet.bytes() + ":" + packet.generationTimeUs())
                .collect(Collectors.toList());
    }

    private static void assertSource(List<Packet> packets, int packetBytes, double ratePps, long durationUs) {
        double expectedPackets = ratePps * durationUs / 1_000_000.0;
        assertEquals(expectedPackets, packets.size(), 6 * Math.sqrt(expectedPackets));
        assertTrue(packets.stream().allMatch(packet -> packet.bytes() == packetBytes
                && packet.generationTimeUs() < durationUs));
    }

    private static long bytes(List<Packet> packets) {
        return packets.stream().mapToLong(Packet::bytes).sum();
    }

    private static List<String> run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(out));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static void assertTraffic(List<String> trace, String selector, List<Packet> packets, long durationUs) {
        Map<String, String> fields = summary(trace, selector);
        assertEquals(packets.size(), Long.parseLong(fields.get("generated_packets")));
        assertEquals(bytes(packets), Long.parseLong(fields.get("generated_bytes")));
        assertEquals(durationUs, Long.parseLong(fields.get("window_us")));
        assertEquals(packets.size() * 1_000_000.0 / durationUs, Double.parseDouble(fields.get("actual_rate_pps")), 0.000001);
        assertEquals(bytes(packets) * 8.0 / durationUs, Double.parseDouble(fields.get("offered_load_mbps")), 0.000001);
    }

    private static Map<String, String> summary(List<String> trace, String selector) {
        List<String> rows = trace.stream().filter(line -> line.startsWith("summary=" + selector + " "))
                .collect(Collectors.toList());
        assertEquals(1, rows.size(), selector);
        Map<String, String> fields = new HashMap<>();
        for (String field : rows.get(0).split(" ")) {
            String[] pair = field.split("=", 2);
            fields.put(pair[0], pair[1]);
        }
        return fields;
    }
}
