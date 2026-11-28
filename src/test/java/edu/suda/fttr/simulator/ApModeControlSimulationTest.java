package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.entity.ApModeController;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApModeControlSimulationTest {
    private static final String POLICY = "wifi.ap.control.policy=";
    private static final String FIXED = POLICY + "fixed-threshold";
    private static final String HYSTERESIS = POLICY + "hysteresis";
    private static final String LOWER = "wifi.ap.control.hysteresis.lower.mbps=";
    private static final String UPPER = "wifi.ap.control.hysteresis.upper.mbps=";
    private static final String PERIOD = "wifi.ap.control.period.us=";
    private static final String ALPHA = "wifi.ap.control.ewma.alpha=";
    private static final String THRESHOLD = "wifi.ap.control.threshold.mbps=";
    private static final String WINDOW = "wifi.ap.activity.window.us=";
    private static final String INITIAL = "sfu.1.wifi.ap.mode=";
    private static final String DECISION = "WIFI_AP_MODE_DECISION";
    private static final String SWITCH = "WIFI_AP_SWITCH_";
    private static final List<String> BASE = List.of("fiber.delay.us=0", "packet.bytes=1500",
            "wifi.model=he-su", "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac",
            "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0", "wifi.mac.edca.seed=101",
            "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=8", "wifi.mac.ack.timeout.us=60",
            "wifi.ap.activity.enabled=true", "wifi.ap.energy.enabled=true", "wifi.ap.switch.enabled=true",
            "wifi.ap.switch.lcm-to-hcm.delay.us=40.125", "wifi.ap.switch.lcm-to-hcm.power.w=11.50",
            "wifi.ap.switch.hcm-to-lcm.delay.us=25.250", "wifi.ap.switch.hcm-to-lcm.power.w=8.75");

    @Test
    void defaultEwmaUsesArrivingBytesAndDoesNotRoundTheThreshold() {
        var controller = new ApModeController(new ApModeController.Settings(Map.of(), 5_000_000, .25, 31));
        assertEquals(ApCapabilityMode.LCM, controller.decide(5_000_000, 19_375).target());
        assertEquals(ApCapabilityMode.LCM, controller.decide(10_000_000, 38_750).target());
        var rising = controller.decide(15_000_000, 70_000);
        assertEquals(50, rising.arrivalMbps());
        assertEquals(35.75, rising.estimateMbps());
        assertEquals(ApCapabilityMode.HCM, rising.target());
        var falling = controller.decide(20_000_000, 76_250);
        assertEquals(29.3125, falling.estimateMbps());
        assertEquals(ApCapabilityMode.LCM, falling.target());
    }

    @Test
    void loadAtOrBelowThresholdFallsToLcmWithoutHysteresis(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, PERIOD + "400", ALPHA + "1", WINDOW + "1700",
                "packet.arrival.us=1,401,801,1201", "packet.1.bytes=1550", "packet.2.bytes=1551",
                "packet.3.bytes=1549", "packet.4.bytes=1551");
        assertEquals(List.of("31.0", "31.02", "30.98", "31.02"), values(trace, DECISION, "ewma_mbps"));
        assertEquals(List.of("LCM", "HCM", "LCM", "HCM"), values(trace, DECISION, "target_mode"));
        assertEquals(List.of("HCM", "LCM", "HCM"), values(trace, SWITCH + "END", "to_mode"));
        assertEquals(List.of("800000", "1200000", "1600000"), values(trace, SWITCH + "START", "t_ns"));
        assertEquals(List.of(461.4375, 220.9375, 461.4375),
                values(trace, SWITCH + "END", "switch_energy_uj").stream().map(Double::valueOf)
                        .collect(Collectors.toList()));
    }

    @Test
    void hysteresisDefaultsTo21And33AndRetainsTargetOnBothEdges(@TempDir Path directory) throws IOException {
        var trace = run(directory, HYSTERESIS, PERIOD + "400", ALPHA + "1", WINDOW + "2100",
                "packet.arrival.us=1,401,801,1201,1601", "packet.1.bytes=1650", "packet.2.bytes=1700",
                "packet.3.bytes=1050", "packet.4.bytes=1000", "packet.5.bytes=1400");
        assertEquals(List.of("33.0", "34.0", "21.0", "20.0", "28.0"), values(trace, DECISION, "ewma_mbps"));
        assertEquals(List.of("LCM", "HCM", "HCM", "LCM", "LCM"), values(trace, DECISION, "target_mode"));
        assertEquals(List.of("HCM", "LCM"), values(trace, SWITCH + "END", "to_mode"));
        assertEquals(List.of("hysteresis", "hysteresis"), values(trace, SWITCH + "END", "policy"));
    }

    @Test
    void hysteresisKeepsAWaitingRequestUntilTheFrameExchangeCanFinish(@TempDir Path directory) throws IOException {
        var trace = run(directory, HYSTERESIS, LOWER + "26", UPPER + "36", PERIOD + "100", ALPHA + "1", WINDOW + "250",
                "packet.arrival.us=0,0,0,150", "packet.4.bytes=375");
        assertEquals(List.of("REQUESTED", "PENDING"), values(trace, DECISION, "action"));
        assertEquals(List.of("HCM", "HCM"), values(trace, DECISION, "target_mode"));
        assertEquals(ticks(only(trace, "WIFI_AMPDU_BA_END")), ticks(only(trace, SWITCH + "START")));
        assertEquals(List.of("1", "2", "3", "4"), values(trace, "DELIVERED", "packet_id"));
    }

    @Test
    void hysteresisRetainsTheInitialModeIndependentlyPerSfu(@TempDir Path directory) throws IOException {
        var trace = run(directory, HYSTERESIS, LOWER + "26", UPPER + "36", PERIOD + "400", ALPHA + "1", WINDOW + "500",
                INITIAL + "HCM", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.bytes=1550",
                "packet.arrival.us=1,1", "packet.2.sta.id=2");
        assertEquals(List.of("HCM", "LCM"), values(trace, DECISION, "target_mode"));
        assertTrue(events(trace, SWITCH + "REQUEST").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "36", "37", "NaN", "-1"})
    void hysteresisRequiresACompleteOrderedFiniteOverrideBand(String lower, @TempDir Path directory) throws IOException {
        var path = lower.equals("missing")
                ? config(directory, HYSTERESIS, "packet.arrival.us=0", UPPER + "36")
                : config(directory, HYSTERESIS, "packet.arrival.us=0", UPPER + "36", LOWER + lower);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(path));
    }

    @Test
    void countsOnlyLocalDlIngressIncludingTheDecisionBoundary(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, PERIOD + "100", ALPHA + "1", WINDOW + "305", "fiber.delay.us=40",
                "traffic.direction=BOTH", "packet.arrival.us=0,60,60,161", "packet.1.direction=UL",
                "packet.2.direction=DL", "packet.3.direction=DL", "packet.4.direction=DL");
        assertEquals(List.of("3000", "0", "1500"), values(trace, DECISION, "arrival_bytes"));
        assertEquals(List.of("240.0", "0.0", "120.0"), values(trace, DECISION, "arrival_mbps"));
        assertEquals(List.of("HCM", "LCM", "HCM"), values(trace, DECISION, "target_mode"));
    }

    @Test
    void defersUntilPartialBlockAckThenRetransmitsEachMissedMemberOnce(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, PERIOD + "100", "packet.arrival.us=0,0,0", WINDOW + "650",
                "wifi.mac.ampdu.first-attempt-error.packet.ids=2");
        assertEquals("100000", only(trace, SWITCH + "REQUEST").get("t_ns"));
        assertTrue(values(trace, DECISION, "action").contains("PENDING"));
        Map<String, String> blockAck = only(trace, "WIFI_AMPDU_BA_END");
        assertEquals("5", blockAck.get("bitmap"));
        assertEquals(blockAck.get("t_ns"), only(trace, SWITCH + "START").get("t_ns"));
        assertEquals(List.of("2"), values(trace, "WIFI_MAC_RETRY", "packet_id"));
        assertEquals(List.of("1", "3", "2"), values(trace, "DELIVERED", "packet_id"));
        assertTrue(ticks(events(trace, "WIFI_TX_START").get(1)) > ticks(only(trace, SWITCH + "END")));
    }

    @Test
    void defersARequestThatArrivesDuringTheRetransmission(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, INITIAL + "HCM", PERIOD + "300", THRESHOLD + "121",
                WINDOW + "500", "packet.arrival.us=0,0,0", "wifi.mac.ampdu.first-attempt-error.packet.ids=2");
        Map<String, String> retry = events(trace, "WIFI_TX_START").get(1);
        assertEquals("2", retry.get("packet_id"));
        assertTrue(ticks(retry) < 300_000);
        assertEquals("300000", only(trace, SWITCH + "REQUEST").get("t_ns"));
        assertTrue(ticks(only(trace, "WIFI_ACK_END")) > 300_000);
        assertEquals(ticks(only(trace, "WIFI_ACK_END")), ticks(only(trace, SWITCH + "START")));
        assertEquals("LCM", only(trace, SWITCH + "END").get("to_mode"));
    }

    @Test
    void waitsForMissingAckTimeoutBeforeSwitchAndRetainsThePacket(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, INITIAL + "HCM", PERIOD + "140", THRESHOLD + "100",
                WINDOW + "700", "packet.arrival.us=0", "wifi.phy.per.enabled=true", "wifi.phy.per.seed=101",
                "wifi.phy.per.curves=7,0,1;7,30,0", "wifi.channel.observation.enabled=true",
                "wifi.phy.per.source=SYNTHETIC_D1_TEST",
                "wifi.channel.truth=" + truth(-5, 0) + ";" + truth(35, 200));
        assertEquals("140000", only(trace, SWITCH + "REQUEST").get("t_ns"));
        assertEquals(ticks(only(trace, "WIFI_ACK_TIMEOUT")), ticks(only(trace, SWITCH + "START")));
        assertEquals(List.of("1"), values(trace, "WIFI_MAC_RETRY", "packet_id"));
        assertEquals(List.of("1"), values(trace, "DELIVERED", "packet_id"));
        assertFalse(values(trace, "WIFI_RETRY_LIMIT_DROP", "packet_id").contains("1"));
    }

    @Test
    void cancelsStaleRequestWhileAggregateStillOwnsTheChannel(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, PERIOD + "100", ALPHA + "1", WINDOW + "350",
                "packet.arrival.us=0,0,0");
        assertEquals(List.of("REQUESTED", "CANCELLED", "HOLD"), values(trace, DECISION, "action"));
        assertEquals(only(trace, SWITCH + "REQUEST").get("switch_id"),
                only(trace, SWITCH + "CANCELLED").get("switch_id"));
        assertTrue(events(trace, SWITCH + "START").isEmpty());
        assertEquals(List.of("1", "2", "3"), values(trace, "DELIVERED", "packet_id"));
    }

    @Test
    void sameTickBackoffCompletionWaitsForTheSwitchAndNewAifs(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, PERIOD + "43", WINDOW + "300", "packet.arrival.us=0");
        assertEquals("43000", only(trace, SWITCH + "START").get("t_ns"));
        assertEquals("83125", only(trace, SWITCH + "END").get("t_ns"));
        assertEquals(126_125, ticks(only(trace, "WIFI_TX_START")));
        assertEquals(List.of("1"), values(trace, "DELIVERED", "packet_id"));
    }

    @Test
    void activeSwitchCompletesAndSupersededReverseRequestIsCancelled(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, PERIOD + "100", ALPHA + "1", WINDOW + "400",
                "packet.arrival.us=100,300", "wifi.ap.switch.lcm-to-hcm.delay.us=240");
        assertEquals(List.of("REQUESTED", "REQUESTED", "CANCELLED"), values(trace, DECISION, "action"));
        assertEquals("100000", only(trace, SWITCH + "START").get("t_ns"));
        assertEquals("340000", only(trace, SWITCH + "END").get("t_ns"));
        assertEquals("2", only(trace, SWITCH + "CANCELLED").get("switch_id"));
        assertEquals(List.of("1", "2"), values(trace, "DELIVERED", "packet_id"));
    }

    @Test
    void controlsSeparateSfusAndIsReproducible(@TempDir Path directory) throws IOException {
        String[] config = {FIXED, PERIOD + "100", THRESHOLD + "120", WINDOW + "400", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "packet.arrival.us=100,100,100", "packet.3.sta.id=2"};
        List<String> trace = run(directory, config);
        assertEquals(trace, run(directory, config));
        List<Map<String, String>> decisions = events(trace, DECISION);
        assertEquals("240.0", decisions.get(0).get("arrival_mbps"));
        assertEquals("120.0", decisions.get(1).get("arrival_mbps"));
        assertEquals("1", only(trace, SWITCH + "END").get("sfu_id"));
        assertEquals("HCM", only(trace, SWITCH + "END").get("to_mode"));
        assertEquals(3, events(trace, "DELIVERED").size());
    }

    @Test
    void naturalControlClockDoesNotExtendTheLastProtocolEvent(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, FIXED, "packet.arrival.us=0,5500");
        assertEquals("5000000", only(trace, DECISION).get("t_ns"));
        assertEquals("1500", only(trace, DECISION).get("arrival_bytes"));
        long lastAck = ticks(events(trace, "WIFI_ACK_END").get(1));
        assertTrue(trace.stream().anyMatch(line -> line.startsWith("summary=WIFI_AP_ACTIVITY ")
                && line.contains(" observation_end_ns=" + lastAck + " ")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LCM", "HCM"})
    void alwaysAndDisabledPoliciesPreserveStaticProtocolAndEnergy(String mode, @TempDir Path directory)
            throws IOException {
        List<String> baseline = run(directory, INITIAL + mode, "packet.arrival.us=0,0,750", WINDOW + "900");
        List<String> always = run(directory, POLICY + "always-" + mode.toLowerCase(Locale.ROOT),
                "packet.arrival.us=0,0,750", WINDOW + "900");
        assertEquals(mode, only(always, "WIFI_AP_MODE_INITIAL").get("current_mode"));
        assertEquals(baseline, always.stream().filter(line -> !line.contains(" event=WIFI_AP_MODE_INITIAL "))
                .collect(Collectors.toList()));
        assertEquals(baseline, run(directory, POLICY + "off", INITIAL + mode, "packet.arrival.us=0,0,750",
                WINDOW + "900"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wifi.ap.control.policy=adaptive", "wifi.ap.control.period.us=0",
            "wifi.ap.control.ewma.alpha=0", "wifi.ap.control.ewma.alpha=1.1",
            "wifi.ap.control.threshold.mbps=NaN", "wifi.ap.control.threshold.mbps=-1",
            "wifi.ap.switch.enabled=false", "sfu.1.wifi.ap.switch.requests=5:HCM",
            "sfu.2.wifi.ap.control.policy=always-hcm", "wifi.model=fixed"})
    void rejectsInvalidControlConfigurations(String invalid, @TempDir Path directory) throws IOException {
        Path path = config(directory, FIXED, "packet.arrival.us=0", invalid);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(path));
    }

    private static String truth(int sinr, int timeUs) {
        return java.util.stream.IntStream.range(0, 4).mapToObj(band ->
                "1,1," + band + "," + timeUs + "," + sinr + ",-1,false,-95").collect(Collectors.joining(";"));
    }

    private static Path config(Path dir, String... additions) throws IOException {
        List<String> properties = new ArrayList<>(BASE);
        properties.addAll(List.of(additions));
        return Files.write(dir.resolve("d1.properties"), properties, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path dir, String... additions) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(config(dir, additions)), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<String> trace, String name) {
        return trace.stream().filter(line -> line.contains(" event=" + name + " ")).map(line -> {
            Map<String, String> row = new HashMap<>();
            for (String token : line.split(" ")) {
                String[] pair = token.split("=", 2);
                row.put(pair[0], pair[1]);
            }
            return row;
        }).collect(Collectors.toList());
    }

    private static Map<String, String> only(List<String> trace, String name) {
        List<Map<String, String>> rows = events(trace, name);
        assertEquals(1, rows.size(), "Expected one event named " + name);
        return rows.get(0);
    }

    private static List<String> values(List<String> trace, String name, String key) {
        return events(trace, name).stream().map(row -> row.get(key)).collect(Collectors.toList());
    }

    private static long ticks(Map<String, String> row) {
        return row.containsKey("t_ns") ? Long.parseLong(row.get("t_ns"))
                : new BigDecimal(row.get("t_us")).movePointRight(3).longValueExact();
    }
}
