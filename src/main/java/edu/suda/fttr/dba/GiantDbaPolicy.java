package edu.suda.fttr.dba;

import edu.suda.fttr.link.PonLinkTiming;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按帧规划。经典模式保留第 16 步结果，完整模式增加固定服务、完整的阶段区分与流水预约。
 * 两种模式只消费 MFU 的不可变需求快照。
 */
public final class GiantDbaPolicy implements DbaPolicy {
    public enum Reason {
        INACTIVE, NOT_DUE, UNKNOWN, NO_DEMAND, AWAITING_SETTLEMENT, NO_CAPACITY, GRANTED, PARTIAL_CAPACITY,
        RECOVERY_DRAIN, RECOVERY_NEEDS_LARGER_GRANT, RECOVERY_UNSERVICEABLE
    }

    private final boolean complete;
    private final Map<Long, Service> previous = new HashMap<>();
    private long lastFrameId = -1;
    private long nonAssuredCursor;
    private long bestEffortCursor;
    private List<Decision> lastDecisions = List.of();

    public GiantDbaPolicy() {
        this(false);
    }

    public GiantDbaPolicy(boolean complete) {
        this.complete = complete;
    }

    @Override
    public DbaPlan plan(DbaContext context) {
        Objects.requireNonNull(context);
        long frameId = context.targetFrameId();
        if (lastFrameId >= 0 && frameId - lastFrameId != 1) {
            throw new IllegalArgumentException("GIANT requires consecutive target frames");
        }

        Set<Long> withheld = new HashSet<>();
        FrameBudget frame;
        List<Service> services;
        do {
            services = frameServices(context);
            frame = allocateFrame(context, services, withheld);
            // 合并各阶段后才判断整笔是否超过失败下界。取消无效试算并重新分配，其他对象可使用释放的容量。
            // 每次重算至少移除一个逻辑对象的动态预算，真实计数器和游标只在最终方案发布时更新。
        } while (withholdFailedSizes(frame, withheld));
        nonAssuredCursor = frame.nonAssuredCursor;
        bestEffortCursor = frame.bestEffortCursor;

        List<DbaPlan.Grant> grants = new ArrayList<>();
        for (Service service : frame.admitted) {
            grants.add(new DbaPlan.Grant(service.id(), service.allocation.sfuId(),
                    service.budgetBytes, service.durationNs, null, service.fixed.granted));
        }
        List<Decision> decisions = new ArrayList<>();
        for (Service service : services) {
            decisions.add(new Decision(service, complete));
            previous.put(service.id(), service);
        }
        lastDecisions = List.copyOf(decisions);
        lastFrameId = frameId;
        return new DbaPlan(context.decisionTimeNs(), frameId, grants,
                grants.isEmpty() ? "GIANT_NO_GRANT" : "GIANT");
    }

    private List<Service> frameServices(DbaContext context) {
        List<Service> services = new ArrayList<>();
        for (DbaContext.Allocation allocation : context.allocations()) {
            Service service = new Service(allocation, previous.get(allocation.allocationId()), complete);
            if (allocation.failedBudgetBytes() > 0) {
                service.recoveryCeiling = service.ceilingBytes(context);
            }
            services.add(service);
        }
        services.sort(Comparator.comparingInt((Service service) -> service.profile.type())
                .thenComparingLong(Service::id));
        return services;
    }

    private FrameBudget allocateFrame(DbaContext context, List<Service> services, Set<Long> withheld) {
        FrameBudget frame = new FrameBudget(context, withheld);
        for (Service service : services) {
            allocate(service, service.fixed, frame);
        }
        List<Service> nonAssured = new ArrayList<>();
        List<Service> bestEffort = new ArrayList<>();
        for (Service service : services) {
            allocate(service, service.assured, frame);
            if (service.nonAssured.interval > 0) {
                nonAssured.add(service);
            }
            if (service.bestEffort.interval > 0) {
                bestEffort.add(service);
            }
        }
        frame.nonAssuredCursor = allocateSurplus(nonAssured, nonAssuredCursor, frame, false);
        frame.bestEffortCursor = allocateSurplus(bestEffort, bestEffortCursor, frame, true);
        return frame;
    }

    private static boolean withholdFailedSizes(FrameBudget frame, Set<Long> withheld) {
        boolean changed = false;
        for (Service service : frame.admitted) {
            if (service.budgetBytes > service.fixed.granted && service.budgetBytes <= service.allocation.failedBudgetBytes()) {
                changed |= withheld.add(service.id());
            }
        }
        return changed;
    }

