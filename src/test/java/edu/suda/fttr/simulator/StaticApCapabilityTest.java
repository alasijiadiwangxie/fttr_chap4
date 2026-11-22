package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
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

class StaticApCapabilityTest {
    @ParameterizedTest
    @CsvSource({"LCM,20,1,86.0294117647,165.6,181.6,209.6", "HCM,80,2,720.5882352941,77.6,93.6,121.6"})
    void calculatesTheStaticModeDataAndKeepsTheAckExchange(String mode, int bandwidth, int nss, double rate,
                                                           String dataEnd, String ackStart, String ackEnd,
                                                           @TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(load(directory, "packet.arrival.us=0", "sfu.1.wifi.ap.mode=" + mode));

        assertPhy(trace, 1, 1, mode, bandwidth, nss, rate);
        assertTimeline(trace, "WIFI_TX_START", "1@0");
        assertTimeline(trace, "WIFI_DATA_END", "1@" + dataEnd);
        assertTimeline(trace, "WIFI_SIFS_START", "1@" + dataEnd);
        assertTimeline(trace, "DELIVERED", "1@" + dataEnd);
        assertTimeline(trace, "WIFI_ACK_START", "1@" + ackStart);
        assertTimeline(trace, "WIFI_ACK_END", "1@" + ackEnd);
        assertDecimal(ackEnd, packetEvent(trace, "WIFI_TX_END", 1), "wifi_service_us");
    }

    @Test
    void keepsMixedSfusIndependentAndTheUnconfiguredLinkOnItsLegacyPhy(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(load(directory, "packet.arrival.us=0,0,0", "sfu.count=3", "sta.count=3",
                "sta.2.sfu.id=2", "sta.3.sfu.id=3", "packet.2.sta.id=2", "packet.3.sta.id=3",
                "sfu.1.wifi.ap.mode=LCM", "sfu.2.wifi.ap.mode=HCM", "wifi.phy.bandwidth.mhz=40",
                "wifi.phy.nss=1", "wifi.phy.mcs=7"));

        assertPhy(trace, 1, 1, "LCM", 20, 1, 86.0294117647);
        assertPhy(trace, 2, 2, "HCM", 80, 2, 720.5882352941);
        // 三个 SFU 的 DATA 同刻发送。未指定模式的 SFU 继续采用全局 40 MHz / 1 NSS。
        assertTimeline(trace, "WIFI_TX_START", "1@0", "2@0", "3@0");
        assertTimeline(trace, "WIFI_DATA_END", "2@77.6", "3@111.2", "1@165.6");
        assertTimeline(trace, "WIFI_ACK_END", "2@121.6", "3@155.2", "1@209.6");
        assertEquals("3", trace.get(trace.size() - 1).get("delivered_packets"));
        assertDecimal("209.6", trace.get(trace.size() - 1), "wifi_busy_us");
    }

    @Test
    void intersectsStationBandwidthAndStreamsForBothDirections(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(load(directory, "sfu.1.wifi.ap.mode=HCM", "sta.count=2",
                "sta.1.wifi.max.nss=1", "sta.2.wifi.max.bandwidth.mhz=40", "traffic.direction=BOTH",
                "packet.arrival.us=0,200,400,600", "packet.1.direction=DL", "packet.2.direction=UL",
                "packet.3.direction=DL", "packet.3.sta.id=2", "packet.4.direction=UL", "packet.4.sta.id=2"));

        assertPhy(trace, 1, 1, "HCM", 80, 1, 360.2941176471);
        assertPhy(trace, 1, 2, "HCM", 40, 2, 344.1176470588);
        // 同一终端的上下行使用同一组能力交集，STA 1 的 NSS 上限不影响 STA 2。
        assertTimeline(trace, "WIFI_TX_START", "1@0", "2@200", "3@400", "4@600");
        assertTimeline(trace, "WIFI_ACK_END", "1@128", "2@328", "3@535.2", "4@735.2");
        assertTimeline(trace, "DELIVERED", "1@84", "2@284", "3@491.2", "4@691.2");
        assertEquals("4", trace.get(trace.size() - 1).get("delivered_packets"));
    }

