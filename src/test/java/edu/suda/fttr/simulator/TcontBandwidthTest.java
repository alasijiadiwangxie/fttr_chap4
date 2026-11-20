package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.MultiGrantRoundRobinDbaPolicy;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.dba.TcontTopology;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.link.PonGrantPacketFiller;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第15步只验收逻辑队列与参数模型。SI/AB 是未来策略的输入元数据，
 * 本阶段 RR 不执行保证/剩余带宽算法；因此不能把这些测试称为 GIANT 验证。
 */
public class TcontBandwidthTest {
    private static final BigDecimal RATE = BigDecimal.valueOf(2500);
    private static final long PERIOD_NS = 125000;

    @Test
    void loadsExplicitIdsTypeTwoThreeFourAndIndependentBandwidthFields(@TempDir Path directory) throws IOException {
        TcontTopology topology = load(directory, configuration(2, "rr-multi", List.of(), List.of())).tcontTopology();
        assertTrue(topology.enabled());
        assertEquals(List.of(101L, 102L, 201L), topology.profiles().stream()
                .map(TcontProfile::allocationId).collect(Collectors.toList()));
        assertEquals(2, topology.profilesForSfu(1).size());
        assertEquals(101, topology.allocationFor(1, AccessCategory.VO));
        assertEquals(102, topology.allocationFor(1, AccessCategory.BK));
        assertEquals(201, topology.allocationFor(2, AccessCategory.BE));
        assertEquals(Map.of(101L, 1, 102L, 1, 201L, 2), topology.allocationOwners());
        TcontProfile video = topology.profile(102);
        assertEquals(3, video.type());
        assertEquals(1, video.siMaxFrames());
        assertEquals(2, video.siMinFrames(), "SI names do not imply SImin <= SImax");
        assertEquals(2400, video.abMinBytes());
        assertEquals(1200, video.abSurBytes(), "ABsur is an independent surplus, not total maximum bandwidth");
        assertEquals(0, topology.profile(201).abMinBytes(), "Type4's polling metadata may be zero");
    }

    @Test
    void profileTopologyAndContextAreImmutableValueInputs() {
        Set<AccessCategory> mutableClasses = new HashSet<>(Set.of(AccessCategory.VO, AccessCategory.VI));
        TcontProfile first = new TcontProfile(101, 1, 2, mutableClasses, 1, 0, 2400, 0);
        mutableClasses.clear();
        assertEquals(Set.of(AccessCategory.VO, AccessCategory.VI), first.classes());
        assertThrows(UnsupportedOperationException.class, () -> first.classes().clear());
        List<TcontProfile> mutableProfiles = new ArrayList<>(List.of(first, profile(102, 1, 3,
                Set.of(AccessCategory.BE, AccessCategory.BK))));
        TcontTopology topology = new TcontTopology(1, mutableProfiles);
        mutableProfiles.clear();
        assertEquals(2, topology.profiles().size());
        assertThrows(UnsupportedOperationException.class, () -> topology.profiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> topology.allocationOwners().clear());
        List<DbaContext.Allocation> mutableAllocations = new ArrayList<>(List.of(allocation(first, 1200L)));
        DbaContext context = new DbaContext(125000, 2, RATE, PERIOD_NS, 205, mutableAllocations);
        mutableAllocations.clear();
        assertEquals(first, context.allocations().get(0).profile());
        assertThrows(UnsupportedOperationException.class, () -> context.allocations().clear());
    }

    @Test
    void unknownZeroAndOutstandingAreTrackedPerAllocationEvenOnTheSameSfu() {
        TcontProfile first = profile(101, 1, 2, Set.of(AccessCategory.VO, AccessCategory.VI));
        TcontProfile second = profile(102, 1, 3, Set.of(AccessCategory.BE, AccessCategory.BK));
        DbaContext.Allocation waiting = new DbaContext.Allocation(101, 1, 6000L, 0L, 0L, true, first);
        DbaContext context = new DbaContext(125000, 2, RATE, PERIOD_NS, 205,
                List.of(waiting, allocation(second, 1200L)));
        DbaPlan plan = new MultiGrantRoundRobinDbaPolicy().plan(context);
        assertEquals(1, plan.grants().size());
        assertEquals(102, plan.grants().get(0).allocationId());
        assertEquals(6000L, waiting.knownDemandBytes());
        assertTrue(waiting.budgetOutstanding());
        DbaContext empty = new DbaContext(125000, 2, RATE, PERIOD_NS, 205,
                List.of(allocation(first, null), allocation(second, 0L)));
        assertFalse(empty.allocations().get(0).known());
        assertTrue(empty.allocations().get(1).known());
        assertTrue(new MultiGrantRoundRobinDbaPolicy().plan(empty).grants().isEmpty());
    }

