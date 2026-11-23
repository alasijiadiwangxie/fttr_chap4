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

class ApModeSwitchSimulationTest {
    private static final String ON = "wifi.ap.switch.enabled=true";
    private static final String PREFIX = "WIFI_AP_SWITCH_";
    private static final String NETWORK_ENERGY = "WIFI_AP_ENERGY_NETWORK";
    private static final String OBSERVATION_WINDOW = "wifi.ap.activity.window.us=";
    private static final List<String> BASE = List.of("wifi.ap.activity.enabled=true", "wifi.ap.energy.enabled=true",
            "fiber.delay.us=0", "packet.bytes=1200", "wifi.model=he-su", "wifi.mac.exchange=data-sifs-ack",
            "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0");
    private static final List<String> SWITCH_PROFILES = List.of(
            "wifi.ap.switch.lcm-to-hcm.delay.us=25.125", "wifi.ap.switch.lcm-to-hcm.power.w=12.5",
            "wifi.ap.switch.hcm-to-lcm.delay.us=60.75", "wifi.ap.switch.hcm-to-lcm.power.w=7.25");

    @Test
    void defersBothDirectionsUntilAckEndsAndUsesEachNewPhy(@TempDir Path directory) throws IOException {
        List<String> trace = run(scenario(directory, BASE, true,
                "packet.arrival.us=0,180,400", "packet.1.type=VO", "packet.2.type=VO", "packet.3.type=VO",
                "sfu.1.wifi.ap.mode=LCM", "sfu.1.wifi.ap.switch.requests=80:HCM,420:LCM",
                OBSERVATION_WINDOW + "800"));

        // 前一次切换请求落在 DATA 内，后一次落在 ACK 内。两次均在 ACK 完成后占用无线资源。
        assertEquals(List.of(80_000L, 420_000L), eventTimes(trace, PREFIX + "REQUEST", 1));
        assertSwitch(trace, 1, 0, "LCM", "HCM", 243_600, 268_725, 25_125, "12.5", "314.0625");
        assertSwitch(trace, 1, 1, "HCM", "LCM", 424_325, 485_075, 60_750, "7.25", "440.4375");
        assertEquals(List.of(243_600L, 424_325L, 728_675L), eventTimes(trace, "WIFI_ACK_END", 1));

        assertData(trace, 1, 1, 34_000, 165_600);
        assertData(trace, 1, 2, 302_725, 77_600);
        assertData(trace, 1, 3, 519_075, 165_600);
        assertNoRadioDuringSwitch(trace, 1);
        assertEquals(3, events(trace, "DELIVERED").size());
        assertAccounting(trace, 1, 800_000, 221_325, 408_800, 84_000, 85_875,
                "1645.3785", "3861.84", "702.8", "754.5", "6964.5185");
        assertModeTime(trace, 1, "LCM", 171_325, 331_200, 56_000);
        assertModeTime(trace, 1, "HCM", 50_000, 77_600, 28_000);
        List<String> switchStates = events(trace, "WIFI_AP_ACTIVITY_CHANGE", 1).stream()
                .filter(row -> row.get("reason").startsWith("SWITCH_"))
                .map(row -> ticks(row) + ":" + row.get("ap_mode") + ":" + row.get("from") + ">" + row.get("to"))
                .collect(Collectors.toList());
        assertEquals(List.of("243600:LCM:IDLE>SWITCH", "268725:HCM:SWITCH>IDLE",
                "424325:HCM:IDLE>SWITCH", "485075:LCM:SWITCH>IDLE"), switchStates);
    }

