package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonUplinkReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 以 MFU 已接收的 REPORT 和已创建的预约估计需求，不查询 SFU 队列。 */
public final class PonDemandLedger {
    // allocationId 是逻辑需求身份，sfuId 只用于校验物理归属。一个 SFU 的不同队列绝不共用账本。
    private final Map<Long, AllocationHistory> histories = new HashMap<>();

    /** 一个完整报文对应一个预约，报文 ID 作为该预约在仿真中的唯一标识。 */
    public boolean recordGrant(int sfuId, int grantId, long createNs, int grantedBytes) {
        return recordAllocationGrant(2L * sfuId, sfuId, grantId, createNs, grantedBytes);
    }

    public boolean recordAllocationGrant(long allocationId, int sfuId, int grantId, long createNs,
                                         int grantedBytes) {
        AllocationHistory history = historyForWrite(allocationId, sfuId);
        if (history.grantsById.containsKey(grantId)) {
            return false;
        }
        Grant previous = history.grants.isEmpty() ? null : history.grants.get(history.grants.size() - 1);
        if (previous != null && createNs < previous.createNs) {
            throw new IllegalArgumentException("Grant creation time cannot go backwards for SFU " + sfuId);
        }
        Grant grant = new Grant(createNs, grantedBytes, previous == null ? 0 : previous.cumulativeBytes());
        history.grants.add(grant);
        history.grantsById.put(grantId, grant);
        return true;
    }

    /** 使用 MFU 当前已经接受的快照。报表查询不改变 REPORT 或授权历史。 */
    public Estimate estimate(int sfuId, long timeNs, Mfu.ReceivedUplinkReport latest) {
        return estimateAllocation(2L * sfuId, sfuId, timeNs, latest);
    }

    /** 显式 allocation API 避免将旧 int 参数的 SFU 语义误当成 Alloc-ID。 */
    public Estimate estimateAllocation(long allocationId, int sfuId, long timeNs,
                                       Mfu.ReceivedUplinkReport latest) {
        AllocationHistory history = historyForRead(allocationId, sfuId);
        if (latest == null || latest.receiveTimeNs() > timeNs) {
            return Estimate.UNKNOWN;
        }
        PonUplinkReport report = latest.report();
        if (report.sfuId() != sfuId || report.allocationId() != allocationId) {
            throw new IllegalArgumentException("REPORT allocation / SFU does not match the demand estimate");
        }
        long granted = 0;
        if (history != null) {
            // 同一帧事件先采样、后授权。因此快照边界同刻创建的授权计入区间。
            granted = history.prefixBytes(timeNs, true) - history.prefixBytes(report.snapshotTimeNs(), false);
            // 预算在创建时锁定需求，装载前生成的新 REPORT 仍含这些报文，不能因快照较新而漏扣。
            // 装载后快照已排除所选数据，不能再次扣减。结算反馈到达之前只知道完整预算。
            for (Budget budget : history.budgets.values()) {
                if (budget.createNs <= timeNs && report.snapshotTimeNs() < budget.startNs && budget.mayRemoveBytesAt(timeNs)) {
                    long debit = budget.settleNs >= 0 && budget.settleNs <= timeNs
                            ? budget.actualBytes : budget.bytes;
                    granted = Math.addExact(granted, debit);
                }
            }
        }
        return new Estimate(latest, granted, Math.max(0, report.pendingBytes() - granted),
                timeNs - report.snapshotTimeNs());
    }

    /** 真实预算有独立身份，不以 packetId 写入原 FCFS 逐包授权历史。 */
    public boolean recordBudgetGrant(int sfuId, long grantId, long createNs, long startNs, long endNs, long bytes) {
        return recordAllocationBudgetGrant(2L * sfuId, sfuId, grantId, createNs, startNs, endNs, bytes);
    }

