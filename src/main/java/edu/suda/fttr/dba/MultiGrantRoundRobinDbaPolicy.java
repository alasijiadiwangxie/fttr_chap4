package edu.suda.fttr.dba;

import edu.suda.fttr.link.PonLinkTiming;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 同帧多预算的简单 RR 策略。只消费传入的已知需求快照，不查询本地 FIFO 或修改授权账本。
 * 每个对象每轮至多获得一次预算，后续对象使用前序预算逐笔取整后真正剩余的纳秒容量。
 */
public final class MultiGrantRoundRobinDbaPolicy implements DbaPolicy {
    private static final BigDecimal BITS_PER_BYTE_NS = BigDecimal.valueOf(8_000);

    private int nextIndex;

    @Override
    public DbaPlan plan(DbaContext context) {
        Objects.requireNonNull(context);
        // 保留完整对象集合。未知或未结算对象仍占据稳定位置，不能过滤后让 RR 游标漂移。
        List<DbaContext.Allocation> allocations = new ArrayList<>(context.allocations());
        allocations.sort(Comparator.comparingInt(DbaContext.Allocation::sfuId)
                .thenComparingLong(DbaContext.Allocation::allocationId));
        long remainingNs = context.availableNs();
        if (capacityBytes(remainingNs, context.rateMbps()).signum() == 0) {
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

        List<DbaPlan.Grant> grants = new ArrayList<>();
        int lastSelectedIndex = -1;
        boolean capacityLimited = false;
        // 循环起点在本轮保持不变；不能在循环中更新 nextIndex，否则会重复或漏过对象。
        int roundStart = nextIndex;
        for (int offset = 0; offset < allocations.size(); offset++) {
            int index = (int) (((long) roundStart + offset) % allocations.size());
            DbaContext.Allocation allocation = allocations.get(index);
            if (allocation.budgetOutstanding() || !allocation.known() || allocation.knownDemandBytes() == 0) {
                continue;
            }
            BigInteger capacity = capacityBytes(remainingNs, context.rateMbps());
            if (capacity.signum() == 0) {
                break;
            }
            long demandBytes = allocation.knownDemandBytes();
            long plannedBytes = BigInteger.valueOf(demandBytes).min(capacity).longValueExact();
            long plannedNs = PonLinkTiming.serializationNs(plannedBytes, context.rateMbps());
            if (plannedNs > remainingNs) {
                throw new IllegalStateException("Multi-grant RR serialization exceeds remaining frame capacity");
            }
            grants.add(new DbaPlan.Grant(allocation.allocationId(), allocation.sfuId(), plannedBytes, plannedNs));
            // 每笔 ceil 后扣纳秒，再为下一笔重新 floor 字节。不能把总字节容量预先一次性分完。
            remainingNs -= plannedNs;
            capacityLimited |= plannedBytes < demandBytes;
            lastSelectedIndex = index;
        }

        if (grants.isEmpty()) {
            return empty(context, outstanding > 0 ? "AWAITING_BUDGET_SETTLEMENT"
                    : unknown == allocations.size() ? "ALL_UNKNOWN" : "NO_KNOWN_POSITIVE_DEMAND");
        }
        // 完整一轮都服务到时会自然回到该轮起点；容量耗尽时从最后获预算对象的下一位继续。
        nextIndex = (lastSelectedIndex + 1) % allocations.size();
        String reason = grants.size() > 1 ? "MULTI_GRANT"
                : capacityLimited ? "FRAME_CAPACITY_LIMIT" : "KNOWN_DEMAND";
        return new DbaPlan(context.decisionTimeNs(), context.targetFrameId(), grants, reason);
    }

    private static BigInteger capacityBytes(long availableNs, BigDecimal rateMbps) {
        return BigDecimal.valueOf(availableNs).multiply(rateMbps)
                .divide(BITS_PER_BYTE_NS, 0, RoundingMode.FLOOR).toBigIntegerExact();
    }

    private static DbaPlan empty(DbaContext context, String reason) {
        return new DbaPlan(context.decisionTimeNs(), context.targetFrameId(), List.of(), reason);
    }
}
