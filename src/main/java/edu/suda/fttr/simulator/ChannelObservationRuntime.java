package edu.suda.fttr.simulator;

import edu.suda.fttr.channel.ApChannelObservations;
import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.channel.ChannelTruth;
import edu.suda.fttr.config.ChannelObservationInput;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.trace.TraceLogger;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** 有限的信道观测时间线。信道真值只在仿真侧用于日志和 STA 测量。 */
final class ChannelObservationRuntime {
    private enum Kind { TRUTH, MEASURE, ARRIVAL, READ }

    private final ChannelTruth truth;
    private final List<Sfu> sfus;
    private final List<Sta> stations;
    private final TraceLogger trace;
    private final PriorityQueue<Pending> pending = new PriorityQueue<>(
            Comparator.comparingLong((Pending event) -> event.timeNs)
                    .thenComparing(event -> event.kind)
                    .thenComparingLong(event -> event.sequence));
    private long nextSequence;

    ChannelObservationRuntime(ChannelObservationInput input, List<Sfu> sfus, List<Sta> stations, TraceLogger trace) {
        this.truth = input.truth();
        this.sfus = List.copyOf(sfus);
        this.stations = List.copyOf(stations);
        this.trace = trace;
        for (ChannelTruth.Change change : truth.changes()) {
            schedule(change.effectiveAtNs(), Kind.TRUTH, () -> trace.recordChannelTruth(change));
        }
        for (ChannelObservationInput.Feedback request : input.feedback()) {
            schedule(request.measuredNs(), Kind.MEASURE, () -> measure(request));
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

    /** 完成已配置的后续观测。此时间线不推进协议时钟和统计窗口。 */
    void finish() {
        while (!pending.isEmpty()) {
            pending.remove().action.run();
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
        ChannelReport report = new ChannelReport(request.sfuId(), request.staId(), request.subchannel(),
                request.measuredNs(), request.arrivalNs(), request.validNs(), sample.sinrDb(), sample.cqi());
        trace.recordChannelFeedback(request.measuredNs(), request, "MEASURED", report);
        schedule(request.arrivalNs(), Kind.ARRIVAL, () -> {
            Sfu sfu = sfus.get(report.sfuId() - 1);
            ApChannelObservations.Receipt result = sfu.receiveChannelReport(report, request.arrivalNs());
            trace.recordChannelFeedback(request.arrivalNs(), request, result.name(), report);
        });
    }

    private void read(long timeNs) {
        for (Sta sta : stations) {
            Sfu sfu = sfus.get(sta.sfuId() - 1);
            for (int subchannel = 0; subchannel < ChannelReport.SUBCHANNEL_COUNT; subchannel++) {
                trace.recordChannelObservation(timeNs, sfu, sta.id(), subchannel);
            }
        }
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
