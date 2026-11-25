package edu.suda.fttr.entity;

import edu.suda.fttr.phy.ApCapabilityMode;

import java.util.Map;

/** 单台 AP 的在线策略。只接收截至决策时刻的本地累计下行到达量。 */
public final class ApModeController {
    public static final long DEFAULT_PERIOD_NS = 5_000_000L;
    public static final double DEFAULT_ALPHA = 0.25;
    public static final double DEFAULT_THRESHOLD_MBPS = 31.0;
    public static final double DEFAULT_ENERGY_MARGIN_UJ = 0.0;
    public static final long DEFAULT_QOS_MARGIN_NS = 50_000L;
    public static final long DEFAULT_DEFERRAL_LIMIT_NS = 2_000_000L;
    public static final long DEFAULT_MINIMUM_RESIDENCY_NS = 500_000L;
    public static final double DEFAULT_REVERSE_MARGIN_UJ = 25.0;

    private final Settings settings;
    private long previousNs;
    private long previousBytes;
    private double estimateMbps;

    public ApModeController(Settings settings) {
        this.settings = settings;
    }

    public Decision decide(long nowNs, long downlinkArrivalBytes) {
        long windowNs = nowNs - previousNs;
        if (windowNs != settings.periodNs() || downlinkArrivalBytes < previousBytes) {
            throw new IllegalArgumentException("AP mode control requires consecutive periods and cumulative arrivals");
        }
        long arrivedBytes = downlinkArrivalBytes - previousBytes;
        double rateMbps = ApTrafficObserver.megabitsPerSecond(arrivedBytes, windowNs);
        // 首窗口用实测到达率初始化；之后使用与 C1 相同的指数加权公式。
        // 保留恒定输入的精确值，避免浮点运算让等于阈值的估计意外越过阈值。
        if (previousNs == 0 || estimateMbps == rateMbps) {
            estimateMbps = rateMbps;
        } else {
            estimateMbps = ApTrafficObserver.smooth(estimateMbps, rateMbps, settings.alpha());
        }
        ApCapabilityMode target = estimateMbps > settings.thresholdMbps() ? ApCapabilityMode.HCM : ApCapabilityMode.LCM;
        Decision decision = new Decision(previousNs, nowNs, arrivedBytes, rateMbps, estimateMbps, target);
        previousNs = nowNs;
        previousBytes = downlinkArrivalBytes;
        return decision;
    }

    public enum Policy {
        OFF("off", null), ALWAYS_LCM("always-lcm", ApCapabilityMode.LCM),
        ALWAYS_HCM("always-hcm", ApCapabilityMode.HCM), FIXED_THRESHOLD("fixed-threshold", null),
        E_AWARE("e-aware", null), EC_AWARE("ec-aware", null),
        ECQ_AWARE("ecq-aware", null), ECQ_SDPS("ecq-sdps", null), ECQ_SDPS_D4("ecq-sdps-d4", null),
        ECQ_SDPS_COMPLETE("ecq-sdps-complete", null);

        private final String key;
        private final ApCapabilityMode staticMode;

        Policy(String key, ApCapabilityMode staticMode) {
            this.key = key;
            this.staticMode = staticMode;
        }

        public String key() { return key; }
        public ApCapabilityMode staticMode() { return staticMode; }
        public boolean usesQos() { return this == ECQ_AWARE || usesDeferral(); }
        public boolean usesDeferral() { return this == ECQ_SDPS || usesResidency(); }
        public boolean usesResidency() { return this == ECQ_SDPS_D4 || isComplete(); }
        public boolean isComplete() { return this == ECQ_SDPS_COMPLETE; }
        public boolean usesChannel() { return this == EC_AWARE || usesQos(); }
        public boolean usesPrediction() { return this == E_AWARE || usesChannel(); }
        public boolean dynamic() { return this == FIXED_THRESHOLD || usesPrediction(); }
    }

    public enum ServiceModel { AUTO, D2_ORIGINAL, LOAD_AWARE }

    /** 仅完整策略使用这些开关。消融时每次只关闭一项。 */
    public static final class CompleteFeatures {
        public static final CompleteFeatures ALL = new CompleteFeatures(true, true, true, true);
        public final boolean channel, qos, deferral, residency;

        public CompleteFeatures(boolean channel, boolean qos, boolean deferral, boolean residency) {
            this.channel = channel;
            this.qos = qos;
            this.deferral = deferral;
            this.residency = residency;
        }
    }

