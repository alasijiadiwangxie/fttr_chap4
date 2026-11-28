package edu.suda.fttr.entity;

import edu.suda.fttr.phy.ApCapabilityMode;

import java.util.Map;

/** 单台 AP 的在线策略。只接收截至决策时刻的本地累计下行到达量。 */
public final class ApModeController {
    public static final long DEFAULT_PERIOD_NS = 5_000_000L;
    public static final double DEFAULT_ALPHA = 0.25;
    public static final double DEFAULT_THRESHOLD_MBPS = 31.0;
    public static final ThresholdBand DEFAULT_HYSTERESIS_BAND = new ThresholdBand(21.0, 33.0);

    private final Settings settings;
    private long previousNs;
    private long previousBytes;
    private double estimateMbps;
    private ApCapabilityMode hysteresisTarget;

    public ApModeController(Settings settings) {
        this(settings, null);
    }

    /** 双阈值基线从实际初始档位开始，区间内继续保留已请求的目标档位。 */
    public ApModeController(Settings settings, ApCapabilityMode initialHysteresisTarget) {
        this.settings = settings;
        hysteresisTarget = initialHysteresisTarget;
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
        Policy policy = Policy.FIXED_THRESHOLD;
        if (hysteresisTarget != null) {
            policy = Policy.HYSTERESIS;
            hysteresisTarget = settings.thresholdBand.choose(hysteresisTarget, estimateMbps);
            target = hysteresisTarget;
        }
        Decision decision = new Decision(previousNs, nowNs, arrivedBytes, rateMbps, estimateMbps, target, policy);
        previousNs = nowNs;
        previousBytes = downlinkArrivalBytes;
        return decision;
    }

    public enum Policy {
        OFF("off", null), ALWAYS_LCM("always-lcm", ApCapabilityMode.LCM),
        ALWAYS_HCM("always-hcm", ApCapabilityMode.HCM), FIXED_THRESHOLD("fixed-threshold", null),
        HYSTERESIS("hysteresis", null);

        private final String key;
        private final ApCapabilityMode staticMode;

        Policy(String key, ApCapabilityMode staticMode) {
            this.key = key;
            this.staticMode = staticMode;
        }

        public String key() { return key; }
        public ApCapabilityMode staticMode() { return staticMode; }
        public boolean usesLoadThreshold() { return this == FIXED_THRESHOLD || this == HYSTERESIS; }
    }

    public static final class ThresholdBand {
        private final double lowerMbps;
        private final double upperMbps;

        public ThresholdBand(double lowerMbps, double upperMbps) {
            if (!Double.isFinite(lowerMbps) || !Double.isFinite(upperMbps) || lowerMbps < 0 || lowerMbps >= upperMbps) {
                throw new IllegalArgumentException("Hysteresis requires 0 <= lower < upper in Mb/s");
            }
            this.lowerMbps = lowerMbps;
            this.upperMbps = upperMbps;
        }

        private ApCapabilityMode choose(ApCapabilityMode currentTarget, double estimateMbps) {
            if (estimateMbps > upperMbps) {
                return ApCapabilityMode.HCM;
            }
            if (estimateMbps < lowerMbps) {
                return ApCapabilityMode.LCM;
            }
            return currentTarget;
        }
    }

    public static final class Settings {
        private final Map<Integer, Policy> policies;
        private final long periodNs;
        private final double alpha;
        private final double thresholdMbps;
        private final ThresholdBand thresholdBand;

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps) {
            this(policies, periodNs, alpha, thresholdMbps, DEFAULT_HYSTERESIS_BAND);
        }

        public Settings(Map<Integer, Policy> policies, long periodNs, double alpha, double thresholdMbps,
                        ThresholdBand thresholdBand) {
            if (periodNs <= 0 || !(alpha > 0 && alpha <= 1)
                    || !Double.isFinite(thresholdMbps) || thresholdMbps < 0) {
                throw new IllegalArgumentException("Invalid AP mode control period, EWMA alpha or load threshold");
            }
            this.policies = Map.copyOf(policies);
            this.periodNs = periodNs;
            this.alpha = alpha;
            this.thresholdMbps = thresholdMbps;
            this.thresholdBand = java.util.Objects.requireNonNull(thresholdBand, "thresholdBand");
        }

        public Map<Integer, Policy> policies() { return policies; }
        public long periodNs() { return periodNs; }
        public double alpha() { return alpha; }
        public double thresholdMbps() { return thresholdMbps; }

        public Policy policyFor(int sfuId) {
            return policies.getOrDefault(sfuId, Policy.OFF);
        }

        public boolean hasLoadThreshold() {
            return policies.values().stream().anyMatch(Policy::usesLoadThreshold);
        }
    }

    public static final class Decision {
        private final long windowStartNs;
        private final long timeNs;
        private final long arrivedBytes;
        private final double arrivalMbps;
        private final double estimateMbps;
        private final ApCapabilityMode target;
        private final Policy policy;

        private Decision(long windowStartNs, long timeNs, long arrivedBytes, double arrivalMbps,
                         double estimateMbps, ApCapabilityMode target, Policy policy) {
            this.windowStartNs = windowStartNs;
            this.timeNs = timeNs;
            this.arrivedBytes = arrivedBytes;
            this.arrivalMbps = arrivalMbps;
            this.estimateMbps = estimateMbps;
            this.target = target;
            this.policy = policy;
        }

        public long windowStartNs() { return windowStartNs; }
        public long timeNs() { return timeNs; }
        public long arrivedBytes() { return arrivedBytes; }
        public double arrivalMbps() { return arrivalMbps; }
        public double estimateMbps() { return estimateMbps; }
        public ApCapabilityMode target() { return target; }
        public Policy policy() { return policy; }
    }
}
