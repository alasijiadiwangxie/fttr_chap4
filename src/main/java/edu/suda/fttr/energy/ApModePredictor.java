package edu.suda.fttr.energy;

import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.AmpduConfig;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.phy.ObservedPerCurve;
import edu.suda.fttr.phy.WirelessPhy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** C3 只接收 C1 快照、物理配置与已经到达的反馈。没有执行无线动作或查询真值的接口。 */
public final class ApModePredictor {
    private static final int DEFAULT_PAYLOAD_BYTES = 1500;

    public enum Confidence { IDLE, NO_PHY_ERRORS, FEEDBACK, HISTORY_ONLY, CHANNEL_UNKNOWN }
    public enum Feasibility { FEASIBLE, DELAY_RISK, RELIABILITY_RISK, CAPACITY_INSUFFICIENT,
        CHANNEL_INFORMATION_INSUFFICIENT, SWITCH_COST_UNKNOWN }

    @FunctionalInterface
    public interface FeedbackLookup {
        Optional<ChannelReport> report(int staId, int subchannel, long timeNs);
    }

    private final ApAnalysisSettings settings;
    private final Map<ApCapabilityMode, Map<Integer, WirelessPhy>> profiles;
    private final ApEnergyModel energyModel;
    private final ApModeSwitch.Settings switching;
    private final WifiFrameExchange exchange;
    private final EdcaParameters edca;
    private final WifiRetryPolicy retry;
    private final AmpduConfig ampdu;
    private final ObservedPerCurve observedPer;
    private final ObservedServicePrediction revised;
    private final Map<ApCapabilityMode, EnumMap<AccessCategory, History>> histories = new EnumMap<>(ApCapabilityMode.class);
    private final Map<AccessCategory, Double> opticalDelayNs = new EnumMap<>(AccessCategory.class);
    private ApCapabilityMode previousMode;

    public ApModePredictor(ApAnalysisSettings settings, Map<ApCapabilityMode, Map<Integer, WirelessPhy>> profiles,
                           ApEnergyModel energyModel, ApModeSwitch.Settings switching, WifiFrameExchange exchange,
                           EdcaParameters edca, WifiRetryPolicy retry, AmpduConfig ampdu, ObservedPerCurve observedPer) {
        this.settings = settings;
        this.profiles = Map.of(
                ApCapabilityMode.LCM, Collections.unmodifiableMap(new TreeMap<>(profiles.get(ApCapabilityMode.LCM))),
                ApCapabilityMode.HCM, Collections.unmodifiableMap(new TreeMap<>(profiles.get(ApCapabilityMode.HCM))));
        this.energyModel = energyModel;
        this.switching = switching;
        this.exchange = exchange;
        this.edca = edca;
        this.retry = retry;
        this.ampdu = ampdu;
        this.observedPer = observedPer;
        this.revised = new ObservedServicePrediction(settings, this.profiles, energyModel, switching,
                exchange, edca, retry, ampdu, observedPer);
        for (ApCapabilityMode mode : ApCapabilityMode.values()) {
            EnumMap<AccessCategory, History> classes = new EnumMap<>(AccessCategory.class);
            for (AccessCategory ac : AccessCategory.values()) {
                classes.put(ac, new History());
            }
            histories.put(mode, classes);
        }
    }

    /** DL 数据显式抵达 AP 时才取得该 packet 的光侧经历。 */
    public void observeDownlinkIngress(Packet packet, long nowNs) {
        double ns = nowNs - packet.generationTimeUs() * 1_000.0;
        opticalDelayNs.merge(packet.type(), ns, this::update);
    }

    public List<Forecast> predict(long nowNs, ApCapabilityMode currentMode,
                                  List<ApTrafficObserver.Observation> observations, FeedbackLookup feedback,
                                  double primaryBusy, double wideBusy) {
        if (previousMode == null || previousMode == currentMode) {
            observeHistory(currentMode, observations);
        }
        previousMode = currentMode;
        return List.of(predictMode(nowNs, currentMode, ApCapabilityMode.LCM, observations, feedback, primaryBusy),
                predictMode(nowNs, currentMode, ApCapabilityMode.HCM, observations, feedback, wideBusy));
    }

