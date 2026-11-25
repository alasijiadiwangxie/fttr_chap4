package edu.suda.fttr.simulator;

import edu.suda.fttr.channel.ApChannelObservations.LocalReception;
import edu.suda.fttr.channel.ExternalInterference;
import edu.suda.fttr.config.ChannelObservationInput;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.channel.AuxChannelScan;
import edu.suda.fttr.energy.ApDeadlineModePolicy;
import edu.suda.fttr.energy.ApEnergyModePolicy;
import edu.suda.fttr.energy.ApModePredictor;
import edu.suda.fttr.energy.ApQosLedger;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApActivity;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeController;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.mac.EdcaAccess.ApPhase;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.entity.PonDbaShadowPlanner;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.link.PonReportPoll;
import edu.suda.fttr.link.PonDbaGrant;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.GiantDbaPolicy;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.phy.DownlinkDecoder.ChannelSample;
import edu.suda.fttr.phy.SinrPerModel;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.trace.TraceLogger;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 每次运行使用独立的事件队列、整数时钟和日志。 */
public final class SimulationContext {
    private final EventQueue events = new EventQueue();
    private final TraceLogger trace;
    private final boolean multiPacket;
    private final boolean nanosecondClock;
    private final boolean wifiRetryEnabled;
    private final Mfu demandObserver;
    private final EnumMap<AccessCategory, DelayStats> byClass = new EnumMap<>(AccessCategory.class);
    private final DelayStats overallDelay = new DelayStats();
    private final Map<Integer, UplinkStats> uplinkByStation = new HashMap<>();
    private final Map<Integer, ApActivity> apActivities = new HashMap<>();
    private final Map<Integer, ApTrafficObserver> apObservers = new LinkedHashMap<>();
    private final Map<Integer, PredictionTrack> apPredictors = new LinkedHashMap<>();
    private final Map<Integer, List<ApModePredictor.Forecast>> latestForecasts = new HashMap<>();
    private ApQosLedger qosLedger;
    private long qosCutoffNs;
    private boolean apActivityTraceEnabled;
    private long apObservationPeriodNs;
    private long lastApSampleNs;
    private long modeDecisionPeriodNs;
    private long nextModeDecisionNs;
    private long modeDecisionEndNs;
    private ApEnergyModePolicy energyModePolicy;
    private ApDeadlineModePolicy deadlineModePolicy;
    private long energyPredictionWindowNs;
    private ChannelObservationRuntime channelObservation;
    private boolean causalChannelSampling;
    private long nowTicks;
    private int deliveredPackets;
    private long deliveredBytes;
    private long lastDeliveryDelayTicks;
    private long wifiBusyTicks;
    private long wifiBusySinceTicks;
    private int activeRadios;
    private long wifiCollisionEvents;
    private int wifiCollisionDroppedPackets;
    private long wifiCollisionDroppedBytes;
    private long wifiRetransmissions;
    private long wifiAckTimeouts;
    private int wifiRetryLimitDrops;
    private int wifiTxops;
    private int wifiTxopPackets;
    private double totalWifiTxopDurationUs;

    SimulationContext(TraceLogger trace, boolean multiPacket, boolean nanosecondClock) {
        this(trace, multiPacket, nanosecondClock, false);
    }

    SimulationContext(TraceLogger trace, boolean multiPacket, boolean nanosecondClock, boolean wifiRetryEnabled) {
        this(trace, multiPacket, nanosecondClock, wifiRetryEnabled, null);
    }

    SimulationContext(TraceLogger trace, boolean multiPacket, boolean nanosecondClock, boolean wifiRetryEnabled,
                      Mfu demandObserver) {
        this.trace = trace.withNanosecondClock(nanosecondClock);
        this.multiPacket = multiPacket;
        this.nanosecondClock = nanosecondClock;
        this.wifiRetryEnabled = wifiRetryEnabled;
        this.demandObserver = demandObserver;
        for (AccessCategory type : AccessCategory.values()) {
            byClass.put(type, new DelayStats());
        }
    }

    public long nowTicks() {
        return nowTicks;
    }

    /** 当前协议事件的绝对时间，转换时不改变原协议时钟的精度。 */
    public long nowNanoseconds() {
        return observationTimeNs(nowTicks);
    }

    /** 先用整数刻度做时间差，再换算到统计所用的微秒。 */
    public double ticksToMicroseconds(long ticks) {
        return nanosecondClock ? ticks / 1_000.0 : (double) ticks;
    }

    public long elapsedSinceGenerationTicks(Packet packet) {
        return nowTicks - microsecondsToTicks(packet.generationTimeUs());
    }

    void scheduleInputEvent(Event event) {
        long timeTicks = microsecondsToTicks(event.timeUs());
        if (timeTicks < nowTicks) {
            throw new IllegalArgumentException("Input event time must not precede the simulation clock");
        }
        // 手动和泊松输入仍用整数微秒，入队时才统一到本次运行的时钟刻度。
        events.add(nanosecondClock ? Event.atTicks(timeTicks, true, event.type(), event.packet(), event.sfuId()) : event);
    }

    /** 将整数微秒转换为当前运行使用的整数时钟刻度。 */
    public long microsecondsToTicks(long timeUs) {
        try {
            return nanosecondClock ? Math.multiplyExact(timeUs, 1_000) : timeUs;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Time in microseconds exceeds the nanosecond clock range", ex);
        }
    }

    public Event scheduleAfter(long delayUs, EventType type, Packet packet, int sfuId) {
        return scheduleAfterTicks(microsecondsToTicks(delayUs), type, packet, sfuId);
    }

    /** HE PPDU 和 PON 共享链路的时间直接使用纳秒刻度，保留小数微秒部分。 */
    public void scheduleAfterNanoseconds(long delayNs, EventType type, Packet packet, int sfuId) {
        if (!nanosecondClock) {
            throw new IllegalStateException("Nanosecond delays require a nanosecond clock");
        }
        scheduleAfterTicks(delayNs, type, packet, sfuId);
    }

    /** 使用当前仿真时钟的原始刻度安排事件。 */
    public Event scheduleAfterTicks(long delayTicks, EventType type, Packet packet, int sfuId) {
        Event event = Event.atTicks(eventTimeAfter(delayTicks, type), nanosecondClock, type, packet, sfuId);
        events.add(event);
        return event;
    }

    public void schedulePonUplinkReportReception(long propagationNs, Packet carrier, PonUplinkReport report) {
        if (!nanosecondClock) {
            throw new IllegalStateException("REPORT reception requires a nanosecond clock");
        }
        long timeNs = eventTimeAfter(propagationNs, EventType.PON_UPLINK_REPORT_RX);
        events.add(Event.uplinkReportReception(timeNs, carrier, report));
    }

    public void schedulePollEvent(long delayNs, EventType type, PonReportPoll poll) {
        if (!nanosecondClock) {
            throw new IllegalStateException("REPORT_ONLY requires a nanosecond clock");
        }
        events.add(Event.pollEvent(eventTimeAfter(delayNs, type), type, poll));
    }

    public void recordPollEvent(PonReportPoll poll, String action) {
        trace.recordPollEvent(poll, action, nowTicks);
    }

    public void recordPollSnapshot(PonReportPoll poll) {
        trace.recordPollSnapshot(poll);
    }

    public void recordPollReception(PonReportPoll poll, boolean applied) {
        trace.recordPollReception(poll, nowTicks, applied);
    }

