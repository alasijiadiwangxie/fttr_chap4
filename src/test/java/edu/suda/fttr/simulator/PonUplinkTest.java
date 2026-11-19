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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonUplinkTest {
    @Test
    void serializes1200BytesInThreePointEightFourMicroseconds(@TempDir Path directory) throws IOException {
        // 固定无线时延结束后才入光侧队列。独立的传播参数保留小数微秒。
        List<Map<String, String>> trace = run(directory, "traffic.direction=UL", "fiber.uplink.mode=pon-shared",
                "fiber.uplink.propagation.us=3.025", "wifi.delay.us=2", "packet.arrival.us=7", "packet.bytes=1200");

        assertTimeline(List.of("1@7"), trace, "WIFI_TX_START", "STA");
        assertTimeline(List.of("1@9"), trace, "PON_UPLINK_ENQUEUE", "SFU");
        assertTimeline(List.of("1@9"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("1@12.84"), trace, "FIBER_TX_END", "SFU");
        assertTimeline(List.of("1@15.865"), trace, "DELIVERED", "MFU");
        assertEquals("8.865", events(trace, "DELIVERED", "MFU").get(0).get("e2e_delay_us"));
    }

    @Test
    void serializesSimultaneousRequestsAndReleasesBeforePropagationEnds(@TempDir Path directory) throws IOException {
        // SFU 2 依配置顺序先请求，验证同刻仲裁没有按 SFU ID 或业务优先级重排。
        String[] properties = {
                "traffic.direction=UL", "fiber.uplink.mode=pon-shared", "fiber.uplink.propagation.us=10",
                "wifi.delay.us=2", "sfu.count=2", "sta.count=2", "sta.1.sfu.id=2", "sta.2.sfu.id=1",
                "packet.arrival.us=0,0", "packet.bytes=1200", "packet.1.type=BK", "packet.2.type=VO",
                "packet.2.sta.id=2"
        };
        List<Map<String, String>> trace = run(directory, properties);

        assertEquals(trace, run(directory, properties));
        assertTimeline(List.of("1@2", "2@2"), trace, "PON_UPLINK_ENQUEUE", "SFU");
        assertTimeline(List.of("1@2", "2@5.84"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("1@5.84", "2@9.68"), trace, "FIBER_TX_END", "SFU");
        assertTimeline(List.of("1@15.84", "2@19.68"), trace, "DELIVERED", "MFU");
        for (String event : List.of("PON_UPLINK_ENQUEUE", "FIBER_TX_START", "FIBER_TX_END")) {
            assertRoute(List.of("1:1>2>1", "2:2>1>1"), events(trace, event, "SFU"));
        }
        assertRoute(List.of("1:1>2>1", "2:2>1>1"), events(trace, "DELIVERED", "MFU"));

        Map<String, String> pon = summary(trace, "PON_UPLINK");
        assertEquals("2", pon.get("sent_packets"));
        assertNumber(1.92, pon, "avg_queue_wait_us");
        assertNumber(3.84, pon, "avg_serialization_us");
        assertNumber(7.68, pon, "busy_us");
        assertNumber(19.68, pon, "window_us");
        assertNumber(7.68 / 19.68, pon, "utilization");

        Map<String, String> uplink = summary(trace, "UL_GLOBAL");
        assertEquals("2", uplink.get("delivered_packets"));
        assertEquals("2400", uplink.get("delivered_bytes"));
        assertNumber(17.76, uplink, "avg_e2e_delay_us");
        for (Map<String, String> sta : summaries(trace, "UL_STA")) {
            assertNumber(0, sta, "avg_queue_wait_us");
        }
        assertEquals("2", trace.get(trace.size() - 1).get("delivered_packets"));
    }

    @Test
    void preservesEachFifoAndUsesOriginalRequestTimeWhenItsHeadChanges(@TempDir Path directory)
            throws IOException {
        // 包 3 光侧入队早于另一台 SFU 的包 4，虽在包 2 后等待，也应在包 4 之前发送。
        List<Map<String, String>> trace = run(directory, "traffic.direction=UL", "fiber.uplink.mode=pon-shared",
                "wifi.delay.us=1", "sfu.count=2", "sta.count=2", "sta.1.sfu.id=2", "sta.2.sfu.id=1",
                "packet.arrival.us=0,0,0,3", "packet.bytes=1200",
                "packet.3.bytes=600", "packet.4.bytes=300", "packet.4.sta.id=2");

        assertTimeline(List.of("1@1", "2@2", "3@3", "4@4"), trace, "PON_UPLINK_ENQUEUE", "SFU");
        assertTimeline(List.of("1@1", "2@4.84", "3@8.68", "4@10.6"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("1@4.84", "2@8.68", "3@10.6", "4@11.56"), trace, "FIBER_TX_END", "SFU");
        assertTimeline(List.of("1@4.84", "2@8.68", "3@10.6", "4@11.56"), trace, "DELIVERED", "MFU");
        assertEquals("3300", summary(trace, "UL_GLOBAL").get("delivered_bytes"));
        assertNumber(3.78, summary(trace, "PON_UPLINK"), "avg_queue_wait_us");
        assertNumber(2.64, summary(trace, "PON_UPLINK"), "avg_serialization_us");
        Map<String, String> firstSta = summaries(trace, "UL_STA").stream()
                .filter(row -> "1".equals(row.get("sta_id"))).findFirst().orElseThrow();
        assertNumber(1, firstSta, "avg_queue_wait_us");
    }

    @Test
    void allowsSharedUplinkAndDownlinkToSendConcurrently(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(directory, "traffic.direction=BOTH", "fiber.uplink.mode=pon-shared",
                "fiber.downlink.mode=pon-shared", "wifi.delay.us=1", "packet.arrival.us=0,1", "packet.bytes=1200",
                "packet.1.direction=UL", "packet.2.direction=DL");

        // 两条光发送资源同时从 1 μs 开始，较长的上行发送不影响下行到达。
        assertTimeline(List.of("1@1"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("1@4.84"), trace, "FIBER_TX_END", "SFU");
        assertTimeline(List.of("2@1"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("2@1.96"), trace, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@4.84"), trace, "DELIVERED", "MFU");
        assertTimeline(List.of("2@2.96"), trace, "DELIVERED", "STA");
        assertEquals("1", summary(trace, "PON_UPLINK").get("sent_packets"));
        assertEquals("1", summary(trace, "PON_DOWNLINK").get("sent_packets"));
        assertEquals("2", trace.get(trace.size() - 1).get("delivered_packets"));
    }

    @Test
    void queuesSuccessfullyReceivedEdcaDataIndependentlyOfTheWirelessAck(@TempDir Path directory)
            throws IOException {
        List<Map<String, String>> trace = run(directory, "traffic.direction=UL", "fiber.uplink.mode=pon-shared",
                "fiber.uplink.propagation.us=4.025", "wifi.model=he-su", "wifi.mac.access=edca-multi-ac",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.cwmax=0",
                "packet.arrival.us=1", "packet.bytes=1200", "packet.1.type=VO");

        assertTimeline(List.of("1@35"), trace, "WIFI_TX_START", "STA");
        assertTimeline(List.of("1@99"), trace, "WIFI_DATA_END", "SFU");
        assertTimeline(List.of("1@99"), trace, "PON_UPLINK_ENQUEUE", "SFU");
        assertTimeline(List.of("1@99"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("1@106.865"), trace, "DELIVERED", "MFU");
        assertTimeline(List.of("1@143"), trace, "WIFI_ACK_END", "STA");
        assertNumber(34, summary(trace, "UL_STA"), "avg_queue_wait_us");
        assertNumber(0, summary(trace, "PON_UPLINK"), "avg_queue_wait_us");
        assertNumber(143, summary(trace, "PON_UPLINK"), "window_us");
    }

    @Test
    void keepsFixedUplinkTraceWhenModeIsOmittedOrExplicit(@TempDir Path directory) throws IOException {
        List<Map<String, String>> legacy = run(directory, "traffic.direction=UL",
                "fiber.delay.us=9", "wifi.delay.us=4", "packet.arrival.us=7", "packet.bytes=1200");
        List<Map<String, String>> explicit = run(directory, "traffic.direction=UL", "fiber.uplink.mode=fixed",
                "fiber.delay.us=9", "wifi.delay.us=4", "packet.arrival.us=7", "packet.bytes=1200");

        assertEquals(legacy, explicit);
        assertTimeline(List.of("1@11"), explicit, "FIBER_TX_START", "SFU");
        assertTimeline(List.of(), explicit, "FIBER_TX_END", "SFU");
        assertTimeline(List.of("1@20"), explicit, "DELIVERED", "MFU");
        assertEquals(List.of(), summaries(explicit, "PON_UPLINK"));
    }

    @Test
    void keepsFixedDownlinkDelayAndAppliesTheConfiguredUplinkRate(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(directory, "traffic.direction=BOTH", "fiber.uplink.mode=pon-shared",
                "fiber.uplink.rate.mbps=4800", "fiber.delay.us=20", "wifi.delay.us=2",
                "packet.arrival.us=0,0", "packet.bytes=1200", "packet.1.direction=DL", "packet.2.direction=UL");

        assertTimeline(List.of("1@0"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("1@20"), trace, "ARRIVAL", "SFU");
        assertTimeline(List.of("1@22"), trace, "DELIVERED", "STA");
        assertTimeline(List.of("2@2"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("2@4"), trace, "FIBER_TX_END", "SFU");
        assertTimeline(List.of("2@4"), trace, "DELIVERED", "MFU");
    }

    @Test
    void rejectsInvalidUplinkRateAndPropagation(@TempDir Path directory) {
        for (String property : List.of("fiber.uplink.rate.mbps=0", "fiber.uplink.rate.mbps=NaN",
                "fiber.uplink.propagation.us=-1", "fiber.uplink.propagation.us=0.0001")) {
            assertThrows(IllegalArgumentException.class, () -> run(directory, "traffic.direction=UL",
                    "fiber.uplink.mode=pon-shared", "packet.arrival.us=0", "packet.bytes=1200", property), property);
        }
    }

    private static List<Map<String, String>> run(Path directory, String... properties) throws IOException {
        Path scenario = Files.write(directory.resolve("pon-uplink.properties"), List.of(properties),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(scenario), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(PonUplinkTest::fields)
                .collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> result = new HashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            result.put(pair[0], pair[1]);
        }
        return result;
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event, String node) {
        return trace.stream().filter(row -> event.equals(row.get("event")) && node.equals(row.get("node")))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> summaries(List<Map<String, String>> trace, String name) {
        return trace.stream().filter(row -> name.equals(row.get("summary"))).collect(Collectors.toList());
    }

    private static Map<String, String> summary(List<Map<String, String>> trace, String name) {
        List<Map<String, String>> rows = summaries(trace, name);
        assertEquals(1, rows.size(), name);
        return rows.get(0);
    }

    private static void assertTimeline(List<String> expected, List<Map<String, String>> trace, String event,
                                       String node) {
        List<String> actual = events(trace, event, node).stream()
                .map(row -> row.get("packet_id") + "@" + row.get("t_us")).collect(Collectors.toList());
        assertEquals(expected, actual, event + " at " + node);
    }

    private static void assertRoute(List<String> expected, List<Map<String, String>> rows) {
        List<String> actual = rows.stream()
                .map(row -> row.get("packet_id") + ":" + row.get("source_sta_id") + ">" + row.get("sfu_id")
                        + ">" + row.get("destination_mfu_id")).collect(Collectors.toList());
        assertEquals(expected, actual);
        assertTrue(rows.stream().allMatch(row -> "UL".equals(row.get("direction"))));
    }

    private static void assertNumber(double expected, Map<String, String> row, String field) {
        assertEquals(expected, Double.parseDouble(row.get(field)), 1e-6, field);
    }
}
