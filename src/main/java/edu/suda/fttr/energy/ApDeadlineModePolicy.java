package edu.suda.fttr.energy;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.phy.ApCapabilityMode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** D3 的候选筛选只读取本机已排队的报文及 C3 预测。实测 C4 账本不参与决策。 */
public final class ApDeadlineModePolicy {
    public enum Status { FEASIBLE, DEADLINE_RISK, RELIABILITY_RISK, CAPACITY_RISK, INFORMATION_INSUFFICIENT }
    public enum Reason { FEASIBLE_ENERGY, ONLY_FEASIBLE, LOWER_VIOLATION_RISK, RISK_TIE_ENERGY }

    private final ApAnalysisSettings analysis;
    private final ApModeSwitch.Settings switching;
    private final long marginNs, delayLimitNs;

    public ApDeadlineModePolicy(ApAnalysisSettings analysis, ApModeSwitch.Settings switching, long marginNs,
                               long delayLimitNs) {
        this.analysis = analysis;
        this.switching = switching;
        this.marginNs = marginNs;
        this.delayLimitNs = delayLimitNs;
    }

    public Decision choose(long nowNs, ApEnergyModePolicy.Decision energy, ApModeSwitch.Readiness ready,
                           boolean externalBusy) {
        ApEnergyModePolicy.Candidate first = energy.candidates.get(0);
        ApEnergyModePolicy.Candidate second = energy.candidates.get(1);
        double activeBusy = (ready.projectedMode == ApCapabilityMode.LCM ? first : second).forecast.conservative.busyFraction;
        Candidate low = evaluate(nowNs, energy.horizonNs, ready, externalBusy, activeBusy, first);
        Candidate high = evaluate(nowNs, energy.horizonNs, ready, externalBusy, activeBusy, second);

        ApCapabilityMode target;
        Reason reason;
        if (low.status == Status.FEASIBLE && high.status == Status.FEASIBLE) {
            target = energy.target;
            reason = Reason.FEASIBLE_ENERGY;
        } else if (low.status == Status.FEASIBLE || high.status == Status.FEASIBLE) {
            target = low.status == Status.FEASIBLE ? low.mode : high.mode;
            reason = Reason.ONLY_FEASIBLE;
        } else {
            int risk = compareRisk(low, high);
            target = risk == 0 ? energy.target : risk < 0 ? low.mode : high.mode;
            reason = risk == 0 ? Reason.RISK_TIE_ENERGY : Reason.LOWER_VIOLATION_RISK;
        }

        long allowed = 0;
        if (target == ApCapabilityMode.HCM && ready.projectedMode == ApCapabilityMode.LCM && high.status == Status.FEASIBLE) {
            allowed = (long) Math.floor(Math.max(0, Math.min(delayLimitNs, high.minimumSlackNs)));
        }
        return new Decision(target, reason, allowed, List.of(low, high));
    }

    private Candidate evaluate(long nowNs, long horizonNs, ApModeSwitch.Readiness ready, boolean externalBusy,
                               double activeBusy, ApEnergyModePolicy.Candidate energy) {
        var forecast = energy.forecast;
        boolean changing = forecast.mode != ready.projectedMode;
        double shared = (double) ready.waitNs + ready.activeSwitchNs;
        double externalWait = changing && externalBusy ? Math.max(0, horizonNs - shared) * activeBusy : 0;
        long switchNs = changing ? switching.profileFrom(ready.projectedMode).delayNs() : 0;
        double lead = shared + externalWait + switchNs;

        EnumMap<AccessCategory, ClassRisk> byClass = new EnumMap<>(AccessCategory.class);
        for (AccessCategory ac : AccessCategory.values()) {
            byClass.put(ac, new ClassRisk(analysis.requirement(ac)));
        }
        double service = 0;
        double available = 1 - forecast.conservative.busyFraction;
        double minimumSlack = Double.POSITIVE_INFINITY;
        // C3 已按 AC 和各队列实际顺序构造批次，预测使用这一串行工作量估计前方等待。
        for (var batch : forecast.deadlineBatches) {
            service += available == 0 ? Double.POSITIVE_INFINITY : batch.workNs / available;
            for (Packet packet : batch.packets) {
                ClassRisk risk = byClass.get(packet.type());
                long remaining = remainingNs(packet, nowNs, risk.requirement);
                double slack = remaining - lead - service - marginNs;
                risk.add(remaining, service, slack, batch.successProbability);
                minimumSlack = Math.min(minimumSlack, slack);
            }
        }
        double arrivalMbps = forecast.expected.arrivalBits * 1_000 / horizonNs;
        double capacity = forecast.saturatedMbps >= 0 ? forecast.saturatedMbps
                : forecast.expected.workNs == 0 ? 0
                : forecast.expected.successBits / forecast.expected.workNs * (1 - forecast.expected.busyFraction) * 1_000;
        double shortfall = Math.max(0, arrivalMbps - capacity);
        boolean hasWork = forecast.expected.demandBits > 0;
        boolean uncertain = hasWork && (forecast.channelIncomplete || ready.timingUncertain);
        Status status = uncertain ? Status.INFORMATION_INSUFFICIENT : shortfall > 1e-9 ? Status.CAPACITY_RISK : Status.FEASIBLE;
        for (ClassRisk risk : byClass.values()) {
            if (risk.packets > 0 && risk.onTimeRatio() + 1e-12 < risk.requirement.minOnTime()) {
                status = risk.latePackets > 0 ? Status.DEADLINE_RISK : Status.RELIABILITY_RISK;
                break;
            }
        }
        return new Candidate(forecast.mode, status, uncertain, byClass, ready.waitNs, ready.activeSwitchNs,
                externalWait, switchNs, marginNs, minimumSlack, shortfall);
    }

