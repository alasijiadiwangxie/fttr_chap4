package edu.suda.fttr.energy;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Packet;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** C4 的端点实绩账本。其全网报文状态从不提供给在线预测器。 */
public final class ApQosLedger {
    private final ApAnalysisSettings settings;
    private final List<EnumMap<Packet.Direction, EnumMap<AccessCategory, Group>>> groups = new ArrayList<>();
    private final Map<Packet, Pending> unfinished = new HashMap<>();
    private boolean finished;

    public ApQosLedger(int sfuCount, ApAnalysisSettings settings) {
        this.settings = settings;
        for (int sfu = 1; sfu <= sfuCount; sfu++) {
            EnumMap<Packet.Direction, EnumMap<AccessCategory, Group>> ap = new EnumMap<>(Packet.Direction.class);
            for (Packet.Direction direction : Packet.Direction.values()) {
                EnumMap<AccessCategory, Group> classes = new EnumMap<>(AccessCategory.class);
                for (AccessCategory ac : AccessCategory.values()) {
                    classes.put(ac, new Group(sfu, direction, ac, settings.requirement(ac)));
                }
                ap.put(direction, classes);
            }
            groups.add(ap);
        }
    }

    public void generated(Packet packet, int sfuId, long nowNs) {
        if (!finished) {
            Group group = group(sfuId, packet);
            group.arrived++;
            unfinished.put(packet, new Pending(group, nowNs));
        }
    }

    public void delivered(Packet packet, long nowNs) {
        if (!finished) {
            Pending pending = take(packet);
            pending.group.delivered(nowNs - pending.generatedNs);
        }
    }

    public void dropped(Packet packet) {
        if (!finished) {
            take(packet).group.dropped++;
        }
    }

    private Pending take(Packet packet) {
        Pending pending = unfinished.remove(packet);
        if (pending == null) {
            throw new IllegalStateException("QoS found repeated or unknown terminal event for packet " + packet.id());
        }
        return pending;
    }

    private Group group(int sfuId, Packet packet) {
        return groups.get(sfuId - 1).get(packet.direction()).get(packet.type());
    }

    public void queueChanged(int sfuId, ApTrafficObserver.QueueEvent event) {
        Packet packet = event.packet();
        if (finished || packet.isUplink()) {
            return;
        }
        Group group = group(sfuId, packet);
        group.queued = event.queuePackets();
        group.inFlight = event.inFlightPackets();
        group.awaitingConfirm = event.awaitingConfirmPackets();
        Pending pending = unfinished.get(packet);
        if (pending == null) {
            return; // 端点交付和 MAC 确认可处于不同时间。
        }
        switch (event.kind()) {
            case ENQUEUED:
                pending.apArrivalNs = event.timeNs();
                pending.waiting = true;
                break;
            case REQUEUED:
                pending.waiting = true;
                break;
            case TX_START:
            case RETRANSMIT_START:
                pending.waiting = false;
                break;
            default:
                break;
        }
    }

    /** 最近一次 C1 采样作为积压增长的参照。 */
    public void markQueueSample(int sfuId, List<ApTrafficObserver.Observation> observations) {
        if (finished) {
            return;
        }
        for (ApTrafficObserver.Observation observation : observations) {
            if (observation.direction() == Packet.Direction.DL) {
                Group group = groups.get(sfuId - 1).get(Packet.Direction.DL).get(observation.category());
                group.lastSampleQueue = observation.queuePackets();
            }
        }
    }

    /** 截面以后继续运行全网，但不再回填已冻结的观测。 */
    public List<Snapshot> finish(long cutoffNs) {
        if (finished) {
            return List.of();
        }
        finished = true;
        for (Pending pending : unfinished.values()) {
            Group group = pending.group;
            group.incomplete++;
            if (cutoffNs - pending.generatedNs >= group.requirement.deadlineNs()) {
                group.overdue++;
            }
            if (pending.waiting) {
                group.headWaitNs = Math.max(group.headWaitNs, cutoffNs - pending.apArrivalNs);
            }
        }
        List<Snapshot> result = new ArrayList<>();
        for (EnumMap<Packet.Direction, EnumMap<AccessCategory, Group>> ap : groups) {
            for (EnumMap<AccessCategory, Group> classes : ap.values()) {
                for (Group group : classes.values()) {
                    result.add(new Snapshot(cutoffNs, group));
                }
            }
        }
        unfinished.clear();
        return List.copyOf(result);
    }