    public List<Forecast> predictRevised(long nowNs, ApCapabilityMode currentMode,
                                         List<ApTrafficObserver.Observation> observations, FeedbackLookup feedback,
                                         double primaryBusy, double wideBusy, Evidence evidence) {
        return revised.predict(nowNs, currentMode, observations, feedback, primaryBusy, wideBusy, evidence,
                opticalDelayNs);
    }

    /** D2 复用 revised 服务模型，切换和已在途的交换由控制器单独对账。 */
    public List<ServiceForecast> predictControlWork(long nowNs, ApCapabilityMode currentMode,
                                                    List<ApTrafficObserver.Observation> observations,
                                                    FeedbackLookup feedback, double primaryBusy, double wideBusy,
                                                    Evidence evidence, boolean useChannel, boolean mixedMode) {
        return predictControlWork(nowNs, currentMode, observations, feedback, primaryBusy, wideBusy, evidence,
                useChannel, mixedMode, false);
    }

    public List<ServiceForecast> predictControlWork(long nowNs, ApCapabilityMode currentMode,
                                                    List<ApTrafficObserver.Observation> observations,
                                                    FeedbackLookup feedback, double primaryBusy, double wideBusy,
                                                    Evidence evidence, boolean useChannel, boolean mixedMode,
                                                    boolean loadAware) {
        return revised.controlWork(nowNs, currentMode, observations, feedback, primaryBusy, wideBusy,
                evidence, useChannel, mixedMode, loadAware);
    }

    public static final class ServiceForecast {
        public final ApCapabilityMode mode;
        public final Confidence confidence;
        public final double observedBusyFraction;
        public final boolean channelIncomplete;
        public final ServiceWork expected, conservative, optimistic;
        public final List<DeadlineBatch> deadlineBatches;
        public final double saturatedMbps, saturatedDepth;

        ServiceForecast(ApCapabilityMode mode, Confidence confidence, double observedBusyFraction,
                        boolean channelIncomplete, ServiceWork expected, ServiceWork conservative,
                        ServiceWork optimistic) {
            this(mode, confidence, observedBusyFraction, channelIncomplete, expected, conservative, optimistic,
                    List.of(), -1, -1);
        }

        ServiceForecast(ApCapabilityMode mode, Confidence confidence, double observedBusyFraction,
                        boolean channelIncomplete, ServiceWork expected, ServiceWork conservative,
                        ServiceWork optimistic, List<DeadlineBatch> deadlineBatches, double saturatedMbps,
                        double saturatedDepth) {
            this.mode = mode;
            this.confidence = confidence;
            this.observedBusyFraction = observedBusyFraction;
            this.channelIncomplete = channelIncomplete;
            this.expected = expected;
            this.conservative = conservative;
            this.optimistic = optimistic;
            this.deadlineBatches = List.copyOf(deadlineBatches);
            this.saturatedMbps = saturatedMbps;
            this.saturatedDepth = saturatedDepth;
        }
    }

    /** 按实际待发队列组织的完整交换。一次聚合的成员共用其预计完成时刻。 */
    public static final class DeadlineBatch {
        public final List<Packet> packets;
        public final double workNs, successProbability, txNs, rxNs;

        DeadlineBatch(List<Packet> packets, double workNs, double successProbability) {
            this(packets, workNs, successProbability, 0, 0);
        }

        DeadlineBatch(List<Packet> packets, double workNs, double successProbability, double txNs, double rxNs) {
            this.packets = List.copyOf(packets);
            this.workNs = workNs;
            this.successProbability = successProbability;
            this.txNs = txNs;
            this.rxNs = rxNs;
        }
    }

    /** 预测业务全量需要的空口工作。workNs 已含 MAC 等待；外部 CCA 占用另以比例表示。 */
    public static final class ServiceWork {
        public final double demandBits, demandPackets, arrivalBits, successBits, workNs, txNs, rxNs;
        public final double busyFraction, meanFailure, ampduDepth;