    private static int compareRisk(Candidate left, Candidate right) {
        // 先比较各 AC 的预计违约数，再用同 AC 的迟到幅度区分两个都会违约的候选。
        for (AccessCategory ac : AccessCategory.values()) {
            ClassRisk a = left.classes.get(ac);
            ClassRisk b = right.classes.get(ac);
            int order = Double.compare(a.expectedMisses(), b.expectedMisses());
            if (order == 0) {
                order = Double.compare(a.latenessNs, b.latenessNs);
            }
            if (order != 0) {
                return order;
            }
        }
        int capacity = Double.compare(left.capacityShortfallMbps, right.capacityShortfallMbps);
        return capacity == 0 ? Boolean.compare(left.uncertain, right.uncertain) : capacity;
    }

    public boolean urgent(Packet packet, long nowNs, long decisionPeriodNs) {
        return packet.type() == AccessCategory.VO || packet.type() == AccessCategory.VI
                || remainingNs(packet, nowNs, analysis.requirement(packet.type())) <= decisionPeriodNs;
    }

    private static long remainingNs(Packet packet, long nowNs, ApAnalysisSettings.Requirement requirement) {
        long generated = Math.multiplyExact(packet.generationTimeUs(), 1_000);
        if (generated > nowNs) {
            throw new IllegalArgumentException("QoS cannot read a packet before its generation");
        }
        return requirement.deadlineNs() - (nowNs - generated);
    }

    public static final class Decision {
        public final ApCapabilityMode target;
        public final Reason reason;
        public final long maxDelayNs;
        public final List<Candidate> candidates;

        private Decision(ApCapabilityMode target, Reason reason, long maxDelayNs, List<Candidate> candidates) {
            this.target = target;
            this.reason = reason;
            this.maxDelayNs = maxDelayNs;
            this.candidates = candidates;
        }
    }

    public static final class Candidate {
        public final ApCapabilityMode mode;
        public final Status status;
        public final boolean uncertain;
        public final Map<AccessCategory, ClassRisk> classes;
        public final long localWaitNs, activeSwitchNs, switchNs, marginNs;
        public final double externalWaitNs, minimumSlackNs, capacityShortfallMbps;

        private Candidate(ApCapabilityMode mode, Status status, boolean uncertain, Map<AccessCategory, ClassRisk> classes,
                          long localWaitNs, long activeSwitchNs, double externalWaitNs, long switchNs, long marginNs,
                          double minimumSlackNs, double capacityShortfallMbps) {
            this.mode = mode;
            this.status = status;
            this.uncertain = uncertain;
            this.classes = Map.copyOf(classes);
            this.localWaitNs = localWaitNs;
            this.activeSwitchNs = activeSwitchNs;
            this.externalWaitNs = externalWaitNs;
            this.switchNs = switchNs;
            this.marginNs = marginNs;
            this.minimumSlackNs = minimumSlackNs;
            this.capacityShortfallMbps = capacityShortfallMbps;
        }
    }

    public static final class ClassRisk {
        private final ApAnalysisSettings.Requirement requirement;
        private long packets, latePackets;
        private long earliestRemainingNs = Long.MAX_VALUE;
        private double onTime, latenessNs, serviceNs, minimumSlackNs = Double.POSITIVE_INFINITY;

        private ClassRisk(ApAnalysisSettings.Requirement requirement) {
            this.requirement = requirement;
        }

        private void add(long remaining, double service, double slack, double probability) {
            packets++;
            if (slack < 0) {
                latePackets++;
                latenessNs += -slack;
            } else {
                onTime += probability;
            }
            earliestRemainingNs = Math.min(earliestRemainingNs, remaining);
            serviceNs = service;
            minimumSlackNs = Math.min(minimumSlackNs, slack);
        }

        public long packets() { return packets; }
        public long deadlineNs() { return requirement.deadlineNs(); }
        public double requiredOnTime() { return requirement.minOnTime(); }
        public double onTimeRatio() { return packets == 0 ? 1 : onTime / packets; }
        public double expectedMisses() { return packets - onTime; }
        public long earliestRemainingNs() { return earliestRemainingNs; }
        public double serviceNs() { return serviceNs; }
        public double minimumSlackNs() { return minimumSlackNs; }
    }
}
