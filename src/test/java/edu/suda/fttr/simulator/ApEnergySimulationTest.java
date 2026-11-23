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
import java.math.BigDecimal;
import java.math.MathContext;
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

class ApEnergySimulationTest {
    private static final String ACTIVITY_ENABLED = "wifi.ap.activity.enabled=true";
    private static final String ENERGY_ENABLED = "wifi.ap.energy.enabled=true";
    private static final String ENERGY = "WIFI_AP_ENERGY";
    private static final String NETWORK = "WIFI_AP_ENERGY_NETWORK";
    private static final List<String> STATES = List.of("idle", "tx", "rx");
    private static final List<BigDecimal> LCM_POWER = decimals("7.38", "9.20", "8.20");
    private static final List<BigDecimal> HCM_POWER = decimals("7.62", "10.50", "8.70");

    @ParameterizedTest
    @CsvSource({
            "lcm-natural, LCM, 505200, 870.84, 3047.04, 459.2, 4377.08",
            "hcm-natural, HCM, 329200, 899.16, 1629.6, 487.2, 3015.96",
            "lcm-1000us, LCM, 1000000, 4522.464, 3047.04, 459.2, 8028.704",
            "hcm-1000us, HCM, 1000000, 6010.656, 1629.6, 487.2, 8127.456"
    })
    void integratesBothB2ModesAndWindowsWithoutChangingTheOriginalTrace(String fixture, String mode, long windowNs,
            String idle, String tx, String rx, String total, @TempDir Path directory) throws IOException {
        Path original = b2Config(fixture);
        List<String> result = run(scenario(directory, Files.readAllLines(original), ENERGY_ENABLED));
        List<BigDecimal> expected = decimals(idle, tx, rx);

        assertAp(result, "1", mode, windowNs, mode.equals("LCM") ? LCM_POWER : HCM_POWER, expected);
        assertNetwork(result, windowNs, "1", "0", expected);
        assertDecimal(new BigDecimal(total), summaries(result, ENERGY).get(0).get("total_energy_uj"));
        assertEquals(run(original), withoutEnergy(result));
    }

    @Test
    void clipsDataAtANanosecondBoundaryAndStillDeliversThePackets(@TempDir Path directory) throws IOException {
        List<String> base = Files.readAllLines(b2Config("hcm-natural"));
        List<String> result = run(scenario(directory, base, ENERGY_ENABLED, "wifi.ap.activity.window.us=50.125"));

        // 34 μs IDLE, 16.125 μs TX, no RX in the observation window.
        List<BigDecimal> expected = decimals("259.08", "169.3125", "0");
        assertAp(result, "1", "HCM", 50_125, HCM_POWER, expected);
        assertNetwork(result, 50_125, "1", "0", expected);
        assertEquals(2, countEvents(result, "DELIVERED"));
    }

    @Test
    void usesConfiguredDecimalsForAllThreeStates(@TempDir Path directory) throws IOException {
        List<String> result = run(scenario(directory, Files.readAllLines(b2Config("lcm-natural")), ENERGY_ENABLED,
                "wifi.ap.energy.lcm.idle.w=1.2345", "wifi.ap.energy.lcm.tx.w=2.3456",
                "wifi.ap.energy.lcm.rx.w=3.4567"));

        assertAp(result, "1", "LCM", 505_200, decimals("1.2345", "2.3456", "3.4567"),
                decimals("145.671", "776.86272", "193.5752"));
    }

    @ParameterizedTest
    @CsvSource({"false, 0, 0", "true, 1000000, 7620"})
    void zeroTrafficUsesTheObservedIdleTimeAndHandlesAnEmptyWindow(boolean fixed, long windowNs, String idle,
            @TempDir Path directory) throws IOException {
        List<String> settings = new ArrayList<>(List.of(ENERGY_ENABLED, ACTIVITY_ENABLED, "traffic.mode=poisson",
                "traffic.direction=DL", "traffic.duration.us=1000", "traffic.sfu.1.rate.pps=0",
                "fiber.delay.us=0", "sfu.1.wifi.ap.mode=HCM"));
        if (fixed) {
            settings.add("wifi.ap.activity.window.us=1000");
        }
        List<String> result = run(scenario(directory, settings));
        List<BigDecimal> expected = decimals(idle, "0", "0");

        assertAp(result, "1", "HCM", windowNs, HCM_POWER, expected);
        assertNetwork(result, windowNs, "1", "0", expected);
        assertEquals(0, countEvents(result, "WIFI_AP_ACTIVITY_CHANGE"));
        assertEquals(0, countEvents(result, "DELIVERED"));
    }