        ServiceWork(double demandBits, double demandPackets, double arrivalBits, double successBits, double workNs,
                    double txNs, double rxNs, double busyFraction, double meanFailure, double ampduDepth) {
            this.demandBits = demandBits;
            this.demandPackets = demandPackets;
            this.arrivalBits = arrivalBits;
            this.successBits = successBits;
            this.workNs = workNs;
            this.txNs = txNs;
            this.rxNs = rxNs;
            this.busyFraction = busyFraction;
            this.meanFailure = meanFailure;
            this.ampduDepth = ampduDepth;
        }
    }

    /** 采样时刻前已处理的发送事件与 AP 持有的 FIFO。上行没有待发队列。 */
    public static final class Evidence {
        final List<Packet> waitingDlFifo;
        final List<Transmission> completedPpdus;
        final long eligibleNs, occupiedNs, blockedNs;

        public Evidence(List<Packet> waitingDlFifo, List<Transmission> completedPpdus,
                        long eligibleNs, long occupiedNs, long blockedNs) {
            if (eligibleNs < 0 || occupiedNs < 0 || blockedNs < 0
                    || blockedNs > eligibleNs || occupiedNs > eligibleNs - blockedNs) {
                throw new IllegalArgumentException("Observable service intervals must be disjoint and bounded");
            }
            this.waitingDlFifo = List.copyOf(waitingDlFifo);
            this.completedPpdus = List.copyOf(completedPpdus);
            this.eligibleNs = eligibleNs;
            this.occupiedNs = occupiedNs;
            this.blockedNs = blockedNs;
        }

        public long eligibleNs() { return eligibleNs; }
        public long occupiedNs() { return occupiedNs; }
        public long blockedNs() { return blockedNs; }
        public List<Transmission> completedPpdus() { return completedPpdus; }
    }

    /** 下行包含已发 MPDU 数和已确认数。上行仅记录 AP 成功解码的 MPDU。 */
    public static final class Transmission {
        final Packet.Direction direction;
        final AccessCategory ac;
        final int staId, attemptedMpdus, confirmedMpdus;
        final long payloadBytes, dataNs, responseNs, mediumNs;

        public Transmission(Packet.Direction direction, AccessCategory ac, int staId, int attemptedMpdus,
                            int confirmedMpdus, long payloadBytes, long dataNs, long responseNs, long mediumNs) {
            if (direction == null || ac == null || staId <= 0 || confirmedMpdus < 0
                    || attemptedMpdus < confirmedMpdus || payloadBytes < 0 || dataNs < 0
                    || responseNs < 0 || mediumNs < dataNs || mediumNs - dataNs < responseNs) {
                throw new IllegalArgumentException("Invalid observed PPDU counts or timing");
            }
            this.direction = direction;
            this.ac = ac;
            this.staId = staId;
            this.attemptedMpdus = attemptedMpdus;
            this.confirmedMpdus = confirmedMpdus;
            this.payloadBytes = payloadBytes;
            this.dataNs = dataNs;
            this.responseNs = responseNs;
            this.mediumNs = mediumNs;
        }

        public Packet.Direction direction() { return direction; }
        public int attemptedMpdus() { return attemptedMpdus; }
        public int confirmedMpdus() { return confirmedMpdus; }
        public long dataNs() { return dataNs; }
        public long responseNs() { return responseNs; }
    }

    private double update(double previous, double sample) {
        return Double.isNaN(previous) ? sample : previous + settings.alpha() * (sample - previous);
    }

    private void observeHistory(ApCapabilityMode mode, List<ApTrafficObserver.Observation> observations) {
        for (ApTrafficObserver.Observation observation : observations) {
            if (observation.direction() != Packet.Direction.DL) {
                continue;
            }
            History history = histories.get(mode).get(observation.category());
            long attempted = observation.confirmedPackets() + observation.requeuedPackets() + observation.droppedPackets();
            if (attempted > 0) {
                history.error = update(history.error, 1.0 - (double) observation.confirmedPackets() / attempted);
            }
            if (observation.dataPpdus() > 0) {
                history.depth = update(history.depth, observation.averageDepth());
            }
        }
    }