    public static final class Settings {
        private final Map<Integer, Policy> policies;
        private final long periodNs;
        private final double alpha;
        private final double thresholdMbps;
        private final double energyMarginUj;
        private final ServiceModel serviceModel;
        private final long qosMarginNs, deferralLimitNs, minimumResidencyNs;
        private final double reverseMarginUj;
        private final CompleteFeatures completeFeatures;

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps) {
            this(policies, periodNs, alpha, thresholdMbps, DEFAULT_ENERGY_MARGIN_UJ);
        }

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps,
                        double energyMarginUj) {
            this(policies, periodNs, alpha, thresholdMbps, energyMarginUj, ServiceModel.AUTO,
                    DEFAULT_QOS_MARGIN_NS, DEFAULT_DEFERRAL_LIMIT_NS, DEFAULT_MINIMUM_RESIDENCY_NS, DEFAULT_REVERSE_MARGIN_UJ);
        }

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps,
                        double energyMarginUj, ServiceModel serviceModel, long qosMarginNs, long deferralLimitNs,
                        long minimumResidencyNs, double reverseMarginUj) {
            this(policies, periodNs, alpha, thresholdMbps, energyMarginUj, serviceModel, qosMarginNs, deferralLimitNs,
                    minimumResidencyNs, reverseMarginUj, CompleteFeatures.ALL);
        }

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps,
                        double energyMarginUj, ServiceModel serviceModel, long qosMarginNs, long deferralLimitNs,
                        long minimumResidencyNs, double reverseMarginUj, CompleteFeatures completeFeatures) {
            if (periodNs <= 0 || !(alpha > 0 && alpha <= 1)
                    || !Double.isFinite(thresholdMbps) || thresholdMbps < 0
                    || !Double.isFinite(energyMarginUj) || energyMarginUj < 0 || qosMarginNs < 0 || deferralLimitNs < 0) {
                throw new IllegalArgumentException("Invalid AP mode control period, EWMA alpha, threshold or margin");
            }
            if (minimumResidencyNs < 0 || !Double.isFinite(reverseMarginUj) || reverseMarginUj < 0) {
                throw new IllegalArgumentException("Invalid AP residency minimum or reverse energy margin");
            }
            this.policies = Map.copyOf(policies);
            this.periodNs = periodNs;
            this.alpha = alpha;
            this.thresholdMbps = thresholdMbps;
            this.energyMarginUj = energyMarginUj;
            this.serviceModel = serviceModel;
            this.qosMarginNs = qosMarginNs;
            this.deferralLimitNs = deferralLimitNs;
            this.minimumResidencyNs = minimumResidencyNs;
            this.reverseMarginUj = reverseMarginUj;
            this.completeFeatures = completeFeatures;
        }

        public Map<Integer, Policy> policies() { return policies; }
        public long periodNs() { return periodNs; }
        public double alpha() { return alpha; }
        public double thresholdMbps() { return thresholdMbps; }
        public double energyMarginUj() { return energyMarginUj; }
        public long qosMarginNs() { return qosMarginNs; }
        public long deferralLimitNs() { return deferralLimitNs; }
        public long minimumResidencyNs() { return minimumResidencyNs; }
        public double reverseMarginUj() { return reverseMarginUj; }
        public CompleteFeatures completeFeatures() { return completeFeatures; }

        public boolean useLoadAwareService(Policy policy) {
            return serviceModel == ServiceModel.LOAD_AWARE || (serviceModel == ServiceModel.AUTO && policy.usesQos());
        }

        public Policy policyFor(int sfuId) {
            return policies.getOrDefault(sfuId, Policy.OFF);
        }

        public boolean hasDynamicPolicy() {
            return policies.values().stream().anyMatch(Policy::dynamic);
        }

        public boolean needsPrediction() {
            return policies.values().stream().anyMatch(Policy::usesPrediction);
        }
    }

    public static final class Decision {
        private final long windowStartNs;
        private final long timeNs;
        private final long arrivedBytes;
        private final double arrivalMbps;
        private final double estimateMbps;
        private final ApCapabilityMode target;

        private Decision(long windowStartNs, long timeNs, long arrivedBytes, double arrivalMbps,
                         double estimateMbps, ApCapabilityMode target) {
            this.windowStartNs = windowStartNs;
            this.timeNs = timeNs;
            this.arrivedBytes = arrivedBytes;
            this.arrivalMbps = arrivalMbps;
            this.estimateMbps = estimateMbps;
            this.target = target;
        }

        public long windowStartNs() { return windowStartNs; }
        public long timeNs() { return timeNs; }
        public long arrivedBytes() { return arrivedBytes; }
        public double arrivalMbps() { return arrivalMbps; }
        public double estimateMbps() { return estimateMbps; }
        public ApCapabilityMode target() { return target; }
    }
}
