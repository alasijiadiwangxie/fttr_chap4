package edu.suda.fttr.energy;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.phy.ApCapabilityMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static edu.suda.fttr.phy.ApCapabilityMode.HCM;
import static edu.suda.fttr.phy.ApCapabilityMode.LCM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ApDeadlineModePolicyTest {
    private static final ApModeSwitch.Settings SWITCH = new ApModeSwitch.Settings(Map.of(),
            new ApModeSwitch.Profile(40_125, new BigDecimal("11.50")),
            new ApModeSwitch.Profile(25_250, new BigDecimal("8.75")));
    private static final long HORIZON = 1_000_000;

    @Test
    void computesDelayWithOriginalGenerationQueueServiceAndBothWaits() {
        Packet packet = new Packet(1, 1500, 50, AccessCategory.BE);
        var ready = new ApModeSwitch.Readiness(LCM, LCM, 200_000, 60_000, 20_000, 0, false);
        var result = choose(100_000, ready, true, 1_000_000,
                forecast(LCM, packet, 700_000, 1, .125), forecast(HCM, packet, 250_000, 1, .25));
        var high = result.candidates.get(1);
        // 用原始生成时刻，剩 950 us。共享交换 200 us，另有 100 us CCA，切换 40.125 us，
        // 服务需要 250/0.75 us，预测裕量 50 us。纳秒向下取整。
        long expected = (long) Math.floor(950_000 - 200_000 - 100_000 - 40_125 - 250_000 / .75 - 50_000);
        assertEquals(ApDeadlineModePolicy.Status.DEADLINE_RISK, result.candidates.get(0).status);
        assertEquals(ApDeadlineModePolicy.Status.FEASIBLE, high.status);
        assertEquals(HCM, result.target);
        assertEquals(expected, result.maxDelayNs);
        assertEquals(100_000, high.externalWaitNs, 1e-9);
    }

    @Test
    void bothFailingModesAreRankedByViolationRiskAndCanPreferLcm() {
        Packet packet = new Packet(1, 1500, 0, AccessCategory.VO);
        var ready = new ApModeSwitch.Readiness(HCM, HCM, 0, 0, 0, 0, false);
        var result = choose(0, ready, false, 500_000,
                forecast(LCM, packet, 200_000, .9, 0), forecast(HCM, packet, 100_000, .1, 0));
        assertEquals(ApDeadlineModePolicy.Status.RELIABILITY_RISK, result.candidates.get(0).status);
        assertEquals(ApDeadlineModePolicy.Status.RELIABILITY_RISK, result.candidates.get(1).status);
        assertEquals(ApDeadlineModePolicy.Reason.LOWER_VIOLATION_RISK, result.reason);
        assertEquals(LCM, result.target);
        assertEquals(0, result.maxDelayNs);
    }

    @Test
    void usesEnergyWhenDeadlinesAreFeasible() {
        Packet packet = new Packet(1, 1500, 0, AccessCategory.BK);
        var ready = new ApModeSwitch.Readiness(LCM, LCM, 0, 0, 0, 0, false);
        var result = choose(0, ready, false, 1_000_000,
                forecast(LCM, packet, 300_000, 1, 0), forecast(HCM, packet, 200_000, 1, 0));
        assertEquals(ApDeadlineModePolicy.Reason.FEASIBLE_ENERGY, result.reason);
        assertEquals(LCM, result.target);
    }

    @Test
    void uncertainFeedbackDoesNotCertifyAnActiveDelay() {
        Packet packet = new Packet(1, 1500, 0, AccessCategory.VI);
        var ready = new ApModeSwitch.Readiness(LCM, LCM, 0, 0, 0, 0, false);
        var knownShape = forecast(HCM, packet, 180_000, 1, 0);
        var unknown = new ApModePredictor.ServiceForecast(HCM, ApModePredictor.Confidence.CHANNEL_UNKNOWN,
                -1, true, knownShape.expected, knownShape.conservative, knownShape.optimistic,
                knownShape.deadlineBatches, knownShape.saturatedMbps, 1);
        var result = choose(0, ready, false, 450_000, forecast(LCM, packet, 480_000, 1, 0), unknown);
        assertEquals(ApDeadlineModePolicy.Status.INFORMATION_INSUFFICIENT, result.candidates.get(1).status);
        assertEquals(HCM, result.target);
        assertEquals(0, result.maxDelayNs);
    }

    @Test
    void rejectsPacketFromTheFuture() {
        Packet future = new Packet(1, 1500, 10, AccessCategory.VI);
        var ready = new ApModeSwitch.Readiness(LCM, LCM, 0, 0, 0, 0, false);
        assertThrows(IllegalArgumentException.class, () -> choose(0, ready, false, 500_000,
                forecast(LCM, future, 250_000, 1, 0), forecast(HCM, future, 180_000, 1, 0)));
    }

    private static ApModePredictor.ServiceForecast forecast(ApCapabilityMode mode, Packet packet, double ns,
                                                             double success, double busy) {
        var work = new ApModePredictor.ServiceWork(12_000, 1, 0, 12_000 * success, ns, ns / 3, ns / 10, busy, 1-success, 1);
        return new ApModePredictor.ServiceForecast(mode, ApModePredictor.Confidence.FEEDBACK, busy, false, work, work, work,
                List.of(new ApModePredictor.DeadlineBatch(List.of(packet), ns, success)), 12_000 / ns * 1000, 1);
    }

    private static ApDeadlineModePolicy.Decision choose(long now, ApModeSwitch.Readiness ready, boolean busy, long deadline,
                                                       ApModePredictor.ServiceForecast low, ApModePredictor.ServiceForecast high) {
        var requirements = new EnumMap<AccessCategory, ApAnalysisSettings.Requirement>(AccessCategory.class);
        for (AccessCategory ac : AccessCategory.values()) {
            requirements.put(ac, new ApAnalysisSettings.Requirement(deadline, .99));
        }
        var energy = new ApEnergyModePolicy(new ApEnergyModel(ApEnergyModel.LCM_DEFAULT, ApEnergyModel.HCM_DEFAULT), SWITCH, 0);
        var filter = new ApDeadlineModePolicy(new ApAnalysisSettings(true, HORIZON, .25, requirements), SWITCH, 50_000, 2_000_000);
        return filter.choose(now, energy.choose(HORIZON, ready, busy, List.of(low, high)), ready, busy);
    }
}
