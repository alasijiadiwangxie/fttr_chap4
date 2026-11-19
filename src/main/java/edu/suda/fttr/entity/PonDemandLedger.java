package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonUplinkReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 以 MFU 已接收的 REPORT 和已创建的预约估计需求，不查询 SFU 队列。 */
public final class PonDemandLedger {
    private final Map<Integer, SfuHistory> histories = new HashMap<>();

    /** 一个完整报文对应一个预约，报文 ID 作为该预约在仿真中的唯一标识。 */
    public boolean recordGrant(int sfuId, int grantId, long createNs, int grantedBytes) {
        SfuHistory history = histories.computeIfAbsent(sfuId, key -> new SfuHistory());
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
        if (latest == null || latest.receiveTimeNs() > timeNs) {
            return Estimate.UNKNOWN;
        }
        PonUplinkReport report = latest.report();
        if (report.sfuId() != sfuId) {
            throw new IllegalArgumentException("REPORT SFU ID does not match the demand estimate");
        }
        SfuHistory history = histories.get(sfuId);
        long granted = 0;
        if (history != null) {
            // 同一帧事件先采样、后授权。因此快照边界同刻创建的授权计入区间。
            granted = history.prefixBytes(timeNs, true) - history.prefixBytes(report.snapshotTimeNs(), false);
            // 预算在创建时锁定需求，装载前生成的新 REPORT 仍含这些报文，不能因快照较新而漏扣。
            // 装载后快照已排除所选数据，不能再次扣减。结算反馈到达之前只知道完整预算。
            for (Budget budget : history.budgets.values()) {
                if (budget.createNs <= timeNs && report.snapshotTimeNs() < budget.startNs) {
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
        if (sfuId < 1 || grantId < 1 || createNs < 0 || startNs <= createNs || endNs <= startNs || bytes < 1) {
            throw new IllegalArgumentException("Invalid real budget ledger entry");
        }
        SfuHistory history = histories.computeIfAbsent(sfuId, key -> new SfuHistory());
        Budget previous = history.budgets.get(grantId);
        if (previous != null) {
            if (previous.createNs != createNs || previous.startNs != startNs
                    || previous.endNs != endNs || previous.bytes != bytes) {
                throw new IllegalArgumentException("Conflicting duplicate DATA budget");
            }
            return false;
        }
        if (hasOutstandingBudget(sfuId, createNs)) {
            throw new IllegalStateException("SFU already has an unsettled DATA budget");
        }
        history.budgets.put(grantId, new Budget(createNs, startNs, endNs, bytes));
        return true;
    }

    /** 仅在 MFU 收到逻辑结算后，以实际发送量替换预算扣减；不用未收到的 SFU 执行信息。 */
    public boolean settleBudgetGrant(int sfuId, long grantId, long receiveNs, long actualBytes) {
        SfuHistory history = histories.get(sfuId);
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
        return true;
    }

    public boolean hasOutstandingBudget(int sfuId, long timeNs) {
        SfuHistory history = histories.get(sfuId);
        return history != null && history.budgets.values().stream().anyMatch(budget -> budget.createNs <= timeNs
                && (budget.settleNs < 0 || budget.settleNs > timeNs));
    }

    public boolean hasBudgetGrant(int sfuId, long grantId) {
        SfuHistory history = histories.get(sfuId);
        return history != null && history.budgets.containsKey(grantId);
    }

    private static final class SfuHistory {
        private final List<Grant> grants = new ArrayList<>();
        private final Map<Integer, Grant> grantsById = new HashMap<>();
        private final Map<Long, Budget> budgets = new HashMap<>();

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
        private final long startNs;
        private final long endNs;
        private final long bytes;
        private long settleNs = -1;
        private long actualBytes;

        private Budget(long createNs, long startNs, long endNs, long bytes) {
            this.createNs = createNs;
            this.startNs = startNs;
            this.endNs = endNs;
            this.bytes = bytes;
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