    public boolean recordAllocationBudgetGrant(long allocationId, int sfuId, long grantId, long createNs,
                                               long startNs, long endNs, long bytes) {
        return recordAllocationBudgetGrant(allocationId, sfuId, grantId, createNs, 0, startNs, endNs, bytes, 0);
    }

    /**
     * MFU 已对扣除在途预约后的净需求做过校验。一个对象可以同时有多笔未结算预算，
     * 但每帧至多一笔，后续窗口必须晚于前一窗口且不能重叠。
     */
    public boolean recordPipelinedAllocationBudgetGrant(long allocationId, int sfuId, long grantId, long createNs,
                                                        long targetFrameId, long startNs, long endNs, long bytes,
                                                        long fixedBytes) {
        if (targetFrameId < 1) {
            throw new IllegalArgumentException("Pipelined budget requires a positive target frame ID");
        }
        return recordAllocationBudgetGrant(allocationId, sfuId, grantId, createNs, targetFrameId, startNs, endNs,
                bytes, fixedBytes);
    }

    private boolean recordAllocationBudgetGrant(long allocationId, int sfuId, long grantId, long createNs,
                                                long targetFrameId, long startNs, long endNs, long bytes,
                                                long fixedBytes) {
        if (sfuId < 1 || grantId < 1 || createNs < 0 || startNs <= createNs || endNs <= startNs || bytes < 1
                || fixedBytes < 0 || fixedBytes > bytes) {
            throw new IllegalArgumentException("Invalid real budget ledger entry");
        }
        AllocationHistory history = historyForWrite(allocationId, sfuId);
        Budget previous = history.budgets.get(grantId);
        if (previous != null) {
            if (previous.createNs != createNs || previous.startNs != startNs
                    || previous.endNs != endNs || previous.bytes != bytes
                    || previous.targetFrameId != targetFrameId || previous.fixedBytes != fixedBytes) {
                throw new IllegalArgumentException("Conflicting duplicate DATA budget");
            }
            return false;
        }
        if (targetFrameId == 0 && hasOutstandingAllocationBudget(allocationId, createNs)) {
            throw new IllegalStateException("Allocation already has an unsettled DATA budget");
        }
        Budget latest = history.latestBudget;
        if (targetFrameId > 0 && latest != null && (targetFrameId <= latest.targetFrameId
                || createNs <= latest.createNs || startNs < latest.endNs)) {
            throw new IllegalArgumentException("Pipelined budgets must use distinct ordered and disjoint frame windows");
        }
        Budget budget = new Budget(createNs, targetFrameId, startNs, endNs, bytes, fixedBytes);
        if (bytes == fixedBytes && bytes <= history.failedBudgetBytes && !awaitingRecoverySettlement(allocationId, createNs)) {
            // 固定预约仍占时隙。同一队首尚未移动时，已证实不足的固定预约不会减少缓存。
            budget.blockedAtNs = createNs;
        }
        history.budgets.put(grantId, budget);
        history.latestBudget = budget;
        return true;
    }

    /** 仅在 MFU 收到逻辑结算后，以实际发送量替换预算扣减；不用未收到的 SFU 执行信息。 */
    public boolean settleBudgetGrant(int sfuId, long grantId, long receiveNs, long actualBytes) {
        return settleAllocationBudgetGrant(2L * sfuId, sfuId, grantId, receiveNs, actualBytes);
    }

    public boolean settleAllocationBudgetGrant(long allocationId, int sfuId, long grantId, long receiveNs,
                                               long actualBytes) {
        return settleAllocationBudgetGrant(allocationId, sfuId, grantId, receiveNs, actualBytes, false);
    }

