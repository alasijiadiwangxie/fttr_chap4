package edu.suda.fttr.energy;

import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.energy.ApModePredictor.Confidence;
import edu.suda.fttr.energy.ApModePredictor.DeadlineBatch;
import edu.suda.fttr.energy.ApModePredictor.Evidence;
import edu.suda.fttr.energy.ApModePredictor.Feasibility;
import edu.suda.fttr.energy.ApModePredictor.FeedbackLookup;
import edu.suda.fttr.energy.ApModePredictor.Forecast;
import edu.suda.fttr.energy.ApModePredictor.QosForecast;
import edu.suda.fttr.energy.ApModePredictor.ServiceForecast;
import edu.suda.fttr.energy.ApModePredictor.ServiceWork;
import edu.suda.fttr.energy.ApModePredictor.Transmission;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.ApTrafficObserver.Observation;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.AmpduConfig;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.phy.ObservedPerCurve;
import edu.suda.fttr.phy.WirelessPhy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 修正后的短时服务模型。输入限于 AP 已知队列、已结束的发送以及有时效约束的报告。 */
final class ObservedServicePrediction {
    private static final int DEFAULT_PAYLOAD = 1500;
    private static final double UNKNOWN_BUSY_EXPECTED = 0.25;
    private static final double UNKNOWN_BUSY_CONSERVATIVE = 0.50;
    private static final double UNKNOWN_ERROR_EXPECTED = 0.20;
    private static final double UNKNOWN_ERROR_CONSERVATIVE = 0.45;
    private static final double UNKNOWN_ERROR_OPTIMISTIC = 0.05;
    private static final double OBSERVED_CONTENTION_WEIGHT = 0.75;
    private static final long MIN_EXCHANGES = 4;

    private enum Estimate { EXPECTED, CONSERVATIVE, OPTIMISTIC }
    private enum Input { C3, CONTROL_ENERGY, CONTROL_CHANNEL }

    private final ApAnalysisSettings settings;
    private final Map<ApCapabilityMode, Map<Integer, WirelessPhy>> profiles;
    private final ApEnergyModel energy;
    private final ApModeSwitch.Settings switching;
    private final WifiFrameExchange exchange;
    private final EdcaParameters edca;
    private final long attemptsLimit;
    private final long timeoutNs;
    private final AmpduConfig ampdu;
    private final ObservedPerCurve observedPer;
    private final Map<ApCapabilityMode, ModeHistory> histories = new EnumMap<>(ApCapabilityMode.class);
    private ApCapabilityMode previousMode;

    ObservedServicePrediction(ApAnalysisSettings settings, Map<ApCapabilityMode, Map<Integer, WirelessPhy>> profiles,
                              ApEnergyModel energy, ApModeSwitch.Settings switching, WifiFrameExchange exchange,
                              EdcaParameters edca, WifiRetryPolicy retry, AmpduConfig ampdu, ObservedPerCurve observedPer) {
        this.settings = settings;
        this.profiles = profiles;
        this.energy = energy;
        this.switching = switching;
        this.exchange = exchange;
        this.edca = edca;
        this.attemptsLimit = retry == null ? 1 : retry.maxRetries() + 1L;
        this.timeoutNs = retry == null ? 0 : retry.ackTimeoutUs() * 1_000L;
        this.ampdu = ampdu;
        this.observedPer = observedPer;
        for (ApCapabilityMode mode : ApCapabilityMode.values()) {
            histories.put(mode, new ModeHistory());
        }
    }

    List<Forecast> predict(long nowNs, ApCapabilityMode current, List<Observation> observations, FeedbackLookup feedback,
                           double primaryBusy, double wideBusy, Evidence evidence,
                           Map<AccessCategory, Double> opticalDelayNs) {
        // B4 跨越采样窗口时，不能将该窗口的 PHY/MAC 实绩全部归入最终模式。
        if (previousMode == null || previousMode == current) {
            updateHistory(histories.get(current), evidence);
        }
        previousMode = current;
        Map<AccessCategory, List<Packet>> queues = groupQueues(evidence);
        return List.of(predictMode(nowNs, current, ApCapabilityMode.LCM, observations, feedback,
                        primaryBusy, primaryBusy, queues, opticalDelayNs),
                predictMode(nowNs, current, ApCapabilityMode.HCM, observations, feedback,
                        wideBusy, primaryBusy, queues, opticalDelayNs));
    }

