package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmpduSimulationTest {
    private static final String ON = "wifi.mac.ampdu.enabled=true";
    private static final String COUNT = "wifi.mac.ampdu.max-mpdus=";
    private static final String BYTE_CAP = "wifi.mac.ampdu.max-bytes=";
    private static final String DATA_START = "WIFI_AMPDU_DATA_START";
    private static final String DATA_END = "WIFI_AMPDU_DATA_END";
    private static final String BA_START = "WIFI_AMPDU_BA_START";
    private static final String BA_END = "WIFI_AMPDU_BA_END";
    private static final List<String> BASE = List.of(
            "fiber.delay.us=0", "packet.bytes=1500",
            "sfu.1.wifi.ap.mode=HCM", "wifi.model=he-su",
            "wifi.mac.access=edca-multi-ac", "wifi.mac.exchange=data-sifs-ack",
            "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0",
            "wifi.mac.sifs.us=16", "wifi.mac.slot.us=9", "wifi.mac.ack.rate.mbps=24");

    @Test
    void leavesTheSingleMpduExchangeIdenticalWithTheSwitchOffOrCapOne(@TempDir Path directory) throws IOException {
        List<String> baseline = run(directory, "packet.arrival.us=0,0");
        assertEquals(baseline, run(directory, "packet.arrival.us=0,0", "wifi.mac.ampdu.enabled=false"));
        assertEquals(baseline, run(directory, "packet.arrival.us=0,0", ON, COUNT + "1"));
        assertEquals(List.of(43_000L, 207_600L), eventTimes(baseline, "WIFI_TX_START"));
        assertEquals(List.of(164_600L, 329_200L), eventTimes(baseline, "WIFI_ACK_END"));

        List<String> singleton = run(directory, "packet.arrival.us=0", ON, COUNT + "8");
        assertEquals(run(directory, "packet.arrival.us=0"), singleton);
        assertTrue(events(singleton, BA_START).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"2,3070,91200,3", "4,6142,132000,f", "8,12286,200000,ff"})
    void aggregatesReadyMpdusIntoOnePpduAndOneBlockAck(int count, int apepBytes, long dataNs, String bitmap,
            @TempDir Path directory) throws IOException {
        String arrivals = IntStream.range(0, count).mapToObj(ignored -> "0").collect(Collectors.joining(","));
        List<String> trace = run(directory, "packet.arrival.us=" + arrivals, ON, COUNT + count);
        List<Integer> ids = IntStream.rangeClosed(1, count).boxed().collect(Collectors.toList());
        Map<String, String> start = onlyEvent(trace, DATA_START);
        assertEquals(Integer.toString(count), start.get("count"));
        assertEquals(Integer.toString(apepBytes), start.get("apep_bytes"));
        assertEquals(Long.toString(dataNs), start.get("ppdu_ns"));
        assertEquals("16000", start.get("sifs_ns"));
        assertEquals("32000", start.get("ba_ns"));
        assertEquals(ids.stream().map(Object::toString).collect(Collectors.joining(",")), start.get("packet_ids"));
        assertEquals(43_000L, ticks(start));
        assertEquals(43_000L + dataNs, ticks(onlyEvent(trace, DATA_END)));
        assertEquals(59_000L + dataNs, ticks(onlyEvent(trace, BA_START)));
        assertEquals(91_000L + dataNs, ticks(onlyEvent(trace, BA_END)));
        assertEquals(bitmap, onlyEvent(trace, BA_END).get("bitmap"));
        assertEquals(ids, packetIds(trace, "DELIVERED"));
        assertTrue(events(trace, "WIFI_ACK_START").isEmpty());
        assertTrue(events(trace, "WIFI_TXOP_START").isEmpty());
    }

    @Test
    void appliesByteCapToDelimitersAndPaddingWithoutSkippingTheQueue(@TempDir Path directory) throws IOException {
        List<String> tighter = run(directory, "packet.arrival.us=0,0,0", ON, COUNT + "8", BYTE_CAP + "3069");
        assertTrue(events(tighter, DATA_START).isEmpty());
        assertEquals(List.of(1, 2, 3), packetIds(tighter, "DELIVERED"));

        List<String> exact = run(directory, "packet.arrival.us=0,0,0", ON, COUNT + "8", BYTE_CAP + "3070");
        assertEquals("1,2", onlyEvent(exact, DATA_START).get("packet_ids"));
        assertEquals("3070", onlyEvent(exact, DATA_START).get("apep_bytes"));
        assertEquals(List.of(3), packetIds(exact, "WIFI_ACK_END"));
        assertEquals(List.of(1, 2, 3), packetIds(exact, "DELIVERED"));

        List<String> mixed = run(directory, "packet.arrival.us=0,0,0", "packet.1.bytes=100",
                "packet.2.bytes=500", ON, COUNT + "8");
        // 每个子帧的 34 B 开销含 delimiter。100 B 与 500 B 子帧补到 4 B 边界，末尾 1500 B 子帧不补。
        assertEquals("2206", onlyEvent(mixed, DATA_START).get("apep_bytes"));
        assertEquals(List.of(1, 2, 3), packetIds(mixed, "DELIVERED"));
    }

    @Test
    void neverMixesDifferentReceiversOrTrafficIdentifiers(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "packet.arrival.us=0,0,0,0,0,0", "sta.count=2",
                "packet.3.sta.id=2", "packet.4.sta.id=2", "packet.5.type=VI", "packet.6.type=VI",
                "wifi.mac.edca.VI.cwmin=0", ON, COUNT + "8");
        List<Map<String, String>> aggregates = events(trace, DATA_START);
        assertEquals(3, aggregates.size());
        Set<String> groups = aggregates.stream().map(row ->
                row.get("sta_id") + ":" + row.get("type") + ":" + row.get("packet_ids")).collect(Collectors.toSet());
        assertEquals(Set.of("1:BE:1,2", "2:BE:3,4", "1:VI:5,6"), groups);
        assertEquals(List.of(1, 2, 3, 4, 5, 6), sortedIds(trace, "DELIVERED"));
    }

    @Test
    void retainsOnlyTheNegativeBlockAckMemberAndDeliversItExactlyOnce(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "packet.arrival.us=0,0,0", ON, COUNT + "3",
                "wifi.mac.ampdu.first-attempt-error.packet.ids=2");
        Map<String, String> blockAck = onlyEvent(trace, BA_END);
        assertEquals("1,2,3", blockAck.get("packet_ids"));
        assertEquals("5", blockAck.get("bitmap")); // 位 0 和位 2 成功，位 1 未获确认。
        assertEquals(List.of(1, 3, 2), packetIds(trace, "DELIVERED"));
        assertEquals(List.of(1, 3), statusIds(trace, "ACKED"));
        assertEquals(List.of(2), statusIds(trace, "NACK"));
        assertEquals(List.of(2), packetIds(trace, "WIFI_MAC_RETRY"));
        assertEquals("1", onlyEvent(trace, "WIFI_MAC_RETRY").get("retry_count"));
        assertTrue(events(trace, "WIFI_ACK_TIMEOUT").isEmpty());
        assertEquals(List.of(2), packetIds(trace, "WIFI_ACK_END"));
        assertTrue(ticks(onlyEvent(trace, "WIFI_ACK_END")) > ticks(blockAck));
    }

    @Test
    void dropsOnlyTheNegativeMemberWhenRetriesAreDisabled(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "packet.arrival.us=0,0,0", ON, COUNT + "3",
                "wifi.mac.ampdu.first-attempt-error.packet.ids=2", "wifi.mac.retry.max=0");
        assertEquals("5", onlyEvent(trace, BA_END).get("bitmap"));
        assertEquals(List.of(1, 3), packetIds(trace, "DELIVERED"));
        assertEquals(List.of(2), packetIds(trace, "WIFI_RETRY_LIMIT_DROP"));
        assertEquals(List.of(2), statusIds(trace, "DROPPED"));
        assertTrue(events(trace, "WIFI_MAC_RETRY").isEmpty());
    }

    @Test
    void countsOneDataAndOneBlockAckIntervalInTheApEnergyLedger(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "packet.arrival.us=0,0", ON, COUNT + "2",
                "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=500", "wifi.ap.energy.enabled=true");
        Map<String, String> activity = summary(trace, "WIFI_AP_ACTIVITY");
        assertEquals("376800", activity.get("idle_ns"));
        assertEquals("91200", activity.get("tx_ns"));
        assertEquals("32000", activity.get("rx_ns"));
        assertEquals(500_000, IntStream.of(Integer.parseInt(activity.get("idle_ns")),
                Integer.parseInt(activity.get("tx_ns")), Integer.parseInt(activity.get("rx_ns"))).sum());
        Map<String, String> energy = summary(trace, "WIFI_AP_ENERGY");
        assertDecimal("2871.216", energy.get("idle_energy_uj"));
        assertDecimal("957.6", energy.get("tx_energy_uj"));
        assertDecimal("278.4", energy.get("rx_energy_uj"));
        assertDecimal("4107.216", energy.get("total_energy_uj"));
    }

    @Test
    void chargesUplinkDataToRxAndBlockAckToTx(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "packet.arrival.us=0,0", "traffic.direction=UL", ON, COUNT + "2",
                "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=500");
        assertEquals("UL", onlyEvent(trace, DATA_START).get("direction"));
        assertEquals(List.of(1, 2), sortedIds(trace, "DELIVERED"));
        Map<String, String> activity = summary(trace, "WIFI_AP_ACTIVITY");
        assertEquals("376800", activity.get("idle_ns"));
        assertEquals("32000", activity.get("tx_ns"));
        assertEquals("91200", activity.get("rx_ns"));
    }

    @Test
    void dropsEveryCollidingMpduWithoutAFalseBlockAck(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "packet.arrival.us=0,0,0,0", "traffic.direction=UL",
                "sta.count=2", "packet.3.sta.id=2", "packet.4.sta.id=2", "wifi.mac.retry.max=0",
                ON, COUNT + "2", "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=500");
        assertEquals(Set.of("1,2", "3,4"), events(trace, "WIFI_AMPDU_BA_TIMEOUT").stream()
                .map(row -> row.get("packet_ids")).collect(Collectors.toSet()));
        assertTrue(events(trace, BA_START).isEmpty());
        assertTrue(events(trace, "DELIVERED").isEmpty());
        assertEquals(List.of(1, 2, 3, 4), sortedIds(trace, "WIFI_RETRY_LIMIT_DROP"));
        Map<String, String> activity = summary(trace, "WIFI_AP_ACTIVITY");
        assertEquals("408800", activity.get("idle_ns"));
        assertEquals("0", activity.get("tx_ns"));
        assertEquals("91200", activity.get("rx_ns"));
    }

    @Test
    void honorsBothBackoffIntervalsWithoutTouchingOtherAccessCategories(@TempDir Path directory) throws IOException {
        List<String> shortAifs = run(directory, "packet.arrival.us=0,0", ON, COUNT + "2");
        List<String> oldAifs = run(directory, "packet.arrival.us=0,0", ON, COUNT + "2",
                "wifi.mac.edca.BE.aifsn=6");
        assertEquals(List.of(43_000L), eventTimes(shortAifs, DATA_START));
        assertEquals(List.of(70_000L), eventTimes(oldAifs, DATA_START));
        assertEquals(27_000L, ticks(onlyEvent(oldAifs, BA_END)) - ticks(onlyEvent(shortAifs, BA_END)));

        List<String> voice = run(directory, "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.BE.aifsn=3", ON, COUNT + "2");
        assertEquals(List.of(34_000L), eventTimes(voice, DATA_START));
    }

    private static List<String> run(Path directory, String... additions) throws IOException {
        List<String> settings = new ArrayList<>(BASE);
        settings.addAll(List.of(additions));
        Path path = Files.write(directory.resolve("ampdu.properties"), settings, StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Integer> sortedIds(List<String> trace, String event) {
        return packetIds(trace, event).stream().sorted().collect(Collectors.toList());
    }

    private static List<Integer> packetIds(List<String> trace, String event) {
        return events(trace, event).stream().map(row -> Integer.parseInt(row.get("packet_id")))
                .collect(Collectors.toList());
    }

    private static List<Integer> statusIds(List<String> trace, String status) {
        return events(trace, "WIFI_AMPDU_MPDU").stream().filter(row -> status.equals(row.get("status")))
                .map(row -> Integer.parseInt(row.get("packet_id"))).collect(Collectors.toList());
    }

    private static List<Long> eventTimes(List<String> trace, String event) {
        return events(trace, event).stream().map(AmpduSimulationTest::ticks).collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<String> trace, String name) {
        return trace.stream().filter(line -> line.contains(" event=" + name + " "))
                .map(AmpduSimulationTest::fields).collect(Collectors.toList());
    }

    private static Map<String, String> onlyEvent(List<String> trace, String name) {
        List<Map<String, String>> rows = events(trace, name);
        assertEquals(1, rows.size(), "Expected one event named " + name);
        return rows.get(0);
    }

    private static Map<String, String> summary(List<String> trace, String name) {
        List<String> rows = trace.stream().filter(line -> line.startsWith("summary=" + name + " "))
                .collect(Collectors.toList());
        assertEquals(1, rows.size(), "Expected one summary named " + name);
        return fields(rows.get(0));
    }

    private static long ticks(Map<String, String> row) {
        return row.containsKey("t_ns") ? Long.parseLong(row.get("t_ns"))
                : new BigDecimal(row.get("t_us")).movePointRight(3).longValueExact();
    }

    private static void assertDecimal(String expected, String actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)),
                () -> "Expected " + expected + ", got " + actual);
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
