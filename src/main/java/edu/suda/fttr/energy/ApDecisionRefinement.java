package edu.suda.fttr.energy;

import edu.suda.fttr.channel.CcaHistory;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeController.RefinementOptions;
import edu.suda.fttr.entity.ApTrafficObserver.Observation;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.phy.ApCapabilityMode;

import java.util.List;

/** E3 的每 AP 决策证据。输入均为 C1/C3 当前可见数据，不控制队列或切换执行器。 */
public final class ApDecisionRefinement {
    private static final int REQUIRED_DEFICIT_WINDOWS = 2;
    private static final int CLEAN_PRIORITY_WINDOWS = 4;
    private static final double MIN_SERVICE_GAIN_FRACTION = 0.10;
    private static final double EPSILON = 1e-9;

    private final RefinementOptions options;
    private final long periodNs;
    private final boolean separateObservationClock;
    private final double alpha;
    private final ApAnalysisSettings deadlines;
    private ApCapabilityMode previousMode;
    private int deficitWindows;
    private long previousBacklogBytes;
    private double previousDeliveredMbps, measuredServiceMbps;
    private double previousArrivalForecastMbps = Double.NaN;
    private double arrivalErrorMbps;
    private boolean widePriorityRisk;
    private int cleanPriorityWindows;
    private long closedArrivalBytes, closedDeliveryBytes, closedVisibleBytes;
    private long reviewedArrivalBytes, reviewedDeliveryBytes, reviewedVisibleBytes, reviewedNs;

    public ApDecisionRefinement(RefinementOptions options, long periodNs, long observationPeriodNs,
                                double alpha, ApAnalysisSettings deadlines) {
        this.options = options;
        this.periodNs = periodNs;
        this.separateObservationClock = observationPeriodNs != periodNs;
        this.alpha = alpha;
        this.deadlines = deadlines;
    }

    /** C1 独立采样时保留已关闭的小窗，复核点再接上尚未关闭的部分。 */
    public void closeObservation(List<Observation> rows) {
        if (!separateObservationClock) {
            return;
        }
        for (Observation row : rows) {
            closedVisibleBytes += row.arrivalBytes();
            if (row.direction() == Packet.Direction.DL) {
                closedArrivalBytes += row.arrivalBytes();
                closedDeliveryBytes += row.deliveredBytes();
            }
        }
    }

    public ObservationWindow observe(ApCapabilityMode mode, List<Observation> rows, boolean periodic, boolean mixedMode,
                                     CcaHistory.Snapshot history) {
        long backlog = 0;
        long oldest = 0;
        long arrived = 0;
        long delivered = 0;
        long intervalNs = 0;
        long visibleBytes = 0;
        long priorityDeadlineNs = Long.MAX_VALUE;
        double arrivalForecastMbps = 0;
        for (Observation row : rows) {
            visibleBytes += row.arrivalBytes();
            arrivalForecastMbps += row.ewmaArrivalMbps();
            if (row.direction() != Packet.Direction.DL) {
                continue;
            }
            backlog += row.queueBytes() + row.inFlightBytes();
            oldest = Math.max(oldest, row.headWaitNs());
            arrived += row.arrivalBytes();
            delivered += row.deliveredBytes();
            intervalNs = row.endNs() - row.startNs();
            if (isPriority(row.category()) && (row.queuePackets() > 0 || row.arrivalPackets() > 0
                    || row.ewmaArrivalPps() * periodNs >= 1e9)) {
                priorityDeadlineNs = Math.min(priorityDeadlineNs, deadlines.requirement(row.category()).deadlineNs());
            }
        }
        if (separateObservationClock) {
            long now = rows.get(0).endNs();
            long cumulativeArrival = closedArrivalBytes + arrived;
            long cumulativeDelivery = closedDeliveryBytes + delivered;
            long cumulativeVisible = closedVisibleBytes + visibleBytes;
            arrived = cumulativeArrival - reviewedArrivalBytes;
            delivered = cumulativeDelivery - reviewedDeliveryBytes;
            visibleBytes = cumulativeVisible - reviewedVisibleBytes;
            intervalNs = now - reviewedNs;
            if (periodic) {
                reviewedNs = now;
                reviewedArrivalBytes = cumulativeArrival;
                reviewedDeliveryBytes = cumulativeDelivery;
                reviewedVisibleBytes = cumulativeVisible;
            }
        }
        long backlogDelta = backlog - previousBacklogBytes;
        double arrivalMbps = rate(arrived, intervalNs);
        double deliveredMbps = rate(delivered, intervalNs);
        if (periodic) {
            // 用同一模式完整的已结束周期确认服务缺口。紧急事件不消费统计窗口。
            if (mode != previousMode || mixedMode || intervalNs != periodNs) {
                deficitWindows = 0;
            }
            if (!mixedMode && intervalNs == periodNs && oldest >= periodNs && arrived > delivered) {
                measuredServiceMbps = deficitWindows == 0 ? deliveredMbps : (previousDeliveredMbps + deliveredMbps) / 2;
                deficitWindows = Math.min(REQUIRED_DEFICIT_WINDOWS, deficitWindows + 1);
            } else {
                deficitWindows = 0;
                measuredServiceMbps = 0;
            }
            previousBacklogBytes = backlog;
            previousDeliveredMbps = deliveredMbps;
            previousMode = mode;
            if (intervalNs == periodNs) {
                double observedArrival = rate(visibleBytes, intervalNs);
                if (Double.isFinite(previousArrivalForecastMbps)) {
                    double residual = Math.abs(previousArrivalForecastMbps - observedArrival);
                    arrivalErrorMbps += alpha * (residual - arrivalErrorMbps);
                }
                previousArrivalForecastMbps = arrivalForecastMbps;
            }
        }
        observePriorityCca(history, priorityDeadlineNs, periodic && intervalNs == periodNs);
        double upperDemand = Math.max(arrivalForecastMbps + arrivalErrorMbps,
                intervalNs == periodNs ? rate(visibleBytes, intervalNs) : arrivalForecastMbps);
        return new ObservationWindow(options, periodic, backlog, backlogDelta, oldest, deficitWindows,
                arrivalMbps, deliveredMbps, measuredServiceMbps, upperDemand,
                widePriorityRisk && priorityDeadlineNs != Long.MAX_VALUE);
    }