    List<ServiceForecast> controlWork(long nowNs, ApCapabilityMode current, List<Observation> observations,
                                      FeedbackLookup feedback, double primaryBusy, double wideBusy, Evidence evidence,
                                      boolean useChannel, boolean mixedMode, boolean loadAware) {
        if (!mixedMode) {
            updateHistory(histories.get(current), evidence);
        }
        Map<AccessCategory, List<Packet>> queues = groupQueues(evidence);
        Input input = useChannel ? Input.CONTROL_CHANNEL : Input.CONTROL_ENERGY;
        return List.of(controlMode(nowNs, current, ApCapabilityMode.LCM, observations, feedback,
                        primaryBusy, primaryBusy, queues, input, loadAware),
                controlMode(nowNs, current, ApCapabilityMode.HCM, observations, feedback,
                        wideBusy, primaryBusy, queues, input, loadAware));
    }

    private ServiceForecast controlMode(long startNs, ApCapabilityMode current, ApCapabilityMode mode,
                                        List<Observation> observations, FeedbackLookup feedback, double busy,
                                        double primary, Map<AccessCategory, List<Packet>> queues, Input input, boolean loadAware) {
        long horizon = Math.min(settings.horizonNs(), Long.MAX_VALUE - startNs);
        Budget expected = budget(startNs, horizon, 0, current, mode, observations, queues, feedback,
                busy, primary, Estimate.EXPECTED, input, loadAware);
        Budget conservative = budget(startNs, horizon, 0, current, mode, observations, queues, feedback,
                busy, primary, Estimate.CONSERVATIVE, input, loadAware);
        Budget optimistic = budget(startNs, horizon, 0, current, mode, observations, queues, feedback,
                busy, primary, Estimate.OPTIMISTIC, input, loadAware);
        boolean incomplete = expected.packets > 0 && (expected.unknownBusy || expected.missingFeedback);
        return new ServiceForecast(mode, expected.confidence, busy, incomplete, expected.serviceWork(),
                conservative.serviceWork(), optimistic.serviceWork(), conservative.deadlineBatches,
                loadAware ? expected.saturatedMbps() : -1, loadAware ? expected.saturatedDepth() : -1);
    }

    private static Map<AccessCategory, List<Packet>> groupQueues(Evidence evidence) {
        Map<AccessCategory, List<Packet>> queues = new EnumMap<>(AccessCategory.class);
        for (Packet packet : evidence.waitingDlFifo) {
            if (!packet.isUplink()) {
                queues.computeIfAbsent(packet.type(), unused -> new ArrayList<>()).add(packet);
            }
        }
        return queues;
    }

    private void updateHistory(ModeHistory mode, Evidence evidence) {
        Map<Link, Counts> windows = new HashMap<>();
        long exchanges = 0;
        for (Transmission frame : evidence.completedPpdus) {
            exchanges++;
            if (frame.attemptedMpdus == 0) {
                continue;
            }
            Counts counts = windows.computeIfAbsent(new Link(frame.direction, frame.ac, frame.staId),
                    unused -> new Counts());
            counts.frames++;
            counts.attempted += frame.attemptedMpdus;
            counts.confirmed += frame.confirmedMpdus;
        }
        windows.forEach((link, counts) -> mode.links.computeIfAbsent(link, unused -> new LinkHistory()).update(counts));
        if (exchanges >= MIN_EXCHANGES && evidence.eligibleNs > evidence.blockedNs) {
            // occupiedNs 已包括 SIFS 等仍持有信道的时间，不再次从空闲竞争中扣除。
            long freeNs = evidence.eligibleNs - evidence.blockedNs - evidence.occupiedNs;
            mode.contentionNs = ewma(mode.contentionNs, (double) freeNs / exchanges);
        }
    }

    private double ewma(double previous, double sample) {
        return Double.isNaN(previous) ? sample : previous + settings.alpha() * (sample - previous);
    }

