package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.link.PonUplink;
import edu.suda.fttr.link.PonUplinkReport;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 第12步真实预算执行验收，真实传输与影子建议分别检查，不将预演当作交付。 */
class PonDbaExecutionTest {
    @Test
    void defaultAndExplicitFcfsRetainTheSameCompleteProtocolOutput(@TempDir Path directory) throws IOException {
        List<String> baseline = run(directory, null, 1200, List.of());
        assertEquals(baseline, run(directory, "fcfs", 1200, List.of()));
        assertEquals(1, events(baseline, "DELIVERED").size());
        assertEquals(1, events(baseline, "PON_BWMAP_TX").size());
        assertTrue(events(baseline, "PON_DBA_BWMAP_TX").isEmpty());
    }

    @Test
    void aKnown1200ByteReportBecomesOneReceivedBudgetAndOneDelivery(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "rr", 1200, List.of());
        assertEquals(trace, run(directory, "rr", 1200, List.of()));
        Map<String, String> tx = only(trace, "PON_DBA_BWMAP_TX");
        assertEquals("D1", tx.get("grant_id"));
        assertEquals("1200", tx.get("budget_bytes"));
        assertEquals("375000", tx.get("time_ns"));
        assertEquals("385000", only(trace, "PON_DBA_BWMAP_RX").get("time_ns"));
        assertEquals("500205", only(trace, "PON_DBA_FILL").get("time_ns"));
        assertEquals("504045", only(trace, "PON_DBA_DATA_END").get("time_ns"));
        assertEquals(514070, atNs(only(trace, "DELIVERED")));
        Map<String, String> settled = only(trace, "PON_DBA_SETTLEMENT_RX");
        assertEquals("514070", settled.get("time_ns"));
        assertEquals("1200", settled.get("actual_bytes"));
        assertEquals("0", settled.get("unused_bytes"));
        assertEquals("0", settled.get("effective_after"));
        assertEquals(1, events(trace, "PON_DBA_LEDGER_CREATED").size());
        assertTrue(events(trace, "PON_DEMAND_LEDGER_GRANT").isEmpty(), "DATA must not debit packet bytes twice");
        assertTrue(events(trace, "PON_DBA_DECISION").stream()
                .anyMatch(row -> "AWAITING_BUDGET_SETTLEMENT".equals(row.get("decision_reason"))));
    }

    @Test
    void anUndersizedBudgetKeepsTheWholeHeadAndSettlesAllUnusedBytes(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "rr", 39062, List.of());
        Map<String, String> fill = only(trace, "PON_DBA_FILL");
        assertEquals("38998", fill.get("budget_bytes"));
        assertEquals("0", fill.get("selected_bytes"));
        assertEquals("HEAD_BYTES_EXCEED_BUDGET", fill.get("fill_reason"));
        assertEquals("124794", fill.get("unused_duration_ns"));
        assertTrue(events(trace, "PON_DBA_DATA_START").isEmpty());
        assertTrue(events(trace, "DELIVERED").isEmpty());
        Map<String, String> settled = only(trace, "PON_DBA_SETTLEMENT_RX");
        assertEquals("0", settled.get("actual_bytes"));
        assertEquals("38998", settled.get("unused_bytes"));
        assertEquals("64", settled.get("effective_before"));
        assertEquals("39062", settled.get("effective_after"));
        assertEquals("1", summary(trace).get("pending_packets"));
        assertEquals("0", summary(trace).get("actual_data_ns"));
    }

    @Test
    void aReportBetweenBudgetCreationAndLoadingStillDeductsTheOutstandingBudget(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, "rr", 1200, List.of());
        Map<String, String> intermediate = events(trace, "PON_DEMAND_LEDGER").stream()
                .filter(row -> "510230".equals(row.get("time_ns"))).findFirst().orElseThrow();
        assertEquals("500000", intermediate.get("report_snapshot_ns"));
        assertEquals("1200", intermediate.get("reported_pending_bytes"));
        assertEquals("1200", intermediate.get("granted_bytes_since_snapshot"));
        assertEquals("0", intermediate.get("effective_known_bytes"));
        assertEquals(1, events(trace, "PON_DBA_BWMAP_TX").size());
        assertEquals(1, events(trace, "DELIVERED").size());
    }

    @Test
    void noKnownReportMeansNoDataBudgetAndTheFiniteWindowTerminates(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "rr", 1200, List.of("fiber.uplink.poll.end.us=250"));
        assertTrue(events(trace, "PON_DBA_BWMAP_TX").isEmpty());
        assertEquals("ALL_UNKNOWN", only(trace, "PON_DBA_DECISION").get("decision_reason"));
        assertEquals("1", summary(trace).get("pending_packets"));
        assertEquals("0", summary(trace).get("actual_bytes"));
        assertTrue(trace.get(trace.size() - 1).contains("simulation_end_us=260.23"));
    }

    @Test
    void partialPerPacketRoundingUsesOneBudgetAtATimeAndDoesNotRepeatDelivery(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, "rr", 1, List.of("packet.arrival.us=0,0",
                "fiber.uplink.poll.end.us=1000"));
        assertEquals(List.of("1", "2"), events(trace, "DELIVERED").stream()
                .map(row -> row.get("packet_id")).collect(Collectors.toList()));
        List<Map<String, String>> settled = events(trace, "PON_DBA_SETTLEMENT_RX");
        assertEquals(2, settled.size());
        assertEquals("2", settled.get(0).get("budget_bytes"));
        assertEquals("1", settled.get(0).get("actual_bytes"));
        assertEquals("1", settled.get(0).get("unused_bytes"));
        assertEquals("3", summary(trace).get("budget_bytes"));
        assertEquals("2", summary(trace).get("actual_bytes"));
        assertEquals("1", summary(trace).get("unused_bytes"));
        assertEquals("8", summary(trace).get("actual_data_ns"));
    }

    @Test
    void missingBwmapPreventsLoadingOrSending(@TempDir Path directory) throws IOException {
        manualExecution(directory, true);
    }

    @Test
    void duplicateBwmapDataCompletionAndSettlementCannotCountTwice(@TempDir Path directory) throws IOException {
        manualExecution(directory, false);
    }

    /** 对真实上行事件逐个执行，可故意漏掉控制接收，不能由日志推测已经收到BWmap。 */
    private static void manualExecution(Path directory, boolean omitBwmap) throws IOException {
        SimulationConfig cfg = ConfigLoader.load(config(directory, "rr", 1200, List.of()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Mfu mfu = new Mfu(List.of(1), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true, false, mfu);
            SimulationConfig.PonLink link = cfg.ponUplink();
            PonUplink uplink = new PonUplink(link.rateMbps(), link.propagationNs(), link.framePeriodNs(),
                    true, link.bwmapPropagationNs(), link.polling());
            uplink.enableDbaExecution(mfu);
            PonUplink.Port port = uplink.attachSfu(1);
            port.enqueue(new Packet(1, 1200, 0, AccessCategory.BE, Packet.Direction.UL, 1, 1), context);
            uplink.startPolling(List.of(1), context);
            Event event;
            while ((event = context.nextEvent()) != null) {
                PonDbaGrant budget = event.dbaGrant();
                switch (event.type()) {
                    case PON_UPLINK_FRAME: uplink.scheduleFrame(context); break;
                    case PON_POLL_BWMAP_RX: port.receivePollBwmap(event.reportPoll(), context); break;
                    case PON_POLL_START: uplink.startPoll(event.reportPoll(), context); break;
                    case PON_POLL_END: uplink.finishPoll(event.reportPoll(), context); break;
                    case PON_POLL_REPORT_RX: mfu.receivePolledReport(event.reportPoll(), context); break;
                    case PON_DBA_BWMAP_RX:
                        if (!omitBwmap) {
                            uplink.receiveDbaBwmap(budget, context);
                            assertThrows(IllegalStateException.class, () -> uplink.receiveDbaBwmap(budget, context));
                        }
                        break;
                    case PON_DBA_SLOT_START:
                        if (omitBwmap) {
                            assertThrows(IllegalStateException.class, () -> uplink.startDbaGrant(budget, context));
                            assertEquals(1, port.ungrantedPacketView(context).packets().size());
                            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("event=PON_DBA_DATA_START"));
                            return;
                        }
                        uplink.startDbaGrant(budget, context);
                        assertThrows(IllegalStateException.class, () -> uplink.startDbaGrant(budget, context));
                        break;
                    case FIBER_TX_START: uplink.start(event.packet(), 1, context); break;
                    case FIBER_TX_END:
                        Packet completed = event.packet();
                        uplink.finish(completed, 1, context);
                        assertThrows(IllegalStateException.class, () -> uplink.finish(completed, 1, context));
                        break;
                    case PON_DBA_SLOT_END: uplink.finishDbaGrant(budget, context); break;
                    case PACKET_ARRIVAL_AT_MFU: mfu.receiveUplink(event.packet(), 1, context); break;
                    case PON_DBA_SETTLEMENT_RX:
                        mfu.receiveDbaSettlement(budget, context);
                        long count = mfu.receivedUplinkReports();
                        mfu.receiveDbaSettlement(budget, context);
                        assertEquals(count, mfu.receivedUplinkReports());
                        assertEquals(0, mfu.estimateUplinkDemand(1, context.nowTicks()).effectiveKnownBytes());
                        break;
                    default: throw new AssertionError("Unexpected event " + event.type());
                }
            }
            assertFalse(omitBwmap, "Expected to encounter a DATA budget slot");
            assertEquals(1, events(bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList()),
                    "DELIVERED").size());
        }
    }

    @Test
    void budgetHistoryIsIdempotentAndOnlyReconcilesAfterFeedbackArrives(@TempDir Path directory) {
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(output), true, true);
            Mfu mfu = new Mfu(List.of(1), null, true);
            context.scheduleAfterNanoseconds(230, EventType.PON_UPLINK_FRAME, null, 0);
            context.nextEvent();
            Packet carrier = new Packet(1, 1200, 0, AccessCategory.BE, Packet.Direction.UL, 1, 1);
            mfu.receiveUplinkReport(new PonUplinkReport(1, 1, 200, 1, 1200, 1, 1200), carrier, context);
            Mfu.ReceivedUplinkReport snapshot = mfu.latestUplinkReport(1);
            PonDemandLedger ledger = new PonDemandLedger();
            assertTrue(ledger.recordBudgetGrant(1, 1, 100, 300, 4140, 1200));
            assertFalse(ledger.recordBudgetGrant(1, 1, 100, 300, 4140, 1200));
            assertEquals(0, ledger.estimate(1, 230, snapshot).effectiveKnownBytes());
            assertTrue(ledger.hasOutstandingBudget(1, 4000));
            assertThrows(IllegalStateException.class, () -> ledger.recordBudgetGrant(1, 2, 240, 500, 4340, 1200));
            assertTrue(ledger.settleBudgetGrant(1, 1, 5000, 600));
            assertFalse(ledger.settleBudgetGrant(1, 1, 5100, 600));
            assertEquals(0, ledger.estimate(1, 4000, snapshot).effectiveKnownBytes(), "Feedback is still future");
            assertEquals(600, ledger.estimate(1, 5000, snapshot).effectiveKnownBytes());
            assertFalse(ledger.hasOutstandingBudget(1, 5000));
            assertThrows(IllegalArgumentException.class, () -> ledger.settleBudgetGrant(1, 1, 5200, 700));
        }
    }

    @Test
    void zeroPropagationStillDeliversDataBeforeTheSameTimeSettlement(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "rr", 1200, List.of("fiber.uplink.propagation.us=0",
                "fiber.downlink.propagation.us=0"));
        assertEquals(1, events(trace, "DELIVERED").size());
        int delivered = indexOf(trace, "DELIVERED");
        int settled = indexOf(trace, "PON_DBA_SETTLEMENT_RX");
        assertTrue(delivered < settled);
        assertEquals(atNs(only(trace, "DELIVERED")), Long.parseLong(only(trace,
                "PON_DBA_SETTLEMENT_RX").get("time_ns")));
    }

    @Test
    void realModeRejectsMissingPrerequisitesAndUnsupportedMultipleSfus(@TempDir Path directory) {
        for (String disabled : List.of("fiber.uplink.report.enabled=false", "fiber.uplink.bwmap.enabled=false",
                "fiber.uplink.demand.ledger.enabled=false", "fiber.uplink.poll.enabled=false", "sfu.count=2")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConfigLoader.load(config(directory, "rr", 1200, List.of(disabled))), disabled);
        }
    }

    @Test
    void mfuCannotCreateUnreportedOrOverDemandBudgetsAndCreationIsIdempotent() {
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            Mfu mfu = new Mfu(List.of(1), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            PonDbaGrant budget = new PonDbaGrant(1, 2, 1, 0, 1, 125000, 128840, 1200);
            assertThrows(IllegalArgumentException.class, () -> mfu.recordDbaBudgetCreated(budget, context));
            Packet carrier = new Packet(1, 1200, 0, AccessCategory.BE, Packet.Direction.UL, 1, 1);
            mfu.receiveUplinkReport(new PonUplinkReport(0, 1, 0, 1, 1200, 1, 1200), carrier, context);
            PonDbaGrant over = new PonDbaGrant(2, 2, 1, 0, 1, 125000, 128844, 1201);
            assertThrows(IllegalArgumentException.class, () -> mfu.recordDbaBudgetCreated(over, context));
            mfu.recordDbaBudgetCreated(budget, context);
            mfu.recordDbaBudgetCreated(budget, context);
            assertEquals(1200, mfu.estimateUplinkDemand(1, 0).grantedBytesSinceSnapshot());
            assertEquals(0, mfu.estimateUplinkDemand(1, 0).effectiveKnownBytes());
        }
    }

    private static Map<String, String> only(List<String> trace, String action) {
        List<Map<String, String>> found = events(trace, action);
        assertEquals(1, found.size(), action);
        return found.get(0);
    }

    private static Map<String, String> summary(List<String> trace) {
        return trace.stream().map(PonDbaExecutionTest::fields)
                .filter(row -> "PON_DBA_EXECUTION".equals(row.get("summary"))).findFirst().orElseThrow();
    }

    private static int indexOf(List<String> trace, String event) {
        for (int i = 0; i < trace.size(); i++) {
            if (event.equals(fields(trace.get(i)).get("event"))) return i;
        }
        throw new AssertionError("Missing event " + event);
    }

    private static List<String> settings(String mode, int packetBytes, List<String> extra) {
        List<String> settings = new ArrayList<>(List.of(
                "traffic.direction=UL", "sfu.count=1", "sta.count=1", "traffic.mode=manual",
                "packet.arrival.us=0", "packet.bytes=" + packetBytes,
                "wifi.model=fixed", "wifi.delay.us=0",
                "fiber.uplink.mode=pon-framed", "fiber.uplink.rate.mbps=2500",
                "fiber.uplink.frame.period.us=125", "fiber.uplink.propagation.us=10.025",
                "fiber.uplink.report.enabled=true", "fiber.uplink.bwmap.enabled=true",
                "fiber.uplink.demand.ledger.enabled=true", "fiber.uplink.poll.enabled=true",
                "fiber.uplink.poll.bytes=64", "fiber.uplink.poll.interval.frames=1",
                "fiber.uplink.poll.end.us=625", "fiber.downlink.mode=pon-shared",
                "fiber.downlink.rate.mbps=10000", "fiber.downlink.propagation.us=10"));
        if (mode != null) {
            settings.add("fiber.uplink.dba.mode=" + mode);
        }
        settings.addAll(extra);
        return settings;
    }

    private static Path config(Path directory, String mode, int packetBytes, List<String> extra) throws IOException {
        return Files.write(directory.resolve("dba-execution.properties"),
                settings(mode, packetBytes, extra), StandardCharsets.UTF_8);
    }

    private static List<String> run(Path directory, String mode, int packetBytes, List<String> extra)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(config(directory, mode, packetBytes, extra)), new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<String> trace, String action) {
        return trace.stream().map(PonDbaExecutionTest::fields)
                .filter(row -> action.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2) {
                fields.put(pair[0], pair[1]);
            }
        }
        return fields;
    }

    private static long atNs(Map<String, String> row) {
        return new BigDecimal(row.get("t_us")).movePointRight(3).longValueExact();
    }
}
