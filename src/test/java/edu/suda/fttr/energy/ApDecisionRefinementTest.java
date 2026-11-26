package edu.suda.fttr.energy;

import edu.suda.fttr.channel.CcaHistory;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeController;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.phy.ApCapabilityMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static edu.suda.fttr.phy.ApCapabilityMode.HCM;
import static edu.suda.fttr.phy.ApCapabilityMode.LCM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApDecisionRefinementTest {
    private static final long PERIOD = 5_000_000;
    private static final ApEnergyModel POWER = new ApEnergyModel(ApEnergyModel.LCM_DEFAULT, ApEnergyModel.HCM_DEFAULT);
    private static final ApModeSwitch.Settings SWITCH = new ApModeSwitch.Settings(Map.of(),
            new ApModeSwitch.Profile(40_125, new BigDecimal("11.50")),
            new ApModeSwitch.Profile(25_250, new BigDecimal("8.75")));
    private static final ApEnergyModePolicy ENERGY = new ApEnergyModePolicy(POWER, SWITCH, 0);
    private static final ApResidencyModePolicy POLICY = new ApResidencyModePolicy(POWER, SWITCH, ENERGY,
            new ApModeController.Settings(Map.of(), PERIOD, .25, 31));

    @Test
    void agesLocalQueueAcrossFullPeriodsWithoutCountingUrgentOrMixedWindows() {
        var refinement = new ApDecisionRefinement(ApModeController.RefinementOptions.DEFAULT, PERIOD, PERIOD, .25, requirements());
        var traffic = new ApTrafficObserver(PERIOD, .25);
        traffic.enqueue(new Packet(1, 1200, 0, AccessCategory.BE), 0);
        var first = refinement.observe(LCM, traffic.sample(PERIOD, 0, 0), true, false, null);
        assertEquals(1, first.deficitWindows);
        assertEquals(1200, first.backlogBytes);
        for (int i = 1; i <= 3; i++) {
            var urgent = refinement.observe(LCM, traffic.preview(PERIOD + i, 0, 0), false, false, null);
            assertEquals(1, urgent.deficitWindows);
        }
        traffic.enqueue(new Packet(2, 1500, 6000, AccessCategory.BE), 6_000_000);
        var second = refinement.observe(LCM, traffic.sample(2 * PERIOD, 0, 0), true, false, null);
        assertEquals(2, second.deficitWindows);
        assertEquals(1500, second.backlogDeltaBytes);
        traffic.enqueue(new Packet(3, 1500, 11000, AccessCategory.BE), 11_000_000);
        assertEquals(0, refinement.observe(HCM, traffic.sample(3 * PERIOD, 0, 0), true, true, null).deficitWindows);
    }

    @Test
    void picksGreaterServiceInEitherDirectionWhenBothModesMissTheDeadline() {
        Packet ordinary = new Packet(1, 1500, 10000, AccessCategory.BE);
        var low = forecast(LCM, slow(), slow(), slow(), false, batch(ordinary, 3_000_000));
        var high = forecast(HCM, fast(), fast(), fast(), false, batch(ordinary, 3_000_000));
        var original = decide(LCM, low, high, sample(false, false));
        assertEquals(ApResidencyModePolicy.Reason.HOLD_UNPROVEN_QOS, original.reason);
        var recovery = decide(LCM, low, high, sample(true, false));
        assertEquals(ApResidencyModePolicy.Reason.CAPACITY_RECOVERY, recovery.reason);
        assertEquals(HCM, recovery.target);

        var reverse = decide(HCM, forecast(LCM, fast(), fast(), fast(), false, batch(ordinary, 3_000_000)),
                forecast(HCM, slow(), slow(), slow(), false, batch(ordinary, 3_000_000)), sample(true, false));
        assertEquals(ApResidencyModePolicy.Reason.CAPACITY_RECOVERY, reverse.reason);
        assertEquals(LCM, reverse.target);
    }

    @Test
    void rejectsCapacityEscapeIfItCostsTimelyVoiceOrLacksCurrentChannelEvidence() {
        var voice = new Packet(1, 500, 10_000, AccessCategory.VO);
        var ordinary = new Packet(2, 1500, 10_000, AccessCategory.BE);
        var low = forecast(LCM, slow(), slow(), slow(), false, batch(voice, 50_000), batch(ordinary, 3_000_000));
        var high = forecast(HCM, fast(), fast(), fast(), false, batch(voice, 1_000_000), batch(ordinary, 3_000_000));
        var priority = decide(LCM, low, high, sample(true, false));
        assertEquals(LCM, priority.target);
        assertEquals(ApDecisionRefinement.Capacity.PRIORITY_RISK, priority.capacity);
        var unavailable = forecast(HCM, fast(), fast(), fast(), true, batch(voice, 50_000), batch(ordinary, 3_000_000));
        assertEquals(ApDecisionRefinement.Capacity.INSUFFICIENT_INFORMATION,
                decide(LCM, low, unavailable, sample(true, false)).capacity);
    }

    @Test
    void rejectsThinExpectedSavingsWhenBoundsOverlapWithoutChangingResidency() {
        var center = work(96_000, 500_000, 200_000);
        var low = forecast(LCM, center, work(96_000, 2_000_000, 900_000), center, false);
        var high = forecast(HCM, center, center, work(96_000, 450_000, 100_000), false);
        assertEquals(ApResidencyModePolicy.Reason.ECONOMICAL_SWITCH, decide(HCM, low, high, sample(false, false)).reason);
        var corrected = decide(HCM, low, high, sample(false, true));
        assertEquals(HCM, corrected.target);
        assertEquals(ApResidencyModePolicy.Reason.ECONOMIC_UNCERTAINTY, corrected.reason);
        assertTrue(corrected.economicEvidence.expectedSavingUj > corrected.marginUj);
        assertTrue(corrected.economicEvidence.robustSavingUj < 0);
    }

    @Test
    void chargesIdleUntilTheCommonCompletionAndAddsReturnOnlyOnce() {
        var low = forecast(LCM, slow(), slow(), slow(), false);
        var high = forecast(HCM, fast(), fast(), fast(), false);
        var plan = ENERGY.choose(PERIOD, ready(LCM), false, List.of(low, high));
        var keep = plan.candidates.get(0).expected;
        var change = plan.candidates.get(1).expected;
        double aligned = keep.totalUj - change.totalUj - 30 - (keep.tailNs - change.tailNs) * 7.62 / 1_000;
        assertTrue(keep.tailNs > change.tailNs);
        assertEquals(aligned, ApDecisionRefinement.completeSaving(keep, change, PERIOD, true, 7.38, 7.62, 0, 30), 1e-8);
    }

    @Test
    void clearsWitnessedPriorityOutageOnlyAfterFreshCleanScans() {
        var refinement = new ApDecisionRefinement(ApModeController.RefinementOptions.DEFAULT, PERIOD, PERIOD, .25, requirements());
        var traffic = new ApTrafficObserver(PERIOD, .25);
        var history = new CcaHistory(PERIOD, 15);
        history.listen(0, 15, 8);
        history.listen(1_000_000, 15, 0);
        traffic.enqueue(new Packet(1, 500, 0, AccessCategory.VO), 0);
        var bad = refinement.observe(HCM, traffic.sample(PERIOD, 0, 0), true, false, history.sample(PERIOD));
        assertTrue(bad.widePriorityRisk);
        history.listen(PERIOD, 1, 0);
        var missing = refinement.observe(LCM, traffic.sample(2 * PERIOD, 0, 0), true, false, history.sample(2 * PERIOD));
        assertTrue(missing.widePriorityRisk);
        for (int i = 3; i <= 6; i++) {
            long tick = i * PERIOD;
            for (int band = 1; band <= 3; band++) {
                history.completeScan(band, tick - 900_000, tick - 500_000, tick + 1_000_000, List.of());
            }
            var current = refinement.observe(LCM, traffic.sample(tick, 0, 0), true, false, history.sample(tick));
            assertEquals(i < 6, current.widePriorityRisk);
        }
        assertFalse(refinement.observe(LCM, traffic.preview(6 * PERIOD + 1, 0, 0), false, false,
                history.sample(6 * PERIOD + 1)).widePriorityRisk);
    }

    @Test
    void mergesShortC1SamplesIntoEachControllerPeriodWithoutDoubleCounting() {
        long c1 = PERIOD / 5;
        var refinement = new ApDecisionRefinement(ApModeController.RefinementOptions.DEFAULT, PERIOD, c1, .25, requirements());
        var traffic = new ApTrafficObserver(c1, .25);
        for (int i = 1; i <= 10; i++) {
            long now = i * c1;
            long arrival = i == 1 ? 0 : now - c1 + 1_000;
            traffic.enqueue(new Packet(i, 1500, arrival / 1_000, AccessCategory.BE), arrival);
            if (i % 5 == 0) {
                var preview = refinement.observe(LCM, traffic.preview(now, 0, 0), true, false, null);
                assertEquals(12, preview.arrivalMbps, 1e-12);
                assertEquals(i / 5, preview.deficitWindows);
            }
            refinement.closeObservation(traffic.sample(now, 0, 0));
        }
    }

    @Test
    void pureFinancialSwitchNeedsServiceRoomForObservedArrivalVariation() {
        var center = work(96_000, 1_000_000, 200_000);
        var low = forecast(LCM, center, center, center, false);
        var high = forecast(HCM, center, center, center, false);
        var sample = new ApDecisionRefinement.ObservationWindow(new ApModeController.RefinementOptions(false, true), true,
                0, 0, 0, 0, 120, 120, 0, 120, false);
        var corrected = decide(HCM, low, high, sample);
        assertTrue(corrected.economicEvidence.robustSavingUj > corrected.marginUj);
        assertFalse(corrected.economicEvidence.headroom);
        assertEquals(ApResidencyModePolicy.Reason.ECONOMIC_UNCERTAINTY, corrected.reason);
    }

    private static ApDecisionRefinement.ObservationWindow sample(boolean capacity, boolean economics) {
        return new ApDecisionRefinement.ObservationWindow(new ApModeController.RefinementOptions(capacity, economics), true,
                160_000, 30_000, PERIOD, 2, 80, 30, 30, 0, false);
    }

    private static ApResidencyModePolicy.Decision decide(ApCapabilityMode current, ApModePredictor.ServiceForecast low,
                                                         ApModePredictor.ServiceForecast high,
                                                         ApDecisionRefinement.ObservationWindow sample) {
        var ready = ready(current);
        var energy = ENERGY.choose(PERIOD, ready, false, List.of(low, high));
        var qos = new ApDeadlineModePolicy(requirements(), SWITCH, 50_000, 2_000_000);
        return POLICY.choose(2 * PERIOD, POLICY.start(0, null), PERIOD, 2_000_000, 0, energy,
                qos.choose(2 * PERIOD, energy, ready, false, true), false, ApModeController.CompleteFeatures.ALL, sample);
    }

    private static ApModeSwitch.Readiness ready(ApCapabilityMode current) {
        return new ApModeSwitch.Readiness(current, current, 0, 0, 0, 0, false);
    }

    private static ApAnalysisSettings requirements() {
        var requirements = new EnumMap<AccessCategory, ApAnalysisSettings.Requirement>(AccessCategory.class);
        for (var ac : AccessCategory.values()) {
            requirements.put(ac, new ApAnalysisSettings.Requirement(650_000, .99));
        }
        return new ApAnalysisSettings(true, PERIOD, .25, requirements);
    }

    private static ApModePredictor.ServiceForecast forecast(ApCapabilityMode mode, ApModePredictor.ServiceWork expected,
            ApModePredictor.ServiceWork conservative, ApModePredictor.ServiceWork optimistic, boolean incomplete,
            ApModePredictor.DeadlineBatch... batches) {
        return new ApModePredictor.ServiceForecast(mode, ApModePredictor.Confidence.FEEDBACK, 0, incomplete,
                expected, conservative, optimistic, List.of(batches), expected.successBits * 1_000 / expected.workNs, 1);
    }

    private static ApModePredictor.DeadlineBatch batch(Packet packet, long ns) {
        return new ApModePredictor.DeadlineBatch(List.of(packet), ns, 1);
    }

    private static ApModePredictor.ServiceWork slow() { return work(400_000, 10_000_000, 4_000_000); }
    private static ApModePredictor.ServiceWork fast() { return work(400_000, 4_000_000, 1_500_000); }

    private static ApModePredictor.ServiceWork work(double bits, long ns, long tx) {
        return new ApModePredictor.ServiceWork(bits, bits / 12_000, 0, bits, ns, tx, 0, 0, 0, 1);
    }
}