    @Test
    void sumsIndependentUplinkAndDownlinkApsOverOneWindowWithoutAssumingLegacyPower(@TempDir Path directory)
            throws IOException {
        List<String> result = run(scenario(directory, Files.readAllLines(b2Config("mixed")), ENERGY_ENABLED,
                "sfu.count=3"));

        assertAp(result, "1", "LCM", 1_000_000, LCM_POWER, decimals("5951.232", "1523.52", "229.6"));
        assertAp(result, "2", "HCM", 1_000_000, HCM_POWER, decimals("6815.328", "294", "675.12"));
        assertEquals(2, summaries(result, ENERGY).size());
        assertEquals("LEGACY", perSfu(result, "WIFI_AP_ACTIVITY", "3").get("ap_mode"));
        assertNetwork(result, 1_000_000, "2", "1", decimals("12766.56", "1817.52", "904.72"));
        assertEquals(2, countEvents(result, "DELIVERED"));
    }

    @Test
    void leavesAnAllLegacyRunUnmodeled(@TempDir Path directory) throws IOException {
        List<String> result = run(scenario(directory, List.of(ACTIVITY_ENABLED, ENERGY_ENABLED, "fiber.delay.us=0",
                "wifi.model=fixed", "wifi.delay.us=10", "packet.arrival.us=0", "packet.bytes=1200")));

        assertTrue(summaries(result, ENERGY).isEmpty());
        assertNetwork(result, 10_000, "0", "1", decimals("0", "0", "0"));
        assertEquals(1, countEvents(result, "DELIVERED"));
    }

