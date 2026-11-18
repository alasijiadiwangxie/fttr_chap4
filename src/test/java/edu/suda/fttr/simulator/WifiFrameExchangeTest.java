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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WifiFrameExchangeTest {
    @Test
    void accountsForTheWholeExchangeAndDeliversAtDataEnd(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(scenario(directory, "fiber.delay.us=10", "packet.arrival.us=0",
                "wifi.mac.exchange=data-sifs-ack"));

        // 默认 HE SU 的 1500 B DATA 占 64 μs。14 B ACK 在 24 Mb/s 下占两个 OFDM 数据符号，
        // 加上 20 μs 前导码和信令合计 28 μs。光纤一跳后，108 μs 的无线占用从 10 μs 开始。
        assertEvent(trace, 1, "WIFI_TX_START", "10", "SFU");
        assertEvent(trace, 1, "WIFI_DATA_END", "74", "STA");
        assertEvent(trace, 1, "WIFI_SIFS_START", "74", "STA");
        assertEvent(trace, 1, "WIFI_ACK_START", "90", "STA");
        assertEvent(trace, 1, "WIFI_ACK_END", "118", "SFU");
        assertEvent(trace, 1, "DELIVERED", "74", "STA");
        Map<String, String> release = packetEvent(trace, 1, "WIFI_TX_END");
        assertMicroseconds("118", release, "t_us");
        assertMicroseconds("108", release, "wifi_service_us");
        assertMicroseconds("74", release, "e2e_delay_us");
        assertMicroseconds("74", trace.get(trace.size() - 1), "e2e_delay_us");
    }

    @ParameterizedTest
    @CsvSource({"6,44", "9,36", "12,32", "18,28", "24,28", "36,24", "48,24", "54,24"})
    void usesConfiguredSifsAndNonHtAckAirtime(int rateMbps, int ackDurationUs, @TempDir Path directory)
            throws IOException {
        List<Map<String, String>> trace = run(scenario(directory, "fiber.delay.us=0", "packet.arrival.us=0",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.sifs.us=10", "wifi.mac.ack.rate.mbps=" + rateMbps));

        // 参数化的 ACK 速率不应改变 DATA PPDU 的 64 μs 时长。
        assertEvent(trace, 1, "WIFI_DATA_END", "64", "STA");
        assertEvent(trace, 1, "WIFI_ACK_START", "74", "STA");
        assertEvent(trace, 1, "WIFI_ACK_END", Integer.toString(74 + ackDurationUs), "SFU");
        assertMicroseconds(Integer.toString(74 + ackDurationUs), packetEvent(trace, 1, "WIFI_TX_END"), "wifi_service_us");
    }

    @Test
    void startsSifsAfterTheFractionalDataPpduIncludingPe(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(scenario(directory, "fiber.delay.us=10", "packet.arrival.us=0",
                "wifi.mac.exchange=data-sifs-ack", "wifi.phy.bandwidth.mhz=80", "wifi.phy.nss=1",
                "wifi.phy.he-ltf.type=1", "wifi.phy.nominal.packet.padding.us=16", "packet.1.bytes=215"));

        // 40 μs 前导码、13.6 μs DATA 符号和 12 μs PE 均先于 SIFS。所有时刻保留小数微秒。
        assertEvent(trace, 1, "WIFI_DATA_END", "75.6", "STA");
        assertEvent(trace, 1, "WIFI_SIFS_START", "75.6", "STA");
        assertEvent(trace, 1, "WIFI_ACK_START", "91.6", "STA");
        assertEvent(trace, 1, "WIFI_ACK_END", "119.6", "SFU");
        assertMicroseconds("109.6", packetEvent(trace, 1, "WIFI_TX_END"), "wifi_service_us");
    }

    @Test
    void releasesBeforeSelectingTheNextPacketAndHonorsATiedPriorityArrival(@TempDir Path directory)
            throws IOException {
        List<Map<String, String>> trace = run(scenario(directory, "fiber.delay.us=0", "packet.arrival.us=0,0,108",
                "wifi.mac.exchange=data-sifs-ack", "packet.3.type=VO"));

        // 第二包等待整个交换。和第一包 ACK 完成同刻入队的 VO 先于等待的 BE 发出。
        assertEquals(List.of("WIFI_TX_START:1@0", "WIFI_ACK_END:1@108", "WIFI_TX_END:1@108",
                "WIFI_TX_START:3@108", "WIFI_ACK_END:3@216", "WIFI_TX_END:3@216",
                "WIFI_TX_START:2@216", "WIFI_ACK_END:2@324", "WIFI_TX_END:2@324"), wirelessBoundaries(trace));
        Map<String, String> totals = trace.get(trace.size() - 1);
        assertEquals("3", totals.get("delivered_packets"));
        assertMicroseconds("324", totals, "wifi_busy_us");
        assertMicroseconds("0", totals, "wifi_idle_us");
        assertMicroseconds("324", totals, "simulation_end_us");
    }

    @Test
    void serializesUplinkAndDownlinkOnTheSameSfu(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(scenario(directory, "fiber.delay.us=10", "packet.arrival.us=0,0,190",
                "wifi.mac.exchange=data-sifs-ack", "wifi.phy.bandwidth.mhz=80", "wifi.phy.nss=1",
                "traffic.direction=BOTH", "sta.count=2", "packet.1.direction=UL", "packet.1.bytes=200",
                "packet.2.direction=DL", "packet.2.sta.id=2", "packet.3.direction=UL", "packet.3.bytes=200",
                "packet.3.type=VO"));

        // UL 由 STA 1 发送，DL 由同一 SFU 向 STA 2 发送。第三包在下行 ACK 期间到达，仍须等 ACK 结束。
        assertEquals(List.of("WIFI_TX_START:1@0", "WIFI_ACK_END:1@100.8", "WIFI_TX_END:1@100.8",
                "WIFI_TX_START:2@100.8", "WIFI_ACK_END:2@215.2", "WIFI_TX_END:2@215.2",
                "WIFI_TX_START:3@215.2", "WIFI_ACK_END:3@316", "WIFI_TX_END:3@316"), wirelessBoundaries(trace));
        assertEvent(trace, 1, "WIFI_ACK_START", "72.8", "SFU");
        assertEvent(trace, 1, "WIFI_ACK_END", "100.8", "STA");
        assertEvent(trace, 2, "WIFI_ACK_START", "187.2", "STA");
        assertEvent(trace, 1, "FIBER_TX_START", "56.8", "SFU");
        assertEvent(trace, 1, "DELIVERED", "66.8", "MFU");
        assertEvent(trace, 2, "DELIVERED", "171.2", "STA");
        assertEvent(trace, 3, "DELIVERED", "282", "MFU");
        assertMicroseconds("316", trace.get(trace.size() - 1), "wifi_busy_us");
    }

    @Test
    void keepsTheRadiosOfDifferentSfusIndependent(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(scenario(directory, "fiber.delay.us=0", "packet.arrival.us=0,30",
                "wifi.mac.exchange=data-sifs-ack", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.2.sta.id=2"));

        // 两个 SFU 的交换分别占用 [0, 108) 和 [30, 138)，全局只累计占用区间的并集。
        assertEquals(List.of("WIFI_TX_START:1@0", "WIFI_TX_START:2@30",
                "WIFI_ACK_END:1@108", "WIFI_TX_END:1@108", "WIFI_ACK_END:2@138", "WIFI_TX_END:2@138"),
                wirelessBoundaries(trace));
        assertEquals("2", packetEvent(trace, 2, "WIFI_ACK_START").get("sfu_id"));
        assertMicroseconds("138", trace.get(trace.size() - 1), "wifi_busy_us");
    }

    @Test
    void validatesMacSettingsAndKeepsDataOnlyModeCompatible(@TempDir Path directory) throws IOException {
        List<Map<String, String>> oldTrace = run(scenario(directory, "fiber.delay.us=0", "packet.arrival.us=0"));
        assertEquals(oldTrace, run(scenario(directory, "fiber.delay.us=0", "packet.arrival.us=0",
                "wifi.mac.exchange=data-only")));
        assertMicroseconds("64", packetEvent(oldTrace, 1, "WIFI_TX_END"), "wifi_service_us");

        for (String invalid : List.of("wifi.mac.sifs.us=0", "wifi.mac.sifs.us=0.8", "wifi.mac.ack.rate.mbps=25",
                "wifi.delay.us=40")) {
            Path path = scenario(directory, "fiber.delay.us=0", "packet.arrival.us=0",
                    "wifi.mac.exchange=data-sifs-ack", invalid);
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(path), invalid);
        }
        Path unsupportedMode = scenario(directory, "fiber.delay.us=0", "packet.arrival.us=0", "wifi.mac.exchange=other");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(unsupportedMode));
    }

    @Test
    void validatesOverflowIncludingAckTime(@TempDir Path directory) throws IOException {
        // 仅 DATA 的 64 μs 仍可容纳。完整的 108 μs 交换超过时钟上界。
        String arrival = "packet.arrival.us=" + (Long.MAX_VALUE / 1000 - 80);
        ConfigLoader.load(scenario(directory, "fiber.delay.us=0", arrival));
        Path file = scenario(directory, "fiber.delay.us=0", arrival, "wifi.mac.exchange=data-sifs-ack");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(file));
    }

    private static Path scenario(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("packet.bytes=1500"));
        properties.addAll(List.of(settings));
        return Files.write(directory.resolve("wifi-frame-exchange.properties"), properties, StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> run(Path file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(file), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(line -> {
            Map<String, String> fields = new HashMap<>();
            for (String token : line.split(" ")) {
                String[] pair = token.split("=", 2);
                fields.put(pair[0], pair[1]);
            }
            return fields;
        }).collect(Collectors.toList());
    }

    private static List<String> wirelessBoundaries(List<Map<String, String>> trace) {
        Set<String> eventKinds = Set.of("WIFI_TX_START", "WIFI_ACK_END", "WIFI_TX_END");
        return trace.stream().filter(row -> row.containsKey("event") && eventKinds.contains(row.get("event")))
                .map(row -> row.get("event") + ":" + row.get("packet_id") + "@" + row.get("t_us"))
                .collect(Collectors.toList());
    }

    private static Map<String, String> packetEvent(List<Map<String, String>> trace, int packetId, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))
                && Integer.toString(packetId).equals(row.get("packet_id"))).findFirst().orElseThrow();
    }

    private static void assertEvent(List<Map<String, String>> trace, int packet, String event, String time, String node) {
        Map<String, String> row = packetEvent(trace, packet, event);
        assertMicroseconds(time, row, "t_us");
        assertEquals(node, row.get("node"), row.toString());
    }

    private static void assertMicroseconds(String expected, Map<String, String> row, String field) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(row.get(field))), row.toString());
    }
}
