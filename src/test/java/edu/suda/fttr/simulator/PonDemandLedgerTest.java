package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonDemandLedgerTest {
    private static final String ENABLED = "fiber.uplink.demand.ledger.enabled=";

    @Test
    void reconcilesSixThousandBytesWithTwoUniqueGrantsBeforeReportReception() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, false, StandardCharsets.UTF_8)) {
            SimulationContext context = context(stream);
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            Packet alreadyReserved = uplink(1, 1, 1_200);
            Packet sameTickGrant = uplink(2, 1, 1_200);
            Packet laterGrant = uplink(3, 1, 1_200);
            Packet otherSfuGrant = uplink(4, 2, 700);
            PonUplinkReport report = report(1, 1, 125_000, 5, 6_000);

            advanceTo(context, 124_999);
            mfu.recordUplinkGrantCreated(1, alreadyReserved, context);
            advanceTo(context, 125_000);
            // 该纳秒先取样、后创建授权。账本包含同刻授权，排除早一纳秒的预约。
            mfu.recordUplinkGrantCreated(1, sameTickGrant, context);
            mfu.recordUplinkGrantCreated(1, sameTickGrant, context);
            advanceTo(context, 126_000);
            mfu.recordUplinkGrantCreated(1, laterGrant, context);
            mfu.recordUplinkGrantCreated(2, otherSfuGrant, context);
            assertFalse(mfu.estimateUplinkDemand(1, context.nowTicks()).known());
            advanceTo(context, 150_000);
            mfu.receiveUplinkReport(report, sameTickGrant, context);

            PonDemandLedger.Estimate estimate = mfu.estimateUplinkDemand(1, context.nowTicks());
            assertEstimate(estimate, report, 150_000, 2_400, 3_600, 25_000);
            assertFalse(mfu.estimateUplinkDemand(2, context.nowTicks()).known());
            assertEquals(6_000, report.pendingBytes());
        }
        List<Map<String, String>> grants = events(lines(output), "PON_DEMAND_LEDGER_GRANT");
        assertEquals(List.of("1", "2", "3", "4"), values(grants, "grant_id"));
    }

    @Test
    void newReportKeepsRelevantHistoryAndStaleOrRepeatedReportsDoNotRollBack() {
        try (PrintStream stream = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext context = context(stream);
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            Packet firstGrant = uplink(1, 1, 2_400);
            Packet secondGrant = uplink(2, 1, 1_200);
            Packet otherSfuGrant = uplink(3, 2, 400);
            PonUplinkReport first = report(1, 1, 125_000, 5, 6_000);
            PonUplinkReport newer = report(2, 1, 250_000, 3, 3_600);
            PonUplinkReport other = report(2, 2, 250_000, 2, 1_000);

            advanceTo(context, 125_000);
            mfu.recordUplinkGrantCreated(1, firstGrant, context);
            advanceTo(context, 170_000);
            mfu.receiveUplinkReport(first, firstGrant, context);
            assertEstimate(mfu.estimateUplinkDemand(1, context.nowTicks()), first, 170_000, 2_400, 3_600, 45_000);

            advanceTo(context, 250_000);
            mfu.recordUplinkGrantCreated(1, secondGrant, context);
            mfu.recordUplinkGrantCreated(2, otherSfuGrant, context);
            advanceTo(context, 280_000);
            mfu.receiveUplinkReport(newer, secondGrant, context);
            mfu.receiveUplinkReport(other, otherSfuGrant, context);
            assertEstimate(mfu.estimateUplinkDemand(1, context.nowTicks()), newer, 280_000, 1_200, 2_400, 30_000);
            assertEstimate(mfu.estimateUplinkDemand(2, context.nowTicks()), other, 280_000, 400, 600, 30_000);

            advanceTo(context, 290_000);
            mfu.receiveUplinkReport(first, firstGrant, context);
            mfu.receiveUplinkReport(report(2, 1, 250_000, 8, 9_600), secondGrant, context);
            assertEstimate(mfu.estimateUplinkDemand(1, context.nowTicks()), newer, 280_000, 1_200, 2_400, 40_000);
            assertEstimate(mfu.estimateUplinkDemand(2, context.nowTicks()), other, 280_000, 400, 600, 40_000);
        }
    }

    @Test
    void preservesUnknownSeparatelyFromAZeroReportAndExhaustedKnownDemand() {
        try (PrintStream stream = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext context = context(stream);
            Mfu mfu = new Mfu(List.of(1, 2, 3), null, true);
            Packet nonempty = uplink(1, 1, 1_200);
            Packet empty = uplink(2, 2, 1_200);
            PonUplinkReport withDemand = report(1, 1, 125_000, 1, 1_200);
            PonUplinkReport withZeroReportedBytes = report(1, 2, 125_000, 0, 0);

            advanceTo(context, 125_000);
            mfu.recordUplinkGrantCreated(1, nonempty, context);
            advanceTo(context, 130_000);
            mfu.receiveUplinkReport(withDemand, nonempty, context);
            // 使用合成接收覆盖 0 B 快照。现有捎带模型中，全空队列得不到发送该快照的时隙。
            mfu.receiveUplinkReport(withZeroReportedBytes, empty, context);
            assertEstimate(mfu.estimateUplinkDemand(1, context.nowTicks()), withDemand, 130_000, 1_200, 0, 5_000);
            assertEstimate(mfu.estimateUplinkDemand(2, context.nowTicks()), withZeroReportedBytes, 130_000,
                    0, 0, 5_000);

            PonDemandLedger.Estimate unknown = mfu.estimateUplinkDemand(3, context.nowTicks());
            assertFalse(unknown.known());
            assertNull(unknown.report());
            assertThrows(IllegalStateException.class, unknown::effectiveKnownBytes);

            advanceTo(context, 135_000);
            mfu.recordUplinkGrantCreated(1, uplink(3, 1, 800), context);
            assertEstimate(mfu.estimateUplinkDemand(1, context.nowTicks()), withDemand, 130_000, 2_000, 0, 10_000);
        }
    }

    @Test
    void countsReservationsOnceAndWaitsForReportedNewDemandInTheLiveBwmapRun(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, "true", List.of(
                "traffic.direction=UL", "fiber.uplink.report.enabled=true", "fiber.uplink.bwmap.enabled=true",
                "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=10",
                "fiber.uplink.propagation.us=80", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "wifi.delay.us=0",
                "packet.arrival.us=0,0,0,0,0,0,0,340", "packet.bytes=1200",
                "packet.3.sta.id=2", "packet.3.bytes=36500", "packet.5.sta.id=2", "packet.5.bytes=37000",
                "packet.8.bytes=1000"));

        List<Map<String, String>> grants = events(trace, "PON_DEMAND_LEDGER_GRANT");
        assertEquals(8, grants.size());
        assertEquals(8, values(grants, "grant_id").stream().distinct().count());
        assertEquals(values(events(trace, "PON_BWMAP_TX"), "packet_id"), values(grants, "grant_id"));
        assertEquals(values(grants, "grant_id"), values(events(trace, "PON_BWMAP_RX"), "packet_id"));
        assertEquals(values(grants, "grant_id"), values(events(trace, "FIBER_TX_START"), "packet_id"));
        assertEquals(List.of("125000", "125000", "125000", "250000", "250000", "375000", "375000", "375000"),
                values(grants, "grant_create_ns"));
        assertEquals(8, events(trace, "DELIVERED").size());

        Map<String, String> snapshot = find(events(trace, "PON_UPLINK_REPORT"),
                Map.of("frame_id", "1", "sfu_id", "1"));
        assertEquals("6000", snapshot.get("pending_bytes"));
        String initialSnapshot = "event=PON_UPLINK_REPORT frame_id=1 sfu_id=1 ";
        assertTrue(index(trace, initialSnapshot)
                < index(trace, "event=PON_DEMAND_LEDGER_GRANT sfu_id=1 grant_id=1 "));
        assertUnknown(state(trace, 250_000, 1, "FRAME_BOUNDARY"));
        assertLedger(state(trace, 333_840, 1, "REPORT_ACCEPTED"), 1, 125_000, 333_840, 6_000, 3_600, 2_400, 208_840);

        // 第 8 包入队后，本地需求为 3400 B。此刻 MFU 仍只知道首份报告的估计值 2400 B。
        assertEquals("3400", find(events(trace, "PON_UPLINK_REPORT"),
                Map.of("frame_id", "3", "sfu_id", "1")).get("pending_bytes"));
        assertLedger(state(trace, 375_000, 1, "FRAME_BOUNDARY"), 1, 125_000, 333_840, 6_000, 3_600, 2_400, 250_000);
        assertUnknown(state(trace, 375_000, 2, "FRAME_BOUNDARY"));
        assertLedger(state(trace, 450_640, 2, "REPORT_ACCEPTED"), 1, 125_000, 450_640, 73_500, 73_500, 0, 325_640);
        assertLedger(state(trace, 577_240, 1, "REPORT_ACCEPTED"), 2, 250_000, 577_240, 3_600, 4_600, 0, 327_240);
        assertLedger(state(trace, 583_840, 1, "REPORT_ACCEPTED"), 3, 375_000, 583_840, 3_400, 3_400, 0, 208_840);
    }

    @Test
    void enabledLedgerPreservesTheFullMixedEdcaBwmapAndReportTrace(@TempDir Path directory) throws IOException {
        List<String> properties = List.of("traffic.direction=BOTH", "fiber.uplink.report.enabled=true",
                "fiber.uplink.bwmap.enabled=true", "wifi.model=he-su", "wifi.mac.exchange=data-sifs-ack",
                "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.cwmax=0",
                "fiber.downlink.mode=pon-shared", "fiber.downlink.propagation.us=5", "fiber.uplink.propagation.us=10.025",
                "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.arrival.us=0,0,130,140", "packet.bytes=1200",
                "packet.1.direction=UL", "packet.1.type=VO", "packet.2.direction=DL", "packet.2.type=VO",
                "packet.2.sta.id=2", "packet.3.direction=UL", "packet.3.type=VO", "packet.4.direction=UL",
                "packet.4.type=VO", "packet.4.sta.id=2");
        List<String> enabled = run(directory, "true", properties);
        List<String> disabled = run(directory, "false", properties);
        assertEquals(disabled, run(directory, null, properties));
        assertEquals(disabled, enabled.stream().filter(line -> !line.contains("event=PON_DEMAND_LEDGER ")
                && !line.contains("event=PON_DEMAND_LEDGER_GRANT ")).collect(Collectors.toList()));
        assertEquals(4, events(enabled, "DELIVERED").size());
        assertEquals(4, events(enabled, "WIFI_ACK_END").size());
        assertEquals(3, events(enabled, "PON_BWMAP_RX").size());
        assertEquals(3, events(enabled, "PON_UPLINK_REPORT_RX").size());
    }

    @Test
    void ledgerWithReportsDisabledContinuesToTreatTheSfuAsUnknown(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "true", List.of("traffic.direction=UL", "wifi.delay.us=0",
                "packet.arrival.us=0", "packet.bytes=1200"));
        assertTrue(events(trace, "PON_UPLINK_REPORT_RX").isEmpty());
        assertEquals(1, events(trace, "PON_DEMAND_LEDGER_GRANT").size());
        assertUnknown(state(trace, 125_000, 1, "FRAME_BOUNDARY"));
        assertUnknown(state(trace, 125_000, 1, "GRANT_CREATED"));
    }

    private static SimulationContext context(PrintStream output) {
        return new SimulationContext(new TraceLogger(output), true, true);
    }

    private static Packet uplink(int id, int sfuId, int bytes) {
        return new Packet(id, bytes, 0, AccessCategory.BE, Packet.Direction.UL, sfuId, Packet.DEFAULT_MFU_ID);
    }

    private static PonUplinkReport report(long frame, int sfuId, long snapshotNs, int packets, long bytes) {
        return new PonUplinkReport(frame, sfuId, snapshotNs, packets, bytes, packets, bytes);
    }

    private static void advanceTo(SimulationContext context, long timeNs) {
        context.scheduleAfterNanoseconds(timeNs - context.nowTicks(), EventType.PON_UPLINK_FRAME, null, 0);
        context.nextEvent();
    }

    private static void assertEstimate(PonDemandLedger.Estimate estimate, PonUplinkReport report, long receiveNs,
                                       long grantedBytes, long effectiveBytes, long ageNs) {
        assertTrue(estimate.known());
        assertSame(report, estimate.report().report());
        assertEquals(receiveNs, estimate.report().receiveTimeNs());
        assertEquals(grantedBytes, estimate.grantedBytesSinceSnapshot());
        assertEquals(effectiveBytes, estimate.effectiveKnownBytes());
        assertEquals(ageNs, estimate.reportAgeNs());
    }

    private static void assertLedger(Map<String, String> row, long frame, long snapshotNs, long receiveNs,
                                     long pendingBytes, long grantedBytes, long effectiveBytes, long ageNs) {
        assertEquals("KNOWN", row.get("state"));
        assertEquals(Long.toString(frame), row.get("latest_report_frame_id"));
        assertEquals(Long.toString(snapshotNs), row.get("report_snapshot_ns"));
        assertEquals(Long.toString(receiveNs), row.get("report_receive_ns"));
        assertEquals(Long.toString(pendingBytes), row.get("reported_pending_bytes"));
        assertEquals(Long.toString(grantedBytes), row.get("granted_bytes_since_snapshot"));
        assertEquals(Long.toString(effectiveBytes), row.get("effective_known_bytes"));
        assertEquals(Long.toString(ageNs), row.get("report_age_ns"));
    }

    private static void assertUnknown(Map<String, String> row) {
        assertEquals("UNKNOWN", row.get("state"));
        assertNull(row.get("reported_pending_bytes"));
        assertNull(row.get("effective_known_bytes"));
    }

    private static List<String> run(Path directory, String enabled, List<String> properties) throws IOException {
        List<String> settings = new ArrayList<>(List.of("fiber.uplink.mode=pon-framed"));
        if (enabled != null) {
            settings.add(ENABLED + enabled);
        }
        settings.addAll(properties);
        Path path = Files.write(directory.resolve("pon-demand-ledger.properties"), settings, StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(stream));
        }
        return lines(buffer);
    }

    private static List<String> lines(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<String> trace, String action) {
        return trace.stream().filter(line -> line.contains("event=" + action + " "))
                .map(PonDemandLedgerTest::fields).collect(Collectors.toList());
    }

    private static List<String> values(List<Map<String, String>> events, String field) {
        return events.stream().map(row -> row.get(field)).collect(Collectors.toList());
    }

    private static Map<String, String> state(List<String> trace, long timeNs, int sfuId, String trigger) {
        return find(events(trace, "PON_DEMAND_LEDGER"), Map.of("time_ns", Long.toString(timeNs),
                "sfu_id", Integer.toString(sfuId), "trigger", trigger));
    }

    private static Map<String, String> find(List<Map<String, String>> rows, Map<String, String> expected) {
        List<Map<String, String>> matches = rows.stream().filter(row -> row.entrySet().containsAll(expected.entrySet()))
                .collect(Collectors.toList());
        assertEquals(1, matches.size(), expected.toString());
        return matches.get(0);
    }

    private static int index(List<String> trace, String fragment) {
        List<String> matches = trace.stream().filter(line -> line.contains(fragment)).collect(Collectors.toList());
        assertEquals(1, matches.size(), fragment);
        return trace.indexOf(matches.get(0));
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
