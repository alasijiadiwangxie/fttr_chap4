package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.MultiGrantRoundRobinDbaPolicy;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同帧多授权验收，计划、实际报文和结算分别核对，不把字节预算当作已交付量。 */
class PonMultiGrantDbaTest {
    private static final BigDecimal RATE = BigDecimal.valueOf(2500);
    private static final long PERIOD_NS = 125000;

    @Test
    void plansTwoKnown1200ByteObjectsAndPreviewsAdjacentWindows() {
        DbaContext input = input(PERIOD_NS, 205, allocation(2, 1, 1200L, false),
                allocation(4, 2, 1200L, false));
        DbaPlan plan = new MultiGrantRoundRobinDbaPolicy().plan(input);
        assertEquals(List.of(1, 2), plan.grants().stream().map(DbaPlan.Grant::sfuId).collect(Collectors.toList()));
        assertEquals(List.of(1200L, 1200L), plan.grants().stream()
                .map(DbaPlan.Grant::plannedBytes).collect(Collectors.toList()));
        List<DbaPlanValidator.Slot> slots = new DbaPlanValidator().validateAndPreview(input, plan);
        assertEquals(2, slots.size());
        assertEquals(125205, slots.get(0).startNs());
        assertEquals(129045, slots.get(0).endNs());
        assertEquals(slots.get(0).endNs(), slots.get(1).startNs());
        assertEquals(132885, slots.get(1).endNs());
        assertTrue(slots.get(1).endNs() <= 2 * PERIOD_NS);
    }

    @Test
    void remainingCapacitySubtractsEachGrantCeilingRatherThanAnAggregatedByteTotal() {
        DbaContext sevenNs = input(7, 0, allocation(2, 1, 1L, false), allocation(4, 2, 1L, false));
        MultiGrantRoundRobinDbaPolicy policy = new MultiGrantRoundRobinDbaPolicy();
        DbaPlan first = policy.plan(sevenNs);
        assertEquals(1, first.grants().size(), "Two 1 B grants each require 4 ns, not 7 ns together");
        assertEquals(1, first.grants().get(0).sfuId());
        assertEquals(4, first.grants().get(0).plannedDurationNs());
        assertEquals(2, policy.plan(sevenNs).grants().get(0).sfuId(), "Capacity exhaustion advances RR fairly");
        new DbaPlanValidator().validateAndPreview(sevenNs, first);

        DbaContext eightNs = input(8, 0, allocation(2, 1, 1L, false), allocation(4, 2, 1L, false));
        DbaPlan both = new MultiGrantRoundRobinDbaPolicy().plan(eightNs);
        assertEquals(2, both.grants().size());
        assertEquals(8, both.grants().stream().mapToLong(DbaPlan.Grant::plannedDurationNs).sum());
        assertEquals(16, new DbaPlanValidator().validateAndPreview(eightNs, both).get(1).endNs());
    }

    @Test
    void skipsOutstandingUnknownAndZeroWithoutChangingTheReportedValues() {
        DbaContext.Allocation waiting = allocation(2, 1, 6000L, true);
        DbaContext.Allocation ready = allocation(4, 2, 1200L, false);
        DbaPlan selected = new MultiGrantRoundRobinDbaPolicy().plan(input(PERIOD_NS, 205, waiting, ready));
        assertEquals(1, selected.grants().size());
        assertEquals(2, selected.grants().get(0).sfuId());
        assertTrue(waiting.known());
        assertEquals(6000L, waiting.knownDemandBytes());
        assertTrue(waiting.budgetOutstanding());

        DbaContext unknownAndZero = input(PERIOD_NS, 205, allocation(2, 1, null, false),
                allocation(4, 2, 0L, false));
        assertTrue(new MultiGrantRoundRobinDbaPolicy().plan(unknownAndZero).grants().isEmpty());
        assertFalse(unknownAndZero.allocations().get(0).known());
        assertTrue(unknownAndZero.allocations().get(1).known());
        assertEquals(0L, unknownAndZero.allocations().get(1).knownDemandBytes());
    }

