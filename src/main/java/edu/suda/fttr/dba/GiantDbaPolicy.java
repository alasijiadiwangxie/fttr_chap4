package edu.suda.fttr.dba;

import edu.suda.fttr.link.PonLinkTiming;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Leligou 等（2006）图 2、3 的两阶段 GIANT。计数器按帧推进。
 * 调度只读取 MFU 的需求快照，授权执行、传播与未用预算结算由原链路负责。
 */
public final class GiantDbaPolicy implements DbaPolicy {
    public enum Reason {
        INACTIVE, NOT_DUE, UNKNOWN, NO_DEMAND, AWAITING_SETTLEMENT, NO_CAPACITY, GRANTED, PARTIAL_CAPACITY
    }

    private final Map<Long, Decision> previous = new HashMap<>();
    private long lastFrameId = -1;
    private long lastType3SurplusId;
    private long lastType4SurplusId;
    private List<Decision> lastDecisions = List.of();

    @Override
    public DbaPlan plan(DbaContext context) {
        Objects.requireNonNull(context);
        long frameId = context.targetFrameId();
        if (lastFrameId >= 0 && frameId - lastFrameId != 1) {
            throw new IllegalArgumentException("GIANT requires consecutive target frames");
        }

        List<Service> services = new ArrayList<>();
        List<Service> type3 = new ArrayList<>();
        List<Service> type4 = new ArrayList<>();
        for (DbaContext.Allocation allocation : context.allocations()) {
            services.add(new Service(allocation, previous.get(allocation.allocationId())));
        }
        services.sort(Comparator.comparingInt((Service service) -> service.profile.type())
                .thenComparingLong(service -> service.allocation.allocationId()));

        FrameBudget frame = new FrameBudget(context);
        // 优先完成所有保障决策，再把空间留给剩余阶段。保障阶段以类型和 ID 定序。
        for (Service service : services) {
            service.assuredReason = allocate(service, service.siMaxBefore, service.profile.abMinBytes(), frame);
            service.assuredBytes = service.budgetBytes;
            switch (service.profile.type()) {
                case 3: type3.add(service); break;
                case 4: type4.add(service); break;
                default: break;
            }
        }
        lastType3SurplusId = allocateSurplus(type3, lastType3SurplusId, frame);
        lastType4SurplusId = allocateSurplus(type4, lastType4SurplusId, frame);

        List<DbaPlan.Grant> grants = new ArrayList<>();
        for (Service service : frame.admitted) {
            grants.add(new DbaPlan.Grant(service.allocation.allocationId(), service.allocation.sfuId(),
                    service.budgetBytes, service.durationNs));
        }
        List<Decision> decisions = new ArrayList<>();
        for (Service service : services) {
            Decision decision = new Decision(service);
            decisions.add(decision);
            previous.put(decision.allocation().allocationId(), decision);
        }
        lastDecisions = List.copyOf(decisions);
        lastFrameId = frameId;
        return new DbaPlan(context.decisionTimeNs(), frameId, grants,
                grants.isEmpty() ? "GIANT_NO_GRANT" : "GIANT");
    }

    /** 最近一帧的不可变决策快照，供 MFU 在通过统一校验后记录。 */
    public List<Decision> lastDecisions() {
        return lastDecisions;
    }

    private static long allocateSurplus(List<Service> group, long lastServedId, FrameBudget frame) {
        // 各剩余阶段各有一个游标。起点在本轮固定，无预算时游标不动。
        int start = 0;
        while (start < group.size() && group.get(start).allocation.allocationId() <= lastServedId) {
            start++;
        }
        for (int offset = 0; offset < group.size(); offset++) {
            int index = (int) (((long) start + offset) % group.size());
            Service service = group.get(index);
            service.surplusReason = allocate(service, service.siMinBefore, service.profile.abSurBytes(), frame);
            if (service.budgetBytes > service.assuredBytes) {
                lastServedId = service.allocation.allocationId();
            }
        }
        return lastServedId;
    }

