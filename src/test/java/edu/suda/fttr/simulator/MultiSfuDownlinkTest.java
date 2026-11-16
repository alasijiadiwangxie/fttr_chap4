package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiSfuDownlinkTest {
    @Test
    void routesByAssociationAndPreservesIndependentPriorityQueues() throws IOException {
        // STA ID 和 SFU ID 交错，防止按下标或相等 ID 错误转发。两个 SFU 首包类型不同，
        // 发送期间各自收到四类业务，并在首包完成时再收到一条同类 VO。
        List<Map<String, String>> trace = run(Path.of("configs", "phase1-multi-sfu-downlink.properties"));

        List<String> expectedRoutes = List.of("1:1>2", "2:2>1", "3:1>4", "4:2>3", "5:1>4", "6:2>1",
                "7:1>2", "8:2>3", "9:1>2", "10:2>1", "11:1>4", "12:2>3", "13:2>1");
        assertEquals(expectedRoutes, routes(events(trace, "FIBER_TX_START"), "destination_sta_id"));
        assertEquals(expectedRoutes, routes(events(trace, "SFU_ENQUEUE"), "destination_sta_id"));
        List<String> actualDeliveries = routes(events(trace, "DELIVERED"), "sta_id");
        assertEquals(expectedRoutes.size(), actualDeliveries.size());
        assertEquals(Set.copyOf(expectedRoutes), Set.copyOf(actualDeliveries));

        // 本地队列的首个快照不能看到另一 SFU 的等待报文。同刻到达的 VO 应在四类选队前入队。
        assertQueueSnapshot(trace, 1, 100, "1", 0, 0, 1, 0);
        assertQueueSnapshot(trace, 2, 100, "2", 0, 0, 0, 1);
        assertQueueSnapshot(trace, 1, 140, "9", 2, 1, 1, 1);
        assertQueueSnapshot(trace, 2, 140, "10", 2, 1, 1, 1);

        // 两个 SFU 同时从 100 μs 发送至 140 μs。初始的 BE 和 BK 均发完，
        // 各 SFU 随后独立按 VO、VI、BE、BK 选队，同类 VO 保持 FIFO。
        assertEquals(List.of("1:BE@100", "9:VO@140", "11:VO@180", "7:VI@220", "5:BE@260", "3:BK@300"),
                timeline(trace, "WIFI_TX_START", 1));
        assertEquals(List.of("2:BK@100", "10:VO@140", "12:VO@180", "8:VI@220", "6:BE@260", "4:BK@300", "13:BE@400"),
                timeline(trace, "WIFI_TX_START", 2));
        assertEquals(List.of("1:BE@140", "9:VO@180", "11:VO@220", "7:VI@260", "5:BE@300", "3:BK@340"),
                timeline(trace, "WIFI_TX_END", 1));
        assertEquals(List.of("2:BK@140", "10:VO@180", "12:VO@220", "8:VI@260", "6:BE@300", "4:BK@340", "13:BE@440"),
                timeline(trace, "WIFI_TX_END", 2));

        assertFields(summary(trace, "SFU", "sfu_id", 1), Map.of("sent_packets", "6", "sent_bytes", "6000",
                "throughput_mbps", "109.09", "avg_queue_wait_us", "86.67"));
        assertFields(summary(trace, "SFU", "sfu_id", 2), Map.of("sent_packets", "7", "sent_bytes", "7000",
                "throughput_mbps", "127.27", "avg_queue_wait_us", "74.29"));
        assertFields(summary(trace, "STA", "sta_id", 1), Map.of("sfu_id", "2",
                "received_packets", "4", "received_bytes", "4000", "throughput_mbps", "72.73", "avg_e2e_delay_us", "185.00"));
        assertFields(summary(trace, "STA", "sta_id", 5), Map.of("sfu_id", "1", "received_packets", "0"));

        assertFields(summary(trace, "GLOBAL", null, 0), Map.of("sent_packets", "13",
                "avg_queue_wait_us", "80.00", "avg_e2e_delay_us", "220.00"));
        // 全局忙时取并集 [100,340) 和 [400,440)，同时发送时只累计一次。
        assertFields(trace.get(trace.size() - 1), Map.of("delivered_packets", "13", "delivered_bytes", "13000",
                "wifi_busy_us", "280", "wifi_idle_us", "160", "simulation_end_us", "440"));
    }

    @Test
    void handlesPartiallyOverlappingSendsAndConservesPacketsAndBytes(@TempDir Path directory) throws IOException {
        // STA 1 的三包同时到达，STA 2 间歇收包。SFU 3 没有关联的 STA。
        List<Map<String, String>> trace = run(writeScenario(directory, List.of(
                "sfu.count=3", "sta.count=2", "sta.1.sfu.id=1", "sta.2.sfu.id=2",
                "fiber.delay.us=100", "wifi.delay.us=40", "packet.arrival.us=0,0,0,15,100",
                "packet.1.bytes=100", "packet.2.bytes=200", "packet.3.bytes=300",
                "packet.4.bytes=400", "packet.5.bytes=500", "packet.4.sta.id=2", "packet.5.sta.id=2")));

        // 115 μs 时 SFU 1 仍有两包等待，SFU 2 收包后立即发送，第二次收包也独立开始发送。
        assertQueueSnapshot(trace, 1, 100, "1", 0, 0, 3, 0);
        assertEquals(List.of("1:BE@100", "2:BE@140", "3:BE@180"), timeline(trace, "WIFI_TX_START", 1));
        assertEquals(List.of("1:BE@140", "2:BE@180", "3:BE@220"), timeline(trace, "WIFI_TX_END", 1));
        assertEquals(List.of("4:BE@115", "5:BE@200"), timeline(trace, "WIFI_TX_START", 2));
        assertEquals(List.of("4:BE@155", "5:BE@240"), timeline(trace, "WIFI_TX_END", 2));

        // 逐包核对只交付一次、投递目标与字节量，再核对各统计层级与输入守恒。
        List<Map<String, String>> deliveries = events(trace, "DELIVERED");
        assertEquals(List.of("1:1>1", "4:2>2", "2:1>1", "3:1>1", "5:2>2"), routes(deliveries, "sta_id"));
        assertEquals(List.of("100", "400", "200", "300", "500"),
                deliveries.stream().map(row -> row.get("bytes")).collect(Collectors.toList()));
        assertFields(summary(trace, "SFU", "sfu_id", 1), Map.of("sent_packets", "3", "sent_bytes", "600"));
        assertFields(summary(trace, "SFU", "sfu_id", 2), Map.of("sent_packets", "2", "sent_bytes", "900"));
        assertFields(summary(trace, "SFU", "sfu_id", 3), Map.of("sent_packets", "0", "sent_bytes", "0",
                "throughput_mbps", "0.00", "avg_queue_wait_us", "0.00"));
        assertFields(summary(trace, "STA", "sta_id", 1), Map.of("received_packets", "3", "received_bytes", "600"));
        assertFields(summary(trace, "STA", "sta_id", 2), Map.of("received_packets", "2", "received_bytes", "900"));
        assertFields(summary(trace, "GLOBAL", null, 0), Map.of("sent_packets", "5"));

        // SFU 1 忙时为 [100,220)，SFU 2 为 [115,155) 和 [200,240)，重叠 60 μs。
        assertFields(trace.get(trace.size() - 1), Map.of("delivered_packets", "5", "delivered_bytes", "1500",
                "wifi_busy_us", "140", "wifi_idle_us", "100", "simulation_end_us", "240"));
    }

    @Test
    void explicitSingleSfuMatchesLegacyMultiStaScenario(@TempDir Path directory) throws IOException {
        Path legacy = Path.of("configs", "phase1-multi-sta-downlink.properties");
        List<String> explicit = new ArrayList<>(Files.readAllLines(legacy, StandardCharsets.UTF_8));
        explicit.addAll(List.of("sfu.count=1", "sta.1.sfu.id=1", "sta.3.sfu.id=1"));
        assertEquals(run(legacy), run(writeScenario(directory, explicit)));
    }

    @Test
    void rejectsInvalidAssociationBeforeSimulation(@TempDir Path directory) throws IOException {
        List<String> base = List.of("sfu.count=2", "sta.count=2", "fiber.delay.us=0", "wifi.delay.us=40",
                "packet.bytes=1000", "packet.arrival.us=0");
        for (String invalid : List.of("sta.1.sfu.id=0", "sta.2.sfu.id=3", "sta.3.sfu.id=1")) {
            List<String> properties = new ArrayList<>(base);
            properties.add(invalid);
            Path invalidScenario = writeScenario(directory, properties);
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(invalidScenario), invalid);
        }
    }

    @Test
    void checksClockOverflowSeparatelyForEachSfu(@TempDir Path directory) throws IOException {
        long arrivalUs = Long.MAX_VALUE - 40;
        List<String> properties = new ArrayList<>(List.of("sfu.count=2", "sta.count=2", "fiber.delay.us=0",
                "wifi.delay.us=40", "packet.bytes=1000", "packet.arrival.us=" + arrivalUs + "," + arrivalUs,
                "packet.2.sta.id=2"));
        // 两包并行都能在时钟上界之前完成。若改为同一 SFU，排队会使第二包越界。
        properties.add("sta.2.sfu.id=2");
        List<Map<String, String>> parallel = run(writeScenario(directory, properties));
        assertFields(parallel.get(parallel.size() - 1), Map.of("delivered_packets", "2", "wifi_busy_us", "40",
                "simulation_end_us", Long.toString(Long.MAX_VALUE)));

        properties.set(properties.size() - 1, "sta.2.sfu.id=1");
        Path serialized = writeScenario(directory, properties);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(serialized));
    }

    private Path writeScenario(Path directory, List<String> properties) throws IOException {
        return Files.write(directory.resolve("multisfu.properties"), properties, StandardCharsets.UTF_8);
    }

    private List<Map<String, String>> run(Path path) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(this::fields).collect(Collectors.toList());
    }

    private Map<String, String> fields(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String item : line.split(" ")) {
            int separator = item.indexOf('=');
            if (separator >= 0) {
                fields.put(item.substring(0, separator), item.substring(separator + 1));
            }
        }
        return fields;
    }

    private List<Map<String, String>> events(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private List<String> routes(List<Map<String, String>> events, String staKey) {
        return events.stream().map(row -> row.get("packet_id") + ":" + row.get("sfu_id") + ">" + row.get(staKey))
                .collect(Collectors.toList());
    }

    private List<String> timeline(List<Map<String, String>> trace, String event, int sfuId) {
        return events(trace, event).stream().filter(row -> Integer.toString(sfuId).equals(row.get("sfu_id")))
                .map(row -> row.get("packet_id") + ":" + row.get("type") + "@" + row.get("t_us"))
                .collect(Collectors.toList());
    }

    private void assertQueueSnapshot(List<Map<String, String>> trace, int sfuId, long timeUs,
                                     String packetId, int vo, int vi, int be, int bk) {
        List<Map<String, String>> snapshots = events(trace, "QUEUE_SELECT").stream()
                .filter(row -> Integer.toString(sfuId).equals(row.get("sfu_id"))
                        && Long.toString(timeUs).equals(row.get("t_us")))
                .collect(Collectors.toList());
        assertEquals(1, snapshots.size(), "SFU " + sfuId + " at " + timeUs + " μs");
        assertFields(snapshots.get(0), Map.of("packet_id", packetId, "vo_queue", Integer.toString(vo),
                "vi_queue", Integer.toString(vi), "be_queue", Integer.toString(be), "bk_queue", Integer.toString(bk)));
    }

    private Map<String, String> summary(List<Map<String, String>> trace, String kind, String idKey, int id) {
        List<Map<String, String>> rows = trace.stream().filter(row -> kind.equals(row.get("summary"))
                        && (idKey == null || Integer.toString(id).equals(row.get(idKey))))
                .collect(Collectors.toList());
        assertEquals(1, rows.size(), kind + " " + id);
        return rows.get(0);
    }

    private void assertFields(Map<String, String> row, Map<String, String> expected) {
        expected.forEach((key, value) -> assertEquals(value, row.get(key), key));
    }
}
