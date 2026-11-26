package edu.suda.fttr.energy;

import edu.suda.fttr.energy.ApModePredictor.ServiceForecast;
import edu.suda.fttr.energy.ApModePredictor.ServiceWork;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.phy.ApCapabilityMode;

import java.util.List;

/** D2 比较同一批业务的能耗。预测时窗和两种方案共享的已承诺事务均固定。 */
public final class ApEnergyModePolicy {
    public enum Reason {
        KEEP_LOWER_COST, ENERGY_MARGIN, SWITCH_SAVES_ENERGY, SWITCH_SERVICE_AVAILABLE,
        CHANNEL_INFORMATION_INSUFFICIENT, WAIT_TIMING_UNCERTAIN, TRANSITION_OUTSIDE_WINDOW,
        ALTERNATIVE_UNSERVICEABLE, NO_COMPLETION_MODEL
    }

    private final ApEnergyModel energy;
    private final ApModeSwitch.Settings switching;
    private final double marginUj;

    public ApEnergyModePolicy(ApEnergyModel energy, ApModeSwitch.Settings switching, double marginUj) {
        this.energy = energy;
        this.switching = switching;
        this.marginUj = marginUj;
    }

    public Decision choose(long horizonNs, ApModeSwitch.Readiness ready, boolean externalBusy,
                           List<ServiceForecast> forecasts) {
        ServiceForecast low = forecasts.get(0);
        ServiceForecast high = forecasts.get(1);
        if (low.mode != ApCapabilityMode.LCM || high.mode != ApCapabilityMode.HCM
                || Math.abs(low.expected.demandBits - high.expected.demandBits) > Math.max(1e-8, low.expected.demandBits * 1e-12)) {
            throw new IllegalArgumentException("D2 candidates must describe the same workload in LCM/HCM order");
        }
        ServiceForecast base = ready.projectedMode == ApCapabilityMode.LCM ? low : high;
        Candidate lcm = evaluate(horizonNs, ready, externalBusy, low, base);
        Candidate hcm = evaluate(horizonNs, ready, externalBusy, high, base);
        Candidate keep = base == low ? lcm : hcm;
        Candidate change = base == low ? hcm : lcm;
        Reason reason = reason(keep, change, ready);
        ApCapabilityMode target = reason == Reason.SWITCH_SAVES_ENERGY || reason == Reason.SWITCH_SERVICE_AVAILABLE
                ? change.forecast.mode : keep.forecast.mode;
        return new Decision(horizonNs, ready, marginUj, target, reason, List.of(lcm, hcm));
    }

    private Reason reason(Candidate keep, Candidate change, ApModeSwitch.Readiness ready) {
        double keepCost = keep.expected.totalUj;
        double changeCost = change.expected.totalUj;
        if (!change.expected.transitionFits) {
            return Reason.TRANSITION_OUTSIDE_WINDOW;
        }
        if (!Double.isFinite(changeCost)) {
            return Double.isFinite(keepCost) ? Reason.ALTERNATIVE_UNSERVICEABLE : Reason.NO_COMPLETION_MODEL;
        }
        if (changeCost >= keepCost) {
            return Reason.KEEP_LOWER_COST;
        }
        if (changeCost + marginUj >= keepCost) {
            return Reason.ENERGY_MARGIN;
        }
        if (ready.timingUncertain) {
            return Reason.WAIT_TIMING_UNCERTAIN;
        }
        if ((keep.forecast.channelIncomplete || change.forecast.channelIncomplete)
                && !(change.highCostUj + marginUj < keep.lowCostUj)) {
            return Reason.CHANNEL_INFORMATION_INSUFFICIENT;
        }
        return Double.isFinite(keepCost) ? Reason.SWITCH_SAVES_ENERGY : Reason.SWITCH_SERVICE_AVAILABLE;
    }

    private Candidate evaluate(long horizonNs, ApModeSwitch.Readiness ready, boolean externalBusy,
                               ServiceForecast forecast, ServiceForecast base) {
        Cost expected = cost(horizonNs, ready, externalBusy, forecast.mode, forecast.expected, base.expected);
        Cost conservative = cost(horizonNs, ready, externalBusy, forecast.mode, forecast.conservative, base.conservative);
        Cost optimistic = cost(horizonNs, ready, externalBusy, forecast.mode, forecast.optimistic, base.optimistic);
        return new Candidate(forecast, expected, conservative, optimistic);
    }

