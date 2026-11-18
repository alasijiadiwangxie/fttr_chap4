package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdcaRetryTest {
    @Test
    void completesSingleFirstAttemptWithAckAndNoRetries(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0", "packet.1.type=VO");

        String trace = run(config);
        assertEquals(List.of("1#0@34"), attempts(trace, "WIFI_MAC_ATTEMPT"));
        assertEquals(List.of("1@98"), timeline(trace, "DELIVERED"));
        assertEquals(List.of("1@142"), timeline(trace, "WIFI_ACK_END"));
        assertEquals(List.of("1#0@142"), attempts(trace, "WIFI_MAC_SUCCESS"));
        assertTrue(events(trace, "WIFI_ACK_TIMEOUT").isEmpty());
        assertRetrySummary(trace, 0, 0, 0);
    }

    @Test
    void retriesTheSameHeadsWithLargerWindowsAndResetsAfterAck(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "traffic.direction=UL", "sta.count=2", "packet.arrival.us=0,0,100",
                "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2", "packet.3.type=VO", "wifi.mac.edca.seed=1");

        String trace = run(config);
        assertEquals(trace, run(config));
        assertEquals(List.of("1@0", "2@0", "3@100"), timeline(trace, "ARRIVAL"));
        assertEquals(List.of("1@34", "2@34"), timeline(trace, "WIFI_COLLISION_START"));
        // 34 μs 开始的 64 μs DATA 结束后，各自等待默认 53 μs。CW 从 0 增到 1。
        assertEquals(List.of("1#0@151", "2#0@151"), attempts(trace, "WIFI_ACK_TIMEOUT"));
        List<Map<String, String>> retries = events(trace, "WIFI_MAC_RETRY");
        assertEquals(List.of("1#1@151", "2#1@151"), attempts(trace, "WIFI_MAC_RETRY"));
        assertEquals(List.of("0:1:1", "0:1:0"), retries.stream().map(row ->
                String.join(":", row.get("cw_before"), row.get("cw_after"), row.get("backoff")))
                .collect(Collectors.toList()));

        // STA 2 抽到 0 格，先于 STA 1 发送。STA 1 等到 ACK 后重新完成 AIFS 与剩余一格。
        // STA 1 成功后恢复 CWmin=0。此种子的下一次抽样若仍用 CW=1 会抽到 1 格。
        assertEquals(List.of("1#0@34", "2#0@34", "2#1@185", "1#1@336", "3#0@478"),
                attempts(trace, "WIFI_MAC_ATTEMPT"));
        assertEquals(List.of("2#1@293", "1#1@444", "3#0@586"), attempts(trace, "WIFI_MAC_SUCCESS"));
        assertEquals(List.of("2@249", "1@400", "3@542"), timeline(trace, "FIBER_TX_START"));
        assertEquals(List.of("2@249", "1@400", "3@542"), timeline(trace, "DELIVERED"));
        assertTrue(trace.contains("delivered_packets=3 delivered_bytes=4500"));
        assertRetrySummary(trace, 2, 2, 0);
    }

    @Test
    void dropsWhenConfiguredRetryCountIsExhaustedAndArmsNewPacket(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "traffic.direction=UL", "sta.count=2", "packet.arrival.us=0,0,246",
                "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2", "packet.3.type=VO", "wifi.mac.edca.seed=11",
                "wifi.mac.ack.timeout.us=20", "wifi.mac.retry.max=1");

        String trace = run(config);
        // 种子 11 使两台 STA 的首次重传都抽到 1 格，恰好碰撞两次。
        assertEquals(List.of("1#0@34", "2#0@34", "1#1@161", "2#1@161", "3#0@280"),
                attempts(trace, "WIFI_MAC_ATTEMPT"));
        assertEquals(List.of("1#0@118", "2#0@118", "1#1@245", "2#1@245"), attempts(trace, "WIFI_ACK_TIMEOUT"));
        assertEquals(List.of("1#1@245", "2#1@245"), attempts(trace, "WIFI_RETRY_LIMIT_DROP"));
        assertEquals(List.of("3#0@388"), attempts(trace, "WIFI_MAC_SUCCESS"));
        assertEquals(List.of("3@344"), timeline(trace, "DELIVERED"));
        assertTrue(trace.contains("summary=WIFI_COLLISION collision_events=2 dropped_packets=2 dropped_bytes=3000"));
        assertRetrySummary(trace, 2, 4, 2);
    }

    @Test
    void blocksAllFailedSenderCategoriesWhileOtherSenderCanUseIdleChannel(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "traffic.direction=UL", "sta.count=3", "packet.arrival.us=0,0,99,99",
                "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2", "packet.3.type=VO", "packet.3.sta.id=3",
                "packet.4.type=VI", "wifi.mac.edca.VI.cwmin=0", "wifi.mac.ack.timeout.us=150", "wifi.mac.retry.max=0");

        String trace = run(config);
        assertEquals(List.of("1@98", "2@98"), timeline(trace, "WIFI_COLLISION_DATA_END"));
        assertEquals(List.of("1#0@248", "2#0@248"), attempts(trace, "WIFI_ACK_TIMEOUT"));
        // STA 3 在其他设备等待 ACK 超时期间完成交换。STA 1 的 VI 仍需等到超时后才开始 AIFS。
        assertEquals(List.of("1#0@34", "2#0@34", "3#0@133", "4#0@291"), attempts(trace, "WIFI_MAC_ATTEMPT"));
        assertEquals(List.of("3#0@241", "4#0@399"), attempts(trace, "WIFI_MAC_SUCCESS"));
        assertEquals(List.of("3@197", "4@355"), timeline(trace, "DELIVERED"));
        assertRetrySummary(trace, 0, 2, 2);
    }

    @Test
    void rejectsInvalidTimeoutAndRetryLimit(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class,
                () -> load(directory, "packet.arrival.us=0", "wifi.mac.ack.timeout.us=0"));
        assertThrows(IllegalArgumentException.class,
                () -> load(directory, "packet.arrival.us=0", "wifi.mac.retry.max=-1"));
    }

    private static SimulationConfig load(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=0", "packet.bytes=1500",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0"));
        properties.addAll(List.of(settings));
        return ConfigLoader.load(Files.write(directory.resolve("edca-retry.properties"), properties, StandardCharsets.UTF_8));
    }

    private static String run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> events(String trace, String event) {
        return trace.lines().filter(line -> line.contains(" event=" + event + " "))
                .map(EdcaRetryTest::fields).collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String field : line.split(" ")) {
            String[] entry = field.split("=", 2);
            fields.put(entry[0], entry[1]);
        }
        return fields;
    }

    private static List<String> timeline(String trace, String event) {
        return events(trace, event).stream().map(row -> row.get("packet_id") + "@" + row.get("t_us"))
                .collect(Collectors.toList());
    }

    private static List<String> attempts(String trace, String event) {
        return events(trace, event).stream()
                .map(row -> row.get("packet_id") + "#" + row.get("retry_count") + "@" + row.get("t_us"))
                .collect(Collectors.toList());
    }

    private static void assertRetrySummary(String trace, int retransmissions, int timeouts, int drops) {
        Map<String, String> summary = trace.lines().filter(line -> line.startsWith("summary=WIFI_RETRY "))
                .map(EdcaRetryTest::fields).findFirst().orElseThrow();
        assertEquals(Integer.toString(retransmissions), summary.get("retransmissions"));
        assertEquals(Integer.toString(timeouts), summary.get("ack_timeouts"));
        assertEquals(Integer.toString(drops), summary.get("retry_limit_drops"));
    }
}
