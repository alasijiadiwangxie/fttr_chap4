package edu.suda.fttr.energy;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeController;
import edu.suda.fttr.entity.ApModeSwitch;
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

final class ApResidencyModePolicyTest {
    private static final long HORIZON = 5_000_000;
    private static final ApModeSwitch.Settings SWITCH = new ApModeSwitch.Settings(Map.of(),
            new ApModeSwitch.Profile(40_125, new BigDecimal("11.50")),
            new ApModeSwitch.Profile(25_250, new BigDecimal("8.75")));
    private static final ApEnergyModel POWER = new ApEnergyModel(ApEnergyModel.LCM_DEFAULT, ApEnergyModel.HCM_DEFAULT);
    private static final ApEnergyModePolicy ENERGY = new ApEnergyModePolicy(POWER, SWITCH, 0);

    @Test
    void emptyRoundTripPaysBackAfter767573NsOfLcmAndChargesEntryOnce() {
        var policy = createPolicy(500_000, 25);
        var change = decide(policy, policy.start(0, null), HORIZON, HORIZON, HCM, 1_000_000, 0,
                shape(LCM, false), shape(HCM, false));
        assertEquals(ApResidencyModePolicy.Reason.ECONOMICAL_SWITCH, change.reason);
        assertEquals(767_573, change.economicResidenceNs);
        assertEquals(184.2175, change.economics.roundTripPremiumUj, 1e-10);
        assertEquals(155.685, change.economics.returnReserveUj, 1e-10);
        assertEquals(38_100, change.economics.keepUj, 1e-10);
        assertEquals(36_934.5925, change.economics.changeUj, 1e-10);
        assertEquals(1_009.7225, change.economics.savingUj, 1e-10);
        var residency = policy.start(5_025_250, change);
        assertEquals(5_792_823, residency.eligibleAtNs);
    }

    @Test
    void checksBothTransitionTimesAtTheIdleForecastBoundary() {
        var policy = createPolicy(0, 0);
        var first = decide(policy, policy.start(0, null), 1_000_000, 832_947, HCM, 1_000_000, 0,
                shape(LCM, false), shape(HCM, false));
        var enough = decide(policy, policy.start(0, null), 1_000_000, 832_948, HCM, 1_000_000, 0,
                shape(LCM, false), shape(HCM, false));
        assertEquals(ApResidencyModePolicy.Reason.PAYBACK_BEYOND_FORECAST, first.reason);
        assertEquals(ApResidencyModePolicy.Reason.ECONOMICAL_SWITCH, enough.reason);
    }

    @Test
    void addsOnlyModeDependentScanPowerAndKeepsHcmWhenScanCostsExceedIdleSaving() {
        var policy = createPolicy(0, 0);
        var result = decide(policy, policy.start(0, null), HORIZON, HORIZON, HCM, 1_000_000, .25,
                shape(LCM, false), shape(HCM, false));
        assertEquals(HCM, result.target);
        assertEquals(.25 * 4974.75, result.economics.changeScanUj, 1e-9);
        assertEquals(0, result.economics.keepScanUj);
        assertEquals(-.01, result.economics.savingW, 1e-12);
    }

    @Test
    void preventsReverseAtCompletionOrWhenSavingDoesNotClearReverseMargin() {
        var policy = createPolicy(0, 25);
        Packet voice = new Packet(1, 1500, 0, AccessCategory.VO);
        var up = decide(policy, policy.start(0, null), 0, HORIZON, LCM, 300_000, 0,
                shape(LCM, false, batch(voice, 650_000, 160_000, 28_000)),
                shape(HCM, false, batch(voice, 100_000, 40_000, 28_000)));
        assertEquals(HCM, up.target);
        var residency = policy.start(40_125, up);
        var sameTick = decide(policy, residency, 40_125, HORIZON, HCM, 1_000_000, 0,
                shape(LCM, false), shape(HCM, false));
        var weak = decide(policy, residency, 1_000_000, 850_000, HCM, 1_000_000, 0,
                shape(LCM, false), shape(HCM, false));
        assertEquals(ApResidencyModePolicy.Reason.SAME_TICK_GUARD, sameTick.reason);
        assertEquals(ApResidencyModePolicy.Reason.REVERSE_HYSTERESIS, weak.reason);
        assertTrue(weak.economics.savingUj > 0 && weak.economics.savingUj < weak.marginUj);
    }

