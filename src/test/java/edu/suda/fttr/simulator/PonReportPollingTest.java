package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.link.PonReportPoll;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonReportPollingTest {
    @Test
    void emptyQueuesReturnZeroReportsInRoundRobinOrderAndTerminate(@TempDir Path directory) throws IOException {
        String[] extra = emptyTraffic();
        List<String> log = run(directory, true, extra);
        assertEquals(log, run(directory, true, extra));
        List<Map<String, String>> polls = events(log, "PON_POLL_BWMAP_TX");
        assertEquals(List.of("1", "2", "1", "2"), values(polls, "sfu_id"));
        assertEquals(List.of("R1", "R2", "R3", "R4"), values(polls, "grant_id"));
        assertTrue(polls.stream().noneMatch(row -> row.containsKey("packet_id")));
        List<Map<String, String>> received = events(log, "PON_POLL_REPORT_RX");
        assertEquals(4, received.size());
        for (Map<String, String> report : received) {
            assertEquals("0", report.get("pending_bytes"));
            assertEquals("true", report.get("applied"));
        }
        assertEquals("0", summary(log, "UL_GLOBAL").get("delivered_packets"));
        assertEquals("0", summary(log, "PON_UPLINK").get("sent_packets"));
        assertTrue(events(log, "PON_DEMAND_LEDGER_GRANT").isEmpty());
        assertTrue(log.get(log.size() - 1).endsWith("simulation_end_us=635.23"));
        assertEquals("820", summary(log, "PON_POLLING").get("report_busy_ns"));
        assertTrue(events(log, "PON_DEMAND_LEDGER").stream().anyMatch(row ->
                "385230".equals(row.get("time_ns")) && "KNOWN".equals(row.get("state"))
                        && "0".equals(row.get("effective_known_bytes"))));
    }

    @Test
    void reportsActualBufferedDemandBeforeAnyDataGrantAndDebitsOnlyData(@TempDir Path directory) throws IOException {
        List<String> log = run(directory, true, "packet.arrival.us=0", "packet.bytes=39062");
        Map<String, String> first = events(log, "PON_POLL_REPORT_RX").get(0);
        assertEquals("39062", first.get("pending_bytes"));
        assertEquals("260230", first.get("receive_time_ns"));
        assertEquals("625000", events(log, "PON_UPLINK_GRANT").get(0).get("grant_time_ns"));
        List<Map<String, String>> debits = events(log, "PON_DEMAND_LEDGER_GRANT");
        assertEquals(1, debits.size());
        assertEquals("39062", debits.get(0).get("grant_bytes"));
        assertEquals("1", summary(log, "PON_UPLINK").get("sent_packets"));
        assertEquals("39062", summary(log, "UL_GLOBAL").get("delivered_bytes"));
        assertEquals("125819", summary(log, "PON_POLLING").get("total_busy_ns"));
        assertEquals("885.024", finalFields(log).get("simulation_end_us"));
        Map<String, String> before = events(log, "PON_DEMAND_LEDGER").stream()
                .filter(row -> "260230".equals(row.get("time_ns"))).findFirst().orElseThrow();
        assertEquals("39062", before.get("effective_known_bytes"));
        assertEquals("0", before.get("granted_bytes_since_snapshot"));
    }

    @Test
    void controlOccupies205NanosecondsBeforeNonOverlappingDataSlots(@TempDir Path directory) throws IOException {
        List<String> log = run(directory, true, "packet.arrival.us=0,0", "packet.bytes=1200", "packet.2.sta.id=2");
        Map<String, String> poll = events(log, "PON_POLL_BWMAP_TX").get(0);
        assertEquals("125000", poll.get("bwmap_tx_ns"));
        assertEquals("250000", poll.get("tx_start_ns"));
        assertEquals("250205", poll.get("tx_end_ns"));
        assertEquals("135000", events(log, "PON_POLL_BWMAP_RX").get(0).get("bwmap_rx_ns"));
        assertEquals(List.of("250.205", "254.045"), values(events(log, "FIBER_TX_START"), "t_us"));
        Map<String, String> frame = summaries(log, "PON_POLL_FRAME").stream()
                .filter(row -> "2".equals(row.get("frame_id"))).findFirst().orElseThrow();
        assertEquals("7680", frame.get("data_busy_ns"));
        assertEquals("205", frame.get("report_busy_ns"));
        assertEquals("7885", frame.get("busy_ns"));
        assertEquals("117115", frame.get("idle_ns"));
        assertEquals("2", summary(log, "UL_GLOBAL").get("delivered_packets"));
        assertEquals(2, events(log, "PON_DEMAND_LEDGER_GRANT").size());
        // 两包在前一帧已预约，实际轮询时的待授权需求为零。迟到的旧 DATA 快照不能覆盖它。
        assertEquals("0", events(log, "PON_POLL_REPORT_RX").get(0).get("pending_bytes"));
        assertEquals("false", events(log, "PON_UPLINK_REPORT_RX").get(0).get("applied"));
    }

    @Test
    void takesSnapshotAtActualSlotIncludingNewUnreservedData(@TempDir Path directory) throws IOException {
        List<String> log = run(directory, true, "packet.arrival.us=200", "packet.bytes=1200");
        Map<String, String> report = events(log, "PON_POLL_REPORT").get(0);
        assertEquals("250000", report.get("snapshot_time_ns"));
        assertEquals("1200", report.get("pending_bytes"));
        assertEquals("250000", events(log, "PON_UPLINK_GRANT").get(0).get("grant_time_ns"));
        assertEquals("REPORT_ONLY", events(log, "PON_POLL_BWMAP_TX").get(0).get("grant_type"));
    }

    @Test
    void intervalSizeAndAssociatedSfusControlThePollingPlan(@TempDir Path directory) throws IOException {
        List<String> log = run(directory, true, "traffic.mode=poisson", "traffic.duration.us=1",
                "sfu.count=3", "sta.1.sfu.id=2", "sta.2.sfu.id=3", "fiber.uplink.poll.interval.frames=2",
                "fiber.uplink.poll.bytes=125", "fiber.uplink.poll.end.us=750");
        assertEquals(List.of("2", "3", "2"), values(events(log, "PON_POLL_BWMAP_TX"), "sfu_id"));
        assertEquals(List.of("125000", "375000", "625000"),
                values(events(log, "PON_POLL_BWMAP_TX"), "bwmap_tx_ns"));
        assertEquals("1200", summary(log, "PON_POLLING").get("report_busy_ns"));
    }

    @Test
    void rejectsExecutionBeforeReceivingTheControlBwmap(@TempDir Path directory) throws IOException {
        SimulationConfig config = ConfigLoader.load(write(directory, settings(true, emptyTraffic())));
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            PonUplink uplink = new PonUplink(BigDecimal.valueOf(2500), 0, 125000, true, 10000,
                    config.ponUplink().polling());
            uplink.attachSfu(1);
            uplink.startPolling(List.of(1), context);
            assertEquals(EventType.PON_UPLINK_FRAME, context.nextEvent().type());
            uplink.scheduleFrame(context);
            Event next = context.nextEvent(); // 故意跳过 BWmap 接收，验证发送端的授权门槛。
            while (next.type() != EventType.PON_POLL_START) {
                next = context.nextEvent();
            }
            PonReportPoll poll = next.reportPoll();
            assertThrows(IllegalStateException.class, () -> uplink.startPoll(poll, context));
        }
    }

    @Test
    void disabledPollingMatchesThePreviousFullTrace(@TempDir Path directory) throws IOException {
        String[] extra = {"packet.arrival.us=0,0,250", "packet.bytes=1200", "packet.2.sta.id=2"};
        List<String> explicit = run(directory, false, extra);
        List<String> omitted = settings(false, extra);
        omitted.remove("fiber.uplink.poll.enabled=false");
        assertEquals(explicit, simulate(ConfigLoader.load(write(directory, omitted))));
        assertTrue(events(explicit, "PON_POLL_BWMAP_TX").isEmpty());
        assertEquals(List.of("250", "253.84", "500"), values(events(explicit, "FIBER_TX_START"), "t_us"));
    }

    @Test
    void rejectsMissingPrerequisitesAndInvalidControlWindows(@TempDir Path directory) {
        for (String property : List.of("fiber.uplink.report.enabled=false", "fiber.uplink.bwmap.enabled=false",
                "fiber.uplink.poll.bytes=0", "fiber.uplink.poll.bytes=39063", "fiber.uplink.poll.interval.frames=0",
                "fiber.uplink.poll.end.us=125", "fiber.uplink.poll.end.us=0", "fiber.uplink.poll.enabled=yes")) {
            assertThrows(IllegalArgumentException.class, () -> run(directory, true,
                    "traffic.mode=poisson", "traffic.duration.us=1", property), property);
        }
        List<String> missing = settings(true, emptyTraffic());
        missing.remove("fiber.uplink.poll.end.us=625");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(write(directory, missing)));
        assertThrows(IllegalArgumentException.class, () -> run(directory, false,
                "traffic.mode=poisson", "traffic.duration.us=1", "fiber.uplink.poll.bytes=64"));
    }

    private static String[] emptyTraffic() {
        return new String[]{"traffic.mode=poisson", "traffic.duration.us=1"};
    }

    private static List<String> settings(boolean enabled, String... extra) {
        List<String> settings = new ArrayList<>(List.of("traffic.direction=UL", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "wifi.delay.us=0", "fiber.uplink.mode=pon-framed",
                "fiber.uplink.report.enabled=true", "fiber.uplink.bwmap.enabled=true",
                "fiber.uplink.demand.ledger.enabled=true", "fiber.downlink.mode=pon-shared",
                "fiber.downlink.propagation.us=10", "fiber.uplink.propagation.us=10.025",
                "fiber.uplink.poll.enabled=" + enabled));
        if (enabled) {
            settings.add("fiber.uplink.poll.end.us=625");
        }
        settings.addAll(Arrays.asList(extra));
        return settings;
    }

    private static Path write(Path directory, List<String> settings) throws IOException {
        return Files.write(directory.resolve("polling.properties"), settings, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path directory, boolean enabled, String... extra) throws IOException {
        return simulate(ConfigLoader.load(write(directory, settings(enabled, extra))));
    }

    private static List<String> simulate(SimulationConfig config) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(out));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            fields.put(pair[0], pair[1]);
        }
        return fields;
    }

    private static List<Map<String, String>> events(List<String> log, String action) {
        return log.stream().map(PonReportPollingTest::fields).filter(row -> action.equals(row.get("event")))
                .collect(Collectors.toList());
    }

    private static List<Map<String, String>> summaries(List<String> log, String name) {
        return log.stream().map(PonReportPollingTest::fields).filter(row -> name.equals(row.get("summary")))
                .collect(Collectors.toList());
    }

    private static Map<String, String> summary(List<String> log, String name) {
        return summaries(log, name).get(0);
    }

    private static Map<String, String> finalFields(List<String> log) {
        return fields(log.get(log.size() - 1));
    }

    private static List<String> values(List<Map<String, String>> rows, String key) {
        return rows.stream().map(row -> row.get(key)).collect(Collectors.toList());
    }
}