    /** D4 的稳态参照只比较两档处理同一份工作所需的能耗。 */
    Cost steady(long horizonNs, ServiceForecast forecast) {
        return cost(horizonNs, idleReady(forecast.mode), false, forecast.mode, forecast.expected, forecast.expected);
    }

    /** 主动等待已交付的比特只从剩余业务扣一次。切换与后续等待仍由同一 D2 成本模型计算。 */
    Cost afterLcmWork(long horizonNs, ServiceForecast lcm, ServiceForecast hcm, boolean externalBusy, double remainingRatio) {
        ServiceWork full = hcm.expected;
        var remaining = new ServiceWork(full.demandBits * remainingRatio, full.demandPackets * remainingRatio,
                full.arrivalBits * remainingRatio, full.successBits * remainingRatio, full.workNs * remainingRatio,
                full.txNs * remainingRatio, full.rxNs * remainingRatio, full.busyFraction, full.meanFailure, full.ampduDepth);
        return cost(horizonNs, idleReady(ApCapabilityMode.LCM), externalBusy, ApCapabilityMode.HCM, remaining, lcm.expected);
    }

    private static ApModeSwitch.Readiness idleReady(ApCapabilityMode mode) {
        return new ApModeSwitch.Readiness(mode, mode, 0, 0, 0, 0, false);
    }

    private Cost cost(long horizonNs, ApModeSwitch.Readiness ready, boolean externalBusy, ApCapabilityMode mode,
                      ServiceWork work, ServiceWork base) {
        ApEnergyModel.Power sourcePower = energy.powerFor(ready.sourceMode);
        ApEnergyModel.Power modePower = energy.powerFor(mode);
        boolean changing = mode != ready.projectedMode;
        double remaining = horizonNs - ready.waitNs - ready.activeSwitchNs;
        // 当前 AP 仍忙时，以可见的忙份额近似等待。候选模式的忙份额只在切换完成后的区间生效。
        double ccaWait = changing && externalBusy ? remaining * base.busyFraction : 0;
        double beforeNewSwitch = remaining - ccaWait;
        long requiredSwitchNs = changing ? switching.profileFrom(ready.projectedMode).delayNs() : 0;
        double newSwitchNs = Math.min(requiredSwitchNs, beforeNewSwitch);
        boolean fits = !changing || requiredSwitchNs < beforeNewSwitch;
        double serviceNs = beforeNewSwitch - newSwitchNs;
        double radioAvailable = serviceNs * (1 - work.busyFraction);
        double served = work.workNs == 0 ? 1 : Math.min(1, radioAvailable / work.workNs);
        double successBits = Math.min(work.demandBits, work.successBits * served);
        double unserved = work.demandBits - successBits;
        double txService = work.txNs * served;
        double rxService = work.rxNs * served;
        double idleService = Math.max(0, serviceNs - txService - rxService);
        double idleWait = ready.waitNs - ready.txNs - ready.rxNs;
        double txUj = energyUj(txService, modePower.txW().doubleValue()) + energyUj(ready.txNs, sourcePower.txW().doubleValue());
        double rxUj = energyUj(rxService, modePower.rxW().doubleValue()) + energyUj(ready.rxNs, sourcePower.rxW().doubleValue());
        double idleUj = energyUj(idleService, modePower.idleW().doubleValue())
                + energyUj(idleWait, sourcePower.idleW().doubleValue())
                + energyUj(ccaWait, energy.powerFor(ready.projectedMode).idleW().doubleValue());
        double activeUj = ready.activeSwitchNs == 0 ? 0
                : ApEnergyModel.switchEnergyUj(ready.activeSwitchNs, switching.powerFrom(ready.sourceMode)).doubleValue();
        double newUj = changing ? energyUj(newSwitchNs, switching.powerFrom(ready.projectedMode).doubleValue()) : 0;

        // 同一批未交付的比特按该模式在相同信道假设下的服务效率补价，包含等待和重试的功耗。
        double tailNs = 0;
        double tailUj = 0;
        if (unserved > 0) {
            if (work.successBits == 0 || work.busyFraction == 1) {
                tailNs = tailUj = Double.POSITIVE_INFINITY;
            } else {
                double scale = unserved / work.successBits;
                tailNs = work.workNs * scale / (1 - work.busyFraction);
                double tailTx = work.txNs * scale;
                double tailRx = work.rxNs * scale;
                tailUj = energyUj(tailTx, modePower.txW().doubleValue()) + energyUj(tailRx, modePower.rxW().doubleValue())
                        + energyUj(Math.max(0, tailNs - tailTx - tailRx), modePower.idleW().doubleValue());
            }
        }
        double effectiveService = work.workNs == 0 ? 0 : (work.successBits / work.workNs) * radioAvailable * 1_000 / horizonNs;
        double windowUj = txUj + rxUj + idleUj + activeUj + newUj;
        return new Cost(fits, successBits, unserved, work.demandPackets * (1 - served), effectiveService, radioAvailable,
                ccaWait, newSwitchNs, ready.txNs + txService, ready.rxNs + rxService, idleWait + ccaWait + idleService,
                txUj, rxUj, idleUj, activeUj, newUj, windowUj, tailNs, tailUj, windowUj + tailUj);
    }

