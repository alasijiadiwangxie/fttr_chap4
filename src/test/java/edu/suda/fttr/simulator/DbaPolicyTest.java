package edu.suda.fttr.simulator;

import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.DbaPolicy;
import edu.suda.fttr.dba.RoundRobinDbaPolicy;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDbaShadowPlanner;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只验证第 10 步策略、不可变输入输出和只读预览，不替换真实 PON 授权执行。 */
class DbaPolicyTest {
    private static final long FRAME_NS = 125_000;

    @Test
    void preservesAllSixStepNineReferenceDecisions() {
        DbaPolicy policy = new RoundRobinDbaPolicy();
        // 来自第 9 步实际事件轨迹：前两轮 UNKNOWN，随后报告和真实授权改变已知需求。
        long[] times = {125_000, 250_000, 375_000, 500_000, 625_000, 750_000};
        Long[][] demands = {{null, null}, {null, null}, {39_062L, null},
                {39_062L, 0L}, {39_062L, 0L}, {0L, 0L}};
        long[] controls = {205, 205, 205, 205, 0, 0};
        long[] bytes = {0, 0, 38_998, 38_998, 39_062, 0};
        long[] durations = {0, 0, 124_794, 124_794, 124_999, 0};
        String[] reasons = {"ALL_UNKNOWN", "ALL_UNKNOWN", "FRAME_CAPACITY_LIMIT",
                "FRAME_CAPACITY_LIMIT", "KNOWN_DEMAND", "NO_KNOWN_POSITIVE_DEMAND"};
        for (int row = 0; row < times.length; row++) {
            DbaContext context = new DbaContext(times[row], row + 2, BigDecimal.valueOf(2500),
                    FRAME_NS, controls[row], List.of(allocation(2, 1, demands[row][0]),
                    allocation(4, 2, demands[row][1])));
            DbaPlan plan = policy.plan(context);
            assertEquals(times[row], plan.decisionTimeNs(), "row " + row);
            assertEquals(row + 2, plan.targetFrameId(), "row " + row);
            assertEquals(reasons[row], plan.decisionReason(), "row " + row);
            assertEquals(bytes[row], plan.grants().stream().mapToLong(DbaPlan.Grant::plannedBytes).sum());
            assertEquals(durations[row], plan.grants().stream()
                    .mapToLong(DbaPlan.Grant::plannedDurationNs).sum());
            if (bytes[row] > 0) {
                assertEquals(1, plan.grants().size());
                assertEquals(1, plan.grants().get(0).sfuId());
            }
            new DbaPlanValidator().validateAndPreview(context, plan);
        }
    }

    @Test
    void plansSixThousandBytesThroughTheCommonInterfaceAndPreviewsAfterControlReservation() {
        DbaContext context = context(205, allocation(2, 1, 6_000L));
        DbaPolicy policy = new RoundRobinDbaPolicy();
        DbaPlan plan = policy.plan(context);
        assertEquals("KNOWN_DEMAND", plan.decisionReason());
        assertEquals(1, plan.grants().size());
        assertEquals(6_000, plan.grants().get(0).plannedBytes());
        assertEquals(19_200, plan.grants().get(0).plannedDurationNs());
        DbaPlanValidator.Slot slot = new DbaPlanValidator().validateAndPreview(context, plan).get(0);
        assertEquals(2, slot.allocationId());
        assertEquals(1, slot.sfuId());
        assertEquals(250_205, slot.startNs());
        assertEquals(269_405, slot.endNs());
        assertEquals(6_000, slot.plannedBytes());
    }

    @Test
    void retainsExactIntegerCapacityAndSerializationAtTheControlSlotBoundary() {
        DbaContext context = context(205, allocation(2, 1, 39_062L));
        assertEquals(124_795, context.availableNs());
        assertEquals(BigInteger.valueOf(38_998), context.capacityBytes());
        DbaPlan plan = new RoundRobinDbaPolicy().plan(context);
        DbaPlan.Grant grant = plan.grants().get(0);
        assertEquals(38_998, grant.plannedBytes());
        assertEquals(124_794, grant.plannedDurationNs());
        assertEquals(1, FRAME_NS - context.controlReservedNs() - grant.plannedDurationNs());
        assertEquals("FRAME_CAPACITY_LIMIT", plan.decisionReason());
        assertTrue(grant.plannedBytes() < 39_062, "Byte budgets do not promise whole-packet execution");
        assertEquals(374_999, new DbaPlanValidator().validateAndPreview(context, plan).get(0).endNs());
    }