    @Test
    void integratesCollidedAndRetriedFramesWithoutDoubleChargingTheirOverlap(@TempDir Path directory)
            throws IOException {
        List<String> base = List.of(ACTIVITY_ENABLED, "fiber.delay.us=0", "packet.bytes=1200", "packet.arrival.us=0,0",
                "traffic.direction=UL", "sta.count=2", "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2",
                "sfu.1.wifi.ap.mode=HCM", "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.seed=1");
        List<String> baseline = run(scenario(directory, base));
        List<String> result = run(scenario(directory, base, ENERGY_ENABLED));

        // B2 supplies 196 μs IDLE, 56 μs ACK TX, and 232.8 μs DATA RX including one overlapped collision.
        assertAp(result, "1", "HCM", 484_800, HCM_POWER, decimals("1493.52", "588", "2025.36"));
        assertEquals(2, countEvents(result, "WIFI_MAC_RETRY"));
        assertEquals(2, countEvents(result, "WIFI_ACK_TIMEOUT"));
        assertEquals(baseline, withoutEnergy(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "NaN", "Infinity", "invalid", ""})
    void rejectsAnInvalidDevicePower(String value, @TempDir Path directory) throws IOException {
        List<String> base = Files.readAllLines(b2Config("lcm-natural"));
        Path config = scenario(directory, base, ENERGY_ENABLED, "wifi.ap.energy.lcm.idle.w=" + value);

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }

    @Test
    void requiresB2WhenEnergyAccountingIsEnabled(@TempDir Path directory) throws IOException {
        Path config = scenario(directory, Files.readAllLines(b2Config("lcm-natural")), ENERGY_ENABLED,
                "wifi.ap.activity.enabled=false");

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }

    private static void assertAp(List<String> trace, String sfu, String mode, long windowNs, List<BigDecimal> powers,
            List<BigDecimal> energies) {
        Map<String, String> row = perSfu(trace, ENERGY, sfu);
        Map<String, String> activity = perSfu(trace, "WIFI_AP_ACTIVITY", sfu);
        assertEquals(mode, row.get("ap_mode"));
        long summedDuration = 0;
        for (int index = 0; index < STATES.size(); index++) {
            String state = STATES.get(index);
            long durationNs = Long.parseLong(activity.get(state + "_ns"));
            summedDuration += durationNs;
            assertDecimal(powers.get(index), row.get(state + "_power_w"));
            assertDecimal(powers.get(index).multiply(BigDecimal.valueOf(durationNs, 3)),
                    row.get(state + "_energy_uj"));
        }
        assertEquals(windowNs, summedDuration);
        assertSummary(row, windowNs, energies);
    }

    private static void assertNetwork(List<String> trace, long windowNs, String modeled, String legacy,
            List<BigDecimal> energies) {
        List<Map<String, String>> network = summaries(trace, NETWORK);
        assertEquals(1, network.size());
        assertEquals(modeled, network.get(0).get("modeled_ap_count"));
        assertEquals(legacy, network.get(0).get("legacy_ap_count"));
        assertSummary(network.get(0), windowNs, energies);
    }

    private static void assertSummary(Map<String, String> row, long windowNs, List<BigDecimal> energies) {
        assertEquals("RESEARCH_INITIAL_V1", row.get("model"));
        assertEquals("0", row.get("observation_start_ns"));
        assertEquals(Long.toString(windowNs), row.get("observation_end_ns"));
        for (int index = 0; index < STATES.size(); index++) {
            assertDecimal(energies.get(index), row.get(STATES.get(index) + "_energy_uj"));
        }
        BigDecimal total = energies.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertDecimal(total, row.get("total_energy_uj"));
        BigDecimal average = windowNs == 0 ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(windowNs, 3), MathContext.DECIMAL128);
        assertTrue(average.subtract(new BigDecimal(row.get("avg_power_w"))).abs()
                .compareTo(new BigDecimal("0.000000001")) <= 0, () -> "Unexpected average power: " + row);
    }

    private static void assertDecimal(BigDecimal expected, String actual) {
        assertEquals(0, expected.compareTo(new BigDecimal(actual)), () -> "Expected " + expected + ", got " + actual);
    }

    private static List<BigDecimal> decimals(String... values) {
        return List.of(values).stream().map(BigDecimal::new).collect(Collectors.toList());
    }

    private static Path b2Config(String fixture) {
        return Path.of("configs", "b2-ap-activity-" + fixture + ".properties");
    }

    private static Path scenario(Path directory, List<String> base, String... additions) throws IOException {
        List<String> properties = new ArrayList<>(base);
        properties.addAll(List.of(additions));
        return Files.write(directory.resolve("ap-energy.properties"), properties, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path config) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(config), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static long countEvents(List<String> trace, String event) {
        return trace.stream().filter(line -> line.contains(" event=" + event + " ")).count();
    }

    private static List<String> withoutEnergy(List<String> trace) {
        return trace.stream().filter(line -> !line.startsWith("summary=" + ENERGY + " ")
                && !line.startsWith("summary=" + NETWORK + " ")).collect(Collectors.toList());
    }

    private static Map<String, String> perSfu(List<String> trace, String kind, String sfu) {
        List<Map<String, String>> matches = summaries(trace, kind).stream()
                .filter(row -> sfu.equals(row.get("sfu_id"))).collect(Collectors.toList());
        assertEquals(1, matches.size(), "expected one " + kind + " for SFU " + sfu);
        return matches.get(0);
    }

    private static List<Map<String, String>> summaries(List<String> trace, String kind) {
        return trace.stream().filter(line -> line.startsWith("summary=" + kind + " "))
                .map(line -> {
                    Map<String, String> fields = new HashMap<>();
                    for (String token : line.split(" ")) {
                        String[] parts = token.split("=", 2);
                        fields.put(parts[0], parts[1]);
                    }
                    return fields;
                }).collect(Collectors.toList());
    }
}