    @Test
    void twoAllocationObjectsOnOnePhysicalSfuMayHaveAdjacentValidatedSlots() {
        List<DbaContext.Allocation> objects = List.of(allocation(profile(101, 1, 2,
                Set.of(AccessCategory.VO, AccessCategory.VI)), 1200L), allocation(profile(102, 1, 3,
                Set.of(AccessCategory.BE, AccessCategory.BK)), 1200L));
        DbaContext context = new DbaContext(125000, 2, RATE, PERIOD_NS, 205, objects);
        DbaPlan plan = new MultiGrantRoundRobinDbaPolicy().plan(context);
        assertEquals(List.of(101L, 102L), plan.grants().stream().map(DbaPlan.Grant::allocationId)
                .collect(Collectors.toList()));
        List<DbaPlanValidator.Slot> slots = new DbaPlanValidator().validateAndPreview(context, plan);
        assertEquals(250205, slots.get(0).startNs());
        assertEquals(254045, slots.get(0).endNs());
        assertEquals(slots.get(0).endNs(), slots.get(1).startNs());
        assertEquals(257885, slots.get(1).endNs());
    }

    @Test
    void parametersAreNotYetUsedAsGiantAssuredOrSurplusBudgetRules() {
        TcontProfile original = profile(101, 1, 2, Set.of(AccessCategory.VO, AccessCategory.VI));
        TcontProfile changed = new TcontProfile(101, 1, 3, original.classes(), 8, 2, 24, 12);
        DbaContext first = new DbaContext(125000, 2, RATE, PERIOD_NS, 205, List.of(allocation(original, 6000L)));
        DbaContext second = new DbaContext(125000, 2, RATE, PERIOD_NS, 205, List.of(allocation(changed, 6000L)));
        DbaPlan a = new MultiGrantRoundRobinDbaPolicy().plan(first);
        DbaPlan b = new MultiGrantRoundRobinDbaPolicy().plan(second);
        assertEquals(6000, a.grants().get(0).plannedBytes());
        assertEquals(a.grants().get(0).plannedBytes(), b.grants().get(0).plannedBytes());
        assertEquals(a.grants().get(0).plannedDurationNs(), b.grants().get(0).plannedDurationNs());
        assertEquals(a.decisionReason(), b.decisionReason());
    }

    @Test
    void samePhysicalSfuQueueViewCannotBeLoadedIntoAnotherAllocationBudget() {
        PonGrantPacketFiller filler = new PonGrantPacketFiller(RATE, Map.of(101L, 1, 102L, 1));
        PonGrantPacketFiller.QueueView first = new PonGrantPacketFiller.QueueView(101, 1, 0,
                List.of(new PonGrantPacketFiller.PacketView(1, 1200, 0)));
        assertEquals(List.of(1), filler.fill(101, 1, 1200, 3840, 125000, first).selectedPacketIds());
        assertThrows(IllegalArgumentException.class, () -> filler.fill(102, 1, 1200, 3840, 125000, first));
        assertThrows(IllegalArgumentException.class, () -> filler.fill(999, 1, 1200, 3840, 125000, first));
        assertThrows(IllegalArgumentException.class, () -> filler.fill(101, 2, 1200, 3840, 125000, first));
        assertEquals(1, first.packets().size(), "Rejected cross-allocation loading must not change the view");
    }