    private Forecast predictMode(long startNs, ApCapabilityMode currentMode, ApCapabilityMode mode,
                                 List<ApTrafficObserver.Observation> observations, FeedbackLookup feedback,
                                 double busyFraction) {
        long horizon = Math.min(settings.horizonNs(), Long.MAX_VALUE - startNs);
        ApModeSwitch.Profile switchProfile = currentMode == mode || switching == null
                ? null : switching.profileFrom(currentMode);
        boolean switchUnknown = currentMode != mode && switchProfile == null;
        long switchNs = switchProfile == null ? 0 : Math.min(horizon, switchProfile.delayNs());
        double switchUj = switchProfile == null ? 0 : ApEnergyModel.switchEnergyUj(switchNs, switchProfile.powerW()).doubleValue();
        double usable = (horizon - switchNs) * (busyFraction < 0 ? 1 : 1 - busyFraction);

        List<Flow> flows = new ArrayList<>();
        Confidence confidence = Confidence.IDLE;
        double totalWorkNs = 0;
        double demandBits = 0;
        double demandPackets = 0;
        double queuedPackets = 0;
        for (ApTrafficObserver.Observation observation : observations) {
            Flow flow = new Flow(observation, horizon);
            flows.add(flow);
            if (flow.demand == 0) {
                continue;
            }
            fillFlow(flow, mode, currentMode, startNs, feedback);
            confidence = confidence.ordinal() >= flow.confidence.ordinal() ? confidence : flow.confidence;
            totalWorkNs += flow.cycles() * flow.cycleNs;
            demandBits += flow.demand * flow.payloadBytes * 8;
            demandPackets += flow.demand;
            queuedPackets += flow.queued;
        }

        // 按预计各 AC 占用的服务时间分摊窗口容量。退避、空闲和外部忙碌不产生 AP TX/RX。
        double servedFraction = totalWorkNs == 0 ? 1 : Math.min(1, usable / totalWorkNs);
        double successBits = 0;
        double txNs = 0;
        double rxNs = 0;
        double ppdus = 0;
        double attempted = 0;
        double retryAttempts = 0;
        List<QosForecast> qos = new ArrayList<>();
        for (Flow flow : flows) {
            if (flow.demand > 0) {
                double exchanges = flow.cycles() * servedFraction;
                successBits += flow.demand * servedFraction * flow.deliveryProbability * 8 * flow.payloadBytes;
                txNs += exchanges * flow.txNs;
                rxNs += exchanges * flow.rxNs;
                ppdus += exchanges;
                attempted += exchanges * flow.depth;
                retryAttempts += flow.demand * servedFraction * (flow.attempts - 1);
            }
            if (flow.observation.direction() == Packet.Direction.DL) {
                qos.add(qosFor(flow, horizon, usable, servedFraction, switchNs, switchUnknown));
            }
        }
        double idleNs = Math.max(0, horizon - switchNs - txNs - rxNs);
        ApEnergyModel.Power power = energyModel.powerFor(mode);
        double txUj = txNs * power.txW().doubleValue() / 1_000;
        double rxUj = rxNs * power.rxW().doubleValue() / 1_000;
        double idleUj = idleNs * power.idleW().doubleValue() / 1_000;
        double energyUj = txUj + rxUj + idleUj + switchUj;
        return new Forecast(startNs, startNs + horizon, currentMode, mode, confidence, busyFraction,
                totalWorkNs == 0 ? 0 : demandBits / totalWorkNs * (usable / horizon) * 1_000,
                successBits / horizon * 1_000, ppdus == 0 ? 0 : attempted / ppdus,
                attempted == 0 ? 0 : retryAttempts / attempted, queuedPackets,
                demandPackets * (1 - servedFraction), totalWorkNs, txNs, rxNs, idleNs, txUj, rxUj, idleUj,
                energyUj, successBits == 0 ? -1 : energyUj / successBits,
                switchUnknown ? -1 : switchNs, switchUnknown ? -1 : switchUj, qos);
    }