    private void observePriorityCca(CcaHistory.Snapshot history, long deadlineNs, boolean fullReview) {
        if (!options.capacity || history == null) {
            return;
        }
        boolean witnessed = history.wide.longestBusyNs >= deadlineNs && history.primary.longestBusyNs < deadlineNs;
        if (witnessed || history.wide.busyNs > history.primary.busyNs) {
            // 宽档的一段实测连续忙碌已经覆盖整项时延预算。少量未命中的扫描不代表干扰消失。
            widePriorityRisk |= witnessed;
            cleanPriorityWindows = 0;
        } else if (fullReview && widePriorityRisk) {
            cleanPriorityWindows = history.wide.missingRecentBand() ? 0 : cleanPriorityWindows + 1;
            if (cleanPriorityWindows >= CLEAN_PRIORITY_WINDOWS) {
                widePriorityRisk = false;
            }
        }
    }

    static Capacity capacity(ObservationWindow sample, ApEnergyModePolicy.Decision plan,
                             ApEnergyModePolicy.Candidate keep, ApEnergyModePolicy.Candidate change,
                             ApDeadlineModePolicy.Candidate keepQos, ApDeadlineModePolicy.Candidate changeQos,
                             boolean checkQos) {
        if (sample == null || !sample.options.capacity) {
            return Capacity.DISABLED;
        }
        if (checkQos && sample.widePriorityRisk && change.forecast.mode == ApCapabilityMode.HCM) {
            return Capacity.PRIORITY_CCA;
        }
        if (!sample.periodic || sample.deficitWindows < REQUIRED_DEFICIT_WINDOWS) {
            return Capacity.TRANSIENT;
        }
        if (plan.readiness.timingUncertain || change.forecast.channelIncomplete || !change.expected.transitionFits) {
            return Capacity.INSUFFICIENT_INFORMATION;
        }
        // 当前档的短期预测可能低于已测成功服务。容量恢复必须也能改善这个可观测参照。
        double reference = Math.max(keep.expected.successBits, sample.measuredServiceMbps * plan.horizonNs / 1_000);
        var work = keep.forecast.expected;
        double typicalPacketBits = work.demandBits / Math.max(1, work.demandPackets);
        double minimumGain = Math.max(typicalPacketBits, reference * MIN_SERVICE_GAIN_FRACTION);
        if (change.expected.successBits - reference < minimumGain
                || change.forecast.saturatedMbps <= keep.forecast.saturatedMbps
                || !(change.expected.unservedBits < keep.expected.unservedBits)) {
            return Capacity.NO_SERVICE_GAIN;
        }
        if (checkQos && (losesTimelyPackets(AccessCategory.VO, keepQos, changeQos)
                || losesTimelyPackets(AccessCategory.VI, keepQos, changeQos))) {
            return Capacity.PRIORITY_RISK;
        }
        return Capacity.RECOVER;
    }

    private static boolean losesTimelyPackets(AccessCategory ac, ApDeadlineModePolicy.Candidate keep,
                                              ApDeadlineModePolicy.Candidate change) {
        return change.classes.get(ac).expectedOnTime() + EPSILON < keep.classes.get(ac).expectedOnTime();
    }

    static boolean isPriority(AccessCategory ac) {
        return ac == AccessCategory.VO || ac == AccessCategory.VI;
    }

