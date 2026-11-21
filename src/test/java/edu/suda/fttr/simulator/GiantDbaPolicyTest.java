package edu.suda.fttr.simulator;

import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.GiantDbaPolicy;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.entity.AccessCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiantDbaPolicyTest {
    private static final BigDecimal RATE = BigDecimal.valueOf(2500);
    private static final long FRAME_NS = 125_000;

    @Test
    void independentSiMaxAndSiMinCountOneTwoAndFourTargetFrames() {
        GiantDbaPolicy policy = new GiantDbaPolicy();
        List<TcontProfile> profiles = List.of(profile(1, 1, 3, 1, 4, 1200, 600),
                profile(2, 1, 3, 2, 2, 1200, 600), profile(3, 2, 3, 4, 1, 1200, 600));
        List<DbaContext.Allocation> demand = profiles.stream().map(profile -> known(profile, 4000))
                .collect(Collectors.toList());

        for (int frame = 1; frame <= 8; frame++) {
            DbaContext input = input(frame, FRAME_NS, 205, demand);
            DbaPlan plan = policy.plan(input);
            new DbaPlanValidator().validateAndPreview(input, plan);
            for (TcontProfile profile : profiles) {
                GiantDbaPolicy.Decision row = decision(policy, profile.allocationId());
                int maxBefore = profile.siMaxFrames() - (frame - 1) % profile.siMaxFrames();
                int minBefore = profile.siMinFrames() - (frame - 1) % profile.siMinFrames();
                assertEquals(maxBefore, row.siMaxBefore(), "SImax before frame " + frame);
                assertEquals(minBefore, row.siMinBefore(), "SImin before frame " + frame);
                assertEquals(maxBefore == 1 ? profile.siMaxFrames() : maxBefore - 1, row.siMaxAfter());
                assertEquals(minBefore == 1 ? profile.siMinFrames() : minBefore - 1, row.siMinAfter());
                assertEquals(maxBefore == 1 ? 1200 : 0, row.assuredBytes());
                assertEquals(minBefore == 1 ? 600 : 0, row.surplusBytes());
                assertEquals(row.assuredBytes() + row.surplusBytes(), plan.grants().stream()
                        .filter(grant -> grant.allocationId() == profile.allocationId())
                        .mapToLong(DbaPlan.Grant::plannedBytes).sum());
            }
        }
    }

    @Test
    void assuredExpiryResetsWhileExpiredSurplusWaitsForARealBudget() {
        TcontProfile profile = profile(1, 1, 3, 2, 2, 800, 400);
        GiantDbaPolicy policy = new GiantDbaPolicy();

        policy.plan(input(1, FRAME_NS, 205, List.of(allocation(profile, null, false))));
        assertEquals("NOT_DUE", decision(policy, 1).assuredReason().name());
        assertEquals("NOT_DUE", decision(policy, 1).surplusReason().name());
        assertBlockedDecision(policy, 2, 205, allocation(profile, null, false), "UNKNOWN", "UNKNOWN", 2, 1);
        assertBlockedDecision(policy, 3, 205, allocation(profile, 1200L, true),
                "NOT_DUE", "AWAITING_SETTLEMENT", 1, 1);
        assertBlockedDecision(policy, 4, FRAME_NS, known(profile, 1200), "NO_CAPACITY", "NO_CAPACITY", 2, 1);
        assertBlockedDecision(policy, 5, 205, known(profile, 0), "NOT_DUE", "NO_DEMAND", 1, 1);

        DbaPlan ready = policy.plan(input(6, FRAME_NS, 205, List.of(known(profile, 1200))));
        assertEquals(1, ready.grants().size());
        assertEquals(1200, ready.grants().get(0).plannedBytes());
        assertEquals(800, decision(policy, 1).assuredBytes());
        assertEquals(400, decision(policy, 1).surplusBytes());
        assertEquals(2, decision(policy, 1).siMaxAfter());
        assertEquals(2, decision(policy, 1).siMinAfter());
    }

    @Test
    void guaranteedOrderWinsScarceCapacityAndTypeThreeMergesBothQuotas() {
        TcontProfile type4 = profile(1, 1, 4, 1, 1, 50, 4);
        TcontProfile type3 = profile(2, 1, 3, 1, 1, 1, 2);
        TcontProfile type2 = profile(3, 2, 2, 1, 0, 2, 0);
        DbaContext input = input(1, 20, 6, List.of(known(type4, 10), known(type3, 3), known(type2, 10)));
        GiantDbaPolicy policy = new GiantDbaPolicy();
        DbaPlan plan = policy.plan(input);

        // 两笔 2 B 授权各占 7 ns。第二笔先给保障 1 B（4 ns），额外 1 B 只占 3 ns 增量。
        assertEquals(List.of(3L, 2L), plan.grants().stream().map(DbaPlan.Grant::allocationId)
                .collect(Collectors.toList()));
        assertEquals(List.of(2L, 2L), plan.grants().stream().map(DbaPlan.Grant::plannedBytes)
                .collect(Collectors.toList()));
        assertEquals(1, decision(policy, 2).assuredBytes());
        assertEquals(1, decision(policy, 2).surplusBytes());
        assertEquals("PARTIAL_CAPACITY", decision(policy, 2).surplusReason().name());
        assertEquals("NO_CAPACITY", decision(policy, 1).surplusReason().name());
        List<DbaPlanValidator.Slot> slots = new DbaPlanValidator().validateAndPreview(input, plan);
        assertEquals(26, slots.get(0).startNs());
        assertEquals(33, slots.get(0).endNs());
        assertEquals(33, slots.get(1).startNs());
        assertEquals(40, slots.get(1).endNs());
    }

    @Test
    void wholeByteCapacityIsRoundedForEachAllocationEvenWhenNoSurplusIsMerged() {
        TcontProfile first = profile(7, 2, 2, 1, 0, 1, 0);
        TcontProfile second = profile(8, 1, 2, 1, 0, 1, 0);
        DbaContext input = input(1, 7, 0, List.of(known(second, 1), known(first, 1)));
        GiantDbaPolicy policy = new GiantDbaPolicy();
        DbaPlan plan = policy.plan(input);

        assertEquals(1, plan.grants().size(), "Two independent 1 B reservations require 8 ns");
        assertEquals(7, plan.grants().get(0).allocationId(), "Assured peers are ordered by Alloc-ID");
        assertEquals(4, plan.grants().get(0).plannedDurationNs());
        assertEquals("NO_CAPACITY", decision(policy, 8).assuredReason().name());
        new DbaPlanValidator().validateAndPreview(input, plan);
    }

    @Test
    void knownDemandLimitsTypeThreeAndTypeFourOnlyReceivesDueSurplus() {
        TcontProfile type3 = profile(2, 1, 3, 1, 1, 800, 900);
        TcontProfile type4 = profile(1, 2, 4, 1, 4, 900_000, 700);
        List<DbaContext.Allocation> allocations = List.of(known(type4, 4_000), known(type3, 1000));
        GiantDbaPolicy policy = new GiantDbaPolicy();

        for (int frame = 1; frame <= 4; frame++) {
            DbaContext input = input(frame, FRAME_NS, 205, allocations);
            DbaPlan plan = policy.plan(input);
            assertEquals(1000, plan.grants().get(0).plannedBytes());
            assertEquals(800, decision(policy, 2).assuredBytes());
            assertEquals(200, decision(policy, 2).surplusBytes());
            assertEquals(frame == 4 ? 2 : 1, plan.grants().size());
            GiantDbaPolicy.Decision bestEffort = decision(policy, 1);
            assertEquals(0, bestEffort.assuredBytes());
            assertEquals(0, bestEffort.siMaxBefore());
            assertEquals(0, bestEffort.siMaxAfter());
            assertEquals("INACTIVE", bestEffort.assuredReason().name());
            assertEquals(frame == 4 ? 700 : 0, bestEffort.surplusBytes());
            new DbaPlanValidator().validateAndPreview(input, plan);
        }
    }

    @Test
    void typeFourRotatesAmongDuePeersWhenOnlyOneFits() {
        TcontProfile first = profile(21, 2, 4, 1, 1, 0, 1);
        TcontProfile second = profile(22, 1, 4, 1, 1, 0, 1);
        List<DbaContext.Allocation> allocations = List.of(known(second, 4), known(first, 4));
        GiantDbaPolicy policy = new GiantDbaPolicy();
        for (int frame = 1; frame <= 4; frame++) {
            DbaContext input = input(frame, 4, 0, allocations);
            DbaPlan plan = policy.plan(input);
            assertEquals(1, plan.grants().size());
            assertEquals(frame % 2 == 1 ? 21 : 22, plan.grants().get(0).allocationId());
            new DbaPlanValidator().validateAndPreview(input, plan);
        }
    }

    private static void assertBlockedDecision(GiantDbaPolicy policy, int frame, long controlReservedNs,
                                              DbaContext.Allocation allocation,
                                              String assuredReason, String surplusReason,
                                              int siMaxAfter, int siMinAfter) {
        assertTrue(policy.plan(input(frame, FRAME_NS, controlReservedNs, List.of(allocation))).grants().isEmpty());
        GiantDbaPolicy.Decision row = decision(policy, allocation.allocationId());
        assertEquals(assuredReason, row.assuredReason().name());
        assertEquals(surplusReason, row.surplusReason().name());
        assertEquals(siMaxAfter, row.siMaxAfter());
        assertEquals(siMinAfter, row.siMinAfter());
    }

    private static GiantDbaPolicy.Decision decision(GiantDbaPolicy policy, long allocationId) {
        return policy.lastDecisions().stream().filter(row -> row.allocation().allocationId() == allocationId)
                .findFirst().orElseThrow();
    }

    private static DbaContext input(long frame, long frameNs, long controlReservedNs,
                                    List<DbaContext.Allocation> allocations) {
        return new DbaContext((frame - 1) * frameNs, frame, RATE, frameNs, controlReservedNs, allocations);
    }

    private static TcontProfile profile(long id, int sfuId, int type, int siMax, int siMin,
                                        long abMin, long abSur) {
        return new TcontProfile(id, sfuId, type, Set.of(AccessCategory.BE), siMax, siMin, abMin, abSur);
    }

    private static DbaContext.Allocation known(TcontProfile profile, long demand) {
        return allocation(profile, demand, false);
    }

    private static DbaContext.Allocation allocation(TcontProfile profile, Long demand, boolean outstanding) {
        return new DbaContext.Allocation(profile.allocationId(), profile.sfuId(), demand,
                demand == null ? null : 0L, demand == null ? null : 0L, outstanding, profile);
    }
}
