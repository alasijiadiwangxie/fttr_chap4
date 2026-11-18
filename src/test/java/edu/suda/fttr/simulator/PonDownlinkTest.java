package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PonDownlinkTest {
    @Test
    void serializes1200BytesInPointNineSixMicroseconds(@TempDir Path directory) throws IOException {
        // 起发、发完、SFU 收齐和 STA 收齐分别落在不同时间，旧光侧时延不叠加到传播时延。
        List<Map<String, String>> trace = run(directory,
                "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=3", "fiber.delay.us=100",
                "wifi.delay.us=4", "packet.arrival.us=7", "packet.bytes=1200");

        assertTimeline(List.of("1@7"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("1@7.96"), trace, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@10.96"), trace, "ARRIVAL", "SFU");
        assertTimeline(List.of("1@10.96"), trace, "WIFI_TX_START", "SFU");
        assertTimeline(List.of("1@14.96"), trace, "DELIVERED", "STA");
    }

    @Test
    void usesOneSharedTransmitterAcrossSfusAndReleasesItBeforePropagationEnds(@TempDir Path directory)
            throws IOException {
        // 相同到达时刻，两包实际目标分别为 SFU 2、SFU 1。高优先级的第二包仍按光侧 FIFO 等待。
        List<Map<String, String>> trace = run(directory,
                "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=5",
                "sfu.count=2", "sta.count=2", "sta.1.sfu.id=2", "sta.2.sfu.id=1",
                "wifi.delay.us=3", "packet.arrival.us=2,2", "packet.bytes=1200",
                "packet.1.type=BK", "packet.2.type=VO", "packet.2.sta.id=2");

        assertTimeline(List.of("1@2", "2@2"), trace, "PON_DOWNLINK_ENQUEUE", "MFU");
        assertTimeline(List.of("1@2", "2@2.96"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("1@2.96", "2@3.92"), trace, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@7.96", "2@8.92"), trace, "ARRIVAL", "SFU");
        assertTimeline(List.of("1@10.96", "2@11.92"), trace, "DELIVERED", "STA");
        assertEquals(List.of("1:2>1", "2:1>2"), routes(trace, "ARRIVAL", "SFU", "destination_sta_id"));
        assertEquals(List.of("1:2>1", "2:1>2"), routes(trace, "DELIVERED", "STA", "sta_id"));
        Map<String, String> totals = trace.get(trace.size() - 1);
        assertEquals("2", totals.get("delivered_packets"));
        assertEquals("2400", totals.get("delivered_bytes"));

        // 利用率使用 [0, 11.92 μs]，包括启动前的空闲和最后一包传播、无线发送的时间。
        List<Map<String, String>> summaries = trace.stream().filter(row -> "PON_DOWNLINK".equals(row.get("summary")))
                .collect(Collectors.toList());
        assertEquals(1, summaries.size());
        Map<String, String> summary = summaries.get(0);
        assertEquals("2", summary.get("sent_packets"));
        assertNumber(0.48, summary, "avg_queue_wait_us");
        assertNumber(0.96, summary, "avg_serialization_us");
        assertNumber(1.92, summary, "busy_us");
        assertNumber(11.92, summary, "window_us");
        assertNumber(1.92 / 11.92, summary, "utilization");
    }

    @Test
    void preservesFifoWhenNewArrivalAndPriorCompletionShareATimestamp(@TempDir Path directory) throws IOException {
        // 9600 Mb/s 时 1200 B 正好用 1 μs。第 3 包在第 1 包发完时入队，应排在已等待的第 2 包后。
        String[] properties = {
                "fiber.downlink.mode=pon-shared", "fiber.downlink.rate.mbps=9600",
                "sfu.count=2", "sta.count=2", "sta.1.sfu.id=2", "sta.2.sfu.id=1",
                "wifi.delay.us=0", "packet.arrival.us=0,0,1,4", "packet.bytes=1200",
                "packet.2.sta.id=2", "packet.3.type=VO", "packet.4.sta.id=2", "packet.4.bytes=600"
        };
        List<Map<String, String>> trace = run(directory, properties);

        assertEquals(trace, run(directory, properties));
        assertTimeline(List.of("1@0", "2@1", "3@2", "4@4"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("1@1", "2@2", "3@3", "4@4.5"), trace, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@1", "2@2", "3@3", "4@4.5"), trace, "ARRIVAL", "SFU");
        List<String> firstFinishBoundary = trace.stream()
                .filter(row -> "1".equals(row.get("t_us")) && List.of("ARRIVAL", "FIBER_TX_END", "FIBER_TX_START")
                        .contains(row.get("event")))
                .map(row -> row.get("node") + ":" + row.get("event") + ":" + row.get("packet_id"))
                .collect(Collectors.toList());
        assertEquals(List.of("MFU:ARRIVAL:3", "MFU:FIBER_TX_END:1", "SFU:ARRIVAL:1", "MFU:FIBER_TX_START:2"),
                firstFinishBoundary);
        assertEquals(List.of("1:2>1", "2:1>2", "3:2>1", "4:1>2"),
                routes(trace, "DELIVERED", "STA", "sta_id"));
    }

    @Test
    void roundsSubNanosecondSerializationUpToOneNanosecond(@TempDir Path directory) throws IOException {
        // 默认速率下 1 B 用 0.8 ns，至少占用一个纳秒刻度；第二包不能在同刻发起。
        List<Map<String, String>> trace = run(directory,
                "fiber.downlink.mode=pon-shared", "wifi.delay.us=0", "packet.arrival.us=0,0", "packet.bytes=1");

        assertTimeline(List.of("1@0", "2@0.001"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("1@0.001", "2@0.002"), trace, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@0.001", "2@0.002"), trace, "DELIVERED", "STA");
    }

    @Test
    void keepsLegacyUplinkOpticalDelayInMixedTraffic(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(directory,
                "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=3", "fiber.delay.us=20",
                "wifi.delay.us=2", "traffic.direction=BOTH", "packet.arrival.us=0,0", "packet.bytes=1200",
                "packet.1.direction=DL", "packet.2.direction=UL");

        assertTimeline(List.of("1@0"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("2@2"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("1@0.96"), trace, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@3.96"), trace, "ARRIVAL", "SFU");
        assertTimeline(List.of("1@5.96"), trace, "DELIVERED", "STA");
        assertTimeline(List.of("2@22"), trace, "DELIVERED", "MFU");
    }

    @Test
    void passesSubMicrosecondArrivalIntoTheExistingEdcaExchange(@TempDir Path directory) throws IOException {
        // CW=0 固定 VO 的接入时延为 AIFS 34 μs，直接核对纳秒级的光侧到达时刻传入 EDCA。
        List<Map<String, String>> trace = run(directory,
                "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=4.025",
                "wifi.model=he-su", "wifi.mac.access=edca-multi-ac", "wifi.mac.exchange=data-sifs-ack",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.cwmax=0",
                "packet.arrival.us=1", "packet.bytes=1200", "packet.1.type=VO");

        assertTimeline(List.of("1@5.985"), trace, "ARRIVAL", "SFU");
        assertTimeline(List.of("1@39.985"), trace, "WIFI_TX_START", "SFU");
        assertTimeline(List.of("1@103.985"), trace, "DELIVERED", "STA");
        assertTimeline(List.of("1@147.985"), trace, "WIFI_ACK_END", "SFU");
    }

    @Test
    void explicitFixedModeMatchesLegacyOpticalAndWirelessTrace(@TempDir Path directory) throws IOException {
        List<Map<String, String>> legacy = run(directory,
                "fiber.delay.us=9", "wifi.delay.us=4", "packet.arrival.us=7,7", "packet.bytes=1200");
        List<Map<String, String>> explicit = run(directory, "fiber.downlink.mode=fixed",
                "fiber.delay.us=9", "wifi.delay.us=4", "packet.arrival.us=7,7", "packet.bytes=1200");

        assertEquals(legacy, explicit);
        assertTimeline(List.of("1@7", "2@7"), explicit, "FIBER_TX_START", "MFU");
        assertTimeline(List.of(), explicit, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@16", "2@16"), explicit, "ARRIVAL", "SFU");
        assertTimeline(List.of("1@20", "2@24"), explicit, "DELIVERED", "STA");
    }

    @Test
    void rejectsInvalidSharedLinkSettings(@TempDir Path directory) {
        for (String property : List.of("fiber.downlink.rate.mbps=0", "fiber.downlink.rate.mbps=-1",
                "fiber.downlink.rate.mbps=NaN", "fiber.downlink.propagation.us=-1",
                "fiber.downlink.propagation.us=0.0001")) {
            assertThrows(IllegalArgumentException.class,
                    () -> run(directory, "fiber.downlink.mode=pon-shared", "packet.arrival.us=0", "packet.bytes=1200",
                            property),
                    property);
        }
    }

    private static List<Map<String, String>> run(Path directory, String... properties) throws IOException {
        Path scenario = Files.write(directory.resolve("pon-downlink.properties"), List.of(properties),
                StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(scenario), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(PonDownlinkTest::fields)
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

    private static void assertTimeline(List<String> expected, List<Map<String, String>> trace, String event,
                                       String node) {
        List<String> actual = events(trace, event, node).stream().map(row ->
                        row.get("packet_id") + "@" + new BigDecimal(row.get("t_us")).stripTrailingZeros().toPlainString())
                .collect(Collectors.toList());
        assertEquals(expected, actual, event + " at " + node);
    }

    private static List<String> routes(List<Map<String, String>> trace, String event, String node, String staKey) {
        return events(trace, event, node).stream()
                .map(row -> row.get("packet_id") + ":" + row.get("sfu_id") + ">" + row.get(staKey))
                .collect(Collectors.toList());
    }

    private static void assertNumber(double expected, Map<String, String> row, String field) {
        assertEquals(expected, Double.parseDouble(row.get(field)), 1e-6, field);
    }
}
