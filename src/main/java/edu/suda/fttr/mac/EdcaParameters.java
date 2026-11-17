package edu.suda.fttr.mac;

import edu.suda.fttr.entity.AccessCategory;

import java.util.Map;

/** 场景共用的上下行 EDCA 参数。这里只计算时长，发送流程仍由现有队列调度。 */
public final class EdcaParameters {
    private final long sifsUs;
    private final long slotTimeUs;
    private final Map<AccessCategory, AcParameters> byCategory;

    public EdcaParameters(long sifsUs, long slotTimeUs, Map<AccessCategory, AcParameters> byCategory) {
        if (sifsUs <= 0 || slotTimeUs <= 0) {
            throw new IllegalArgumentException("wifi.mac.sifs.us and wifi.mac.slot.us must be positive");
        }
        this.sifsUs = sifsUs;
        this.slotTimeUs = slotTimeUs;
        this.byCategory = Map.copyOf(byCategory);
        for (AccessCategory category : AccessCategory.values()) {
            if (!this.byCategory.containsKey(category)) {
                throw new IllegalArgumentException("Missing EDCA parameters for " + category);
            }
            try {
                // 配置使用整数微秒，HE SU 事件时钟使用纳秒。提前检查可表示范围。
                Math.multiplyExact(aifsUs(category), 1_000L);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("AIFS for " + category + " exceeds the nanosecond clock range", e);
            }
        }
    }

    public long sifsUs() {
        return sifsUs;
    }

    public long slotTimeUs() {
        return slotTimeUs;
    }

    public AcParameters forCategory(AccessCategory category) {
        return byCategory.get(category);
    }

    public long aifsUs(AccessCategory category) {
        return Math.addExact(sifsUs, Math.multiplyExact(forCategory(category).aifsn(), slotTimeUs));
    }

    public static final class AcParameters {
        private final int aifsn;
        private final int cwMin;
        private final int cwMax;

        public AcParameters(int aifsn, int cwMin, int cwMax) {
            // 参数同时适用于普通 STA 和 AP。仅 AP 可用的 AIFSN=1 不用于这份共用配置。
            if (aifsn < 2 || aifsn > 15) {
                throw new IllegalArgumentException("AIFSN must be between 2 and 15");
            }
            validateWindow(cwMin, "CWmin");
            validateWindow(cwMax, "CWmax");
            if (cwMin > cwMax) {
                throw new IllegalArgumentException("CWmin must not exceed CWmax");
            }
            this.aifsn = aifsn;
            this.cwMin = cwMin;
            this.cwMax = cwMax;
        }

        private static void validateWindow(int window, String name) {
            // 配置存储实际 CW 值。标准参数字段用 4 位指数 ECW 编码 CW = 2^ECW − 1。
            if (window < 0 || window > 32_767 || Integer.bitCount(window + 1) != 1) {
                throw new IllegalArgumentException(name + " must be 2^n - 1 with n in 0..15");
            }
        }

        public int aifsn() {
            return aifsn;
        }

        public int cwMin() {
            return cwMin;
        }

        public int cwMax() {
            return cwMax;
        }
    }
}