    private static Reason allocate(Service service, int counter, long quota, FrameBudget frame) {
        if (counter == 0) {
            return Reason.INACTIVE;
        }
        if (counter > 1) {
            return Reason.NOT_DUE;
        }
        DbaContext.Allocation allocation = service.allocation;
        if (allocation.budgetOutstanding()) {
            return Reason.AWAITING_SETTLEMENT;
        }
        if (!allocation.known()) {
            return Reason.UNKNOWN;
        }
        long wanted = Math.min(quota, allocation.knownDemandBytes() - service.budgetBytes);
        if (wanted == 0) {
            return Reason.NO_DEMAND;
        }

        // 合并预算的总字节只做一次 ceil。已有预算的纳秒可在本对象内重新使用，
        // 因此额外阶段只扣合并后增加的时长；不能借用别的对象的纳秒取整余量。
        BigInteger capacity = frame.context.capacityBytes(frame.remainingNs + service.durationNs)
                .subtract(BigInteger.valueOf(service.budgetBytes));
        long extra = capacity.min(BigInteger.valueOf(wanted)).longValueExact();
        if (extra <= 0) {
            return Reason.NO_CAPACITY;
        }
        if (service.budgetBytes == 0) {
            frame.admitted.add(service);
        }
        service.budgetBytes += extra;
        long ns = PonLinkTiming.serializationNs(service.budgetBytes, frame.context.rateMbps());
        frame.remainingNs -= ns - service.durationNs;
        service.durationNs = ns;
        return extra < wanted ? Reason.PARTIAL_CAPACITY : Reason.GRANTED;
    }

    private static final class FrameBudget {
        private final DbaContext context;
        private final List<Service> admitted = new ArrayList<>();
        private long remainingNs;

        private FrameBudget(DbaContext context) {
            this.context = context;
            remainingNs = context.availableNs();
        }
    }

    private static final class Service {
        private final DbaContext.Allocation allocation;
        private final TcontProfile profile;
        private final int siMaxBefore;
        private final int siMinBefore;
        private Reason assuredReason = Reason.INACTIVE;
        private Reason surplusReason = Reason.INACTIVE;
        private long assuredBytes;
        private long budgetBytes;
        private long durationNs;

        private Service(DbaContext.Allocation allocation, Decision previous) {
            this.allocation = allocation;
            profile = allocation.profile();
            if (profile == null) {
                throw new IllegalArgumentException("GIANT requires a T-CONT profile for every allocation");
            }
            siMaxBefore = previous != null ? previous.siMaxAfter() : profile.type() == 4 ? 0 : profile.siMaxFrames();
            siMinBefore = previous != null ? previous.siMinAfter() : profile.siMinFrames();
        }
    }

    public static final class Decision {
        private final DbaContext.Allocation allocation;
        private final int siMaxBefore;
        private final int siMaxAfter;
        private final int siMinBefore;
        private final int siMinAfter;
        private final Reason assuredReason;
        private final Reason surplusReason;
        private final long assuredBytes;
        private final long surplusBytes;
        private final long budgetBytes;
        private final long durationNs;

        private Decision(Service service) {
            allocation = service.allocation;
            siMaxBefore = service.siMaxBefore;
            siMinBefore = service.siMinBefore;
            assuredReason = service.assuredReason;
            surplusReason = service.surplusReason;
            assuredBytes = service.assuredBytes;
            budgetBytes = service.budgetBytes;
            surplusBytes = budgetBytes - assuredBytes;
            durationNs = service.durationNs;
            // SImax 到期即重置，哪怕本轮没有授权。SImin 到期后等待正额外预算；
            // 两者都按规划走时钟，不等待 DATA 装载或结算反馈。
            siMaxAfter = siMaxBefore == 1 ? service.profile.siMaxFrames() : Math.max(0, siMaxBefore - 1);
            siMinAfter = surplusBytes > 0 ? service.profile.siMinFrames()
                    : siMinBefore > 1 ? siMinBefore - 1 : siMinBefore;
        }

        public DbaContext.Allocation allocation() { return allocation; }
        public int siMaxBefore() { return siMaxBefore; }
        public int siMaxAfter() { return siMaxAfter; }
        public int siMinBefore() { return siMinBefore; }
        public int siMinAfter() { return siMinAfter; }
        public Reason assuredReason() { return assuredReason; }
        public Reason surplusReason() { return surplusReason; }
        public long assuredBytes() { return assuredBytes; }
        public long surplusBytes() { return surplusBytes; }
        public long budgetBytes() { return budgetBytes; }
        public long durationNs() { return durationNs; }
    }
}