    @Test
    void aBudgetThatConsumesTheFrameMovesTheNextRoundToTheOtherSfu() {
        DbaContext saturated = input(PERIOD_NS, 205, allocation(2, 1, 40000L, false),
                allocation(4, 2, 40000L, false));
        MultiGrantRoundRobinDbaPolicy policy = new MultiGrantRoundRobinDbaPolicy();
        DbaPlan first = policy.plan(saturated);
        DbaPlan second = policy.plan(saturated);
        assertEquals(1, first.grants().size());
        assertEquals(1, second.grants().size());
        assertEquals(1, first.grants().get(0).sfuId());
        assertEquals(2, second.grants().get(0).sfuId());
        assertEquals(38998, first.grants().get(0).plannedBytes());
        assertEquals(124794, first.grants().get(0).plannedDurationNs());
        new DbaPlanValidator().validateAndPreview(saturated, first);
        new DbaPlanValidator().validateAndPreview(saturated, second);
    }

    @Test
    void mfuMaterializesBothValidatedOffsetsUsingOnlyReceivedReports() {
        try (PrintStream out = quiet()) {
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            mfu.enableMultiGrantDba();
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            receiveKnown(mfu, context, 1, 1200);
            receiveKnown(mfu, context, 2, 1200);
            DbaPlan plan = mfu.planDbaExecution(1, 205, RATE, PERIOD_NS, context);
            assertEquals(2, plan.grants().size());
            assertEquals(205L, plan.grants().get(0).startOffsetNs());
            assertEquals(4045L, plan.grants().get(1).startOffsetNs());
            assertEquals(1200, mfu.estimateUplinkDemand(1, 0).effectiveKnownBytes());
            assertEquals(1200, mfu.estimateUplinkDemand(2, 0).effectiveKnownBytes());
        }
    }

    @Test
    void two1200BytePacketsActuallySendAndSettleInsideTheSameTargetFrame(@TempDir Path directory)
            throws IOException {
        List<String> trace = runBootstrapped(directory, 1200, RATE, false);
        assertEquals(trace, runBootstrapped(directory, 1200, RATE, false));
        List<Map<String, String>> grants = events(trace, "PON_DBA_BWMAP_TX");
        assertEquals(2, grants.size());
        assertEquals(List.of("1", "2"), values(grants, "sfu_id"));
        assertEquals(List.of("2", "2"), values(grants, "target_frame_id"));
        assertEquals("250205", grants.get(0).get("tx_start_ns"));
        assertEquals("254045", grants.get(0).get("tx_end_ns"));
        assertEquals(grants.get(0).get("tx_end_ns"), grants.get(1).get("tx_start_ns"));
        assertEquals("257885", grants.get(1).get("tx_end_ns"));
        assertEquals(List.of("1", "2"), values(events(trace, "DELIVERED"), "packet_id"));
        assertEquals(List.of("264070", "267910"), events(trace, "DELIVERED").stream()
                .map(row -> Long.toString(ns(row))).collect(Collectors.toList()));
        assertClosedLoop(trace);
        assertWindows(trace);
    }

    @Test
    void exactAdjacentGrantsReleaseDataBeforeTheNextStartAndRetainIndependentSnapshots(@TempDir Path directory)
            throws IOException {
        List<String> trace = runBootstrapped(directory, 19968, BigDecimal.valueOf(2560), false);
        List<Map<String, String>> grants = events(trace, "PON_DBA_BWMAP_TX");
        assertEquals("250200", grants.get(0).get("tx_start_ns"));
        assertEquals("312600", grants.get(0).get("tx_end_ns"));
        assertEquals("312600", grants.get(1).get("tx_start_ns"));
        assertEquals("375000", grants.get(1).get("tx_end_ns"));
        assertTrue(index(trace, "PON_DBA_DATA_END", "1") < index(trace, "PON_DBA_DATA_START", "2"));
        List<Map<String, String>> settled = events(trace, "PON_DBA_SETTLEMENT_RX");
        assertEquals(List.of("1", "2"), values(settled, "sfu_id"));
        assertEquals(List.of("312600", "375000"), values(settled, "snapshot_time_ns"));
        assertTrue(settled.stream().allMatch(row -> "0".equals(row.get("pending_bytes"))
                && "0".equals(row.get("effective_after"))));
        assertClosedLoop(trace);
        assertWindows(trace);
    }