    private void fillFlow(Flow flow, ApCapabilityMode mode, ApCapabilityMode currentMode, long startNs,
                          FeedbackLookup feedback) {
        History history = histories.get(mode).get(flow.observation.category());
        double previousDepth = histories.get(currentMode).get(flow.observation.category()).depth;
        int observedDepth = Double.isNaN(previousDepth) || flow.observation.direction() == Packet.Direction.UL
                ? 1 : (int) Math.round(previousDepth);
        int wantedDepth = ampdu == null ? 1 : (int) Math.min(ampdu.maxMpdus(), Math.max(observedDepth, flow.queued));
        Map<Integer, WirelessPhy> stations = profiles.get(mode);
        int depth = wantedDepth;
        for (WirelessPhy phy : stations.values()) {
            depth = fitDepth(phy, flow.payloadBytes, depth);
        }
        flow.depth = depth;
        double perSum = 0;
        double dataNs = 0;
        int knownStations = 0;
        for (Map.Entry<Integer, WirelessPhy> entry : stations.entrySet()) {
            WirelessPhy phy = entry.getValue();
            dataNs += dataNs(phy, flow.payloadBytes, depth);
            if (observedPer != null && flow.observation.direction() == Packet.Direction.DL) {
                double estimate = estimatePer(feedback, phy, entry.getKey(), flow.payloadBytes, startNs);
                if (!Double.isNaN(estimate)) {
                    perSum += estimate;
                    knownStations++;
                }
            }
        }
        dataNs /= stations.size();
        if (knownStations == stations.size() && observedPer != null) {
            flow.per = perSum / knownStations;
            flow.confidence = Confidence.FEEDBACK;
        } else if (flow.observation.direction() == Packet.Direction.DL && !Double.isNaN(history.error)) {
            flow.per = history.error;
            flow.confidence = Confidence.HISTORY_ONLY;
        } else {
            // 不完整的 CSI 时保留理想情况下的参考预算，C4 显式判为信息不足。
            flow.confidence = observedPer == null || flow.observation.direction() == Packet.Direction.UL
                    ? Confidence.NO_PHY_ERRORS : Confidence.CHANNEL_UNKNOWN;
        }
        double responseChance = 1 - StrictMath.pow(flow.per, depth);
        double ackNs = depth > 1 ? exchange.blockAckNs() : exchange.ackNs();
        long failWait = retry == null ? 0 : retry.ackTimeoutUs() * 1_000L;
        AccessCategory ac = flow.observation.category();
        double accessNs = (edca.aifsUs(ac) + edca.forCategory(ac).cwMin() * 0.5 * edca.slotTimeUs()) * 1_000;
        flow.cycleNs = accessNs + dataNs + responseChance * (exchange.sifsNs() + ackNs)
                + (1 - responseChance) * failWait;
        if (flow.observation.direction() == Packet.Direction.DL) {
            flow.txNs = dataNs;
            flow.rxNs = responseChance * ackNs;
        } else {
            flow.txNs = responseChance * ackNs;
            flow.rxNs = dataNs;
        }
        double attemptsLimit = retry == null ? 1 : retry.maxRetries() + 1.0;
        flow.deliveryProbability = flow.per == 0 ? 1 : -StrictMath.expm1(attemptsLimit * StrictMath.log(flow.per));
        flow.attempts = flow.per == 1 ? attemptsLimit : flow.deliveryProbability / (1 - flow.per);
    }

    private int fitDepth(WirelessPhy phy, int payloadBytes, int depth) {
        for (int count = depth; count > 1; count--) {
            try {
                if (aggregateBytes(phy, payloadBytes, count) <= ampdu.maxBytes()) {
                    dataNs(phy, payloadBytes, count);
                    return count;
                }
            } catch (IllegalArgumentException tooLong) {
                // 调整到满足当前 PHY 帧时长限制的最大完整深度。
            }
        }
        return 1;
    }

    private static int aggregateBytes(WirelessPhy phy, int bytes, int count) {
        int subframe = phy.ampduSubframeBytes(bytes);
        int padded = (subframe + 3) / 4 * 4;
        return padded * (count - 1) + subframe;
    }

    private static long dataNs(WirelessPhy phy, int bytes, int count) {
        return count == 1 ? phy.ppduDurationNs(bytes) : phy.ampduPpduTiming(aggregateBytes(phy, bytes, count)).durationNs();
    }