    /** 只有收到确认的零装载结算才能建立失败下界，队列为空的零实发不能触发恢复。 */
    public boolean settleAllocationBudgetGrant(long allocationId, int sfuId, long grantId, long receiveNs,
                                               long actualBytes, boolean headBlocked) {
        AllocationHistory history = historyForRead(allocationId, sfuId);
        Budget budget = history == null ? null : history.budgets.get(grantId);
        if (budget == null || receiveNs < budget.endNs || actualBytes < 0 || actualBytes > budget.bytes) {
            throw new IllegalArgumentException("Unknown, premature or over-budget DATA settlement");
        }
        if (budget.settleNs >= 0) {
            if (budget.actualBytes != actualBytes) {
                throw new IllegalArgumentException("Conflicting duplicate DATA settlement");
            }
            return false;
        }
        budget.settleNs = receiveNs;
        budget.actualBytes = actualBytes;
        if (budget.endNs >= history.lastRecoverySlotEndNs) {
            history.lastRecoverySlotEndNs = budget.endNs;
            if (actualBytes > 0) {
                history.failedBudgetBytes = 0;
            } else if (headBlocked) {
                history.failedBudgetBytes = Math.max(history.failedBudgetBytes, budget.bytes);
                markBlockedFixedBudgets(history, receiveNs);
            }
        }
        return true;
    }

    /** 已预约固定时隙仍执行。若在它之前有可能发走队首，就保留它的账本扣减。 */
    private static void markBlockedFixedBudgets(AllocationHistory history, long nowNs) {
        long possibleProgress = history.budgets.values().stream()
                .filter(budget -> budget.outstandingAt(nowNs) && !budget.fixedBelow(history.failedBudgetBytes))
                .mapToLong(budget -> budget.startNs).min().orElse(Long.MAX_VALUE);
        for (Budget budget : history.budgets.values()) {
            if (budget.outstandingAt(nowNs) && budget.startNs < possibleProgress
                    && budget.fixedBelow(history.failedBudgetBytes) && budget.blockedAtNs < 0) {
                budget.blockedAtNs = nowNs;
            }
        }
    }

    public long failedBudgetBytes(long allocationId) {
        AllocationHistory history = histories.get(allocationId);
        return history == null ? 0 : history.failedBudgetBytes;
    }

    public boolean awaitingRecoverySettlement(long allocationId, long timeNs) {
        AllocationHistory history = histories.get(allocationId);
        return history != null && history.failedBudgetBytes > 0 && history.budgets.values().stream()
                .anyMatch(budget -> budget.outstandingAt(timeNs) && budget.mayRemoveBytesAt(timeNs));
    }

    public boolean hasOutstandingBudget(int sfuId, long timeNs) {
        return hasOutstandingAllocationBudget(2L * sfuId, timeNs);
    }

    public boolean hasOutstandingAllocationBudget(long allocationId, long timeNs) {
        AllocationHistory history = histories.get(allocationId);
        return history != null && history.budgets.values().stream().anyMatch(budget -> budget.outstandingAt(timeNs));
    }

    public boolean hasBudgetGrant(int sfuId, long grantId) {
        return hasAllocationBudget(2L * sfuId, grantId);
    }

    public boolean hasAllocationBudget(long allocationId, long grantId) {
        AllocationHistory history = histories.get(allocationId);
        return history != null && history.budgets.containsKey(grantId);
    }

    private AllocationHistory historyForWrite(long allocationId, int sfuId) {
        AllocationHistory history = historyForRead(allocationId, sfuId);
        if (history == null) {
            history = new AllocationHistory(sfuId);
            histories.put(allocationId, history);
        }
        return history;
    }

    /** 读操作只校验已有映射，不创建历史，也不会把 UNKNOWN 变成已知零需求。 */
    private AllocationHistory historyForRead(long allocationId, int sfuId) {
        if (allocationId < 1 || sfuId < 1) {
            throw new IllegalArgumentException("Allocation and SFU IDs must be positive");
        }
        AllocationHistory history = histories.get(allocationId);
        if (history != null && history.sfuId != sfuId) {
            throw new IllegalArgumentException("Allocation ID belongs to another SFU");
        }
        return history;
    }