    @Test
    void expiredVoiceConsumesQueueTimeButCannotImproveDeadlineReward() {
        var policy = createPolicy(0, 0);
        Packet expired = new Packet(1, 1500, 0, AccessCategory.VO);
        Packet live = new Packet(2, 1500, 300, AccessCategory.VO);
        var result = decide(policy, policy.start(0, null), 300_000, HORIZON, LCM, 300_000, 0,
                shape(LCM, false, batch(expired, 200_000, 150_000, 28_000), batch(live, 200_000, 150_000, 28_000)),
                shape(HCM, false, batch(expired, 100_000, 50_000, 28_000), batch(live, 100_000, 50_000, 28_000)));
        assertEquals(1, result.keepQos.expiredPackets);
        assertEquals(1, result.keepQos.liveDeadlines.size());
        assertEquals(400_000, result.keepQos.classes.get(AccessCategory.VO).serviceNs());
        assertEquals(0, result.keepQos.classes.get(AccessCategory.VO).expectedOnTime());
        assertEquals(1, result.changeQos.classes.get(AccessCategory.VO).expectedOnTime());
        assertEquals(HCM, result.target);
    }

    @Test
    void bypassesResidenceForRealRescueEvenWithBothModesInfeasible() {
        var policy = createPolicy(500_000, 25);
        Packet first = new Packet(1, 1500, 0, AccessCategory.VO);
        Packet second = new Packet(2, 1500, 0, AccessCategory.VO);
        var low = shape(LCM, false, batch(first, 500_000, 150_000, 28_000), batch(second, 200_000, 150_000, 28_000));
        var high = shape(HCM, false, batch(first, 300_000, 60_000, 28_000), batch(second, 300_000, 60_000, 28_000));
        var rescue = decide(policy, policy.start(0, null), 0, HORIZON, LCM, 450_000, 0, low, high);
        assertEquals(ApDeadlineModePolicy.Status.DEADLINE_RISK, rescue.keepQos.status);
        assertEquals(ApDeadlineModePolicy.Status.DEADLINE_RISK, rescue.changeQos.status);
        assertEquals(ApResidencyModePolicy.Reason.URGENT_EXCEPTION, rescue.reason);
        assertEquals(first, rescue.risk.urgent.packet);
        assertEquals(59_875, rescue.risk.urgent.slackNs);
        var hopeless = decide(policy, policy.start(0, null), 0, HORIZON, LCM, 200_000, 0, low, high);
        assertEquals(ApResidencyModePolicy.Reason.HOLD_UNPROVEN_QOS, hopeless.reason);
        assertFalse(hopeless.risk.clearImprovement());
    }

    @Test
    void shortensTheSameWorkWithACompleteLcmBatchBeforeSwitching() {
        var policy = createPolicy(0, 0);
        var first = new Packet(1, 1200, 0, AccessCategory.VO);
        var second = new Packet(2, 1200, 0, AccessCategory.BE);
        var low = shape(LCM, false, batch(first, 100_000, 20_000, 10_000), batch(second, 1_200_000, 180_000, 28_000));
        var high = shape(HCM, false, batch(first, 130_000, 55_000, 10_000), batch(second, 130_000, 55_000, 10_000));
        var result = decide(policy, policy.start(0, null), 0, HORIZON, LCM, 1_000_000, 0, low, high);
        assertEquals(ApResidencyModePolicy.Reason.QOS_IMPROVEMENT, result.reason);
        assertEquals(649_875, result.delayNs);
        assertEquals(9_600, result.deferred.successBits);
        assertEquals(1, result.deferred.batches);
        // 649.875 us LCM，之后扣除已交付的一半工作，HCM 完成余下一个包。
        double switchAndReserve = 40.125 * 11.50 + result.economics.returnReserveUj;
        double immediate = 110 * 10.50 + 20 * 8.70 + (5000 - 40.125 - 130) * 7.62 + switchAndReserve;
        double continued = 20 * 9.20 + 10 * 8.20 + (649.875 - 30) * 7.38
                + 55 * 10.50 + 10 * 8.70 + (5000 - 649.875 - 40.125 - 65) * 7.62 + switchAndReserve;
        assertEquals(immediate, result.immediateUj, 1e-8);
        assertEquals(continued, result.deferred.costUj, 1e-8);
    }

