package edu.suda.fttr.channel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 一台 SFU 的反馈收件箱。它只持有已经实际到达的 STA 报告。 */
public final class ApChannelObservations {
    public enum Receipt { ACCEPTED, EXPIRED, STALE }

    public enum Visibility { VISIBLE, UNKNOWN, EXPIRED, MODE_LIMIT }

    public enum LocalReception { UL_DATA, DL_ACK, DL_BLOCK_ACK }

    private final int sfuId;
    private final Map<Integer, StationState> byStation = new HashMap<>();
    private long lastArrivalNs;

    public ApChannelObservations(int sfuId, Set<Integer> stationIds) {
        if (sfuId <= 0 || stationIds.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("AP observation link identifiers must be positive");
        }
        this.sfuId = sfuId;
        for (int staId : stationIds) {
            byStation.put(staId, new StationState());
        }
    }

    public Receipt receive(ChannelReport report, long nowNs) {
        if (report.sfuId() != sfuId) {
            throw new IllegalArgumentException("Report destination does not match receiving SFU");
        }
        StationState station = requireStation(report.staId());
        if (nowNs != report.arrivedAtNs() || nowNs < lastArrivalNs) {
            throw new IllegalArgumentException("Channel reports must be processed in order at their arrival time");
        }
        lastArrivalNs = nowNs;
        if (!report.isUsableAt(nowNs)) {
            return Receipt.EXPIRED;
        }
        ChannelReport previous = station.reports[report.subchannel()];
        if (previous != null && previous.measuredAtNs() >= report.measuredAtNs()) {
            return Receipt.STALE;
        }
        // 保留最新接受的测量时刻，即使它日后过期，迟到的旧样本也不能覆盖它。
        station.reports[report.subchannel()] = report;
        return Receipt.ACCEPTED;
    }

    /** 模式限制的子信道始终返回 MODE_LIMIT。只有已到达的测量才可判定为过期。 */
    public Visibility visibility(int staId, int subchannel, int visibleSubchannels, long nowNs) {
        ChannelReport.requireSubchannel(subchannel);
        StationState station = requireStation(staId);
        requireQueryTime(nowNs);
        if (visibleSubchannels != 1 && visibleSubchannels != 2 && visibleSubchannels != 4) {
            throw new IllegalArgumentException("AP observation bandwidth must cover 1, 2 or 4 subchannels");
        }
        if (subchannel >= visibleSubchannels) {
            return Visibility.MODE_LIMIT;
        }
        ChannelReport report = station.reports[subchannel];
        if (report == null || nowNs < report.arrivedAtNs()) {
            return Visibility.UNKNOWN;
        }
        return report.isUsableAt(nowNs) ? Visibility.VISIBLE : Visibility.EXPIRED;
    }

    /** 只返回当前能力范围内、已经到达且尚未过期的最新观测。 */
    public Optional<ChannelReport> latest(int staId, int subchannel, int visibleSubchannels, long nowNs) {
        return visibility(staId, subchannel, visibleSubchannels, nowNs) == Visibility.VISIBLE
                ? Optional.of(requireStation(staId).reports[subchannel]) : Optional.empty();
    }

    /** 记录由本 AP 实际解码的数据或收到的确认。它们独立于 CQI/SINR 报告。 */
    public void recordLocalReception(int staId, LocalReception kind, long nowNs, int observedMpdus) {
        requireQueryTime(nowNs);
        int minMpdus = kind == LocalReception.DL_BLOCK_ACK ? 0 : 1;
        if (observedMpdus < minMpdus || (kind == LocalReception.DL_ACK && observedMpdus != 1)) {
            throw new IllegalArgumentException("Invalid locally observed MPDU count for " + kind);
        }
        StationState station = requireStation(staId);
        LocalEvidence previous = station.receptions[kind.ordinal()];
        if (previous != null && nowNs < previous.receivedAtNs()) {
            throw new IllegalArgumentException("AP local reception time cannot move backwards");
        }
        station.receptions[kind.ordinal()] = new LocalEvidence(kind, nowNs, observedMpdus);
    }

    public Optional<LocalEvidence> localReception(int staId, LocalReception kind, long nowNs) {
        requireQueryTime(nowNs);
        LocalEvidence evidence = requireStation(staId).receptions[kind.ordinal()];
        return evidence != null && evidence.receivedAtNs() <= nowNs ? Optional.of(evidence) : Optional.empty();
    }

    private static void requireQueryTime(long nowNs) {
        if (nowNs < 0) {
            throw new IllegalArgumentException("AP observation time cannot be negative");
        }
    }

    private StationState requireStation(int staId) {
        StationState station = byStation.get(staId);
        if (station == null) {
            throw new IllegalArgumentException("STA " + staId + " is not associated with SFU " + sfuId);
        }
        return station;
    }

    private static final class StationState {
        private final ChannelReport[] reports = new ChannelReport[ChannelReport.SUBCHANNEL_COUNT];
        private final LocalEvidence[] receptions = new LocalEvidence[LocalReception.values().length];
    }

    /** 最近一次本地接收的时间和数量。BA 能被接收但不确认任何 MPDU。 */
    public static final class LocalEvidence {
        private final LocalReception kind;
        private final long receivedAtNs;
        private final int observedMpdus;

        private LocalEvidence(LocalReception kind, long receivedAtNs, int observedMpdus) {
            this.kind = kind;
            this.receivedAtNs = receivedAtNs;
            this.observedMpdus = observedMpdus;
        }

        public LocalReception kind() { return kind; }
        public long receivedAtNs() { return receivedAtNs; }
        public int observedMpdus() { return observedMpdus; }
    }
}