    private Forecast predictMode(long startNs, ApCapabilityMode current, ApCapabilityMode mode,
                                 List<Observation> observations, FeedbackLookup feedback, double busy, double primary,
                                 Map<AccessCategory, List<Packet>> queues, Map<AccessCategory, Double> paths) {
        long horizon = Math.min(settings.horizonNs(), Long.MAX_VALUE - startNs);
        ApModeSwitch.Profile transition = current == mode || switching == null ? null : switching.profileFrom(current);
        boolean switchUnknown = current != mode && transition == null;
        long switchNs = transition == null ? 0 : Math.min(horizon, transition.delayNs());
        double switchEnergy = transition == null ? 0
                : ApEnergyModel.switchEnergyUj(switchNs, transition.powerW()).doubleValue();

        Budget expected = budget(startNs, horizon, switchNs, current, mode, observations, queues, feedback,
                busy, primary, Estimate.EXPECTED, Input.C3, false);
        Budget conservative = budget(startNs, horizon, switchNs, current, mode, observations, queues, feedback,
                busy, primary, Estimate.CONSERVATIVE, Input.C3, false);
        Budget optimistic = budget(startNs, horizon, switchNs, current, mode, observations, queues, feedback,
                busy, primary, Estimate.OPTIMISTIC, Input.C3, false);
        List<QosForecast> qos = new ArrayList<>();
        for (int index = 0; index < observations.size(); index++) {
            Observation observation = observations.get(index);
            if (observation.direction() != Packet.Direction.DL) {
                continue;
            }
            double path = paths.getOrDefault(observation.category(), 0.0);
            QosForecast central = qos(expected, index, path, switchNs, switchUnknown);
            qos.add(new QosForecast(central, qos(conservative, index, path, switchNs, switchUnknown).status,
                    qos(optimistic, index, path, switchNs, switchUnknown).status));
        }
        double tx = expected.tx * expected.served;
        double rx = expected.rx * expected.served;
        double idle = Math.max(0, horizon - switchNs - tx - rx);
        ApEnergyModel.Power power = energy.powerFor(mode);
        double txUj = tx * power.txW().doubleValue() / 1_000;
        double rxUj = rx * power.rxW().doubleValue() / 1_000;
        double idleUj = idle * power.idleW().doubleValue() / 1_000;
        double energyUj = txUj + rxUj + idleUj + switchEnergy;
        double deliveredBits = expected.success * expected.served;
        double incrementalService = txUj + rxUj - (tx + rx) * power.idleW().doubleValue() / 1_000;
        double phyRate = profiles.get(mode).values().stream().mapToDouble(WirelessPhy::phyRateMbps).average().orElse(0);
        double serviceMbps = expected.capacityMbps(expected.offeredBits);
        Forecast central = new Forecast(startNs, startNs + horizon, current, mode, expected.confidence, busy,
                serviceMbps, expected.throughputMbps(), expected.frames == 0 ? 0 : expected.attempts / expected.frames,
                expected.attempts == 0 ? 0 : (expected.attempts - expected.packets) / expected.attempts,
                expected.queued, expected.packets * (1 - expected.served), expected.work,
                tx, rx, idle, txUj, rxUj, idleUj, energyUj, deliveredBits == 0 ? -1 : energyUj / deliveredBits,
                switchUnknown ? -1 : switchNs, switchUnknown ? -1 : switchEnergy, qos);
        return new Forecast(central, phyRate, expected.capacityMbps(expected.attemptedBits), conservative.throughputMbps(),
                optimistic.throughputMbps(), incrementalService, expected.arrivalBits * 1_000 / horizon, qos);
    }

