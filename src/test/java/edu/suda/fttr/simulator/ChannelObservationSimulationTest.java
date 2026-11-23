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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelObservationSimulationTest {
    private static final String ENABLE = "wifi.channel.observation.enabled=";
    private static final String TRUTH = "wifi.channel.truth=";
    private static final String FEEDBACK = "wifi.channel.feedback=";
    private static final String READ_TIMES = "wifi.channel.observe.us=";
    private static final String TRUTH_EVENT = "WIFI_CHANNEL_TRUTH";
    private static final String FEEDBACK_EVENT = "WIFI_CHANNEL_FEEDBACK";
    private static final String READ_EVENT = "WIFI_CHANNEL_OBSERVATION";
    private static final String LOCAL_EVENT = "WIFI_CHANNEL_LOCAL";
    private static final List<String> C2A_EVENTS = List.of(TRUTH_EVENT, FEEDBACK_EVENT, READ_EVENT, LOCAL_EVENT);
    private static final List<String> BASE = List.of(
            "traffic.mode=manual", "traffic.direction=DL", "packet.arrival.us=0", "packet.bytes=1500",
            "fiber.delay.us=0", "wifi.model=he-su", "sfu.1.wifi.ap.mode=HCM", "wifi.mac.access=edca-multi-ac",
            "wifi.mac.exchange=data-sifs-ack", "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0",
            "wifi.mac.ack.rate.mbps=24");

    @Test
    void measuresOnlyCurrentTruthAndPublishesAfterArrivalUntilExpiry(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, ENABLE + "true",
                TRUTH + "1,1,0,0,8,4,false;1,1,0,100.125,1.75,1,true;1,1,0,240,-16,0,true",
                FEEDBACK + "1,1,0,100.124,19.876,100.001;1,1,0,100.125,109.875,180",
                READ_TIMES + "0,100.124,100.125,119.999,120,200.124,200.125,209.999,210,240,280.124,280.125");

        List<Map<String, String>> states = rows(trace, TRUTH_EVENT);
        assertEquals(List.of(0L, 100_125L, 240_000L), numbers(states, "t_ns"));
        assertEquals(List.of("false", "true", "true"), values(states, "interfered"));
        assertEquals(1.75, decimal(states.get(1), "sinr_db"), 1e-12);

        List<Map<String, String>> first = feedback(trace, 1);
        assertEquals(List.of("MEASURED", "ACCEPTED"), values(first, "phase"));
        assertEquals(List.of(100_124L, 120_000L), numbers(first, "t_ns"));
        assertEquals(8.0, decimal(first.get(0), "sinr_db"), 1e-12);
        List<Map<String, String>> second = feedback(trace, 2);
        assertEquals(List.of("MEASURED", "ACCEPTED"), values(second, "phase"));
        assertEquals(List.of(100_125L, 210_000L), numbers(second, "t_ns"));
        assertEquals(1.75, decimal(second.get(0), "sinr_db"), 1e-12);

        assertUnavailable(read(trace, 1, 1, 0, 0), "UNKNOWN");
        assertUnavailable(read(trace, 1, 1, 0, 119_999), "UNKNOWN");
        assertVisible(read(trace, 1, 1, 0, 120_000), 100_124, 120_000, 200_125, 8, 4);
        assertVisible(read(trace, 1, 1, 0, 200_124), 100_124, 120_000, 200_125, 8, 4);
        assertUnavailable(read(trace, 1, 1, 0, 200_125), "EXPIRED");
        assertUnavailable(read(trace, 1, 1, 0, 209_999), "EXPIRED");
        assertVisible(read(trace, 1, 1, 0, 210_000), 100_125, 210_000, 280_125, 1.75, 1);
        assertVisible(read(trace, 1, 1, 0, 240_000), 100_125, 210_000, 280_125, 1.75, 1);
        assertVisible(read(trace, 1, 1, 0, 280_124), 100_125, 210_000, 280_125, 1.75, 1);
        assertUnavailable(read(trace, 1, 1, 0, 280_125), "EXPIRED");
    }

    @Test
    void isolatesTopologicalLinksAndMasksSecondaryChannelsForLcm(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, ENABLE + "true", "sfu.count=2", "sta.count=3", "sta.3.sfu.id=2",
                "sfu.2.wifi.ap.mode=LCM", "packet.arrival.us=0,0,0", "packet.2.sta.id=2", "packet.3.sta.id=3",
                TRUTH + "1,1,0,0,11,5,false;1,2,0,0,22,7,false;1,1,1,0,33,9,false;"
                        + "2,3,0,0,44,11,false;2,3,1,0,55,15,true",
                FEEDBACK + "1,1,0,0,10,100;1,2,0,0,10,100;1,1,1,0,10,100;"
                        + "2,3,0,0,10,100;2,3,1,0,10,100",
                READ_TIMES + "9.999,10");

        assertEquals(12, rows(trace, READ_EVENT).stream().filter(row -> number(row, "t_ns") == 10_000).count());
        assertUnavailable(read(trace, 1, 1, 0, 9_999), "UNKNOWN");
        assertVisible(read(trace, 1, 1, 0, 10_000), 0, 10_000, 100_000, 11, 5);
        assertVisible(read(trace, 1, 2, 0, 10_000), 0, 10_000, 100_000, 22, 7);
        assertVisible(read(trace, 2, 3, 0, 10_000), 0, 10_000, 100_000, 44, 11);
        assertVisible(read(trace, 1, 1, 1, 10_000), 0, 10_000, 100_000, 33, 9);
        assertUnavailable(read(trace, 1, 2, 1, 10_000), "UNKNOWN");
        assertUnavailable(read(trace, 1, 1, 2, 10_000), "UNKNOWN");
        assertUnavailable(read(trace, 2, 3, 1, 10_000), "MODE_LIMIT");
        assertUnavailable(read(trace, 2, 3, 2, 10_000), "MODE_LIMIT");
        assertTrue(rows(trace, READ_EVENT).stream().noneMatch(row -> number(row, "sfu_id") == 1
                && number(row, "sta_id") == 3));
    }

    @Test
    void rejectsStaleAndAlreadyExpiredArrivalsWithoutReplacingFreshFeedback(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, ENABLE + "true",
                TRUTH + "1,1,0,0,5,3,false;1,1,0,30,15,7,true",
                FEEDBACK + "1,1,0,10,60,200;1,1,0,30,10,100;1,1,0,80,30,30;1,1,2,0,25,100",
                READ_TIMES + "39.999,40,69.999,70,110,129.999,130");

        assertEquals(List.of("MEASURED", "STALE"), values(feedback(trace, 1), "phase"));
        assertEquals(List.of("MEASURED", "ACCEPTED"), values(feedback(trace, 2), "phase"));
        assertEquals(List.of("MEASURED", "EXPIRED"), values(feedback(trace, 3), "phase"));
        assertEquals(List.of("NO_TRUTH"), values(feedback(trace, 4), "phase"));
        assertUnavailable(read(trace, 1, 1, 0, 39_999), "UNKNOWN");
        for (long time : List.of(40_000L, 69_999L, 70_000L, 110_000L, 129_999L)) {
            assertVisible(read(trace, 1, 1, 0, time), 30_000, 40_000, 130_000, 15, 7);
        }
        assertUnavailable(read(trace, 1, 1, 0, 130_000), "EXPIRED");
        assertUnavailable(read(trace, 1, 1, 2, 130_000), "UNKNOWN");
    }

    @Test
    void observesDecodedUplinkAndReceivedAckWithoutInferringChannelQuality(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, ENABLE + "true", "traffic.direction=BOTH",
                "packet.arrival.us=0,0,0,0,500,1000", "packet.1.direction=DL", "packet.2.direction=DL",
                "packet.3.direction=DL", "packet.4.direction=DL", "packet.5.direction=DL", "packet.6.direction=UL",
                "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=4",
                "wifi.mac.ampdu.first-attempt-error.packet.ids=2,4", TRUTH + "1,1,0,0,31,11,false",
                READ_TIMES + "175,222.999,223,405.2,700,1000,1200");

        List<Map<String, String>> local = rows(trace, LOCAL_EVENT);
        assertEquals(List.of("DL_BLOCK_ACK", "DL_BLOCK_ACK", "DL_ACK", "UL_DATA"), values(local, "kind"));
        assertEquals(List.of(2L, 2L, 1L, 1L), numbers(local, "mpdus"));
        assertEquals(223_000, number(local.get(0), "t_ns"));
        assertEquals(405_200, number(local.get(1), "t_ns"));
        assertEquals("-", read(trace, 1, 1, 0, 175_000).get("local_dl_ba_ns"));
        assertEquals("-", read(trace, 1, 1, 0, 222_999).get("local_dl_ba_ns"));
        assertEquals(223_000, number(read(trace, 1, 1, 0, 223_000), "local_dl_ba_ns"));
        assertEquals(405_200, number(read(trace, 1, 1, 0, 405_200), "local_dl_ba_ns"));
        assertEquals(number(local.get(2), "t_ns"), number(read(trace, 1, 1, 0, 700_000), "local_dl_ack_ns"));
        assertEquals("-", read(trace, 1, 1, 0, 1_000_000).get("local_ul_data_ns"));
        Map<String, String> last = read(trace, 1, 1, 0, 1_200_000);
        assertEquals(number(local.get(3), "t_ns"), number(last, "local_ul_data_ns"));
        assertUnavailable(last, "UNKNOWN");
    }

    @Test
    void followsExistingManualModeChangesByTheirActualCompletionTime(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, ENABLE + "true", "packet.arrival.us=1000", "sfu.1.wifi.ap.mode=LCM",
                "wifi.ap.activity.enabled=true", "wifi.ap.energy.enabled=true", "wifi.ap.switch.enabled=true",
                "sfu.1.wifi.ap.switch.requests=100:HCM,200:LCM", "wifi.ap.switch.lcm-to-hcm.delay.us=10",
                "wifi.ap.switch.lcm-to-hcm.power.w=11.5", "wifi.ap.switch.hcm-to-lcm.delay.us=10",
                "wifi.ap.switch.hcm-to-lcm.power.w=8.75",
                TRUTH + "1,1,0,0,8,3,false;1,1,1,0,18,7,false;1,1,2,0,19,7,true;1,1,3,0,17,7,false",
                FEEDBACK + "1,1,3,15,5,500;1,1,0,15,5,500;1,1,1,15,105,500;1,1,2,15,5,30",
                READ_TIMES + "109.999,110,120,209.999,210");

        assertUnavailable(read(trace, 1, 1, 3, 109_999), "MODE_LIMIT");
        assertVisible(read(trace, 1, 1, 3, 110_000), 15_000, 20_000, 515_000, 17, 7);
        assertUnavailable(read(trace, 1, 1, 1, 110_000), "UNKNOWN");
        assertUnavailable(read(trace, 1, 1, 2, 110_000), "EXPIRED");
        assertVisible(read(trace, 1, 1, 1, 120_000), 15_000, 120_000, 515_000, 18, 7);
        assertVisible(read(trace, 1, 1, 3, 209_999), 15_000, 20_000, 515_000, 17, 7);
        assertUnavailable(read(trace, 1, 1, 3, 210_000), "MODE_LIMIT");
        for (long time : List.of(109_999L, 110_000L, 209_999L, 210_000L)) {
            assertVisible(read(trace, 1, 1, 0, time), 15_000, 20_000, 515_000, 8, 3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HCM", "LCM"})
    void preservesPartialBlockAckC1AndEnergyOutputWithObserverTailEvents(String mode, @TempDir Path directory)
            throws IOException {
        List<String> fixture = new ArrayList<>(List.of(
                "sfu.1.wifi.ap.mode=" + mode, "packet.arrival.us=0,0,0,0", "wifi.mac.ampdu.enabled=true",
                "wifi.mac.ampdu.max-mpdus=4", "wifi.mac.ampdu.first-attempt-error.packet.ids=2,4",
                "wifi.ap.activity.enabled=true", "wifi.ap.energy.enabled=true", "wifi.ap.observation.enabled=true",
                "wifi.ap.observation.period.us=50"));
        List<String> baseline = run(directory, fixture.toArray(String[]::new));
        fixture.addAll(List.of(TRUTH + "1,1,0,0,5,3,false;1,1,0,3000,-12,0,true",
                FEEDBACK + "1,1,0,3000,250,500", READ_TIMES + "0,500,3250,3500", ENABLE + "false"));
        assertEquals(baseline, run(directory, fixture.toArray(String[]::new)));
        fixture.add(ENABLE + "true");
        List<String> seen = run(directory, fixture.toArray(String[]::new));

        assertEquals(baseline, originalTrace(seen));
        assertFalse(rows(baseline, "WIFI_AP_OBSERVATION").isEmpty());
        assertTrue(baseline.stream().anyMatch(line -> line.startsWith("summary=WIFI_AP_ENERGY ")));
        assertVisible(read(seen, 1, 1, 0, 3_250_000), 3_000_000, 3_250_000, 3_500_000, -12, 0);
        assertUnavailable(read(seen, 1, 1, 0, 3_500_000), "EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wifi.channel.observation.enabled=unknown",
            "wifi.channel.truth=2,1,0,0,5,3,false",
            "wifi.channel.truth=1,1,4,0,5,3,false",
            "wifi.channel.truth=1,1,0,0,NaN,3,false",
            "wifi.channel.truth=1,1,0,0,5,16,false",
            "wifi.channel.truth=1,1,0,0,5,3,unknown",
            "wifi.channel.truth=1,1,0,0,5,3,false;1,1,0,0,6,3,true",
            "wifi.channel.feedback=1,1,0,0,2,0",
            "wifi.channel.feedback=1,1,0,2,-1,3",
            "wifi.channel.observe.us=0.0001",
            "wifi.channel.observe.us=0,0.000"
    })
    void rejectsMalformedChannelConfiguration(String property, @TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> run(directory, ENABLE + "true", property));
    }

    private static List<String> run(Path directory, String... overrides) throws IOException {
        List<String> config = new ArrayList<>(BASE);
        config.addAll(List.of(overrides));
        Path path = Files.write(directory.resolve("c2a.properties"), config, StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> feedback(List<String> trace, int reportId) {
        return rows(trace, FEEDBACK_EVENT).stream().filter(row -> number(row, "report_id") == reportId)
                .collect(Collectors.toList());
    }

    private static Map<String, String> read(List<String> trace, int sfuId, int staId, int subchannel, long timeNs) {
        List<Map<String, String>> matches = rows(trace, READ_EVENT).stream().filter(row ->
                        number(row, "sfu_id") == sfuId && number(row, "sta_id") == staId
                                && number(row, "subchannel") == subchannel && number(row, "t_ns") == timeNs)
                .collect(Collectors.toList());
        assertEquals(1, matches.size(), "AP observation for (" + sfuId + ", " + staId + ", " + subchannel
                + ") at " + timeNs + " ns");
        return matches.get(0);
    }

    private static void assertUnavailable(Map<String, String> row, String status) {
        assertEquals(status, row.get("status"));
        assertEquals("-", row.get("sinr_db"));
        assertEquals("-", row.get("cqi"));
        assertEquals("-", row.get("measured_ns"));
        assertEquals("-", row.get("arrival_ns"));
        assertEquals("-", row.get("expires_ns"));
    }

    private static void assertVisible(Map<String, String> row, long measurement, long arrival, long expiry,
                                      double sinr, int cqi) {
        assertEquals("VISIBLE", row.get("status"));
        assertEquals(measurement, number(row, "measured_ns"));
        assertEquals(arrival, number(row, "arrival_ns"));
        assertEquals(expiry, number(row, "expires_ns"));
        assertEquals(sinr, decimal(row, "sinr_db"), 1e-12);
        assertEquals(cqi, number(row, "cqi"));
        assertFalse(row.containsKey("interfered"), "Live interference truth is not exposed to the AP");
    }

    private static List<String> originalTrace(List<String> trace) {
        return trace.stream().filter(line -> C2A_EVENTS.stream().noneMatch(event -> isEvent(line, event)))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> rows(List<String> trace, String event) {
        return trace.stream().filter(line -> isEvent(line, event)).map(ChannelObservationSimulationTest::fields)
                .collect(Collectors.toList());
    }

    private static boolean isEvent(String line, String event) {
        String token = "event=" + event + " ";
        return line.startsWith(token) || line.contains(" " + token);
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

    private static List<String> values(List<Map<String, String>> rows, String field) {
        return rows.stream().map(row -> row.get(field)).collect(Collectors.toList());
    }

    private static List<Long> numbers(List<Map<String, String>> rows, String field) {
        return rows.stream().map(row -> number(row, field)).collect(Collectors.toList());
    }

    private static long number(Map<String, String> row, String field) {
        return Long.parseLong(row.get(field));
    }

    private static double decimal(Map<String, String> row, String field) {
        return Double.parseDouble(row.get(field));
    }
}