    public List<Decision> lastDecisions() {
        return lastDecisions;
    }

    private long allocateSurplus(List<Service> group, long cursor, FrameBudget frame, boolean bestEffort) {
        group.sort(Comparator.comparingLong(Service::id));
        int start = 0;
        while (start < group.size() && group.get(start).id() <= cursor) {
            start++;
        }
        long firstServed = 0;
        boolean constrained = false;
        for (int offset = 0; offset < group.size(); offset++) {
            int index = (int) (((long) start + offset) % group.size());
            Service service = group.get(index);
            Phase phase = bestEffort ? service.bestEffort : service.nonAssured;
            allocate(service, phase, frame);
            if (phase.granted > 0) {
                if (firstServed == 0) {
                    firstServed = service.id();
                }
                cursor = service.id();
            }
            constrained |= phase.reason == Reason.NO_CAPACITY || phase.reason == Reason.PARTIAL_CAPACITY;
        }
        // 完整模式不能把帧尾的零碎字节当成一次公平轮转。拥塞时把下轮首选机会
        // 交给本轮首个正预算对象之后的同级对象，后者有机会得到足够装载整包的窗口。
        return complete && constrained && firstServed != 0 ? firstServed : cursor;
    }

    private void allocate(Service service, Phase phase, FrameBudget frame) {
        if (phase.before == 0) {
            return;
        }
        if (phase.before > 1) {
            phase.reason = Reason.NOT_DUE;
            return;
        }
        DbaContext.Allocation allocation = service.allocation;
        if (phase == service.fixed) {
            // 固定预约独立于 REPORT。空 FIFO 的预留仍占时隙，但不会计作 DATA。
            phase.wanted = phase.quota;
        } else {
            if (!complete && allocation.budgetOutstanding()) {
                phase.reason = Reason.AWAITING_SETTLEMENT;
                return;
            }
            if (!allocation.known()) {
                phase.reason = Reason.UNKNOWN;
                return;
            }
            if (allocation.failedBudgetBytes() > 0) {
                if (allocation.recoveryWaiting()) {
                    phase.reason = Reason.RECOVERY_DRAIN;
                    return;
                }
                if (service.recoveryCeiling <= allocation.failedBudgetBytes()) {
                    phase.reason = Reason.RECOVERY_UNSERVICEABLE;
                    return;
                }
                if (frame.withheld.contains(service.id())) {
                    phase.reason = Reason.RECOVERY_NEEDS_LARGER_GRANT;
                    return;
                }
            }
            phase.wanted = Math.min(phase.quota, Math.max(0, allocation.knownDemandBytes() - service.budgetBytes));
            if (phase.wanted == 0) {
                phase.reason = Reason.NO_DEMAND;
                return;
            }
        }

        // 已有额度与本阶段额度属于同一窗口，只扣合并后增加的纳秒。
        BigInteger capacity = frame.context.capacityBytes(frame.remainingNs + service.durationNs)
                .subtract(BigInteger.valueOf(service.budgetBytes));
        phase.granted = capacity.min(BigInteger.valueOf(phase.wanted)).longValueExact();
        if (phase.granted == 0) {
            phase.reason = Reason.NO_CAPACITY;
            return;
        }
        if (service.budgetBytes == 0) {
            frame.admitted.add(service);
        }
        phase.order = ++frame.phaseOrder;
        service.budgetBytes += phase.granted;
        long ns = PonLinkTiming.serializationNs(service.budgetBytes, frame.context.rateMbps());
        frame.remainingNs -= ns - service.durationNs;
        service.durationNs = ns;
        phase.reason = phase.granted < phase.wanted ? Reason.PARTIAL_CAPACITY : Reason.GRANTED;
    }

    private static final class FrameBudget {
        private final DbaContext context;
        private final Set<Long> withheld;
        private final List<Service> admitted = new ArrayList<>();
        private long remainingNs;
        private int phaseOrder;
        private long nonAssuredCursor;
        private long bestEffortCursor;

        private FrameBudget(DbaContext context, Set<Long> withheld) {
            this.context = context;
            this.withheld = withheld;
            remainingNs = context.availableNs();
        }
    }

    /** 计数器与额度状态。0 表示该阶段不适用，1 是本帧到期。 */
    private static final class Phase {
        private final int interval;
        private final int before;
        private final long quota;
        private final boolean periodic;
        private long wanted;
        private long granted;
        private int order;
        private Reason reason = Reason.INACTIVE;

