package edu.suda.fttr.entity;

import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.DbaPolicy;
import edu.suda.fttr.dba.RoundRobinDbaPolicy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * MFU 与统一 DBA 策略之间的只读适配层。
 * 将已知需求复制为上下文，再调用策略、校验与预览；保留第 9 步的 Decision 日志接口。
 */
public final class PonDbaShadowPlanner {
    private final List<Integer> sfuIds;
    private final BigDecimal rateMbps;
    private final long framePeriodNs;
    private final DbaPolicy policy;
    private final DbaPlanValidator validator = new DbaPlanValidator();

    public PonDbaShadowPlanner(List<Integer> sfuIds, BigDecimal rateMbps, long framePeriodNs) {
        this(sfuIds, rateMbps, framePeriodNs, new RoundRobinDbaPolicy());
    }

    /** 策略可以显式注入，现有构造及 shadow-rr 默认仍使用 RR，不需要反射或注册框架。 */
    public PonDbaShadowPlanner(List<Integer> sfuIds, BigDecimal rateMbps, long framePeriodNs, DbaPolicy policy) {
        Objects.requireNonNull(sfuIds);
        this.policy = Objects.requireNonNull(policy);
        this.rateMbps = Objects.requireNonNull(rateMbps);
        if (rateMbps.signum() <= 0 || framePeriodNs <= 0) {
            throw new IllegalArgumentException("Shadow planner rate and frame period must be positive");
        }
        if (sfuIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("Shadow planner SFU IDs must be positive");
        }
        this.sfuIds = List.copyOf(new TreeSet<>(sfuIds));
        this.framePeriodNs = framePeriodNs;
    }

    public Decision plan(long decisionTimeNs, long targetFrameId, long controlReservedNs,
                         IntFunction<PonDemandLedger.Estimate> estimates) {
        if (decisionTimeNs < 0 || targetFrameId < 0 || controlReservedNs < 0 || controlReservedNs > framePeriodNs) {
            throw new IllegalArgumentException("Shadow planner time or frame reservation is outside its valid range");
        }
        Objects.requireNonNull(estimates);
        List<PonDemandLedger.Estimate> visible = new ArrayList<>(sfuIds.size());
        List<DbaContext.Allocation> allocations = new ArrayList<>(sfuIds.size());
        int unknown = 0;
        int knownZero = 0;
        for (int sfuId : sfuIds) {
            PonDemandLedger.Estimate estimate = Objects.requireNonNull(estimates.apply(sfuId));
            visible.add(estimate);
            if (!estimate.known()) {
                unknown++;
                allocations.add(new DbaContext.Allocation(allocationIdFor(sfuId), sfuId, null, null, null));
            } else if (estimate.effectiveKnownBytes() == 0) {
                knownZero++;
            }
            if (estimate.known()) {
                allocations.add(new DbaContext.Allocation(allocationIdFor(sfuId), sfuId,
                        estimate.effectiveKnownBytes(), estimate.report().report().frameId(), estimate.reportAgeNs()));
            }
        }
        DbaContext context = new DbaContext(decisionTimeNs, targetFrameId, rateMbps, framePeriodNs,
                controlReservedNs, allocations);
        DbaPlan plan = policy.plan(context);
        List<DbaPlanValidator.Slot> preview = validator.validateAndPreview(context, plan);
        // 当前 RR 每轮至多一个对象。旧标量字段继续只描述该对象，新模型本身使用集合。
        DbaPlan.Grant grant = plan.grants().isEmpty() ? null : plan.grants().get(0);
        PonDemandLedger.Estimate selected = grant == null ? null : visible.get(sfuIds.indexOf(grant.sfuId()));
        return new Decision(decisionTimeNs, targetFrameId, grant == null ? 0 : grant.sfuId(), selected,
                controlReservedNs, context.availableNs(), context.capacityBytes(),
                grant == null ? 0 : grant.plannedBytes(), grant == null ? 0 : grant.plannedDurationNs(),
                plan.decisionReason(), unknown, knownZero, grant == null ? 0 : grant.allocationId(), preview);
    }

    /**
     * 当前每台 SFU 一个逻辑对象。偶数 ID 仅为稳定、独立的本地命名约定，非真实 Alloc-ID 编码；
     * 将来多个对象的映射应由拓扑提供，策略和校验器都无需依赖此命名公式。
     */
    private static long allocationIdFor(int sfuId) {
        return 2L * sfuId;
    }

    /** 预算仅为按字节估计的建议，未表示任何完整报文已经获得真实时隙。 */
    public static final class Decision {
        private final long decisionTimeNs;
        private final long targetFrameId;
        private final int selectedSfuId;
        private final PonDemandLedger.Estimate estimate;
        private final long controlReservedNs;
        private final long availableDataNs;
        private final BigInteger dataCapacityBytes;
        private final long plannedGrantBytes;
        private final long plannedGrantNs;
        private final String decisionReason;
        private final int unknownSfus;
        private final int knownZeroSfus;
        private final long allocationId;
        private final List<DbaPlanValidator.Slot> preview;

        private Decision(long decisionTimeNs, long targetFrameId, int selectedSfuId,
                         PonDemandLedger.Estimate estimate, long controlReservedNs, long availableDataNs,
                         BigInteger dataCapacityBytes, long plannedGrantBytes, long plannedGrantNs,
                         String decisionReason, int unknownSfus, int knownZeroSfus,
                         long allocationId, List<DbaPlanValidator.Slot> preview) {
            this.decisionTimeNs = decisionTimeNs;
            this.targetFrameId = targetFrameId;
            this.selectedSfuId = selectedSfuId;
            this.estimate = estimate;
            this.controlReservedNs = controlReservedNs;
            this.availableDataNs = availableDataNs;
            this.dataCapacityBytes = dataCapacityBytes;
            this.plannedGrantBytes = plannedGrantBytes;
            this.plannedGrantNs = plannedGrantNs;
            this.decisionReason = decisionReason;
            this.unknownSfus = unknownSfus;
            this.knownZeroSfus = knownZeroSfus;
            this.allocationId = allocationId;
            this.preview = List.copyOf(preview);
        }

        public long decisionTimeNs() {
            return decisionTimeNs;
        }

        public long targetFrameId() {
            return targetFrameId;
        }

        public int selectedSfuId() {
            return selectedSfuId;
        }

        public PonDemandLedger.Estimate estimate() {
            return estimate;
        }

        public Long reportFrameId() {
            return estimate == null ? null : estimate.report().report().frameId();
        }

        public Long reportAgeNs() {
            return estimate == null ? null : estimate.reportAgeNs();
        }

        public long effectiveKnownBytes() {
            return estimate == null ? 0 : estimate.effectiveKnownBytes();
        }

        public long controlReservedNs() {
            return controlReservedNs;
        }

        public long availableDataNs() {
            return availableDataNs;
        }

        public BigInteger dataCapacityBytes() {
            return dataCapacityBytes;
        }

        public long plannedGrantBytes() {
            return plannedGrantBytes;
        }

        public long plannedGrantNs() {
            return plannedGrantNs;
        }

        public String decisionReason() {
            return decisionReason;
        }

        public int unknownSfus() {
            return unknownSfus;
        }

        public int knownZeroSfus() {
            return knownZeroSfus;
        }

        public long allocationId() {
            return allocationId;
        }

        public List<DbaPlanValidator.Slot> preview() {
            return preview;
        }
    }
}
