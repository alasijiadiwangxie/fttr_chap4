package edu.suda.fttr.simulator;

import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.dba.TcontTopology;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiantPipelinedLedgerTest {
    private static final long ALLOC = 101;
    private static final long PERIOD = 125_000;
    private static final Packet CARRIER = new Packet(1, 1200, 0, AccessCategory.VO, Packet.Direction.UL, 1,
            Packet.DEFAULT_MFU_ID);

    @Test
    void delayedSnapshotsDebitTwoPendingWindowsWithoutDebitingLoadAtSnapshotTime() {
        Mfu mfu = new Mfu(List.of(1), null, true);
        TcontProfile profile = new TcontProfile(ALLOC, 1, 2, EnumSet.allOf(AccessCategory.class), 1, 0, 2400, 0);
        mfu.configureTcont(new TcontTopology(1, List.of(profile)));
        mfu.enableGiantDba(true);
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream())) {
            SimulationContext clock = new SimulationContext(new TraceLogger(out), true, true);
            mfu.receiveUplinkReport(report(0, 0, 6000), CARRIER, clock);
            advanceTo(clock, PERIOD);
            mfu.recordDbaBudgetCreated(new PonDbaGrant(1, ALLOC, 1, PERIOD, 2, 250_205, 254_045, 1200), clock);
            advanceTo(clock, 2 * PERIOD);
            PonDbaGrant second = new PonDbaGrant(2, ALLOC, 1, 2 * PERIOD, 3, 375_205, 382_885, 2400);
            mfu.recordDbaBudgetCreated(second, clock);
            mfu.recordDbaBudgetCreated(second, clock);

            advanceTo(clock, 252_000);
            // 队列快照早于两个装载时隙，直到两笔预算都已发放后才到达 MFU。
            mfu.receiveUplinkReport(report(0, 120_000, 6000), CARRIER, clock);
            assertEquals(3600, mfu.estimateAllocationDemand(ALLOC, clock.nowTicks()).grantedBytesSinceSnapshot());
            assertEquals(2400, mfu.estimateAllocationDemand(ALLOC, clock.nowTicks()).effectiveKnownBytes());
            assertThrows(IllegalArgumentException.class, () -> mfu.recordDbaBudgetCreated(
                    new PonDbaGrant(3, ALLOC, 1, clock.nowTicks(), 4, 500_205, 511_725, 3600), clock));

            advanceTo(clock, 380_000);
            // 同刻先装载下一个时隙、再采上一个时隙结算快照。已经装载的数据不能重扣。
            mfu.receiveUplinkReport(report(2, 375_205, 2400), CARRIER, clock);
            mfu.receiveUplinkReport(report(1, 250_204, 12_000), CARRIER, clock);
            assertEquals(375_205, mfu.latestAllocationReport(ALLOC).report().snapshotTimeNs());
            assertEquals(0, mfu.estimateAllocationDemand(ALLOC, clock.nowTicks()).grantedBytesSinceSnapshot());
            assertEquals(2400, mfu.estimateAllocationDemand(ALLOC, clock.nowTicks()).effectiveKnownBytes());
        }
    }

    @Test
    void onlyDistinctOrderedWindowsCanCoexistAndSettlementIsIdempotent() {
        PonDemandLedger ledger = new PonDemandLedger();
        assertTrue(ledger.recordPipelinedAllocationBudgetGrant(ALLOC, 1, 1, 100, 2, 200, 300, 1200, 0));
        assertTrue(ledger.recordPipelinedAllocationBudgetGrant(ALLOC, 1, 2, 150, 3, 300, 500, 1200, 1200));
        assertFalse(ledger.recordPipelinedAllocationBudgetGrant(ALLOC, 1, 2, 150, 3, 300, 500, 1200, 1200));
        assertThrows(IllegalArgumentException.class, () ->
                ledger.recordPipelinedAllocationBudgetGrant(ALLOC, 1, 2, 150, 3, 300, 500, 1200, 0));
        assertThrows(IllegalArgumentException.class, () ->
                ledger.recordPipelinedAllocationBudgetGrant(ALLOC, 1, 3, 160, 3, 500, 600, 1200, 0));
        assertThrows(IllegalArgumentException.class, () ->
                ledger.recordPipelinedAllocationBudgetGrant(ALLOC, 1, 3, 160, 4, 499, 600, 1200, 0));
        assertThrows(IllegalArgumentException.class, () ->
                ledger.recordPipelinedAllocationBudgetGrant(ALLOC, 1, 3, 149, 4, 500, 600, 1200, 0));

        assertTrue(ledger.settleAllocationBudgetGrant(ALLOC, 1, 1, 550, 400));
        assertTrue(ledger.hasOutstandingAllocationBudget(ALLOC, 550));
        assertTrue(ledger.settleAllocationBudgetGrant(ALLOC, 1, 2, 560, 1000));
        assertFalse(ledger.settleAllocationBudgetGrant(ALLOC, 1, 2, 561, 1000));
        assertFalse(ledger.hasOutstandingAllocationBudget(ALLOC, 560));
        assertThrows(IllegalArgumentException.class, () ->
                ledger.settleAllocationBudgetGrant(ALLOC, 1, 2, 562, 999));
    }

    @Test
    void unknownDemandCanReceiveOnlyConfiguredFixedQuotaInCompleteMode() {
        TcontProfile profile = new TcontProfile(ALLOC, 1, 1, EnumSet.allOf(AccessCategory.class),
                0, 0, 0, 0, 1, 1200, 0, 0);
        DbaContext context = new DbaContext(PERIOD, 2, BigDecimal.valueOf(2500), PERIOD, 205,
                List.of(new DbaContext.Allocation(ALLOC, 1, null, null, null, true, profile)));
        DbaPlan fixed = plan(new DbaPlan.Grant(ALLOC, 1, 1200, 3840, null, 1200));
        DbaPlan tooMuchFixed = plan(new DbaPlan.Grant(ALLOC, 1, 2400, 7680, null, 2400));
        DbaPlan unknownDynamic = plan(new DbaPlan.Grant(ALLOC, 1, 2400, 7680, null, 1200));
        DbaPlanValidator validator = new DbaPlanValidator();
        assertEquals(1200, validator.validateAndPreview(context, fixed, true).get(0).fixedBytes());
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndPreview(context, fixed));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndPreview(context, tooMuchFixed, true));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndPreview(context, unknownDynamic, true));
    }

    private static DbaPlan plan(DbaPlan.Grant grant) {
        return new DbaPlan(PERIOD, 2, List.of(grant), "TEST");
    }

    private static PonUplinkReport report(long frame, long snapshot, long bytes) {
        int packets = Math.toIntExact(bytes / 1200);
        return new PonUplinkReport(frame, ALLOC, 1, snapshot, packets, bytes, packets, bytes);
    }

    private static void advanceTo(SimulationContext clock, long timeNs) {
        clock.scheduleAfterNanoseconds(timeNs - clock.nowTicks(), EventType.PACKET_GENERATED, CARRIER, 1);
        clock.nextEvent();
    }
}