    private static double energyUj(double ns, double watts) {
        return ns / 1_000 * watts;
    }

    public static final class Decision {
        public final long horizonNs;
        public final ApModeSwitch.Readiness readiness;
        public final double marginUj;
        public final ApCapabilityMode target;
        public final Reason reason;
        public final List<Candidate> candidates;

        private Decision(long horizonNs, ApModeSwitch.Readiness readiness, double marginUj, ApCapabilityMode target,
                         Reason reason, List<Candidate> candidates) {
            this.horizonNs = horizonNs;
            this.readiness = readiness;
            this.marginUj = marginUj;
            this.target = target;
            this.reason = reason;
            this.candidates = candidates;
        }
    }

    public static final class Candidate {
        public final ServiceForecast forecast;
        public final Cost expected, conservative, optimistic;
        public final double lowerSuccessBits, upperSuccessBits, lowCostUj, highCostUj;

        private Candidate(ServiceForecast forecast, Cost expected, Cost conservative, Cost optimistic) {
            this.forecast = forecast;
            this.expected = expected;
            this.conservative = conservative;
            this.optimistic = optimistic;
            lowerSuccessBits = Math.min(conservative.successBits, Math.min(optimistic.successBits, expected.successBits));
            upperSuccessBits = Math.max(conservative.successBits, Math.max(optimistic.successBits, expected.successBits));
            lowCostUj = Math.min(optimistic.totalUj, Math.min(conservative.totalUj, expected.totalUj));
            highCostUj = Math.max(optimistic.totalUj, Math.max(conservative.totalUj, expected.totalUj));
        }
    }

    public static final class Cost {
        public final boolean transitionFits;
        public final double successBits, unservedBits, endingQueuePackets, effectiveServiceMbps, availableNs;
        public final double ccaWaitNs, newSwitchNs, txNs, rxNs, idleNs, txUj, rxUj, idleUj, activeUj, newSwitchUj;
        public final double windowUj, tailNs, tailUj, totalUj;

        private Cost(boolean transitionFits, double successBits, double unservedBits, double endingQueuePackets,
                     double effectiveServiceMbps, double availableNs, double ccaWaitNs, double newSwitchNs, double txNs,
                     double rxNs, double idleNs, double txUj, double rxUj, double idleUj, double activeUj,
                     double newSwitchUj, double windowUj, double tailNs, double tailUj, double totalUj) {
            this.transitionFits = transitionFits;
            this.successBits = successBits;
            this.unservedBits = unservedBits;
            this.endingQueuePackets = endingQueuePackets;
            this.effectiveServiceMbps = effectiveServiceMbps;
            this.availableNs = availableNs;
            this.ccaWaitNs = ccaWaitNs;
            this.newSwitchNs = newSwitchNs;
            this.txNs = txNs;
            this.rxNs = rxNs;
            this.idleNs = idleNs;
            this.txUj = txUj;
            this.rxUj = rxUj;
            this.idleUj = idleUj;
            this.activeUj = activeUj;
            this.newSwitchUj = newSwitchUj;
            this.windowUj = windowUj;
            this.tailNs = tailNs;
            this.tailUj = tailUj;
            this.totalUj = transitionFits ? totalUj : Double.POSITIVE_INFINITY;
        }
    }
}