    private Budget budget(long startNs, long horizon, long switchNs, ApCapabilityMode current, ApCapabilityMode mode,
                          List<Observation> observations, Map<AccessCategory, List<Packet>> queues,
                          FeedbackLookup feedback, double busy, double primary, Estimate estimate, Input input,
                          boolean loadAware) {
        ModeHistory prior = histories.get(mode);
        ModeHistory measured = histories.get(current);
        double blocked = busyEstimate(busy, primary, estimate);
        Budget total = new Budget(horizon, (horizon - switchNs) * (1 - blocked), busy < 0, blocked, loadAware);
        Map<Integer, WirelessPhy> stations = profiles.get(mode);
        for (Observation observation : observations) {
            Flow flow = new Flow(observation, horizon, input);
            total.flows.add(flow);
            if (flow.demand == 0 || stations.isEmpty()) {
                continue;
            }
            List<Packet> local = observation.direction() == Packet.Direction.DL
                    ? queues.getOrDefault(observation.category(), List.of()) : List.of();
            int offset = 0;
            while (offset < local.size()) {
                Packet head = local.get(offset);
                WirelessPhy phy = stations.get(head.staId());
                int depth = queueDepth(phy, local, offset);
                int bytes = 0;
                int aggregateBytes = 0;
                for (int member = 0; member < depth; member++) {
                    Packet packet = local.get(offset + member);
                    bytes += packet.bytes();
                    if (depth > 1) {
                        aggregateBytes = appendMpdu(phy, aggregateBytes, packet.bytes());
                    }
                }
                Link link = new Link(Packet.Direction.DL, observation.category(), head.staId());
                ErrorEstimate error = errorFor(startNs, feedback, phy, link, Math.max(1, bytes / depth),
                        prior, measured, estimate, input);
                Batch batch = batch(depth, dataNs(phy, depth, bytes, aggregateBytes), error.value,
                        contentionNs(link.ac, prior, measured, estimate));
                addBatch(flow, depth, bytes, depth, error, batch);
                if (input != Input.C3 && estimate == Estimate.CONSERVATIVE) {
                    total.deadlineBatches.add(new DeadlineBatch(local.subList(offset, offset + depth),
                            batch.airNs * batch.attempts, batch.success, batch.dataNs * batch.attempts,
                            batch.responseNs * batch.attempts));
                }
                if (loadAware) {
                    // 当前 FIFO 的同伴顺序已知，这部分容量保留真实可装载的批次。
                    total.addSaturated(depth, depth, bytes, batch);
                }
                offset += depth;
            }
            double remaining = Math.max(0, flow.demand - local.size());
            if (remaining > 0) {
                addPredictedArrivals(flow, remaining, stations, startNs, feedback, prior, measured, estimate, input, total);
            }
            total.add(flow);
        }
        total.served = total.work == 0 ? 1 : Math.min(1, total.available / total.work);
        return total;
    }

    private void addPredictedArrivals(Flow flow, double remaining, Map<Integer, WirelessPhy> stations, long startNs,
                                      FeedbackLookup feedback, ModeHistory prior, ModeHistory measured, Estimate kind,
                                      Input input, Budget total) {
        Packet.Direction direction = flow.observation.direction();
        AccessCategory ac = flow.observation.category();
        double shares = stations.keySet().stream().mapToDouble(sta -> flowWeight(measured, direction, ac, sta)).sum();
        for (Map.Entry<Integer, WirelessPhy> entry : stations.entrySet()) {
            int sta = entry.getKey();
            WirelessPhy phy = entry.getValue();
            double weight = flowWeight(measured, direction, ac, sta);
            double count = remaining * weight / shares;
            if (count <= 0) {
                continue;
            }
            Link link = new Link(direction, ac, sta);
            LinkHistory history = prior.links.getOrDefault(link, measured.links.get(link));
            int bytes = flow.forecastBytes;
            int depth = history == null ? 1 : (int) Math.round(history.depth);
            ErrorEstimate error = errorFor(startNs, feedback, phy, link, bytes, prior, measured, kind, input);
            double access = contentionNs(ac, prior, measured, kind);
            if (total.loadAware && direction == Packet.Direction.DL) {
                // 两档用同一目标流量和接收者布局。候选用自身交换时长推导能跟上负载的最小深度。
                double peerShare = weight / shares;
                int maxDepth = ampdu == null ? 1 : ampdu.maxMpdus();
                int contiguous = peerShare == 1 ? maxDepth
                        : (int) Math.max(1, Math.min(maxDepth, Math.round(1 / (1 - peerShare))));
                int cap = fittedDepth(phy, contiguous, bytes);
                int limited = Math.min(cap, Math.max(1, (int) Math.ceil(count)));
                double offeredPps = flow.observation.ewmaArrivalPps() * peerShare;
                depth = sustainableDepth(phy, bytes, offeredPps, limited, error.value, access, total.busyFraction);
                total.addSaturated(cap, count, (double) cap * bytes, equalBatch(phy, cap, bytes, error.value, access));
            } else {
                depth = Math.min(depth, Math.max(1, (int) Math.ceil(count)));
            }
            depth = fittedDepth(phy, depth, bytes);
            Batch batch = equalBatch(phy, depth, bytes, error.value, access);
            addBatch(flow, depth, (double) depth * bytes, count, error, batch);
            if (total.loadAware && direction == Packet.Direction.UL) {
                // AP 不知道 STA 的私有队列，沿用实际可见的上行批次深度。
                total.addSaturated(depth, count, (double) depth * bytes, batch);
            }
        }
    }