    @Test
    void sameTickRequestFreezesTheRemainingBackoffBeforeGrant(@TempDir Path directory) throws IOException {
        List<String> original = Files.readAllLines(Path.of("configs", "b2-ap-activity-lcm-natural.properties"));
        List<String> trace = run(scenario(directory, original, true, "wifi.ap.energy.enabled=true",
                "sfu.1.wifi.ap.switch.requests=295.6:HCM"));

        // B1 第二包抽到 2 槽。切换请求与第二槽结束同刻，优先冻结并保留一个未完成退避槽。
        assertSwitch(trace, 1, 0, "LCM", "HCM", 295_600, 320_725, 25_125, "12.5", "314.0625");
        assertData(trace, 1, 2, 363_725, 77_600); // 320.725 + 34 μs AIFS + 9 μs 退避
        assertNoRadioDuringSwitch(trace, 1);
    }

    @Test
    void defersARequestDuringTheInterFrameSifsUntilTheWholeTxopEnds(@TempDir Path directory) throws IOException {
        List<String> trace = run(scenario(directory, BASE, true,
                "packet.arrival.us=0,0,180", "packet.1.type=VO", "packet.2.type=VO", "packet.3.type=VO",
                "sfu.1.wifi.ap.mode=HCM", "sfu.1.wifi.ap.switch.requests=160:LCM",
                "wifi.mac.edca.VO.txop.limit.us=260"));

        assertData(trace, 1, 1, 34_000, 77_600);
        assertData(trace, 1, 2, 171_600, 77_600);
        assertSwitch(trace, 1, 0, "HCM", "LCM", 293_200, 353_950, 60_750, "7.25", "440.4375");
        assertEquals(293_200L, eventTimes(trace, "WIFI_TXOP_END", 1).get(0));
        assertData(trace, 1, 3, 387_950, 165_600);
        assertNoRadioDuringSwitch(trace, 1);
    }

    @Test
    void defersSwitchUntilPendingCollisionAckTimeoutsAreProcessed(@TempDir Path directory) throws IOException {
        List<String> trace = run(scenario(directory, BASE, true,
                "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2",
                "traffic.direction=UL", "sta.count=2", "sfu.1.wifi.ap.mode=HCM",
                "sfu.1.wifi.ap.switch.requests=120:LCM", "wifi.mac.retry.max=0"));

        assertEquals(List.of(111_600L, 111_600L), eventTimes(trace, "WIFI_COLLISION_DATA_END", 1));
        assertEquals(List.of(164_600L, 164_600L), eventTimes(trace, "WIFI_ACK_TIMEOUT", 1));
        assertSwitch(trace, 1, 0, "HCM", "LCM", 164_600, 225_350, 60_750, "7.25", "440.4375");
        assertTrue(events(trace, "WIFI_ACK_START").isEmpty());
        assertTrue(events(trace, "DELIVERED").isEmpty());
        assertAccounting(trace, 1, 225_350, 87_000, 0, 77_600, 60_750,
                "662.94", "0", "675.12", "440.4375", "1778.4975");
    }