    @Test
    void naturalReportsCanConvergeToOneMultiGrantFrameWhileOldRrStillUsesSeparateFrames(@TempDir Path directory)
            throws IOException {
        // SFU1的大预算先完成装载，SFU2在首报告之后又到一包；750us时两方各有1200 B已知需求。
        List<String> extra = List.of("packet.arrival.us=0,0,0,600", "packet.1.bytes=38000",
                "packet.1.sta.id=1", "packet.2.bytes=1200", "packet.2.sta.id=1",
                "packet.3.bytes=1200", "packet.3.sta.id=2", "packet.4.bytes=1200",
                "packet.4.sta.id=2", "fiber.uplink.poll.end.us=1250");
        List<String> multi = run(directory, "rr-multi", extra);
        assertEquals(multi, run(directory, "rr-multi", extra));
        List<Map<String, String>> grants = events(multi, "PON_DBA_BWMAP_TX");
        assertEquals(4, grants.size());
        List<Map<String, String>> frameSeven = grants.stream()
                .filter(row -> "7".equals(row.get("target_frame_id"))).collect(Collectors.toList());
        assertEquals(List.of("1", "2"), values(frameSeven, "sfu_id"));
        assertEquals(List.of("1200", "1200"), values(frameSeven, "budget_bytes"));
        assertEquals("875205", frameSeven.get(0).get("tx_start_ns"));
        assertEquals("879045", frameSeven.get(0).get("tx_end_ns"));
        assertEquals("879045", frameSeven.get(1).get("tx_start_ns"));
        assertEquals("882885", frameSeven.get(1).get("tx_end_ns"));
        assertEquals(4, events(multi, "DELIVERED").size());
        assertClosedLoop(multi);
        assertWindows(multi);
        List<Map<String, String>> old = events(run(directory, "rr", extra), "PON_DBA_BWMAP_TX");
        assertEquals(old.size(), new HashSet<>(values(old, "target_frame_id")).size(), "rr remains single-grant");
    }

