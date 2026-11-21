package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.RoundRobinDbaPolicy;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.link.PonUplink;
import edu.suda.fttr.link.PonUplinkReport;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 双SFU真实RR验收：需求和授权状态独立，物理发送仍共享唯一上行时隙。 */
class PonMultiSfuDbaTest {
    private static final BigDecimal RATE = BigDecimal.valueOf(2500);
    private static final long PERIOD_NS = 125_000;

    @Test
    void policySkipsAnOutstandingObjectWithoutChangingItsKnownDemand() {
        DbaContext.Allocation blocked = new DbaContext.Allocation(2, 1, 6000L, 1L, 10L, true);
        DbaContext.Allocation available = new DbaContext.Allocation(4, 2, 1200L, 1L, 10L, false);
        RoundRobinDbaPolicy policy = new RoundRobinDbaPolicy();
        DbaContext input = new DbaContext(125000, 2, RATE, PERIOD_NS, 205, List.of(blocked, available));
        DbaPlan selected = policy.plan(input);
        assertEquals(2, selected.grants().get(0).sfuId());
        assertEquals(1200, selected.grants().get(0).plannedBytes());
        assertTrue(blocked.known());
        assertEquals(6000L, blocked.knownDemandBytes());
        assertTrue(blocked.budgetOutstanding());
        assertFalse(available.budgetOutstanding());
        DbaPlan illegal = new DbaPlan(125000, 2,
                List.of(new DbaPlan.Grant(2, 1, 1200, 3840)), "BYPASS_OUTSTANDING");
        assertThrows(IllegalArgumentException.class,
                () -> new DbaPlanValidator().validateAndPreview(input, illegal));
        assertFalse(new DbaContext.Allocation(6, 3, 1200L, 1L, 10L).budgetOutstanding(),
                "The original five-argument constructor preserves the old eligible state");
    }

    @Test
    void twoKnownMfuObjectsRotateAndOneOutstandingBudgetOnlyBlocksItsOwner() {
        try (PrintStream out = quiet()) {
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            accept(mfu, context, 1, 0, 12000);
            accept(mfu, context, 2, 0, 2400);
            assertEquals(1, mfu.planDbaExecution(1, 205, RATE, PERIOD_NS, context).grants().get(0).sfuId());
            assertEquals(2, mfu.planDbaExecution(2, 205, RATE, PERIOD_NS, context).grants().get(0).sfuId());
            assertEquals(1, mfu.planDbaExecution(3, 205, RATE, PERIOD_NS, context).grants().get(0).sfuId());

            // 只授权SFU1已知需求的一部分，其剩余6000 B仍为KNOWN正需求，不能用零来伪装等待。
            PonDbaGrant first = new PonDbaGrant(1, 2, 1, 0, 1, 125000, 144200, 6000);
            mfu.recordDbaBudgetCreated(first, context);
            assertEquals(6000, mfu.estimateUplinkDemand(1, 0).effectiveKnownBytes());
            assertEquals(2400, mfu.estimateUplinkDemand(2, 0).effectiveKnownBytes());
            assertEquals(2, mfu.planDbaExecution(4, 205, RATE, PERIOD_NS, context).grants().get(0).sfuId());
            assertEquals(2, mfu.planDbaExecution(5, 205, RATE, PERIOD_NS, context).grants().get(0).sfuId());
            assertEquals(6000, mfu.estimateUplinkDemand(1, 0).effectiveKnownBytes());
        }
    }