    @Test
    void switchesTwoApsIndependentlyAndSumsTheirEnergy(@TempDir Path directory) throws IOException {
        List<String> trace = run(scenario(directory, BASE, true,
                "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2",
                "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "sfu.1.wifi.ap.mode=LCM", "sfu.2.wifi.ap.mode=HCM",
                "sfu.1.wifi.ap.switch.requests=0:HCM", "sfu.2.wifi.ap.switch.requests=0:LCM",
                OBSERVATION_WINDOW + "400"));

        assertSwitch(trace, 1, 0, "LCM", "HCM", 0, 25_125, 25_125, "12.5", "314.0625");
        assertSwitch(trace, 2, 0, "HCM", "LCM", 0, 60_750, 60_750, "7.25", "440.4375");
        assertData(trace, 1, 1, 59_125, 77_600); // 此时 SFU 2 仍在切换。
        assertData(trace, 2, 2, 94_750, 165_600);
        assertNoRadioDuringSwitch(trace, 1);
        assertNoRadioDuringSwitch(trace, 2);

        Map<String, String> network = summary(trace, NETWORK_ENERGY, null);
        assertEquals("2", network.get("modeled_ap_count"));
        assertDecimal("754.5", network.get("switch_energy_uj"));
        for (String component : List.of("idle", "tx", "rx", "switch", "total")) {
            String key = component + "_energy_uj";
            BigDecimal first = new BigDecimal(summary(trace, "WIFI_AP_ENERGY", 1).get(key));
            BigDecimal second = new BigDecimal(summary(trace, "WIFI_AP_ENERGY", 2).get(key));
            assertDecimal(first.add(second).toPlainString(), network.get(key));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "19.999, 19999, 0, 152.39238, 0, 152.39238",
            "20, 20000, 0, 152.4, 0, 152.4",
            "30.125, 20000, 10125, 152.4, 73.40625, 225.80625",
            "80.75, 20000, 60750, 152.4, 440.4375, 592.8375",
            "100, 39250, 60750, 294.465, 440.4375, 734.9025"
    })
    void clipsTheSwitchAtTheWindowAndStillCompletesTheModeChange(String windowUs, long idleNs, long switchNs,
            String idleEnergy, String switchEnergy, String total, @TempDir Path directory) throws IOException {
        List<String> trace = run(scenario(directory, List.of("wifi.ap.activity.enabled=true",
                "wifi.ap.energy.enabled=true", "fiber.delay.us=0", "wifi.model=he-su", "traffic.mode=poisson",
                "traffic.direction=DL", "traffic.duration.us=1000", "traffic.sfu.1.rate.pps=0"), true,
                "sfu.1.wifi.ap.mode=HCM", "sfu.1.wifi.ap.switch.requests=20:LCM", OBSERVATION_WINDOW + windowUs));

        assertSwitch(trace, 1, 0, "HCM", "LCM", 20_000, 80_750, switchNs, "7.25", switchEnergy);
        assertAccounting(trace, 1, new BigDecimal(windowUs).movePointRight(3).longValueExact(),
                idleNs, 0, 0, switchNs, idleEnergy, "0", "0", switchEnergy, total);
        assertTrue(events(trace, "WIFI_TX_START").isEmpty());
        assertTrue(events(trace, "WIFI_ACK_START").isEmpty());
    }

    @Test
    void ordersRequestsAtTheSameTickAndSkipsOnlyTheRedundantTarget(@TempDir Path directory) throws IOException {
        List<String> trace = run(scenario(directory, BASE, true, "packet.arrival.us=0", "packet.1.type=VO",
                "sfu.1.wifi.ap.mode=LCM", "sfu.1.wifi.ap.switch.requests=0:HCM,0:HCM,0:LCM"));

        assertEquals(List.of(0L, 0L, 0L), eventTimes(trace, PREFIX + "REQUEST", 1));
        assertEquals(List.of(25_125L), eventTimes(trace, PREFIX + "SKIPPED", 1));
        assertSwitch(trace, 1, 0, "LCM", "HCM", 0, 25_125, 25_125, "12.5", "314.0625");
        assertSwitch(trace, 1, 1, "HCM", "LCM", 25_125, 85_875, 60_750, "7.25", "440.4375");
        assertData(trace, 1, 1, 119_875, 165_600);
        assertNoRadioDuringSwitch(trace, 1);
    }

    @Test
    void disabledSwitchingPreservesEveryOriginalProtocolAndEnergyLine(@TempDir Path directory) throws IOException {
        List<String> base = Files.readAllLines(Path.of("configs", "b3-ap-energy-lcm-1000us.properties"));
        List<String> baseline = run(scenario(directory, base, false));
        assertEquals(baseline, run(scenario(directory, base, true, "wifi.ap.switch.enabled=false",
                "sfu.1.wifi.ap.switch.requests=0:HCM")));
    }