    @Test
    void anUnfillableHeadDoesNotCreateGlobalBlockingOfTheOtherSfu(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "rr-multi", List.of("packet.1.bytes=39062",
                "fiber.uplink.poll.end.us=625"));
        assertTrue(events(trace, "PON_DBA_FILL").stream().anyMatch(row -> "1".equals(row.get("sfu_id"))
                && "HEAD_BYTES_EXCEED_BUDGET".equals(row.get("fill_reason"))
                && "0".equals(row.get("selected_bytes"))));
        assertEquals(List.of("2"), values(events(trace, "DELIVERED"), "sfu_id"));
        assertTrue(events(trace, "PON_DBA_OBJECT_STATE").stream().anyMatch(row -> "1".equals(row.get("sfu_id"))
                && "true".equals(row.get("budget_outstanding")) && "64".equals(row.get("known_demand_bytes"))));
        assertClosedLoop(trace);
        assertWindows(trace);
    }

    @Test
    void missingOneBwmapCannotBeBypassedByTheOtherSfusSuccessfulSlot(@TempDir Path directory) throws IOException {
        runBootstrapped(directory, 1200, RATE, true);
    }

    @Test
    void oldModesRemainUnchangedAndMultiModeKeepsItsPrerequisites(@TempDir Path directory) throws IOException {
        List<String> original = run(directory, null, List.of());
        assertEquals(original, run(directory, "fcfs", List.of()));
        assertEquals(original, run(directory, "shadow-rr", List.of()).stream()
                .filter(line -> !line.contains("event=PON_DBA_SHADOW ")
                        && !line.contains("event=PON_DBA_POLICY ")).collect(Collectors.toList()));
        for (String dependency : List.of("fiber.uplink.report.enabled=false", "fiber.uplink.bwmap.enabled=false",
                "fiber.uplink.demand.ledger.enabled=false", "fiber.uplink.poll.enabled=false", "sfu.count=3")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConfigLoader.load(config(directory, "rr-multi", List.of(dependency))), dependency);
        }
    }

    /**
     * PON局部闭环fixture：队列已入队，先通过真实MFU REPORT接收方法建立已知需求。
     * 不增加生产控制通道；之后所有授权、BWmap、装载、DATA和结算都走真实事件系统。
     */
    private static List<String> runBootstrapped(Path directory, int packetBytes, BigDecimal rate, boolean omitSecondRx)
            throws IOException {
        SimulationConfig cfg = ConfigLoader.load(config(directory, "rr-multi",
                List.of("fiber.uplink.rate.mbps=" + rate.toPlainString(), "packet.bytes=" + packetBytes)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true, false, mfu);
            SimulationConfig.PonLink link = cfg.ponUplink();
            PonUplink uplink = new PonUplink(link.rateMbps(), link.propagationNs(), link.framePeriodNs(),
                    true, link.bwmapPropagationNs(), link.polling());
            uplink.enableDbaExecution(mfu, true);
            List<PonUplink.Port> ports = List.of(uplink.attachSfu(1), uplink.attachSfu(2));
            for (int sfu = 1; sfu <= 2; sfu++) {
                ports.get(sfu - 1).enqueue(packet(sfu, packetBytes, sfu), context);
                receiveKnown(mfu, context, sfu, packetBytes);
            }
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
                        if (!omitSecondRx || budget.sfuId() != 2) {
                            uplink.receiveDbaBwmap(budget, context);
                            assertThrows(IllegalStateException.class, () -> uplink.receiveDbaBwmap(budget, context));
                        }
                        break;
                    case PON_DBA_SLOT_START:
                        if (omitSecondRx && budget.sfuId() == 2) {
                            assertThrows(IllegalStateException.class, () -> uplink.startDbaGrant(budget, context));
                            assertEquals(1, ports.get(1).ungrantedPacketView(context).packets().size());
                            List<String> trace = lines(bytes);
                            assertEquals(List.of("1"), values(events(trace, "PON_DBA_DATA_START"), "sfu_id"));
                            return trace;
                        }
                        uplink.startDbaGrant(budget, context);
                        assertThrows(IllegalStateException.class, () -> uplink.startDbaGrant(budget, context));
                        break;
                    case FIBER_TX_START: uplink.start(event.packet(), event.sfuId(), context); break;
                    case FIBER_TX_END:
                        Packet completed = event.packet();
                        int owner = event.sfuId();
                        uplink.finish(completed, owner, context);
                        assertThrows(IllegalStateException.class, () -> uplink.finish(completed, owner, context));
                        break;
                    case PON_DBA_SLOT_END:
                        uplink.finishDbaGrant(budget, context);
                        assertEquals(budget.sfuId(), budget.report().sfuId());
                        assertEquals(0, budget.report().pendingBytes());
                        break;
                    case PACKET_ARRIVAL_AT_MFU: mfu.receiveUplink(event.packet(), event.sfuId(), context); break;
                    case PON_DBA_SETTLEMENT_RX:
                        mfu.receiveDbaSettlement(budget, context);
                        long reports = mfu.receivedUplinkReports();
                        mfu.receiveDbaSettlement(budget, context);
                        assertEquals(reports, mfu.receivedUplinkReports());
                        break;
                    default: throw new AssertionError("Unexpected event " + event.type());
                }
            }
            assertFalse(omitSecondRx, "Expected to encounter SFU2's DATA slot");
            assertEquals(0, mfu.estimateUplinkDemand(1, context.nowTicks()).effectiveKnownBytes());
            assertEquals(0, mfu.estimateUplinkDemand(2, context.nowTicks()).effectiveKnownBytes());
        }
        return lines(bytes);
    }

    private static void assertClosedLoop(List<String> trace) {
        List<Map<String, String>> grants = events(trace, "PON_DBA_BWMAP_TX");
        List<String> ids = values(grants, "grant_id");
        assertEquals(ids.size(), new HashSet<>(ids).size());
        for (String phase : List.of("PON_DBA_LEDGER_CREATED", "PON_DBA_BWMAP_RX", "PON_DBA_FILL",
                "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX")) {
            List<String> actual = values(events(trace, phase), "grant_id");
            assertEquals(ids.size(), actual.size(), phase);
            assertEquals(new HashSet<>(ids), new HashSet<>(actual), phase);
        }
        List<Map<String, String>> delivered = events(trace, "DELIVERED");
        assertEquals(delivered.size(), new HashSet<>(values(delivered, "packet_id")).size());
        for (Map<String, String> settled : events(trace, "PON_DBA_SETTLEMENT_RX")) {
            assertEquals(Long.parseLong(settled.get("budget_bytes")), Long.parseLong(settled.get("actual_bytes"))
                    + Long.parseLong(settled.get("unused_bytes")));
        }
    }

    private static void assertWindows(List<String> trace) {
        List<Map<String, String>> windows = new ArrayList<>(events(trace, "PON_DBA_BWMAP_TX"));
        windows.addAll(events(trace, "PON_POLL_BWMAP_TX"));
        windows.sort(Comparator.comparingLong(row -> Long.parseLong(row.get("tx_start_ns"))));
        long previousEnd = 0;
        for (Map<String, String> window : windows) {
            long start = Long.parseLong(window.get("tx_start_ns"));
            long end = Long.parseLong(window.get("tx_end_ns"));
            long frame = Long.parseLong(window.get("target_frame_id"));
            assertTrue(start >= previousEnd, "Shared upstream reservations cannot overlap");
            assertTrue(start >= frame * PERIOD_NS && end <= (frame + 1) * PERIOD_NS);
            previousEnd = end;
        }
    }

    private static DbaContext input(long periodNs, long controlNs, DbaContext.Allocation... allocations) {
        return new DbaContext(0, 1, RATE, periodNs, controlNs, List.of(allocations));
    }

    private static DbaContext.Allocation allocation(long id, int sfu, Long bytes, boolean outstanding) {
        return new DbaContext.Allocation(id, sfu, bytes, bytes == null ? null : 0L,
                bytes == null ? null : 0L, outstanding);
    }

    private static void receiveKnown(Mfu mfu, SimulationContext context, int sfu, int bytes) {
        mfu.receiveUplinkReport(new PonUplinkReport(0, sfu, context.nowTicks(), 1, bytes, 1, bytes),
                packet(sfu, bytes, sfu), context);
    }

    private static Packet packet(int id, int bytes, int sta) {
        return new Packet(id, bytes, 0, AccessCategory.BE, Packet.Direction.UL, sta, Packet.DEFAULT_MFU_ID);
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8);
    }

    private static List<String> lines(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static Path config(Path directory, String mode, List<String> extra) throws IOException {
        List<String> settings = new ArrayList<>(List.of("traffic.direction=UL", "traffic.mode=manual",
                "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2", "packet.arrival.us=0,0", "packet.bytes=1200",
                "packet.1.sta.id=1", "packet.2.sta.id=2", "wifi.model=fixed", "wifi.delay.us=0",
                "fiber.uplink.mode=pon-framed", "fiber.uplink.rate.mbps=2500", "fiber.uplink.frame.period.us=125",
                "fiber.uplink.propagation.us=10.025", "fiber.uplink.report.enabled=true", "fiber.uplink.bwmap.enabled=true",
                "fiber.uplink.demand.ledger.enabled=true", "fiber.uplink.poll.enabled=true", "fiber.uplink.poll.bytes=64",
                "fiber.uplink.poll.interval.frames=1", "fiber.uplink.poll.end.us=1000",
                "fiber.downlink.mode=pon-shared", "fiber.downlink.rate.mbps=10000", "fiber.downlink.propagation.us=10"));
        if (mode != null) settings.add("fiber.uplink.dba.mode=" + mode);
        settings.addAll(extra);
        return Files.write(directory.resolve("multi-grant.properties"), settings, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path directory, String mode, List<String> extra) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(config(directory, mode, extra)), new TraceLogger(out));
        }
        return lines(bytes);
    }

    private static List<Map<String, String>> events(List<String> trace, String action) {
        return trace.stream().map(PonMultiGrantDbaTest::fields).filter(row -> action.equals(row.get("event")))
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

    private static int index(List<String> trace, String action, String packetId) {
        for (int i = 0; i < trace.size(); i++) {
            Map<String, String> row = fields(trace.get(i));
            if (action.equals(row.get("event")) && packetId.equals(row.get("packet_id"))) return i;
        }
        throw new AssertionError("Missing " + action + " for packet " + packetId);
    }
}