    private static final class AllocationHistory {
        private final int sfuId;
        private final List<Grant> grants = new ArrayList<>();
        private final Map<Integer, Grant> grantsById = new HashMap<>();
        private final Map<Long, Budget> budgets = new HashMap<>();
        private Budget latestBudget;
        private long failedBudgetBytes;
        private long lastRecoverySlotEndNs = -1;

        private AllocationHistory(int sfuId) {
            this.sfuId = sfuId;
        }

        /** 用二分法取得快照和查询边界的已授权字节前缀，避免逐次扫描历史。 */
        private long prefixBytes(long timeNs, boolean inclusive) {
            int first = 0;
            int end = grants.size();
            while (first < end) {
                int middle = first + (end - first) / 2;
                long created = grants.get(middle).createNs;
                if (created < timeNs || (inclusive && created == timeNs)) {
                    first = middle + 1;
                } else {
                    end = middle;
                }
            }
            return first == 0 ? 0 : grants.get(first - 1).cumulativeBytes();
        }
    }

    private static final class Budget {
        private final long createNs;
        private final long targetFrameId;
        private final long startNs;
        private final long endNs;
        private final long bytes;
        private final long fixedBytes;
        private long settleNs = -1;
        private long actualBytes;
        private long blockedAtNs = -1;

        private Budget(long createNs, long targetFrameId, long startNs, long endNs, long bytes, long fixedBytes) {
            this.createNs = createNs;
            this.targetFrameId = targetFrameId;
            this.startNs = startNs;
            this.endNs = endNs;
            this.bytes = bytes;
            this.fixedBytes = fixedBytes;
        }

        private boolean outstandingAt(long nowNs) {
            return createNs <= nowNs && (settleNs < 0 || settleNs > nowNs);
        }

        private boolean mayRemoveBytesAt(long nowNs) {
            return blockedAtNs < 0 || nowNs < blockedAtNs;
        }

        private boolean fixedBelow(long failedBytes) {
            return bytes == fixedBytes && bytes <= failedBytes;
        }
    }

    private static final class Grant {
        private final long createNs;
        private final int bytes;
        private final long earlierBytes;

        private Grant(long createNs, int bytes, long earlierBytes) {
            this.createNs = createNs;
            this.bytes = bytes;
            this.earlierBytes = earlierBytes;
        }

        private long cumulativeBytes() {
            return Math.addExact(earlierBytes, bytes);
        }
    }

    public static final class Estimate {
        private static final Estimate UNKNOWN = new Estimate(null, 0, 0, 0);

        private final Mfu.ReceivedUplinkReport report;
        private final long grantedBytesSinceSnapshot;
        private final long effectiveKnownBytes;
        private final long reportAgeNs;

        private Estimate(Mfu.ReceivedUplinkReport report, long grantedBytesSinceSnapshot,
                         long effectiveKnownBytes, long reportAgeNs) {
            this.report = report;
            this.grantedBytesSinceSnapshot = grantedBytesSinceSnapshot;
            this.effectiveKnownBytes = effectiveKnownBytes;
            this.reportAgeNs = reportAgeNs;
        }

        public boolean known() {
            return report != null;
        }

        /** 从未收到可用快照时返回 null。 */
        public Mfu.ReceivedUplinkReport report() {
            return report;
        }

        /**
         * 保留旧接口名：FCFS 为快照后逐包授权；真实预算还包括快照之前创建、
         * 但快照仍包含其数据的在途预算。结算前后分别按预算/实际量计入。
         */
        public long grantedBytesSinceSnapshot() {
            requireKnown();
            return grantedBytesSinceSnapshot;
        }

        public long effectiveKnownBytes() {
            requireKnown();
            return effectiveKnownBytes;
        }

        public long reportAgeNs() {
            requireKnown();
            return reportAgeNs;
        }

        private void requireKnown() {
            if (!known()) {
                throw new IllegalStateException("Demand estimate is unknown before receiving a REPORT");
            }
        }
    }
}
