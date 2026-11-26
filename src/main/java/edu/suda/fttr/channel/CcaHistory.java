package edu.suda.fttr.channel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** AP 在固定时间窗内已经获得的外部 CCA 证据。每个时间片按可见子信道做并集。 */
public final class CcaHistory {
    private static final int BANDS = ChannelReport.SUBCHANNEL_COUNT;
    private static final int WIDE_MASK = (1 << BANDS) - 1;
    // 与 C3 的未知忙碌先验一致。剩余未观测时长仍单列，并用 0~100% 给出识别边界。
    private static final double UNKNOWN_EXPECTED = 0.25;
    private static final double UNKNOWN_CONSERVATIVE = 0.50;

    public enum Current { IDLE, BUSY, UNKNOWN }

    private final long windowNs;
    private final Deque<Reading> passive = new ArrayDeque<>();
    private final List<Scan> scans = new ArrayList<>();
    private long sinceNs;
    private int sensed, busy;

    public CcaHistory(long windowNs, int initialSensed) {
        if (windowNs <= 0) {
            throw new IllegalArgumentException("CCA history window must be positive");
        }
        this.windowNs = windowNs;
        this.sensed = initialSensed & WIDE_MASK;
    }

    public void listen(long nowNs, int sensedMask, int busyMask) {
        if (nowNs < sinceNs) {
            throw new IllegalArgumentException("CCA time cannot move backwards");
        }
        int nextSensed = sensedMask & WIDE_MASK;
        int nextBusy = busyMask & nextSensed;
        if (nextSensed != sensed || nextBusy != busy) {
            if (sinceNs < nowNs) {
                passive.addLast(new Reading(sinceNs, nowNs, sensed, busy));
            }
            sinceNs = nowNs;
            sensed = nextSensed;
            busy = nextBusy;
        }
        evict(nowNs);
    }

    /** 一次扫描只上报所扫的 20 MHz。测量在完成事件后才可使用，到期后不再填补缺口。 */
    public void completeScan(int band, long fromNs, long endNs, long expiresNs, List<BusyInterval> intervals) {
        ChannelReport.requireSubchannel(band);
        if (band == 0 || fromNs < 0 || endNs <= fromNs || expiresNs <= endNs) {
            throw new IllegalArgumentException("Invalid CCA scan interval");
        }
        long previousEnd = fromNs;
        for (BusyInterval span : intervals) {
            if (span.fromNs < previousEnd || span.endNs > endNs) {
                throw new IllegalArgumentException("Scan busy interval is outside coverage or out of order");
            }
            previousEnd = span.endNs;
        }
        scans.add(new Scan(1 << band, fromNs, endNs, expiresNs, List.copyOf(intervals)));
    }

    public Snapshot sample(long nowNs) {
        if (nowNs < sinceNs) {
            throw new IllegalArgumentException("CCA query must follow the most recent observation");
        }
        evict(nowNs);
        long from = Math.max(0, nowNs - windowNs);
        TreeMap<Long, int[]> edges = new TreeMap<>();
        edges.put(from, new int[2 * BANDS]);
        edges.put(nowNs, new int[2 * BANDS]);
        for (Reading sample : passive) {
            addInterval(edges, sample.fromNs, sample.endNs, sample.sensed, sample.busy, from, nowNs);
        }
        addInterval(edges, sinceNs, nowNs, sensed, busy, from, nowNs);
        for (Scan scan : scans) {
            if (scan.endNs > nowNs) {
                continue;
            }
            addInterval(edges, scan.fromNs, scan.endNs, scan.mask, 0, from, nowNs);
            for (BusyInterval span : scan.intervals) {
                addInterval(edges, span.fromNs, span.endNs, 0, scan.mask, from, nowNs);
            }
        }

        Accumulator primary = new Accumulator(1);
        Accumulator wide = new Accumulator(WIDE_MASK);
        int[] levels = new int[2 * BANDS];
        long[] lastSeen = new long[BANDS];
        Arrays.fill(lastSeen, -1);
        long last = from;
        for (Map.Entry<Long, int[]> edge : edges.entrySet()) {
            int visibleMask = mask(levels, 0);
            int busyMask = mask(levels, BANDS);
            primary.add(last, edge.getKey(), visibleMask, busyMask);
            wide.add(last, edge.getKey(), visibleMask, busyMask);
            for (int band = 0; band < BANDS; band++) {
                if (last < edge.getKey() && (visibleMask & (1 << band)) != 0) {
                    lastSeen[band] = edge.getKey();
                }
            }
            for (int field = 0; field < levels.length; field++) {
                levels[field] += edge.getValue()[field];
            }
            last = edge.getKey();
        }
        return new Snapshot(windowNs, from, primary.finish(nowNs, lastSeen), wide.finish(nowNs, lastSeen));
    }

    private void evict(long nowNs) {
        long from = Math.max(0, nowNs - windowNs);
        while (!passive.isEmpty() && passive.getFirst().endNs <= from) {
            passive.removeFirst();
        }
        scans.removeIf(scan -> scan.endNs <= from || scan.expiresNs <= nowNs);
    }