    @Test
    void rotatesKnownPositiveObjectsDeterministicallyAndSkipsUnknownAndZero() {
        DbaContext context = context(205, allocation(8, 4, null), allocation(4, 2, 0L),
                allocation(6, 3, 2_400L), allocation(2, 1, 1_200L));
        DbaPolicy policy = new RoundRobinDbaPolicy();
        List<Integer> actual = new ArrayList<>();
        for (int round = 0; round < 6; round++) {
            DbaPlan plan = policy.plan(context);
            assertEquals(1, plan.grants().size());
            actual.add(plan.grants().get(0).sfuId());
        }
        assertEquals(List.of(1, 3, 1, 3, 1, 3), actual);
        assertFalse(context.allocations().get(0).known());
        assertNull(context.allocations().get(0).knownDemandBytes());
        assertTrue(context.allocations().get(1).known());
        assertEquals(0L, context.allocations().get(1).knownDemandBytes());
    }

    @Test
    void distinguishesUnknownAndKnownZeroAndDoesNotAdvanceCursorWithoutCapacity() {
        DbaPolicy policy = new RoundRobinDbaPolicy();
        assertEquals("ALL_UNKNOWN", policy.plan(context(205, allocation(2, 1, null))).decisionReason());
        assertEquals("NO_KNOWN_POSITIVE_DEMAND",
                policy.plan(context(205, allocation(2, 1, 0L))).decisionReason());
        DbaPlan noCapacity = policy.plan(context(FRAME_NS,
                allocation(2, 1, 1_200L), allocation(4, 2, 1_200L)));
        assertEquals("NO_DATA_CAPACITY", noCapacity.decisionReason());
        assertTrue(noCapacity.grants().isEmpty());
        assertEquals(1, policy.plan(context(205, allocation(2, 1, 1_200L),
                allocation(4, 2, 1_200L))).grants().get(0).sfuId());
    }

    @Test
    void defensivelyCopiesContextAndPlanListsAndReturnsAnImmutablePreview() {
        List<DbaContext.Allocation> allocations = new ArrayList<>(List.of(allocation(2, 1, 6_000L)));
        DbaContext context = new DbaContext(125_000, 2, BigDecimal.valueOf(2500),
                FRAME_NS, 205, allocations);
        allocations.clear();
        assertEquals(1, context.allocations().size());
        assertThrows(UnsupportedOperationException.class, () -> context.allocations().clear());
        List<DbaPlan.Grant> grants = new ArrayList<>(List.of(new DbaPlan.Grant(2, 1, 6_000, 19_200)));
        DbaPlan plan = new DbaPlan(125_000, 2, grants, "TEST");
        grants.clear();
        assertEquals(1, plan.grants().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.grants().clear());
        List<DbaPlanValidator.Slot> preview = new DbaPlanValidator().validateAndPreview(context, plan);
        assertThrows(UnsupportedOperationException.class, preview::clear);
        assertEquals(6_000L, context.allocations().get(0).knownDemandBytes());
    }

    @Test
    void validatesEveryExecutableCsvScenarioAgainstItsActualOutcome() {
        List<DbaPlanValidationCases.Outcome> outcomes = DbaPlanValidationCases.runCases();
        assertEquals(20, outcomes.size());
        for (DbaPlanValidationCases.Outcome outcome : outcomes) {
            assertTrue(outcome.matchesExpectation(), outcome.id());
        }
    }

    @Test
    void packsTwoObjectsWithoutOverlapAndKeepsAllocationAndSfuIdentitySeparate() {
        DbaContext context = context(205, allocation(101, 1, 1_200L), allocation(207, 2, 2_400L));
        DbaPlan plan = new DbaPlan(125_000, 2, List.of(new DbaPlan.Grant(101, 1, 1_200, 3_840),
                new DbaPlan.Grant(207, 2, 2_400, 7_680)), "TWO_OBJECTS");
        List<DbaPlanValidator.Slot> preview = new DbaPlanValidator().validateAndPreview(context, plan);
        assertEquals(2, preview.size());
        assertEquals(250_205, preview.get(0).startNs());
        assertEquals(254_045, preview.get(0).endNs());
        assertEquals(preview.get(0).endNs(), preview.get(1).startNs());
        assertEquals(261_725, preview.get(1).endNs());
        assertEquals(101, preview.get(0).allocationId());
        assertEquals(1, preview.get(0).sfuId());
        assertEquals(207, preview.get(1).allocationId());
        assertEquals(2, preview.get(1).sfuId());
    }

