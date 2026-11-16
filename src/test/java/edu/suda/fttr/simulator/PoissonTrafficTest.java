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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoissonTrafficTest {
    @Test
    void routesGeneratedPacketsThroughMfuAndReportsSourceLoads(@TempDir Path directory) throws IOException {
        long durationUs = 100_000;
        long firstSourceUs = 70_000;
        SimulationConfig config = loadPoisson(directory, durationUs,
                "traffic.sfu.1.rate.pps=1000", "traffic.sfu.1.seed=-7", "traffic.sfu.1.packet.bytes=500",
                "traffic.sfu.1.duration.us=" + firstSourceUs, "traffic.sfu.1.class.weights=1,0,0,0",
                "traffic.sfu.2.rate.pps=600", "traffic.sfu.2.seed=53", "traffic.sfu.2.packet.bytes=1500",
                "traffic.sfu.2.class.weights=0,0,0,1");
        String output = run(config);
        List<Map<String, String>> trace = output.lines().map(PoissonTrafficTest::fields).collect(Collectors.toList());
        Map<Integer, Map<String, String>> arrivals = packetEvents(trace, "MFU", "ARRIVAL");
        Map<Integer, Map<String, String>> optical = packetEvents(trace, "MFU", "FIBER_TX_START");
        Map<Integer, Map<String, String>> enqueues = packetEvents(trace, "SFU", "SFU_ENQUEUE");
        Map<Integer, Map<String, String>> deliveries = packetEvents(trace, "STA", "DELIVERED");
        assertEquals(config.packets().size(), arrivals.size());
        assertEquals(arrivals.keySet(), optical.keySet());
        assertEquals(arrivals.keySet(), enqueues.keySet());
        assertEquals(arrivals.keySet(), deliveries.keySet());

        // 两个源使用不同包长和单一业务类型，可反查每包应走哪条光路。
        long previousUs = 0;
        int expectedId = 1;
        for (Packet packet : config.packets()) {
            int sfuId = packet.bytes() == 500 ? 1 : 2;
            List<Integer> linkedStations = sfuId == 1 ? List.of(2, 4) : List.of(1, 3);
            assertTrue(linkedStations.contains(packet.destinationStaId()));
            assertEquals(sfuId == 1 ? 500 : 1500, packet.bytes());
            assertEquals(sfuId == 1 ? AccessCategory.VO : AccessCategory.BK, packet.type());
            assertEquals(expectedId++, packet.id());
            assertTrue(packet.mfuArrivalUs() >= previousUs);
            assertTrue(packet.mfuArrivalUs() < (sfuId == 1 ? firstSourceUs : durationUs));
            previousUs = packet.mfuArrivalUs();

            assertEquals(Long.toString(packet.mfuArrivalUs()), arrivals.get(packet.id()).get("t_us"));
            assertEquals(Long.toString(packet.mfuArrivalUs()), optical.get(packet.id()).get("t_us"));
            assertEquals(Integer.toString(sfuId), optical.get(packet.id()).get("sfu_id"));
            assertEquals(Integer.toString(sfuId), enqueues.get(packet.id()).get("sfu_id"));
            long enqueueUs = packet.mfuArrivalUs() + config.fiberDelayUs();
            assertEquals(Long.toString(enqueueUs), enqueues.get(packet.id()).get("t_us"));
            Map<String, String> delivery = deliveries.get(packet.id());
            assertEquals(Integer.toString(sfuId), delivery.get("sfu_id"));
            assertEquals(Integer.toString(packet.destinationStaId()), delivery.get("sta_id"));
            assertEquals(packet.type().name(), delivery.get("type"));
            assertEquals(Integer.toString(packet.bytes()), delivery.get("bytes"));
            // 光链路时延长于生成窗口，窗口结束后仍应完成已生成报文的投递。
            assertTrue(Long.parseLong(delivery.get("t_us")) >= enqueueUs + config.wifiDelayUs());
        }

        for (int sfuId = 1; sfuId <= config.sfuCount(); sfuId++) {
            List<Packet> packets = sourcePackets(config, sfuId);
            if (sfuId <= 2) {
                assertTrue(packets.size() > 20);
                assertEquals(2, packets.stream().map(Packet::destinationStaId).distinct().count());
            }
            long windowUs = sfuId == 1 ? firstSourceUs : durationUs;
            assertSourceSummary(trace, sfuId, null, windowUs, packets);
            for (AccessCategory type : AccessCategory.values()) {
                List<Packet> categoryPackets = packets.stream().filter(packet -> packet.type() == type)
                        .collect(Collectors.toList());
                assertSourceSummary(trace, sfuId, type, windowUs, categoryPackets);
            }
        }
    }

    @Test
    void reproducesPacketSequenceAndIsolatesSourceRandomStreams(@TempDir Path directory) throws IOException {
        String[] original = {
                "traffic.sfu.1.rate.pps=1000", "traffic.sfu.1.seed=15", "traffic.sfu.1.packet.bytes=950",
                "traffic.sfu.1.class.weights=1,1,1,1",
                "traffic.sfu.2.rate.pps=1000", "traffic.sfu.2.seed=15", "traffic.sfu.2.packet.bytes=950",
                "traffic.sfu.2.class.weights=1,1,1,1"
        };
        SimulationConfig first = loadPoisson(directory, 100_000, original);
        assertEquals(run(first), run(loadPoisson(directory, 100_000, original)));

        // 两台 SFU 使用相同的显式种子，仍不能得到相同的到达序列。
        assertNotEquals(times(sourcePackets(first, 1)), times(sourcePackets(first, 2)));

        // 更改一个源的速率和种子，其他源的时间、类型、目的 STA 仍相同。全局 ID 会重新编号。
        String[] changed = original.clone();
        changed[0] = "traffic.sfu.1.rate.pps=2000";
        changed[1] = "traffic.sfu.1.seed=111";
        SimulationConfig other = loadPoisson(directory, 100_000, changed);
        assertNotEquals(times(sourcePackets(first, 1)), times(sourcePackets(other, 1)));
        assertEquals(sourceMarks(first, 2), sourceMarks(other, 2));
    }

    @Test
    void matchesArrivalRatesExponentialGapsAndClassWeights(@TempDir Path directory) throws IOException {
        long durationUs = 30_000_000;
        SimulationConfig config = loadPoisson(directory, durationUs,
                "traffic.sfu.1.rate.pps=400", "traffic.sfu.1.seed=283", "traffic.sfu.1.packet.bytes=1000",
                "traffic.sfu.1.class.weights=1,2,3,4",
                "traffic.sfu.2.rate.pps=800", "traffic.sfu.2.seed=-312", "traffic.sfu.2.packet.bytes=750",
                "traffic.sfu.2.class.weights=4,3,2,1");
        assertArrivalAndMarkStatistics(sourcePackets(config, 1), durationUs, 400, new double[]{0.1, 0.2, 0.3, 0.4});
        assertArrivalAndMarkStatistics(sourcePackets(config, 2), durationUs, 800, new double[]{0.4, 0.3, 0.2, 0.1});
    }

    @Test
    void retainsMultipleArrivalsInOneMicrosecond(@TempDir Path directory) throws IOException {
        long durationUs = 100;
        SimulationConfig config = loadPoisson(directory, durationUs,
                "traffic.sfu.1.rate.pps=2000000", "traffic.sfu.1.seed=486", "traffic.sfu.1.packet.bytes=64");
        List<Packet> packets = config.packets();
        // 期望约 200 包，超过窗口的微秒数。每个间隔至少取 1 μs 会错误压低速率。
        assertTrue(packets.size() > durationUs);
        assertTrue(packets.stream().allMatch(packet -> packet.mfuArrivalUs() >= 0
                && packet.mfuArrivalUs() < durationUs && packet.type() == AccessCategory.BE));
        assertTrue(times(packets).stream().distinct().count() < packets.size());
    }

    @Test
    void allowsEmptyTrafficAndRejectsAnActiveSourceWithoutStations(@TempDir Path directory) throws IOException {
        SimulationConfig config = loadPoisson(directory, 5000, "traffic.sfu.3.rate.pps=0");
        assertTrue(config.packets().isEmpty());
        List<Map<String, String>> trace = run(config).lines().map(PoissonTrafficTest::fields).collect(Collectors.toList());
        assertEquals(0, packetEvents(trace, "MFU", "ARRIVAL").size());
        assertSourceSummary(trace, 3, null, 5000, List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> loadPoisson(directory, 5000,
                "traffic.sfu.3.rate.pps=1000", "traffic.sfu.3.seed=123", "traffic.sfu.3.packet.bytes=600"));
        assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("sfu") && error.getMessage().contains("3"));
    }

    @Test
    void explicitManualModePreservesLegacyTrace(@TempDir Path directory) throws IOException {
        Path legacy = Path.of("configs", "phase1-multi-sfu-downlink.properties");
        List<String> properties = new ArrayList<>(Files.readAllLines(legacy, StandardCharsets.UTF_8));
        properties.add("traffic.mode=manual");
        Path scenario = Files.write(directory.resolve("manual.properties"), properties, StandardCharsets.UTF_8);
        assertEquals(run(ConfigLoader.load(legacy)), run(ConfigLoader.load(scenario)));
    }

    private static void assertArrivalAndMarkStatistics(List<Packet> packets, long durationUs, double rate,
                                                        double[] expectedShares) {
        assertEquals(rate, packets.size() * 1_000_000.0 / durationUs, rate * 0.05);
        double expectedGapUs = 1_000_000.0 / rate;
        double gapSumUs = 0.0;
        double gapSquaredSum = 0.0;
        int shorterThanMean = 0;
        for (int i = 1; i < packets.size(); i++) {
            long gapUs = packets.get(i).mfuArrivalUs() - packets.get(i - 1).mfuArrivalUs();
            assertTrue(gapUs >= 0);
            gapSumUs += gapUs;
            gapSquaredSum += (double) gapUs * gapUs;
            if (gapUs <= expectedGapUs) {
                shorterThanMean++;
            }
        }
        int gapCount = packets.size() - 1;
        double meanGapUs = gapSumUs / gapCount;
        double stdGapUs = Math.sqrt(gapSquaredSum / gapCount - meanGapUs * meanGapUs);
        // 指数分布的变异系数为 1，理论均值处的累计概率是 1-e⁻¹。
        assertEquals(expectedGapUs, meanGapUs, expectedGapUs * 0.05);
        assertEquals(1.0, stdGapUs / meanGapUs, 0.05);
        assertEquals(1 - Math.exp(-1), (double) shorterThanMean / gapCount, 0.02);

        AccessCategory[] categories = AccessCategory.values();
        for (int i = 0; i < categories.length; i++) {
            AccessCategory category = categories[i];
            long count = packets.stream().filter(packet -> packet.type() == category).count();
            assertEquals(expectedShares[i], (double) count / packets.size(), 0.02, category.name());
        }
    }

    private static SimulationConfig loadPoisson(Path directory, long durationUs, String... sourceProperties) throws IOException {
        List<String> properties = new ArrayList<>(List.of("traffic.mode=poisson", "traffic.duration.us=" + durationUs,
                "sfu.count=3", "sta.count=4", "sta.1.sfu.id=2", "sta.2.sfu.id=1",
                "sta.3.sfu.id=2", "sta.4.sfu.id=1", "fiber.delay.us=200000", "wifi.delay.us=40"));
        Collections.addAll(properties, sourceProperties);
        Path scenario = Files.write(directory.resolve("poisson.properties"), properties, StandardCharsets.UTF_8);
        return ConfigLoader.load(scenario);
    }

    private static List<Packet> sourcePackets(SimulationConfig config, int sfuId) {
        return config.packets().stream().filter(packet -> config.staSfuIds().get(packet.destinationStaId() - 1) == sfuId)
                .collect(Collectors.toList());
    }

    private static List<Long> times(List<Packet> packets) {
        return packets.stream().map(Packet::mfuArrivalUs).collect(Collectors.toList());
    }

    private static List<String> sourceMarks(SimulationConfig config, int sfuId) {
        return sourcePackets(config, sfuId).stream().map(packet -> packet.mfuArrivalUs() + ":" + packet.destinationStaId()
                + ":" + packet.type() + ":" + packet.bytes()).collect(Collectors.toList());
    }

    private static String run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String item : line.split(" ")) {
            int separator = item.indexOf('=');
            if (separator >= 0) {
                fields.put(item.substring(0, separator), item.substring(separator + 1));
            }
        }
        return fields;
    }

    private static Map<Integer, Map<String, String>> packetEvents(List<Map<String, String>> trace, String node, String event) {
        return trace.stream().filter(row -> node.equals(row.get("node")) && event.equals(row.get("event")))
                .collect(Collectors.toMap(row -> Integer.parseInt(row.get("packet_id")), Function.identity()));
    }

    private static void assertSourceSummary(List<Map<String, String>> trace, int sfuId, AccessCategory type,
                                            long windowUs, List<Packet> packets) {
        String summary = type == null ? "TRAFFIC_SFU" : "TRAFFIC_CLASS";
        List<Map<String, String>> rows = trace.stream().filter(row -> summary.equals(row.get("summary"))
                && Integer.toString(sfuId).equals(row.get("sfu_id")) && (type == null || type.name().equals(row.get("type"))))
                .collect(Collectors.toList());
        assertEquals(1, rows.size(), "SFU " + sfuId + " / " + type);
        Map<String, String> row = rows.get(0);
        long bytes = packets.stream().mapToLong(Packet::bytes).sum();
        assertEquals(Integer.toString(packets.size()), row.get("generated_packets"));
        assertEquals(Long.toString(bytes), row.get("generated_bytes"));
        assertEquals(Long.toString(windowUs), row.get("window_us"));
        assertEquals(packets.size() * 1_000_000.0 / windowUs, Double.parseDouble(row.get("actual_rate_pps")), 0.000001);
        assertEquals(bytes * 8.0 / windowUs, Double.parseDouble(row.get("offered_load_mbps")), 0.000001);
    }
}
