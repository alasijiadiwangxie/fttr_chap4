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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeSuSimulationTest {
    @Test
    void defaultsToHeSuAndReportsTheConfiguredPhyRate(@TempDir Path directory) throws IOException {
        // 新场景省略固定无线时延与 PHY 参数，使用默认 160 MHz、MCS 11、2 NSS、0.8 μs GI。
        List<Map<String, String>> trace = trace(run(load(directory, "packet.bytes=1500", "packet.arrival.us=0",
                "fiber.delay.us=100")));
        Map<String, String> phy = row(trace, "summary", "WIFI_PHY");
        assertEquals("HE_SU", phy.get("model"));
        assertEquals(32666.0 / 13.6, Double.parseDouble(phy.get("phy_rate_mbps")), 1e-6);
        assertDecimal("64", packetEvent(trace, "WIFI_TX_END", 1), "wifi_service_us");
        assertDecimal("164", packetEvent(trace, "DELIVERED", 1), "t_us");
    }

    @Test
    void keepsFractionalPpduBoundariesAndTheSharedUplinkDownlinkRadio(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = trace(run(load(directory, "wifi.model=he-su", "wifi.phy.bandwidth.mhz=80",
                "wifi.phy.mcs=11", "wifi.phy.nss=1", "wifi.phy.gi.us=0.8", "wifi.phy.mac.overhead.bytes=34",
                "fiber.delay.us=10", "traffic.direction=BOTH", "packet.bytes=200", "packet.arrival.us=0,0,57",
                "packet.1.direction=UL", "packet.2.direction=DL", "packet.2.bytes=1500",
                "packet.3.direction=UL", "packet.3.type=VO")));

        // 80 MHz/MCS 11/1 NSS 的前导码为 43.2 μs。200 B 用一个数据符号，1500 B 用两个。
        // 上行第 3 包在下行第 2 包开始后到达。VO 必须等当前 PPDU 发完；光侧传播不占无线资源。
        assertWifiInterval(trace, 1, "0", "56.8", "56.8", "0");
        assertWifiInterval(trace, 2, "56.8", "127.2", "70.4", "46.8");
        assertWifiInterval(trace, 3, "127.2", "184", "56.8", "70.2");
        assertDecimal("56.8", packetEvent(trace, "FIBER_TX_START", 1), "t_us");
        assertDecimal("66.8", packetEvent(trace, "DELIVERED", 1), "t_us");
        assertDecimal("127.2", packetEvent(trace, "DELIVERED", 2), "t_us");
        assertDecimal("194", packetEvent(trace, "DELIVERED", 3), "t_us");

        // 忙时只含三段 PPDU，最后 10 μs 是上行光侧，计入总时长和端到端时延。
        Map<String, String> totals = trace.get(trace.size() - 1);
        assertEquals("3", totals.get("delivered_packets"));
        assertDecimal("184", totals, "wifi_busy_us");
        assertDecimal("10", totals, "wifi_idle_us");
        assertDecimal("194", totals, "simulation_end_us");
        assertDecimal("101.9", row(trace, "summary", "UL_GLOBAL"), "avg_e2e_delay_us");
        assertDecimal("35.1", row(trace, "summary", "UL_STA"), "avg_queue_wait_us");
    }

    @Test
    void keepsLegacyFixedModeAndRejectsConflictingModelParameters(@TempDir Path directory) throws IOException {
        List<String> explicit = run(load(directory, "wifi.model=fixed", "wifi.delay.us=23", "fiber.delay.us=7",
                "packet.arrival.us=11", "packet.bytes=1500"));
        assertEquals(explicit, run(load(directory, "wifi.delay.us=23", "fiber.delay.us=7", "packet.arrival.us=11",
                "packet.bytes=1500")));
        assertTrue(explicit.stream().noneMatch(line -> line.startsWith("summary=WIFI_PHY ")));
        List<Map<String, String>> trace = trace(explicit);
        assertDecimal("23", packetEvent(trace, "WIFI_TX_END", 1), "wifi_service_us");
        assertDecimal("41", packetEvent(trace, "DELIVERED", 1), "t_us");

        assertThrows(IllegalArgumentException.class, () -> load(directory, "wifi.model=he-su", "wifi.delay.us=23",
                "fiber.delay.us=7", "packet.arrival.us=11", "packet.bytes=1500"));
        assertThrows(IllegalArgumentException.class, () -> load(directory, "wifi.model=fixed", "wifi.delay.us=23",
                "wifi.phy.mcs=0", "fiber.delay.us=7", "packet.arrival.us=11", "packet.bytes=1500"));
    }

    @Test
    void rejectsAnInputOutsideTheNanosecondClockRange(@TempDir Path directory) {
        long arrivalUs = Long.MAX_VALUE / 1000 + 1;
        assertThrows(IllegalArgumentException.class, () -> load(directory, "wifi.model=he-su", "fiber.delay.us=0",
                "packet.arrival.us=" + arrivalUs, "packet.bytes=1500"));
    }

    private static SimulationConfig load(Path directory, String... properties) throws IOException {
        Path scenario = Files.write(directory.resolve("he-su.properties"), List.of(properties), StandardCharsets.UTF_8);
        return ConfigLoader.load(scenario);
    }

    private static List<String> run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> trace(List<String> lines) {
        return lines.stream().map(line -> {
            Map<String, String> fields = new HashMap<>();
            for (String token : line.split(" ")) {
                String[] pair = token.split("=", 2);
                fields.put(pair[0], pair[1]);
            }
            return fields;
        }).collect(Collectors.toList());
    }

    private static Map<String, String> row(List<Map<String, String>> trace, String key, String value) {
        return trace.stream().filter(fields -> value.equals(fields.get(key))).findFirst().orElseThrow();
    }

    private static Map<String, String> packetEvent(List<Map<String, String>> trace, String event, int packetId) {
        return trace.stream().filter(fields -> event.equals(fields.get("event"))
                && Integer.toString(packetId).equals(fields.get("packet_id"))).findFirst().orElseThrow();
    }

    private static void assertWifiInterval(List<Map<String, String>> trace, int packetId, String start, String end,
                                           String service, String wait) {
        assertDecimal(start, packetEvent(trace, "WIFI_TX_START", packetId), "t_us");
        Map<String, String> finish = packetEvent(trace, "WIFI_TX_END", packetId);
        assertDecimal(end, finish, "t_us");
        assertDecimal(service, finish, "wifi_service_us");
        assertDecimal(wait, finish, "wifi_queue_wait_us");
    }

    private static void assertDecimal(String expected, Map<String, String> fields, String key) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(fields.get(key))), fields.toString());
    }
}