    @Test
    void enabledWithNoRequestsHasZeroSwitchTimeAndEnergy(@TempDir Path directory) throws IOException {
        List<String> base = Files.readAllLines(Path.of("configs", "b3-ap-energy-lcm-1000us.properties"));
        List<String> trace = run(scenario(directory, base, false, ON));

        assertTrue(trace.stream().noneMatch(line -> line.contains(" event=" + PREFIX)));
        assertAccounting(trace, 1, 1_000_000, 612_800, 331_200, 56_000, 0,
                "4522.464", "3047.04", "459.2", "0", "8028.704");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1:LEGACY", "10:HCM,9:LCM", "-0.001:HCM", "0.0001:HCM", "1.5HCM"})
    void rejectsMalformedOrUnsortedRequests(String requests, @TempDir Path directory) throws IOException {
        Path config = scenario(directory, BASE, true, "packet.arrival.us=0", "sfu.1.wifi.ap.mode=LCM",
                "sfu.1.wifi.ap.switch.requests=" + requests);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wifi.ap.switch.lcm-to-hcm.delay.us=0",
            "wifi.ap.switch.hcm-to-lcm.delay.us=0.0001", "wifi.ap.switch.lcm-to-hcm.power.w=NaN",
            "wifi.ap.switch.hcm-to-lcm.power.w=-0.1", "wifi.ap.energy.enabled=false"})
    void rejectsInvalidSwitchProfilesAndMissingAccounting(String property, @TempDir Path directory)
            throws IOException {
        Path config = scenario(directory, BASE, true, "packet.arrival.us=0", "sfu.1.wifi.ap.mode=LCM",
                "sfu.1.wifi.ap.switch.requests=1:HCM", property);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }

