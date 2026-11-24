package edu.suda.fttr.simulator;

import edu.suda.fttr.channel.ApChannelObservations;
import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.channel.ChannelTruth;
import edu.suda.fttr.channel.ExternalInterference;
import edu.suda.fttr.config.ChannelObservationInput;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.trace.TraceLogger;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** 有限的信道观测时间线。信道真值只在仿真侧用于日志和 STA 测量。 */
final class ChannelObservationRuntime {
    private enum Kind { TRUTH, MEASURE, ARRIVAL, READ }

    private final ChannelTruth truth;
    private final List<Sfu> sfus;
    private final List<Sta> stations;
    private final TraceLogger trace;
    private final ChannelObservationInput input;
    private final ExternalInterference interference;
    private final SimulationContext context;
    private final ApEnergyModel energyModel;
    private final Map<Event, Runnable> management = new HashMap<>();
    private final Map<Integer, ChannelReport> pendingFeedback = new HashMap<>();
    private final List<ObservationStats> stats;
    private final PriorityQueue<Pending> pending = new PriorityQueue<>(
            Comparator.comparingLong((Pending event) -> event.timeNs)
                    .thenComparing(event -> event.kind)
                    .thenComparingLong(event -> event.sequence));
    private long nextSequence;

    ChannelObservationRuntime(ChannelObservationInput input, List<Sfu> sfus, List<Sta> stations, TraceLogger trace,
                              ExternalInterference interference, ApEnergyModel energyModel, SimulationContext context) {
        this.input = input;
        this.truth = input.truth();
        this.sfus = List.copyOf(sfus);
        this.stations = List.copyOf(stations);
        this.trace = trace;
        this.interference = interference;
        this.context = context;
        this.energyModel = energyModel;
        this.stats = sfus.stream().map(sfu -> new ObservationStats()).collect(java.util.stream.Collectors.toList());
        for (ChannelTruth.Change change : truth.changes()) {
            schedule(change.effectiveAtNs(), Kind.TRUTH, () -> trace.recordChannelTruth(change));
        }
        for (ChannelObservationInput.Feedback request : input.feedback()) {
            schedule(request.measuredNs(), Kind.MEASURE, () -> measure(request));
            if (request.airtimeNs() > 0) {
                scheduleManagement(request.txStartNs(), EventType.WIFI_CHANNEL_MANAGEMENT_START, request.sfuId(),
                        () -> startFeedback(request));
            }
        }
        for (ChannelObservationInput.Scan scan : input.scans()) {
            scheduleManagement(scan.startNs(), EventType.WIFI_CHANNEL_MANAGEMENT_START, scan.sfuId(),
                    () -> startScan(scan));
        }
        for (long timeNs : input.readTimesNs()) {
            schedule(timeNs, Kind.READ, () -> read(timeNs));
        }
    }

    /** 同刻真值、测量和报告先到。读取放到该时刻最后一个真实协议事件之后。 */
    void advanceBeforeProtocolEvent(long nextProtocolNs) {
        while (!pending.isEmpty()) {
            Pending event = pending.peek();
            if (event.timeNs > nextProtocolNs || (event.timeNs == nextProtocolNs && event.kind == Kind.READ)) {
                break;
            }
            pending.remove().action.run();
        }
    }

    void advanceThrough(long endNs) {
        while (!pending.isEmpty() && pending.peek().timeNs <= endNs) {
            pending.remove().action.run();
        }
    }

    /** 完成已配置的后续观测。此时间线不推进协议时钟和统计窗口。 */
    void finish() {
        while (!pending.isEmpty()) {
            pending.remove().action.run();
        }
        if (!management.isEmpty()) {
            throw new IllegalStateException("Channel management events remain after simulation end");
        }
        if (input.usesTimedReceiver() || input.passive()) {
            for (int index = 0; index < stats.size(); index++) {
                ObservationStats observation = stats.get(index);
                trace.recordChannelOverhead(index + 1, observation.feedbackNs, observation.scanNs,
                        observation.scanEnergyUj, observation.feedbackRxEnergyUj, observation.feedbackExtraEnergyUj);
            }
        }
    }

    private void measure(ChannelObservationInput.Feedback request) {
        ChannelTruth.Change sample = truth.stateAt(request.sfuId(), request.staId(), request.subchannel(),
                request.measuredNs()).orElse(null);
        if (sample == null) {
            trace.recordChannelFeedback(request.measuredNs(), request, "NO_TRUTH", null);
            return;
        }
        // 报告内容冻结于测量时刻。稍后投递时只传该记录，不能重查当时的实时真值。
        double measuredSinr = interference == null ? sample.sinrDb()
                : interference.downlinkSinrDb(sample, request.measuredNs());
        ChannelReport report = new ChannelReport(request.sfuId(), request.staId(), request.subchannel(),
                request.measuredNs(), request.arrivalNs(), request.validNs(), measuredSinr,
                measuredSinr == sample.sinrDb() ? sample.cqi() : ChannelReport.UNKNOWN_CQI);
        trace.recordChannelFeedback(request.measuredNs(), request, "MEASURED", report);
        if (request.airtimeNs() == 0) {
            schedule(request.arrivalNs(), Kind.ARRIVAL, () -> receiveFeedback(request, report));
        } else {
            pendingFeedback.put(request.id(), report);
        }
    }