    private static void addInterval(Map<Long, int[]> edges, long start, long end, int sensed, int busy,
                                    long clipFrom, long clipEnd) {
        long from = Math.max(start, clipFrom), until = Math.min(end, clipEnd);
        if (until <= from) {
            return;
        }
        int[] begin = edges.computeIfAbsent(from, unused -> new int[2 * BANDS]);
        int[] finish = edges.computeIfAbsent(until, unused -> new int[2 * BANDS]);
        for (int band = 0; band < BANDS; band++) {
            if ((sensed & (1 << band)) != 0) {
                begin[band]++;
                finish[band]--;
            }
            if ((busy & (1 << band)) != 0) {
                begin[band + BANDS]++;
                finish[band + BANDS]--;
            }
        }
    }

    private static int mask(int[] counts, int offset) {
        int mask = 0;
        for (int band = 0; band < BANDS; band++) {
            if (counts[band + offset] > 0) {
                mask |= 1 << band;
            }
        }
        return mask;
    }

    private static Current classify(int wanted, int sensed, int busy) {
        if ((busy & wanted) != 0) {
            return Current.BUSY;
        }
        return (sensed & wanted) == wanted ? Current.IDLE : Current.UNKNOWN;
    }

    private final class Accumulator {
        private final int wanted;
        private long busyNs, knownNs, fullySensedNs, currentRunNs, longestRunNs;
        private long latestKnownNs = -1;

        private Accumulator(int wanted) { this.wanted = wanted; }

        private void add(long fromNs, long untilNs, int visible, int busyMask) {
            long duration = untilNs - fromNs;
            if (duration == 0) {
                return;
            }
            Current state = classify(wanted, visible, busyMask);
            if ((visible & wanted) == wanted) {
                fullySensedNs += duration;
            }
            if (state != Current.UNKNOWN) {
                knownNs += duration;
                latestKnownNs = untilNs;
            }
            if (state == Current.BUSY) {
                busyNs += duration;
                currentRunNs += duration;
                longestRunNs = Math.max(longestRunNs, currentRunNs);
            } else {
                currentRunNs = 0;
            }
        }

        private Estimate finish(long nowNs, long[] lastSeen) {
            Current current = classify(wanted, sensed, busy);
            long oldestBand = nowNs;
            for (int band = 0; band < BANDS; band++) {
                if ((wanted & (1 << band)) != 0) {
                    oldestBand = Math.min(oldestBand, lastSeen[band]);
                }
            }
            return new Estimate(windowNs, busyNs, knownNs, fullySensedNs,
                    latestKnownNs < 0 ? -1 : nowNs - latestKnownNs,
                    oldestBand < 0 ? -1 : nowNs - oldestBand,
                    current, current == Current.BUSY ? currentRunNs : 0, longestRunNs);
        }
    }

    public static final class Estimate {
        public final long windowNs, busyNs, knownNs, fullySensedNs, ageNs, oldestBandAgeNs, ongoingBusyNs, longestBusyNs;
        public final Current current;

        private Estimate(long windowNs, long busyNs, long knownNs, long fullySensedNs, long ageNs, long oldestBandAgeNs,
                         Current current, long ongoingBusyNs, long longestBusyNs) {
            this.windowNs = windowNs;
            this.busyNs = busyNs;
            this.knownNs = knownNs;
            this.fullySensedNs = fullySensedNs;
            this.ageNs = ageNs;
            this.oldestBandAgeNs = oldestBandAgeNs;
            this.current = current;
            this.ongoingBusyNs = ongoingBusyNs;
            this.longestBusyNs = longestBusyNs;
        }

        public double coverage() { return (double) knownNs / windowNs; }
        public double lower() { return (double) busyNs / windowNs; }
        public double upper() { return lower() + (1 - coverage()); }
        public double expected() { return lower() + UNKNOWN_EXPECTED * (1 - coverage()); }
        public double conservative() { return lower() + UNKNOWN_CONSERVATIVE * (1 - coverage()); }
        public boolean incomplete() { return coverage() < 0.8 || ageNs < 0 || ageNs > windowNs / 2; }

        /** 非同步扫描无法补齐整段并集，但每个频段的近期证据仍允许比较带上下界的预测。 */
        public boolean missingRecentBand() { return oldestBandAgeNs < 0 || oldestBandAgeNs > windowNs / 2; }
    }

    public static final class Snapshot {
        public final long windowNs, fromNs;
        public final Estimate primary, wide;

        private Snapshot(long windowNs, long fromNs, Estimate primary, Estimate wide) {
            this.windowNs = windowNs;
            this.fromNs = fromNs;
            this.primary = primary;
            this.wide = wide;
        }
    }

    public static final class BusyInterval {
        public final long fromNs, endNs;

        public BusyInterval(long fromNs, long endNs) {
            if (fromNs < 0 || endNs <= fromNs) {
                throw new IllegalArgumentException("Invalid busy interval");
            }
            this.fromNs = fromNs;
            this.endNs = endNs;
        }
    }

    private static final class Reading {
        private final long fromNs, endNs;
        private final int sensed, busy;

        private Reading(long fromNs, long endNs, int sensed, int busy) {
            this.fromNs = fromNs;
            this.endNs = endNs;
            this.sensed = sensed;
            this.busy = busy;
        }
    }

    private static final class Scan {
        private final int mask;
        private final long fromNs, endNs, expiresNs;
        private final List<BusyInterval> intervals;

        private Scan(int mask, long fromNs, long endNs, long expiresNs, List<BusyInterval> intervals) {
            this.mask = mask;
            this.fromNs = fromNs;
            this.endNs = endNs;
            this.expiresNs = expiresNs;
            this.intervals = intervals;
        }
    }
}