    private int sustainableDepth(WirelessPhy phy, int bytes, double pps, int limit, double error,
                                 double access, double busy) {
        for (int depth = 1; depth < limit; depth++) {
            Batch batch = equalBatch(phy, depth, bytes, error, access);
            if (depth * batch.success * (1 - busy) * 1e9 >= pps * batch.attempts * batch.airNs) {
                return depth;
            }
        }
        return limit;
    }

    private Batch equalBatch(WirelessPhy phy, int depth, int bytes, double error, double access) {
        return batch(depth, dataNs(phy, depth, bytes, depth == 1 ? 0 : equalMpduBytes(phy, depth, bytes)), error, access);
    }

    private static double flowWeight(ModeHistory current, Packet.Direction direction, AccessCategory ac, int sta) {
        LinkHistory history = current.links.get(new Link(direction, ac, sta));
        return history == null ? 1 : Math.max(1, history.attempted);
    }

    private Batch batch(int depth, long dataNs, double failure, double access) {
        double deliveredProbability = failure == 1 ? 0 : failure == 0 ? 1
                : -StrictMath.expm1(attemptsLimit * StrictMath.log(failure));
        double attempts = failure == 1 ? attemptsLimit : deliveredProbability / (1 - failure);
        double responseChance = 1 - StrictMath.pow(failure, depth);
        double responseNs = depth > 1 ? exchange.blockAckNs() : exchange.ackNs();
        double cycle = dataNs + responseChance * (exchange.sifsNs() + responseNs)
                + (1 - responseChance) * timeoutNs + access;
        return new Batch(cycle, dataNs, responseChance * responseNs, attempts, deliveredProbability);
    }

    private static void addBatch(Flow flow, int depth, double bytes, double count, ErrorEstimate error, Batch batch) {
        double exchanges = count * batch.attempts / depth;
        boolean uplink = flow.observation.direction() == Packet.Direction.UL;
        flow.work += exchanges * batch.airNs;
        flow.tx += exchanges * (uplink ? batch.responseNs : batch.dataNs);
        flow.rx += exchanges * (uplink ? batch.dataNs : batch.responseNs);
        flow.frames += exchanges;
        flow.attempts += count * batch.attempts;
        flow.offeredBits += count * bytes * 8 / depth;
        flow.attemptedBits += count * bytes * 8 * batch.attempts / depth;
        flow.success += count * bytes * 8 * batch.success / depth;
        flow.successPackets += count * batch.success;
        flow.failedFirst += count * error.value;
        flow.confidence = worse(flow.confidence, error.confidence);
    }

    private double contentionNs(AccessCategory ac, ModeHistory prior, ModeHistory measured, Estimate kind) {
        double analytic = (edca.aifsUs(ac) + edca.slotTimeUs() * edca.forCategory(ac).cwMin() * 0.5) * 1_000;
        double empirical = Double.isNaN(prior.contentionNs) ? measured.contentionNs : prior.contentionNs;
        double expected = Double.isNaN(empirical) ? analytic
                : OBSERVED_CONTENTION_WEIGHT * empirical + (1 - OBSERVED_CONTENTION_WEIGHT) * analytic;
        if (Double.isNaN(empirical)) {
            return kind == Estimate.CONSERVATIVE ? 1.25 * expected : expected;
        }
        switch (kind) {
            case CONSERVATIVE: return Math.max(analytic, expected) * 1.25;
            case OPTIMISTIC: return Math.min(analytic, expected);
            default: return expected;
        }
    }

