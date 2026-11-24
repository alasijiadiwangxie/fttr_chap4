package edu.suda.fttr.channel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** 完整场景的信道真值时序。由仿真事件层持有，供测量及离线核查。 */
public final class ChannelTruth {
    /** 旧场景没有绝对噪声输入时使用的合成背景功率。 */
    public static final double DEFAULT_NOISE_DBM = -95.0;
    private final List<Change> changes;
    private final Map<LinkSubchannel, NavigableMap<Long, Change>> bySubchannel = new HashMap<>();

    public ChannelTruth(List<Change> changes) {
        List<Change> ordered = new ArrayList<>(changes);
        ordered.sort(Comparator.comparingLong(Change::effectiveAtNs).thenComparingInt(Change::sfuId)
                .thenComparingInt(Change::staId).thenComparingInt(Change::subchannel));
        this.changes = List.copyOf(ordered);
        for (Change change : ordered) {
            LinkSubchannel key = new LinkSubchannel(change.sfuId(), change.staId(), change.subchannel());
            NavigableMap<Long, Change> timeline = bySubchannel.computeIfAbsent(key, unused -> new TreeMap<>());
            if (timeline.putIfAbsent(change.effectiveAtNs(), change) != null) {
                throw new IllegalArgumentException("Duplicate channel truth change for SFU " + change.sfuId()
                        + ", STA " + change.staId() + ", subchannel " + change.subchannel()
                        + " at " + change.effectiveAtNs() + " ns");
            }
        }
    }

    /** 按生效时刻、SFU、STA、子信道排序，供仿真层导出真值事件。 */
    public List<Change> changes() {
        return changes;
    }

    /** 读取给定时刻已经生效的值。第一条记录生效前该子信道未知。 */
    public Optional<Change> stateAt(int sfuId, int staId, int subchannel, long timeNs) {
        ChannelReport.requireLink(sfuId, staId, subchannel);
        if (timeNs < 0) {
            throw new IllegalArgumentException("Channel truth time cannot be negative");
        }
        NavigableMap<Long, Change> timeline = bySubchannel.get(new LinkSubchannel(sfuId, staId, subchannel));
        Map.Entry<Long, Change> state = timeline == null ? null : timeline.floorEntry(timeNs);
        return state == null ? Optional.empty() : Optional.of(state.getValue());
    }

    /** 一个链路子信道的阶梯状态。从生效时刻起保持到下一次状态变化。 */
    public static final class Change {
        private final int sfuId;
        private final int staId;
        private final int subchannel;
        private final long effectiveAtNs;
        private final double sinrDb;
        private final int cqi;
        private final boolean interfered;
        private final double noiseDbm;

        public Change(int sfuId, int staId, int subchannel, long effectiveAtNs,
                      double sinrDb, int cqi, boolean interfered) {
            this(sfuId, staId, subchannel, effectiveAtNs, sinrDb, cqi, interfered, DEFAULT_NOISE_DBM);
        }

        public Change(int sfuId, int staId, int subchannel, long effectiveAtNs,
                      double sinrDb, int cqi, boolean interfered, double noiseDbm) {
            ChannelReport.requireLink(sfuId, staId, subchannel);
            ChannelReport.requireQuality(sinrDb, cqi);
            if (effectiveAtNs < 0) {
                throw new IllegalArgumentException("Channel truth time cannot be negative");
            }
            if (!Double.isFinite(noiseDbm)) {
                throw new IllegalArgumentException("Channel truth background noise must be finite dBm");
            }
            this.sfuId = sfuId;
            this.staId = staId;
            this.subchannel = subchannel;
            this.effectiveAtNs = effectiveAtNs;
            this.sinrDb = sinrDb;
            this.cqi = cqi;
            this.interfered = interfered;
            this.noiseDbm = noiseDbm;
        }

        public int sfuId() { return sfuId; }
        public int staId() { return staId; }
        public int subchannel() { return subchannel; }
        public long effectiveAtNs() { return effectiveAtNs; }
        public double sinrDb() { return sinrDb; }
        public int cqi() { return cqi; }
        public boolean interfered() { return interfered; }
        public double noiseDbm() { return noiseDbm; }
    }

    private static final class LinkSubchannel {
        private final int sfuId;
        private final int staId;
        private final int subchannel;

        private LinkSubchannel(int sfuId, int staId, int subchannel) {
            this.sfuId = sfuId;
            this.staId = staId;
            this.subchannel = subchannel;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof LinkSubchannel)) {
                return false;
            }
            LinkSubchannel key = (LinkSubchannel) other;
            return sfuId == key.sfuId && staId == key.staId && subchannel == key.subchannel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sfuId, staId, subchannel);
        }
    }
}
