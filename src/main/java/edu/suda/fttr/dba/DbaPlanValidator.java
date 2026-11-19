package edu.suda.fttr.dba;

import edu.suda.fttr.link.PonLinkTiming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 所有 DBA 策略共用的只读校验与预览入口。
 * 这里没有调度器、SFU 或授权账本引用，因此预览不会变成真实发送授权。
 */
public final class DbaPlanValidator {
    public List<Slot> validateAndPreview(DbaContext context, DbaPlan plan) {
        Objects.requireNonNull(context, "DBA context");
        Objects.requireNonNull(plan, "DBA plan");
        if (plan.decisionTimeNs() != context.decisionTimeNs()
                || plan.targetFrameId() != context.targetFrameId()) {
            throw new IllegalArgumentException("Plan decision time or target frame does not match context");
        }
        if (plan.targetFrameId() <= context.decisionTimeNs() / context.framePeriodNs()) {
            throw new IllegalArgumentException("Plan target frame must be in the future");
        }

        // 当前控制预约位于目标帧开头。未来若控制时隙不连续，必须扩展输入为实际窗口集合。
        // 所有绝对时刻均检查 long 边界，禁止溢出后得到过去的时隙。
        try {
            long frameStartNs = Math.multiplyExact(plan.targetFrameId(), context.framePeriodNs());
            long frameEndNs = Math.addExact(frameStartNs, context.framePeriodNs());
            long firstDataNs = Math.addExact(frameStartNs, context.controlReservedNs());
            Map<Long, DbaContext.Allocation> topology = new HashMap<>();
            for (DbaContext.Allocation allocation : context.allocations()) {
                topology.put(allocation.allocationId(), allocation);
            }
            Set<Long> usedAllocations = new HashSet<>();
            List<Slot> slots = new ArrayList<>();
            long cursorNs = firstDataNs;
            long totalDataNs = 0;
            for (DbaPlan.Grant grant : plan.grants()) {
                DbaContext.Allocation allocation = topology.get(grant.allocationId());
                if (allocation == null || allocation.sfuId() != grant.sfuId()) {
                    throw new IllegalArgumentException("Unknown allocationId or mismatched SFU in plan");
                }
                if (!usedAllocations.add(grant.allocationId())) {
                    throw new IllegalArgumentException("Duplicate allocationId in plan");
                }
                if (grant.plannedBytes() < 0 || grant.plannedDurationNs() < 0) {
                    throw new IllegalArgumentException("Plan bytes and duration must be nonnegative");
                }
                if (grant.plannedBytes() > 0 && (!allocation.known()
                        || grant.plannedBytes() > allocation.knownDemandBytes())) {
                    throw new IllegalArgumentException("Plan exceeds known effective demand or demand is UNKNOWN");
                }
                long expectedNs = grant.plannedBytes() == 0 ? 0
                        : PonLinkTiming.serializationNs(grant.plannedBytes(), context.rateMbps());
                if (grant.plannedDurationNs() != expectedNs) {
                    throw new IllegalArgumentException("Plan duration does not match serialization time");
                }
                totalDataNs = Math.addExact(totalDataNs, expectedNs);
                if (totalDataNs > context.availableNs()) {
                    throw new IllegalArgumentException("DATA and control reservation exceed frame capacity");
                }
                Long offsetNs = grant.startOffsetNs();
                if (offsetNs != null && offsetNs < 0) {
                    throw new IllegalArgumentException("Plan slot offset must be nonnegative");
                }
                long startNs = offsetNs == null ? cursorNs : Math.addExact(frameStartNs, offsetNs);
                long endNs = Math.addExact(startNs, expectedNs);
                if (startNs < firstDataNs || startNs < cursorNs) {
                    throw new IllegalArgumentException("Plan slots overlap control reservation or another DATA slot");
                }
                if (endNs > frameEndNs) {
                    throw new IllegalArgumentException("Plan slot exceeds target frame capacity");
                }
                // 零预算合法，但不产生虚构的发送窗口；正预算按计划顺序放置。
                if (grant.plannedBytes() > 0) {
                    slots.add(new Slot(grant.allocationId(), grant.sfuId(), startNs, endNs,
                            grant.plannedBytes()));
                    cursorNs = endNs;
                }
            }
            return List.copyOf(slots);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Plan time arithmetic exceeds the nanosecond clock range", exception);
        }
    }

    /** 仅用于复核的不可变逻辑窗口，没有 grantId、packetId 或执行事件。 */
    public static final class Slot {
        private final long allocationId;
        private final int sfuId;
        private final long startNs;
        private final long endNs;
        private final long plannedBytes;

        private Slot(long allocationId, int sfuId, long startNs, long endNs, long plannedBytes) {
            this.allocationId = allocationId;
            this.sfuId = sfuId;
            this.startNs = startNs;
            this.endNs = endNs;
            this.plannedBytes = plannedBytes;
        }

        public long allocationId() { return allocationId; }
        public int sfuId() { return sfuId; }
        public long startNs() { return startNs; }
        public long endNs() { return endNs; }
        public long plannedBytes() { return plannedBytes; }
    }
}
