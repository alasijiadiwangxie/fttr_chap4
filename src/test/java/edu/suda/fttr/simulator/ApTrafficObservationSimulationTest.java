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
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApTrafficObservationSimulationTest {
    private static final String ENABLE = "wifi.ap.observation.enabled=true";
    private static final String PERIOD = "wifi.ap.observation.period.us=";
    private static final String ALPHA = "wifi.ap.observation.ewma.alpha=";
    private static final String SAMPLE = "WIFI_AP_OBSERVATION";
    private static final String QUEUE = "WIFI_AP_QUEUE_EVENT";
    private static final List<String> BASE = List.of(
            "traffic.mode=manual", "traffic.direction=DL", "fiber.delay.us=0", "packet.bytes=1500",
            "sfu.1.wifi.ap.mode=HCM", "wifi.model=he-su", "wifi.mac.access=edca-multi-ac",
            "wifi.mac.exchange=data-sifs-ack", "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0",
            "wifi.mac.sifs.us=16", "wifi.mac.slot.us=9", "wifi.mac.ack.rate.mbps=24");

    @ParameterizedTest
    @CsvSource({
            "LCM,false,false", "LCM,true,false", "HCM,false,false", "HCM,true,false",
            "LCM,false,true", "LCM,true,true", "HCM,false,true", "HCM,true,true"
    })
    void observesAllAcsWithoutChangingAnyProtocolOrEnergyOutput(String mode, boolean aggregate, boolean saturated,
                                                               @TempDir Path directory) throws IOException {
        List<String> settings = new ArrayList<>(List.of(
                "sfu.1.wifi.ap.mode=" + mode, "wifi.mac.ampdu.enabled=" + aggregate, "wifi.mac.ampdu.max-mpdus=4",
                "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=1000", "wifi.ap.energy.enabled=true",
                "packet.1.type=VO", "packet.2.type=VI", "packet.3.type=BE", "packet.4.type=BK"));
        String arrivals = saturated ? IntStream.range(0, 32).mapToObj(i -> "0").collect(Collectors.joining(","))
                : "0,2000,4000,6000";
        settings.add("packet.arrival.us=" + arrivals);
        List<String> baseline = run(directory, settings);
        assertEquals(baseline, run(directory, with(settings, "wifi.ap.observation.enabled=false")));
        List<String> observed = run(directory, with(settings, ENABLE));

        assertEquals(baseline, originalTrace(observed));
        assertFalse(rows(observed, SAMPLE).isEmpty());
        assertTrue(observed.stream().anyMatch(line -> line.contains(" window_end_ns=1000000 ")));
        for (String ac : List.of("VO", "VI", "BE", "BK")) {
            assertTrue(rows(observed, SAMPLE).stream().anyMatch(row ->
                    ac.equals(row.get("ac")) && number(row, "arrival_packets") > 0));
        }
    }

    @Test
    void splitsAggregateAirtimeAcrossWindowsAndKeepsDeliveredMembersInFlightUntilBa(@TempDir Path directory)
            throws IOException {
        List<String> observed = run(directory, "packet.arrival.us=0,0,0,0", ENABLE, PERIOD + "50",
                "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=4",
                "wifi.mac.ampdu.first-attempt-error.packet.ids=2,4");
        Map<String, String> first = sample(observed, 1, "DL", "BE", 50_000);
        assertCounts(first, 0, 4, 4, 0, 0);
        assertEquals(7_000L, number(first, "tx_ns"));
        assertEquals(1L, number(first, "ppdus"));
        assertEquals(4L, number(first, "mpdus"));
        assertEquals(4.0, decimal(first, "avg_depth"), 1e-12);
        assertEquals(6_000L, number(first, "inflight_bytes"));

        assertEquals(50_000L, number(sample(observed, 1, "DL", "BE", 100_000), "tx_ns"));
        assertEquals(50_000L, number(sample(observed, 1, "DL", "BE", 150_000), "tx_ns"));
        Map<String, String> beforeBlockAck = sample(observed, 1, "DL", "BE", 200_000);
        assertCounts(beforeBlockAck, 0, 4, 0, 2, 0);
        assertEquals(2L, number(beforeBlockAck, "awaiting_confirm_packets"));
        assertEquals(25_000L, number(beforeBlockAck, "tx_ns"));
        assertEquals(9_000L, number(beforeBlockAck, "rx_ns"));

        Map<String, String> afterBlockAck = sample(observed, 1, "DL", "BE", 250_000);
        assertCounts(afterBlockAck, 2, 0, 0, 0, 2);
        assertEquals(23_000L, number(afterBlockAck, "rx_ns"));
        assertEquals(250_000L, number(afterBlockAck, "head_wait_ns"));
        assertEquals(3_000L, number(afterBlockAck, "pending_bytes"));
        assertEquals(2L, number(afterBlockAck, "requeued_packets"));
        assertEquals(0L, number(afterBlockAck, "retry_attempt_mpdus"));

        Map<String, String> retryStarts = sample(observed, 1, "DL", "BE", 300_000);
        assertCounts(retryStarts, 0, 2, 0, 0, 0);
        assertEquals(2L, number(retryStarts, "retry_attempt_mpdus"));
        assertEquals(2.0, decimal(retryStarts, "avg_depth"), 1e-12);
        assertEquals(4L, sumDlSamples(observed, "arrival_packets"));
        assertEquals(4L, sumDlSamples(observed, "delivered_packets"));
        assertEquals(4L, sumDlSamples(observed, "confirmed_packets"));
        assertEquals(0L, sumDlSamples(observed, "dropped_packets"));
        assertEquals(List.of(2L, 4L), actionPacketIds(observed, "RETRANSMIT_START"));
        assertEquals(List.of(1L, 3L, 2L, 4L), rows(observed, "DELIVERED").stream()
                .map(row -> number(row, "packet_id")).collect(Collectors.toList()));
    }

    @Test
    void countsPositiveBlockAckMembersAndFinalDropsExactlyOnce(@TempDir Path directory) throws IOException {
        List<String> observed = run(directory, "packet.arrival.us=0,0,0,0", ENABLE, PERIOD + "50",
                "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=4", "wifi.mac.retry.max=0",
                "wifi.mac.ampdu.first-attempt-error.packet.ids=2,4");
        assertEquals(4L, sumDlSamples(observed, "arrival_packets"));
        assertEquals(2L, sumDlSamples(observed, "delivered_packets"));
        assertEquals(2L, sumDlSamples(observed, "confirmed_packets"));
        assertEquals(2L, sumDlSamples(observed, "dropped_packets"));
        assertEquals(0L, sumDlSamples(observed, "retry_attempt_mpdus"));
        assertEquals(List.of(2L, 4L), actionPacketIds(observed, "DROPPED"));
    }

    @Test
    void snapshotsAfterSameTickArrivalsWithoutRepeatingThemInTheNextWindow(@TempDir Path directory)
            throws IOException {
        List<String> observed = run(directory, "packet.arrival.us=40,90,290", "fiber.delay.us=10", ENABLE,
                PERIOD + "50", ALPHA + "0.5");
        Map<String, String> first = sample(observed, 1, "DL", "BE", 50_000);
        assertCounts(first, 1, 0, 1, 0, 0);
        assertEquals(0L, number(first, "head_wait_ns"));
        assertEquals(0L, number(first, "window_start_ns"));
        assertEquals(20_000.0, decimal(first, "arrival_pps"), 1e-9);
        assertEquals(20_000.0, decimal(first, "ewma_arrival_pps"), 1e-9);

        Map<String, String> second = sample(observed, 1, "DL", "BE", 100_000);
        assertCounts(second, 1, 1, 1, 0, 0);
        assertEquals(0L, number(second, "head_wait_ns"));
        assertEquals(20_000.0, decimal(second, "arrival_pps"), 1e-9);
        assertEquals(20_000.0, decimal(second, "ewma_arrival_pps"), 1e-9);
        Map<String, String> third = sample(observed, 1, "DL", "BE", 150_000);
        assertCounts(third, 1, 1, 0, 0, 0);
        assertEquals(50_000L, number(third, "head_wait_ns"));
        assertEquals(0.0, decimal(third, "arrival_pps"), 1e-9);
        assertEquals(10_000.0, decimal(third, "ewma_arrival_pps"), 1e-9);
        assertEquals(50_000L, number(second, "window_start_ns"));
        assertEquals(100_000L, number(third, "window_start_ns"));
        assertEquals(3L, sumDlSamples(observed, "arrival_packets"));
    }

    @Test
    void hidesStationQueuesAndKeepsApObservationsIsolated(@TempDir Path directory) throws IOException {
        List<String> observed = run(directory, "traffic.direction=BOTH", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "sfu.2.wifi.ap.mode=LCM", "packet.1.direction=DL",
                "packet.2.sta.id=2", "packet.2.direction=UL",
                "packet.arrival.us=0,0", "fiber.delay.us=60", ENABLE, PERIOD + "50");
        assertCounts(sample(observed, 1, "DL", "BE", 50_000), 0, 0, 0, 0, 0);
        assertCounts(sample(observed, 1, "DL", "BE", 100_000), 1, 0, 1, 0, 0);
        assertCounts(sample(observed, 2, "DL", "BE", 100_000), 0, 0, 0, 0, 0);
        assertCounts(sample(observed, 2, "UL", "BE", 100_000), 0, 0, 0, 0, 0);
        assertCounts(sample(observed, 1, "UL", "BE", 100_000), 0, 0, 0, 0, 0);
        assertEquals(50_000L, number(sample(observed, 2, "UL", "BE", 100_000), "rx_ns"));

        List<Map<String, String>> uplinkSamples = rows(observed, SAMPLE).stream().filter(row ->
                "2".equals(row.get("sfu_id")) && "BE".equals(row.get("ac")) && "UL".equals(row.get("direction")))
                .collect(Collectors.toList());
        assertEquals(1L, uplinkSamples.stream().mapToLong(row -> number(row, "arrival_packets")).sum());
        assertEquals(1L, uplinkSamples.stream().mapToLong(row -> number(row, "delivered_packets")).sum());
        assertEquals(0L, uplinkSamples.stream().mapToLong(row -> number(row, "pending_packets")).sum());
        assertEquals(0L, uplinkSamples.stream().mapToLong(row -> number(row, "inflight_packets")).sum());
        assertTrue(rows(observed, QUEUE).stream().filter(row -> "UL".equals(row.get("direction")))
                .noneMatch(row -> number(row, "t_ns") <= 100_000L));
    }

    @Test
    void attributesHalfDuplexCollisionAirtimeWithoutCountingPrivateUplinkAttempts(@TempDir Path directory)
            throws IOException {
        List<String> settings = List.of("traffic.direction=BOTH", "packet.arrival.us=0,0", "packet.bytes=1200",
                "sta.count=2", "packet.1.direction=DL", "packet.1.type=VO", "packet.1.sta.id=2",
                "packet.2.direction=UL", "packet.2.type=VO", "sta.1.wifi.max.nss=1", "wifi.mac.edca.VO.cwmin=0",
                "wifi.mac.retry.max=0", "wifi.mac.ack.timeout.us=5");
        List<String> observed = run(directory, with(with(settings, ENABLE), PERIOD + "100"));
        assertEquals(run(directory, settings), originalTrace(observed));
        assertEquals(1L, sumDlSamples(observed, "arrival_packets"));
        assertEquals(1L, sumDlSamples(observed, "dropped_packets"));
        assertEquals(0L, sumDlSamples(observed, "delivered_packets"));
        assertEquals(List.of(1L), actionPacketIds(observed, "DROPPED"));
        assertEquals(77_600L, sumAirtime(observed, "tx_ns"));
        assertEquals(6_400L, sumAirtime(observed, "rx_ns"));
        assertTrue(rows(observed, SAMPLE).stream().filter(row -> "UL".equals(row.get("direction")))
                .allMatch(row -> number(row, "arrival_packets") == 0 && number(row, "retry_attempt_mpdus") == 0));
    }

    @Test
    void observesTheEntireExchangeEvenWhenTheEnergyWindowClipsData(@TempDir Path directory) throws IOException {
        List<String> settings = List.of("packet.arrival.us=0,0", "wifi.mac.ampdu.enabled=true",
                "wifi.mac.ampdu.max-mpdus=2", "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=50.125",
                "wifi.ap.energy.enabled=true");
        List<String> observed = run(directory, with(with(settings, ENABLE), PERIOD + "50"));
        assertEquals(run(directory, settings), originalTrace(observed));

        Map<String, String> b2 = observed.stream().filter(line -> line.startsWith("summary=WIFI_AP_ACTIVITY "))
                .findFirst().map(ApTrafficObservationSimulationTest::fields).orElseThrow();
        assertEquals(50_125L, number(b2, "observation_end_ns"));
        assertEquals(7_125L, number(b2, "tx_ns"));
        assertEquals(0L, number(b2, "rx_ns"));
        assertEquals(91_200L, sumAirtime(observed, "tx_ns"));
        assertEquals(32_000L, sumAirtime(observed, "rx_ns"));
        assertEquals(2L, number(sample(observed, 1, "DL", "BE", 182_200L), "confirmed_packets"));
    }

    @Test
    void preservesLegacyClockOutputAndEndsAtTheLastRealEvent(@TempDir Path directory) throws IOException {
        List<String> settings = List.of("traffic.mode=manual", "packet.arrival.us=0,10", "packet.bytes=1200",
                "fiber.delay.us=0", "wifi.model=fixed", "wifi.delay.us=8");
        List<String> baseline = runProperties(directory, settings);
        List<String> observed = runProperties(directory, with(with(settings, ENABLE), PERIOD + "5.5"));
        assertEquals(baseline, originalTrace(observed));
        assertEquals(2L, sumDlSamples(observed, "arrival_packets"));
        assertEquals(2L, sumDlSamples(observed, "confirmed_packets"));
        assertEquals(5_500L, number(sample(observed, 1, "DL", "BE", 5_500L), "tx_ns"));
        assertEquals(18_000L, rows(observed, SAMPLE).stream().mapToLong(row -> number(row, "window_end_ns"))
                .max().orElseThrow());
        assertEquals(16_000L, sumAirtime(observed, "tx_ns"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0001", "abc", "9223372036854775.808"})
    void rejectsInvalidSamplingPeriods(String period, @TempDir Path directory) throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> run(directory, "packet.arrival.us=0", ENABLE, PERIOD + period));
        assertTrue(error.getMessage().startsWith("wifi.ap.observation.period.us"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.5", "1.1", "Infinity", "NaN"})
    void rejectsInvalidEwmaWeights(String alpha, @TempDir Path directory) throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> run(directory, "packet.arrival.us=0", ENABLE, ALPHA + alpha));
        assertTrue(error.getMessage().startsWith("wifi.ap.observation.ewma.alpha"));
    }

    private static List<String> run(Path directory, String... settings) throws IOException {
        return run(directory, List.of(settings));
    }

    private static List<String> run(Path directory, List<String> settings) throws IOException {
        List<String> config = new ArrayList<>(BASE);
        config.addAll(settings);
        return runProperties(directory, config);
    }

    private static List<String> runProperties(Path directory, List<String> settings) throws IOException {
        Path file = Files.write(directory.resolve("c1-observation.properties"), settings, StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(file), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<String> with(List<String> settings, String key) {
        List<String> result = new ArrayList<>(settings);
        result.add(key);
        return result;
    }

    private static List<String> originalTrace(List<String> observed) {
        return observed.stream().filter(line -> !isEvent(line, SAMPLE) && !isEvent(line, QUEUE)
                && !isEvent(line, "WIFI_AP_PPDU_OBSERVED"))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> rows(List<String> trace, String event) {
        return trace.stream().filter(line -> isEvent(line, event))
                .map(ApTrafficObservationSimulationTest::fields).collect(Collectors.toList());
    }

    private static boolean isEvent(String line, String event) {
        String field = "event=" + event + " ";
        return line.startsWith(field) || line.contains(" " + field);
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> result = new HashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            result.put(pair[0], pair[1]);
        }
        return result;
    }

    private static Map<String, String> sample(List<String> trace, int sfuId, String direction, String ac, long endNs) {
        List<Map<String, String>> candidates = rows(trace, SAMPLE).stream()
                .filter(row -> number(row, "sfu_id") == sfuId && direction.equals(row.get("direction"))
                        && ac.equals(row.get("ac")) && number(row, "window_end_ns") == endNs)
                .collect(Collectors.toList());
        assertEquals(1, candidates.size(), "One " + direction + " " + ac + " row at " + endNs + " for SFU " + sfuId);
        return candidates.get(0);
    }

    private static void assertCounts(Map<String, String> sample, long pending, long inflight, long arrived,
                                     long delivered, long confirmed) {
        assertEquals(pending, number(sample, "pending_packets"), "pending");
        assertEquals(inflight, number(sample, "inflight_packets"), "in-flight");
        assertEquals(arrived, number(sample, "arrival_packets"), "new arrivals");
        assertEquals(delivered, number(sample, "delivered_packets"), "successful wireless deliveries");
        assertEquals(confirmed, number(sample, "confirmed_packets"), "confirmations");
    }

    private static long sumDlSamples(List<String> trace, String field) {
        return rows(trace, SAMPLE).stream().filter(row -> "DL".equals(row.get("direction")))
                .mapToLong(row -> number(row, field)).sum();
    }

    private static long sumAirtime(List<String> trace, String field) {
        // 无线时长为 AP 级，取一个 AC 和方向，避免按业务行重复累计同一窗口。
        return rows(trace, SAMPLE).stream().filter(row -> "DL".equals(row.get("direction")) && "BE".equals(row.get("ac")))
                .mapToLong(row -> number(row, field)).sum();
    }

    private static List<Long> actionPacketIds(List<String> trace, String action) {
        return rows(trace, QUEUE).stream().filter(row -> action.equals(row.get("action")))
                .map(row -> number(row, "packet_id")).collect(Collectors.toList());
    }

    private static long number(Map<String, String> row, String field) {
        return Long.parseLong(row.get(field));
    }

    private static double decimal(Map<String, String> row, String field) {
        return Double.parseDouble(row.get(field));
    }
}