    private double estimatePer(FeedbackLookup feedback, WirelessPhy phy, int staId, int bytes, long nowNs) {
        double[] sinr = new double[phy.bandwidthMhz() / 20];
        for (int channel = 0; channel < sinr.length; channel++) {
            Optional<ChannelReport> report = feedback.report(staId, channel, nowNs);
            if (report.isEmpty() || !report.get().isUsableAt(nowNs)) {
                return Double.NaN;
            }
            sinr[channel] = report.get().sinrDb();
        }
        return observedPer.estimate(phy.mcs(), sinr, phy.mpduBytes(bytes));
    }

    private QosForecast qosFor(Flow flow, long horizon, double usable, double servedFraction,
                               long switchNs, boolean switchUnknown) {
        AccessCategory ac = flow.observation.category();
        ApAnalysisSettings.Requirement requirement = settings.requirement(ac);
        if (flow.demand == 0) {
            return new QosForecast(ac, Feasibility.FEASIBLE, 0, 0, 0, 1, requirement);
        }
        double serviceNs = flow.cycleNs * flow.attempts;
        double waitNs = usable == 0 ? -1 : (flow.queued / flow.depth) * serviceNs * horizon / usable;
        double pathNs = opticalDelayNs.getOrDefault(ac, 0.0);
        double delayNs = waitNs < 0 ? -1 : pathNs + switchNs + serviceNs + waitNs
                + Math.max(0, flow.observation.headWaitNs());
        double remaining = flow.demand * (1 - servedFraction);
        Feasibility status = Feasibility.FEASIBLE;
        if (flow.confidence == Confidence.CHANNEL_UNKNOWN) {
            status = Feasibility.CHANNEL_INFORMATION_INSUFFICIENT;
        } else if (remaining > flow.queued) {
            status = Feasibility.CAPACITY_INSUFFICIENT;
        } else if (delayNs < 0 || delayNs > requirement.deadlineNs()) {
            status = Feasibility.DELAY_RISK;
        } else if (flow.deliveryProbability < requirement.minOnTime()) {
            status = Feasibility.RELIABILITY_RISK;
        } else if (switchUnknown) {
            status = Feasibility.SWITCH_COST_UNKNOWN;
        }
        return new QosForecast(ac, status, waitNs, serviceNs, delayNs, flow.deliveryProbability, requirement);
    }

    private static final class History {
        private double error = Double.NaN;
        private double depth = Double.NaN;
    }

    private static final class Flow {
        private final ApTrafficObserver.Observation observation;
        private final double queued;
        private final double demand;
        private final int payloadBytes;
        private Confidence confidence = Confidence.IDLE;
        private int depth = 1;
        private double per, cycleNs, txNs, rxNs, attempts, deliveryProbability;

        private Flow(ApTrafficObserver.Observation observation, long horizonNs) {
            this.observation = observation;
            queued = observation.queuePackets() + observation.inFlightPackets() - observation.awaitingConfirmPackets();
            demand = queued + observation.ewmaArrivalPps() * horizonNs / 1_000_000_000.0;
            payloadBytes = typicalBytes(observation);
        }

        private static int typicalBytes(ApTrafficObserver.Observation observation) {
            if (observation.queuePackets() > 0) {
                return (int) Math.max(1, Math.round((double) observation.queueBytes() / observation.queuePackets()));
            }
            if (observation.ewmaArrivalPps() > 0) {
                return (int) Math.max(1, Math.round(observation.ewmaArrivalMbps() * 125_000 / observation.ewmaArrivalPps()));
            }
            return DEFAULT_PAYLOAD_BYTES;
        }

        private double cycles() {
            return demand * attempts / depth;
        }
    }

    public static final class Forecast {
        public final long startNs, endNs, switchNs;
        public final ApCapabilityMode currentMode, mode;
        public final Confidence confidence;
        public final double busyFraction, serviceMbps, successMbps, aggDepth, retryFraction, queuePackets,
                endingQueuePackets, workloadNs, txNs, rxNs, idleNs, txUj, rxUj, idleUj, energyUj,
                energyPerBitUj, switchUj;
        public final List<QosForecast> qos;
        public final boolean revised;
        public final double phyMbps, macMbps, conservativeMbps, optimisticMbps, incrementalEnergyUj, inputMbps;