    private void receiveFeedback(ChannelObservationInput.Feedback request, ChannelReport report) {
        String phase = "LOST";
        if (!request.lost()) {
            Sfu sfu = sfus.get(report.sfuId() - 1);
            ApChannelObservations.Receipt result = sfu.receiveChannelReport(report, request.arrivalNs());
            phase = result.name();
        }
        trace.recordChannelFeedback(request.arrivalNs(), request, phase, report);
    }

    private void startFeedback(ChannelObservationInput.Feedback request) {
        ChannelReport report = pendingFeedback.remove(request.id());
        if (report == null) {
            return; // 没有可测量的信道状态，已记录 NO_TRUTH。
        }
        Sfu sfu = sfus.get(request.sfuId() - 1);
        if (!sfu.beginChannelFeedback(context)) {
            trace.recordChannelFeedback(request.txStartNs(), request, "CCA_BUSY_DROP", report);
            return;
        }
        trace.recordChannelFeedback(request.txStartNs(), request, "TX_START", report);
        scheduleManagement(request.arrivalNs(), EventType.WIFI_CHANNEL_MANAGEMENT_END, request.sfuId(), () -> {
            ObservationStats observation = stats.get(request.sfuId() - 1);
            observation.feedbackNs += request.airtimeNs();
            ApCapabilityMode mode = context.apActivityMode(request.sfuId());
            if (mode != null && energyModel != null) {
                ApEnergyModel.Power power = energyModel.powerFor(mode);
                BigDecimal observedUs = BigDecimal.valueOf(
                        context.apActivityOverlapNs(request.sfuId(), request.txStartNs(), request.arrivalNs()), 3);
                observation.feedbackRxEnergyUj = observation.feedbackRxEnergyUj.add(power.rxW().multiply(observedUs));
                observation.feedbackExtraEnergyUj = observation.feedbackExtraEnergyUj
                        .add(power.rxW().subtract(power.idleW()).multiply(observedUs));
            }
            sfu.endChannelFeedback(context);
            receiveFeedback(request, report);
        });
    }

    private void startScan(ChannelObservationInput.Scan scan) {
        Sfu sfu = sfus.get(scan.sfuId() - 1);
        boolean accepted = sfu.canStartAuxScan();
        trace.recordChannelScan(scan.startNs(), scan, accepted ? "START" : "SKIP_MODE", 0, BigDecimal.ZERO);
        if (!accepted) {
            return;
        }
        scheduleManagement(scan.endNs(), EventType.WIFI_CHANNEL_MANAGEMENT_END, scan.sfuId(), () -> {
            long busy = interference == null ? 0 : interference.apBusyDurationNs(
                    scan.sfuId(), scan.subchannel(), scan.startNs(), scan.endNs());
            sfu.completeAuxScan(scan.subchannel(), scan.startNs(), scan.endNs(), busy, scan.expiresNs());
            ObservationStats observation = stats.get(scan.sfuId() - 1);
            observation.scanNs += scan.endNs() - scan.startNs();
            BigDecimal energyUj = input.scanPowerW()
                    .multiply(BigDecimal.valueOf(context.apActivityOverlapNs(scan.sfuId(), scan.startNs(), scan.endNs()), 3));
            observation.scanEnergyUj = observation.scanEnergyUj.add(energyUj);
            trace.recordChannelScan(scan.endNs(), scan, "END", busy, energyUj);
        });
    }

    private void read(long timeNs) {
        if (input.passive()) {
            for (Sfu sfu : sfus) {
                ObservationStats observation = stats.get(sfu.id() - 1);
                trace.recordPassiveChannel(timeNs, sfu, sfu.observedExternalMask(), observation.confirmed,
                        observation.retries, observation.dropped);
            }
        }
        for (Sta sta : stations) {
            Sfu sfu = sfus.get(sta.sfuId() - 1);
            for (int subchannel = 0; subchannel < ChannelReport.SUBCHANNEL_COUNT; subchannel++) {
                trace.recordChannelObservation(timeNs, sfu, sta.id(), subchannel);
            }
        }
    }

    void recordMacObservation(int sfuId, int confirmed, int retry, int dropped) {
        if (input.passive()) {
            ObservationStats observation = stats.get(sfuId - 1);
            observation.confirmed += confirmed;
            observation.retries += retry;
            observation.dropped += dropped;
        }
    }

    void executeManagement(Event event) {
        Runnable action = management.remove(event);
        if (action == null) {
            throw new IllegalStateException("Missing channel management action");
        }
        action.run();
    }

    private void scheduleManagement(long timeNs, EventType kind, int sfuId, Runnable action) {
        Event event = context.scheduleAfterTicks(timeNs - context.nowTicks(), kind, null, sfuId);
        management.put(event, action);
    }

    private static final class ObservationStats {
        private long feedbackNs;
        private long scanNs;
        private BigDecimal scanEnergyUj = BigDecimal.ZERO;
        private BigDecimal feedbackRxEnergyUj = BigDecimal.ZERO;
        private BigDecimal feedbackExtraEnergyUj = BigDecimal.ZERO;
        private long confirmed;
        private long retries;
        private long dropped;
    }

    private void schedule(long timeNs, Kind kind, Runnable action) {
        pending.add(new Pending(timeNs, kind, nextSequence++, action));
    }

    private static final class Pending {
        private final long timeNs;
        private final Kind kind;
        private final long sequence;
        private final Runnable action;

        private Pending(long timeNs, Kind kind, long sequence, Runnable action) {
            this.timeNs = timeNs;
            this.kind = kind;
            this.sequence = sequence;
            this.action = action;
        }
    }
}
