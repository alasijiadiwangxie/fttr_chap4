package edu.suda.fttr.mac;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.mac.EdcaParameters.AcParameters;

import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SplittableRandom;

/** 一台无线设备的四类 AC 竞争状态。状态由未来的竞争流程推进。 */
public final class EdcaState {
    private final Map<AccessCategory, AcState> byCategory = new EnumMap<>(AccessCategory.class);

    private EdcaState(long deviceKey, EdcaParameters parameters) {
        AccessCategory[] categories = AccessCategory.values();
        for (AccessCategory category : categories) {
            long streamKey = deviceKey * categories.length + category.ordinal();
            // 按设备类型、ID 和 AC 派生种子，序列与其他设备或 AC 的创建和抽取顺序无关。
            long seed = parameters.seed() ^ streamKey;
            byCategory.put(category, new AcState(parameters.forCategory(category), seed));
        }
    }

    public static EdcaState forSfu(int id, EdcaParameters parameters) {
        return new EdcaState(2L * id, parameters);
    }

    public static EdcaState forSta(int id, EdcaParameters parameters) {
        return new EdcaState(2L * id + 1, parameters);
    }

    public AcState forCategory(AccessCategory category) {
        return byCategory.get(category);
    }

    public static final class AcState {
        private static final int NO_BACKOFF = -1;
        private final AcParameters parameters;
        private final SplittableRandom random;
        private int currentCw;
        private int backoffCounter = NO_BACKOFF;

        private AcState(AcParameters parameters, long seed) {
            this.parameters = parameters;
            // 为每个 AC 派生自己的随机流，拥有独立的内部状态和步进参数。
            random = new SplittableRandom(seed).split();
            currentCw = parameters.cwMin();
        }

        public int currentCw() {
            return currentCw;
        }

        /** 空值表示还未为本轮竞争抽取计数器。计数器 0 是有效结果。 */
        public OptionalInt backoffCounter() {
            return backoffCounter == NO_BACKOFF ? OptionalInt.empty() : OptionalInt.of(backoffCounter);
        }

        /** 从当前 CW 的闭区间 [0, CW] 均匀抽取并保存整数退避值。 */
        public int drawBackoff() {
            backoffCounter = random.nextInt(currentCw + 1);
            return backoffCounter;
        }

        public void onContentionFailure() {
            currentCw = Math.min(2 * currentCw + 1, parameters.cwMax());
            backoffCounter = NO_BACKOFF;
        }

        public void onTransmissionSuccess() {
            currentCw = parameters.cwMin();
            backoffCounter = NO_BACKOFF;
        }
    }
}
