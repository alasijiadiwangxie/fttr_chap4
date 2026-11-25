package edu.suda.fttr.energy;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeController;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.phy.ApCapabilityMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/** D4 在同一份在线预测上比较驻留、换档与先用 LCM 服务，实际请求仍交给 B4。 */
public final class ApResidencyModePolicy {
    private static final double MIN_QOS_GAIN_PACKETS = 0.5;
    private static final double NUMERICAL_TOLERANCE = 1e-9;
    private final ApEnergyModel power;
    private final ApModeSwitch.Settings switching;
    private final ApEnergyModePolicy energyPolicy;
    private final long minimumNs;
    private final double reverseMarginUj;

    public ApResidencyModePolicy(ApEnergyModel power, ApModeSwitch.Settings switching, ApEnergyModePolicy energyPolicy,
                                 ApModeController.Settings settings) {
        this.power = power;
        this.switching = switching;
        this.energyPolicy = energyPolicy;
        this.minimumNs = settings.minimumResidencyNs();
        this.reverseMarginUj = settings.reverseMarginUj();
    }

    public Residence start(long nowNs, Decision executed) {
        // 驻留从接收能力真正改变时计时。QoS 驱动的换档只应用基本驻留时间。
        long duration = executed != null && executed.reason == Reason.ECONOMICAL_SWITCH
                ? Math.max(minimumNs, executed.economicResidenceNs) : minimumNs;
        return new Residence(nowNs, nowNs + Math.min(duration, Long.MAX_VALUE - nowNs), executed != null);
    }

    public Decision choose(long nowNs, Residence residence, long untilReviewNs, long delayLimitNs, double scanW,
                           ApEnergyModePolicy.Decision energy, ApDeadlineModePolicy.Decision qos, boolean externalBusy) {
        var ready = energy.readiness;
        var current = ready.projectedMode;
        int base = current == ApCapabilityMode.LCM ? 0 : 1;
        var keep = energy.candidates.get(base);
        var change = energy.candidates.get(1 - base);
        var keepQos = qos.candidates.get(base);
        var changeQos = qos.candidates.get(1 - base);
        boolean keepFeasible = feasible(keepQos);
        boolean changeFeasible = feasible(changeQos);
        boolean energyLed = keepFeasible && changeFeasible;
        double marginUj = energy.marginUj + (residence.afterSwitch ? reverseMarginUj : 0);

        Economics economics = economics(energy, keep, change, scanW, marginUj);
        long untilRelease = Math.max(0, residence.eligibleAtNs - nowNs);
        Risk risk = risk(keepQos, changeQos, Math.min(untilRelease, untilReviewNs));
        boolean exception = untilRelease > 0 && risk.urgent != null && !keepFeasible;
        Reason reason;
        if (ready.activeSwitchNs > 0) {
            reason = Reason.SWITCH_IN_PROGRESS;
        } else if (residence.afterSwitch && nowNs == residence.startedNs) {
            reason = Reason.SAME_TICK_GUARD;
        } else if (keepFeasible && !changeFeasible) {
            reason = Reason.KEEP_QOS;
        } else if (!keepFeasible && !changeFeasible && !risk.clearImprovement()) {
            reason = Reason.HOLD_UNPROVEN_QOS;
        } else if (energyLed && !(economics.savingUj > marginUj)) {
            reason = residence.afterSwitch && economics.savingUj > energy.marginUj
                    ? Reason.REVERSE_HYSTERESIS : Reason.KEEP_ENERGY;
        } else if (energyLed && !economics.covered()) {
            reason = Reason.PAYBACK_BEYOND_FORECAST;
        } else if (untilRelease > 0 && !exception) {
            reason = Reason.RESIDENCY_HOLD;
        } else {
            reason = exception ? Reason.URGENT_EXCEPTION : energyLed ? Reason.ECONOMICAL_SWITCH : Reason.QOS_IMPROVEMENT;
        }

        var target = reason.requestsChange() ? change.forecast.mode : current;
        long allowed = target == ApCapabilityMode.HCM && current == ApCapabilityMode.LCM && changeFeasible
                ? Math.min(qos.maxDelayNs, delayLimitNs) : 0;
        Deferred deferred = allowed > 0 && reason != Reason.URGENT_EXCEPTION
                ? defer(energy, keep, change, allowed, scanW, externalBusy, economics.returnReserveUj) : Deferred.UNAVAILABLE;
        double immediateUj = economics.changeUj + economics.returnReserveUj;
        long delayNs = deferred.costUj + energy.marginUj < immediateUj && Double.isFinite(deferred.costUj) ? allowed : 0;
        return new Decision(target, reason, residence, economics, marginUj, scanW, risk, keepQos, changeQos,
                allowed, delayNs, immediateUj, deferred);
    }