    static EconomicEvidence economics(ObservationWindow sample, ApEnergyModel power, ApEnergyModePolicy.Decision plan,
                                      ApEnergyModePolicy.Candidate keep, ApEnergyModePolicy.Candidate change,
                                      double scanW, double returnReserveUj) {
        if (sample == null || !sample.options.economics) {
            return EconomicEvidence.DISABLED;
        }
        long window = plan.horizonNs - plan.readiness.waitNs - plan.readiness.activeSwitchNs;
        double keepIdleW = power.powerFor(keep.forecast.mode).idleW().doubleValue();
        double changeIdleW = power.powerFor(change.forecast.mode).idleW().doubleValue();
        double expected = completeSaving(keep.expected, change.expected, window, keep.forecast.mode == ApCapabilityMode.LCM,
                keepIdleW, changeIdleW, scanW, returnReserveUj);
        // 最有利于原档与最不利于候选档使用同一业务定义、同一观察时长和相同的排空终点。
        double conservative = completeSaving(keep.optimistic, change.conservative, window,
                keep.forecast.mode == ApCapabilityMode.LCM, keepIdleW, changeIdleW, scanW, returnReserveUj);
        // 刚到达的业务可能多于单个 EWMA 点预测。只有经济动作要通过这条额外约束，紧急救援不受它阻挡。
        boolean headroom = keep.forecast.expected.demandBits == 0
                || change.conservative.effectiveServiceMbps + EPSILON >= sample.upperDemandMbps;
        return new EconomicEvidence(expected, Math.min(expected, conservative), !plan.readiness.timingUncertain, headroom);
    }

    static double completeSaving(ApEnergyModePolicy.Cost keep, ApEnergyModePolicy.Cost change, long window,
                                 boolean sourceLcm, double keepIdleW, double changeIdleW,
                                 double scanW, double returnReserveUj) {
        if (!Double.isFinite(keep.totalUj) || !Double.isFinite(change.totalUj)) {
            return Double.NEGATIVE_INFINITY;
        }
        // 先结清同一批比特。再给先排空的一方计入到共同截止时刻的空闲能耗。
        double completion = Math.max(keep.tailNs, change.tailNs);
        double keepScanNs = sourceLcm ? window + keep.tailNs : 0;
        double changeScanNs = sourceLcm ? change.ccaWaitNs : window - change.newSwitchNs + change.tailNs;
        double before = keep.totalUj + scanW * keepScanNs / 1_000;
        double after = change.totalUj + scanW * Math.max(0, changeScanNs) / 1_000 + returnReserveUj;
        double equalEnd = before - after + ((completion - keep.tailNs) * (keepIdleW + (sourceLcm ? scanW : 0))
                - (completion - change.tailNs) * (changeIdleW + (sourceLcm ? 0 : scanW))) / 1_000;
        return Math.min(before - after, equalEnd);
    }

    private static double rate(long bytes, long durationNs) {
        return durationNs > 0 ? 8_000.0 * bytes / durationNs : 0;
    }

    public enum Capacity { DISABLED, TRANSIENT, INSUFFICIENT_INFORMATION, NO_SERVICE_GAIN, PRIORITY_RISK, PRIORITY_CCA, RECOVER }

    public static final class ObservationWindow {
        public final RefinementOptions options;
        public final boolean periodic;
        public final long backlogBytes, backlogDeltaBytes, oldestNs;
        public final int deficitWindows;
        public final double arrivalMbps, deliveredMbps, measuredServiceMbps, upperDemandMbps;
        public final boolean widePriorityRisk;

        ObservationWindow(RefinementOptions options, boolean periodic, long backlogBytes, long backlogDeltaBytes,
                          long oldestNs, int deficitWindows, double arrivalMbps, double deliveredMbps,
                          double measuredServiceMbps, double upperDemandMbps, boolean widePriorityRisk) {
            this.options = options;
            this.periodic = periodic;
            this.backlogBytes = backlogBytes;
            this.backlogDeltaBytes = backlogDeltaBytes;
            this.oldestNs = oldestNs;
            this.deficitWindows = deficitWindows;
            this.arrivalMbps = arrivalMbps;
            this.deliveredMbps = deliveredMbps;
            this.measuredServiceMbps = measuredServiceMbps;
            this.upperDemandMbps = upperDemandMbps;
            this.widePriorityRisk = widePriorityRisk;
        }
    }

    public static final class EconomicEvidence {
        static final EconomicEvidence DISABLED = new EconomicEvidence(0, 0, true, true);
        public final double expectedSavingUj, robustSavingUj;
        public final boolean headroom;
        private final boolean timingKnown;

        private EconomicEvidence(double expectedSavingUj, double robustSavingUj, boolean timingKnown, boolean headroom) {
            this.expectedSavingUj = expectedSavingUj;
            this.robustSavingUj = robustSavingUj;
            this.timingKnown = timingKnown;
            this.headroom = headroom;
        }

        boolean permits(double marginUj) {
            return this == DISABLED || timingKnown && headroom && robustSavingUj > marginUj;
        }
    }
}