    private ErrorEstimate errorFor(long nowNs, FeedbackLookup feedback, WirelessPhy phy, Link link, int bytes,
                                   ModeHistory prior, ModeHistory measured, Estimate kind, Input input) {
        if (input == Input.CONTROL_ENERGY) {
            return new ErrorEstimate(0, Confidence.NO_PHY_ERRORS);
        }
        LinkHistory history = prior.links.get(link);
        double csi = link.direction == Packet.Direction.DL && observedPer != null
                ? observedError(nowNs, feedback, phy, link.sta, bytes) : Double.NaN;
        if (!Double.isNaN(csi)) {
            double central = history == null ? csi : (csi + history.error) / 2;
            double min = history == null ? csi : Math.min(csi, history.error);
            double max = history == null ? csi : Math.max(csi, history.error);
            return new ErrorEstimate(select(kind, central, max, min), Confidence.FEEDBACK);
        }
        if (history != null) {
            double uncertainty = 0.5 / Math.sqrt(Math.max(1, history.attempted));
            return new ErrorEstimate(select(kind, history.error, Math.min(1, history.error + uncertainty),
                    Math.max(0, history.error - uncertainty)), Confidence.HISTORY_ONLY);
        }
        if (link.direction == Packet.Direction.UL || observedPer == null) {
            LinkHistory current = measured.links.get(link);
            return new ErrorEstimate(current == null ? 0 : current.error, Confidence.NO_PHY_ERRORS);
        }
        LinkHistory current = measured.links.get(link);
        double central = current == null ? UNKNOWN_ERROR_EXPECTED : (current.error + UNKNOWN_ERROR_EXPECTED) / 2;
        return new ErrorEstimate(select(kind, central, Math.max(central, UNKNOWN_ERROR_CONSERVATIVE),
                Math.min(central, UNKNOWN_ERROR_OPTIMISTIC)), Confidence.CHANNEL_UNKNOWN);
    }

    private double observedError(long nowNs, FeedbackLookup feedback, WirelessPhy phy, int sta, int bytes) {
        double[] sinrs = new double[phy.bandwidthMhz() / 20];
        for (int index = 0; index < sinrs.length; index++) {
            Optional<ChannelReport> report = feedback.report(sta, index, nowNs);
            if (report.isEmpty() || !report.get().isUsableAt(nowNs)) {
                return Double.NaN;
            }
            sinrs[index] = report.get().sinrDb();
        }
        return observedPer.estimate(phy.mcs(), sinrs, phy.mpduBytes(bytes));
    }

    private int queueDepth(WirelessPhy phy, List<Packet> queue, int offset) {
        if (ampdu == null || ampdu.maxMpdus() == 1) {
            return 1;
        }
        int sta = queue.get(offset).staId();
        int aggregateBytes = appendMpdu(phy, 0, queue.get(offset).bytes());
        for (int depth = 1; depth < ampdu.maxMpdus() && offset + depth < queue.size(); depth++) {
            Packet next = queue.get(offset + depth);
            if (next.staId() != sta) {
                return depth;
            }
            int candidate = appendMpdu(phy, aggregateBytes, next.bytes());
            if (!fits(phy, candidate)) {
                return depth;
            }
            aggregateBytes = candidate;
        }
        return Math.min(ampdu.maxMpdus(), queue.size() - offset);
    }

    private int fittedDepth(WirelessPhy phy, int depth, int bytes) {
        if (ampdu == null) {
            return 1;
        }
        for (int count = Math.min(depth, ampdu.maxMpdus()); count > 1; count--) {
            if (fits(phy, equalMpduBytes(phy, count, bytes))) {
                return count;
            }
        }
        return 1;
    }

    private static int equalMpduBytes(WirelessPhy phy, int count, int payloadBytes) {
        int mpdu = phy.ampduSubframeBytes(payloadBytes);
        return ((mpdu + 3) & ~3) * (count - 1) + mpdu;
    }

    private static int appendMpdu(WirelessPhy phy, int length, int payloadBytes) {
        return ((length + 3) & ~3) + phy.ampduSubframeBytes(payloadBytes);
    }

