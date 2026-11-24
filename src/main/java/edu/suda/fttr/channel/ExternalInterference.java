package edu.suda.fttr.channel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 外部源的有限活动时间线。绝对功率分别在 AP 与关联 STA 接收端定义。 */
public final class ExternalInterference {
    private static final Power NO_INTERFERENCE = new Power(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
    private static final double DB_PER_NEPER = 10.0 / StrictMath.log(10.0);
    private final Map<Integer, List<NavigableMap<Long, Power>>> bySfu = new HashMap<>();
    private final double ccaThresholdDbm;
    private final List<Edge> edges;

    public ExternalInterference(double ccaThresholdDbm, List<Activity> activities) {
        if (!Double.isFinite(ccaThresholdDbm)) {
            throw new IllegalArgumentException("External CCA threshold must be finite dBm");
        }
        this.ccaThresholdDbm = ccaThresholdDbm;
        Map<Integer, List<NavigableMap<Long, List<Boundary>>>> input = new TreeMap<>();
        for (Activity activity : activities) {
            NavigableMap<Long, List<Boundary>> intervals = input.computeIfAbsent(activity.sfuId, unused -> timelines())
                    .get(activity.subchannel);
            intervals.computeIfAbsent(activity.startNs, unused -> new ArrayList<>()).add(new Boundary(activity, true));
            intervals.computeIfAbsent(activity.endNs, unused -> new ArrayList<>()).add(new Boundary(activity, false));
        }
        Map<Long, Set<Integer>> allEdges = new TreeMap<>();
        input.forEach((sfuId, channels) -> {
            List<NavigableMap<Long, Power>> output = timelines();
            bySfu.put(sfuId, output);
            for (int subchannel = 0; subchannel < ChannelReport.SUBCHANNEL_COUNT; subchannel++) {
                NavigableMap<Long, Power> power = output.get(subchannel);
                Set<Activity> active = new LinkedHashSet<>();
                channels.get(subchannel).forEach((time, boundaries) -> {
                    for (Boundary boundary : boundaries) {
                        if (boundary.start) {
                            active.add(boundary.activity);
                        } else {
                            active.remove(boundary.activity);
                        }
                    }
                    power.put(time, sumPower(active));
                    allEdges.computeIfAbsent(time, unused -> new TreeSet<>()).add(sfuId);
                });
            }
        });
        List<Edge> events = new ArrayList<>();
        allEdges.forEach((time, sfus) -> sfus.forEach(sfuId -> events.add(new Edge(sfuId, time))));
        edges = List.copyOf(events);
    }

    private static <T> List<NavigableMap<Long, T>> timelines() {
        List<NavigableMap<Long, T>> result = new ArrayList<>(ChannelReport.SUBCHANNEL_COUNT);
        for (int index = 0; index < ChannelReport.SUBCHANNEL_COUNT; index++) {
            result.add(new TreeMap<>());
        }
        return result;
    }

    public List<Edge> edges() { return edges; }

    /** 以 AP 接收到的外部能量判断四个 20 MHz 信道是否达到阈值。 */
    public int apBusyMask(int sfuId, long nowNs) {
        int mask = 0;
        for (int index = 0; index < ChannelReport.SUBCHANNEL_COUNT; index++) {
            if (powerAt(sfuId, index, nowNs).apDbm >= ccaThresholdDbm) {
                mask |= 1 << index;
            }
        }
        return mask;
    }

    /** 扫描只汇总 AP 本地的 CCA 能量，结果不包含 STA 下行质量。区间为 [fromNs,toNs)。 */
    public long apBusyDurationNs(int sfuId, int subchannel, long fromNs, long toNs) {
        ChannelReport.requireSubchannel(subchannel);
        if (fromNs < 0 || toNs < fromNs) {
            throw new IllegalArgumentException("Invalid AP CCA measurement interval");
        }
        List<NavigableMap<Long, Power>> channels = bySfu.get(sfuId);
        if (channels == null) {
            return 0;
        }
        NavigableMap<Long, Power> timeline = channels.get(subchannel);
        long cursor = fromNs;
        long busyNs = 0;
        boolean busy = powerAt(sfuId, subchannel, fromNs).apDbm >= ccaThresholdDbm;
        for (Map.Entry<Long, Power> edge : timeline.subMap(fromNs, false, toNs, false).entrySet()) {
            if (busy) {
                busyNs += edge.getKey() - cursor;
            }
            cursor = edge.getKey();
            busy = edge.getValue().apDbm >= ccaThresholdDbm;
        }
        return busy ? busyNs + (toNs - cursor) : busyNs;
    }

    /** 原始 SINR 已包含背景噪声和旧干扰。只将本时间线的新增功率加到分母一次。 */
    public double downlinkSinrDb(ChannelTruth.Change baseline, long nowNs) {
        double externalDbm = powerAt(baseline.sfuId(), baseline.subchannel(), nowNs).staDbm;
        return externalDbm == Double.NEGATIVE_INFINITY ? baseline.sinrDb()
                : baseline.sinrDb() - (sumDbm(baseline.noiseDbm(), externalDbm) - baseline.noiseDbm());
    }

    private Power powerAt(int sfuId, int subchannel, long nowNs) {
        List<NavigableMap<Long, Power>> channels = bySfu.get(sfuId);
        Map.Entry<Long, Power> state = channels == null ? null : channels.get(subchannel).floorEntry(nowNs);
        return state == null ? NO_INTERFERENCE : state.getValue();
    }

    private static Power sumPower(Set<Activity> active) {
        double apDbm = Double.NEGATIVE_INFINITY;
        double staDbm = Double.NEGATIVE_INFINITY;
        for (Activity activity : active) {
            apDbm = sumDbm(apDbm, activity.apPowerDbm);
            staDbm = sumDbm(staDbm, activity.staPowerDbm);
        }
        return new Power(apDbm, staDbm);
    }

    private static double sumDbm(double left, double right) {
        double max = Math.max(left, right);
        double min = Math.min(left, right);
        return min == Double.NEGATIVE_INFINITY ? max
                : max + DB_PER_NEPER * StrictMath.log1p(StrictMath.exp((min - max) / DB_PER_NEPER));
    }

    private static final class Power {
        private final double apDbm;
        private final double staDbm;

        private Power(double apDbm, double staDbm) {
            this.apDbm = apDbm;
            this.staDbm = staDbm;
        }
    }

    private static final class Boundary {
        private final Activity activity;
        private final boolean start;

        private Boundary(Activity activity, boolean start) {
            this.activity = activity;
            this.start = start;
        }
    }

    public static final class Activity {
        private final int sfuId;
        private final int subchannel;
        private final long startNs;
        private final long endNs;
        private final double apPowerDbm;
        private final double staPowerDbm;

        public Activity(int sfuId, int subchannel, long startNs, long endNs, double apPowerDbm, double staPowerDbm) {
            ChannelReport.requireSubchannel(subchannel);
            if (sfuId < 1 || startNs < 0 || endNs <= startNs || !Double.isFinite(apPowerDbm)
                    || !Double.isFinite(staPowerDbm)) {
                throw new IllegalArgumentException("Invalid external interference SFU, time interval or dBm power");
            }
            this.sfuId = sfuId;
            this.subchannel = subchannel;
            this.startNs = startNs;
            this.endNs = endNs;
            this.apPowerDbm = apPowerDbm;
            this.staPowerDbm = staPowerDbm;
        }
    }

    public static final class Edge {
        private final int sfuId;
        private final long timeNs;

        private Edge(int sfuId, long timeNs) {
            this.sfuId = sfuId;
            this.timeNs = timeNs;
        }

        public int sfuId() { return sfuId; }
        public long timeNs() { return timeNs; }
    }
}