    @Test
    void reportsAndBudgetDebitStayIndependentWithinOnePhysicalSfu(@TempDir Path directory) throws IOException {
        Mfu mfu = new Mfu(List.of(1), null, true);
        mfu.configureTcont(load(directory, configuration(1, "rr-multi", List.of(), List.of())).tcontTopology());
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            Packet carrier = new Packet(1, 1200, 0, AccessCategory.VO, Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID);
            assertFalse(mfu.estimateAllocationDemand(101, 0).known());
            assertFalse(mfu.estimateAllocationDemand(102, 0).known());
            mfu.receiveUplinkReport(new PonUplinkReport(0, 101, 1, 0, 5, 6000, 5, 6000), carrier, context);
            assertFalse(mfu.estimateAllocationDemand(102, 0).known(), "A sibling REPORT cannot make another FIFO known");
            mfu.receiveUplinkReport(new PonUplinkReport(0, 102, 1, 0, 2, 2400, 2, 2400), carrier, context);
            PonDbaGrant budget = new PonDbaGrant(1, 101, 1, 0, 1, 125000, 128840, 1200);
            mfu.recordDbaBudgetCreated(budget, context);
            mfu.recordDbaBudgetCreated(budget, context);
            assertEquals(4800, mfu.estimateAllocationDemand(101, 0).effectiveKnownBytes());
            assertEquals(2400, mfu.estimateAllocationDemand(102, 0).effectiveKnownBytes());
            mfu.receiveUplinkReport(new PonUplinkReport(0, 101, 1, 0, 5, 6000, 5, 6000), carrier, context);
            assertEquals(4800, mfu.estimateAllocationDemand(101, 0).effectiveKnownBytes(), "Repeated REPORT cannot restore already budgeted bytes");
            assertEquals(102, mfu.latestAllocationReport(102).report().allocationId());
            assertThrows(IllegalStateException.class, () -> mfu.latestUplinkReport(1));
        }
    }

    @Test
    void delayedAndPostLoadReportsReconcileOnlyTheirOwnAllocation(@TempDir Path directory) throws IOException {
        Mfu mfu = new Mfu(List.of(1), null, true);
        mfu.configureTcont(load(directory, configuration(1, "rr-multi", List.of(), List.of())).tcontTopology());
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            Packet carrier = new Packet(1, 1200, 0, AccessCategory.VO, Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID);
            mfu.receiveUplinkReport(new PonUplinkReport(0, 101, 1, 0, 5, 6000, 5, 6000), carrier, context);
            mfu.receiveUplinkReport(new PonUplinkReport(0, 102, 1, 0, 2, 2400, 2, 2400), carrier, context);
            context.scheduleAfterNanoseconds(10, EventType.PACKET_GENERATED, carrier, 1);
            context.nextEvent();
            mfu.recordDbaBudgetCreated(new PonDbaGrant(1, 101, 1, 10, 1, 125000, 128840, 1200), context);
            context.scheduleAfterNanoseconds(10, EventType.PACKET_GENERATED, carrier, 1);
            context.nextEvent();
            // 快照5ns先于10ns预算，但直到20ns才送达；历史预算必须立即扣减，且仅属于101。
            mfu.receiveUplinkReport(new PonUplinkReport(0, 101, 1, 5, 5, 6000, 5, 6000), carrier, context);
            assertEquals(4800, mfu.estimateAllocationDemand(101, 20).effectiveKnownBytes());
            assertEquals(2400, mfu.estimateAllocationDemand(102, 20).effectiveKnownBytes());
            mfu.receiveUplinkReport(new PonUplinkReport(0, 102, 1, 15, 0, 0, 0, 0), carrier, context);
            assertEquals(0, mfu.estimateAllocationDemand(102, 20).effectiveKnownBytes());
            assertEquals(4800, mfu.estimateAllocationDemand(101, 20).effectiveKnownBytes());
            mfu.receiveUplinkReport(new PonUplinkReport(0, 101, 1, 2, 9, 9999, 9, 9999), carrier, context);
            assertEquals(4800, mfu.estimateAllocationDemand(101, 20).effectiveKnownBytes(), "Old report must not roll back the latest snapshot");
            context.scheduleAfterNanoseconds(125010, EventType.PACKET_GENERATED, carrier, 1);
            context.nextEvent();
            // 装载之后的快照已排除所选1200B，不能再次扣预算，也不能改变兄弟对象的已知0B。
            mfu.receiveUplinkReport(new PonUplinkReport(1, 101, 1, 125005, 4, 4800, 4, 4800), carrier, context);
            assertEquals(4800, mfu.estimateAllocationDemand(101, context.nowTicks()).effectiveKnownBytes());
            assertEquals(0, mfu.estimateAllocationDemand(102, context.nowTicks()).effectiveKnownBytes());
        }
    }

    @Test
    void rejectsWrongOwnerReportsAndCrossAllocationBudgetSettlements(@TempDir Path directory) throws IOException {
        Mfu mfu = new Mfu(List.of(1, 2), null, true);
        mfu.configureTcont(load(directory, configuration(2, "rr-multi", List.of(), List.of())).tcontTopology());
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(out), true, true);
            Packet carrier = new Packet(1, 1200, 0, AccessCategory.VO, Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID);
            assertThrows(IllegalArgumentException.class, () -> mfu.receiveUplinkReport(
                    new PonUplinkReport(0, 201, 1, 0, 1, 1200, 1, 1200), carrier, context));
            assertThrows(IllegalArgumentException.class, () -> mfu.receiveUplinkReport(
                    new PonUplinkReport(0, 999, 1, 0, 1, 1200, 1, 1200), carrier, context));
            assertFalse(mfu.estimateAllocationDemand(201, 0).known());
        }
        PonDemandLedger ledger = new PonDemandLedger();
        assertTrue(ledger.recordAllocationBudgetGrant(101, 1, 11, 10, 100, 200, 1200));
        assertThrows(IllegalArgumentException.class, () -> ledger.settleAllocationBudgetGrant(102, 1, 11, 300, 1200));
        assertThrows(IllegalArgumentException.class, () -> ledger.settleAllocationBudgetGrant(101, 2, 11, 300, 1200));
        assertTrue(ledger.hasOutstandingAllocationBudget(101, 300));
        assertFalse(ledger.hasOutstandingAllocationBudget(102, 300));
        assertTrue(ledger.settleAllocationBudgetGrant(101, 1, 11, 300, 1200));
        assertFalse(ledger.hasOutstandingAllocationBudget(101, 300));
    }

    @Test
    void sameSfuQueuesReportsGrantsDataAndSettlementKeepAllocationIdentity(@TempDir Path directory) throws IOException {
        List<PacketInput> packets = List.of(new PacketInput(1, 1200, 0, "VO"),
                new PacketInput(1, 1200, 0, "BE"), new PacketInput(1, 600, 2, "VO"),
                new PacketInput(1, 800, 2, "BE"));
        List<Map<String, String>> trace = run(directory, configuration(1, "rr-multi", packets, List.of()));
        assertEquals(List.of("1", "3"), packetIds(events(trace, "TCONT_ENQUEUE"), "101"));
        assertEquals(List.of("2", "4"), packetIds(events(trace, "TCONT_ENQUEUE"), "102"));
        assertEquals(List.of("1", "3"), packetIds(events(trace, "PON_DBA_DATA_START"), "101"));
        assertEquals(List.of("2", "4"), packetIds(events(trace, "PON_DBA_DATA_START"), "102"));
        assertTrue(events(trace, "TCONT_REPORT_RX").stream().anyMatch(row -> "101".equals(row.get("allocation_id"))));
        assertTrue(events(trace, "TCONT_REPORT_RX").stream().anyMatch(row -> "102".equals(row.get("allocation_id"))));
        assertConservationAndIsolation(trace, 4, Map.of("101", "1", "102", "1"));
    }

    @Test
    void sameSfuDifferentAllocationsCanActuallySendWithinOneTargetFrame(@TempDir Path directory) throws IOException {
        List<PacketInput> packets = List.of(new PacketInput(1, 38000, 0, "VO"),
                new PacketInput(1, 1200, 0, "VO"), new PacketInput(1, 1200, 0, "BE"),
                new PacketInput(1, 1200, 600, "BE"));
        List<Map<String, String>> trace = run(directory, configuration(1, "rr-multi", packets, List.of()));
        Map<String, List<Map<String, String>>> byFrame = events(trace, "PON_DBA_BWMAP_TX").stream()
                .collect(Collectors.groupingBy(row -> row.get("target_frame_id")));
        assertTrue(byFrame.values().stream().anyMatch(rows -> rows.size() == 2
                && rows.stream().map(row -> row.get("allocation_id")).collect(Collectors.toSet())
                .equals(Set.of("101", "102"))), "Natural REPORTs must permit same-SFU multi-allocation grants");
        assertConservationAndIsolation(trace, 4, Map.of("101", "1", "102", "1"));
    }

    @Test
    void twoSfusThreeAllocationObjectsShareOneNonOverlappingUpstream(@TempDir Path directory) throws IOException {
        List<PacketInput> packets = List.of(new PacketInput(1, 1200, 0, "VO"),
                new PacketInput(1, 1200, 0, "BE"), new PacketInput(2, 1200, 0, "BK"),
                new PacketInput(1, 800, 800, "VI"), new PacketInput(2, 900, 800, "VO"));
        List<Map<String, String>> trace = run(directory, configuration(2, "rr-multi", packets, List.of()));
        assertEquals(Set.of("101", "102", "201"), events(trace, "PON_DBA_BWMAP_TX").stream()
                .map(row -> row.get("allocation_id")).collect(Collectors.toSet()));
        assertConservationAndIsolation(trace, 5, Map.of("101", "1", "102", "1", "201", "2"));
    }

    @Test
    void threeSfusAndFourAllocationObjectsAreNoLongerLimitedToTwoPhysicalDevices(@TempDir Path directory) throws IOException {
        List<PacketInput> packets = List.of(new PacketInput(1, 1200, 0, "VO"),
                new PacketInput(1, 1200, 0, "BE"), new PacketInput(2, 1200, 0, "BK"),
                new PacketInput(3, 1200, 0, "VI"));
        List<Map<String, String>> trace = run(directory, configuration(3, "rr-multi", packets, List.of()));
        assertConservationAndIsolation(trace, 4, Map.of("101", "1", "102", "1", "201", "2", "301", "3"));
    }

    @Test
    void configuredSfuWithoutAssociatedStaCanStillReturnAnEmptyPolledReport(@TempDir Path directory) throws IOException {
        String settings = configuration(2, "rr-multi", List.of(), List.of()).lines()
                .filter(line -> !line.startsWith("sta.2.sfu.id=")).collect(Collectors.joining("\n"))
                + "\nsta.count=1\n";
        List<Map<String, String>> trace = run(directory, settings);
        assertTrue(events(trace, "TCONT_REPORT_RX").stream().anyMatch(row -> "201".equals(row.get("allocation_id"))
                && "0".equals(row.get("pending_bytes")) && "REPORT_ONLY".equals(row.get("report_kind"))));
        assertTrue(events(trace, "TCONT_DEMAND").stream().anyMatch(row -> "201".equals(row.get("allocation_id"))
                && "KNOWN".equals(row.get("state")) && "0".equals(row.get("effective_known_bytes"))));
        assertTrue(events(trace, "PON_DBA_BWMAP_TX").stream().noneMatch(row -> "201".equals(row.get("allocation_id"))));
    }

    @Test
    void blockedFifoHeadDoesNotBlockOtherAllocationOnTheSameSfu(@TempDir Path directory) throws IOException {
        List<PacketInput> packets = List.of(new PacketInput(1, 39062, 0, "VO"),
                new PacketInput(1, 1200, 0, "VO"), new PacketInput(1, 1200, 0, "BE"));
        List<Map<String, String>> trace = run(directory, configuration(1, "rr-multi", packets,
                List.of("fiber.uplink.poll.end.us=1000")));
        assertEquals(List.of("3"), events(trace, "DELIVERED").stream().map(row -> row.get("packet_id"))
                .collect(Collectors.toList()));
        assertTrue(events(trace, "PON_DBA_FILL").stream().filter(row -> "101".equals(row.get("allocation_id")))
                .allMatch(row -> "0".equals(row.get("selected_bytes"))));
        assertTrue(events(trace, "PON_DBA_SETTLEMENT_RX").stream().filter(row -> "102".equals(row.get("allocation_id")))
                .anyMatch(row -> "1200".equals(row.get("actual_bytes"))));
        assertWindows(trace);
    }

    @Test
    void repeatedSimulationAndBandwidthOnlyChangesLeaveExecutionDeterministic(@TempDir Path directory) throws IOException {
        List<PacketInput> packets = List.of(new PacketInput(1, 1200, 0, "VO"), new PacketInput(1, 600, 10, "BE"));
        String settings = configuration(1, "rr-multi", packets, List.of());
        List<Map<String, String>> first = run(directory, settings);
        assertEquals(first, run(directory, settings));
        List<Map<String, String>> changed = run(directory, settings + "fiber.uplink.tcont.alloc.101.si.max.frames=8\n"
                + "fiber.uplink.tcont.alloc.101.ab.min.bytes=24\n"
                + "fiber.uplink.tcont.alloc.102.si.min.frames=7\n"
                + "fiber.uplink.tcont.alloc.102.ab.sur.bytes=12\n");
        // 参数观察行可以变化；DATA/BWmap/REPORT与预算结算的真实协议时序必须一致。
        Set<String> phases = Set.of("PON_DBA_BWMAP_TX", "PON_DBA_BWMAP_RX", "PON_DBA_FILL", "PON_DBA_DATA_START",
                "PON_DBA_DATA_END", "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX", "DELIVERED", "TCONT_REPORT_RX");
        assertEquals(first.stream().filter(row -> phases.contains(row.getOrDefault("event", ""))).collect(Collectors.toList()),
                changed.stream().filter(row -> phases.contains(row.getOrDefault("event", ""))).collect(Collectors.toList()));
    }

    @Test
    void oldConfigurationKeepsDisabledTopologyAndLegacyAllocationMapping(@TempDir Path directory) throws IOException {
        String old = configuration(2, "rr-multi", List.of(), List.of()).lines()
                .filter(line -> !line.startsWith("fiber.uplink.tcont.")).collect(Collectors.joining("\n")) + "\n";
        SimulationConfig config = load(directory, old);
        assertFalse(config.tcontTopology().enabled());
        assertEquals(2, config.tcontTopology().allocationFor(1, AccessCategory.VO));
        assertEquals(4, config.tcontTopology().allocationFor(2, AccessCategory.BK));
        assertEquals(run(directory, old), run(directory, old + "fiber.uplink.tcont.enabled=false\n"));
    }

    @Test
    void rejectsOverlappingMissingAndInvalidOwnershipMappings(@TempDir Path directory) throws IOException {
        String base = configuration(2, "rr-multi", List.of(), List.of());
        for (String setting : List.of("fiber.uplink.tcont.alloc.102.classes=VO,BE,BK",
                "fiber.uplink.tcont.alloc.102.classes=BE", "fiber.uplink.tcont.alloc.201.sfu.id=3",
                "fiber.uplink.tcont.alloc.ids=101,101,201", "fiber.uplink.tcont.alloc.ids=0,102,201")) {
            assertThrows(IllegalArgumentException.class, () -> load(directory, base + setting + "\n"), setting);
        }
    }

    @Test
    void rejectsInvalidTypeSpecificBandwidthFields(@TempDir Path directory) throws IOException {
        String base = configuration(2, "rr-multi", List.of(), List.of());
        for (String setting : List.of("fiber.uplink.tcont.alloc.101.si.max.frames=0",
                "fiber.uplink.tcont.alloc.101.si.min.frames=1", "fiber.uplink.tcont.alloc.101.ab.sur.bytes=1",
                "fiber.uplink.tcont.alloc.101.ab.min.bytes=0", "fiber.uplink.tcont.alloc.102.si.min.frames=0",
                "fiber.uplink.tcont.alloc.102.ab.sur.bytes=-1", "fiber.uplink.tcont.alloc.201.ab.min.bytes=-1",
                "fiber.uplink.tcont.alloc.201.ab.sur.bytes=0", "fiber.uplink.tcont.alloc.101.type=1")) {
            assertThrows(IllegalArgumentException.class, () -> load(directory, base + setting + "\n"), setting);
        }
    }

    @Test
    void rejectsActiveTcontOutsideSupportedExecutionModeAndDisabledStrayKeys(@TempDir Path directory) throws IOException {
        String base = configuration(1, "rr-multi", List.of(), List.of());
        for (String setting : List.of("fiber.uplink.dba.mode=fcfs", "fiber.uplink.dba.mode=shadow-rr",
                "fiber.uplink.bwmap.enabled=false", "fiber.uplink.report.enabled=false",
                "fiber.uplink.tcont.enabled=false")) {
            assertThrows(IllegalArgumentException.class, () -> load(directory, base + setting + "\n"), setting);
        }
    }

    private static TcontProfile profile(long id, int sfu, int type, Set<AccessCategory> classes) {
        return new TcontProfile(id, sfu, type, classes, 1, type == 2 ? 0 : 2,
                type == 4 ? 0 : 2400, type == 2 ? 0 : 1200);
    }

    private static DbaContext.Allocation allocation(TcontProfile profile, Long bytes) {
        return new DbaContext.Allocation(profile.allocationId(), profile.sfuId(), bytes,
                bytes == null ? null : 0L, bytes == null ? null : 0L, false, profile);
    }

    private static SimulationConfig load(Path directory, String settings) throws IOException {
        Path path = directory.resolve("tcont.properties");
        Files.writeString(path, settings, StandardCharsets.UTF_8);
        return ConfigLoader.load(path);
    }

    private static List<Map<String, String>> run(Path directory, String settings) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(load(directory, settings), new TraceLogger(out));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().map(TcontBandwidthTest::fields)
                .collect(Collectors.toList());
    }

    private static void assertConservationAndIsolation(List<Map<String, String>> trace, int count,
                                                        Map<String, String> owners) {
        List<Map<String, String>> enqueue = events(trace, "TCONT_ENQUEUE");
        assertEquals(count, enqueue.size());
        assertEquals(count, events(trace, "DELIVERED").size());
        assertEquals(count, new HashSet<>(events(trace, "DELIVERED").stream().map(row -> row.get("packet_id"))
                .collect(Collectors.toList())).size());
        Map<String, String> packetAllocations = enqueue.stream().collect(Collectors.toMap(
                row -> row.get("packet_id"), row -> row.get("allocation_id")));
        for (Map<String, String> row : trace) {
            if (row.containsKey("allocation_id") && owners.containsKey(row.get("allocation_id")) && row.containsKey("sfu_id")) {
                assertEquals(owners.get(row.get("allocation_id")), row.get("sfu_id"), row.toString());
            }
            if ("PON_DBA_DATA_START".equals(row.get("event"))) {
                assertEquals(packetAllocations.get(row.get("packet_id")), row.get("allocation_id"));
            }
        }
        List<Map<String, String>> budgets = events(trace, "PON_DBA_BWMAP_TX");
        Set<String> grantIds = budgets.stream().map(row -> row.get("grant_id")).collect(Collectors.toSet());
        assertEquals(budgets.size(), grantIds.size());
        for (String phase : List.of("PON_DBA_LEDGER_CREATED", "PON_DBA_BWMAP_RX", "PON_DBA_FILL",
                "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX")) {
            List<Map<String, String>> records = events(trace, phase);
            assertEquals(budgets.size(), records.size(), phase);
            assertEquals(grantIds, records.stream().map(row -> row.get("grant_id")).collect(Collectors.toSet()), phase);
        }
        for (Map<String, String> settled : events(trace, "PON_DBA_SETTLEMENT_RX")) {
            assertEquals(Long.parseLong(settled.get("budget_bytes")), Long.parseLong(settled.get("actual_bytes"))
                    + Long.parseLong(settled.get("unused_bytes")));
        }
        for (String id : owners.keySet()) {
            assertEquals(packetIds(enqueue, id), packetIds(events(trace, "PON_DBA_DATA_START"), id),
                    "Per-allocation optical FIFO must survive REPORT and grant execution");
        }
        assertWindows(trace);
    }

    private static void assertWindows(List<Map<String, String>> trace) {
        List<Map<String, String>> windows = new ArrayList<>(events(trace, "PON_DBA_BWMAP_TX"));
        windows.addAll(events(trace, "PON_POLL_BWMAP_TX"));
        windows.sort(Comparator.comparingLong(row -> Long.parseLong(row.get("tx_start_ns"))));
        long previous = 0;
        for (Map<String, String> row : windows) {
            long start = Long.parseLong(row.get("tx_start_ns"));
            long end = Long.parseLong(row.get("tx_end_ns"));
            long frame = Long.parseLong(row.get("target_frame_id"));
            assertTrue(start >= previous && end <= (frame + 1) * PERIOD_NS && start >= frame * PERIOD_NS);
            previous = end;
        }
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String action) {
        return trace.stream().filter(row -> action.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static List<String> packetIds(List<Map<String, String>> trace, String allocation) {
        return trace.stream().filter(row -> allocation.equals(row.get("allocation_id")))
                .map(row -> row.get("packet_id")).collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2) row.put(pair[0], pair[1]);
        }
        return row;
    }

    /** 测试专用确定性场景构造；生产流量仍从 STA 经既有无线链路进入光队列。 */
    private static String configuration(int sfus, String mode, List<PacketInput> input, List<String> extra) {
        List<PacketInput> packets = input.isEmpty() ? List.of(new PacketInput(1, 1200, 0, "VO")) : input;
        List<String> settings = new ArrayList<>(List.of("traffic.direction=UL", "traffic.mode=manual",
                "sfu.count=" + sfus, "sta.count=" + sfus, "wifi.model=fixed", "wifi.delay.us=0",
                "wifi.mac.access=fixed-priority", "packet.bytes=1200", "packet.arrival.us=" + packets.stream()
                .map(packet -> Integer.toString(packet.timeUs)).collect(Collectors.joining(",")),
                "fiber.uplink.mode=pon-framed", "fiber.uplink.rate.mbps=2500", "fiber.uplink.frame.period.us=125",
                "fiber.uplink.propagation.us=10.025", "fiber.uplink.report.enabled=true",
                "fiber.uplink.bwmap.enabled=true", "fiber.uplink.demand.ledger.enabled=true",
                "fiber.uplink.poll.enabled=true", "fiber.uplink.poll.bytes=64", "fiber.uplink.poll.interval.frames=1",
                "fiber.uplink.poll.end.us=2500", "fiber.uplink.dba.mode=" + mode,
                "fiber.downlink.mode=pon-shared", "fiber.downlink.rate.mbps=10000", "fiber.downlink.propagation.us=10",
                "fiber.uplink.tcont.enabled=true", "fiber.uplink.tcont.alloc.ids="
                + (sfus == 1 ? "101,102" : sfus == 2 ? "101,102,201" : "101,102,201,301")));
        for (int sfu = 1; sfu <= sfus; sfu++) settings.add("sta." + sfu + ".sfu.id=" + sfu);
        addProfile(settings, 101, 1, 2, "VO,VI", 1, 0, 2400, 0);
        addProfile(settings, 102, 1, 3, "BE,BK", 1, 2, 2400, 1200);
        if (sfus >= 2) addProfile(settings, 201, 2, 4, "VO,VI,BE,BK", 1, 1, 0, 1200);
        if (sfus >= 3) addProfile(settings, 301, 3, 4, "VO,VI,BE,BK", 1, 1, 0, 1200);
        for (int index = 0; index < packets.size(); index++) {
            PacketInput packet = packets.get(index);
            settings.add("packet." + (index + 1) + ".sta.id=" + packet.sta);
            settings.add("packet." + (index + 1) + ".bytes=" + packet.bytes);
            settings.add("packet." + (index + 1) + ".type=" + packet.type);
        }
        settings.addAll(extra);
        return String.join("\n", settings) + "\n";
    }

    private static void addProfile(List<String> settings, long id, int sfu, int type, String classes,
                                   int siMax, int siMin, long abMin, long abSur) {
        String prefix = "fiber.uplink.tcont.alloc." + id + ".";
        settings.add(prefix + "sfu.id=" + sfu);
        settings.add(prefix + "type=" + type);
        settings.add(prefix + "classes=" + classes);
        settings.add(prefix + "si.max.frames=" + siMax);
        settings.add(prefix + "si.min.frames=" + siMin);
        settings.add(prefix + "ab.min.bytes=" + abMin);
        settings.add(prefix + "ab.sur.bytes=" + abSur);
    }

    private static final class PacketInput {
        final int sta;
        final int bytes;
        final int timeUs;
        final String type;

        PacketInput(int sta, int bytes, int timeUs, String type) {
            this.sta = sta;
            this.bytes = bytes;
            this.timeUs = timeUs;
            this.type = type;
        }
    }

    /**
     * CSV生成脚本调用此测试辅助入口：实际经 Java ConfigLoader 加载每份配置，
     * 打印成功的参数值或真实拒绝原因。脚本不能把Python配置字典当作Java实测结果。
     */
    public static void main(String[] args) throws IOException {
        for (String argument : args) {
            Path path = Path.of(argument);
            String scenario = path.getFileName().toString().replace(".properties", "");
            try {
                SimulationConfig config = ConfigLoader.load(path);
                for (TcontProfile profile : config.tcontTopology().profiles()) {
                    String classes = profile.classes().stream().sorted().map(Enum::name).collect(Collectors.joining(";"));
                    System.out.println("event=TCONT_PROFILE scenario_id=" + scenario + " allocation_id=" + profile.allocationId()
                            + " sfu_id=" + profile.sfuId() + " type=" + profile.type() + " classes=" + classes
                            + " si_max_frames=" + profile.siMaxFrames() + " si_min_frames=" + profile.siMinFrames()
                            + " ab_min_bytes=" + profile.abMinBytes() + " ab_sur_bytes=" + profile.abSurBytes()
                            + " actual_result=ACCEPTED validation_reason=VALID_PROFILE");
                }
                System.out.println("event=TCONT_CONFIG_VALIDATION scenario_id=" + scenario + " actual_result=ACCEPTED validation_reason=VALID_CONFIG");
            } catch (IllegalArgumentException error) {
                System.out.println("event=TCONT_CONFIG_VALIDATION scenario_id=" + scenario + " actual_result=REJECTED validation_reason="
                        + error.getMessage().replace(' ', '_').replace('\n', '_').replace('\r', '_'));
            }
        }
    }
}