        Forecast(long startNs, long endNs, ApCapabilityMode currentMode, ApCapabilityMode mode,
                         Confidence confidence, double busyFraction, double serviceMbps, double successMbps,
                         double aggDepth, double retryFraction, double queuePackets, double endingQueuePackets,
                         double workloadNs, double txNs, double rxNs, double idleNs, double txUj, double rxUj,
                         double idleUj, double energyUj, double energyPerBitUj, long switchNs, double switchUj,
                         List<QosForecast> qos) {
            this.startNs = startNs;
            this.endNs = endNs;
            this.currentMode = currentMode;
            this.mode = mode;
            this.confidence = confidence;
            this.busyFraction = busyFraction;
            this.serviceMbps = serviceMbps;
            this.successMbps = successMbps;
            this.aggDepth = aggDepth;
            this.retryFraction = retryFraction;
            this.queuePackets = queuePackets;
            this.endingQueuePackets = endingQueuePackets;
            this.workloadNs = workloadNs;
            this.txNs = txNs;
            this.rxNs = rxNs;
            this.idleNs = idleNs;
            this.txUj = txUj;
            this.rxUj = rxUj;
            this.idleUj = idleUj;
            this.energyUj = energyUj;
            this.energyPerBitUj = energyPerBitUj;
            this.switchNs = switchNs;
            this.switchUj = switchUj;
            this.qos = List.copyOf(qos);
            revised = false;
            phyMbps = macMbps = conservativeMbps = optimisticMbps = incrementalEnergyUj = inputMbps = -1;
        }

        Forecast(Forecast expected, double phyMbps, double macMbps, double conservativeMbps,
                 double optimisticMbps, double incrementalEnergyUj, double inputMbps, List<QosForecast> qos) {
            startNs = expected.startNs;
            endNs = expected.endNs;
            currentMode = expected.currentMode;
            mode = expected.mode;
            confidence = expected.confidence;
            busyFraction = expected.busyFraction;
            serviceMbps = expected.serviceMbps;
            successMbps = expected.successMbps;
            aggDepth = expected.aggDepth;
            retryFraction = expected.retryFraction;
            queuePackets = expected.queuePackets;
            endingQueuePackets = expected.endingQueuePackets;
            workloadNs = expected.workloadNs;
            txNs = expected.txNs;
            rxNs = expected.rxNs;
            idleNs = expected.idleNs;
            txUj = expected.txUj;
            rxUj = expected.rxUj;
            idleUj = expected.idleUj;
            energyUj = expected.energyUj;
            energyPerBitUj = expected.energyPerBitUj;
            switchNs = expected.switchNs;
            switchUj = expected.switchUj;
            this.qos = List.copyOf(qos);
            revised = true;
            this.phyMbps = phyMbps;
            this.macMbps = macMbps;
            this.conservativeMbps = Math.min(conservativeMbps, expected.successMbps);
            this.optimisticMbps = Math.max(optimisticMbps, expected.successMbps);
            this.incrementalEnergyUj = incrementalEnergyUj;
            this.inputMbps = inputMbps;
        }
    }

    public static final class QosForecast {
        public final AccessCategory ac;
        public final Feasibility status;
        public final Feasibility conservativeStatus, optimisticStatus;
        public final double waitingNs, serviceNs, e2eNs, successProbability;
        public final ApAnalysisSettings.Requirement requirement;

        QosForecast(AccessCategory ac, Feasibility status, double waitingNs, double serviceNs, double e2eNs,
                            double successProbability, ApAnalysisSettings.Requirement requirement) {
            this.ac = ac;
            this.status = status;
            conservativeStatus = optimisticStatus = status;
            this.waitingNs = waitingNs;
            this.serviceNs = serviceNs;
            this.e2eNs = e2eNs;
            this.successProbability = successProbability;
            this.requirement = requirement;
        }

        QosForecast(QosForecast expected, Feasibility conservative, Feasibility optimistic) {
            ac = expected.ac;
            status = expected.status;
            conservativeStatus = conservative;
            optimisticStatus = optimistic;
            waitingNs = expected.waitingNs;
            serviceNs = expected.serviceNs;
            e2eNs = expected.e2eNs;
            successProbability = expected.successProbability;
            requirement = expected.requirement;
        }
    }
}
