package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinrPerSimulationTest {
    private static final String ENABLE = "wifi.phy.per.enabled=";
    private static final String SEED = "wifi.phy.per.seed=";
    private static final String CURVES = "wifi.phy.per.curves=";
    private static final String TRUTH = "wifi.channel.truth=";
    private static final String DECODE = "WIFI_PHY_MPDU_DECODE";
    private static final String C1 = "WIFI_AP_OBSERVATION";

    private static final List<String> BASE = List.of(
            "traffic.mode=manual", "traffic.direction=DL", "packet.arrival.us=0", "packet.bytes=1500",
            "fiber.delay.us=0", "wifi.model=he-su", "sfu.1.wifi.ap.mode=HCM",
            "wifi.mac.access=edca-multi-ac", "wifi.mac.exchange=data-sifs-ack",
            "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0",
            "wifi.mac.ack.rate.mbps=24", "wifi.mac.ack.timeout.us=48",
            "wifi.ap.observation.enabled=true", "wifi.ap.observation.period.us=25",
            "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=1500",
            "wifi.ap.energy.enabled=true");

    @Test
    void disablingPerPreservesTheOriginalB6AndC1TraceExactly(@TempDir Path directory) throws IOException {
        List<String> settings = new ArrayList<>(List.of("packet.arrival.us=0,0,0,0", "wifi.mac.ampdu.enabled=true",
                "wifi.mac.ampdu.max-mpdus=4", "wifi.mac.ampdu.first-attempt-error.packet.ids=2,4"));
        List<String> baseline = run(directory, settings);
        settings.addAll(List.of(ENABLE + "false", TRUTH + flat(change(0, -20))));
        assertEquals(baseline, run(directory, settings));
        assertEquals(List.of(1L, 3L, 2L, 4L), packetIds(baseline, "DELIVERED"));
    }

    @Test
    void resamplesOnRetryAndFreezesSinrAtThePpduStart(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, TRUTH + flat(change(0, -20), change(43.001, 40)),
                "wifi.mac.retry.max=1");
        List<Map<String, String>> decoded = rows(trace, DECODE);
        assertEquals(2, decoded.size());
        assertEquals(List.of("SINR_ERROR", "DECODED"), List.of(decoded.get(0).get("outcome"), decoded.get(1).get("outcome")));
        assertEquals(43_000L, number(decoded.get(0), "ppdu_start_ns"));
        assertEquals(120_600L, timeNs(decoded.get(0)));
        assertEquals(0, number(decoded.get(0), "attempt"));
        assertEquals(1, number(decoded.get(1), "attempt"));
        assertEquals(-20.0, decimal(decoded.get(0), "sinr_db"), 1e-12);
        assertEquals(1.0, decimal(decoded.get(0), "per"), 1e-12);
        assertEquals(40.0, decimal(decoded.get(1), "sinr_db"), 1e-12);
        assertEquals(0.0, decimal(decoded.get(1), "per"), 1e-12);
        assertEquals(List.of(1L), packetIds(trace, "WIFI_ACK_TIMEOUT"));
        assertEquals(List.of(1L), packetIds(trace, "WIFI_MAC_RETRY"));
        assertEquals(List.of(1L), packetIds(trace, "DELIVERED"));
        assertEquals(List.of(1L), packetIds(trace, "WIFI_ACK_END"));
        assertTrue(rows(trace, "WIFI_COLLISION_START").isEmpty());
        assertEquals(1, countDl(trace, "arrival_packets"));
        assertEquals(1, countDl(trace, "retry_attempt_mpdus"));
        assertEquals(1, countDl(trace, "requeued_packets"));
        assertEquals(1, countDl(trace, "delivered_packets"));
        assertEquals(1, countDl(trace, "confirmed_packets"));
        assertEquals(0, countDl(trace, "dropped_packets"));
        assertEnergy(trace, 155_200, 28_000);
    }

    @Test
    void appliesATruthChangeExactlyAtThePpduStart(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, TRUTH + flat(change(0, -20), change(43, 40)),
                "wifi.mac.retry.max=0");
        assertEquals(List.of(43_000L), eventTimes(trace, "WIFI_TX_START"));
        assertEquals(40.0, decimal(only(trace, DECODE), "sinr_db"), 1e-12);
        assertEquals(List.of(1L), packetIds(trace, "DELIVERED"));
        assertTrue(rows(trace, "WIFI_ACK_TIMEOUT").isEmpty());
        assertEnergy(trace, 77_600, 28_000);
    }

    @Test
    void preservesAPartialBaBitmapAndRetriesOnlyItsNegativeMembers(@TempDir Path directory) throws IOException {
        String[] settings = {"packet.arrival.us=0,0,0,0,0,0,0,0", "wifi.mac.ampdu.enabled=true",
                "wifi.mac.ampdu.max-mpdus=8", TRUTH + flat(change(0, 10), change(43.001, 40)),
                "wifi.mac.retry.max=1"};
        List<String> trace = runPer(directory, settings);
        assertEquals(trace, runPer(directory, settings), "固定种子的全部输出必须可复现");
        List<Map<String, String>> decoded = rows(trace, DECODE);
        assertTrue(decoded.size() > 8 && decoded.size() < 16, "首轮应同时包含成功和失败的成员");
        assertEquals(8, decoded.stream().filter(row -> decimal(row, "sinr_db") == 10.0).count());
        assertEquals(1, decoded.stream().filter(row -> number(row, "attempt") == 0)
                .map(row -> number(row, "ppdu_start_ns")).distinct().count());
        assertEquals(List.of("1,2,3,4,5,6,7,8"), rows(trace, "WIFI_AMPDU_BA_END").stream()
                .limit(1).map(row -> row.get("packet_ids")).collect(Collectors.toList()));
        List<Long> positive = rows(trace, "WIFI_AMPDU_MPDU").stream()
                .filter(row -> "ACKED".equals(row.get("status"))).filter(row -> timeNs(row) == 291_000L)
                .map(row -> number(row, "packet_id")).collect(Collectors.toList());
        List<Long> negative = packetIds(trace, "WIFI_MAC_RETRY");
        assertFalse(positive.isEmpty());
        assertFalse(negative.isEmpty());
        assertEquals(8, positive.size() + negative.size());
        assertTrue(positive.stream().noneMatch(negative::contains));
        assertEquals(negative, decoded.stream().filter(row -> "SINR_ERROR".equals(row.get("outcome")))
                .map(row -> number(row, "packet_id")).collect(Collectors.toList()));
        assertEquals(negative.size(), decoded.size() - 8);
        assertEquals(negative.size(), countDl(trace, "retry_attempt_mpdus"));
        assertEquals(negative.size(), countDl(trace, "requeued_packets"));
        assertEquals(8, countDl(trace, "arrival_packets"));
        assertEquals(8, countDl(trace, "delivered_packets"));
        assertEquals(8, countDl(trace, "confirmed_packets"));
        assertEquals(0, countDl(trace, "dropped_packets"));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), packetIds(trace, "DELIVERED").stream()
                .sorted().collect(Collectors.toList()));
        List<String> changed = new ArrayList<>(List.of(settings));
        changed.add(SEED + "202");
        assertNotEquals(rows(trace, DECODE), rows(runPer(directory, changed.toArray(String[]::new)), DECODE));
    }

    @Test
    void timesOutWhenEveryMpduFailsAndDropsOnlyOncePerPacket(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, "packet.arrival.us=0,0", "wifi.mac.ampdu.enabled=true",
                "wifi.mac.ampdu.max-mpdus=2", TRUTH + flat(change(0, -20)), "wifi.mac.retry.max=1");
        assertEquals(4, rows(trace, DECODE).size());
        assertTrue(rows(trace, DECODE).stream().allMatch(row -> "SINR_ERROR".equals(row.get("outcome"))));
        assertEquals(List.of(1L, 2L), packetIds(trace, "WIFI_MAC_RETRY"));
        assertEquals(List.of(1L, 2L), packetIds(trace, "WIFI_RETRY_LIMIT_DROP"));
        assertEquals(2, rows(trace, "WIFI_ACK_TIMEOUT").size());
        for (String event : List.of("WIFI_AMPDU_BA_START", "WIFI_ACK_START", "DELIVERED", "WIFI_COLLISION_START")) {
            assertTrue(rows(trace, event).isEmpty(), event);
        }
        assertEquals(2, countDl(trace, "arrival_packets"));
        assertEquals(2, countDl(trace, "retry_attempt_mpdus"));
        assertEquals(2, countDl(trace, "requeued_packets"));
        assertEquals(2, countDl(trace, "dropped_packets"));
        assertEquals(0, countDl(trace, "delivered_packets"));
        assertEquals(0, countDl(trace, "confirmed_packets"));
        assertFalse(trace.stream().anyMatch(line -> line.startsWith("summary=WIFI_COLLISION ")));
        assertEquals(2, number(summary(trace, "WIFI_RETRY"), "retry_limit_drops"));
        assertEquals(List.of("DROPPED", "DROPPED"), rows(trace, "WIFI_AP_QUEUE_EVENT").stream()
                .filter(row -> "DROPPED".equals(row.get("action"))).map(row -> row.get("action"))
                .collect(Collectors.toList()));
        List<Map<String, String>> samples = dlSamples(trace);
        assertEquals(0, number(samples.get(samples.size() - 1), "pending_packets"));
        assertEquals(0, number(samples.get(samples.size() - 1), "inflight_packets"));
        assertEnergy(trace, 182_400, 0);
    }

    @Test
    void doesNotUseAStaleApObservationForPhyDecoding(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, "wifi.channel.observation.enabled=true",
                TRUTH + flat(change(0, 40), change(42, -20)),
                "wifi.channel.feedback=1,1,0,0,0,500", "wifi.channel.observe.us=43", "wifi.mac.retry.max=0");
        Map<String, String> ap = rows(trace, "WIFI_CHANNEL_OBSERVATION").stream()
                .filter(row -> number(row, "subchannel") == 0).findFirst().orElseThrow();
        assertEquals("VISIBLE", ap.get("status"));
        assertEquals(40.0, decimal(ap, "sinr_db"), 1e-12);
        assertEquals(-20.0, decimal(only(trace, DECODE), "sinr_db"), 1e-12);
        assertEquals(List.of(1L), packetIds(trace, "WIFI_RETRY_LIMIT_DROP"));
        assertTrue(rows(trace, "DELIVERED").isEmpty());
        assertEnergy(trace, 77_600, 0);
    }

    @Test
    void keepsExistingCollisionCountersSeparateFromSinrFailures(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, "traffic.direction=BOTH", "packet.arrival.us=0,0",
                "packet.1.direction=DL", "packet.1.type=VO", "packet.2.direction=UL", "packet.2.type=VO",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.retry.max=0", TRUTH + flat(change(0, 40)));
        assertEquals(Set.of(1L, 2L), Set.copyOf(packetIds(trace, "WIFI_COLLISION_START")));
        assertEquals(Set.of(1L, 2L), Set.copyOf(packetIds(trace, "WIFI_RETRY_LIMIT_DROP")));
        assertTrue(rows(trace, "DELIVERED").isEmpty());
        Map<String, String> collision = only(trace, DECODE);
        assertEquals(1, number(collision, "packet_id"));
        assertEquals("MAC_COLLISION", collision.get("outcome"));
        assertEquals("-", collision.get("sinr_db"));
        assertEquals("-", collision.get("per"));
        assertEquals("-", collision.get("draw"));
        assertEquals(1, number(summary(trace, "WIFI_COLLISION"), "collision_events"));
        assertEquals(2, number(summary(trace, "WIFI_COLLISION"), "dropped_packets"));
    }

    @Test
    void usesPerMpduLengthWithoutDelimiterOrSubframePadding(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, "packet.arrival.us=0,0", "packet.1.bytes=151",
                "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=2", "wifi.mac.retry.max=0",
                TRUTH + flat(change(0, 10)));
        List<Map<String, String>> decoded = rows(trace, DECODE);
        assertEquals(2, decoded.size());
        assertEquals(List.of(181L, 1530L), decoded.stream().map(row -> number(row, "mpdu_bytes"))
                .collect(Collectors.toList()));
        assertEquals(7, number(decoded.get(0), "mcs"));
        assertEquals(-Math.expm1(Math.log1p(-0.5) * 181.0 / 1530.0), decimal(decoded.get(0), "per"), 1e-9);
        assertEquals(0.5, decimal(decoded.get(1), "per"), 1e-9);
    }

    @Test
    void choosesEachLinkActualMcsRatherThanTheApOrGlobalDefault(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2",
                "wifi.phy.bandwidth.mhz=80", "wifi.phy.mcs=4", "packet.arrival.us=0,0", "packet.2.sta.id=2",
                "wifi.mac.retry.max=0", TRUTH + flat(change(0, 10)) + ";" + flat(2, 2, change(0, 10)),
                CURVES + "7,-20,0;7,40,0;4,-20,1;4,40,1");
        Map<Long, Map<String, String>> decoded = rows(trace, DECODE).stream()
                .collect(Collectors.toMap(row -> number(row, "packet_id"), row -> row));
        assertEquals(7, number(decoded.get(1L), "mcs"));
        assertEquals(4, number(decoded.get(2L), "mcs"));
        assertEquals("DECODED", decoded.get(1L).get("outcome"));
        assertEquals("SINR_ERROR", decoded.get(2L).get("outcome"));
        assertEquals(List.of(1L), packetIds(trace, "DELIVERED"));
        assertEquals(List.of(2L), packetIds(trace, "WIFI_RETRY_LIMIT_DROP"));
    }

    @Test
    void endsTxopAndCompetesAgainWhenItsSecondFrameIsUndecodable(@TempDir Path directory) throws IOException {
        List<String> trace = runPer(directory, "packet.arrival.us=0,0", "wifi.mac.edca.BE.txop.limit.us=400",
                "wifi.mac.retry.max=1", TRUTH + flat(change(0, 40), change(180.6, -20), change(300, 40)));
        List<Map<String, String>> decoded = rows(trace, DECODE);
        assertEquals(List.of("DECODED", "SINR_ERROR", "DECODED"),
                decoded.stream().map(row -> row.get("outcome")).collect(Collectors.toList()));
        assertEquals(List.of(1L, 2L, 2L), packetIds(trace, DECODE));
        assertEquals(180_600, number(decoded.get(1), "ppdu_start_ns"));
        assertEquals(258_200, eventTimes(trace, "WIFI_TXOP_END").get(0));
        assertEquals(List.of(306_200L), eventTimes(trace, "WIFI_ACK_TIMEOUT"));
        assertTrue(number(decoded.get(2), "ppdu_start_ns") >= 349_200);
        assertEquals(List.of(1L, 2L), packetIds(trace, "DELIVERED"));
        assertEquals(List.of(2L), packetIds(trace, "WIFI_MAC_RETRY"));
        assertEnergy(trace, 232_800, 56_000);
    }

    @Test
    void rejectsB6SyntheticDecodeFailuresWhenSinrSamplingIsEnabled(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> runPer(directory, "packet.arrival.us=0,0",
                "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.first-attempt-error.packet.ids=2",
                TRUTH + flat(change(0, 10))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,1,0,0,20,5,false",
            "1,1,0,0,20,5,false;1,1,1,0,20,5,false;1,1,2,0,8,3,false;1,1,3,0,20,5,false"})
    void rejectsUnknownOrNonFlatChannelsRatherThanAveraging(String truth, @TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> runPer(directory, TRUTH + truth));
    }

    private static List<String> runPer(Path directory, String... additions) throws IOException {
        List<String> settings = new ArrayList<>(List.of(ENABLE + "true", SEED + "101",
                CURVES + "7,-20,1;7,10,0.5;7,40,0", "wifi.phy.per.source=SYNTHETIC_TEST"));
        settings.addAll(List.of(additions));
        return run(directory, settings);
    }

    private static String flat(String... changes) {
        return flat(1, 1, changes);
    }

    private static String flat(int sfu, int sta, String... changes) {
        return IntStream.range(0, 4).mapToObj(subchannel -> List.of(changes).stream()
                .map(change -> sfu + "," + sta + "," + subchannel + "," + change).collect(Collectors.joining(";")))
                .collect(Collectors.joining(";"));
    }

    private static String change(double timeUs, double sinrDb) {
        return timeUs + "," + sinrDb + ",-1,false";
    }

    private static List<String> run(Path directory, List<String> settings) throws IOException {
        List<String> config = new ArrayList<>(BASE);
        config.addAll(settings);
        Path path = Files.write(directory.resolve("c2b1.properties"), config, StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> rows(List<String> trace, String name) {
        return trace.stream().map(SinrPerSimulationTest::fields).filter(row -> name.equals(row.get("event")))
                .collect(Collectors.toList());
    }

    private static Map<String, String> only(List<String> trace, String event) {
        List<Map<String, String>> matches = rows(trace, event);
        assertEquals(1, matches.size(), event);
        return matches.get(0);
    }

    private static List<Long> packetIds(List<String> trace, String event) {
        return rows(trace, event).stream().map(row -> number(row, "packet_id")).collect(Collectors.toList());
    }

    private static List<Long> eventTimes(List<String> trace, String event) {
        return rows(trace, event).stream().map(SinrPerSimulationTest::timeNs).collect(Collectors.toList());
    }

    private static long timeNs(Map<String, String> row) {
        return row.containsKey("t_ns") ? number(row, "t_ns")
                : new BigDecimal(row.get("t_us")).movePointRight(3).longValueExact();
    }

    private static List<Map<String, String>> dlSamples(List<String> trace) {
        return rows(trace, C1).stream().filter(row -> "DL".equals(row.get("direction")) && "BE".equals(row.get("ac")))
                .collect(Collectors.toList());
    }

    private static long countDl(List<String> trace, String field) {
        return dlSamples(trace).stream().mapToLong(row -> number(row, field)).sum();
    }

    private static void assertEnergy(List<String> trace, long txNs, long rxNs) {
        Map<String, String> activity = summary(trace, "WIFI_AP_ACTIVITY");
        assertEquals(txNs, number(activity, "tx_ns"));
        assertEquals(rxNs, number(activity, "rx_ns"));
        long idleNs = number(activity, "idle_ns");
        assertEquals(1_500_000, Math.addExact(idleNs, Math.addExact(txNs, rxNs)));
        Map<String, String> energy = summary(trace, "WIFI_AP_ENERGY");
        BigDecimal expected = energyAt(idleNs, energy.get("idle_power_w")).add(energyAt(txNs, energy.get("tx_power_w")))
                .add(energyAt(rxNs, energy.get("rx_power_w")));
        assertEquals(0, expected.compareTo(new BigDecimal(energy.get("total_energy_uj"))));
        assertEquals(txNs, countDl(trace, "tx_ns"));
        assertEquals(rxNs, countDl(trace, "rx_ns"));
    }

    private static BigDecimal energyAt(long nanoseconds, String watts) {
        return new BigDecimal(watts).multiply(BigDecimal.valueOf(nanoseconds)).movePointLeft(3);
    }

    private static Map<String, String> summary(List<String> trace, String name) {
        return trace.stream().map(SinrPerSimulationTest::fields).filter(row -> name.equals(row.get("summary")))
                .findFirst().orElseThrow();
    }

    private static long number(Map<String, String> row, String field) {
        return Long.parseLong(row.get(field));
    }

    private static double decimal(Map<String, String> row, String field) {
        return Double.parseDouble(row.get(field));
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            int separator = token.indexOf('=');
            if (separator >= 0) {
                row.put(token.substring(0, separator), token.substring(separator + 1));
            }
        }
        return row;
    }
}