    private boolean fits(WirelessPhy phy, int aggregateBytes) {
        if (aggregateBytes > ampdu.maxBytes()) {
            return false;
        }
        try {
            phy.ampduPpduTiming(aggregateBytes);
            return true;
        } catch (IllegalArgumentException exceedsDuration) {
            return false;
        }
    }

    private static long dataNs(WirelessPhy phy, int depth, int singlePayloadBytes, int aggregateBytes) {
        return depth == 1 ? phy.ppduDurationNs(singlePayloadBytes) : phy.ampduPpduTiming(aggregateBytes).durationNs();
    }

    private static double busyEstimate(double requested, double primary, Estimate kind) {
        if (requested >= 0) {
            return requested;
        }
        double availableLowerBound = Math.max(0, primary);
        return select(kind, Math.max(availableLowerBound, UNKNOWN_BUSY_EXPECTED),
                Math.max(availableLowerBound, UNKNOWN_BUSY_CONSERVATIVE), availableLowerBound);
    }

    private static double select(Estimate kind, double expected, double conservative, double optimistic) {
        switch (kind) {
            case CONSERVATIVE: return conservative;
            case OPTIMISTIC: return optimistic;
            default: return expected;
        }
    }

    private static Confidence worse(Confidence left, Confidence right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private QosForecast qos(Budget budget, int index, double path, long switchNs, boolean switchUnknown) {
        Flow flow = budget.flows.get(index);
        AccessCategory ac = flow.observation.category();
        ApAnalysisSettings.Requirement requirement = settings.requirement(ac);
        if (flow.demand == 0) {
            return new QosForecast(ac, Feasibility.FEASIBLE, 0, 0, 0, 1, requirement);
        }
        double service = flow.work / flow.demand;
        double wait = budget.available <= 0 ? -1 : flow.queued * service * budget.horizon / budget.available;
        double delay = wait < 0 ? -1 : path + switchNs + wait + service + Math.max(0, flow.observation.headWaitNs());
        double success = flow.successPackets / flow.demand * budget.served;
        Feasibility status = Feasibility.FEASIBLE;
        if (flow.confidence == Confidence.CHANNEL_UNKNOWN || budget.unknownBusy) {
            status = Feasibility.CHANNEL_INFORMATION_INSUFFICIENT;
        } else if (flow.demand * (1 - budget.served) > flow.queued && flow.arriving > 0) {
            status = Feasibility.CAPACITY_INSUFFICIENT;
        } else if (delay < 0 || delay > requirement.deadlineNs()) {
            status = Feasibility.DELAY_RISK;
        } else if (success < requirement.minOnTime()) {
            status = Feasibility.RELIABILITY_RISK;
        } else if (switchUnknown) {
            status = Feasibility.SWITCH_COST_UNKNOWN;
        }
        return new QosForecast(ac, status, wait, service, delay, success, requirement);
    }

    private static final class Budget {
        private final long horizon;
        private final double available;
        private final boolean unknownBusy;
        private final double busyFraction;
        private final boolean loadAware;
        private final List<Flow> flows = new ArrayList<>();
        private final List<DeadlineBatch> deadlineBatches = new ArrayList<>();
        private Confidence confidence = Confidence.IDLE;
        private double packets, queued, offeredBits, attemptedBits, arrivalBits, success, frames, attempts, work, tx, rx;
        private double served;
        private double failedFirst;
        private boolean missingFeedback;
        private double saturatedBits, saturatedNs, saturatedFrames, saturatedMpdus;

        private Budget(long horizon, double available, boolean unknownBusy, double busyFraction, boolean loadAware) {
            this.horizon = horizon;
            this.available = available;
            this.unknownBusy = unknownBusy;
            this.busyFraction = busyFraction;
            this.loadAware = loadAware;
        }

        private void addSaturated(int depth, double count, double bytes, Batch batch) {
            double exchanged = count * batch.attempts / depth;
            saturatedBits += count * bytes * 8 * batch.success / depth;
            saturatedNs += exchanged * batch.airNs;
            saturatedFrames += exchanged;
            saturatedMpdus += count * batch.attempts;
        }

        private double saturatedMbps() {
            return saturatedNs == 0 ? 0 : saturatedBits / saturatedNs * (1 - busyFraction) * 1_000;
        }

        private double saturatedDepth() {
            return saturatedFrames == 0 ? 0 : saturatedMpdus / saturatedFrames;
        }

        private void add(Flow flow) {
            confidence = worse(confidence, flow.confidence);
            packets += flow.demand;
            queued += flow.queued;
            offeredBits += flow.offeredBits;
            attemptedBits += flow.attemptedBits;
            arrivalBits += flow.arriving * flow.forecastBytes * 8;
            success += flow.success;
            frames += flow.frames;
            attempts += flow.attempts;
            work += flow.work;
            tx += flow.tx;
            rx += flow.rx;
            failedFirst += flow.failedFirst;
            if (flow.observation.direction() == Packet.Direction.DL
                    && (flow.confidence == Confidence.HISTORY_ONLY || flow.confidence == Confidence.CHANNEL_UNKNOWN)) {
                missingFeedback = true;
            }
        }

        private ServiceWork serviceWork() {
            return new ServiceWork(offeredBits, packets, arrivalBits, success, work, tx, rx, busyFraction,
                    packets == 0 ? 0 : failedFirst / packets, frames == 0 ? 0 : attempts / frames);
        }

        private double capacityMbps(double bits) {
            return work == 0 ? 0 : bits / work * available / horizon * 1_000;
        }

        private double throughputMbps() {
            return success * served * 1_000 / horizon;
        }
    }

    private static final class Batch {
        private final double airNs, dataNs, responseNs, attempts, success;

        private Batch(double airNs, double dataNs, double responseNs, double attempts, double success) {
            this.airNs = airNs;
            this.dataNs = dataNs;
            this.responseNs = responseNs;
            this.attempts = attempts;
            this.success = success;
        }
    }

    private static final class Flow {
        private final Observation observation;
        private final double queued, arriving, demand;
        private final int forecastBytes;
        private Confidence confidence = Confidence.IDLE;
        private double offeredBits, attemptedBits, success, successPackets, frames, attempts, work, tx, rx, failedFirst;

        private Flow(Observation observation, long horizon, Input input) {
            this.observation = observation;
            // D2 将已发出但尚未结算的事务归入两种方案共用的前缀；本轮仅预测新的调度工作。
            queued = observation.queuePackets() + (input == Input.C3
                    ? observation.inFlightPackets() - observation.awaitingConfirmPackets() : 0);
            arriving = observation.ewmaArrivalPps() * horizon / 1e9;
            demand = queued + arriving;
            forecastBytes = observation.ewmaArrivalPps() > 0 ? Math.max(1,
                    (int) Math.round(observation.ewmaArrivalMbps() * 125_000 / observation.ewmaArrivalPps()))
                    : observation.queuePackets() > 0 ? Math.max(1, (int) Math.round(
                    (double) observation.queueBytes() / observation.queuePackets())) : DEFAULT_PAYLOAD;
        }
    }

    private static final class ErrorEstimate {
        private final double value;
        private final Confidence confidence;

        private ErrorEstimate(double value, Confidence confidence) {
            this.value = value;
            this.confidence = confidence;
        }
    }

    private static final class ModeHistory {
        private double contentionNs = Double.NaN;
        private final Map<Link, LinkHistory> links = new HashMap<>();
    }

    private final class LinkHistory {
        private double depth = Double.NaN;
        private double error = Double.NaN;
        private double attempted = Double.NaN;

        private void update(Counts counts) {
            depth = ewma(depth, (double) counts.attempted / counts.frames);
            error = ewma(error, 1 - (double) counts.confirmed / counts.attempted);
            attempted = ewma(attempted, counts.attempted);
        }
    }

    private static final class Counts {
        private long frames, attempted, confirmed;
    }

    private static final class Link {
        private final Packet.Direction direction;
        private final AccessCategory ac;
        private final int sta;

        private Link(Packet.Direction direction, AccessCategory ac, int sta) {
            this.direction = direction;
            this.ac = ac;
            this.sta = sta;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Link)) {
                return false;
            }
            Link link = (Link) other;
            return direction == link.direction && ac == link.ac && sta == link.sta;
        }

        @Override
        public int hashCode() {
            return Objects.hash(direction, ac, sta);
        }
    }
}