    private Economics economics(ApEnergyModePolicy.Decision plan, ApEnergyModePolicy.Candidate keep,
                                ApEnergyModePolicy.Candidate change, double scanW, double marginUj) {
        var source = keep.forecast.mode;
        var target = change.forecast.mode;
        long horizon = plan.horizonNs;
        long available = horizon - plan.readiness.waitNs - plan.readiness.activeSwitchNs;
        var entry = switching.profileFrom(source);
        var exit = switching.profileFrom(target);
        BigDecimal idle = power.powerFor(source).idleW();
        BigDecimal entryPremium = switchPremium(entry, idle);
        BigDecimal returnPremium = switchPremium(exit, idle);
        // D2 的候选成本已包含本次切换。这里只加将来可能再次切回的增量，避免再计入本次切换。
        double reserveUj = Math.max(0, returnPremium.doubleValue());
        double keepScanUj = scanCost(scanW, source == ApCapabilityMode.LCM ? available + keep.expected.tailNs : 0);
        double changeLcmNs = source == ApCapabilityMode.LCM ? change.expected.ccaWaitNs
                : available - change.expected.newSwitchNs + change.expected.tailNs;
        double changeScanUj = scanCost(scanW, changeLcmNs);
        double keepUj = keep.expected.totalUj + keepScanUj;
        double changeUj = change.expected.totalUj + changeScanUj;
        double savingW;
        BigDecimal savingWatts;
        if (keep.forecast.expected.demandBits == 0) {
            // Decimal B3 功率用于空闲边界，避免二进制误差使纳秒上取整多出 1 ns。
            double scanDelta = source == ApCapabilityMode.LCM ? scanW : -scanW;
            savingWatts = idle.subtract(power.powerFor(target).idleW()).add(BigDecimal.valueOf(scanDelta));
            savingW = savingWatts.doubleValue();
        } else {
            double keepSteady = steadyUj(horizon, keep, scanW);
            double changeSteady = steadyUj(horizon, change, scanW);
            savingW = (keepSteady - changeSteady) * 1_000 / horizon;
            savingWatts = Double.isFinite(savingW) ? BigDecimal.valueOf(savingW) : BigDecimal.ZERO;
        }
        BigDecimal extraUj = entryPremium.add(returnPremium.max(BigDecimal.ZERO)).add(BigDecimal.valueOf(marginUj));
        long payback = breakEvenNs(extraUj, savingWatts);
        double room = available - change.expected.ccaWaitNs - entry.delayNs() - exit.delayNs();
        return new Economics(keepUj, changeUj, reserveUj, keepScanUj, changeScanUj, extraUj.doubleValue(),
                savingW, payback, Math.max(0, room), change.expected.transitionFits);
    }

    private double steadyUj(long horizonNs, ApEnergyModePolicy.Candidate candidate, double scanW) {
        var steady = energyPolicy.steady(horizonNs, candidate.forecast);
        return steady.totalUj + (candidate.forecast.mode == ApCapabilityMode.LCM
                ? scanCost(scanW, horizonNs + steady.tailNs) : 0);
    }

    private static BigDecimal switchPremium(ApModeSwitch.Profile profile, BigDecimal idleW) {
        return profile.powerW().subtract(idleW).multiply(BigDecimal.valueOf(profile.delayNs(), 3));
    }

