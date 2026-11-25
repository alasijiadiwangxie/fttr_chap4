package edu.suda.fttr.energy;

import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.channel.ChannelTruth;
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
import edu.suda.fttr.phy.DownlinkDecoder;
import edu.suda.fttr.phy.ObservedPerCurve;
import edu.suda.fttr.phy.SinrPerModel;
import edu.suda.fttr.phy.WirelessPhy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static edu.suda.fttr.phy.ApCapabilityMode.HCM;
import static edu.suda.fttr.phy.ApCapabilityMode.LCM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApServicePredictionTest {
    private static final ApModePredictor.FeedbackLookup EMPTY = (sta, channel, now) -> Optional.empty();

    @Test
    void separatesAnalyticCapacityDemandAndIncrementalEnergy() {
        List<Packet> queue = List.of(packet(1, 1));
        var forecasts = predictor(null, null, false).predictRevised(2_000_000, HCM, windows(queue), EMPTY,
                0, 0, evidence(queue));
        var hcm = forecasts.get(1);
        assertTrue(hcm.revised);
        assertEquals(12, hcm.successMbps, 1e-10);
        assertEquals(12_000_000.0 / 164_600, hcm.macMbps, 1e-10);
        assertTrue(hcm.phyMbps > hcm.macMbps);
        assertEquals(0, hcm.inputMbps);
        assertEquals(77_600, hcm.txNs);
        assertEquals(28_000, hcm.rxNs);
        assertEquals(894_400, hcm.idleNs);
        assertEquals(77.6 * (10.50 - 7.62) + 28 * (8.70 - 7.62), hcm.incrementalEnergyUj, 1e-10);
        assertFalse(predictor(null, null, false).predict(2_000_000, HCM, windows(queue), EMPTY, 0, 0).get(1).revised);
    }

    @Test
    void limitsBurstToContiguousFifoPeersOfEachAc() {
        List<Packet> separate = List.of(packet(1, 1), packet(2, 2), packet(3, 1), packet(4, 2));
        List<Packet> consecutive = List.of(packet(1, 1), packet(2, 1), packet(3, 2), packet(4, 2));
        var singles = predictor(null, null, true).predictRevised(2_000_000, HCM, windows(separate), EMPTY,
                0, 0, evidence(separate)).get(1);
        var pairs = predictor(null, null, true).predictRevised(2_000_000, HCM, windows(consecutive), EMPTY,
                0, 0, evidence(consecutive)).get(1);
        assertEquals(1, singles.aggDepth);
        assertEquals(2, pairs.aggDepth);
        assertTrue(pairs.workloadNs < singles.workloadNs);
    }

    @Test
    void separatesObservedContentionFromBlockedTimeAndOwnedAirtime() {
        List<Packet> queue = new ArrayList<>();
        for (int id = 1; id <= 10; id++) {
            queue.add(packet(id, 1));
        }
        var frame = new ApModePredictor.Transmission(Packet.Direction.DL, AccessCategory.BE,
                1, 1, 1, 1500, 40_000, 10_000, 60_000);
        var history = new ApModePredictor.Evidence(queue, List.of(frame, frame, frame, frame),
                1_000_000, 240_000, 160_000);
        var forecast = predictor(null, null, false).predictRevised(2_000_000, HCM, windows(queue), EMPTY,
                0.16, 0.16, history).get(1);
        double accessNs = 0.75 * 150_000 + 0.25 * 43_000;
        assertEquals(accessNs + 77_600 + 44_000, forecast.workloadNs / 10, 1e-8);
        assertTrue(forecast.successMbps < forecast.phyMbps);
        assertEquals(1_000_000, forecast.txNs + forecast.rxNs + forecast.idleNs, 1e-8);
    }

    @Test
    void usesBoundsAndInformationInsufficientForExpiredAndFutureReports() {
        List<Packet> queue = List.of(packet(1, 1), packet(2, 1), packet(3, 1), packet(4, 1),
                packet(5, 2), packet(6, 2), packet(7, 2), packet(8, 2));
        var curve = new DownlinkDecoder(new ChannelTruth(List.of()), new SinrPerModel(1, 1530, Map.of(7, List.of(
                new SinrPerModel.Knot(-12, 1), new SinrPerModel.Knot(30, 0))))).observedPerCurve();
        var predict = predictor(curve, null, false);
        var forecast = predict.predictRevised(2_000_000, HCM, windows(queue), (sta, channel, now) ->
                channel == 0 ? Optional.of(new ChannelReport(1, sta, 0, 1_000_000, 1_100_000, 1_000_000, 30, -1))
                        : Optional.of(new ChannelReport(1, sta, channel, 1_000_000, 2_000_001, 2_000_000, 30, -1)),
                0.10, -1, evidence(queue)).get(1);
        assertEquals(ApModePredictor.Confidence.CHANNEL_UNKNOWN, forecast.confidence);
        assertTrue(forecast.conservativeMbps < forecast.successMbps);
        assertTrue(forecast.successMbps < forecast.optimisticMbps);
        var be = forecast.qos.get(AccessCategory.BE.ordinal());
        assertEquals(ApModePredictor.Feasibility.CHANNEL_INFORMATION_INSUFFICIENT, be.conservativeStatus);
    }

    @Test
    void accountsForSwitchEnergySeparatelyFromServiceEnergy() {
        List<Packet> queue = List.of(packet(1, 1));
        var switchSettings = new ApModeSwitch.Settings(Map.of(),
                new ApModeSwitch.Profile(100_000, BigDecimal.valueOf(5)),
                new ApModeSwitch.Profile(200_000, BigDecimal.valueOf(6)));
        var candidate = predictor(null, switchSettings, false).predictRevised(2_000_000, LCM, windows(queue), EMPTY,
                0, 0, evidence(queue)).get(1);
        assertEquals(500, candidate.switchUj);
        assertEquals(1_000_000, candidate.switchNs + candidate.txNs + candidate.rxNs + candidate.idleNs, 1e-9);
        assertEquals(77.6 * (10.50 - 7.62) + 28 * (8.70 - 7.62), candidate.incrementalEnergyUj, 1e-10);
    }

    @Test
    void loadAwareDepthDoesNotConfuseALightQueueWithSaturatedHcmCapacity() {
        var predictor = predictor(null, null, true);
        var observer = new ApTrafficObserver(5_000_000, .25);
        for (int id = 1; id <= 35; id++) {
            Packet packet = packet(id, 1);
            observer.enqueue(packet, 0);
            observer.start(packet, 0);
            observer.deliver(packet, 0);
            observer.confirm(packet, 0);
        }
        var snapshot = observer.sample(5_000_000, 0, 0);
        var frame = new ApModePredictor.Transmission(Packet.Direction.DL, AccessCategory.BE,
                1, 1, 1, 1500, 77_600, 28_000, 121_600);
        var evidence = new ApModePredictor.Evidence(List.of(), java.util.Collections.nCopies(35, frame), 0, 0, 0);
        var legacy = predictor.predictControlWork(5_000_000, HCM, snapshot, EMPTY, 0, 0, evidence, false, false).get(1);
        var corrected = predictor.predictControlWork(5_000_000, HCM, snapshot, EMPTY, 0, 0, evidence, false, true, true).get(1);
        assertEquals(1, legacy.expected.ampduDepth);
        assertEquals(legacy.expected.demandBits, corrected.expected.demandBits, 1e-8);
        assertTrue(corrected.expected.ampduDepth > 1);
        assertTrue(corrected.saturatedMbps > corrected.expected.successBits / corrected.expected.workNs * 1000);
        assertTrue(corrected.saturatedDepth > corrected.expected.ampduDepth);
    }

    private static Packet packet(int id, int sta) {
        return new Packet(id, 1500, 0, AccessCategory.BE, sta);
    }

    private static List<ApTrafficObserver.Observation> windows(List<Packet> waiting) {
        var observer = new ApTrafficObserver(1_000_000, 1);
        waiting.forEach(packet -> observer.enqueue(packet, 0));
        observer.sample(1_000_000, 0, 0);
        return observer.sample(2_000_000, 0, 0);
    }

    private static ApModePredictor.Evidence evidence(List<Packet> queue) {
        return new ApModePredictor.Evidence(queue, List.of(), 0, 0, 0);
    }

    private static ApModePredictor predictor(ObservedPerCurve curve, ApModeSwitch.Settings switching, boolean aggregate) {
        var lcm = new WirelessPhy(20, 7, 1, 800, 34);
        var hcm = new WirelessPhy(80, 7, 2, 800, 34);
        Map<ApCapabilityMode, Map<Integer, WirelessPhy>> profiles = Map.of(
                LCM, Map.of(1, lcm, 2, lcm), HCM, Map.of(1, hcm, 2, hcm));
        EnumMap<AccessCategory, ApAnalysisSettings.Requirement> qos = new EnumMap<>(AccessCategory.class);
        EnumMap<AccessCategory, EdcaParameters.AcParameters> edca = new EnumMap<>(AccessCategory.class);
        for (AccessCategory ac : AccessCategory.values()) {
            qos.put(ac, new ApAnalysisSettings.Requirement(10_000_000, 0.95));
            edca.put(ac, new EdcaParameters.AcParameters(3, 0, 0));
        }
        return new ApModePredictor(new ApAnalysisSettings(true, 1_000_000, 0.25, qos), profiles,
                new ApEnergyModel(ApEnergyModel.LCM_DEFAULT, ApEnergyModel.HCM_DEFAULT), switching,
                new WifiFrameExchange(16, 24), new EdcaParameters(16, 9, edca), new WifiRetryPolicy(60, 1),
                aggregate ? new AmpduConfig(8, 65535, Set.of()) : null, curve);
    }
}
