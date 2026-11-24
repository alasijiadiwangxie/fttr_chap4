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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubchannelPerSimulationTest {
    private static final String FREQUENCY_MODEL = "wifi.phy.per.frequency.model=";
    private static final String WEIGHTS = "wifi.phy.per.subchannel.weights=";
    private static final String TRUTH = "wifi.channel.truth=";
    private static final String DECODE = "WIFI_PHY_MPDU_DECODE";

    private static final List<String> BASE = List.of(
            "traffic.mode=manual", "traffic.direction=DL", "packet.arrival.us=0", "packet.bytes=1500",
            "fiber.delay.us=0", "wifi.model=he-su", "wifi.mac.access=edca-multi-ac",
            "wifi.mac.exchange=data-sifs-ack", "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0",
            "wifi.mac.ack.rate.mbps=24", "wifi.mac.ack.timeout.us=48", "wifi.mac.retry.max=0",
            "wifi.phy.per.enabled=true", "wifi.phy.per.seed=101", "wifi.phy.per.source=SYNTHETIC_TEST",
            "wifi.phy.per.reference.mpdu.bytes=1530", "wifi.phy.per.curves=7,-12,1;7,10,0.5;7,30,0",
            "wifi.ap.observation.enabled=true", "wifi.ap.observation.period.us=25",
            "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=1500",
            "wifi.ap.energy.enabled=true");

    @ParameterizedTest
    @ValueSource(strings = {"LCM", "HCM"})
    void matchesC2b1ExactlyForFlatSubchannels(String mode, @TempDir Path directory) throws IOException {
        String[] settings = {
                TRUTH + changes("0", 10, 10, 10, 10) + ";" + changes("250", 30, 30, 30, 30),
                "packet.arrival.us=0,0,0,0", "packet.1.bytes=201", "packet.3.bytes=1001",
                "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=4", "wifi.mac.retry.max=1"
        };
        List<String> old = run(directory, mode, "flat", settings);
        List<String> blocks = run(directory, mode, "subchannel-blocks", settings);
        List<Map<String, String>> oldDecode = rows(old, DECODE);
        List<Map<String, String>> blockDecode = rows(blocks, DECODE);
        assertTrue(oldDecode.size() >= 4);
        assertEquals(oldDecode.size(), blockDecode.size());
        for (int i = 0; i < oldDecode.size(); i++) {
            Map<String, String> current = blockDecode.get(i);
            oldDecode.get(i).forEach((field, expected) -> assertEquals(expected, current.get(field), field));
            assertEquals(mode.equals("LCM") ? "0" : "0,1,2,3", current.get("subchannels"));
        }
        assertEquals(protocol(old), protocol(blocks));
    }

    @Test
    void usesOnlyPrimaryInLcmAndAccountsForWeakSecondaryInHcm(@TempDir Path directory) throws IOException {
        String full = TRUTH + changes("0", 30, 30, 30, 10);
        List<String> lcm = run(directory, "LCM", "subchannel-blocks", full);
        assertEquals(lcm, run(directory, "LCM", "subchannel-blocks", TRUTH + changes("0", 30)));
        Map<String, String> lcmDecode = only(lcm, DECODE);
        assertEquals("0", lcmDecode.get("subchannels"));
        assertEquals("30.0", lcmDecode.get("subchannel_sinr_db"));
        assertEquals(0.0, decimal(lcmDecode, "per"));
        assertEquals("DECODED", lcmDecode.get("outcome"));
        assertEquals(List.of(1L), packetIds(lcm, "DELIVERED"));

        List<String> hcmStrong = run(directory, "HCM", "subchannel-blocks", TRUTH + changes("0", 30, 30, 30, 30));
        assertEquals(0.0, decimal(only(hcmStrong, DECODE), "per"));
        assertEquals(List.of(1L), packetIds(hcmStrong, "DELIVERED"));
        List<String> hcmWeak = run(directory, "HCM", "subchannel-blocks", full);
        Map<String, String> hcmDecode = only(hcmWeak, DECODE);
        assertEquals("0,1,2,3", hcmDecode.get("subchannels"));
        assertEquals("30.0,30.0,30.0,10.0", hcmDecode.get("subchannel_sinr_db"));
        assertEquals("-", hcmDecode.get("sinr_db"));
        assertEquals(1.0 - Math.pow(0.5, 0.25), decimal(hcmDecode, "per"), 1e-14);
        assertEquals(lcmDecode.get("draw"), hcmDecode.get("draw"));
        assertEquals("SINR_ERROR", hcmDecode.get("outcome"));
        assertEquals(List.of(1L), packetIds(hcmWeak, "WIFI_RETRY_LIMIT_DROP"));
        assertEnergy(hcmWeak, 77_600, 0);
    }

    @Test
    void samplesTheWeakPrimaryWithTheBandwidthActuallyUsed(@TempDir Path directory) throws IOException {
        String truth = TRUTH + changes("0", 10, 30, 30, 30);
        String seed = "wifi.phy.per.seed=77";
        List<String> lcm = run(directory, "LCM", "subchannel-blocks", truth, seed);
        List<String> hcm = run(directory, "HCM", "subchannel-blocks", truth, seed);
        Map<String, String> lcmDecode = only(lcm, DECODE);
        Map<String, String> hcmDecode = only(hcm, DECODE);
        assertEquals("10.0", lcmDecode.get("subchannel_sinr_db"));
        assertEquals("10.0,30.0,30.0,30.0", hcmDecode.get("subchannel_sinr_db"));
        assertEquals(0.5, decimal(lcmDecode, "per"));
        assertEquals(1.0 - Math.pow(0.5, 0.25), decimal(hcmDecode, "per"), 1e-14);
        assertEquals(lcmDecode.get("draw"), hcmDecode.get("draw"));
        assertEquals("SINR_ERROR", lcmDecode.get("outcome"));
        assertEquals("DECODED", hcmDecode.get("outcome"));
        assertEquals(List.of(1L), packetIds(lcm, "WIFI_RETRY_LIMIT_DROP"));
        assertEquals(List.of(1L), packetIds(hcm, "DELIVERED"));
        assertEnergy(hcm, 77_600, 28_000);
    }

    @Test
    void normalizesConfiguredWeightsOverTheStasActualBandwidth(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "HCM", "subchannel-blocks", TRUTH + changes("0", 30, 10),
                "sta.1.wifi.max.bandwidth.mhz=40", WEIGHTS + "1,3,100,100");
        Map<String, String> decode = only(trace, DECODE);
        assertEquals("0,1", decode.get("subchannels"));
        assertEquals("30.0,10.0", decode.get("subchannel_sinr_db"));
        assertEquals("0.25,0.75", decode.get("block_weights"));
        assertEquals(1.0 - Math.pow(0.5, 0.75), decimal(decode, "per"), 1e-14);
        assertEquals("SINR_ERROR", decode.get("outcome"));
    }

    @Test
    void rejectsMissingPrimaryOrActiveSecondaryTruth(@TempDir Path directory) {
        IllegalArgumentException lcm = assertThrows(IllegalArgumentException.class,
                () -> run(directory, "LCM", "subchannel-blocks", TRUTH + "1,1,1,0,30,-1,false"));
        assertTrue(lcm.getMessage().contains("subchannel 0"), lcm.getMessage());
        IllegalArgumentException hcm = assertThrows(IllegalArgumentException.class,
                () -> run(directory, "HCM", "subchannel-blocks", TRUTH + changes("0", 30, 30, 30)));
        assertTrue(hcm.getMessage().contains("subchannel 3"), hcm.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,1,1", "1,1,1,0", "1,1,1,NaN", "1,1,1,-1"})
    void rejectsMalformedWeights(String weights, @TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> run(directory, "HCM", "subchannel-blocks",
                TRUTH + changes("0", 30, 30, 30, 30), WEIGHTS + weights));
    }

    @Test
    void freezesTheSubchannelVectorAndConservesPartialBaRetriesAndEnergy(@TempDir Path directory)
            throws IOException {
        String truth = TRUTH + String.join(";", changes("0", 30, 30, 30, 30), changes("43", 10, 10, 10, 9.5),
                changes("43.001", -12, -12, -12, -12), changes("220", 30, 30, 30, 30));
        String[] settings = {truth, "packet.arrival.us=0,0,0,0", "wifi.phy.per.seed=77", "wifi.mac.retry.max=1",
                "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=4",
                "wifi.channel.observation.enabled=true", "wifi.channel.feedback=1,1,0,0,20,1000",
                "wifi.channel.observe.us=43"};
        List<String> trace = run(directory, "HCM", "subchannel-blocks", settings);
        assertEquals(trace, run(directory, "HCM", "subchannel-blocks", settings));
        List<Map<String, String>> decode = rows(trace, DECODE);
        assertEquals(6, decode.size());
        List<Map<String, String>> firstAttempt = decode.subList(0, 4);
        assertTrue(firstAttempt.stream().allMatch(row -> number(row, "ppdu_start_ns") == 43_000L
                && "10.0,10.0,10.0,9.5".equals(row.get("subchannel_sinr_db"))));
        assertEquals(List.of("SINR_ERROR", "DECODED", "DECODED", "SINR_ERROR"), firstAttempt.stream()
                .map(row -> row.get("outcome")).collect(Collectors.toList()));
        assertEquals(List.of(1L, 4L), decode.subList(4, 6).stream().map(row -> number(row, "packet_id"))
                .collect(Collectors.toList()));
        assertTrue(decode.subList(4, 6).stream().allMatch(row -> number(row, "attempt") == 1L
                && "30.0,30.0,30.0,30.0".equals(row.get("subchannel_sinr_db")) && decimal(row, "per") == 0.0));
        assertEquals(List.of("6", "3"), rows(trace, "WIFI_AMPDU_BA_END").stream()
                .map(row -> row.get("bitmap")).collect(Collectors.toList()));
        assertEquals(List.of(1L, 4L), packetIds(trace, "WIFI_MAC_RETRY"));
        assertEquals(List.of(2L, 3L, 1L, 4L), packetIds(trace, "DELIVERED"));
        Map<String, String> observed = rows(trace, "WIFI_CHANNEL_OBSERVATION").stream()
                .filter(row -> number(row, "subchannel") == 0).findFirst().orElseThrow();
        assertEquals(30.0, decimal(observed, "sinr_db"));
        assertEquals(4, countDl(trace, "arrival_packets"));
        assertEquals(4, countDl(trace, "delivered_packets"));
        assertEquals(4, countDl(trace, "confirmed_packets"));
        assertEquals(2, countDl(trace, "retry_attempt_mpdus"));
        assertEquals(2, countDl(trace, "requeued_packets"));
        assertEquals(0, countDl(trace, "dropped_packets"));
        assertEnergy(trace, 223_200, 64_000);
    }

    @Test
    void doesNotSampleTheSubchannelsForAnExistingMacCollision(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "HCM", "subchannel-blocks", "traffic.direction=BOTH",
                "packet.arrival.us=0,0", "packet.1.direction=DL", "packet.2.direction=UL",
                "packet.1.type=VO", "packet.2.type=VO", "wifi.mac.edca.VO.cwmin=0");
        Map<String, String> decode = only(trace, DECODE);
        assertEquals("MAC_COLLISION", decode.get("outcome"));
        assertEquals("0,1,2,3", decode.get("subchannels"));
        for (String field : List.of("subchannel_sinr_db", "block_weights", "per", "draw")) {
            assertEquals("-", decode.get(field), field);
        }
        assertEquals(Set.of(1L, 2L), Set.copyOf(packetIds(trace, "WIFI_COLLISION_START")));
        assertEquals(Set.of(1L, 2L), Set.copyOf(packetIds(trace, "WIFI_RETRY_LIMIT_DROP")));
    }

    private static List<String> run(Path directory, String apMode, String frequencyModel, String... settings)
            throws IOException {
        List<String> config = new ArrayList<>(BASE);
        config.add("sfu.1.wifi.ap.mode=" + apMode);
        config.add(FREQUENCY_MODEL + frequencyModel);
        config.addAll(List.of(settings));
        Path path = Files.write(directory.resolve("c2b2a.properties"), config, StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static String changes(String timeUs, double... sinrs) {
        List<String> changes = new ArrayList<>();
        for (int channel = 0; channel < sinrs.length; channel++) {
            changes.add("1,1," + channel + "," + timeUs + "," + sinrs[channel] + ",-1,false");
        }
        return String.join(";", changes);
    }

    private static List<String> protocol(List<String> trace) {
        return trace.stream().filter(line -> !DECODE.equals(fields(line).get("event")))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> rows(List<String> trace, String event) {
        return trace.stream().map(SubchannelPerSimulationTest::fields).filter(row -> event.equals(row.get("event")))
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

    private static long countDl(List<String> trace, String field) {
        return rows(trace, "WIFI_AP_OBSERVATION").stream()
                .filter(row -> "DL".equals(row.get("direction")) && "BE".equals(row.get("ac")))
                .mapToLong(row -> number(row, field)).sum();
    }

    private static void assertEnergy(List<String> trace, long txNs, long rxNs) {
        Map<String, String> activity = summary(trace, "WIFI_AP_ACTIVITY");
        long idleNs = number(activity, "idle_ns");
        assertEquals(txNs, number(activity, "tx_ns"));
        assertEquals(rxNs, number(activity, "rx_ns"));
        assertEquals(1_500_000L, idleNs + txNs + rxNs);
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
        return trace.stream().map(SubchannelPerSimulationTest::fields).filter(row -> name.equals(row.get("summary")))
                .findFirst().orElseThrow();
    }

    private static long number(Map<String, String> row, String field) {
        return Long.parseLong(row.get(field));
    }

    private static double decimal(Map<String, String> row, String field) {
        return Double.parseDouble(row.get(field));
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            int separator = token.indexOf('=');
            if (separator >= 0) {
                result.put(token.substring(0, separator), token.substring(separator + 1));
            }
        }
        return result;
    }
}