    @Test
    void completePolicyReleasesHcmWhenBothModesHaveSameDeadlineRiskButLcmSavesEnergy() {
        var policy = createPolicy(500_000, 25);
        var residence = policy.start(0, null);
        var packet = new Packet(1, 1200, 1000, AccessCategory.VO);
        var low = shape(LCM, false, batch(packet, 250_000, 80_000, 28_000));
        var high = shape(HCM, false, batch(packet, 350_000, 100_000, 28_000));
        var old = decide(policy, residence, 1_000_000, HORIZON, HCM, 100_000, 0, low, high);
        var complete = decide(policy, residence, 1_000_000, HORIZON, HCM, 100_000, 0, low, high,
                ApModeController.CompleteFeatures.ALL);
        assertEquals(ApResidencyModePolicy.Reason.HOLD_UNPROVEN_QOS, old.reason);
        assertEquals(ApResidencyModePolicy.Reason.SAFE_ECONOMIC_EXIT, complete.reason);
        assertEquals(LCM, complete.target);
        assertTrue(complete.safeEconomicExit && complete.economics.savingUj > complete.marginUj);
    }

    @Test
    void completeEconomicExitRejectsLessThroughputOrUnknownChannel() {
        var policy = createPolicy(500_000, 25);
        var residence = policy.start(0, null);
        var packet = new Packet(1, 1200, 1000, AccessCategory.VO);
        var reducedService = shape(LCM, false, batch(packet, 6_000_000, 80_000, 28_000));
        var unknown = shape(LCM, true, batch(packet, 250_000, 80_000, 28_000));
        var high = shape(HCM, false, batch(packet, 350_000, 100_000, 28_000));
        for (var low : List.of(reducedService, unknown)) {
            var denied = decide(policy, residence, 1_000_000, HORIZON, HCM, 100_000, 0, low, high,
                    ApModeController.CompleteFeatures.ALL);
            assertFalse(denied.safeEconomicExit);
            assertEquals(HCM, denied.target);
        }
    }

    private static ApResidencyModePolicy createPolicy(long minimumNs, double reverseMargin) {
        var settings = new ApModeController.Settings(Map.of(), HORIZON, .25, 31, 0,
                ApModeController.ServiceModel.AUTO, 50_000, 2_000_000, minimumNs, reverseMargin);
        return new ApResidencyModePolicy(POWER, SWITCH, ENERGY, settings);
    }

    private static ApResidencyModePolicy.Decision decide(ApResidencyModePolicy policy, ApResidencyModePolicy.Residence resident,
                                                         long now, long span, ApCapabilityMode current, long deadline, double scanW,
                                                         ApModePredictor.ServiceForecast low, ApModePredictor.ServiceForecast high) {
        return decide(policy, resident, now, span, current, deadline, scanW, low, high, null);
    }

    private static ApResidencyModePolicy.Decision decide(ApResidencyModePolicy policy, ApResidencyModePolicy.Residence resident,
                                                         long now, long span, ApCapabilityMode current, long deadline, double scanW,
                                                         ApModePredictor.ServiceForecast low, ApModePredictor.ServiceForecast high,
                                                         ApModeController.CompleteFeatures features) {
        var requirements = new EnumMap<AccessCategory, ApAnalysisSettings.Requirement>(AccessCategory.class);
        for (AccessCategory ac : AccessCategory.values()) {
            requirements.put(ac, new ApAnalysisSettings.Requirement(deadline, .99));
        }
        var ready = new ApModeSwitch.Readiness(current, current, 0, 0, 0, 0, false);
        var energy = ENERGY.choose(span, ready, false, List.of(low, high));
        var qos = new ApDeadlineModePolicy(new ApAnalysisSettings(true, span, .25, requirements), SWITCH, 50_000, 2_000_000);
        return policy.choose(now, resident, HORIZON, 2_000_000, scanW, energy, qos.choose(now, energy, ready, false, true), false, features);
    }

    private static ApModePredictor.DeadlineBatch batch(Packet packet, double workNs, double txNs, double rxNs) {
        return new ApModePredictor.DeadlineBatch(List.of(packet), workNs, 1, txNs, rxNs);
    }

    private static ApModePredictor.ServiceForecast shape(ApCapabilityMode mode, boolean insufficient,
                                                         ApModePredictor.DeadlineBatch... batches) {
        double bits = 0, ns = 0, tx = 0, rx = 0;
        for (var batch : batches) {
            bits += batch.packets.get(0).bytes() * 8.0;
            ns += batch.workNs;
            tx += batch.txNs;
            rx += batch.rxNs;
        }
        var work = new ApModePredictor.ServiceWork(bits, batches.length, 0, bits, ns, tx, rx, 0, 0, 1);
        return new ApModePredictor.ServiceForecast(mode, ApModePredictor.Confidence.FEEDBACK, 0, insufficient,
                work, work, work, List.of(batches), ns == 0 ? 0 : bits * 1_000 / ns, 1);
    }
}
