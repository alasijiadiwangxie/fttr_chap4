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
    private final long defaultDeferralLimitNs;

    public ApResidencyModePolicy(ApEnergyModel power, ApModeSwitch.Settings switching, ApEnergyModePolicy energyPolicy,
                                 ApModeController.Settings settings) {
        this.power = power;
        this.switching = switching;
        this.energyPolicy = energyPolicy;
        this.minimumNs = settings.minimumResidencyNs();
        this.reverseMarginUj = settings.reverseMarginUj();
        this.defaultDeferralLimitNs = settings.deferralLimitNs();
    }

    public Residence start(long nowNs, Decision executed) {
        return start(nowNs, executed, true);
    }

    public Residence start(long nowNs, Decision executed, boolean protect) {
        // 驻留从接收能力真正改变时计时。QoS 驱动的换档只应用基本驻留时间。
        long duration = protect ? minimumNs : 0;
        if (protect && executed != null && executed.reason.economic()) {
            duration = Math.max(minimumNs, executed.economicResidenceNs);
        }
        return new Residence(nowNs, nowNs + Math.min(duration, Long.MAX_VALUE - nowNs), executed != null);
    }

    public Decision choose(long nowNs, Residence residence, long untilReviewNs, long delayLimitNs, double scanW,
                           ApEnergyModePolicy.Decision energy, ApDeadlineModePolicy.Decision qos, boolean externalBusy) {
        return choose(nowNs, residence, untilReviewNs, delayLimitNs, scanW, energy, qos, externalBusy, null);
    }

    public Decision choose(long nowNs, Residence residence, long untilReviewNs, long delayLimitNs, double scanW,
                           ApEnergyModePolicy.Decision energy, ApDeadlineModePolicy.Decision qos, boolean externalBusy,
                           ApModeController.CompleteFeatures features) {
        var ready = energy.readiness;
        var current = ready.projectedMode;
        int base = current == ApCapabilityMode.LCM ? 0 : 1;
        var keep = energy.candidates.get(base);
        var change = energy.candidates.get(1 - base);
        var keepQos = qos.candidates.get(base);
        var changeQos = qos.candidates.get(1 - base);
        boolean checkQos = features == null || features.qos;
        boolean economicProtection = features == null || features.residency;
        boolean keepFeasible = !checkQos || feasible(keepQos);
        boolean changeFeasible = !checkQos || feasible(changeQos);
        boolean bothRisky = !keepFeasible && !changeFeasible;
        double marginUj = energy.marginUj + (residence.afterSwitch && economicProtection ? reverseMarginUj : 0);

        Economics economics = economics(energy, keep, change, scanW, marginUj, economicProtection);
        long untilRelease = Math.max(0, residence.eligibleAtNs - nowNs);
        Risk risk = checkQos ? risk(keepQos, changeQos, Math.min(untilRelease, untilReviewNs)) : Risk.NONE;
        // D4 只接受可挽救报文带来的明确增益。完整策略也允许 QoS 不退步且仍能承担业务的经济降档。
        boolean safeExit = features != null && current == ApCapabilityMode.HCM && bothRisky && !risk.clearImprovement()
                && safeEconomicExit(keepQos, changeQos, keep, change);
        boolean energyLed = keepFeasible && changeFeasible || safeExit;
        boolean exception = untilRelease > 0 && risk.urgent != null && !keepFeasible;
        Reason reason;
        if (ready.activeSwitchNs > 0) {
            reason = Reason.SWITCH_IN_PROGRESS;
        } else if (residence.afterSwitch && nowNs == residence.startedNs) {
            reason = Reason.SAME_TICK_GUARD;
        } else if (keepFeasible && !changeFeasible) {
            reason = Reason.KEEP_QOS;
        } else if (bothRisky && !risk.clearImprovement() && !safeExit) {
            reason = Reason.HOLD_UNPROVEN_QOS;
        } else if (energyLed && !(economics.savingUj > marginUj)) {
            reason = residence.afterSwitch && economics.savingUj > energy.marginUj
                    ? Reason.REVERSE_HYSTERESIS : Reason.KEEP_ENERGY;
        } else if (energyLed && !(economicProtection ? economics.covered() : change.expected.transitionFits)) {
            reason = Reason.PAYBACK_BEYOND_FORECAST;
        } else if (untilRelease > 0 && !exception) {
            reason = Reason.RESIDENCY_HOLD;
        } else {
            reason = exception ? Reason.URGENT_EXCEPTION : safeExit ? Reason.SAFE_ECONOMIC_EXIT
                    : energyLed ? Reason.ECONOMICAL_SWITCH : Reason.QOS_IMPROVEMENT;
        }

        var target = reason.requestsChange() ? change.forecast.mode : current;
        boolean canDelay = (features == null || features.deferral) && target == ApCapabilityMode.HCM
                && current == ApCapabilityMode.LCM && changeFeasible;
        long allowed = canDelay ? Math.min(checkQos ? qos.maxDelayNs : defaultDeferralLimitNs, delayLimitNs) : 0;
        Deferred deferred = allowed > 0 && reason != Reason.URGENT_EXCEPTION
                ? defer(energy, keep, change, allowed, scanW, externalBusy, economics.returnReserveUj) : Deferred.UNAVAILABLE;
        double immediateUj = economics.changeUj + economics.returnReserveUj;
        long delayNs = deferred.costUj + energy.marginUj < immediateUj && Double.isFinite(deferred.costUj) ? allowed : 0;
        return new Decision(target, reason, residence, economics, marginUj, scanW, risk, keepQos, changeQos,
                allowed, delayNs, immediateUj, deferred, safeExit);
    }

    /** 紧急事件可重算。只有换档能挽救一包、且等到下次周期会失去机会时才即时提交。 */
    public static Risk urgentAction(Decision proposal, long untilReviewNs) {
        return risk(proposal.keepQos, proposal.changeQos, untilReviewNs);
    }

    private static boolean safeEconomicExit(ApDeadlineModePolicy.Candidate keepQos,
                                            ApDeadlineModePolicy.Candidate changeQos,
                                            ApEnergyModePolicy.Candidate keep, ApEnergyModePolicy.Candidate change) {
        // 比较相同时窗内、含 B4 等待和新切换的成功量。仅比时延概率相同会误放行更慢的 LCM。
        if (keepQos.uncertain || changeQos.uncertain || !change.expected.transitionFits
                || change.expected.successBits + NUMERICAL_TOLERANCE < keep.expected.successBits
                || changeQos.capacityShortfallMbps > keepQos.capacityShortfallMbps + NUMERICAL_TOLERANCE) {
            return false;
        }
        Map<Packet, Double> previous = new HashMap<>();
        for (var estimate : keepQos.liveDeadlines) {
            previous.put(estimate.packet, estimate.onTimeProbability);
        }
        return changeQos.liveDeadlines.size() == previous.size() && changeQos.liveDeadlines.stream().allMatch(
                estimate -> estimate.onTimeProbability + NUMERICAL_TOLERANCE
                        >= previous.getOrDefault(estimate.packet, Double.POSITIVE_INFINITY));
    }

    private Economics economics(ApEnergyModePolicy.Decision plan, ApEnergyModePolicy.Candidate keep,
                                ApEnergyModePolicy.Candidate change, double scanW, double marginUj, boolean protect) {
        var source = keep.forecast.mode;
        var target = change.forecast.mode;
        long horizon = plan.horizonNs;
        long available = horizon - plan.readiness.waitNs - plan.readiness.activeSwitchNs;
        var entry = switching.profileFrom(source);
        var exit = switching.profileFrom(target);
        BigDecimal idle = power.powerFor(source).idleW();
        BigDecimal entryPremium = switchPremium(entry, idle);
        BigDecimal returnPremium = protect ? switchPremium(exit, idle) : BigDecimal.ZERO;
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
        PAYBACK_BEYOND_FORECAST, RESIDENCY_HOLD, ECONOMICAL_SWITCH, SAFE_ECONOMIC_EXIT, QOS_IMPROVEMENT, URGENT_EXCEPTION;

        boolean requestsChange() {
            return economic() || this == QOS_IMPROVEMENT || this == URGENT_EXCEPTION;
        }

        private boolean economic() { return this == ECONOMICAL_SWITCH || this == SAFE_ECONOMIC_EXIT; }
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
        public final boolean safeEconomicExit;

        private Decision(ApCapabilityMode target, Reason reason, Residence residence, Economics economics,
                         double marginUj, double scanW, Risk risk, ApDeadlineModePolicy.Candidate keepQos,
                         ApDeadlineModePolicy.Candidate changeQos, long allowedDelayNs, long delayNs,
                         double immediateUj, Deferred deferred, boolean safeEconomicExit) {
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
            this.safeEconomicExit = safeEconomicExit;
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
        private static final Risk NONE = new Risk(null, 0, false, null);
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
