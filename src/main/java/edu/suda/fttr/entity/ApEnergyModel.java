package edu.suda.fttr.entity;

import edu.suda.fttr.phy.ApCapabilityMode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/** 等效 AP 设备级功耗参数与观察窗口能耗积分。 */
public final class ApEnergyModel {
    public static final String PROFILE = "RESEARCH_INITIAL_V1";
    // IDLE 以文献的其他 AP 实测值为参考，TX 和 RX 为研究假设。
    public static final Power LCM_DEFAULT = new Power(
            new BigDecimal("7.38"), new BigDecimal("9.20"), new BigDecimal("8.20"));
    public static final Power HCM_DEFAULT = new Power(
            new BigDecimal("7.62"), new BigDecimal("10.50"), new BigDecimal("8.70"));

    private final Power lcm;
    private final Power hcm;

    public ApEnergyModel(Power lcm, Power hcm) {
        this.lcm = Objects.requireNonNull(lcm);
        this.hcm = Objects.requireNonNull(hcm);
    }

    /** 仅接收 B1 显式配置的能力模式。 */
    public Power powerFor(ApCapabilityMode mode) {
        switch (mode) {
            case LCM:
                return lcm;
            case HCM:
                return hcm;
            default:
                throw new IllegalArgumentException("Unsupported AP capability mode: " + mode);
        }
    }

    /** 分别按驻留模式和切换方向积分，切换时长不进入任何常规状态。 */
    public Energy integrate(ApActivity.Totals activity, BigDecimal lcmToHcmW, BigDecimal hcmToLcmW) {
        Energy regular = lcm.integrate(activity.forMode(ApCapabilityMode.LCM))
                .plus(hcm.integrate(activity.forMode(ApCapabilityMode.HCM)));
        BigDecimal switchEnergy = switchEnergyUj(activity.switchNsFrom(ApCapabilityMode.LCM), lcmToHcmW)
                .add(switchEnergyUj(activity.switchNsFrom(ApCapabilityMode.HCM), hcmToLcmW));
        return new Energy(regular.idleUj(), regular.txUj(), regular.rxUj(), switchEnergy);
    }

    /** 观察窗口内没有切换时，能耗为零，方向功率可以缺省。 */
    public static BigDecimal switchEnergyUj(long observedNs, BigDecimal powerW) {
        if (observedNs == 0) {
            return BigDecimal.ZERO;
        }
        if (observedNs < 0 || !Power.isValidWatts(powerW)) {
            throw new IllegalArgumentException("AP switch energy requires nonnegative time and positive finite watts");
        }
        return stateEnergyUj(observedNs, powerW);
    }

    private static BigDecimal stateEnergyUj(long durationNs, BigDecimal powerW) {
        return powerW.multiply(BigDecimal.valueOf(durationNs, 3));
    }

    public static final class Power {
        private final BigDecimal idleW;
        private final BigDecimal txW;
        private final BigDecimal rxW;

        public Power(BigDecimal idleW, BigDecimal txW, BigDecimal rxW) {
            if (!isValidWatts(idleW) || !isValidWatts(txW) || !isValidWatts(rxW)) {
                throw new IllegalArgumentException("AP activity powers must be positive finite values in watts");
            }
            this.idleW = idleW;
            this.txW = txW;
            this.rxW = rxW;
        }

        /** 以可表示为正有限 double 的范围做校验，积分始终使用原十进制数。 */
        public static boolean isValidWatts(BigDecimal watts) {
            if (watts == null || watts.signum() <= 0) {
                return false;
            }
            double projected = watts.doubleValue();
            return Double.isFinite(projected) && projected > 0.0;
        }

        public BigDecimal idleW() { return idleW; }
        public BigDecimal txW() { return txW; }
        public BigDecimal rxW() { return rxW; }

        /** 兼容静态能力模式的 B3 汇总。 */
        public Energy integrate(ApActivity.Totals activity) {
            return integrate(activity.idleNs(), activity.txNs(), activity.rxNs());
        }

        public Energy integrate(ApActivity.Durations activity) {
            return integrate(activity.idleNs(), activity.txNs(), activity.rxNs());
        }

        private Energy integrate(long idleNs, long txNs, long rxNs) {
            // W × μs = μJ。缩放纳秒为微秒时保留全部小数，不在积分前舍入。
            return new Energy(stateEnergyUj(idleNs, idleW), stateEnergyUj(txNs, txW),
                    stateEnergyUj(rxNs, rxW), BigDecimal.ZERO);
        }
    }

    public static final class Energy {
        public static final Energy ZERO = new Energy(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        private final BigDecimal idleUj;
        private final BigDecimal txUj;
        private final BigDecimal rxUj;
        private final BigDecimal switchUj;

        private Energy(BigDecimal idleUj, BigDecimal txUj, BigDecimal rxUj, BigDecimal switchUj) {
            this.idleUj = idleUj;
            this.txUj = txUj;
            this.rxUj = rxUj;
            this.switchUj = switchUj;
        }

        public BigDecimal idleUj() { return idleUj; }
        public BigDecimal txUj() { return txUj; }
        public BigDecimal rxUj() { return rxUj; }
        public BigDecimal switchUj() { return switchUj; }

        public BigDecimal totalUj() {
            return idleUj.add(txUj).add(rxUj).add(switchUj);
        }

        public Energy plus(Energy other) {
            return new Energy(idleUj.add(other.idleUj), txUj.add(other.txUj), rxUj.add(other.rxUj),
                    switchUj.add(other.switchUj));
        }

        /** 仅平均功率除法可能需要舍入。零时长窗口按 0 W 输出。 */
        public BigDecimal averagePowerW(long observationNs) {
            if (observationNs == 0) {
                return BigDecimal.ZERO;
            }
            return totalUj().divide(BigDecimal.valueOf(observationNs, 3), MathContext.DECIMAL128);
        }
    }
}
