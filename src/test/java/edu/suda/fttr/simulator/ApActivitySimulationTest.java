package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApActivitySimulationTest {
    private static final String ENABLED = "wifi.ap.activity.enabled=true";
    private static final String WINDOW_KEY = "wifi.ap.activity.window.us";
    private static final String CHANGE = "WIFI_AP_ACTIVITY_CHANGE";
    private static final String SUMMARY = "WIFI_AP_ACTIVITY";

    @ParameterizedTest
    @CsvSource({
            "LCM, false, 505200, 118000, 331200",
            "HCM, false, 329200, 118000, 155200",
            "LCM, true, 1000000, 612800, 331200",
            "HCM, true, 1000000, 788800, 155200"
    })
    void accountsForBothOriginalB1DownlinkPacketsAndTheOptionalIdleTail(
            String mode, boolean fixedWindow, long end, long idle, long tx, @TempDir Path directory) throws IOException {
        Path original = b1Config(mode);
        List<String> activities = new ArrayList<>(List.of(ENABLED));
        if (fixedWindow) {
            activities.add(WINDOW_KEY + "=1000");
        }
        List<String> trace = run(withSettings(directory, Files.readAllLines(original), activities));

        assertActivity(trace, 1, mode, end, idle, tx, 56_000);
        assertEquals(run(original), protocolTrace(trace));
        if (mode.equals("LCM")) {
            assertTransitions(trace, 1, "34000:IDLE>TX:DATA_START", "199600:TX>IDLE:DATA_END",
                    "215600:IDLE>RX:ACK_START", "243600:RX>IDLE:ACK_END", "295600:IDLE>TX:DATA_START",
                    "461200:TX>IDLE:DATA_END", "477200:IDLE>RX:ACK_START", "505200:RX>IDLE:ACK_END");
        } else {
            assertTransitions(trace, 1, "34000:IDLE>TX:DATA_START", "111600:TX>IDLE:DATA_END",
                    "127600:IDLE>RX:ACK_START", "155600:RX>IDLE:ACK_END", "207600:IDLE>TX:DATA_START",
                    "285200:TX>IDLE:DATA_END", "301200:IDLE>RX:ACK_START", "329200:RX>IDLE:ACK_END");
        }
    }

    @Test
    void clipsADataIntervalAtAnExactNanosecondWithoutStoppingTheSimulation(@TempDir Path directory)
            throws IOException {
        Path original = b1Config("HCM");
        List<String> trace = run(withSettings(directory, Files.readAllLines(original),
                List.of(ENABLED, WINDOW_KEY + "=50.125")));

        assertActivity(trace, 1, "HCM", 50_125, 34_000, 16_125, 0);
        assertEquals(run(original), protocolTrace(trace));
    }

    @Test
    void uplinkDataUsesRxAndAckUsesTx(@TempDir Path directory) throws IOException {
        List<String> trace = runScenario(directory, "packet.arrival.us=0", "traffic.direction=UL",
                "sfu.1.wifi.ap.mode=HCM");

        assertActivity(trace, 1, "HCM", 121_600, 16_000, 28_000, 77_600);
        assertTransitions(trace, 1, "0:IDLE>RX:DATA_START", "77600:RX>IDLE:DATA_END",
                "93600:IDLE>TX:ACK_START", "121600:TX>IDLE:ACK_END");
        assertEquals(1, events(trace, "DELIVERED").size());
    }

    @Test
    void independentRadiosUseACommonNaturalWindowAndIncludeIdleSfus(@TempDir Path directory) throws IOException {
        List<String> trace = runScenario(directory, "packet.arrival.us=0,0", "sfu.count=3", "sta.count=2",
                "sta.2.sfu.id=2", "packet.2.sta.id=2", "sfu.1.wifi.ap.mode=LCM", "sfu.2.wifi.ap.mode=HCM");

        assertActivity(trace, 1, "LCM", 209_600, 16_000, 165_600, 28_000);
        assertActivity(trace, 2, "HCM", 209_600, 104_000, 77_600, 28_000);
        assertActivity(trace, 3, "LEGACY", 209_600, 209_600, 0, 0);
        assertEquals(2, events(trace, "DELIVERED").size());
    }

    @Test
    void overlappingUplinkCollisionUsesOneRxIntervalEvenWhenAnEarlyTimeoutOverlapsIt(@TempDir Path directory)
            throws IOException {
        List<String> trace = runScenario(directory, "packet.arrival.us=0,0", "traffic.direction=UL", "sta.count=2",
                "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2", "sfu.1.wifi.ap.mode=HCM",
                "sta.1.wifi.max.nss=1", "sta.2.wifi.max.bandwidth.mhz=40", "wifi.mac.access=edca-multi-ac",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.retry.max=0", "wifi.mac.ack.timeout.us=5");

        // 两包从 34 μs 同时开始，分别在 118 μs 和 125.2 μs 结束。第一次超时在 123 μs。
        assertActivity(trace, 1, "HCM", 130_200, 39_000, 0, 91_200);
        assertTransitions(trace, 1, "34000:IDLE>RX:COLLISION_START", "125200:RX>IDLE:COLLISION_DATA_END");
        assertEquals(List.of("123", "130.2"), eventTimes(trace, "WIFI_ACK_TIMEOUT"));
        assertTrue(events(trace, "WIFI_ACK_START").isEmpty());
        assertTrue(events(trace, "DELIVERED").isEmpty());
    }

    @Test
    void mixedCollisionUsesHalfDuplexTxPrecedenceAndAccountsForTheLongerRxTail(@TempDir Path directory)
            throws IOException {
        List<String> trace = runScenario(directory, "packet.arrival.us=0,0", "traffic.direction=BOTH", "sta.count=2",
                "packet.1.direction=DL", "packet.1.type=VO", "packet.1.sta.id=2", "packet.2.direction=UL",
                "packet.2.type=VO", "packet.2.sta.id=1", "sfu.1.wifi.ap.mode=HCM", "sta.1.wifi.max.nss=1",
                "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.retry.max=0",
                "wifi.mac.ack.timeout.us=5");

        assertActivity(trace, 1, "HCM", 123_000, 39_000, 77_600, 6_400);
        assertTransitions(trace, 1, "34000:IDLE>TX:COLLISION_START", "111600:TX>RX:COLLISION_DATA_END",
                "118000:RX>IDLE:COLLISION_DATA_END");
        assertEquals(2, events(trace, "WIFI_RETRY_LIMIT_DROP").size());
        assertTrue(events(trace, "WIFI_ACK_START").isEmpty());
    }

    @Test
    void countsFailedAndSuccessfulAttemptsAndKeepsTimeoutAndBackoffIdle(@TempDir Path directory) throws IOException {
        List<String> trace = runScenario(directory, "packet.arrival.us=0,0", "traffic.direction=UL", "sta.count=2",
                "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2", "sfu.1.wifi.ap.mode=HCM",
                "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.seed=1");

        // 失败的重叠 DATA 计一次 RX，之后两次成功的重传各计一次 RX 和一次 ACK TX。
        assertActivity(trace, 1, "HCM", 484_800, 196_000, 56_000, 232_800);
        assertEquals(List.of("164.6", "164.6"), eventTimes(trace, "WIFI_ACK_TIMEOUT"));
        assertEquals(2, events(trace, "WIFI_MAC_RETRY").size());
        assertEquals(2, events(trace, "DELIVERED").size());
    }

    @Test
    void countsTheInterFrameSifsAsIdleInsideATxop(@TempDir Path directory) throws IOException {
        List<String> trace = runScenario(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "sfu.1.wifi.ap.mode=HCM", "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0",
                "wifi.mac.edca.VO.txop.limit.us=260");

        assertActivity(trace, 1, "HCM", 293_200, 82_000, 155_200, 56_000);
        assertTransitions(trace, 1, "34000:IDLE>TX:DATA_START", "111600:TX>IDLE:DATA_END",
                "127600:IDLE>RX:ACK_START", "155600:RX>IDLE:ACK_END", "171600:IDLE>TX:DATA_START",
                "249200:TX>IDLE:DATA_END", "265200:IDLE>RX:ACK_START", "293200:RX>IDLE:ACK_END");
        assertEquals(1, events(trace, "WIFI_TXOP_END").size());
    }

    @Test
    void legacyDataOnlyHandlesAnEndAndTheNextStartAtTheSameTick(@TempDir Path directory) throws IOException {
        List<String> trace = runScenario(directory, "packet.arrival.us=0,0", "traffic.direction=BOTH",
                "packet.1.direction=DL", "packet.1.type=VO", "packet.2.direction=UL", "packet.2.type=VI",
                "wifi.model=fixed", "wifi.delay.us=10", "wifi.mac.exchange=data-only");

        assertActivity(trace, 1, "LEGACY", 20_000, 0, 10_000, 10_000);
        assertTransitions(trace, 1, "0:IDLE>TX:DATA_START", "10000:TX>IDLE:DATA_END",
                "10000:IDLE>RX:DATA_START", "20000:RX>IDLE:DATA_END");
        assertTrue(events(trace, "WIFI_ACK_START").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0001", "abc", "9223372036854775.808"})
    void rejectsAnInvalidOrUnrepresentableObservationWindow(String value, @TempDir Path directory)
            throws IOException {
        Path config = withSettings(directory, Files.readAllLines(b1Config("LCM")),
                List.of(ENABLED, WINDOW_KEY + "=" + value));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }

    @Test
    void rejectsAnObservationWindowWhenAccountingIsDisabled(@TempDir Path directory) throws IOException {
        Path config = withSettings(directory, Files.readAllLines(b1Config("LCM")),
                List.of("wifi.ap.activity.enabled=false", WINDOW_KEY + "=1000"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }

    private static Path b1Config(String mode) {
        return Path.of("configs", "b1-static-ap-" + mode.toLowerCase(Locale.ROOT) + ".properties");
    }

    private static List<String> runScenario(Path directory, String... settings) throws IOException {
        return run(withSettings(directory, List.of(ENABLED, "fiber.delay.us=0", "packet.bytes=1200",
                "wifi.mac.exchange=data-sifs-ack"), List.of(settings)));
    }

    private static Path withSettings(Path directory, List<String> base, List<String> settings) throws IOException {
        List<String> properties = new ArrayList<>(base);
        properties.addAll(settings);
        return Files.write(directory.resolve("ap-activity.properties"), properties, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path config) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(config), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<String> protocolTrace(List<String> trace) {
        return trace.stream().filter(line -> !line.contains(CHANGE) && !line.startsWith("summary=" + SUMMARY + " "))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<String> trace, String event) {
        return trace.stream().filter(line -> line.contains(" event=" + event + " "))
                .map(ApActivitySimulationTest::fields).collect(Collectors.toList());
    }

    private static List<String> eventTimes(List<String> trace, String event) {
        return events(trace, event).stream().map(row -> row.get("t_us")).collect(Collectors.toList());
    }

    private static List<Map<String, String>> changesFor(List<String> trace, int sfuId) {
        return events(trace, CHANGE).stream().filter(row -> Integer.toString(sfuId).equals(row.get("sfu_id")))
                .collect(Collectors.toList());
    }

    private static void assertTransitions(List<String> trace, int sfuId, String... expected) {
        assertEquals(List.of(expected), changesFor(trace, sfuId).stream()
                .map(row -> row.get("t_ns") + ":" + row.get("from") + ">" + row.get("to") + ":" + row.get("reason"))
                .collect(Collectors.toList()));
    }

    private static void assertActivity(List<String> trace, int sfuId, String mode, long end, long idle, long tx, long rx) {
        List<Map<String, String>> summaries = trace.stream().filter(line -> line.startsWith("summary=" + SUMMARY + " "))
                .map(ApActivitySimulationTest::fields)
                .filter(row -> Integer.toString(sfuId).equals(row.get("sfu_id")) && mode.equals(row.get("ap_mode")))
                .collect(Collectors.toList());
        assertEquals(1, summaries.size(), "each SFU and mode has one activity summary");
        Map<String, String> summary = summaries.get(0);
        assertEquals("0", summary.get("observation_start_ns"));
        assertEquals(Long.toString(end), summary.get("observation_end_ns"));
        assertEquals(Long.toString(idle), summary.get("idle_ns"));
        assertEquals(Long.toString(tx), summary.get("tx_ns"));
        assertEquals(Long.toString(rx), summary.get("rx_ns"));
        assertEquals(end, idle + tx + rx);

        // 从状态转换独立重算半开窗口内的累计时间，并验证同刻转换前后状态相连。
        Map<String, Long> activity = new HashMap<>(Map.of("IDLE", 0L, "TX", 0L, "RX", 0L));
        long since = 0;
        String state = "IDLE";
        for (Map<String, String> change : changesFor(trace, sfuId)) {
            assertEquals(mode, change.get("ap_mode"));
            long time = Long.parseLong(change.get("t_ns"));
            assertTrue(time >= since, "transitions must be ordered");
            if (time > end) {
                break;
            }
            assertEquals(state, change.get("from"));
            assertFalse(state.equals(change.get("to")), "a change must move to another state");
            activity.merge(state, time - since, Long::sum);
            state = change.get("to");
            since = time;
        }
        activity.merge(state, end - since, Long::sum);
        assertEquals(Map.of("IDLE", idle, "TX", tx, "RX", rx), activity);
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> row = new HashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            row.put(pair[0], pair[1]);
        }
        return row;
    }
}
