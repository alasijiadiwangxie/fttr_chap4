package edu.suda.fttr.energy;

import edu.suda.fttr.energy.ApModePredictor.Confidence;
import edu.suda.fttr.energy.ApModePredictor.ServiceForecast;
import edu.suda.fttr.energy.ApModePredictor.ServiceWork;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.phy.ApCapabilityMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static edu.suda.fttr.phy.ApCapabilityMode.HCM;
import static edu.suda.fttr.phy.ApCapabilityMode.LCM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApEnergyModePolicyTest {
    private static final double EPS = 1e-8;

    @Test
    void usesIdenticalWorkAndChargesOnlyTheActualTransitionWithinTheWindow() {
        var ready = new ApModeSwitch.Readiness(LCM, LCM, 100_000, 40_000, 10_000, 0, false);
        var forecasts = List.of(profile(LCM, work(12_000, 12_000, 300_000, 180_000, 30_000, 0)),
                profile(HCM, work(12_000, 12_000, 160_000, 80_000, 30_000, 0)));
        var decision = policy(0).choose(1_000_000, ready, false, forecasts);
        var low = decision.candidates.get(0).expected;
        var high = decision.candidates.get(1).expected;
        assertEquals(LCM, decision.target);
        assertEquals(12_000, low.successBits, EPS);
        assertEquals(low.successBits, high.successBits, EPS);
        assertEquals(0, high.unservedBits, EPS);
        assertEquals(40_125, high.newSwitchNs);
        assertEquals(461.4375, high.newSwitchUj, EPS);
        assertEquals(1_000_000, high.txNs + high.rxNs + high.idleNs + high.newSwitchNs, EPS);
        assertEquals(9.2 * 220 + 8.2 * 40 + 7.38 * 740, low.totalUj, EPS);
        assertEquals(9.2 * 40 + 8.2 * 10 + 7.38 * 50 + 10.5 * 80 + 8.7 * 30 + 7.62 * 749.875 + 461.4375,
                high.totalUj, EPS);
    }

    @Test
    void appliesConfigurableMarginToTheIdleEnergySaving() {
        var forecasts = List.of(profile(LCM, idle(0)), profile(HCM, idle(0)));
        var down = policy(0).choose(5_000_000, ready(HCM), false, forecasts);
        assertEquals(LCM, down.target);
        assertEquals(7.38 * 4974.75 + 8.75 * 25.25, down.candidates.get(0).expected.totalUj, EPS);
        var hold = policy(1_200).choose(5_000_000, ready(HCM), false, forecasts);
        assertEquals(HCM, hold.target);
        assertEquals(ApEnergyModePolicy.Reason.ENERGY_MARGIN, hold.reason);
    }

    @Test
    void cheapIncompleteWindowDoesNotWinWhenItsRemainingServiceIsExpensive() {
        var slow = work(12_000, 12_000, 2_000_000, 200_000, 100_000, .5);
        var fast = work(12_000, 12_000, 600_000, 350_000, 100_000, 0);
        var decision = policy(0).choose(1_000_000, ready(HCM), false,
                List.of(profile(LCM, slow), profile(HCM, fast)));
        var low = decision.candidates.get(0).expected;
        var high = decision.candidates.get(1).expected;
        assertTrue(low.windowUj < high.windowUj);
        assertEquals(12_000.0 * 974_750 * .5 / 2_000_000, low.successBits, EPS);
        assertEquals(12_000, low.successBits + low.unservedBits, EPS);
        double fraction = low.unservedBits / 12_000;
        assertEquals(4_000_000 * fraction, low.tailNs, EPS);
        assertEquals(fraction * (3700 * 7.38 + 200 * 9.2 + 100 * 8.2), low.tailUj, EPS);
        assertTrue(low.totalUj > high.totalUj);
        assertEquals(HCM, decision.target);
    }

    @Test
    void distinguishesNoServiceFromLowPower() {
        var failed = work(12_000, 0, 400_000, 200_000, 0, 0);
        var healthy = work(12_000, 12_000, 600_000, 350_000, 100_000, 0);
        var recover = policy(0).choose(1_000_000, ready(LCM), false,
                List.of(profile(LCM, failed), profile(HCM, healthy)));
        assertEquals(Double.POSITIVE_INFINITY, recover.candidates.get(0).expected.totalUj);
        assertEquals(HCM, recover.target);
        assertEquals(ApEnergyModePolicy.Reason.SWITCH_SERVICE_AVAILABLE, recover.reason);
        var noModel = policy(0).choose(1_000_000, ready(LCM), false, List.of(profile(LCM, failed), profile(HCM, failed)));
        assertEquals(LCM, noModel.target);
        assertEquals(ApEnergyModePolicy.Reason.NO_COMPLETION_MODEL, noModel.reason);
    }

    @Test
    void needsAnEnergyAdvantageAcrossTheBoundsWhenChannelInformationIsIncomplete() {
        var low = work(12_000, 12_000, 300_000, 100_000, 28_000, 0);
        var high = work(12_000, 12_000, 200_000, 100_000, 28_000, 0);
        var incomplete = new ServiceForecast(LCM, Confidence.CHANNEL_UNKNOWN, -1, true, low,
                work(12_000, 0, 600_000, 300_000, 0, .5), low);
        var decision = policy(0).choose(5_000_000, ready(HCM), false, List.of(incomplete, profile(HCM, high)));
        assertTrue(decision.candidates.get(0).expected.totalUj < decision.candidates.get(1).expected.totalUj);
        assertEquals(ApEnergyModePolicy.Reason.CHANNEL_INFORMATION_INSUFFICIENT, decision.reason);
        assertEquals(HCM, decision.target);
    }

    @Test
    void keepsCommittedAndNewSwitchCostsSeparateAndRejectsATransitionOutsideTheWindow() {
        var alreadySwitching = new ApModeSwitch.Readiness(HCM, LCM, 0, 0, 0, 10_000, false);
        var forecasts = List.of(profile(LCM, idle(0)), profile(HCM, idle(0)));
        var decision = policy(0).choose(1_000_000, alreadySwitching, false, forecasts);
        assertEquals(87.5, decision.candidates.get(0).expected.activeUj, EPS);
        assertEquals(87.5, decision.candidates.get(1).expected.activeUj, EPS);
        assertEquals(0, decision.candidates.get(0).expected.newSwitchUj);
        assertEquals(461.4375, decision.candidates.get(1).expected.newSwitchUj, EPS);
        var shortWindow = policy(0).choose(30_000, alreadySwitching, false, forecasts);
        assertEquals(LCM, shortWindow.target);
        assertFalse(shortWindow.candidates.get(1).expected.transitionFits);
        assertEquals(ApEnergyModePolicy.Reason.TRANSITION_OUTSIDE_WINDOW, shortWindow.reason);
    }

    @Test
    void doesNotDeductEstimatedCcaTwiceFromTheSameTime() {
        var ready = new ApModeSwitch.Readiness(LCM, LCM, 200_000, 60_000, 20_000, 0, false);
        var decision = policy(0).choose(1_000_000, ready, true, List.of(profile(LCM, idle(.25)), profile(HCM, idle(.5))));
        var keep = decision.candidates.get(0).expected;
        var change = decision.candidates.get(1).expected;
        assertEquals(600_000, keep.availableNs, EPS);
        assertEquals(200_000, change.ccaWaitNs, EPS);
        assertEquals((800_000 - 200_000 - 40_125) * .5, change.availableNs, EPS);
        assertEquals(1_000_000, change.txNs + change.rxNs + change.idleNs + change.newSwitchNs, EPS);
    }

    private static ApModeSwitch.Readiness ready(ApCapabilityMode current) {
        return new ApModeSwitch.Readiness(current, current, 0, 0, 0, 0, false);
    }

    private static ServiceForecast profile(ApCapabilityMode mode, ServiceWork work) {
        return new ServiceForecast(mode, Confidence.FEEDBACK, work.busyFraction, false, work, work, work);
    }

    private static ServiceWork idle(double busy) {
        return work(0, 0, 0, 0, 0, busy);
    }

    private static ServiceWork work(double bits, double success, double ns, double tx, double rx, double busy) {
        return new ServiceWork(bits, bits / 12_000, 0, success, ns, tx, rx, busy, success == bits ? 0 : 1, 1);
    }

    private static ApEnergyModePolicy policy(double margin) {
        return new ApEnergyModePolicy(new ApEnergyModel(ApEnergyModel.LCM_DEFAULT, ApEnergyModel.HCM_DEFAULT),
                new ApModeSwitch.Settings(Map.of(), new ApModeSwitch.Profile(40_125, new BigDecimal("11.50")),
                        new ApModeSwitch.Profile(25_250, new BigDecimal("8.75"))), margin);
    }
}
