package edu.suda.fttr.dba;

import java.util.List;
import java.util.Objects;

/**
 * 不可变的 DBA 建议计划。它既不是已下发的 BWmap，也不代表任何报文已经预约或发送。
 * 数值及拓扑合法性集中由 DbaPlanValidator 检查，禁止在这里静默修正非法建议。
 */
public final class DbaPlan {
    private final long decisionTimeNs;
    private final long targetFrameId;
    private final List<Grant> grants;
    private final String decisionReason;

    public DbaPlan(long decisionTimeNs, long targetFrameId, List<Grant> grants, String decisionReason) {
        this.decisionTimeNs = decisionTimeNs;
        this.targetFrameId = targetFrameId;
        this.grants = List.copyOf(Objects.requireNonNull(grants));
        this.decisionReason = Objects.requireNonNull(decisionReason);
    }

    public long decisionTimeNs() {
        return decisionTimeNs;
    }

    public long targetFrameId() {
        return targetFrameId;
    }

    /** 各策略按目标帧生成一个或多个对象的预算，允许空计划。 */
    public List<Grant> grants() {
        return grants;
    }

    public String decisionReason() {
        return decisionReason;
    }

    /** 一个待验证预算；字节预算并不承诺能够容纳任何完整报文。 */
    public static final class Grant {
        private final long allocationId;
        private final int sfuId;
        private final long plannedBytes;
        private final long plannedDurationNs;
        private final Long startOffsetNs;
        private final long fixedBytes;

        /** 未指定偏移时，由校验预览器在控制预留之后按集合顺序连续排放。 */
        public Grant(long allocationId, int sfuId, long plannedBytes, long plannedDurationNs) {
            this(allocationId, sfuId, plannedBytes, plannedDurationNs, null);
        }

        /**
         * 可选偏移相对于目标帧起点，用于表达显式计划并检测重叠；仍不创建真实发送事件。
         * 保留非法原值交由统一校验器拒绝，避免各个策略采用不一致的修正规则。
         */
        public Grant(long allocationId, int sfuId, long plannedBytes, long plannedDurationNs, Long startOffsetNs) {
            this(allocationId, sfuId, plannedBytes, plannedDurationNs, startOffsetNs, 0);
        }

        /** 固定成分可以独立于 REPORT 发放，其余字节仍受 MFU 已知剩余需求约束。 */
        public Grant(long allocationId, int sfuId, long plannedBytes, long plannedDurationNs, Long startOffsetNs,
                     long fixedBytes) {
            this.allocationId = allocationId;
            this.sfuId = sfuId;
            this.plannedBytes = plannedBytes;
            this.plannedDurationNs = plannedDurationNs;
            this.startOffsetNs = startOffsetNs;
            this.fixedBytes = fixedBytes;
        }

        public long allocationId() {
            return allocationId;
        }

        public int sfuId() {
            return sfuId;
        }

        public long plannedBytes() {
            return plannedBytes;
        }

        public long plannedDurationNs() {
            return plannedDurationNs;
        }

        public Long startOffsetNs() {
            return startOffsetNs;
        }

        public long fixedBytes() {
            return fixedBytes;
        }
    }
}