    private static long breakEvenNs(BigDecimal incrementalUj, BigDecimal savingW) {
        if (savingW.signum() <= 0) {
            return Long.MAX_VALUE;
        }
        BigDecimal ticks = incrementalUj.max(BigDecimal.ZERO).movePointRight(3).divide(savingW, 0, RoundingMode.CEILING);
        return ticks.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    private Deferred defer(ApEnergyModePolicy.Decision plan, ApEnergyModePolicy.Candidate low,
                           ApEnergyModePolicy.Candidate high, long delayNs, double scanW, boolean externalBusy,
                           double returnReserveUj) {
        var ready = plan.readiness;
        if (delayNs <= ready.waitNs || delayNs >= plan.horizonNs || ready.activeSwitchNs > 0) {
            return Deferred.UNAVAILABLE;
        }
        var work = low.forecast.conservative;
        double airtime = (delayNs - ready.waitNs) * (1 - work.busyFraction);
        double tx = ready.txNs;
        double rx = ready.rxNs;
        double successBits = 0;
        int batchCount = 0;
        for (var batch : low.forecast.deadlineBatches) {
            // 只给在主动延迟结束前能完成的本地整批报文记入成功量，绝不假定未来到达可填满这个窗口。
            if (batch.workNs > airtime) {
                break;
            }
            airtime -= batch.workNs;
            tx += batch.txNs;
            rx += batch.rxNs;
            successBits += batch.packets.stream().mapToLong(packet -> 8L * packet.bytes()).sum() * batch.successProbability;
            batchCount++;
        }
        if (successBits <= 0) {
            return Deferred.UNAVAILABLE;
        }
        ApEnergyModel.Power lcmPower = power.powerFor(ApCapabilityMode.LCM);
        double prefix = (tx * lcmPower.txW().doubleValue() + rx * lcmPower.rxW().doubleValue()
                + Math.max(0, delayNs - tx - rx) * lcmPower.idleW().doubleValue()) / 1_000;
        double remaining = Math.max(0, 1 - successBits / high.forecast.expected.demandBits);
        long restNs = plan.horizonNs - delayNs;
        if (remaining == 0) {
            double cost = prefix + restNs * lcmPower.idleW().doubleValue() / 1_000
                    + scanCost(scanW, plan.horizonNs - ready.waitNs);
            return new Deferred(cost, successBits, batchCount);
        }
        var after = energyPolicy.afterLcmWork(restNs, low.forecast, high.forecast, externalBusy, remaining);
        double scanUj = scanCost(scanW, delayNs - ready.waitNs + after.ccaWaitNs);
        return new Deferred(prefix + scanUj + after.totalUj + returnReserveUj, successBits, batchCount);
    }

    private static boolean feasible(ApDeadlineModePolicy.Candidate candidate) {
        return candidate.status == ApDeadlineModePolicy.Status.FEASIBLE;
    }

    private static double scanCost(double scanW, double durationNs) {
        return scanW == 0 ? 0 : scanW * Math.max(0, durationNs) / 1_000;
    }

    private static Risk risk(ApDeadlineModePolicy.Candidate keep, ApDeadlineModePolicy.Candidate change, long waitNs) {
        AccessCategory affected = null;
        double gain = 0;
        for (AccessCategory ac : AccessCategory.values()) {
            gain = change.classes.get(ac).expectedOnTime() - keep.classes.get(ac).expectedOnTime();
            if (Math.abs(gain) > NUMERICAL_TOLERANCE) {
                affected = ac;
                break;
            }
        }
        if (affected == null || gain < MIN_QOS_GAIN_PACKETS || keep.uncertain || change.uncertain) {
            return new Risk(affected, affected == null ? 0 : gain, false, null);
        }
        if (waitNs <= 0) {
            return new Risk(affected, gain, true, null);
        }
        Map<Packet, Double> before = new HashMap<>();
        for (var estimate : keep.liveDeadlines) {
            before.put(estimate.packet, estimate.onTimeProbability);
        }
        var requirement = change.classes.get(affected);
        ApDeadlineModePolicy.DeadlineEstimate urgent = null;
        for (var estimate : change.liveDeadlines) {
            if (estimate.packet.type() == affected && estimate.slackNs < waitNs
                    && estimate.onTimeProbability >= requirement.requiredOnTime()
                    && before.get(estimate.packet) < requirement.requiredOnTime()
                    && (urgent == null || estimate.slackNs < urgent.slackNs)) {
                urgent = estimate;
            }
        }
        return new Risk(affected, gain, true, urgent);
    }

    public enum Reason {
        SWITCH_IN_PROGRESS, SAME_TICK_GUARD, KEEP_QOS, HOLD_UNPROVEN_QOS, REVERSE_HYSTERESIS, KEEP_ENERGY,
        PAYBACK_BEYOND_FORECAST, RESIDENCY_HOLD, ECONOMICAL_SWITCH, QOS_IMPROVEMENT, URGENT_EXCEPTION;

        boolean requestsChange() {
            return this == ECONOMICAL_SWITCH || this == QOS_IMPROVEMENT || this == URGENT_EXCEPTION;
        }
    }

    public static final class Residence {
        public final long startedNs, eligibleAtNs;
        public final boolean afterSwitch;

        private Residence(long startedNs, long eligibleAtNs, boolean afterSwitch) {
            this.startedNs = startedNs;
            this.eligibleAtNs = eligibleAtNs;
            this.afterSwitch = afterSwitch;
        }
    }

    public static final class Decision {
        public final ApCapabilityMode target;
        public final Reason reason;
        public final Residence residence;
        public final ApDeadlineModePolicy.Candidate keepQos, changeQos;
        public final long economicResidenceNs, allowedDelayNs, delayNs;
        public final double marginUj, scanW, immediateUj;
        public final Economics economics;
        public final Risk risk;
        public final Deferred deferred;

        private Decision(ApCapabilityMode target, Reason reason, Residence residence, Economics economics,
                         double marginUj, double scanW, Risk risk, ApDeadlineModePolicy.Candidate keepQos,
                         ApDeadlineModePolicy.Candidate changeQos, long allowedDelayNs, long delayNs,
                         double immediateUj, Deferred deferred) {
            this.target = target;
            this.reason = reason;
            this.residence = residence;
            this.economics = economics;
            this.marginUj = marginUj;
            this.scanW = scanW;
            this.risk = risk;
            this.keepQos = keepQos;
            this.changeQos = changeQos;
            this.economicResidenceNs = economics.criticalNs;
            this.allowedDelayNs = allowedDelayNs;
            this.delayNs = delayNs;
            this.immediateUj = immediateUj;
            this.deferred = deferred;
        }
    }

    public static final class Economics {
        public final double keepUj, changeUj, returnReserveUj, keepScanUj, changeScanUj, roundTripPremiumUj, savingW, forecastNs, savingUj;
        private final long criticalNs;
        private final boolean transitionFits;

        private Economics(double keepUj, double changeUj, double returnReserveUj, double keepScanUj, double changeScanUj,
                          double roundTripPremiumUj, double savingW, long criticalNs, double forecastNs, boolean transitionFits) {
            this.keepUj = keepUj;
            this.changeUj = changeUj;
            this.returnReserveUj = returnReserveUj;
            this.keepScanUj = keepScanUj;
            this.changeScanUj = changeScanUj;
            this.roundTripPremiumUj = roundTripPremiumUj;
            this.savingW = savingW;
            this.criticalNs = criticalNs;
            this.forecastNs = forecastNs;
            this.savingUj = keepUj - (changeUj + returnReserveUj);
            this.transitionFits = transitionFits;
        }

        private boolean covered() {
            return transitionFits && criticalNs != Long.MAX_VALUE && criticalNs <= forecastNs;
        }
    }

    public static final class Risk {
        public final AccessCategory ac;
        public final double expectedOnTimeGain;
        private final boolean clear;
        public final ApDeadlineModePolicy.DeadlineEstimate urgent;

        private Risk(AccessCategory ac, double gain, boolean clear, ApDeadlineModePolicy.DeadlineEstimate urgent) {
            this.ac = ac;
            this.expectedOnTimeGain = gain;
            this.clear = clear;
            this.urgent = urgent;
        }

        public boolean clearImprovement() { return clear; }
    }

    public static final class Deferred {
        private static final Deferred UNAVAILABLE = new Deferred(Double.POSITIVE_INFINITY, 0, 0);
        public final double costUj, successBits;
        public final int batches;

        private Deferred(double costUj, double successBits, int batches) {
            this.costUj = costUj;
            this.successBits = successBits;
            this.batches = batches;
        }
    }
}
