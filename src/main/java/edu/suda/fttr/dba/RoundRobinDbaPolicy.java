package edu.suda.fttr.dba;

import edu.suda.fttr.link.PonLinkTiming;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 第 9 步最简 RR 影子算法的统一策略实现。每轮最多选择一个已知正需求对象，
 * 唯一可变状态是本策略自身的 RR 游标，不会累计或扣减真实需求。
 */
public final class RoundRobinDbaPolicy implements DbaPolicy {
    private int nextIndex;

    @Override
    public DbaPlan plan(DbaContext context) {
        Objects.requireNonNull(context);
        // 不依赖调用方、HashMap 或其他容器的遍历顺序。相同 SFU 的多个逻辑对象按 allocationId 排序。
        List<DbaContext.Allocation> allocations = new ArrayList<>(context.allocations());
        allocations.sort(Comparator.comparingInt(DbaContext.Allocation::sfuId)
                .thenComparingLong(DbaContext.Allocation::allocationId));
        BigInteger capacityBytes = context.capacityBytes();
        if (capacityBytes.signum() == 0) {
            // 与第 9 步一致：没有 DATA 容量时不推进 RR 游标。
            return empty(context, "NO_DATA_CAPACITY");
        }
        int unknown = 0;
        int outstanding = 0;
        for (DbaContext.Allocation allocation : allocations) {
            if (!allocation.known()) {
                unknown++;
            }
            if (allocation.budgetOutstanding()) {
                outstanding++;
            }
        }
        for (int offset = 0; offset < allocations.size(); offset++) {
            int index = (int) (((long) nextIndex + offset) % allocations.size());
            DbaContext.Allocation allocation = allocations.get(index);
            if (!allocation.budgetOutstanding() && allocation.known() && allocation.knownDemandBytes() > 0) {
                long demandBytes = allocation.knownDemandBytes();
                long plannedBytes = BigInteger.valueOf(demandBytes).min(capacityBytes).longValueExact();
                long plannedNs = PonLinkTiming.serializationNs(plannedBytes, context.rateMbps());
                if (plannedNs > context.availableNs()) {
                    throw new IllegalStateException("DBA RR budget serialization exceeds the remaining frame");
                }
                nextIndex = (index + 1) % allocations.size();
                String reason = plannedBytes < demandBytes ? "FRAME_CAPACITY_LIMIT" : "KNOWN_DEMAND";
                DbaPlan.Grant grant = new DbaPlan.Grant(allocation.allocationId(), allocation.sfuId(),
                        plannedBytes, plannedNs);
                return new DbaPlan(context.decisionTimeNs(), context.targetFrameId(), List.of(grant), reason);
            }
        }
        return empty(context, outstanding > 0 ? "AWAITING_BUDGET_SETTLEMENT"
                : unknown == allocations.size() ? "ALL_UNKNOWN" : "NO_KNOWN_POSITIVE_DEMAND");
    }

    private static DbaPlan empty(DbaContext context, String reason) {
        return new DbaPlan(context.decisionTimeNs(), context.targetFrameId(), List.of(), reason);
    }
}