    @Test
    void allowsDistinctAllocationObjectsForOneSfuWithoutPretendingTheyAreTcontServices() {
        DbaContext context = context(205, allocation(101, 1, 1_200L), allocation(102, 1, 2_400L));
        DbaPlan plan = new DbaPlan(125_000, 2, List.of(new DbaPlan.Grant(101, 1, 1_200, 3_840),
                new DbaPlan.Grant(102, 1, 2_400, 7_680)), "TWO_LOGICAL_OBJECTS");
        List<DbaPlanValidator.Slot> preview = new DbaPlanValidator().validateAndPreview(context, plan);
        assertEquals(2, preview.size());
        assertEquals(1, preview.get(0).sfuId());
        assertEquals(1, preview.get(1).sfuId());
        assertEquals(101, preview.get(0).allocationId());
        assertEquals(102, preview.get(1).allocationId());
        assertTrue(preview.get(0).endNs() <= preview.get(1).startNs());
    }

    @Test
    void rejectsOverlappingExplicitWindowsAndAcceptsDisjointWindowsWithIdleGaps() {
        DbaContext context = context(205, allocation(2, 1, 1_200L), allocation(4, 2, 2_400L));
        DbaPlan overlapping = new DbaPlan(125_000, 2, List.of(
                new DbaPlan.Grant(2, 1, 1_200, 3_840, 205L),
                new DbaPlan.Grant(4, 2, 2_400, 7_680, 205L)), "OVERLAP");
        assertThrows(IllegalArgumentException.class,
                () -> new DbaPlanValidator().validateAndPreview(context, overlapping));
        DbaPlan disjoint = new DbaPlan(125_000, 2, List.of(
                new DbaPlan.Grant(2, 1, 1_200, 3_840, 205L),
                new DbaPlan.Grant(4, 2, 2_400, 7_680, 10_000L)), "GAP");
        List<DbaPlanValidator.Slot> preview = new DbaPlanValidator().validateAndPreview(context, disjoint);
        assertEquals(250_205, preview.get(0).startNs());
        assertEquals(260_000, preview.get(1).startNs());
        assertEquals(267_680, preview.get(1).endNs());
    }

    @Test
    void rejectsTargetsThatAreNotStrictlyAfterTheDecisionFrame() {
        DbaContext currentFrame = new DbaContext(125_000, 1, BigDecimal.valueOf(2500),
                FRAME_NS, 0, List.of(allocation(2, 1, 1_200L)));
        DbaPlan plan = new DbaPlan(125_000, 1, List.of(new DbaPlan.Grant(2, 1, 1_200, 3_840)), "PAST");
        assertThrows(IllegalArgumentException.class,
                () -> new DbaPlanValidator().validateAndPreview(currentFrame, plan));
    }