    @Test
    void rejectsSwitchRequestsForLegacyAps(@TempDir Path directory) throws IOException {
        Path config = scenario(directory, BASE, true, "packet.arrival.us=0", "sfu.1.wifi.ap.switch.requests=1:HCM");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(config));
    }

    private static void assertSwitch(List<String> trace, int sfu, int index, String from, String to, long startNs,
            long endNs, long observedNs, String powerW, String energyUj) {
        Map<String, String> start = events(trace, PREFIX + "START", sfu).get(index);
        Map<String, String> end = events(trace, PREFIX + "END", sfu).get(index);
        assertEquals(startNs, ticks(start));
        assertEquals(endNs, ticks(end));
        assertEquals(start.get("switch_id"), end.get("switch_id"));
        for (Map<String, String> row : List.of(start, end)) {
            assertEquals(from, row.get("from_mode"));
            assertEquals(to, row.get("to_mode"));
        }
        assertEquals(endNs - startNs, Long.parseLong(end.get("duration_ns")));
        assertEquals(observedNs, Long.parseLong(end.get("observed_ns")));
        assertDecimal(powerW, end.get("power_w"));
        assertDecimal(energyUj, end.get("switch_energy_uj"));
    }

    private static void assertData(List<String> trace, int sfu, int packetId, long startNs, long durationNs) {
        String id = Integer.toString(packetId);
        Map<String, String> start = events(trace, "WIFI_TX_START", sfu).stream()
                .filter(row -> id.equals(row.get("packet_id"))).findFirst().orElseThrow();
        Map<String, String> end = events(trace, "WIFI_DATA_END", sfu).stream()
                .filter(row -> id.equals(row.get("packet_id"))).findFirst().orElseThrow();
        assertEquals(startNs, ticks(start));
        assertEquals(durationNs, ticks(end) - ticks(start));
    }

    private static void assertNoRadioDuringSwitch(List<String> trace, int sfu) {
        List<Long> starts = eventTimes(trace, PREFIX + "START", sfu);
        List<Long> ends = eventTimes(trace, PREFIX + "END", sfu);
        assertEquals(starts.size(), ends.size());
        // 区间半开，ACK 完成允许与随后切换开始同刻，不能让 DATA 或 ACK 在切换内部启动。
        for (String action : List.of("WIFI_TX_START", "WIFI_ACK_START", "WIFI_COLLISION_START")) {
            for (long frame : eventTimes(trace, action, sfu)) {
                for (int index = 0; index < starts.size(); index++) {
                    assertTrue(frame < starts.get(index) || frame >= ends.get(index),
                            "Frame started inside a switch interval for SFU " + sfu);
                }
            }
        }
    }

    private static void assertAccounting(List<String> trace, int sfu, long end, long idle, long tx, long rx,
            long switching, String idleEnergy, String txEnergy, String rxEnergy, String switchEnergy, String total) {
        Map<String, String> activity = summary(trace, "WIFI_AP_ACTIVITY", sfu);
        assertEquals("0", activity.get("observation_start_ns"));
        assertEquals(Long.toString(end), activity.get("observation_end_ns"));
        assertEquals(Long.toString(idle), activity.get("idle_ns"));
        assertEquals(Long.toString(tx), activity.get("tx_ns"));
        assertEquals(Long.toString(rx), activity.get("rx_ns"));
        assertEquals(Long.toString(switching), activity.get("switch_ns"));
        assertEquals(end, idle + tx + rx + switching);

        Map<String, String> energy = summary(trace, "WIFI_AP_ENERGY", sfu);
        assertDecimal(idleEnergy, energy.get("idle_energy_uj"));
        assertDecimal(txEnergy, energy.get("tx_energy_uj"));
        assertDecimal(rxEnergy, energy.get("rx_energy_uj"));
        assertDecimal(switchEnergy, energy.get("switch_energy_uj"));
        assertDecimal(total, energy.get("total_energy_uj"));
        assertDecimal(total, new BigDecimal(idleEnergy).add(new BigDecimal(txEnergy)).add(new BigDecimal(rxEnergy))
                .add(new BigDecimal(switchEnergy)).toPlainString());
    }

    private static void assertModeTime(List<String> trace, int sfu, String mode, long idle, long tx, long rx) {
        Map<String, String> row = trace.stream().filter(line -> line.startsWith("summary=WIFI_AP_ENERGY_MODE "))
                .map(ApModeSwitchSimulationTest::fields)
                .filter(fields -> Integer.toString(sfu).equals(fields.get("sfu_id"))
                        && mode.equals(fields.get("ap_mode"))).findFirst().orElseThrow();
        assertEquals(Long.toString(idle), row.get("idle_ns"));
        assertEquals(Long.toString(tx), row.get("tx_ns"));
        assertEquals(Long.toString(rx), row.get("rx_ns"));
    }

    private static void assertDecimal(String expected, String actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)),
                () -> "Expected " + expected + ", got " + actual);
    }

    private static Path scenario(Path directory, List<String> base, boolean switching, String... settings)
            throws IOException {
        List<String> properties = new ArrayList<>(base);
        if (switching) {
            properties.add(ON);
            properties.addAll(SWITCH_PROFILES);
        }
        properties.addAll(List.of(settings));
        return Files.write(directory.resolve("ap-mode-switch.properties"), properties, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path config) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(config), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static Map<String, String> summary(List<String> trace, String kind, Integer sfu) {
        List<Map<String, String>> matches = trace.stream().filter(line -> line.startsWith("summary=" + kind + " "))
                .map(ApModeSwitchSimulationTest::fields)
                .filter(row -> sfu == null || sfu.toString().equals(row.get("sfu_id")))
                .collect(Collectors.toList());
        assertEquals(1, matches.size(), "Expected one " + kind + " summary for SFU " + sfu);
        return matches.get(0);
    }

    private static List<Map<String, String>> events(List<String> trace, String action) {
        return trace.stream().filter(line -> line.contains(" event=" + action + " "))
                .map(ApModeSwitchSimulationTest::fields).collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<String> trace, String action, int sfu) {
        return events(trace, action).stream().filter(row -> Integer.toString(sfu).equals(row.get("sfu_id")))
                .collect(Collectors.toList());
    }

    private static List<Long> eventTimes(List<String> trace, String action, int sfu) {
        return events(trace, action, sfu).stream().map(ApModeSwitchSimulationTest::ticks).collect(Collectors.toList());
    }

    private static long ticks(Map<String, String> event) {
        if (event.containsKey("t_ns")) {
            return Long.parseLong(event.get("t_ns"));
        }
        return new BigDecimal(event.get("t_us")).movePointRight(3).longValueExact();
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