    public void recordPollFrame(long frameId, long startNs, long endNs, long dataNs, long reportNs) {
        trace.recordPollFrame(frameId, startNs, endNs, dataNs, reportNs);
    }

    public void recordPollingSummary(long polls, long dataNs, long reportNs) {
        trace.recordPollingSummary(polls, dataNs, reportNs);
    }

    private long eventTimeAfter(long delayTicks, EventType type) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Event delay must be non-negative");
        }
        // 以当前事件发生的时刻为基准安排下一跳。零时延允许，但时钟不能倒退。
        try {
            return Math.addExact(nowTicks, delayTicks);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Event time exceeds the clock range: " + type, ex);
        }
    }

    public void cancel(Event event) {
        events.cancel(event);
    }

    public void scheduleWifiSelection(int sfuId) {
        events.add(Event.atTicks(nowTicks, nanosecondClock, EventType.WIFI_SELECT_NEXT, null, sfuId));
    }

    Event nextEvent() {
        scheduleModeDecision();
        Event event = events.poll();
        finishQosAtBoundary(event);
        if (!causalChannelSampling) {
            advanceChannelObservation(event);
        }
        if (apObservationPeriodNs > 0) {
            // 只用事件时钟推进采样。相同时间戳的全部协议事件（含新安排的零延时事件）先执行。
            // 观测不进入事件队列，也不改变自然结束时间、吞吐分母或能耗窗口。
            long throughNs = event == null ? observationTimeNs(nowTicks) : observationTimeNs(event.timeTicks()) - 1;
            while (throughNs - lastApSampleNs >= apObservationPeriodNs) {
                sampleApTraffic(lastApSampleNs + apObservationPeriodNs);
            }
            if (event == null && throughNs > lastApSampleNs) {
                sampleApTraffic(throughNs); // 自然结束后的最后一个非空短窗口。
            }
        }
        if (causalChannelSampling) {
            advanceChannelObservation(event);
        }
        if (event != null) {
            nowTicks = event.timeTicks();
            trace.recordEvent(event);
        }
        return event;
    }

    void trackApModeControl(long periodNs, long observationEndNs) {
        modeDecisionPeriodNs = periodNs;
        nextModeDecisionNs = periodNs;
        modeDecisionEndNs = observationEndNs;
    }

    private void scheduleModeDecision() {
        long decisionNs = nextModeDecisionNs;
        if (decisionNs == 0 || (modeDecisionEndNs > 0 && decisionNs >= modeDecisionEndNs)) {
            return;
        }
        Event next = events.peek();
        // 自然窗口只在有实际事件覆盖时推进决策；固定窗口则可观察尾部空载。
        if (next == null ? modeDecisionEndNs < 0 : next.timeTicks() < decisionNs) {
            return;
        }
        events.add(Event.atTicks(decisionNs, nanosecondClock, EventType.WIFI_AP_MODE_DECISION, null, 0));
        nextModeDecisionNs = decisionNs <= Long.MAX_VALUE - modeDecisionPeriodNs
                ? decisionNs + modeDecisionPeriodNs : 0;
    }

    private void advanceChannelObservation(Event event) {
        if (channelObservation == null) {
            return;
        }
        if (event == null) {
            channelObservation.finish();
        } else {
            channelObservation.advanceBeforeProtocolEvent(observationTimeNs(event.timeTicks()));
        }
    }

    void trackChannelObservation(ChannelObservationInput input, List<Sfu> sfus, List<Sta> stations,
                                 ExternalInterference interference, ApEnergyModel energyModel) {
        channelObservation = new ChannelObservationRuntime(input, sfus, stations, trace, interference, energyModel, this);
        if (interference != null || input.hasManagementObservations()) {
            enableCausalChannelSampling();
        }
    }

    void enableCausalChannelSampling() {
        causalChannelSampling = true;
    }

    void executeChannelManagement(Event event) {
        channelObservation.executeManagement(event);
    }

    public void recordChannelFeedbackActivity(int sfuId, boolean start) {
        if (start) {
            recordWifiStart();
        }
        recordApFrameBoundary(null, sfuId, false, start, start ? "FEEDBACK_RX_START" : "FEEDBACK_RX_END");
        if (!start) {
            recordWifiEnd();
        }
    }

    long apActivityOverlapNs(int sfuId, long startNs, long endNs) {
        ApActivity activity = apActivities.get(sfuId);
        return activity == null ? endNs - startNs : activity.observedDurationNs(startNs, endNs);
    }

    ApCapabilityMode apActivityMode(int sfuId) {
        ApActivity activity = apActivities.get(sfuId);
        return activity == null ? null : activity.mode();
    }

    public void recordChannelLocal(long timeNs, int sfuId, int staId, LocalReception kind, int mpdus) {
        trace.recordChannelLocal(timeNs, sfuId, staId, kind, mpdus);
    }

    public void recordExternalCca(int sfuId, int observedMask, int accessMask, boolean busy) {
        trace.recordExternalCca(nowNanoseconds(), sfuId, observedMask, accessMask, busy);
        PredictionTrack track = apPredictors.get(sfuId);
        if (track != null && track.passive && (!track.policy.usesPrediction() || track.predictChannel)) {
            track.advanceCca(nowNanoseconds());
            track.accessMask = accessMask;
            track.busyMask = observedMask & accessMask;
        }
    }

    void trackApAnalysis(SimulationConfig config, List<Sfu> sfus) {
        qosLedger = new ApQosLedger(sfus.size(), config.apAnalysis());
        qosCutoffNs = config.apActivityWindowNs();
        if (config.apControl().needsPrediction()) {
            energyModePolicy = new ApEnergyModePolicy(config.apEnergyModel(), config.apSwitching(),
                    config.apControl().energyMarginUj());
            energyPredictionWindowNs = config.apAnalysis().horizonNs();
        }
        if (config.apControl().policies().values().stream().anyMatch(ApModeController.Policy::usesQos)) {
            deadlineModePolicy = new ApDeadlineModePolicy(config.apAnalysis(), config.apSwitching(),
                    config.apControl().qosMarginNs(), config.apControl().deferralLimitNs());
        }
        if (!config.apAnalysis().predictionEnabled() && energyModePolicy == null) {
            return;
        }
        enableCausalChannelSampling();
        for (Sfu sfu : sfus) {
            ApModeController.Policy policy = config.apControl().policyFor(sfu.id());
            if (!policy.usesPrediction() && !config.apAnalysis().predictionEnabled()) {
                continue;
            }
            ApCapabilityMode mode = config.apCapabilityMode(sfu.id());
            Map<Integer, WirelessPhy> current = new TreeMap<>();
            Map<Integer, WirelessPhy> candidate = new TreeMap<>();
            for (int sta = 1; sta <= config.staCount(); sta++) {
                if (config.staSfuIds().get(sta - 1) == sfu.id()) {
                    current.put(sta, config.wirelessPhyForSta(sta));
                    candidate.put(sta, config.alternateWirelessPhyForSta(sta));
                }
            }
            var predictor = new ApModePredictor(config.apAnalysis(),
                    Map.of(mode, current, ApModeSwitch.otherMode(mode), candidate),
                    config.apEnergyModel(), config.apSwitching(), config.wifiFrameExchange(),
                    config.edcaParameters(), config.wifiRetryPolicy(), config.ampduConfig(),
                    config.downlinkDecoder() == null ? null : config.downlinkDecoder().observedPerCurve());
            boolean passive = config.channelObservation() != null && config.channelObservation().passive();
            boolean externalCca = config.externalInterference() != null;
            boolean predictChannel = policy.usesChannel() && config.downlinkDecoder() != null;
            // D2 的本地 CCA 历史只累计本机实际侦听到的带宽。次信道仍须通过扫描或实际驻留获得。
            passive |= policy.usesPrediction() && externalCca;
            apPredictors.put(sfu.id(), new PredictionTrack(sfu, predictor, passive, mode.bandwidthMhz(),
                    config.apAnalysis().revisedPrediction() || policy.usesPrediction(), policy, predictChannel, externalCca,
                    config.apControl().useLoadAwareService(policy)));
        }
    }

    void applyEnergyModeDecisions() {
        if (energyModePolicy == null) {
            return;
        }
        for (PredictionTrack track : apPredictors.values()) {
            if (track.policy.usesPrediction()) {
                boolean due = track.deferred != null && track.deferred.timer.timeTicks() == nowTicks;
                decideApMode(track, due ? "DEFER_EXPIRES" : "PERIODIC", true);
            }
        }
    }

    void applyQosRecheck(Event event) {
        PredictionTrack track = apPredictors.get(event.sfuId());
        if (event == track.recheck) {
            track.recheck = null;
            decideApMode(track, track.recheckReason, false);
        } else if (track.deferred != null && event == track.deferred.timer) {
            decideApMode(track, "DEFER_EXPIRES", false);
        }
    }

    private void decideApMode(PredictionTrack track, String trigger, boolean periodic) {
        long now = nowNanoseconds();
        if (track.lastDecisionNs == now) {
            return;
        }
        track.lastDecisionNs = now;
        int sfuId = track.sfu.id();
        long horizon = Math.min(energyPredictionWindowNs, Long.MAX_VALUE - now);
        ApTrafficObserver observer = apObservers.get(sfuId);
        ApActivity.Airtime airtime = apActivities.get(sfuId).airtimeAt(now);
        var visible = observer.preview(now, airtime.txNs(), airtime.rxNs(), track.policy.usesQos());
        var evidence = track.mac.snapshot(now, observer.waitingDownlinkPackets());
        var ready = track.sfu.modeSwitchReadiness(now, horizon);
        boolean useCca = track.predictChannel && track.externalCca;
        double[] busy = useCca ? track.sampleCca(now, periodic) : new double[] {0, 0};
        int projectedMask = (1 << (ready.projectedMode.bandwidthMhz() / 20)) - 1;
        boolean externalBusy = useCca && (track.sfu.observedExternalMask() & projectedMask) != 0;
        var forecasts = track.predictor.predictControlWork(now, ready.sourceMode, visible,
                track.sfu::candidateChannelReport, busy[0], busy[1], evidence, track.predictChannel,
                track.mixedMode || !periodic, track.loadAware);
        if (periodic) {
            // 紧急事件读取同一窗口已有证据。只有周期边界会消费该窗口并训练历史。
            track.mac.resetWindow();
            track.mixedMode = false;
        }
        var decision = energyModePolicy.choose(horizon, ready, externalBusy, forecasts);
        if (track.policy.usesQos()) {
            var fullReadiness = track.sfu.modeSwitchReadiness(now, Long.MAX_VALUE - now);
            var qos = deadlineModePolicy.choose(now, decision, fullReadiness, externalBusy);
            var outcome = applyQosModeTarget(track, trigger, qos, ready);
            trace.recordApEnergyModeDecision(now, sfuId, track.policy, visible, decision, null, externalBusy);
            trace.recordApQosModeDecision(now, sfuId, track.policy, trigger, qos, outcome);
        } else {
            var outcome = track.sfu.applyModeTarget(decision.target, track.policy, this);
            trace.recordApEnergyModeDecision(now, sfuId, track.policy, visible, decision, outcome, externalBusy);
        }
        observeApMediumAfterEvent(sfuId);
    }

    private ApModeSwitch.Outcome applyQosModeTarget(PredictionTrack track, String trigger,
                                                    ApDeadlineModePolicy.Decision qos, ApModeSwitch.Readiness ready) {
        long now = nowNanoseconds();
        boolean canDefer = track.policy == ApModeController.Policy.ECQ_SDPS && qos.target == ApCapabilityMode.HCM
                && ready.projectedMode == ApCapabilityMode.LCM && ready.activeSwitchNs == 0
                && !track.sfu.hasPendingModeTarget(ApCapabilityMode.HCM);
        DeferredHcm pending = track.deferred;
        if (canDefer) {
            long latest = now + Math.min(qos.maxDelayNs, Long.MAX_VALUE - now);
            if (modeDecisionEndNs > 0) {
                latest = Math.min(latest, modeDecisionEndNs);
            }
            long due = pending == null ? latest : Math.min(latest, pending.timer.timeTicks());
            if (due > now) {
                if (pending == null) {
                    Event timer = scheduleAfterTicks(due - now, EventType.WIFI_AP_QOS_RECHECK, null, track.sfu.id());
                    track.deferred = pending = new DeferredHcm(track.nextDeferralId++, now, timer);
                    traceDeferral(track, pending, "OPEN", trigger, qos.maxDelayNs, 0);
                } else if (due < pending.timer.timeTicks()) {
                    cancel(pending.timer);
                    pending.timer = scheduleAfterTicks(due - now, EventType.WIFI_AP_QOS_RECHECK, null, track.sfu.id());
                    traceDeferral(track, pending, "ADVANCE", trigger, qos.maxDelayNs, 0);
                } else {
                    traceDeferral(track, pending, "MERGE", trigger, qos.maxDelayNs, 0);
                }
                return null;
            }
        }

        track.deferred = null;
        if (pending != null) {
            cancel(pending.timer);
        }
        ApModeSwitch.Outcome outcome = track.sfu.applyModeTarget(qos.target, track.policy, this);
        if (pending != null) {
            String phase = qos.target == ApCapabilityMode.HCM && outcome.action() == ApModeSwitch.Action.REQUESTED
                    ? "RELEASE" : "CANCEL";
            traceDeferral(track, pending, phase, trigger, qos.maxDelayNs, outcome.requestId());
        }
        return outcome;
    }

    private void traceDeferral(PredictionTrack track, DeferredHcm pending, String phase, String trigger, long allowed, int switchId) {
        trace.recordApHcmDeferral(nowNanoseconds(), track.sfu.id(), pending.id, pending.openedNs,
                pending.timer.timeTicks(), phase, trigger, allowed, switchId);
    }

    void observeApMediumAfterEvent(int sfuId) {
        PredictionTrack track = apPredictors.get(sfuId);
        if (track != null && track.mac != null) {
            track.mac.mediumChanged(nowNanoseconds(), track.sfu.hasLocalChannelOwner(),
                    track.passive && track.sfu.observedExternalMask() != 0, track.sfu.observedApContentionPhase());
        }
    }

    public void recordPredictionExchange(Packet head, int sfuId, List<Packet> members, long bitmap,
                                         long startTicks, long dataEndTicks, long responseNs) {
        PredictionTrack track = apPredictors.get(sfuId);
        if (track != null && track.mac != null) {
            track.mac.complete(head, members, bitmap, startTicks, dataEndTicks, responseNs, nowNanoseconds());
        }
    }

    /** C3 发布的已完成快照，供后续读取。 */
    public List<ApModePredictor.Forecast> latestApModeForecasts(int sfuId) {
        return latestForecasts.getOrDefault(sfuId, List.of());
    }

    void recordQosGeneration(Packet packet, int sfuId) {
        if (qosLedger != null) {
            qosLedger.generated(packet, sfuId, nowNanoseconds());
        }
    }

    private void finishQosAtBoundary(Event next) {
        if (qosLedger != null && (next == null
                || (qosCutoffNs > 0 && observationTimeNs(next.timeTicks()) > qosCutoffNs))) {
            long endNs = qosCutoffNs > 0 ? qosCutoffNs : nowNanoseconds();
            for (ApQosLedger.Snapshot metrics : qosLedger.finish(endNs)) {
                trace.recordApQos(metrics);
            }
            qosLedger = null;
        }
    }

    public void recordDelivery(Packet packet, Sta sta) {
        recordDelivered(packet);
        trace.recordDelivery(packet, sta, nowTicks);
    }

    public void recordUplinkDelivery(Packet packet, int sfuId) {
        recordDelivered(packet);
        UplinkStats stats = uplinkByStation.computeIfAbsent(packet.staId(), id -> new UplinkStats());
        stats.packets++;
        stats.bytes += packet.bytes();
        stats.delaySumUs += ticksToMicroseconds(lastDeliveryDelayTicks);
        trace.recordUplinkDelivery(packet, sfuId, nowTicks, lastDeliveryDelayTicks);
    }

    private void recordDelivered(Packet packet) {
        deliveredPackets++;
        deliveredBytes += packet.bytes();
        lastDeliveryDelayTicks = elapsedSinceGenerationTicks(packet);
        if (qosLedger != null) {
            qosLedger.delivered(packet, nowNanoseconds());
        }
    }

    public void recordPonDownlinkEnqueue(Packet packet, int sfuId) {
        trace.recordPonDownlinkEnqueue(packet, sfuId, nowTicks);
    }

    public void recordPonUplinkEnqueue(Packet packet, int sfuId) {
        trace.recordPonUplinkEnqueue(packet, sfuId, nowTicks);
    }

    public void recordTcontEnqueue(Packet packet, long allocationId, int sfuId) {
        trace.recordTcontEnqueue(packet, allocationId, sfuId, nowTicks);
    }

    public void recordTcontDemandState(long allocationId, int sfuId, String trigger,
                                       PonDemandLedger.Estimate estimate) {
        trace.recordTcontDemandState(allocationId, sfuId, nowTicks, trigger, estimate);
    }

    public void recordTcontReportReceived(PonUplinkReport report, boolean applied, String kind) {
        trace.recordTcontReportReceived(report, nowTicks, applied, kind);
    }

    public void recordPonUplinkReport(PonUplinkReport report) {
        trace.recordPonUplinkReport(report);
    }

    public void recordPonUplinkReportReception(PonUplinkReport report, int carrierPacketId, boolean applied) {
        trace.recordPonUplinkReportReception(report, carrierPacketId, nowTicks, applied);
    }

    public void recordMfuReportState(long frameId, int sfuId, Mfu.ReceivedUplinkReport latest) {
        trace.recordMfuReportState(frameId, sfuId, nowTicks, latest);
    }

    public void recordPonUplinkReportSummary(long generated, long received, long withoutGrant) {
        trace.recordPonUplinkReportSummary(generated, received, withoutGrant);
    }

    public void recordPonUplinkGrant(Packet packet, int sfuId, long frameId, long enqueueNs, long startNs, long endNs) {
        trace.recordPonUplinkGrant(packet, sfuId, frameId, enqueueNs, nowTicks, startNs, endNs);
        if (demandObserver != null) {
            // 只观察预约创建。BWmap 接收、DATA 起发和交付均无需再次修改账本。
            demandObserver.recordUplinkGrantCreated(sfuId, packet, this);
        }
    }

    public void recordDemandLedgerGrant(int sfuId, int grantId, int grantBytes) {
        trace.recordDemandLedgerGrant(sfuId, grantId, nowTicks, grantBytes);
    }

    public void recordDemandLedgerState(int sfuId, String trigger, PonDemandLedger.Estimate estimate) {
        trace.recordDemandLedgerState(sfuId, nowTicks, trigger, estimate);
    }

    public void planDbaShadow(long targetFrameId, long controlReservedNs) {
        if (demandObserver != null) {
            demandObserver.planUplinkBudget(targetFrameId, controlReservedNs, this);
        }
    }

    public void recordDbaShadow(PonDbaShadowPlanner.Decision decision) {
        trace.recordDbaShadow(decision);
    }

    public void scheduleDbaEvent(long delayNs, EventType type, PonDbaGrant grant) {
        if (!nanosecondClock) {
            throw new IllegalStateException("Real DBA events require a nanosecond clock");
        }
        events.add(Event.dbaEvent(eventTimeAfter(delayNs, type), type, grant));
    }

    public void recordDbaDecision(DbaPlan plan, long controlNs) {
        trace.recordDbaDecision(plan, controlNs);
    }

    public void recordDbaMultiPlan(DbaPlan plan, long controlNs) {
        trace.recordDbaMultiPlan(plan, controlNs);
    }

    public void recordDbaObjectState(DbaContext input, DbaContext.Allocation allocation) {
        trace.recordDbaObjectState(input, allocation);
    }

    public void recordGiantDecision(DbaContext input, GiantDbaPolicy.Decision decision) {
        trace.recordGiantDecision(input, decision);
    }

    public void scheduleDbaDataEnd(long delayNs, Packet packet, PonDbaGrant grant) {
        if (!nanosecondClock) {
            throw new IllegalStateException("Real DBA DATA completion requires a nanosecond clock");
        }
        events.add(Event.dbaDataEnd(eventTimeAfter(delayNs, EventType.FIBER_TX_END), packet, grant));
    }

    public void recordDbaExecution(String action, PonDbaGrant grant, String details) {
        trace.recordDbaExecution(action, grant, nowTicks, details);
    }

    public void recordDbaSummary(long budgets, long grantedBytes, long actualBytes, long unusedBytes,
                                 long reservedNs, long actualNs, long pendingPackets) {
        trace.recordDbaSummary(budgets, grantedBytes, actualBytes, unusedBytes, reservedNs, actualNs, pendingPackets);
    }

    public void recordPonBwmap(Packet packet, int sfuId, long grantFrameId, long targetFrameId,
                               long grantTxNs, long startNs, long endNs, boolean received) {
        trace.recordPonBwmap(packet, sfuId, grantFrameId, targetFrameId, grantTxNs, startNs, endNs, nowTicks, received);
    }

    public void recordPonBwmapSummary(long packets, double ungrantedWaitUs, double reservedWaitUs,
                                      double serializationUs) {
        trace.recordPonBwmapSummary(packets, ungrantedWaitUs, reservedWaitUs, serializationUs);
    }

    public void recordPonUplinkFrame(long frameId, long frameStartNs, long frameEndNs, int packets, long busyNs) {
        trace.recordPonUplinkFrame(frameId, frameStartNs, frameEndNs, packets, busyNs);
    }

    public void recordPonUplinkFrames(long frames, long periodNs, long packets, long busyNs, double grantWaitUs) {
        trace.recordPonUplinkFrames(frames, periodNs, packets, busyNs, grantWaitUs);
    }

    public void recordPonSummary(Packet.Direction direction, long sentPackets, double totalQueueWaitTicks,
                                 long busyTicks) {
        // 分母沿用其他全局统计，以仿真起点到最后一个事件为窗口。
        double averageWaitUs = sentPackets == 0 ? 0.0 : totalQueueWaitTicks / sentPackets / 1_000.0;
        double averageSerializationUs = sentPackets == 0 ? 0.0 : ticksToMicroseconds(busyTicks) / sentPackets;
        double utilization = nowTicks == 0 ? 0.0 : (double) busyTicks / nowTicks;
        trace.recordPonSummary(direction, sentPackets, averageWaitUs, averageSerializationUs, busyTicks, nowTicks,
                utilization);
    }

    public void recordWifiStart() {
        if (activeRadios++ == 0) {
            wifiBusySinceTicks = nowTicks;
        }
    }

    void trackApActivity(int sfuId, ApCapabilityMode mode, long windowNs) {
        apActivityTraceEnabled = true;
        apActivities.put(sfuId, new ApActivity(mode, windowNs));
    }

    void trackApTraffic(int sfuId, ApCapabilityMode mode, long periodNs, double alpha) {
        apObservationPeriodNs = periodNs;
        apObservers.put(sfuId, new ApTrafficObserver(periodNs, alpha));
        // 单独开 C1 时，用相同的活动边界，无需开启 B2 日志或 B3 功率统计。
        apActivities.computeIfAbsent(sfuId, key -> new ApActivity(mode, -1));
    }

    /** 调用方只能读取已经发布的不可变窗口。关闭观测或首个采样点前返回空列表。 */
    public List<ApTrafficObserver.Observation> latestApTrafficObservation(int sfuId) {
        ApTrafficObserver observer = apObservers.get(sfuId);
        return observer == null ? List.of() : observer.latest();
    }

    long apDownlinkArrivalBytes(int sfuId) {
        return apObservers.get(sfuId).downlinkArrivalBytes();
    }

    void recordInitialApMode(int sfuId, ApModeController.Policy policy, ApCapabilityMode mode) {
        trace.recordInitialApMode(sfuId, policy, mode);
    }

    public void recordApModeDecision(int sfuId, ApCapabilityMode current, ApModeController.Decision decision,
                                     ApModeSwitch.Outcome outcome) {
        trace.recordApModeDecision(sfuId, current, decision, outcome);
    }

    private void sampleApTraffic(long endNs) {
        if (causalChannelSampling && channelObservation != null) {
            channelObservation.advanceThrough(endNs);
        }
        apObservers.forEach((sfuId, observer) -> {
            ApActivity.Airtime airtime = apActivities.get(sfuId).airtimeAt(endNs);
            List<ApTrafficObserver.Observation> sample = observer.sample(endNs, airtime.txNs(), airtime.rxNs());
            for (ApTrafficObserver.Observation observation : sample) {
                trace.recordApTrafficObservation(sfuId, observation);
            }
            if (qosLedger != null) {
                qosLedger.markQueueSample(sfuId, sample);
            }
            PredictionTrack track = apPredictors.get(sfuId);
            if (track != null && !track.policy.usesPrediction() && endNs < Long.MAX_VALUE) {
                ApCapabilityMode mode = apActivities.get(sfuId).mode();
                double[] busy = track.sampleCca(endNs);
                List<ApModePredictor.Forecast> forecasts;
                if (track.mac == null) {
                    forecasts = track.predictor.predict(endNs, mode, sample, track.sfu::candidateChannelReport,
                            busy[0], busy[1]);
                } else {
                    ApModePredictor.Evidence evidence = track.mac.snapshot(endNs, observer.waitingDownlinkPackets());
                    trace.recordApMacEvidence(sfuId, endNs, evidence, track.mac.phaseNs(ApPhase.AIFS),
                            track.mac.phaseNs(ApPhase.BACKOFF), track.mac.phaseNs(ApPhase.FROZEN), sample);
                    forecasts = track.predictor.predictRevised(endNs, mode, sample, track.sfu::candidateChannelReport,
                            busy[0], busy[1], evidence);
                    track.mac.resetWindow();
                }
                latestForecasts.put(sfuId, forecasts);
                for (ApModePredictor.Forecast forecast : forecasts) {
                    trace.recordApPrediction(sfuId, forecast);
                }
            }
        });
        lastApSampleNs = endNs;
    }

    private long observationTimeNs(long ticks) {
        return nanosecondClock ? ticks : Math.multiplyExact(ticks, 1_000);
    }

    /** 实际 DATA 开始才离开待发视图，包括退避后再次发送和发生碰撞的 PPDU。 */
    public void recordApDownlinkAttempt(Packet head, int sfuId, List<Packet> aggregate) {
        ApTrafficObserver observer = apObservers.get(sfuId);
        if (observer == null || head.isUplink()) {
            return;
        }
        long timeNs = observationTimeNs(nowTicks);
        PredictionTrack track = apPredictors.get(sfuId);
        if (track != null && track.mac != null) {
            track.mac.downlinkStarted(timeNs);
        }
        if (aggregate == null) {
            traceApQueue(sfuId, observer.start(head, timeNs));
        } else {
            for (Packet packet : aggregate) {
                traceApQueue(sfuId, observer.start(packet, timeNs));
            }
        }
        recordObservedPpdu(observer, head, sfuId, aggregate == null ? 1 : aggregate.size(), timeNs);
    }

    /** 上行仅在 AP 解码到成员后记入可见深度。不从 STA 队列推断其它成员。 */
    public void recordApUplinkReception(Packet head, int sfuId, int decodedMpdus) {
        ApTrafficObserver observer = apObservers.get(sfuId);
        if (observer != null && head.isUplink() && decodedMpdus > 0) {
            recordObservedPpdu(observer, head, sfuId, decodedMpdus, observationTimeNs(nowTicks));
        }
    }

    private void recordObservedPpdu(ApTrafficObserver observer, Packet head, int sfuId, int depth, long timeNs) {
        observer.recordPpdu(head, depth, timeNs);
        trace.recordApObservedPpdu(head, sfuId, depth, timeNs);
    }

    public void recordApTrafficDelivery(Packet packet, int sfuId) {
        ApTrafficObserver observer = apObservers.get(sfuId);
        if (observer != null) {
            traceApQueue(sfuId, observer.deliver(packet, observationTimeNs(nowTicks)));
        }
    }

    private void traceApQueue(int sfuId, ApTrafficObserver.QueueEvent event) {
        if (event != null) {
            trace.recordApQueueEvent(sfuId, event);
            if (qosLedger != null) {
                qosLedger.queueChanged(sfuId, event);
            }
            PredictionTrack track = apPredictors.get(sfuId);
            if (track != null && track.mac != null && !event.packet().isUplink()) {
                track.mac.queueChanged(event);
                considerQosRecheck(sfuId, track, event);
            }
        }
    }

    private void considerQosRecheck(int sfuId, PredictionTrack track, ApTrafficObserver.QueueEvent event) {
        long now = event.timeNs();
        if (!track.policy.usesQos() || (modeDecisionEndNs > 0 && now >= modeDecisionEndNs) || track.lastDecisionNs == now) {
            return;
        }
        boolean ingress = event.kind() == ApTrafficObserver.Kind.ENQUEUED || event.kind() == ApTrafficObserver.Kind.REQUEUED;
        String reason;
        if (ingress && deadlineModePolicy.urgent(event.packet(), now, modeDecisionPeriodNs)) {
            reason = "URGENT_QUEUE";
        } else if (track.deferred != null && ingress) {
            reason = "DEFER_QUEUE_CHANGED";
        } else if (track.deferred != null && (event.kind() == ApTrafficObserver.Kind.CONFIRMED
                || event.kind() == ApTrafficObserver.Kind.DROPPED) && !apObservers.get(sfuId).hasWaitingDownlink()) {
            reason = "QUEUE_DRAIN";
        } else {
            return;
        }
        if (track.recheck == null) {
            track.recheck = scheduleAfterTicks(0, EventType.WIFI_AP_QOS_RECHECK, null, sfuId);
            track.recheckReason = reason;
        } else if (reason.equals("URGENT_QUEUE")) {
            track.recheckReason = reason;
        }
    }

    public void recordApSwitchEvent(String event, int sfuId, ApModeSwitch.Request request, ApCapabilityMode from,
                                    ApModeSwitch.Profile profile, long startNs, long observedNs, BigDecimal energyUj) {
        trace.recordApSwitchEvent(nowTicks, event, sfuId, request, from, profile, startNs, observedNs, energyUj);
    }

    public void startApModeSwitch(int sfuId, ApCapabilityMode target) {
        ApActivity activity = apActivities.get(sfuId);
        logApActivity(sfuId, activity, activity.beginSwitch(nowTicks, target), null, "SWITCH_START");
    }

    public long finishApModeSwitch(int sfuId, long startNs) {
        ApActivity activity = apActivities.get(sfuId);
        logApActivity(sfuId, activity, activity.endSwitch(nowTicks), null, "SWITCH_END");
        PredictionTrack track = apPredictors.get(sfuId);
        if (track != null && track.policy.usesPrediction()) {
            track.mixedMode = true;
        }
        return activity.observedDurationNs(startNs, nowTicks);
    }

    /** DATA 的实际发送端和接收端决定 AP 活动。接入和帧间等待没有活动边界。 */
    public void recordApDataBoundary(Packet packet, int sfuId, boolean start) {
        recordApFrameBoundary(packet, sfuId, !packet.isUplink(), start, start ? "DATA_START" : "DATA_END");
    }

    /** ACK 沿 DATA 的反方向发送，等待 ACK 到达期间只统计实际收发。 */
    public void recordApAckBoundary(Packet packet, int sfuId, boolean start) {
        recordApFrameBoundary(packet, sfuId, packet.isUplink(), start, start ? "ACK_START" : "ACK_END");
    }

    public void recordApCollisionDataEnd(Packet packet, int sfuId) {
        recordApFrameBoundary(packet, sfuId, !packet.isUplink(), false, "COLLISION_DATA_END");
    }

    private void recordApFrameBoundary(Packet packet, int sfuId, boolean apTransmits, boolean start, String reason) {
        ApActivity activity = apActivities.get(sfuId);
        if (activity != null) {
            ApActivity.State frameState = apTransmits ? ApActivity.State.TX : ApActivity.State.RX;
            long timeNs = observationTimeNs(nowTicks);
            ApActivity.Transition transition = start ? activity.begin(timeNs, frameState)
                    : activity.end(timeNs, frameState);
            logApActivity(sfuId, activity, transition, packet, reason);
        }
    }

    private void logApActivity(int sfuId, ApActivity activity, ApActivity.Transition transition, Packet packet,
                               String reason) {
        if (apActivityTraceEnabled && transition != null) {
            trace.recordApActivityChange(sfuId, activity.mode(), nowTicks, transition, packet, reason);
        }
    }

    public void recordWifiDataEnd(Packet packet, int sfuId) {
        trace.recordWifiDataEnd(packet, sfuId, nowTicks);
    }

    public void recordWifiMpduDecode(Packet head, Packet packet, int sfuId, long startNs, int attempt, int mcs,
                                     int mpduBytes, ChannelSample sample, int frequencyBlocks,
                                     SinrPerModel.Decision decision) {
        trace.recordWifiMpduDecode(nowNanoseconds(), startNs, head, packet, sfuId, attempt, mcs, mpduBytes,
                sample, frequencyBlocks, decision);
    }

    public void recordAmpduEvent(String phase, Packet head, int sfuId, List<Packet> members, int bytes,
                                 long ppduNs, long sifsNs, long baNs, long bitmap) {
        trace.recordAmpduEvent(nowTicks, phase, head, sfuId, members, bytes, ppduNs, sifsNs, baNs, bitmap);
    }

    public void recordAmpduMpdu(Packet head, Packet member, int sfuId, String status, int retries) {
        trace.recordAmpduMpdu(nowTicks, head, member, sfuId, status, retries);
    }

    /** 一个聚合交换只释放一次占用，逐 MPDU 的服务日志不改变全局忙时。 */
    public void recordWifiExchangeEnd() {
        recordWifiEnd();
    }

    public void recordWifiService(Packet packet, int sfuId, long queueEntryTicks, long startTicks, long dataEndTicks,
                                  boolean channelReleased) {
        if (channelReleased) {
            recordWifiEnd();
        }
        double queueWaitUs = ticksToMicroseconds(startTicks - queueEntryTicks);
        long endToEndTicks = 0;
        if (packet.isUplink()) {
            // 上行端到端时延由 MFU 的收包时刻统计，无线一跳只记录排队时延。
            uplinkByStation.computeIfAbsent(packet.staId(), id -> new UplinkStats()).waitSumUs += queueWaitUs;
        } else {
            // 下行端到端时延止于 STA 收齐 DATA，服务时长止于无线资源释放。
            endToEndTicks = dataEndTicks - microsecondsToTicks(packet.generationTimeUs());
            double delayUs = ticksToMicroseconds(endToEndTicks);
            byClass.get(packet.type()).add(queueWaitUs, delayUs);
            overallDelay.add(queueWaitUs, delayUs);
        }
        trace.recordWifiService(packet, sfuId, queueEntryTicks, startTicks, nowTicks, endToEndTicks);
        ApTrafficObserver observer = apObservers.get(sfuId);
        if (observer != null) {
            traceApQueue(sfuId, observer.confirm(packet, observationTimeNs(nowTicks)));
        }
    }

    public void recordWifiTxopStart(Packet packet, int sfuId, long limitNs) {
        trace.recordWifiTxopStart(packet, sfuId, nowTicks, limitNs);
    }

    public void recordWifiTxopEnd(Packet packet, int sfuId, long startTicks, long limitNs, int packetCount) {
        long durationTicks = nowTicks - startTicks;
        wifiTxops++;
        wifiTxopPackets += packetCount;
        totalWifiTxopDurationUs += ticksToMicroseconds(durationTicks);
        trace.recordWifiTxopEnd(packet, sfuId, nowTicks, limitNs, durationTicks, packetCount);
    }

    public void recordWifiCollisionStart(List<Packet> packets, int sfuId) {
        // 碰撞中的多次发射仍只占用一份 SFU 信道，忙时从共同起点算到最后一个 DATA 结束。
        recordWifiStart();
        wifiCollisionEvents++;
        for (Packet packet : packets) {
            trace.recordCollisionPacket(packet, sfuId, nowTicks, "WIFI_COLLISION_START");
        }
        ApActivity activity = apActivities.get(sfuId);
        if (activity != null) {
            // 所有碰撞 DATA 同时开始。批量更新避免因候选顺序产生零时长的中间 RX 状态。
            int txFrames = 0;
            Packet representative = packets.get(0);
            for (Packet packet : packets) {
                if (!packet.isUplink()) {
                    txFrames++;
                    representative = packet;
                }
            }
            ApActivity.Transition transition = activity.beginCollision(observationTimeNs(nowTicks), txFrames,
                    packets.size() - txFrames);
            logApActivity(sfuId, activity, transition, representative, "COLLISION_START");
        }
    }

    public void recordWifiCollisionDrop(Packet packet, int sfuId) {
        recordCollisionLoss(packet);
        trace.recordCollisionPacket(packet, sfuId, nowTicks, "WIFI_COLLISION_DROP");
        observeApDrop(packet, sfuId);
    }

    public void recordWifiCollisionDataEnd(Packet packet, int sfuId) {
        trace.recordCollisionPacket(packet, sfuId, nowTicks, "WIFI_COLLISION_DATA_END");
    }

    public void recordWifiMacAttempt(Packet packet, int sfuId, int retries) {
        if (retries > 0) {
            wifiRetransmissions++;
            if (channelObservation != null && !packet.isUplink()) {
                channelObservation.recordMacObservation(sfuId, 0, 1, 0);
            }
        }
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_MAC_ATTEMPT", retries);
    }

    public void recordWifiAckTimeout(Packet packet, int sfuId, int retries) {
        wifiAckTimeouts++;
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_ACK_TIMEOUT", retries);
    }

    public void recordWifiMacRetry(Packet packet, int sfuId, int retries, int cwBefore, int cwAfter, int backoff) {
        trace.recordMacRetry(packet, sfuId, nowTicks, retries, cwBefore, cwAfter, backoff);
        ApTrafficObserver observer = apObservers.get(sfuId);
        if (observer != null) {
            traceApQueue(sfuId, observer.retry(packet, observationTimeNs(nowTicks)));
        }
    }

    public void recordWifiRetryLimitDrop(Packet packet, int sfuId, int retries) {
        recordWifiRetryLimitDrop(packet, sfuId, retries, true);
    }

    public void recordWifiRetryLimitDrop(Packet packet, int sfuId, int retries, boolean collision) {
        wifiRetryLimitDrops++;
        if (collision) {
            recordCollisionLoss(packet);
        }
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_RETRY_LIMIT_DROP", retries);
        observeApDrop(packet, sfuId);
    }

    private void observeApDrop(Packet packet, int sfuId) {
        if (qosLedger != null) {
            qosLedger.dropped(packet);
        }
        if (channelObservation != null && !packet.isUplink()) {
            channelObservation.recordMacObservation(sfuId, 0, 0, 1);
        }
        ApTrafficObserver observer = apObservers.get(sfuId);
        if (observer != null) {
            traceApQueue(sfuId, observer.drop(packet, observationTimeNs(nowTicks)));
        }
    }

    private void recordCollisionLoss(Packet packet) {
        wifiCollisionDroppedPackets++;
        wifiCollisionDroppedBytes += packet.bytes();
    }

    public void recordWifiMacSuccess(Packet packet, int sfuId, int retries) {
        trace.recordMacRetryPacket(packet, sfuId, nowTicks, "WIFI_MAC_SUCCESS", retries);
        if (channelObservation != null && !packet.isUplink()) {
            channelObservation.recordMacObservation(sfuId, 1, 0, 0);
        }
    }

    public void recordWifiCollisionEnd(int sfuId) {
        recordWifiEnd();
        trace.recordCollisionEnd(sfuId, nowTicks);
    }

    private void recordWifiEnd() {
        // 不同 SFU 的忙区间可以重叠，按区间并集计入全局忙时。
        if (--activeRadios == 0) {
            wifiBusyTicks += nowTicks - wifiBusySinceTicks;
        }
    }

    public void recordEnqueue(Packet packet, int sfuId, int queueDepth) {
        trace.recordEnqueue(packet, nowTicks, sfuId, queueDepth);
        PredictionTrack track = apPredictors.get(sfuId);
        if (track != null && !packet.isUplink()) {
            track.predictor.observeDownlinkIngress(packet, nowNanoseconds());
        }
        ApTrafficObserver observer = apObservers.get(sfuId);
        if (observer != null) {
            traceApQueue(sfuId, observer.enqueue(packet, observationTimeNs(nowTicks)));
        }
    }

    public void recordQueueSelection(Packet packet, int sfuId, int vo, int vi, int be, int bk) {
        trace.recordQueueSelection(packet, nowTicks, sfuId, vo, vi, be, bk);
    }

    public void recordVirtualCollision(Packet loser, Packet winner, int sfuId, int cwBefore, int cwAfter, int backoff) {
        trace.recordVirtualCollision(loser, winner, sfuId, nowTicks, cwBefore, cwAfter, backoff);
    }

    void recordSummary(List<Sfu> sfus, List<Sta> stations, boolean uplinkEnabled, ApEnergyModel apEnergyModel,
                       ApModeSwitch.Settings switching) {
        byClass.forEach((type, stats) -> trace.recordClassSummary(
                type, stats.count, stats.averageWaitUs(), stats.averageDelayUs()));
        trace.recordGlobalDelay(overallDelay.count, overallDelay.averageWaitUs(), overallDelay.averageDelayUs());
        ApEnergyModel.Energy networkEnergy = ApEnergyModel.Energy.ZERO;
        long observationEndNs = 0;
        int modeledApCount = 0;
        for (Sfu sfu : sfus) {
            trace.recordSfuSummary(sfu, throughputMbps(sfu.sentBytes()));
            ApActivity activity = apActivities.get(sfu.id());
            if (apActivityTraceEnabled && activity != null) {
                ApActivity.Totals totals = activity.finish(nowTicks);
                ApCapabilityMode mode = activity.mode();
                String observedMode = observedApMode(mode, totals);
                trace.recordApActivityTotals(sfu.id(), observedMode, totals, switching != null);
                if (apEnergyModel != null) {
                    // 所有 AP 共用 B2 的观察窗口。LEGACY 仍有活动统计，能耗汇总只覆盖有功率参数的 AP。
                    observationEndNs = totals.observationEndNs();
                    if (mode != null) {
                        ApEnergyModel.Energy energy = recordApEnergy(sfu.id(), mode, observedMode, totals,
                                apEnergyModel, switching);
                        networkEnergy = networkEnergy.plus(energy);
                        modeledApCount++;
                    }
                }
            }
        }
        if (apEnergyModel != null) {
            trace.recordApNetworkEnergy(observationEndNs, modeledApCount, sfus.size() - modeledApCount, networkEnergy,
                    switching != null);
        }
        for (Sta sta : stations) {
            trace.recordStaSummary(sta, throughputMbps(sta.receivedBytes()));
        }
        if (uplinkEnabled) {
            recordUplinkSummary(stations);
        }
        if (wifiCollisionEvents > 0) {
            trace.recordCollisionSummary(wifiCollisionEvents, wifiCollisionDroppedPackets, wifiCollisionDroppedBytes);
        }
        if (wifiRetryEnabled) {
            trace.recordRetrySummary(wifiRetransmissions, wifiAckTimeouts, wifiRetryLimitDrops);
        }
        if (wifiTxops > 0) {
            trace.recordTxopSummary(wifiTxops, totalWifiTxopDurationUs, wifiTxopPackets);
        }
        if (multiPacket) {
            trace.recordRunSummary(deliveredPackets, deliveredBytes, wifiBusyTicks, nowTicks);
        } else {
            trace.recordSinglePacketSummary(deliveredPackets, deliveredBytes, lastDeliveryDelayTicks);
        }
    }

    private ApEnergyModel.Energy recordApEnergy(int sfuId, ApCapabilityMode mode, String observedMode,
                                               ApActivity.Totals totals, ApEnergyModel model,
                                               ApModeSwitch.Settings switching) {
        if (switching == null) {
            ApEnergyModel.Power power = model.powerFor(mode);
            ApEnergyModel.Energy energy = power.integrate(totals);
            trace.recordApEnergy(sfuId, mode, totals.observationEndNs(), power, energy);
            return energy;
        }
        ApEnergyModel.Energy energy = model.integrate(totals, switching.powerFrom(ApCapabilityMode.LCM),
                switching.powerFrom(ApCapabilityMode.HCM));
        trace.recordSwitchableApEnergy(sfuId, observedMode, totals.observationEndNs(), energy);
        for (ApCapabilityMode capability : ApCapabilityMode.values()) {
            ApEnergyModel.Power power = model.powerFor(capability);
            ApActivity.Durations durations = totals.forMode(capability);
            trace.recordApEnergyByMode(sfuId, capability, durations, power, power.integrate(durations));
        }
        return energy;
    }

    private static String observedApMode(ApCapabilityMode finalMode, ApActivity.Totals totals) {
        if (finalMode == null) {
            return "LEGACY";
        }
        boolean lcm = hasObservedTime(totals.forMode(ApCapabilityMode.LCM));
        boolean hcm = hasObservedTime(totals.forMode(ApCapabilityMode.HCM));
        if (lcm && hcm) {
            return "MIXED";
        }
        if (lcm) {
            return ApCapabilityMode.LCM.name();
        }
        return hcm ? ApCapabilityMode.HCM.name() : finalMode.name();
    }

    private static boolean hasObservedTime(ApActivity.Durations durations) {
        return durations.idleNs() > 0 || durations.txNs() > 0 || durations.rxNs() > 0;
    }

    private void recordUplinkSummary(List<Sta> stations) {
        int packets = 0;
        long bytes = 0;
        double delaySumUs = 0.0;
        UplinkStats empty = new UplinkStats();
        for (Sta sta : stations) {
            UplinkStats stats = uplinkByStation.getOrDefault(sta.id(), empty);
            trace.recordUplinkStaSummary(sta, stats.packets, stats.bytes, throughputMbps(stats.bytes),
                    average(stats.waitSumUs, stats.packets), average(stats.delaySumUs, stats.packets));
            packets += stats.packets;
            bytes += stats.bytes;
            delaySumUs += stats.delaySumUs;
        }
        trace.recordUplinkGlobalSummary(packets, bytes, average(delaySumUs, packets));
    }

    private static double average(double sum, int count) {
        return count == 0 ? 0.0 : sum / count;
    }

    private double throughputMbps(long bytes) {
        // SFU 和 STA 统一采用 0 至最后事件的观察窗口。bit/μs 与 Mb/s 等值。
        return nowTicks == 0 ? 0.0 : bytes * 8.0 / ticksToMicroseconds(nowTicks);
    }

    private static final class DelayStats {
        private int count;
        // 时刻仍用 long，累计值用于求均值，避免多包时延相加超过时钟整数范围。
        private double waitSumUs;
        private double delaySumUs;

        void add(double waitUs, double delayUs) {
            count++;
            waitSumUs += waitUs;
            delaySumUs += delayUs;
        }

        double averageWaitUs() {
            return count == 0 ? 0.0 : waitSumUs / count;
        }

        double averageDelayUs() {
            return count == 0 ? 0.0 : delaySumUs / count;
        }
    }

    /** 仅累计 AP 正在侦听的带宽。次信道扫描只有完成且未过期后才可补充。 */
    private static final class PredictionTrack {
        private final Sfu sfu;
        private final ApModePredictor predictor;
        private final boolean passive;
        private final ApMacEvidence mac;
        private final ApModeController.Policy policy;
        private final boolean predictChannel, externalCca;
        private final boolean loadAware;
        private boolean mixedMode;
        private long lastDecisionNs = -1;
        private Event recheck;
        private String recheckReason;
        private DeferredHcm deferred;
        private long nextDeferralId = 1;
        private long startNs, previousNs, primaryBusyNs, wideBusyNs, wideObservedNs;
        private int accessMask, busyMask;

        private PredictionTrack(Sfu sfu, ApModePredictor predictor, boolean passive, int bandwidthMhz, boolean revised,
                                ApModeController.Policy policy, boolean predictChannel, boolean externalCca, boolean loadAware) {
            this.sfu = sfu;
            this.predictor = predictor;
            this.passive = passive;
            this.mac = revised ? new ApMacEvidence() : null;
            this.policy = policy;
            this.predictChannel = predictChannel;
            this.externalCca = externalCca;
            this.loadAware = loadAware;
            accessMask = (1 << (bandwidthMhz / 20)) - 1;
        }

        private void advanceCca(long timeNs) {
            long delta = timeNs - previousNs;
            if ((busyMask & 1) != 0) {
                primaryBusyNs += delta;
            }
            if (accessMask == 15) {
                wideObservedNs += delta;
                if (busyMask != 0) {
                    wideBusyNs += delta;
                }
            }
            previousNs = timeNs;
        }

        private double[] sampleCca(long endNs) {
            return sampleCca(endNs, true);
        }

        private double[] sampleCca(long endNs, boolean reset) {
            if (!passive) {
                return new double[] {-1, -1};
            }
            advanceCca(endNs);
            long duration = endNs - startNs;
            if (duration == 0) {
                return new double[] {-1, -1};
            }
            double primary = (double) primaryBusyNs / duration;
            double wide = wideObservedNs == duration ? (double) wideBusyNs / duration : estimatedWideBusy(endNs, primary);
            if (reset) {
                startNs = endNs;
                primaryBusyNs = wideBusyNs = wideObservedNs = 0;
            }
            return new double[] {primary, wide};
        }

        private double estimatedWideBusy(long endNs, double primary) {
            // 不同时间的扫描无法直接求并集。候选宽带用独立占用近似，并以缺少扫描结果显式降级。
            double idleFraction = 1 - primary;
            for (int subchannel = 1; subchannel < 4; subchannel++) {
                Optional<AuxChannelScan.Result> scan = sfu.auxChannelScan(subchannel, endNs);
                if (scan.isEmpty()) {
                    return -1;
                }
                idleFraction *= 1 - scan.get().busyFraction();
            }
            return 1 - idleFraction;
        }
    }

    private static final class DeferredHcm {
        private final long id, openedNs;
        private Event timer;

        private DeferredHcm(long id, long openedNs, Event timer) {
            this.id = id;
            this.openedNs = openedNs;
            this.timer = timer;
        }
    }

    private static final class UplinkStats {
        private int packets;
        private long bytes;
        private double waitSumUs;
        private double delaySumUs;
    }
}