    @Test
    void txopAdmissionUsesTheSelectedStationsFullExchangeDuration(@TempDir Path directory) throws IOException {
        List<Map<String, String>> fits = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO",
                "packet.2.type=VO", "sfu.1.wifi.ap.mode=HCM", "wifi.mac.access=edca-multi-ac",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.txop.limit.us=260"));
        List<Map<String, String>> shorter = run(load(directory, "packet.arrival.us=0,0", "packet.1.type=VO",
                "packet.2.type=VO", "sfu.1.wifi.ap.mode=HCM", "wifi.mac.access=edca-multi-ac",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.txop.limit.us=259"));

        // HCM 的两次完整交换及中间 SIFS 共 121.6 + 16 + 121.6 = 259.2 μs。
        assertTimeline(fits, "WIFI_TX_START", "1@34", "2@171.6");
        assertTimeline(fits, "WIFI_ACK_END", "1@155.6", "2@293.2");
        assertTimeline(shorter, "WIFI_TX_START", "1@34", "2@189.6");
        assertTimeline(shorter, "WIFI_ACK_END", "1@155.6", "2@311.2");
    }

    @Test
    void collidedFramesKeepEachStationsPpduAndTheLongestBusyInterval(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(load(directory, "sfu.1.wifi.ap.mode=HCM", "sta.count=2",
                "sta.1.wifi.max.nss=1", "sta.2.wifi.max.bandwidth.mhz=40", "traffic.direction=UL",
                "packet.arrival.us=0,0", "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2",
                "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.retry.max=0",
                "wifi.mac.ack.timeout.us=5"));

        assertTimeline(trace, "WIFI_COLLISION_START", "1@34", "2@34");
        assertTimeline(trace, "WIFI_COLLISION_DATA_END", "1@118", "2@125.2");
        assertTimeline(trace, "WIFI_RETRY_LIMIT_DROP", "1@123", "2@130.2");
        List<Map<String, String>> releases = events(trace, "WIFI_COLLISION_END");
        assertEquals(1, releases.size());
        assertDecimal("125.2", releases.get(0), "t_us");
        assertTrue(events(trace, "DELIVERED").isEmpty());
        assertTrue(events(trace, "WIFI_ACK_START").isEmpty());
        assertDecimal("91.2", trace.get(trace.size() - 1), "wifi_busy_us");
    }

    @Test
    void startsModeSpecificWirelessOnlyAfterTheSharedPonDownlinkArrives(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(load(directory, "packet.arrival.us=7", "sfu.1.wifi.ap.mode=LCM",
                "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=3"));

        // 1200 B 在 10 Gb/s 光链路串行发送 0.96 μs，再传播 3 μs，最后执行 LCM 无线交换。
        assertTimeline(trace, "FIBER_TX_START", "1@7");
        assertTimeline(trace, "FIBER_TX_END", "1@7.96");
        assertTimeline(trace, "WIFI_TX_START", "1@10.96");
        assertTimeline(trace, "DELIVERED", "1@176.56");
        assertTimeline(trace, "WIFI_ACK_END", "1@220.56");
        assertPhy(trace, 1, 1, "LCM", 20, 1, 86.0294117647);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sfu.1.wifi.ap.mode=INVALID", "sfu.2.wifi.ap.mode=LCM",
            "sta.2.wifi.max.nss=1", "sta.1.wifi.max.nss=0", "sta.1.wifi.max.bandwidth.mhz=30"})
    void rejectsInvalidModeCapabilitiesOrTopologyIndex(String setting, @TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> load(directory, "packet.arrival.us=0", setting));
    }

    @Test
    void rejectsStaticApModeOnTheFixedWifiModel(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> load(directory, "packet.arrival.us=0",
                "wifi.model=fixed", "wifi.delay.us=20", "wifi.mac.exchange=data-only", "sfu.1.wifi.ap.mode=LCM"));
    }

    private static SimulationConfig load(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=0", "packet.bytes=1200",
                "wifi.mac.exchange=data-sifs-ack"));
        properties.addAll(List.of(settings));
        return ConfigLoader.load(Files.write(directory.resolve("static-ap.properties"), properties, StandardCharsets.UTF_8));
    }

    private static List<Map<String, String>> run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(line -> {
            Map<String, String> row = new HashMap<>();
            for (String field : line.split(" ")) {
                String[] pair = field.split("=", 2);
                row.put(pair[0], pair[1]);
            }
            return row;
        }).collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static Map<String, String> packetEvent(List<Map<String, String>> trace, String event, int packet) {
        return events(trace, event).stream().filter(row -> Integer.toString(packet).equals(row.get("packet_id")))
                .findFirst().orElseThrow();
    }

    private static void assertPhy(List<Map<String, String>> trace, int sfu, int station, String mode,
                                  int bandwidth, int nss, double rate) {
        Map<String, String> row = trace.stream().filter(entry -> "WIFI_STA_PHY".equals(entry.get("summary"))
                && Integer.toString(sfu).equals(entry.get("sfu_id"))
                && Integer.toString(station).equals(entry.get("sta_id"))).findFirst().orElseThrow();
        assertEquals(mode, row.get("ap_mode"));
        assertEquals(Integer.toString(bandwidth), row.get("bandwidth_mhz"));
        assertEquals(Integer.toString(nss), row.get("nss"));
        assertEquals(rate, Double.parseDouble(row.get("phy_rate_mbps")), 1e-6);
    }

    private static void assertTimeline(List<Map<String, String>> trace, String event, String... expected) {
        assertEquals(List.of(expected), events(trace, event).stream()
                .map(row -> row.get("packet_id") + "@" + row.get("t_us")).collect(Collectors.toList()));
    }

    private static void assertDecimal(String expected, Map<String, String> fields, String key) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(fields.get(key))), fields.toString());
    }
}
