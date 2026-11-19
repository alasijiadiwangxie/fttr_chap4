package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonUplinkFramedTest {
    @Test
    void schedulesTwoSfusInRequestOrderOnExactFrameBoundaries(@TempDir Path directory) throws IOException {
        // 故意让 SFU 2 先请求，同时让传播时间跨帧，核对接收窗口与光侧利用率各自的口径。
        List<String> properties = List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed",
                "fiber.uplink.propagation.us=152.025", "wifi.delay.us=0", "sfu.count=2", "sta.count=2",
                "sta.1.sfu.id=2", "sta.2.sfu.id=1", "packet.arrival.us=0,0", "packet.bytes=1200",
                "packet.2.sta.id=2");
        List<Map<String, String>> trace = run(directory, properties);

        assertEquals(trace, run(directory, properties));
        List<Map<String, String>> grants = grants(trace);
        assertEquals(2, grants.size());
        assertGrant(grants.get(0), 1, 1, 2, 0, 125_000, 125_000, 128_840);
        assertGrant(grants.get(1), 1, 2, 1, 0, 125_000, 128_840, 132_680);
        assertTimeline(List.of("1@125000", "2@128840"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("1@128840", "2@132680"), trace, "FIBER_TX_END", "SFU");
        assertTimeline(List.of("1@280865", "2@284705"), trace, "DELIVERED", "MFU");
        List<Map<String, String>> deliveries = events(trace, "DELIVERED", "MFU");
        assertEquals(List.of("2", "1"), values(deliveries, "sfu_id"));
        assertEquals(List.of("1", "2"), values(deliveries, "source_sta_id"));
        assertEquals(List.of("1", "1"), values(deliveries, "destination_mfu_id"));

        List<Map<String, String>> frames = summaries(trace, "PON_UPLINK_FRAME");
        assertEquals(2, frames.size());
        assertFrame(frames.get(0), 0, 0, 125_000, 0, 0);
        assertFrame(frames.get(1), 1, 125_000, 250_000, 2, 7_680);
        Map<String, String> total = summary(trace, "PON_UPLINK_FRAMES");
        assertFields(total, Map.of("frames", 2L, "frame_period_ns", 125_000L, "window_ns", 250_000L,
                "allocated_packets", 2L, "busy_ns", 7_680L, "idle_ns", 242_320L));
        assertNumber(125, total, "avg_grant_wait_us");
        assertNumber(7_680.0 / 250_000, total, "utilization");
        assertNumber(126.92, summary(trace, "PON_UPLINK"), "avg_queue_wait_us");
    }

    @Test
    void enforcesThirtyTwoPacketsPerFrameAcrossSeveralSfus(@TempDir Path directory) throws IOException {
        // 65 条等长报文在 0 μs 入队，前两轮各发满 32 条，其余一条进入第三轮。
        int packetCount = 65;
        List<String> properties = new ArrayList<>(List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed",
                "fiber.uplink.propagation.us=12.025", "wifi.delay.us=0", "sfu.count=2", "sta.count=2",
                "sta.1.sfu.id=2", "sta.2.sfu.id=1", "packet.bytes=1200",
                "packet.arrival.us=" + String.join(",", Collections.nCopies(packetCount, "0"))));
        for (int packetId = 2; packetId <= packetCount; packetId += 2) {
            properties.add("packet." + packetId + ".sta.id=2");
        }
        List<Map<String, String>> trace = run(directory, properties);
        List<Map<String, String>> enqueues = events(trace, "PON_UPLINK_ENQUEUE", "SFU");
        List<Map<String, String>> grants = grants(trace);
        List<Map<String, String>> starts = events(trace, "FIBER_TX_START", "SFU");
        List<Map<String, String>> ends = events(trace, "FIBER_TX_END", "SFU");
        List<Map<String, String>> deliveries = events(trace, "DELIVERED", "MFU");

        List<String> packetIds = values(enqueues, "packet_id");
        assertEquals(packetCount, packetIds.size());
        assertEquals(packetCount, packetIds.stream().distinct().count());
        assertEquals(packetIds, values(grants, "packet_id"));
        assertEquals(packetIds, values(starts, "packet_id"));
        assertEquals(packetIds, values(ends, "packet_id"));
        assertEquals(packetIds, values(deliveries, "packet_id"));
        assertEquals(values(enqueues, "sfu_id"), values(deliveries, "sfu_id"));
        for (int i = 0; i < packetCount; i++) {
            long frameId = 1 + i / 32;
            long grantNs = frameId * 125_000;
            long startNs = grantNs + (i % 32) * 3_840L;
            long endNs = startNs + 3_840;
            long packetId = Long.parseLong(packetIds.get(i));
            int sfuId = packetId % 2 == 0 ? 1 : 2;
            assertGrant(grants.get(i), frameId, packetId, sfuId, 0, grantNs, startNs, endNs);
            assertEquals(startNs, atNs(starts.get(i)));
            assertEquals(endNs, atNs(ends.get(i)));
            assertEquals(endNs + 12_025, atNs(deliveries.get(i)));
        }
        List<Map<String, String>> frames = summaries(trace, "PON_UPLINK_FRAME");
        assertEquals(4, frames.size());
        assertFrame(frames.get(0), 0, 0, 125_000, 0, 0);
        assertFrame(frames.get(1), 1, 125_000, 250_000, 32, 122_880);
        assertFrame(frames.get(2), 2, 250_000, 375_000, 32, 122_880);
        assertFrame(frames.get(3), 3, 375_000, 500_000, 1, 3_840);
        assertFields(summary(trace, "UL_GLOBAL"), Map.of("delivered_packets", 65L, "delivered_bytes", 78_000L));
    }

    @Test
    void defersBoundaryAndMidFrameEnqueuesUntilTheNextFrame(@TempDir Path directory) throws IOException {
        // 包 2 的无线完成事件先于 125 μs 帧事件排入仿真队列，边界规则仍应按入队时刻判断。
        List<Map<String, String>> trace = run(directory, List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed",
                "wifi.delay.us=100", "sfu.count=4", "sta.count=4", "sta.2.sfu.id=2", "sta.3.sfu.id=3",
                "sta.4.sfu.id=4", "packet.arrival.us=0,25,26,150", "packet.bytes=1200", "packet.2.sta.id=2",
                "packet.3.sta.id=3", "packet.4.sta.id=4"));

        assertTimeline(List.of("1@100000", "2@125000", "3@126000", "4@250000"), trace, "PON_UPLINK_ENQUEUE", "SFU");
        List<Map<String, String>> grants = grants(trace);
        assertEquals(4, grants.size());
        assertGrant(grants.get(0), 1, 1, 1, 100_000, 125_000, 125_000, 128_840);
        assertGrant(grants.get(1), 2, 2, 2, 125_000, 250_000, 250_000, 253_840);
        assertGrant(grants.get(2), 2, 3, 3, 126_000, 250_000, 253_840, 257_680);
        assertGrant(grants.get(3), 3, 4, 4, 250_000, 375_000, 375_000, 378_840);
        assertTimeline(List.of("1@125000", "2@250000", "3@253840", "4@375000"), trace, "FIBER_TX_START", "SFU");
    }

    @Test
    void doesNotBackfillPastAnOlderHeadThatDoesNotFit(@TempDir Path directory) throws IOException {
        // 第一包发完余 29 μs，第二包需要 32 μs。第三包虽能放入，也必须等待第二包。
        List<Map<String, String>> trace = run(directory, List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed",
                "wifi.delay.us=0", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.arrival.us=0,0,1",
                "packet.bytes=1200", "packet.1.bytes=30000", "packet.2.bytes=10000", "packet.3.sta.id=2"));

        List<Map<String, String>> grants = grants(trace);
        assertEquals(3, grants.size());
        assertGrant(grants.get(0), 1, 1, 1, 0, 125_000, 125_000, 221_000);
        assertGrant(grants.get(1), 2, 2, 1, 0, 250_000, 250_000, 282_000);
        assertGrant(grants.get(2), 2, 3, 2, 1_000, 250_000, 282_000, 285_840);
        assertFrame(summaries(trace, "PON_UPLINK_FRAME").get(1), 1, 125_000, 250_000, 1, 96_000);
    }

    @Test
    void allowsSlotsToFinishOnTheNextBoundaryUsingConfiguredNanosecondPeriod(@TempDir Path directory)
            throws IOException {
        // 8 Gb/s 时每字节 1 ns。首包填满整帧，下一帧可在同一刻度开始发送。
        List<Map<String, String>> trace = run(directory, List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed",
                "fiber.uplink.frame.period.us=125.025", "fiber.uplink.rate.mbps=8000", "wifi.delay.us=0",
                "packet.arrival.us=0,0", "packet.bytes=125025", "packet.2.bytes=1200"));

        List<Map<String, String>> grants = grants(trace);
        assertEquals(2, grants.size());
        assertGrant(grants.get(0), 1, 1, 1, 0, 125_025, 125_025, 250_050);
        assertGrant(grants.get(1), 2, 2, 1, 0, 250_050, 250_050, 251_250);
        assertTimeline(List.of("1@250050", "2@251250"), trace, "DELIVERED", "MFU");
        assertFrame(summaries(trace, "PON_UPLINK_FRAME").get(1), 1, 125_025, 250_050, 1, 125_025);
    }

    @Test
    void keepsFramedUplinkIndependentOfEdcaAckAndSharedDownlink(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(directory, List.of("traffic.direction=BOTH",
                "fiber.uplink.mode=pon-framed", "fiber.uplink.propagation.us=4.025", "fiber.downlink.mode=pon-shared",
                "wifi.model=he-su", "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.cwmax=0", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "packet.arrival.us=1,126", "packet.bytes=1200", "packet.1.direction=UL",
                "packet.2.direction=DL", "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2"));

        assertTimeline(List.of("1@99000"), trace, "WIFI_DATA_END", "SFU");
        assertGrant(grants(trace).get(0), 1, 1, 1, 99_000, 125_000, 125_000, 128_840);
        assertTimeline(List.of("1@125000"), trace, "FIBER_TX_START", "SFU");
        assertTimeline(List.of("2@126000"), trace, "FIBER_TX_START", "MFU");
        assertTimeline(List.of("2@126960"), trace, "FIBER_TX_END", "MFU");
        assertTimeline(List.of("1@132865"), trace, "DELIVERED", "MFU");
        assertTimeline(List.of("1@143000"), trace, "WIFI_ACK_END", "STA");
        assertTimeline(List.of("2@224960"), trace, "DELIVERED", "STA");
        assertNumber(26, summary(trace, "PON_UPLINK_FRAMES"), "avg_grant_wait_us");
        assertNumber(34, summaries(trace, "UL_STA").get(0), "avg_queue_wait_us");
    }

    @Test
    void rejectsAPacketLargerThanTheFrameBeforeStartingSimulation(@TempDir Path directory) throws IOException {
        Path path = write(directory, List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed", "wifi.delay.us=0",
                "packet.arrival.us=0", "packet.bytes=39063"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(path));
        assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("frame"), error.getMessage());
    }

    @Test
    void rejectsInvalidFramePeriodsAndPeriodsOutsideFramedMode(@TempDir Path directory) throws IOException {
        for (String value : List.of("0", "-125", "125.0001", "NaN")) {
            Path path = write(directory, List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed",
                    "fiber.uplink.frame.period.us=" + value, "wifi.delay.us=0", "packet.arrival.us=0", "packet.bytes=1200"));
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(path), value);
        }
        Path shared = write(directory, List.of("traffic.direction=UL", "fiber.uplink.mode=pon-shared",
                "fiber.uplink.frame.period.us=125", "wifi.delay.us=0", "packet.arrival.us=0", "packet.bytes=1200"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(shared));
    }

    @Test
    void accountsForIdleFramesBeforeTheFirstGrant(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(directory, List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed",
                "wifi.delay.us=0", "packet.arrival.us=375", "packet.bytes=1200"));

        List<Map<String, String>> grants = grants(trace);
        assertEquals(1, grants.size());
        assertGrant(grants.get(0), 4, 1, 1, 375_000, 500_000, 500_000, 503_840);
        List<Map<String, String>> frames = summaries(trace, "PON_UPLINK_FRAME");
        assertEquals(5, frames.size());
        for (int i = 0; i < 4; i++) {
            assertFrame(frames.get(i), i, i * 125_000L, (i + 1) * 125_000L, 0, 0);
        }
        assertFrame(frames.get(4), 4, 500_000, 625_000, 1, 3_840);
    }

    private static Path write(Path directory, List<String> properties) throws IOException {
        return Files.write(directory.resolve("pon-framed-uplink.properties"), properties, StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> run(Path directory, List<String> properties) throws IOException {
        Path path = write(directory, properties);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(PonUplinkFramedTest::fields)
                .collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> result = new HashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            result.put(pair[0], pair[1]);
        }
        return result;
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event, String node) {
        return trace.stream().filter(row -> event.equals(row.get("event")) && node.equals(row.get("node")))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> grants(List<Map<String, String>> trace) {
        return events(trace, "PON_UPLINK_GRANT", "MFU");
    }

    private static List<Map<String, String>> summaries(List<Map<String, String>> trace, String name) {
        return trace.stream().filter(row -> name.equals(row.get("summary"))).collect(Collectors.toList());
    }

    private static Map<String, String> summary(List<Map<String, String>> trace, String name) {
        List<Map<String, String>> rows = summaries(trace, name);
        assertEquals(1, rows.size(), name);
        return rows.get(0);
    }

    private static List<String> values(List<Map<String, String>> rows, String name) {
        return rows.stream().map(row -> row.get(name)).collect(Collectors.toList());
    }

    private static long atNs(Map<String, String> row) {
        return new BigDecimal(row.get("t_us")).movePointRight(3).longValueExact();
    }

    private static void assertTimeline(List<String> expected, List<Map<String, String>> trace, String event,
                                       String node) {
        List<String> actual = events(trace, event, node).stream()
                .map(row -> row.get("packet_id") + "@" + atNs(row)).collect(Collectors.toList());
        assertEquals(expected, actual, event + " at " + node);
    }

    private static void assertGrant(Map<String, String> grant, long frameId, long packetId, long sfuId, long enqueueNs,
                                    long grantNs, long startNs, long endNs) {
        assertFields(grant, Map.of("frame_id", frameId, "packet_id", packetId, "sfu_id", sfuId,
                "enqueue_time_ns", enqueueNs, "grant_time_ns", grantNs, "tx_start_ns", startNs, "tx_end_ns", endNs));
        assertEquals(grantNs, atNs(grant));
    }

    private static void assertFrame(Map<String, String> frame, long frameId, long startNs, long endNs, long packets,
                                    long busyNs) {
        long periodNs = endNs - startNs;
        assertFields(frame, Map.of("frame_id", frameId, "frame_start_ns", startNs, "frame_end_ns", endNs,
                "allocated_packets", packets, "busy_ns", busyNs, "idle_ns", periodNs - busyNs));
        assertNumber((double) busyNs / periodNs, frame, "utilization");
    }

    private static void assertFields(Map<String, String> row, Map<String, Long> expected) {
        expected.forEach((key, value) -> assertEquals(value.toString(), row.get(key), key));
    }

    private static void assertNumber(double expected, Map<String, String> row, String field) {
        assertEquals(expected, Double.parseDouble(row.get(field)), 1e-6, field);
    }
}
