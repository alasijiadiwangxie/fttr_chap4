package edu.suda.fttr.energy;

import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.channel.ChannelTruth;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Packet;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static edu.suda.fttr.phy.ApCapabilityMode.HCM;
import static edu.suda.fttr.phy.ApCapabilityMode.LCM;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ApAnalysisTest {
    @Test
    void predictsOneKnownQueuedMessageUsingAnalyticAirtimeAndStateEnergy() {
        ApModePredictor.Forecast prediction = predictor(null, null).predict(2_000_000, HCM, oneWaiting(),
                (sta, channel, now) -> Optional.empty(), 0, 0).get(1);
        assertEquals(HCM, prediction.mode);
        assertEquals(12, prediction.successMbps, 1e-12);
        assertEquals(77_600, prediction.txNs, 1e-9);
        assertEquals(28_000, prediction.rxNs, 1e-9);
        assertEquals(894_400, prediction.idleNs, 1e-9);
        // 43 us AIFS + 77.6 us HE SU DATA + 16 us SIFS + 28 us ACK
        assertEquals(164_600, prediction.workloadNs, 1e-9);
        assertEquals(12_000_000.0 / 164_600, prediction.serviceMbps, 1e-10);
        assertEquals(77.6 * 10.50 + 28 * 8.70 + 894.4 * 7.62, prediction.energyUj, 1e-9);
        assertEquals(prediction.energyUj / 12_000, prediction.energyPerBitUj, 1e-12);
        assertEquals(0, prediction.endingQueuePackets, 1e-12);
    }

    @Test
    void excludesFutureAndExpiredReportsAndRequiresEveryOccupiedSubchannel() {
        SinrPerModel model = new SinrPerModel(1, 1530, Map.of(7, List.of(
                new SinrPerModel.Knot(-12, 1), new SinrPerModel.Knot(30, 0))));
        ObservedPerCurve curve = new DownlinkDecoder(new ChannelTruth(List.of()), model).observedPerCurve();
        var outputs = predictor(curve, null).predict(2_000_000, LCM, oneWaiting(),
                (sta, channel, now) -> {
                    switch (channel) {
                        case 0: return Optional.of(new ChannelReport(1, 1, 0, 1_000_000, 1_100_000, 2_000_000, 30, -1));
                        case 1: return Optional.of(new ChannelReport(1, 1, 1, 1_000_000, 2_000_001, 2_000_000, -12, -1));
                        case 2: return Optional.of(new ChannelReport(1, 1, 2, 1_000_000, 1_100_000, 1_000_000, -12, -1));
                        default: return Optional.empty();
                    }
                }, 0, -1);
        assertEquals(ApModePredictor.Confidence.FEEDBACK, outputs.get(0).confidence);
        assertEquals(ApModePredictor.Confidence.CHANNEL_UNKNOWN, outputs.get(1).confidence);
        assertEquals(ApModePredictor.Feasibility.CHANNEL_INFORMATION_INSUFFICIENT,
                outputs.get(1).qos.get(AccessCategory.BE.ordinal()).status);
    }

    @Test
    void reservesSwitchTimeAndEnergyOnlyForTheCandidateWithB4Profile() {
        var settings = new ApModeSwitch.Settings(Map.of(), new ApModeSwitch.Profile(100_000, BigDecimal.valueOf(5)),
                new ApModeSwitch.Profile(200_000, BigDecimal.valueOf(6)));
        var outputs = predictor(null, settings).predict(2_000_000, LCM, oneWaiting(),
                (sta, channel, now) -> Optional.empty(), 0, 0);
        var hcm = outputs.get(1);
        assertEquals(0, outputs.get(0).switchNs);
        assertEquals(100_000, hcm.switchNs);
        assertEquals(500, hcm.switchUj, 1e-10);
        assertEquals(1_000_000, hcm.switchNs + hcm.txNs + hcm.rxNs + hcm.idleNs, 1e-9);
    }

    @Test
    void measuresCutoffCohortJitterAndPendingWithoutTreatingAllIncompleteAsDrops() {
        ApQosLedger ledger = new ApQosLedger(1, analysis(100));
        Packet onTime = new Packet(1, 1500, 0);
        Packet late = new Packet(2, 1500, 0);
        Packet lost = new Packet(3, 1500, 0);
        Packet waiting = new Packet(4, 1500, 0);
        Packet inFiber = new Packet(5, 1500, 0);
        ledger.generated(onTime, 1, 0);
        ledger.generated(late, 1, 0);
        ledger.generated(lost, 1, 10);
        ledger.generated(waiting, 1, 30);
        var observer = new ApTrafficObserver(1_000, 1);
        ledger.queueChanged(1, observer.enqueue(waiting, 30));
        ledger.delivered(onTime, 50);
        ledger.delivered(late, 120);
        ledger.dropped(lost);
        ledger.generated(inFiber, 1, 150);
        ApQosLedger.Snapshot be = ledger.finish(200).get(AccessCategory.BE.ordinal());
        assertEquals(5, be.arrived);
        assertEquals(2, be.delivered);
        assertEquals(1, be.dropped);
        assertEquals(2, be.incomplete);
        assertEquals(1, be.overdue);
        assertEquals(1, be.onTime);
        assertEquals(3, be.violations);
        assertEquals(4, be.decided);
        assertEquals(85, be.avgNs);
        assertEquals(120, be.p95Ns);
        assertEquals(120, be.p99Ns);
        assertEquals(70, be.jitterNs);
        assertEquals(0.75, be.violationRate, 1e-12);
        assertEquals(0.20, be.lossRate, 1e-12);
        assertEquals(1, be.queuePackets);
        assertEquals(170, be.headWaitNs);
    }

    private static ApModePredictor predictor(ObservedPerCurve curve, ApModeSwitch.Settings switching) {
        Map<ApCapabilityMode, Map<Integer, WirelessPhy>> profiles = Map.of(
                LCM, Map.of(1, new WirelessPhy(20, 7, 1, 800, 34)),
                HCM, Map.of(1, new WirelessPhy(80, 7, 2, 800, 34)));
        EnumMap<AccessCategory, EdcaParameters.AcParameters> edca = new EnumMap<>(AccessCategory.class);
        for (AccessCategory ac : AccessCategory.values()) {
            edca.put(ac, new EdcaParameters.AcParameters(3, 0, 0));
        }
        return new ApModePredictor(analysis(10_000_000), profiles,
                new ApEnergyModel(ApEnergyModel.LCM_DEFAULT, ApEnergyModel.HCM_DEFAULT), switching,
                new WifiFrameExchange(16, 24), new EdcaParameters(16, 9, edca), new WifiRetryPolicy(60, 1),
                null, curve);
    }

    private static ApAnalysisSettings analysis(long deadline) {
        EnumMap<AccessCategory, ApAnalysisSettings.Requirement> requirements = new EnumMap<>(AccessCategory.class);
        for (AccessCategory ac : AccessCategory.values()) {
            requirements.put(ac, new ApAnalysisSettings.Requirement(deadline, 0.99));
        }
        return new ApAnalysisSettings(true, 1_000_000, 0.25, requirements);
    }

    private static List<ApTrafficObserver.Observation> oneWaiting() {
        var observer = new ApTrafficObserver(1_000_000, 1);
        observer.enqueue(new Packet(1, 1500, 0), 0);
        observer.sample(1_000_000, 0, 0);
        return observer.sample(2_000_000, 0, 0);
    }
}
