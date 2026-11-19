package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonUplinkReportDeliveryTest {
    @Test
    void receivesEachSfuReportWithTheFirstSlotAndKeepsItsOriginalSnapshot(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, "true", List.of("traffic.direction=UL", "fiber.uplink.propagation.us=130",
                "wifi.delay.us=0", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2",
                "packet.arrival.us=0,1,2,130,260", "packet.bytes=1200", "packet.2.bytes=800",
                "packet.3.bytes=1000", "packet.3.sta.id=2", "packet.4.bytes=600", "packet.5.bytes=500",
                "packet.5.sta.id=2"));

        assertUnknown(state(trace, 1, 1), 125_000);
        assertUnknown(state(trace, 1, 2), 125_000);
        // 两个首个时隙都已在上一帧发完，固定传播时延使其在第 2 帧边界仍未到达。
        assertUnknown(state(trace, 2, 1), 250_000);
        assertUnknown(state(trace, 2, 2), 250_000);

        List<Map<String, String>> received = reportReceptions(trace);
        assertEquals(4, received.size());
        assertReception(received.get(0), 1, 1, 1, 125_000, 258_840, 2_000);
        assertReception(received.get(1), 1, 2, 3, 125_000, 264_600, 1_000);
        assertReception(received.get(2), 2, 1, 4, 250_000, 381_920, 600);
        assertReception(received.get(3), 3, 2, 5, 375_000, 506_600, 500);

        // SFU 1 已在第 2 帧发送后来入队的 600 B，MFU 此时仍应保留第 1 帧的原始 2000 B。
        assertKnown(state(trace, 3, 1), 375_000, 1, 125_000, 258_840, 2_000, 2_000);
        assertKnown(state(trace, 3, 2), 375_000, 1, 125_000, 264_600, 1_000, 1_000);
        assertFields(single(trace, "summary=PON_UPLINK_REPORTS "), Map.of("generated", "8", "received", "4",
                "without_grant", "4"));
    }

    @Test
    void receivesDataAndThenReportBeforeSamplingTheSameFrameBoundary(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, "true", List.of("traffic.direction=UL",
                "fiber.uplink.propagation.us=121.16", "wifi.delay.us=0", "packet.arrival.us=0,200",
                "packet.bytes=1200"));

        // 128840 ns 的首个时隙结束，加 121160 ns 的传播时延，恰好命中下一帧边界。
        assertUnknown(state(trace, 1, 1), 125_000);
        Map<String, String> report = reportReceptions(trace).get(0);
        assertReception(report, 1, 1, 1, 125_000, 250_000, 1_200);
        int dataIndex = indexOf(trace, "t_us=250 node=MFU event=DELIVERED packet_id=1 ");
        int reportIndex = indexOf(trace, "event=PON_UPLINK_REPORT_RX frame_id=1 sfu_id=1 ");
        int stateIndex = indexOf(trace, "event=PON_UPLINK_REPORT_STATE frame_id=2 sfu_id=1 ");
        assertTrue(dataIndex < reportIndex);
        assertTrue(reportIndex < stateIndex);
        assertKnown(state(trace, 2, 1), 250_000, 1, 125_000, 250_000, 1_200, 1_200);
    }

    @Test
    void keepsDemandFromAnSfuWithoutAGrantLocal(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "true", List.of("traffic.direction=UL", "wifi.delay.us=0",
                "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.arrival.us=0,1", "packet.bytes=1200",
                "packet.1.bytes=39062", "packet.2.sta.id=2"));

        // 39062 B 按 2.5 Gb/s 向上取整后占用 124999 ns，SFU 2 的 1200 B 无法放入帧尾。
        assertFields(single(trace, "event=PON_UPLINK_REPORT frame_id=1 sfu_id=2 "),
                Map.of("pending_bytes", "1200", "eligible_bytes", "1200"));
        assertUnknown(state(trace, 2, 2), 250_000);
        List<Map<String, String>> received = reportReceptions(trace);
        assertEquals(2, received.size());
        assertReception(received.get(0), 1, 1, 1, 125_000, 249_999, 39_062);
        assertReception(received.get(1), 2, 2, 2, 250_000, 253_840, 1_200);
        assertEquals(indexOf(trace, "t_us=249.999 node=MFU event=DELIVERED packet_id=1 ") + 1,
                indexOf(trace, "t_us=249.999 node=MFU event=PON_UPLINK_REPORT_RX frame_id=1 "));
        assertFields(single(trace, "summary=PON_UPLINK_REPORTS "), Map.of("generated", "6", "received", "2",
                "without_grant", "4"));
    }

    @Test
    void reportDeliveryDoesNotChangeMixedDataAckOrFcfsTrace(@TempDir Path directory) throws IOException {
        List<String> properties = List.of("traffic.direction=BOTH", "wifi.model=he-su",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0",
                "wifi.mac.edca.VO.cwmax=0", "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=5",
                "fiber.uplink.propagation.us=10.025", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2",
                "packet.arrival.us=0,0,130,140", "packet.bytes=1200", "packet.1.direction=UL", "packet.1.type=VO",
                "packet.2.direction=DL", "packet.2.type=VO", "packet.2.sta.id=2", "packet.3.direction=UL",
                "packet.3.type=VO", "packet.4.direction=UL", "packet.4.type=VO", "packet.4.sta.id=2");

        List<String> enabled = run(directory, "true", properties);
        List<String> disabled = run(directory, "false", properties);
        assertEquals(disabled, run(directory, null, properties));
        assertEquals(disabled, PonUplinkReportTest.withoutReportTelemetry(enabled));
        assertEquals(4, matching(enabled, "event=WIFI_ACK_END ").size());
        assertEquals(4, matching(enabled, "event=DELIVERED ").size());
        assertEquals(3, matching(enabled, "event=PON_UPLINK_GRANT ").size());
        assertEquals(3, reportReceptions(enabled).size());
        assertTrue(reportReceptions(disabled).isEmpty());
        assertTrue(matching(disabled, "event=PON_UPLINK_REPORT_STATE ").isEmpty());
        assertTrue(matching(disabled, "summary=PON_UPLINK_REPORTS ").isEmpty());
    }

    @Test
    void staleAndDuplicateReportsDoNotOverwriteTheMfuState() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(output), true, true);
            Mfu mfu = new Mfu(List.of(1, 2));
            Packet carrier = new Packet(1, 1200, 0, AccessCategory.BE, Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID);
            PonUplinkReport newest = new PonUplinkReport(2, 1, 250_000, 3, 3_600, 2, 2_400);
            PonUplinkReport stale = new PonUplinkReport(1, 1, 125_000, 1, 1_200, 1, 1_200);
            PonUplinkReport repeated = new PonUplinkReport(2, 1, 250_000, 7, 8_400, 5, 6_000);

            assertNull(mfu.latestUplinkReport(1));
            advanceBy(context, 260_000);
            mfu.receiveUplinkReport(newest, carrier, context);
            Mfu.ReceivedUplinkReport known = mfu.latestUplinkReport(1);
            assertSame(newest, known.report());
            assertEquals(260_000, known.receiveTimeNs());

            advanceBy(context, 1);
            mfu.receiveUplinkReport(stale, carrier, context);
            advanceBy(context, 1);
            mfu.receiveUplinkReport(repeated, carrier, context);
            assertSame(known, mfu.latestUplinkReport(1));
            assertEquals(3_600, known.report().pendingBytes());
            assertEquals(2_400, known.report().eligibleBytes());
            assertNull(mfu.latestUplinkReport(2));
            assertEquals(3, mfu.receivedUplinkReports());
        }
        List<Map<String, String>> receptions = reportReceptions(buffer.toString(StandardCharsets.UTF_8)
                .lines().collect(Collectors.toList()));
        assertEquals(List.of("true", "false", "false"), receptions.stream().map(row -> row.get("applied"))
                .collect(Collectors.toList()));
    }

    private static void advanceBy(SimulationContext context, long durationNs) {
        context.scheduleAfterNanoseconds(durationNs, EventType.PON_UPLINK_FRAME, null, 0);
        context.nextEvent();
    }

    private static List<String> run(Path directory, String enabled, List<String> properties) throws IOException {
        List<String> scenario = new ArrayList<>(List.of("fiber.uplink.mode=pon-framed"));
        if (enabled != null) {
            scenario.add("fiber.uplink.report.enabled=" + enabled);
        }
        scenario.addAll(properties);
        Path path = Files.write(directory.resolve("pon-uplink-report-delivery.properties"), scenario,
                StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static Map<String, String> state(List<String> trace, long frameId, int sfuId) {
        return single(trace, "event=PON_UPLINK_REPORT_STATE frame_id=" + frameId + " sfu_id=" + sfuId + " ");
    }

    private static void assertUnknown(Map<String, String> row, long boundaryNs) {
        assertFields(row, Map.of("status", "UNKNOWN", "boundary_time_ns", Long.toString(boundaryNs)));
        assertNull(row.get("latest_report_frame_id"));
        assertNull(row.get("known_pending_bytes"));
    }

    private static void assertKnown(Map<String, String> row, long boundaryNs, long reportFrameId, long snapshotNs,
                                    long rxNs, long pendingBytes, long eligibleBytes) {
        assertFields(row, Map.of("status", "KNOWN", "boundary_time_ns", Long.toString(boundaryNs),
                "latest_report_frame_id", Long.toString(reportFrameId), "latest_snapshot_time_ns",
                Long.toString(snapshotNs), "latest_receive_time_ns", Long.toString(rxNs),
                "known_pending_bytes", Long.toString(pendingBytes), "known_eligible_bytes", Long.toString(eligibleBytes),
                "report_age_ns", Long.toString(boundaryNs - snapshotNs)));
    }

    private static void assertReception(Map<String, String> row, long frameId, int sfuId, int carrierId,
                                        long snapshotNs, long rxNs, long demandBytes) {
        assertFields(row, Map.of("frame_id", Long.toString(frameId), "sfu_id", Integer.toString(sfuId), "packet_id",
                Integer.toString(carrierId), "snapshot_time_ns", Long.toString(snapshotNs), "receive_time_ns",
                Long.toString(rxNs), "pending_bytes", Long.toString(demandBytes), "eligible_bytes",
                Long.toString(demandBytes), "applied", "true"));
    }

    private static void assertFields(Map<String, String> actual, Map<String, String> expected) {
        expected.forEach((key, value) -> assertEquals(value, actual.get(key), key));
    }

    private static List<String> matching(List<String> trace, String fragment) {
        return trace.stream().filter(line -> line.contains(fragment)).collect(Collectors.toList());
    }

    private static List<Map<String, String>> reportReceptions(List<String> trace) {
        return matching(trace, "event=PON_UPLINK_REPORT_RX ").stream().map(PonUplinkReportDeliveryTest::fields)
                .collect(Collectors.toList());
    }

    private static int indexOf(List<String> trace, String fragment) {
        List<String> rows = matching(trace, fragment);
        assertEquals(1, rows.size(), fragment);
        return trace.indexOf(rows.get(0));
    }

    private static Map<String, String> single(List<String> trace, String fragment) {
        return fields(trace.get(indexOf(trace, fragment)));
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> result = new HashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            result.put(pair[0], pair[1]);
        }
        return result;
    }
}