        private Phase(int interval, long quota, boolean periodic, Phase previous) {
            this.interval = interval;
            this.quota = quota;
            this.periodic = periodic;
            before = previous == null ? interval : previous.after();
        }

        private int after() {
            if (before == 1 && (periodic || granted > 0)) {
                return interval;
            }
            return before > 1 ? before - 1 : before;
        }
    }

    private static final class Service {
        private final DbaContext.Allocation allocation;
        private final TcontProfile profile;
        private final Phase fixed;
        private final Phase assured;
        private final Phase nonAssured;
        private final Phase bestEffort;
        private long budgetBytes;
        private long durationNs;
        private long recoveryCeiling;

        private Service(DbaContext.Allocation allocation, Service prior, boolean complete) {
            this.allocation = allocation;
            profile = allocation.profile();
            if (profile == null) {
                throw new IllegalArgumentException("GIANT requires a T-CONT profile for every allocation");
            }
            int type = profile.type();
            if (!complete && (type == 1 || type == 5)) {
                throw new IllegalArgumentException("Type 1 and Type 5 require complete GIANT");
            }
            boolean assuredActive = type == 2 || type == 3 || type == 5;
            boolean nonAssuredActive = type == 3 || type == 5;
            fixed = new Phase(profile.siFixedFrames(), profile.abFixedBytes(), true,
                    prior == null ? null : prior.fixed);
            assured = new Phase(assuredActive ? profile.siMaxFrames() : 0, profile.abMinBytes(), true,
                    prior == null ? null : prior.assured);
            nonAssured = new Phase(nonAssuredActive ? profile.siMinFrames() : 0, profile.abSurBytes(), false,
                    prior == null ? null : prior.nonAssured);
            bestEffort = new Phase(type == 4 ? profile.siMinFrames() : profile.siBeFrames(),
                    type == 4 ? profile.abSurBytes() : profile.abBeBytes(), false,
                    prior == null ? null : prior.bestEffort);
        }

        private long id() {
            return allocation.allocationId();
        }

        private long ceilingBytes(DbaContext context) {
            BigInteger quotas = BigInteger.valueOf(fixed.quota).add(BigInteger.valueOf(assured.quota))
                    .add(BigInteger.valueOf(nonAssured.quota)).add(BigInteger.valueOf(bestEffort.quota));
            return quotas.min(context.capacityBytes(context.framePeriodNs())).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
        }
    }

    /** 每帧创建新 Service，已发布快照不再修改。记录阶段决策顺序与额度，供验收复核。 */
    public static final class Decision {
        private final Service service;
        private final boolean complete;

        private Decision(Service service, boolean complete) {
            this.service = service;
            this.complete = complete;
        }

        private Phase minPhase() {
            return service.profile.type() == 4 ? service.bestEffort : service.nonAssured;
        }

        public boolean complete() { return complete; }
        public DbaContext.Allocation allocation() { return service.allocation; }
        public long recoveryCeiling() { return service.recoveryCeiling; }
        public int siMaxBefore() { return service.assured.before; }
        public int siMaxAfter() { return service.assured.after(); }
        public int siMinBefore() { return minPhase().before; }
        public int siMinAfter() { return minPhase().after(); }
        public int siFixedBefore() { return service.fixed.before; }
        public int siFixedAfter() { return service.fixed.after(); }
        public int siBeBefore() { return service.profile.type() == 5 ? service.bestEffort.before : 0; }
        public int siBeAfter() { return service.profile.type() == 5 ? service.bestEffort.after() : 0; }
        public Reason assuredReason() { return service.assured.reason; }
        public Reason surplusReason() { return minPhase().reason; }
        public Reason fixedReason() { return service.fixed.reason; }
        public Reason nonAssuredReason() { return service.nonAssured.reason; }
        public Reason bestEffortReason() { return service.bestEffort.reason; }
        public long assuredBytes() { return service.assured.granted; }
        public long assuredWantedBytes() { return service.assured.wanted; }
        public long surplusBytes() { return nonAssuredBytes() + bestEffortBytes(); }
        public long fixedBytes() { return service.fixed.granted; }
        public long nonAssuredBytes() { return service.nonAssured.granted; }
        public long bestEffortBytes() { return service.bestEffort.granted; }
        public long budgetBytes() { return service.budgetBytes; }
        public long durationNs() { return service.durationNs; }
        public int fixedOrder() { return service.fixed.order; }
        public int assuredOrder() { return service.assured.order; }
        public int nonAssuredOrder() { return service.nonAssured.order; }
        public int bestEffortOrder() { return service.bestEffort.order; }
    }
}