    @Test
    void planningAndPreviewCannotDebitRealMfuReportGrantHistory() {
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext simulation = new SimulationContext(new TraceLogger(output), true, true);
            Mfu mfu = new Mfu(List.of(1), null, true);
            advanceTo(simulation, 125_000);
            Packet reserved = new Packet(1, 2_400, 0, AccessCategory.BE,
                    Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID);
            mfu.recordUplinkGrantCreated(1, reserved, simulation);
            advanceTo(simulation, 200_000);
            PonUplinkReport report = new PonUplinkReport(1, 1, 125_000, 5, 6_000, 5, 6_000);
            mfu.receiveUplinkReport(report, reserved, simulation);
            PonDemandLedger.Estimate before = mfu.estimateUplinkDemand(1, 200_000);
            assertEquals(3_600, before.effectiveKnownBytes());
            DbaContext context = new DbaContext(200_000, 2, BigDecimal.valueOf(2500), FRAME_NS,
                    205, List.of(new DbaContext.Allocation(2, 1, before.effectiveKnownBytes(),
                    before.report().report().frameId(), before.reportAgeNs())));
            DbaPolicy policy = new RoundRobinDbaPolicy();
            for (int round = 0; round < 3; round++) {
                DbaPlan plan = policy.plan(context);
                assertEquals(3_600, plan.grants().get(0).plannedBytes());
                new DbaPlanValidator().validateAndPreview(context, plan);
            }
            PonDemandLedger.Estimate after = mfu.estimateUplinkDemand(1, 200_000);
            assertEquals(before.grantedBytesSinceSnapshot(), after.grantedBytesSinceSnapshot());
            assertEquals(2_400, after.grantedBytesSinceSnapshot());
            assertEquals(before.effectiveKnownBytes(), after.effectiveKnownBytes());
            assertEquals(6_000, report.pendingBytes());
            assertNull(simulation.nextEvent(), "A policy preview must not schedule transmission events");
        }
    }

    @Test
    void adapterAcceptsAnInjectedPurePolicyAndStillRejectsItsInvalidPlan() {
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext simulation = new SimulationContext(new TraceLogger(output), true, true);
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            advanceTo(simulation, 125_000);
            Packet reserved = new Packet(1, 2_400, 0, AccessCategory.BE,
                    Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID);
            mfu.recordUplinkGrantCreated(1, reserved, simulation);
            advanceTo(simulation, 200_000);
            PonUplinkReport report = new PonUplinkReport(1, 1, 125_000, 5, 6_000, 5, 6_000);
            mfu.receiveUplinkReport(report, reserved, simulation);

            // 算法只得到不可变估计值；不能接触 MFU、远端队列或创建真实授权的接口。
            DbaPolicy observeOnly = context -> {
                assertEquals(200_000, context.decisionTimeNs());
                assertEquals(2, context.targetFrameId());
                assertEquals(BigDecimal.valueOf(2500), context.rateMbps());
                assertEquals(FRAME_NS, context.framePeriodNs());
                assertEquals(205, context.controlReservedNs());
                assertEquals(2, context.allocations().size());
                DbaContext.Allocation known = context.allocations().get(0);
                assertEquals(2, known.allocationId());
                assertEquals(1, known.sfuId());
                assertEquals(3_600L, known.knownDemandBytes());
                assertEquals(1L, known.reportFrameId());
                assertEquals(75_000L, known.reportAgeNs());
                DbaContext.Allocation unknown = context.allocations().get(1);
                assertEquals(4, unknown.allocationId());
                assertEquals(2, unknown.sfuId());
                assertFalse(unknown.known());
                assertNull(unknown.knownDemandBytes());
                return new DbaPlan(context.decisionTimeNs(), context.targetFrameId(),
                        List.of(), "CUSTOM_OBSERVATION");
            };
            PonDbaShadowPlanner planner = new PonDbaShadowPlanner(List.of(2, 1),
                    BigDecimal.valueOf(2500), FRAME_NS, observeOnly);
            PonDbaShadowPlanner.Decision decision = planner.plan(200_000, 2, 205,
                    sfuId -> mfu.estimateUplinkDemand(sfuId, 200_000));
            assertEquals("CUSTOM_OBSERVATION", decision.decisionReason());
            assertEquals(0, decision.plannedGrantBytes());
            assertEquals(0, decision.selectedSfuId());
            assertTrue(decision.preview().isEmpty());

            // 自定义算法也必须经过同一个校验器，不能凭借注入入口绕过需求上限。
            DbaPolicy overBudget = context -> new DbaPlan(context.decisionTimeNs(), context.targetFrameId(),
                    List.of(new DbaPlan.Grant(2, 1, 3_601, 11_524)), "CUSTOM_OVER_BUDGET");
            PonDbaShadowPlanner invalidPlanner = new PonDbaShadowPlanner(List.of(1, 2),
                    BigDecimal.valueOf(2500), FRAME_NS, overBudget);
            assertThrows(IllegalArgumentException.class, () -> invalidPlanner.plan(200_000, 2, 205,
                    sfuId -> mfu.estimateUplinkDemand(sfuId, 200_000)));
            PonDemandLedger.Estimate after = mfu.estimateUplinkDemand(1, 200_000);
            assertEquals(2_400, after.grantedBytesSinceSnapshot());
            assertEquals(3_600, after.effectiveKnownBytes());
            assertEquals(6_000, report.pendingBytes());
            assertFalse(mfu.estimateUplinkDemand(2, 200_000).known());
            assertNull(simulation.nextEvent());
        }
    }

    @Test
    void executableValidationHarnessRegeneratesCsvFromRealValidatorCalls(@TempDir Path directory)
            throws IOException {
        Path csv = directory.resolve("plan-validation.csv");
        DbaPlanValidationCases.main(new String[] {csv.toString()});
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(21, lines.size());
        assertTrue(lines.get(0).startsWith("scenario_id,target_frame_id,proposed_bytes,"));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("capacity_38998,2,38998,205,124794,124795,")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("explicit_overlapping_windows,")
                && line.contains(",REJECTED,REJECTED,")));
    }

    private static DbaContext context(long controlNs, DbaContext.Allocation... allocations) {
        return new DbaContext(125_000, 2, BigDecimal.valueOf(2500), FRAME_NS, controlNs,
                List.of(allocations));
    }

    private static DbaContext.Allocation allocation(long id, int sfuId, Long bytes) {
        return new DbaContext.Allocation(id, sfuId, bytes, bytes == null ? null : 1L,
                bytes == null ? null : 0L);
    }

    private static void advanceTo(SimulationContext context, long timeNs) {
        context.scheduleAfterNanoseconds(timeNs - context.nowTicks(), EventType.PON_UPLINK_FRAME, null, 0);
        context.nextEvent();
    }
}
