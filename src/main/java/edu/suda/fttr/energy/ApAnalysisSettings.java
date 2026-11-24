package edu.suda.fttr.energy;

import edu.suda.fttr.entity.AccessCategory;

import java.util.Map;

/** C3 与 C4 的只读分析配置。分析可以独立关闭。 */
public final class ApAnalysisSettings {
    private final boolean predictionEnabled;
    private final long horizonNs;
    private final double alpha;
    private final Map<AccessCategory, Requirement> requirements;

    public ApAnalysisSettings(boolean predictionEnabled, long horizonNs, double alpha,
                              Map<AccessCategory, Requirement> requirements) {
        if (horizonNs <= 0 || !(alpha > 0 && alpha <= 1)) {
            throw new IllegalArgumentException("AP prediction requires a positive window and 0 < alpha <= 1");
        }
        this.predictionEnabled = predictionEnabled;
        this.horizonNs = horizonNs;
        this.alpha = alpha;
        this.requirements = Map.copyOf(requirements);
        if (this.requirements.size() != AccessCategory.values().length) {
            throw new IllegalArgumentException("QoS requirements must specify every AC");
        }
    }

    public boolean predictionEnabled() { return predictionEnabled; }
    public long horizonNs() { return horizonNs; }
    public double alpha() { return alpha; }
    public Requirement requirement(AccessCategory category) { return requirements.get(category); }

    public static final class Requirement {
        private final long deadlineNs;
        private final double minOnTime;

        public Requirement(long deadlineNs, double minOnTime) {
            if (deadlineNs <= 0 || !(minOnTime >= 0 && minOnTime <= 1)) {
                throw new IllegalArgumentException("AC QoS requires a positive deadline and min-on-time in [0, 1]");
            }
            this.deadlineNs = deadlineNs;
            this.minOnTime = minOnTime;
        }

        public long deadlineNs() { return deadlineNs; }
        public double minOnTime() { return minOnTime; }
    }
}