    private static final class Pending {
        private final Group group;
        private final long generatedNs;
        private long apArrivalNs;
        private boolean waiting;

        private Pending(Group group, long generatedNs) {
            this.group = group;
            this.generatedNs = generatedNs;
        }
    }

    private static final class Group {
        private final int sfuId;
        private final Packet.Direction direction;
        private final AccessCategory ac;
        private final ApAnalysisSettings.Requirement requirement;
        private final List<Long> delays = new ArrayList<>();
        private long arrived;
        private long dropped;
        private long onTime;
        private long incomplete;
        private long overdue;
        private long queued;
        private long inFlight;
        private long awaitingConfirm;
        private long lastSampleQueue;
        private long headWaitNs = -1;
        private long previousDelay;
        private double delaySum;
        private double successiveDifferenceSum;

        private Group(int sfuId, Packet.Direction direction, AccessCategory ac, ApAnalysisSettings.Requirement requirement) {
            this.sfuId = sfuId;
            this.direction = direction;
            this.ac = ac;
            this.requirement = requirement;
        }

        private void delivered(long delayNs) {
            if (!delays.isEmpty()) {
                successiveDifferenceSum += Math.abs(delayNs - previousDelay);
            }
            previousDelay = delayNs;
            delays.add(delayNs);
            delaySum += delayNs;
            if (delayNs <= requirement.deadlineNs()) {
                onTime++;
            }
        }
    }

    public static final class Snapshot {
        public final long cutoffNs, deadlineNs, arrived, delivered, dropped, incomplete, overdue, onTime, violations,
                decided, p95Ns, p99Ns, queuePackets, inflightPackets, awaitingConfirmPackets, headWaitNs, backlogDelta;
        public final int sfuId;
        public final Packet.Direction direction;
        public final AccessCategory ac;
        public final double avgNs, jitterNs, onTimeRate, violationRate, lossRate;

        private Snapshot(long cutoffNs, Group group) {
            this.cutoffNs = cutoffNs;
            sfuId = group.sfuId;
            direction = group.direction;
            ac = group.ac;
            deadlineNs = group.requirement.deadlineNs();
            arrived = group.arrived;
            delivered = group.delays.size();
            dropped = group.dropped;
            incomplete = group.incomplete;
            overdue = group.overdue;
            if (arrived != delivered + dropped + incomplete) {
                throw new IllegalStateException("QoS packet conservation failed");
            }
            onTime = group.onTime;
            violations = delivered - onTime + dropped + overdue;
            decided = delivered + dropped + overdue;
            avgNs = delivered == 0 ? -1 : group.delaySum / delivered;
            group.delays.sort(Long::compareTo);
            p95Ns = nearestRank(group.delays, 0.95);
            p99Ns = nearestRank(group.delays, 0.99);
            jitterNs = delivered < 2 ? 0 : group.successiveDifferenceSum / (delivered - 1);
            onTimeRate = ratio(onTime, decided);
            violationRate = ratio(violations, decided);
            lossRate = ratio(dropped, arrived);
            queuePackets = direction == Packet.Direction.DL ? group.queued : -1;
            inflightPackets = direction == Packet.Direction.DL ? group.inFlight : -1;
            awaitingConfirmPackets = direction == Packet.Direction.DL ? group.awaitingConfirm : -1;
            headWaitNs = group.headWaitNs;
            backlogDelta = direction == Packet.Direction.DL ? group.queued - group.lastSampleQueue : -1;
        }

        private static long nearestRank(List<Long> sorted, double probability) {
            return sorted.isEmpty() ? -1 : sorted.get((int) Math.ceil(sorted.size() * probability) - 1);
        }

        private static double ratio(long numerator, long denominator) {
            return denominator == 0 ? -1 : (double) numerator / denominator;
        }
    }
}
