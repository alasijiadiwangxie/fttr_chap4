package edu.suda.fttr.dba;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一轮 DBA 决策的不可变输入快照。分配对象只含 MFU 收到的 REPORT–Grant 估计值，
 * 不保存 MFU、SFU、FIFO 或可被策略修改的需求账本引用。
 */
public final class DbaContext {
    private static final BigDecimal BITS_PER_BYTE_NS = BigDecimal.valueOf(8_000);

    private final long decisionTimeNs;
    private final long targetFrameId;
    private final BigDecimal rateMbps;
    private final long framePeriodNs;
    private final long controlReservedNs;
    private final List<Allocation> allocations;

    public DbaContext(long decisionTimeNs, long targetFrameId, BigDecimal rateMbps,
                      long framePeriodNs, long controlReservedNs, List<Allocation> allocations) {
        this.rateMbps = Objects.requireNonNull(rateMbps);
        if (decisionTimeNs < 0 || targetFrameId < 0 || rateMbps.signum() <= 0 || framePeriodNs <= 0
                || controlReservedNs < 0 || controlReservedNs > framePeriodNs) {
            throw new IllegalArgumentException("DBA context time, rate or frame reservation is outside its valid range");
        }
        this.allocations = List.copyOf(Objects.requireNonNull(allocations));
        Set<Long> allocationIds = new HashSet<>();
        for (Allocation allocation : this.allocations) {
            if (!allocationIds.add(allocation.allocationId())) {
                throw new IllegalArgumentException("Duplicate DBA allocation ID: " + allocation.allocationId());
            }
        }
        this.decisionTimeNs = decisionTimeNs;
        this.targetFrameId = targetFrameId;
        this.framePeriodNs = framePeriodNs;
        this.controlReservedNs = controlReservedNs;
    }

    public long decisionTimeNs() {
        return decisionTimeNs;
    }

    public long targetFrameId() {
        return targetFrameId;
    }

    public BigDecimal rateMbps() {
        return rateMbps;
    }

    public long framePeriodNs() {
        return framePeriodNs;
    }

    public long controlReservedNs() {
        return controlReservedNs;
    }

    /** 当前目标帧扣除 REPORT-only 控制时隙后的 DATA 可用时间。 */
    public long availableNs() {
        return framePeriodNs - controlReservedNs;
    }

    /**
     * floor(可用 ns × Mb/s / 8000)。使用精确十进制和 BigInteger，避免中间容量溢出，
     * 串行化的向上取整则在策略及计划校验阶段再次核验。
     */
    public BigInteger capacityBytes() {
        return capacityBytes(availableNs());
    }

    BigInteger capacityBytes(long availableNs) {
        return BigDecimal.valueOf(availableNs).multiply(rateMbps)
                .divide(BITS_PER_BYTE_NS, 0, RoundingMode.FLOOR).toBigIntegerExact();
    }

    public List<Allocation> allocations() {
        return allocations;
    }

    /**
     * 一个逻辑分配对象。allocationId 标识调度对象，sfuId 标识物理 SFU；两者语义独立，
     * 因此多个 allocationId 可以属于同一 SFU。profile 为 GIANT 提供 T-CONT 的服务参数。
     */
    public static final class Allocation {
        private final long allocationId;
        private final int sfuId;
        private final Long knownDemandBytes;
        private final Long reportFrameId;
        private final Long reportAgeNs;
        private final boolean budgetOutstanding;
        private final TcontProfile profile;
        private final long failedBudgetBytes;
        private final boolean recoveryWaiting;

        public Allocation(long allocationId, int sfuId, Long knownDemandBytes,
                          Long reportFrameId, Long reportAgeNs) {
            this(allocationId, sfuId, knownDemandBytes, reportFrameId, reportAgeNs, false);
        }

        /** 未结算预算是独立的授权状态，不改变 REPORT 的 UNKNOWN/KNOWN 或净需求数值。 */
        public Allocation(long allocationId, int sfuId, Long knownDemandBytes,
                          Long reportFrameId, Long reportAgeNs, boolean budgetOutstanding) {
            this(allocationId, sfuId, knownDemandBytes, reportFrameId, reportAgeNs, budgetOutstanding, null);
        }

        /** T-CONT 参数为不可变元数据；单对象兼容模式使用 null。 */
        public Allocation(long allocationId, int sfuId, Long knownDemandBytes,
                          Long reportFrameId, Long reportAgeNs, boolean budgetOutstanding, TcontProfile profile) {
            this(allocationId, sfuId, knownDemandBytes, reportFrameId, reportAgeNs, budgetOutstanding, profile, 0, false);
        }

        /** 恢复只使用已经结算的零装载记录。失败额度是下界，调度端不读取远端队首大小。 */
        public Allocation(long allocationId, int sfuId, Long knownDemandBytes, Long reportFrameId, Long reportAgeNs,
                          boolean budgetOutstanding, TcontProfile profile, long failedBudgetBytes, boolean recoveryWaiting) {
            if (allocationId < 1 || sfuId < 1 || (knownDemandBytes != null && knownDemandBytes < 0)
                    || (reportFrameId != null && reportFrameId < 0) || (reportAgeNs != null && reportAgeNs < 0)
                    || failedBudgetBytes < 0 || (recoveryWaiting && (failedBudgetBytes == 0 || !budgetOutstanding))) {
                throw new IllegalArgumentException("DBA allocation identity or known demand is outside its valid range");
            }
            // UNKNOWN 不能附带旧报告元数据，否则策略可能错误地把它当成已知零需求。
            if (knownDemandBytes == null && (reportFrameId != null || reportAgeNs != null)) {
                throw new IllegalArgumentException("Unknown DBA demand must not contain REPORT metadata");
            }
            if (profile != null && (profile.allocationId() != allocationId || profile.sfuId() != sfuId)) {
                throw new IllegalArgumentException("T-CONT profile identity does not match DBA allocation");
            }
            this.allocationId = allocationId;
            this.sfuId = sfuId;
            this.knownDemandBytes = knownDemandBytes;
            this.reportFrameId = reportFrameId;
            this.reportAgeNs = reportAgeNs;
            this.budgetOutstanding = budgetOutstanding;
            this.profile = profile;
            this.failedBudgetBytes = failedBudgetBytes;
            this.recoveryWaiting = recoveryWaiting;
        }

        public long allocationId() {
            return allocationId;
        }

        public int sfuId() {
            return sfuId;
        }

        /** null 表示 UNKNOWN；Long.valueOf(0) 表示 MFU 已知需求为零。 */
        public Long knownDemandBytes() {
            return knownDemandBytes;
        }

        public boolean known() {
            return knownDemandBytes != null;
        }

        public Long reportFrameId() {
            return reportFrameId;
        }

        public Long reportAgeNs() {
            return reportAgeNs;
        }

        public boolean budgetOutstanding() {
            return budgetOutstanding;
        }

        public TcontProfile profile() {
            return profile;
        }

        public long failedBudgetBytes() { return failedBudgetBytes; }

        public boolean recoveryWaiting() { return recoveryWaiting; }
    }
}
