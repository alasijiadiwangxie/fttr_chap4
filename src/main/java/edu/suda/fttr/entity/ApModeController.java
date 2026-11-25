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
        ECQ_AWARE("ecq-aware", null), ECQ_SDPS("ecq-sdps", null);

        private final String key;
        private final ApCapabilityMode staticMode;

        Policy(String key, ApCapabilityMode staticMode) {
            this.key = key;
            this.staticMode = staticMode;
        }

        public String key() { return key; }
        public ApCapabilityMode staticMode() { return staticMode; }
        public boolean usesQos() { return this == ECQ_AWARE || this == ECQ_SDPS; }
        public boolean usesChannel() { return this == EC_AWARE || usesQos(); }
        public boolean usesPrediction() { return this == E_AWARE || usesChannel(); }
        public boolean dynamic() { return this == FIXED_THRESHOLD || usesPrediction(); }
    }

    public enum ServiceModel { AUTO, D2_ORIGINAL, LOAD_AWARE }

    public static final class Settings {
        private final Map<Integer, Policy> policies;
        private final long periodNs;
        private final double alpha;
        private final double thresholdMbps;
        private final double energyMarginUj;
        private final ServiceModel serviceModel;
        private final long qosMarginNs, deferralLimitNs;

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps) {
            this(policies, periodNs, alpha, thresholdMbps, DEFAULT_ENERGY_MARGIN_UJ);
        }

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps,
                        double energyMarginUj) {
            this(policies, periodNs, alpha, thresholdMbps, energyMarginUj, ServiceModel.AUTO,
                    DEFAULT_QOS_MARGIN_NS, DEFAULT_DEFERRAL_LIMIT_NS);
        }

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps,
                        double energyMarginUj, ServiceModel serviceModel, long qosMarginNs, long deferralLimitNs) {
            if (periodNs <= 0 || !(alpha > 0 && alpha <= 1)
                    || !Double.isFinite(thresholdMbps) || thresholdMbps < 0
                    || !Double.isFinite(energyMarginUj) || energyMarginUj < 0 || qosMarginNs < 0 || deferralLimitNs < 0) {
                throw new IllegalArgumentException("Invalid AP mode control period, EWMA alpha, threshold or margin");
            }
            this.policies = Map.copyOf(policies);
            this.periodNs = periodNs;
            this.alpha = alpha;
            this.thresholdMbps = thresholdMbps;
            this.energyMarginUj = energyMarginUj;
            this.serviceModel = serviceModel;
            this.qosMarginNs = qosMarginNs;
            this.deferralLimitNs = deferralLimitNs;
        }

        public Map<Integer, Policy> policies() { return policies; }
        public long periodNs() { return periodNs; }
        public double alpha() { return alpha; }
        public double thresholdMbps() { return thresholdMbps; }
        public double energyMarginUj() { return energyMarginUj; }
        public long qosMarginNs() { return qosMarginNs; }
        public long deferralLimitNs() { return deferralLimitNs; }

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