    @Test
    void twoRealSfusReceiveIndependentBudgetsWhileTheFirstStillAwaitsSettlement(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, "rr", List.of());
        assertEquals(trace, run(directory, "rr", List.of()), "The full event trace must replay identically");
        List<Map<String, String>> grants = events(trace, "PON_DBA_BWMAP_TX");
        assertEquals(List.of("1", "2"), values(grants, "sfu_id"));
        Map<String, String> firstSettled = events(trace, "PON_DBA_SETTLEMENT_RX").get(0);
        assertTrue(ns(grants.get(1)) < ns(firstSettled), "SFU1 waiting must not stop SFU2's next-frame budget");
        assertEquals(List.of("1", "2"), values(events(trace, "DELIVERED"), "packet_id"));
        assertEquals(List.of("1", "2"), values(events(trace, "DELIVERED"), "sfu_id"));
        assertUniqueClosedLoop(trace);
        assertWindowsDoNotOverlap(trace);
    }

    @Test
    void sustainedKnownDemandObtainsAlternatingRealRrGrantsAndPreservesEachLocalFifo(@TempDir Path directory)
            throws IOException {
        List<String> extra = new ArrayList<>(List.of("packet.arrival.us="
                + String.join(",", Collections.nCopies(80, "0")), "fiber.uplink.poll.end.us=1500"));
        for (int packet = 1; packet <= 80; packet++) {
            extra.add("packet." + packet + ".sta.id=" + (packet % 2 == 0 ? 2 : 1));
        }
        List<String> trace = run(directory, "rr", extra);
        assertEquals(List.of("1", "2", "1", "2"), values(events(trace, "PON_DBA_BWMAP_TX"), "sfu_id"));
        List<Map<String, String>> delivered = events(trace, "DELIVERED");
        assertEquals(80, delivered.size());
        for (int sfu = 1; sfu <= 2; sfu++) {
            final int owner = sfu;
            List<String> expected = IntStream.rangeClosed(1, 80).filter(id -> (id % 2 == 0 ? 2 : 1) == owner)
                    .mapToObj(Integer::toString).collect(Collectors.toList());
            assertEquals(expected, values(delivered.stream().filter(row -> Integer.toString(owner)
                    .equals(row.get("sfu_id"))).collect(Collectors.toList()), "packet_id"));
        }
        assertUniqueClosedLoop(trace);
        assertWindowsDoNotOverlap(trace);
    }

    @Test
    void aLargeBlockedHeadOnSfuOneDoesNotPreventSfuTwoDelivery(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "rr", List.of("packet.1.bytes=39062"));
        List<Map<String, String>> fills = events(trace, "PON_DBA_FILL");
        assertTrue(fills.stream().anyMatch(row -> "1".equals(row.get("sfu_id"))
                && "HEAD_BYTES_EXCEED_BUDGET".equals(row.get("fill_reason"))
                && "0".equals(row.get("selected_bytes"))));
        assertEquals(List.of("2"), values(events(trace, "DELIVERED"), "packet_id"));
        assertEquals(List.of("2"), values(events(trace, "DELIVERED"), "sfu_id"));
        assertTrue(events(trace, "PON_DBA_DATA_START").stream().allMatch(row -> "2".equals(row.get("sfu_id"))));
        List<Map<String, String>> firstSettlement = events(trace, "PON_DBA_SETTLEMENT_RX").stream()
                .filter(row -> "1".equals(row.get("sfu_id"))).collect(Collectors.toList());
        assertTrue(firstSettlement.stream().allMatch(row -> "39062".equals(row.get("effective_after"))));
        assertWindowsDoNotOverlap(trace);
        assertUniqueClosedLoop(trace);
    }

    @Test
    void aDataPacketEndingAtTheFrameBoundaryReleasesBeforeTheNextSfuPoll(@TempDir Path directory)
            throws IOException {
        // 2560 Mb/s：64 B控制恰为200 ns，两个19968 B数据各62400 ns，
        // SFU1的第一个预算正好占用500200..625000，末包与下一轮询在625000同刻。
        List<String> trace = run(directory, "rr", List.of("fiber.uplink.rate.mbps=2560",
                "packet.arrival.us=0,0,0", "packet.1.bytes=19968", "packet.2.bytes=19968",
                "packet.1.sta.id=1", "packet.2.sta.id=1", "packet.3.sta.id=2"));
        List<Map<String, String>> grants = events(trace, "PON_DBA_BWMAP_TX");
        assertEquals(List.of("1", "2"), values(grants, "sfu_id"));
        assertEquals("500200", grants.get(0).get("tx_start_ns"));
        assertEquals("625000", grants.get(0).get("tx_end_ns"));
        Map<String, String> lastFirstSfuData = events(trace, "PON_DBA_DATA_END").stream()
                .filter(row -> "2".equals(row.get("packet_id"))).findFirst().orElseThrow();
        assertEquals(625000, ns(lastFirstSfuData));
        assertEquals(List.of("1", "2", "3"), values(events(trace, "DELIVERED"), "packet_id"));
        assertTrue(events(trace, "PON_POLL_TX_START").stream().anyMatch(row -> ns(row) == 625000));
        assertUniqueClosedLoop(trace);
        assertWindowsDoNotOverlap(trace);
    }

    @Test
    void unknownZeroAndDelayedReportsRemainSeparateForEachSfu() {
        try (PrintStream out = quiet()) {
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            assertEquals("ALL_UNKNOWN", mfu.planDbaExecution(1, 205, RATE, PERIOD_NS, context).decisionReason());
            accept(mfu, context, 1, 0, 0);
            assertTrue(mfu.estimateUplinkDemand(1, 0).known());
            assertEquals(0, mfu.estimateUplinkDemand(1, 0).effectiveKnownBytes());
            assertFalse(mfu.estimateUplinkDemand(2, 0).known());
            assertTrue(mfu.planDbaExecution(1, 205, RATE, PERIOD_NS, context).grants().isEmpty());
            advance(context, 10);
            accept(mfu, context, 2, 5, 2400);
            assertEquals(2, mfu.planDbaExecution(1, 205, RATE, PERIOD_NS, context).grants().get(0).sfuId());
            advance(context, 20);
            accept(mfu, context, 2, 15, 1200);
            advance(context, 30);
            accept(mfu, context, 2, 6, 9999); // 更迟到达的旧快照不得回退该SFU的已知状态。
            assertEquals(15, mfu.latestUplinkReport(2).report().snapshotTimeNs());
            assertEquals(1200, mfu.estimateUplinkDemand(2, 30).effectiveKnownBytes());
            assertEquals(0, mfu.estimateUplinkDemand(1, 30).effectiveKnownBytes());
            assertEquals(1200, mfu.planDbaExecution(1, 205, RATE, PERIOD_NS, context).grants().get(0).plannedBytes());
        }
    }

    @Test
    void budgetHistoryAndSettlementForOneSfuCannotDebitOrReleaseTheOtherSfu() {
        try (PrintStream out = quiet()) {
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            accept(mfu, context, 1, 0, 6000);
            accept(mfu, context, 2, 0, 2400);
            PonDemandLedger ledger = new PonDemandLedger();
            assertTrue(ledger.recordBudgetGrant(1, 1, 0, 125000, 128840, 1200));
            assertTrue(ledger.recordBudgetGrant(2, 2, 0, 250000, 257680, 2400));
            assertFalse(ledger.recordBudgetGrant(1, 1, 0, 125000, 128840, 1200));
            assertEquals(4800, ledger.estimate(1, 100000, mfu.latestUplinkReport(1)).effectiveKnownBytes());
            assertEquals(0, ledger.estimate(2, 100000, mfu.latestUplinkReport(2)).effectiveKnownBytes());
            assertTrue(ledger.settleBudgetGrant(1, 1, 300000, 600));
            assertEquals(5400, ledger.estimate(1, 300000, mfu.latestUplinkReport(1)).effectiveKnownBytes());
            assertEquals(0, ledger.estimate(2, 300000, mfu.latestUplinkReport(2)).effectiveKnownBytes());
            assertFalse(ledger.hasOutstandingBudget(1, 300000));
            assertTrue(ledger.hasOutstandingBudget(2, 300000));
            assertFalse(ledger.settleBudgetGrant(1, 1, 300001, 600));
            assertTrue(ledger.hasOutstandingBudget(2, 300001));
        }
    }

    @Test
    void duplicateBudgetEventsAndWrongSfuBwmapCannotRepeatDeliveryOrSettlement(@TempDir Path directory)
            throws IOException {
        SimulationConfig cfg = ConfigLoader.load(config(directory, "rr", List.of()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true, false, mfu);
            SimulationConfig.PonLink link = cfg.ponUplink();
            PonUplink uplink = new PonUplink(link.rateMbps(), link.propagationNs(), link.framePeriodNs(),
                    true, link.bwmapPropagationNs(), link.polling());
            uplink.enableDbaExecution(mfu);
            List<PonUplink.Port> ports = List.of(uplink.attachSfu(1), uplink.attachSfu(2));
            ports.get(0).enqueue(packet(1, 1200, 1), context);
            ports.get(1).enqueue(packet(2, 1200, 2), context);
            uplink.startPolling(List.of(1, 2), context);
            Event event;
            while ((event = context.nextEvent()) != null) {
                PonDbaGrant budget = event.dbaGrant();
                switch (event.type()) {
                    case PON_UPLINK_FRAME: uplink.scheduleFrame(context); break;
                    case PON_POLL_BWMAP_RX: ports.get(event.sfuId() - 1).receivePollBwmap(event.reportPoll(), context); break;
                    case PON_POLL_START: uplink.startPoll(event.reportPoll(), context); break;
                    case PON_POLL_END: uplink.finishPoll(event.reportPoll(), context); break;
                    case PON_POLL_REPORT_RX: mfu.receivePolledReport(event.reportPoll(), context); break;
                    case PON_DBA_BWMAP_RX:
                        assertThrows(IllegalStateException.class,
                                () -> ports.get(budget.sfuId() == 1 ? 1 : 0).receiveDbaBwmap(budget, context));
                        uplink.receiveDbaBwmap(budget, context);
                        assertThrows(IllegalStateException.class, () -> uplink.receiveDbaBwmap(budget, context));
                        break;
                    case PON_DBA_SLOT_START:
                        uplink.startDbaGrant(budget, context);
                        assertThrows(IllegalStateException.class, () -> uplink.startDbaGrant(budget, context));
                        break;
                    case FIBER_TX_START: uplink.start(event.packet(), event.sfuId(), context); break;
                    case FIBER_TX_END:
                        Packet completed = event.packet();
                        int sfuId = event.sfuId();
                        uplink.finish(completed, sfuId, context);
                        assertThrows(IllegalStateException.class, () -> uplink.finish(completed, sfuId, context));
                        break;
                    case PON_DBA_SLOT_END: uplink.finishDbaGrant(budget, context); break;
                    case PACKET_ARRIVAL_AT_MFU: mfu.receiveUplink(event.packet(), event.sfuId(), context); break;
                    case PON_DBA_SETTLEMENT_RX:
                        int other = budget.sfuId() == 1 ? 2 : 1;
                        PonDemandLedger.Estimate otherBefore = mfu.estimateUplinkDemand(other, context.nowTicks());
                        mfu.receiveDbaSettlement(budget, context);
                        long count = mfu.receivedUplinkReports();
                        mfu.receiveDbaSettlement(budget, context);
                        assertEquals(count, mfu.receivedUplinkReports());
                        PonDemandLedger.Estimate otherAfter = mfu.estimateUplinkDemand(other, context.nowTicks());
                        assertEquals(otherBefore.known(), otherAfter.known());
                        if (otherBefore.known()) {
                            assertEquals(otherBefore.effectiveKnownBytes(), otherAfter.effectiveKnownBytes());
                            assertEquals(otherBefore.grantedBytesSinceSnapshot(), otherAfter.grantedBytesSinceSnapshot());
                        }
                        break;
                    default: throw new AssertionError("Unexpected test event " + event.type());
                }
            }
        }
        List<String> trace = bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
        assertEquals(2, events(trace, "DELIVERED").size());
        assertUniqueClosedLoop(trace);
        assertWindowsDoNotOverlap(trace);
    }

    @Test
    void twoSfuDefaultFcfsAndShadowKeepTheSameActualDataTrace(@TempDir Path directory) throws IOException {
        List<String> original = run(directory, null, List.of());
        assertEquals(original, run(directory, "fcfs", List.of()));
        List<String> shadow = run(directory, "shadow-rr", List.of());
        assertEquals(original, shadow.stream().filter(line -> !line.contains("event=PON_DBA_SHADOW ")
                && !line.contains("event=PON_DBA_POLICY ")).collect(Collectors.toList()));
        assertEquals(2, events(original, "DELIVERED").size());
    }

    @Test
    void finiteUnknownWindowLeavesBothUnsentQueuesVisible(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "rr", List.of("fiber.uplink.poll.end.us=250"));
        assertTrue(events(trace, "PON_DBA_BWMAP_TX").isEmpty());
        assertTrue(events(trace, "DELIVERED").isEmpty());
        assertTrue(events(trace, "PON_DBA_DECISION").stream()
                .allMatch(row -> "ALL_UNKNOWN".equals(row.get("decision_reason"))));
        Map<String, String> summary = trace.stream().map(PonMultiSfuDbaTest::fields)
                .filter(row -> "PON_DBA_EXECUTION".equals(row.get("summary"))).findFirst().orElseThrow();
        assertEquals("2", summary.get("pending_packets"));
        assertTrue(trace.get(trace.size() - 1).contains("simulation_end_us=260.23"));
    }

    private static void assertUniqueClosedLoop(List<String> trace) {
        List<Map<String, String>> grants = events(trace, "PON_DBA_BWMAP_TX");
        List<String> ids = values(grants, "grant_id");
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertEquals(ids.size(), new HashSet<>(values(grants, "target_frame_id")).size(), "One DATA budget per frame");
        for (String phase : List.of("PON_DBA_LEDGER_CREATED", "PON_DBA_BWMAP_RX", "PON_DBA_FILL",
                "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX")) {
            assertEquals(ids, values(events(trace, phase), "grant_id"), phase);
        }
        List<String> delivered = values(events(trace, "DELIVERED"), "packet_id");
        assertEquals(delivered.size(), new HashSet<>(delivered).size());
        assertTrue(events(trace, "PON_DEMAND_LEDGER_GRANT").isEmpty());
        for (Map<String, String> settled : events(trace, "PON_DBA_SETTLEMENT_RX")) {
            assertEquals(Long.parseLong(settled.get("budget_bytes")),
                    Long.parseLong(settled.get("actual_bytes")) + Long.parseLong(settled.get("unused_bytes")));
            assertEquals(2L * Integer.parseInt(settled.get("sfu_id")), Long.parseLong(settled.get("allocation_id")));
        }
    }

    private static void assertWindowsDoNotOverlap(List<String> trace) {
        List<Map<String, String>> grants = events(trace, "PON_DBA_BWMAP_TX");
        long end = 0;
        for (Map<String, String> grant : grants) {
            long start = Long.parseLong(grant.get("tx_start_ns"));
            long nextEnd = Long.parseLong(grant.get("tx_end_ns"));
            assertTrue(start >= end, "DATA budget windows must not overlap across SFUs");
            long frame = Long.parseLong(grant.get("target_frame_id"));
            assertTrue(start >= frame * PERIOD_NS && nextEnd <= (frame + 1) * PERIOD_NS);
            end = nextEnd;
        }
        for (Map<String, String> data : events(trace, "PON_DBA_DATA_START")) {
            Map<String, String> grant = grants.stream().filter(row -> row.get("grant_id")
                    .equals(data.get("grant_id"))).findFirst().orElseThrow();
            assertEquals(grant.get("sfu_id"), data.get("sfu_id"));
            assertTrue(ns(data) >= Long.parseLong(grant.get("tx_start_ns")));
        }
    }

    private static void accept(Mfu mfu, SimulationContext context, int sfuId, long snapshotNs, long bytes) {
        mfu.receiveUplinkReport(new PonUplinkReport(0, sfuId, snapshotNs, bytes == 0 ? 0 : 1,
                bytes, bytes == 0 ? 0 : 1, bytes), packet(sfuId, 1200, sfuId), context);
    }

    private static void advance(SimulationContext context, long ns) {
        context.scheduleAfterNanoseconds(ns - context.nowTicks(), EventType.PON_UPLINK_FRAME, null, 0);
        context.nextEvent();
    }

    private static Packet packet(int id, int bytes, int staId) {
        return new Packet(id, bytes, 0, AccessCategory.BE, Packet.Direction.UL, staId, Packet.DEFAULT_MFU_ID);
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8);
    }

    private static Path config(Path directory, String mode, List<String> extra) throws IOException {
        List<String> settings = new ArrayList<>(List.of("traffic.direction=UL", "traffic.mode=manual",
                "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.arrival.us=0,0", "packet.bytes=1200",
                "packet.1.sta.id=1", "packet.2.sta.id=2", "wifi.model=fixed", "wifi.delay.us=0",
                "fiber.uplink.mode=pon-framed", "fiber.uplink.rate.mbps=2500", "fiber.uplink.frame.period.us=125",
                "fiber.uplink.propagation.us=10.025", "fiber.uplink.report.enabled=true",
                "fiber.uplink.bwmap.enabled=true", "fiber.uplink.demand.ledger.enabled=true",
                "fiber.uplink.poll.enabled=true", "fiber.uplink.poll.bytes=64",
                "fiber.uplink.poll.interval.frames=1", "fiber.uplink.poll.end.us=1000",
                "fiber.downlink.mode=pon-shared", "fiber.downlink.rate.mbps=10000",
                "fiber.downlink.propagation.us=10"));
        if (mode != null) settings.add("fiber.uplink.dba.mode=" + mode);
        settings.addAll(extra);
        return Files.write(directory.resolve("multi-sfu-dba.properties"), settings, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path directory, String mode, List<String> extra) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(config(directory, mode, extra)), new TraceLogger(out));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<String> trace, String action) {
        return trace.stream().map(PonMultiSfuDbaTest::fields).filter(row -> action.equals(row.get("event")))
                .collect(Collectors.toList());
    }

    private static List<String> values(List<Map<String, String>> rows, String key) {
        return rows.stream().map(row -> row.get(key)).collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2) row.put(pair[0], pair[1]);
        }
        return row;
    }

    private static long ns(Map<String, String> row) {
        return row.containsKey("time_ns") ? Long.parseLong(row.get("time_ns"))
                : new BigDecimal(row.get("t_us")).movePointRight(3).longValueExact();
    }
}
