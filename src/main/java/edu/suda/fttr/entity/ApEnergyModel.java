package edu.suda.fttr.entity;

import edu.suda.fttr.phy.ApCapabilityMode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/** B3 的等效 AP 设备级功耗参数与观察窗口能耗积分。 */
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

        /** W × μs = μJ。缩放纳秒为微秒时保留全部小数，不在积分前舍入。 */
        public Energy integrate(ApActivity.Totals activity) {
            return new Energy(idleW.multiply(BigDecimal.valueOf(activity.idleNs(), 3)),
                    txW.multiply(BigDecimal.valueOf(activity.txNs(), 3)),
                    rxW.multiply(BigDecimal.valueOf(activity.rxNs(), 3)));
        }
    }

    public static final class Energy {
        public static final Energy ZERO = new Energy(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        private final BigDecimal idleUj;
        private final BigDecimal txUj;
        private final BigDecimal rxUj;

        private Energy(BigDecimal idleUj, BigDecimal txUj, BigDecimal rxUj) {
            this.idleUj = idleUj;
            this.txUj = txUj;
            this.rxUj = rxUj;
        }

        public BigDecimal idleUj() { return idleUj; }
        public BigDecimal txUj() { return txUj; }
        public BigDecimal rxUj() { return rxUj; }

        public BigDecimal totalUj() {
            return idleUj.add(txUj).add(rxUj);
        }

        public Energy plus(Energy other) {
            return new Energy(idleUj.add(other.idleUj), txUj.add(other.txUj), rxUj.add(other.rxUj));
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
