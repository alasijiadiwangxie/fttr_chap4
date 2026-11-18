package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdcaTxopTest {
    @Test
    void defaultAndExplicitZeroLimitsKeepSingleFrameAccess(@TempDir Path directory) throws IOException {
        String defaults = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO"));
        String explicitZero = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "wifi.mac.edca.VO.txop.limit.us=0", "wifi.mac.edca.VI.txop.limit.us=0",
                "wifi.mac.edca.BE.txop.limit.us=0", "wifi.mac.edca.BK.txop.limit.us=0"));

        assertEquals(defaults, explicitZero);
        assertEquals(List.of("1@34", "2@176"), timeline(defaults, "WIFI_TX_START"));
        assertEquals(List.of("1@142", "2@284"), timeline(defaults, "WIFI_ACK_END"));
        assertFalse(defaults.contains("WIFI_TXOP"));
    }

    @Test
    void countsFullExchangesAndAllowsAnExactLimit(@TempDir Path directory) throws IOException {
        String exact = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "wifi.mac.edca.VO.txop.limit.us=232"));

        // 两次交换各耗时 108 μs，中间再预留一个 16 μs 的 SIFS。
        assertEquals(List.of("1@34", "2@158"), timeline(exact, "WIFI_TX_START"));
        assertEquals(List.of("1@142", "2@266"), timeline(exact, "WIFI_ACK_END"));
        assertEquals(List.of("1@34"), timeline(exact, "WIFI_TXOP_START"));
        assertTxopEnds(exact, "1@266:232:2:232");
        assertTxopSummary(exact, 1, "232", 2);
        assertTrue(exact.contains("wifi_busy_us=232 wifi_idle_us=34 simulation_end_us=266"));

        String shorter = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "wifi.mac.edca.VO.txop.limit.us=231"));
        assertEquals(List.of("1@34", "2@176"), timeline(shorter, "WIFI_TX_START"));
        assertTxopEnds(shorter, "1@142:108:1:231", "2@284:108:1:231");
        assertTxopSummary(shorter, 2, "216", 2);
    }

    @Test
    void exchangesLargerThanTheLimitUseSingleFrameAccess(@TempDir Path directory) throws IOException {
        String trace = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "wifi.mac.edca.VO.txop.limit.us=107"));

        assertEquals(List.of("1@34", "2@176"), timeline(trace, "WIFI_TX_START"));
        assertEquals(List.of("1@142", "2@284"), timeline(trace, "WIFI_ACK_END"));
        assertFalse(trace.contains("WIFI_TXOP"));
    }

    @Test
    void usesFractionalPpduDurationForTheContinuationDecision(@TempDir Path directory) throws IOException {
        // 215 B 和 1500 B 的完整交换分别为 100.8 与 114.4 μs。两帧加间隔共 231.2 μs。
        String capped = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "packet.1.bytes=215", "wifi.phy.bandwidth.mhz=80", "wifi.phy.nss=1",
                "wifi.mac.edca.VO.txop.limit.us=231"));
        assertEquals(List.of("1@34", "2@168.8"), timeline(capped, "WIFI_TX_START"));
        assertTxopEnds(capped, "1@134.8:100.8:1:231", "2@283.2:114.4:1:231");

        String fits = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "packet.1.bytes=215", "wifi.phy.bandwidth.mhz=80", "wifi.phy.nss=1",
                "wifi.mac.edca.VO.txop.limit.us=232"));
        assertEquals(List.of("1@34", "2@150.8"), timeline(fits, "WIFI_TX_START"));
        assertTxopEnds(fits, "1@265.2:231.2:2:232");
        assertTxopSummary(fits, 1, "231.2", 2);
    }

    @Test
    void onlyTheOwningSenderAndCategoryMayContinue(@TempDir Path directory) throws IOException {
        String trace = run(load(directory, "traffic.direction=UL", "sta.count=2", "packet.arrival.us=0,1,10,12",
                "packet.1.type=VO", "packet.2.type=VO", "packet.3.type=VO", "packet.3.sta.id=2", "packet.4.type=VI",
                "wifi.mac.edca.VI.cwmin=0", "wifi.mac.edca.VO.txop.limit.us=500"));

        // 同一 STA 的 VO 可连续发送。其他 STA 的 VO 和首个 STA 的 VI 都必须再次竞争。
        assertEquals(List.of("1@34", "2@158", "3@300", "4@451"), timeline(trace, "WIFI_TX_START"));
        assertEquals(List.of("1@142", "2@266", "3@408", "4@559"), timeline(trace, "WIFI_ACK_END"));
        assertTxopEnds(trace, "1@266:232:2:500", "3@408:108:1:500");
        assertTxopSummary(trace, 2, "340", 3);
        assertTrue(trace.contains("wifi_busy_us=448 wifi_idle_us=111 simulation_end_us=559"));
    }

    @Test
    void keepsParallelSfusIndependentAndCountsOverlappingBusyTimeOnce(@TempDir Path directory) throws IOException {
        String trace = run(load(directory, "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2",
                "packet.arrival.us=0,0,20,21", "packet.1.type=VO", "packet.2.type=VO",
                "packet.3.type=VO", "packet.3.sta.id=2", "packet.4.type=VO", "packet.4.sta.id=2",
                "wifi.mac.edca.VO.txop.limit.us=232"));

        assertEquals(List.of("1@34", "3@54", "2@158", "4@178"), timeline(trace, "WIFI_TX_START"));
        assertTxopEnds(trace, "1@266:232:2:232", "3@286:232:2:232");
        assertTxopSummary(trace, 2, "464", 4);
        // 两段 TXOP 的持续时间相加。全局忙时取两台 SFU 忙区间的并集。
        assertTrue(trace.contains("wifi_busy_us=252 wifi_idle_us=34 simulation_end_us=286"));
    }

    @Test
    void retransmissionCanGainANewTxopAfterCollisionAndAckTimeout(@TempDir Path directory) throws IOException {
        SimulationConfig scenario = load(directory, "traffic.direction=UL", "sta.count=2", "packet.arrival.us=0,0,100,101",
                "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2", "packet.3.type=VO",
                "packet.4.type=VO", "packet.4.sta.id=2", "wifi.mac.edca.seed=1", "wifi.mac.edca.VO.txop.limit.us=232");
        String trace = run(scenario);

        assertEquals(trace, run(scenario));
        assertEquals(List.of("1@34", "2@34"), timeline(trace, "WIFI_COLLISION_START"));
        assertEquals(List.of("1@151", "2@151"), timeline(trace, "WIFI_ACK_TIMEOUT"));
        // 碰撞尝试没有获得 TXOP。重新争用成功后，重传帧可以在新的 TXOP 中带上下一条队首帧。
        assertEquals(List.of("2@185", "1@460"), timeline(trace, "WIFI_TXOP_START"));
        assertEquals(List.of("2@185", "4@309", "1@460", "3@584"), timeline(trace, "WIFI_TX_START"));
        assertEquals(List.of("2@249", "4@373", "1@524", "3@648"), timeline(trace, "DELIVERED"));
        assertTxopEnds(trace, "2@417:232:2:232", "1@692:232:2:232");
        assertTxopSummary(trace, 2, "464", 4);
        assertTrue(trace.contains("summary=WIFI_RETRY retransmissions=2 ack_timeouts=2 retry_limit_drops=0"));
    }

    @Test
    void rejectsNegativeAndOutOfRangeLimits(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class,
                () -> load(directory, "packet.arrival.us=0", "wifi.mac.edca.VI.txop.limit.us=-1"));
        assertThrows(IllegalArgumentException.class,
                () -> load(directory, "packet.arrival.us=0", "wifi.mac.edca.BE.txop.limit.us=9223372036854776"));
    }

    private static SimulationConfig load(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=0", "packet.bytes=1500",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0"));
        properties.addAll(List.of(settings));
        return ConfigLoader.load(Files.write(directory.resolve("edca-txop.properties"), properties, StandardCharsets.UTF_8));
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
                .map(EdcaTxopTest::fields).collect(Collectors.toList());
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

    private static void assertTxopEnds(String trace, String... expected) {
        List<String> result = events(trace, "WIFI_TXOP_END").stream()
                .map(row -> row.get("packet_id") + "@" + row.get("t_us") + ":" + row.get("duration_us")
                        + ":" + row.get("packet_count") + ":" + row.get("limit_us"))
                .collect(Collectors.toList());
        assertEquals(List.of(expected), result);
    }

    private static void assertTxopSummary(String trace, int txops, String totalUs, int packetCount) {
        Map<String, String> summary = trace.lines().filter(line -> line.startsWith("summary=WIFI_TXOP "))
                .map(EdcaTxopTest::fields).findFirst().orElseThrow();
        assertEquals(Integer.toString(txops), summary.get("txops"));
        assertEquals(0, new BigDecimal(totalUs).compareTo(new BigDecimal(summary.get("total_duration_us"))));
        assertEquals(Integer.toString(packetCount), summary.get("transmitted_packets"));
    }
}
