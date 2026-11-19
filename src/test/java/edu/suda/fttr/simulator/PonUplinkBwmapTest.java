package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.PonUplink;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonUplinkBwmapTest {
    private static final String ENABLED = "fiber.uplink.bwmap.enabled=";

    @Test
    void receivesAFirstGrantBeforeItsNextFrameSlot(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = parse(run(directory, "true", List.of(
                "fiber.downlink.propagation.us=10", "fiber.uplink.propagation.us=4.025",
                "packet.arrival.us=0", "packet.bytes=1200")));

        assertGrant(only(events(trace, "PON_BWMAP_TX", "MFU")), 1, 1, 1, 250_000, 253_840, 1200);
        Map<String, String> reception = only(events(trace, "PON_BWMAP_RX", "SFU"));
        assertGrant(reception, 1, 1, 1, 250_000, 253_840, 1200);
        assertEquals("135000", reception.get("grant_rx_ns"));
        assertEquals(135_000, atNs(reception));
        assertTimeline(List.of("1@0"), events(trace, "PON_UPLINK_ENQUEUE", "SFU"));
        assertTimeline(List.of("1@250000"), events(trace, "FIBER_TX_START", "SFU"));
        assertTimeline(List.of("1@253840"), events(trace, "FIBER_TX_END", "SFU"));
        assertTimeline(List.of("1@257865"), events(trace, "DELIVERED", "MFU"));
        assertEquals(125_000, atNs(only(events(trace, "PON_BWMAP_TX", "MFU"))));

        Map<String, String> summary = only(summaries(trace, "PON_BWMAP"));
        assertEquals("1", summary.get("packets"));
        assertEquals(125, Double.parseDouble(summary.get("avg_ungranted_wait_us")));
        assertEquals(125, Double.parseDouble(summary.get("avg_reserved_wait_us")));
        assertEquals(3.84, Double.parseDouble(summary.get("avg_serialization_us")), 1e-9);
    }

    @Test
    void holdsThirtyFivePacketsToNonOverlappingSlotsAndReportsOnlyUnreservedDemand(@TempDir Path directory)
            throws IOException {
        int packetCount = 35;
        List<String> settings = new ArrayList<>(List.of("fiber.downlink.propagation.us=10",
                "fiber.uplink.propagation.us=4.025", "fiber.uplink.report.enabled=true",
                "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.bytes=1200",
                "packet.arrival.us=" + String.join(",", Collections.nCopies(packetCount, "0"))));
        for (int packetId = 1; packetId <= packetCount; packetId += 2) {
            settings.add("packet." + packetId + ".sta.id=2");
        }
        List<String> firstRun = run(directory, "true", settings);
        assertEquals(firstRun, run(directory, "true", settings));
        List<Map<String, String>> trace = parse(firstRun);
        List<Map<String, String>> grants = events(trace, "PON_BWMAP_TX", "MFU");
        List<Map<String, String>> receptions = events(trace, "PON_BWMAP_RX", "SFU");
        List<Map<String, String>> starts = events(trace, "FIBER_TX_START", "SFU");
        List<Map<String, String>> ends = events(trace, "FIBER_TX_END", "SFU");
        List<Map<String, String>> deliveries = events(trace, "DELIVERED", "MFU");
        List<String> packets = IntStream.rangeClosed(1, packetCount).mapToObj(Integer::toString)
                .collect(Collectors.toList());
        // 用完整 ID 列表核对授权、收图、发送与交付各一次，包含第 33 包顺延的情况。
        for (List<Map<String, String>> phase : List.of(grants, receptions, starts, ends, deliveries)) {
            assertEquals(packets, phase.stream().map(row -> row.get("packet_id")).collect(Collectors.toList()));
        }

        long previousEnd = 0;
        for (int index = 0; index < packetCount; index++) {
            long grantFrame = index / 32 + 1L;
            long startNs = (grantFrame + 1) * 125_000 + index % 32 * 3_840L;
            long endNs = startNs + 3_840;
            int sfuId = index % 2 == 0 ? 2 : 1;
            assertGrant(grants.get(index), grantFrame, index + 1, sfuId, startNs, endNs, 1200);
            assertGrant(receptions.get(index), grantFrame, index + 1, sfuId, startNs, endNs, 1200);
            assertEquals(grantFrame * 125_000 + 10_000, atNs(receptions.get(index)));
            assertTrue(atNs(receptions.get(index)) < atNs(starts.get(index)));
            assertTrue(previousEnd <= atNs(starts.get(index)), "跨 SFU 时隙不可重叠");
            assertEquals(startNs, atNs(starts.get(index)));
            assertEquals(endNs, atNs(ends.get(index)));
            assertEquals(endNs + 4_025, atNs(deliveries.get(index)));
            assertEquals(Integer.toString(sfuId), deliveries.get(index).get("sfu_id"));
            previousEnd = endNs;
        }
        assertFrame(trace, 1, 0, 0);
        assertFrame(trace, 2, 32, 122_880);
        assertFrame(trace, 3, 3, 11_520);
        assertDemand(trace, 1, 1, 17, 17);
        assertDemand(trace, 1, 2, 18, 18);
        assertDemand(trace, 2, 1, 1, 1);
        assertDemand(trace, 2, 2, 2, 2);
        assertDemand(trace, 3, 1, 0, 0);
        assertDemand(trace, 3, 2, 0, 0);

        List<Map<String, String>> reports = events(trace, "PON_UPLINK_REPORT_RX", "MFU");
        assertTimeline(List.of("1@257865", "2@261705", "33@382865", "34@386705"), reports);
        assertFields(reports.get(0), Map.of("frame_id", "1", "sfu_id", "2", "snapshot_time_ns", "125000",
                "pending_bytes", "21600", "eligible_bytes", "21600"));
        assertFields(reports.get(2), Map.of("frame_id", "2", "sfu_id", "2", "snapshot_time_ns", "250000",
                "pending_bytes", "2400", "eligible_bytes", "2400"));
    }

    @Test
    void defersSameBoundaryAndLaterEnqueuesWithoutReadingFutureDemand(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = parse(run(directory, "true", List.of(
                "fiber.downlink.propagation.us=10", "fiber.uplink.report.enabled=true", "wifi.delay.us=100",
                "sfu.count=3", "sta.count=3", "sta.2.sfu.id=2", "sta.3.sfu.id=3",
                "packet.arrival.us=0,25,26", "packet.bytes=1200", "packet.2.sta.id=2", "packet.3.sta.id=3")));

        assertTimeline(List.of("1@100000", "2@125000", "3@126000"), events(trace, "PON_UPLINK_ENQUEUE", "SFU"));
        List<Map<String, String>> grants = events(trace, "PON_BWMAP_TX", "MFU");
        assertEquals(3, grants.size());
        assertGrant(grants.get(0), 1, 1, 1, 250_000, 253_840, 1200);
        assertGrant(grants.get(1), 2, 2, 2, 375_000, 378_840, 1200);
        assertGrant(grants.get(2), 2, 3, 3, 378_840, 382_680, 1200);
        assertDemand(trace, 1, 2, 1, 0);
        assertDemand(trace, 1, 3, 0, 0);
        assertDemand(trace, 2, 1, 0, 0);
    }

    @Test
    void allowsAnExactFrameEndToMeetTheNextAlreadyReceivedGrant(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = parse(run(directory, "true", List.of(
                "fiber.downlink.propagation.us=124.999", "fiber.uplink.rate.mbps=8000",
                "packet.arrival.us=0,0,0", "packet.bytes=125000", "packet.3.bytes=1200")));

        // 前两包各占满一个目标帧，后一帧不能在前一帧结束事件前启动。
        assertTimeline(List.of("1@249999", "2@374999", "3@499999"), events(trace, "PON_BWMAP_RX", "SFU"));
        assertTimeline(List.of("1@250000", "2@375000", "3@500000"), events(trace, "FIBER_TX_START", "SFU"));
        assertTimeline(List.of("1@375000", "2@500000", "3@501200"), events(trace, "FIBER_TX_END", "SFU"));
        List<Map<String, String>> transitions = trace.stream()
                .filter(row -> "SFU".equals(row.get("node")) && atNsIfTimed(row, 375_000)
                        && List.of("FIBER_TX_END", "FIBER_TX_START").contains(row.get("event")))
                .collect(Collectors.toList());
        assertEquals(List.of("FIBER_TX_END", "FIBER_TX_START"),
                transitions.stream().map(row -> row.get("event")).collect(Collectors.toList()));
        assertFrame(trace, 2, 1, 125_000);
        assertFrame(trace, 3, 1, 125_000);
        assertFrame(trace, 4, 1, 1200);
    }

    @Test
    void rejectsSendingAnIdentifiedReservationWithoutReceivingItsMap() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(stream), true, true);
            PonUplink uplink = new PonUplink(BigDecimal.valueOf(2500), 0, 125_000, false, 10_000);
            Packet packet = new Packet(1, 1200, 0, AccessCategory.BE, Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID);
            PonUplink.Port port = uplink.attachSfu(1);
            port.enqueue(packet, context);
            Event frame = context.nextEvent();
            assertEquals(EventType.PON_UPLINK_FRAME, frame.type());
            uplink.scheduleFrame(context);

            // 人工丢弃接收事件并注入一个定时正确的上行启动请求，单独验证接收门槛。
            context.scheduleAfterNanoseconds(125_000, EventType.FIBER_TX_START, packet, 1);
            Event event = context.nextEvent();
            while (event != null && event.type() != EventType.FIBER_TX_START) {
                event = context.nextEvent();
            }
            assertNotNull(event);
            assertEquals(250_000, event.timeTicks());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> uplink.start(packet, 1, context));
            assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("bwmap"), failure.getMessage());

            IllegalStateException lateReception = assertThrows(IllegalStateException.class,
                    () -> port.receiveBwmap(packet, context));
            assertTrue(lateReception.getMessage().toLowerCase(Locale.ROOT).contains("bwmap"),
                    lateReception.getMessage());
        }
    }

    @Test
    void bwmapSharesTheDownlinkPropagationTimeWithoutConsumingDownlinkDataSlots(@TempDir Path directory)
            throws IOException {
        List<String> settings = List.of("traffic.direction=BOTH", "wifi.model=he-su",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.VO.cwmin=0",
                "wifi.mac.edca.VO.cwmax=0", "fiber.downlink.propagation.us=10", "fiber.uplink.propagation.us=4.025",
                "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.arrival.us=0,125", "packet.bytes=1200",
                "packet.1.direction=UL", "packet.1.type=VO", "packet.2.direction=DL", "packet.2.type=VO",
                "packet.2.sta.id=2");
        List<Map<String, String>> enabled = parse(run(directory, "true", settings));
        List<Map<String, String>> disabled = parse(run(directory, "false", settings));
        assertTimeline(List.of("1@125000"), events(enabled, "PON_BWMAP_TX", "MFU"));
        assertTimeline(List.of("1@135000"), events(enabled, "PON_BWMAP_RX", "SFU"));
        assertTimeline(List.of("2@125000"), events(enabled, "FIBER_TX_START", "MFU"));
        assertTimeline(List.of("2@125960"), events(enabled, "FIBER_TX_END", "MFU"));
        assertTimeline(List.of("2@135960"), events(enabled, "ARRIVAL", "SFU"));
        assertTimeline(List.of("2@233960"), events(enabled, "DELIVERED", "STA"));
        assertEquals(events(disabled, "DELIVERED", "STA"), events(enabled, "DELIVERED", "STA"));
        assertEquals(events(disabled, "WIFI_ACK_END", "STA"), events(enabled, "WIFI_ACK_END", "STA"));
        assertEquals(events(disabled, "WIFI_ACK_END", "SFU"), events(enabled, "WIFI_ACK_END", "SFU"));
        assertEquals(summaries(disabled, "PON_DOWNLINK"), summaries(enabled, "PON_DOWNLINK"));
    }

    @Test
    void defaultsToTheIdenticalImmediateAuthorizationWithReportDelivery(@TempDir Path directory) throws IOException {
        List<String> settings = List.of("fiber.downlink.propagation.us=10", "fiber.uplink.report.enabled=true",
                "fiber.uplink.propagation.us=10.025", "packet.arrival.us=0,0,125", "packet.bytes=1200");
        List<String> disabled = run(directory, "false", settings);
        assertEquals(disabled, run(directory, null, settings));
        List<Map<String, String>> trace = parse(disabled);
        assertTrue(events(trace, "PON_BWMAP_TX", "MFU").isEmpty());
        assertTrue(events(trace, "PON_BWMAP_RX", "SFU").isEmpty());
        assertTrue(summaries(trace, "PON_BWMAP").isEmpty());
        assertTimeline(List.of("1@125000", "2@128840", "3@250000"), events(trace, "FIBER_TX_START", "SFU"));
        assertTimeline(List.of("1@138865", "2@142705", "3@263865"), events(trace, "DELIVERED", "MFU"));
        assertTimeline(List.of("1@138865", "3@263865"), events(trace, "PON_UPLINK_REPORT_RX", "MFU"));
    }

    @Test
    void rejectsControlPropagationAtOrBeyondTheFramePeriod(@TempDir Path directory) {
        for (String value : List.of("125", "125.001", "-0.001")) {
            assertThrows(IllegalArgumentException.class, () -> run(directory, "true", List.of(
                    "fiber.downlink.propagation.us=" + value, "packet.arrival.us=0", "packet.bytes=1200")), value);
        }
        assertThrows(IllegalArgumentException.class, () -> run(directory, "yes", List.of(
                "fiber.downlink.propagation.us=10", "packet.arrival.us=0", "packet.bytes=1200")));
    }

    @Test
    void finishesAReportOnlyBoundaryWithoutCalculatingAnUnusedTargetFrame(@TempDir Path directory)
            throws IOException {
        // 目标帧 2 的末尾仍在 long 范围内。末帧仅取样，不需要计算超出范围的帧 3 末尾。
        List<Map<String, String>> trace = parse(run(directory, "true", List.of(
                "fiber.uplink.frame.period.us=3000000000000000", "fiber.uplink.report.enabled=true",
                "fiber.downlink.propagation.us=10", "packet.arrival.us=0", "packet.bytes=1200")));

        assertTimeline(List.of("1@6000000000000000000"), events(trace, "FIBER_TX_START", "SFU"));
        assertTimeline(List.of("1@6000000000000003840"), events(trace, "DELIVERED", "MFU"));
        assertDemand(trace, 2, 1, 0, 0);
        assertEquals(1, events(trace, "PON_BWMAP_TX", "MFU").size());
    }

    private static List<String> run(Path directory, String enabled, List<String> properties) throws IOException {
        List<String> input = new ArrayList<>(List.of("fiber.uplink.mode=pon-framed", "fiber.downlink.mode=pon-shared"));
        if (properties.stream().noneMatch(property -> property.startsWith("traffic.direction="))) {
            input.add("traffic.direction=UL");
        }
        if (enabled != null) {
            input.add(ENABLED + enabled);
        }
        input.addAll(properties);
        if (properties.stream().noneMatch(property -> property.startsWith("wifi.delay.us=")
                || property.startsWith("wifi.model="))) {
            input.add("wifi.delay.us=0");
        }
        Path path = Files.write(directory.resolve("pon-uplink-bwmap.properties"), input, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(stream));
        }
        return output.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> parse(List<String> lines) {
        return lines.stream().map(line -> {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String token : line.split(" ")) {
                String[] parts = token.split("=", 2);
                fields.put(parts[0], parts[1]);
            }
            return fields;
        }).collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event, String node) {
        return trace.stream().filter(row -> event.equals(row.get("event")) && node.equals(row.get("node")))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> summaries(List<Map<String, String>> trace, String summary) {
        return trace.stream().filter(row -> summary.equals(row.get("summary"))).collect(Collectors.toList());
    }

    private static Map<String, String> only(List<Map<String, String>> rows) {
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0);
    }

    private static long atNs(Map<String, String> row) {
        return new BigDecimal(row.get("t_us")).movePointRight(3).longValueExact();
    }

    private static boolean atNsIfTimed(Map<String, String> row, long timeNs) {
        return row.containsKey("t_us") && atNs(row) == timeNs;
    }

    private static void assertTimeline(List<String> expected, List<Map<String, String>> rows) {
        assertEquals(expected, rows.stream().map(row -> row.get("packet_id") + "@" + atNs(row))
                .collect(Collectors.toList()));
    }

    private static void assertGrant(Map<String, String> row, long grantFrame, int packetId, int sfuId,
                                    long startNs, long endNs, int bytes) {
        assertFields(row, Map.of("grant_frame_id", Long.toString(grantFrame),
                "target_frame_id", Long.toString(grantFrame + 1), "packet_id", Integer.toString(packetId),
                "sfu_id", Integer.toString(sfuId), "grant_tx_ns", Long.toString(grantFrame * 125_000),
                "tx_start_ns", Long.toString(startNs), "tx_end_ns", Long.toString(endNs),
                "granted_bytes", Integer.toString(bytes)));
    }

    private static void assertFrame(List<Map<String, String>> trace, long frameId, int packets, long busyNs) {
        Map<String, String> frame = only(summaries(trace, "PON_UPLINK_FRAME").stream()
                .filter(row -> Long.toString(frameId).equals(row.get("frame_id"))).collect(Collectors.toList()));
        assertFields(frame, Map.of("frame_start_ns", Long.toString(frameId * 125_000),
                "frame_end_ns", Long.toString((frameId + 1) * 125_000), "allocated_packets", Integer.toString(packets),
                "busy_ns", Long.toString(busyNs)));
    }

    private static void assertDemand(List<Map<String, String>> trace, int frameId, int sfuId,
                                     int pending, int eligible) {
        Map<String, String> report = only(events(trace, "PON_UPLINK_REPORT", "SFU").stream()
                .filter(row -> Integer.toString(frameId).equals(row.get("frame_id"))
                        && Integer.toString(sfuId).equals(row.get("sfu_id"))).collect(Collectors.toList()));
        assertFields(report, Map.of("pending_packets", Integer.toString(pending),
                "pending_bytes", Integer.toString(1200 * pending), "eligible_packets", Integer.toString(eligible),
                "eligible_bytes", Integer.toString(1200 * eligible)));
    }

    private static void assertFields(Map<String, String> actual, Map<String, String> expected) {
        expected.forEach((key, value) -> assertEquals(value, actual.get(key), key + " in " + actual));
    }
}
