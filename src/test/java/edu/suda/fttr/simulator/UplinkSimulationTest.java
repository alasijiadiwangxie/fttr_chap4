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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UplinkSimulationTest {
    @Test
    void routesUplinkAndSharesWifiAcrossDirectionsWithoutInterruptingTransmissions(@TempDir Path dir) throws IOException {
        // STA 1 关联 SFU 2，另外两台 STA 共享 SFU 1 的无线资源。光侧和无线时延分别为 10、20 μs。
        SimulationConfig config = load(dir, List.of(
                "traffic.direction=BOTH", "fiber.delay.us=10", "wifi.delay.us=20", "sfu.count=2", "sta.count=3",
                "sta.1.sfu.id=2", "sta.2.sfu.id=1", "sta.3.sfu.id=1", "packet.bytes=1000",
                "packet.arrival.us=0,0,0,20,20,40,70",
                "packet.1.direction=UL", "packet.1.sta.id=2",
                "packet.2.direction=DL", "packet.2.sta.id=3", "packet.2.type=BK",
                "packet.3.direction=UL", "packet.3.sta.id=1",
                "packet.4.direction=UL", "packet.4.sta.id=3", "packet.4.type=VO",
                "packet.5.direction=DL", "packet.5.sta.id=2", "packet.5.type=VI",
                "packet.6.direction=UL", "packet.6.sta.id=2", "packet.6.type=BK",
                "packet.7.direction=UL", "packet.7.sta.id=2", "packet.7.type=VO"));
        List<Map<String, String>> trace = trace(run(config));
        List<Packet> packets = config.packets();

        // 同一 SFU 上下行串行，不同 SFU 并行。包 4 在包 1 结束时到达，优先于已等待的 BK。
        // 包 7 到达时包 2 已在发送，等包 2 完成后才可发送。两条 BK 按入队顺序发出。
        assertEquals(List.of("1@0", "4@20", "5@40", "2@60", "7@80", "6@100"), wifiTimes(trace, "WIFI_TX_START", 1));
        assertEquals(List.of("1@20", "4@40", "5@60", "2@80", "7@100", "6@120"), wifiTimes(trace, "WIFI_TX_END", 1));
        assertEquals(List.of("3@0"), wifiTimes(trace, "WIFI_TX_START", 2));
        assertEquals(List.of("3@20"), wifiTimes(trace, "WIFI_TX_END", 2));

        for (int packetId : List.of(1, 3, 4, 6, 7)) {
            Packet packet = packets.get(packetId - 1);
            int sta = packet.sourceNodeId();
            int sfu = config.staSfuIds().get(sta - 1);
            Map<String, String> arrival = event(trace, "ARRIVAL", packetId, "STA");
            Map<String, String> enqueue = event(trace, "SFU_ENQUEUE", packetId, "STA");
            Map<String, String> select = event(trace, "QUEUE_SELECT", packetId, "SFU");
            Map<String, String> wifiStart = event(trace, "WIFI_TX_START", packetId, "STA");
            Map<String, String> wifiEnd = event(trace, "WIFI_TX_END", packetId, "SFU");
            Map<String, String> fiberStart = event(trace, "FIBER_TX_START", packetId, "SFU");
            Map<String, String> delivery = event(trace, "DELIVERED", packetId, "MFU");
            for (Map<String, String> row : List.of(arrival, enqueue, select, wifiStart, wifiEnd, fiberStart, delivery)) {
                assertEquals("UL", row.get("direction"));
                assertEquals(sta, number(row, "source_sta_id"));
                assertEquals(Packet.DEFAULT_MFU_ID, number(row, "destination_mfu_id"));
            }
            for (Map<String, String> row : List.of(enqueue, select, wifiStart, wifiEnd, fiberStart, delivery)) {
                assertEquals(sfu, number(row, "sfu_id"));
            }
            assertEquals(packet.generationTimeUs(), number(arrival, "t_us"));
            assertEquals(packet.generationTimeUs(), number(enqueue, "t_us"));
            assertEquals(number(wifiStart, "t_us") - number(enqueue, "t_us"), number(wifiEnd, "wifi_queue_wait_us"));
            assertEquals(20, number(wifiEnd, "wifi_service_us"));
            assertEquals(number(wifiEnd, "t_us"), number(fiberStart, "t_us"));
            assertEquals(number(fiberStart, "t_us") + 10, number(delivery, "t_us"));
            assertEquals(number(delivery, "t_us") - packet.generationTimeUs(), number(delivery, "e2e_delay_us"));
        }
        assertEquals(60, number(event(trace, "DELIVERED", 5, "STA"), "t_us"));
        assertEquals(80, number(event(trace, "DELIVERED", 2, "STA"), "t_us"));
        assertEquals(2, number(summary(trace, "GLOBAL", null, null), "sent_packets"));
        Map<String, String> uplink = summary(trace, "UL_GLOBAL", null, null);
        assertEquals(5, number(uplink, "delivered_packets"));
        assertEquals(5000, number(uplink, "delivered_bytes"));
        assertEquals(44.0, Double.parseDouble(uplink.get("avg_e2e_delay_us")));
        Map<String, String> sta2 = summary(trace, "UL_STA", "sta_id", "2");
        assertEquals(3, number(sta2, "delivered_packets"));
        assertEquals(3000, number(sta2, "delivered_bytes"));
        assertEquals(184.62, Double.parseDouble(sta2.get("throughput_mbps")));
        assertEquals(23.33, Double.parseDouble(sta2.get("avg_queue_wait_us")));
        assertEquals(53.33, Double.parseDouble(sta2.get("avg_e2e_delay_us")));
        Map<String, String> totals = trace.get(trace.size() - 1);
        assertEquals(7, number(totals, "delivered_packets"));
        assertEquals(7000, number(totals, "delivered_bytes"));
        assertEquals(120, number(totals, "wifi_busy_us"));
        assertEquals(130, number(totals, "simulation_end_us"));
    }

    @Test
    void generatesAndReportsIndependentPoissonTrafficForEachSta(@TempDir Path dir) throws IOException {
        List<String> properties = List.of(
                "traffic.mode=poisson", "traffic.direction=UL", "traffic.duration.us=200000",
                "fiber.delay.us=10", "wifi.delay.us=20", "sfu.count=2", "sta.count=3", "sta.1.sfu.id=2",
                "traffic.sta.1.rate.pps=800", "traffic.sta.1.duration.us=150000", "traffic.sta.1.seed=31",
                "traffic.sta.1.packet.bytes=240", "traffic.sta.1.class.weights=1,0,0,0",
                "traffic.sta.2.rate.pps=700", "traffic.sta.2.seed=31", "traffic.sta.2.packet.bytes=750",
                "traffic.sta.2.class.weights=0,0,0,1");
        SimulationConfig config = load(dir, properties);
        String output = run(config);
        assertEquals(output, run(load(dir, properties)));
        List<Map<String, String>> trace = trace(output);
        List<Packet> packets = config.packets();
        assertTrue(packets.stream().allMatch(packet -> packet.direction() == Packet.Direction.UL
                && packet.destinationNodeId() == Packet.DEFAULT_MFU_ID));
        assertEquals(packets.size(), number(summary(trace, "UL_GLOBAL", null, null), "delivered_packets"));
        assertEquals(0, number(summary(trace, "GLOBAL", null, null), "sent_packets"));

        for (int sta = 1; sta <= 3; sta++) {
            int sourceId = sta;
            List<Packet> source = packets.stream().filter(packet -> packet.sourceNodeId() == sourceId)
                    .collect(Collectors.toList());
            long windowUs = sta == 1 ? 150_000 : 200_000;
            int sfu = config.staSfuIds().get(sta - 1);
            long bytes = source.stream().mapToLong(Packet::bytes).sum();
            assertTrue(source.stream().allMatch(packet -> packet.generationTimeUs() < windowUs));
            if (sta < 3) {
                assertEquals(sta == 1 ? 800.0 : 700.0, source.size() * 1_000_000.0 / windowUs, 200.0);
                AccessCategory type = sta == 1 ? AccessCategory.VO : AccessCategory.BK;
                int packetBytes = sta == 1 ? 240 : 750;
                assertTrue(source.stream().allMatch(packet -> packet.type() == type && packet.bytes() == packetBytes));
            } else {
                assertTrue(source.isEmpty());
            }
            Map<String, String> stats = summary(trace, "TRAFFIC_UL_STA", "sta_id", Integer.toString(sta));
            assertEquals(sfu, number(stats, "sfu_id"));
            assertEquals(source.size(), number(stats, "generated_packets"));
            assertEquals(bytes, number(stats, "generated_bytes"));
            assertEquals(windowUs, number(stats, "window_us"));
            assertEquals(source.size() * 1_000_000.0 / windowUs, Double.parseDouble(stats.get("actual_rate_pps")), 0.000001);
            assertEquals(bytes * 8.0 / windowUs, Double.parseDouble(stats.get("offered_load_mbps")), 0.000001);
            List<Map<String, String>> classes = trace.stream()
                    .filter(row -> "TRAFFIC_UL_CLASS".equals(row.get("summary")) && Integer.toString(sourceId).equals(row.get("sta_id")))
                    .collect(Collectors.toList());
            assertEquals(4, classes.size());
            for (Map<String, String> row : classes) {
                long categoryCount = source.stream().filter(packet -> packet.type().name().equals(row.get("type"))).count();
                assertEquals(categoryCount, number(row, "generated_packets"));
            }
            Map<String, String> delivered = summary(trace, "UL_STA", "sta_id", Integer.toString(sta));
            assertEquals(source.size(), number(delivered, "delivered_packets"));
            assertEquals(bytes, number(delivered, "delivered_bytes"));
        }

        // 两台 STA 使用相同的外部种子，独立随机流仍产生不同的生成时刻。
        assertNotEquals(firstArrival(packets, 1), firstArrival(packets, 2));
    }

    @Test
    void keepsDownlinkArrivalsWhenEnablingUplinkInPoissonMode(@TempDir Path dir) throws IOException {
        List<String> sources = List.of(
                "traffic.mode=poisson", "traffic.duration.us=200000", "fiber.delay.us=10", "wifi.delay.us=20",
                "traffic.sfu.1.rate.pps=1000", "traffic.sfu.1.seed=77", "traffic.sfu.1.packet.bytes=1200");
        SimulationConfig downlink = load(dir, sources);
        List<String> mixedProperties = new ArrayList<>(sources);
        mixedProperties.addAll(List.of("traffic.direction=BOTH", "traffic.sta.1.rate.pps=800",
                "traffic.sta.1.seed=77", "traffic.sta.1.packet.bytes=600"));
        SimulationConfig mixed = load(dir, mixedProperties);
        assertTrue(mixed.packets().stream().anyMatch(packet -> packet.direction() == Packet.Direction.UL));
        List<Long> downlinkTimes = mixed.packets().stream().filter(packet -> packet.direction() == Packet.Direction.DL)
                .map(Packet::generationTimeUs).collect(Collectors.toList());
        assertEquals(downlink.packetArrivalsUs(), downlinkTimes);
        List<Map<String, String>> trace = trace(run(mixed));
        assertEquals(downlinkTimes.size(), number(summary(trace, "TRAFFIC_SFU", "sfu_id", "1"), "generated_packets"));
        assertEquals(mixed.packets().size() - downlinkTimes.size(),
                number(summary(trace, "TRAFFIC_UL_STA", "sta_id", "1"), "generated_packets"));
        assertEquals(mixed.packets().size(), number(trace.get(trace.size() - 1), "delivered_packets"));
    }

    private static long firstArrival(List<Packet> packets, int staId) {
        return packets.stream().filter(packet -> packet.sourceNodeId() == staId)
                .findFirst().orElseThrow().generationTimeUs();
    }

    private static SimulationConfig load(Path dir, List<String> properties) throws IOException {
        Path path = Files.write(dir.resolve("scenario.properties"), properties, StandardCharsets.UTF_8);
        return ConfigLoader.load(path);
    }

    private static String run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(out));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> trace(String output) {
        return output.lines().map(line -> {
            Map<String, String> fields = new HashMap<>();
            for (String item : line.split(" ")) {
                int separator = item.indexOf('=');
                if (separator >= 0) {
                    fields.put(item.substring(0, separator), item.substring(separator + 1));
                }
            }
            return fields;
        }).collect(Collectors.toList());
    }

    private static Map<String, String> event(List<Map<String, String>> trace, String event, int packetId, String node) {
        List<Map<String, String>> rows = trace.stream().filter(row -> event.equals(row.get("event"))
                && Integer.toString(packetId).equals(row.get("packet_id"))).collect(Collectors.toList());
        assertEquals(1, rows.size(), event + " / packet " + packetId);
        Map<String, String> row = rows.get(0);
        assertEquals(node, row.get("node"));
        return row;
    }

    private static List<String> wifiTimes(List<Map<String, String>> trace, String event, int sfuId) {
        return trace.stream().filter(row -> event.equals(row.get("event"))
                && Integer.toString(sfuId).equals(row.get("sfu_id")))
                .map(row -> row.get("packet_id") + "@" + row.get("t_us")).collect(Collectors.toList());
    }

    private static Map<String, String> summary(List<Map<String, String>> trace, String kind, String key, String value) {
        List<Map<String, String>> rows = trace.stream().filter(row -> kind.equals(row.get("summary"))
                && (key == null || value.equals(row.get(key)))).collect(Collectors.toList());
        assertEquals(1, rows.size(), kind + " / " + value);
        return rows.get(0);
    }

    private static long number(Map<String, String> row, String key) {
        return Long.parseLong(row.get(key));
    }
}
