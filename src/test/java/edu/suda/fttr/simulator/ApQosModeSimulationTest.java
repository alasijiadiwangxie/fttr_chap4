package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApQosModeSimulationTest {
    private static final String DEFER = "WIFI_AP_HCM_DEFER";
    private static final String CHOOSE = "WIFI_AP_QOS_MODE_DECISION";
    private static final String B4 = "WIFI_AP_SWITCH_";
    private static final String D4 = "wifi.ap.control.policy=ecq-sdps-d4";
    private static final String D4_DECISION = "WIFI_AP_D4_DECISION";
    private static final String D5 = "wifi.ap.control.policy=ecq-sdps-complete";
    private static final String D5_DECISION = "WIFI_AP_D5_DECISION";
    private static final List<String> BASE = List.of("wifi.ap.control.policy=ecq-sdps", "fiber.delay.us=0", "wifi.model=he-su",
            "packet.bytes=1500", "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac",
            "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VI.cwmin=0",
            "wifi.mac.edca.BE.cwmin=0", "wifi.mac.edca.BK.cwmin=0", "wifi.mac.edca.seed=101",
            "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=8", "wifi.ap.activity.enabled=true",
            "wifi.ap.activity.window.us=1000", "wifi.ap.energy.enabled=true", "wifi.ap.qos.enabled=true",
            "wifi.ap.switch.enabled=true", "wifi.ap.switch.lcm-to-hcm.delay.us=40.125", "wifi.ap.switch.lcm-to-hcm.power.w=11.50",
            "wifi.ap.switch.hcm-to-lcm.delay.us=25.25", "wifi.ap.switch.hcm-to-lcm.power.w=8.75");

    @ParameterizedTest
    @CsvSource({"VO,254.225,0", "VI,265.475,0", "BE,295,29525", "BK,350,17025"})
    void urgentDecisionsAndDelayLimitsMatchTheHeSuHandCalculation(String ac, String deadline, long delay,
                                                                  @TempDir Path dir) throws IOException {
        var trace = run(dir, "packet.arrival.us=0", "packet.1.type=" + ac, "wifi.ap.qos." + ac + ".deadline.us=" + deadline);
        var decision = events(trace, CHOOSE).stream().filter(row -> "HCM".equals(row.get("candidate_mode"))).findFirst().orElseThrow();
        assertEquals("URGENT_QUEUE", decision.get("trigger"));
        assertEquals("FEASIBLE", decision.get("qos_status"));
        assertEquals(delay, Long.parseLong(decision.get("max_delay_ns")));
        assertEquals(delay, stamp(only(trace, B4 + "REQUEST")));
        assertEquals(delay + 40_125, stamp(only(trace, B4 + "END")));
        assertTrue(stamp(only(trace, "DELIVERED")) <= Double.parseDouble(deadline) * 1000);
        assertEquals(delay == 0 ? 0 : 2, events(trace, DEFER).size());
    }

    @Test
    void spendsAlreadyElapsedFiberTimeBeforeDeciding(@TempDir Path dir) throws IOException {
        var trace = run(dir, "fiber.delay.us=100", "packet.arrival.us=0", "packet.1.type=VO",
                "wifi.ap.qos.VO.deadline.us=354.225");
        assertEquals(100_000, stamp(only(trace, B4 + "REQUEST")));
        var prediction = events(trace, "WIFI_AP_QOS_DEADLINE_PREDICTION").stream()
                .filter(row -> "HCM".equals(row.get("candidate_mode"))).findFirst().orElseThrow();
        assertEquals("254225", prediction.get("earliest_remaining_ns"));
        assertEquals("0.0", prediction.get("qos_min_slack_ns"));
    }

    @Test
    void ecqAwareUsesTheSameFilterAndRequestsImmediately(@TempDir Path dir) throws IOException {
        var trace = run(dir, "wifi.ap.control.policy=ecq-aware", "packet.arrival.us=0", "wifi.ap.qos.BE.deadline.us=295");
        assertEquals(0, stamp(only(trace, B4 + "REQUEST")));
        assertTrue(events(trace, DEFER).isEmpty());
    }

    @Test
    void mergesLaterTrafficWithoutPostponingOrDuplicatingTheRequest(@TempDir Path dir) throws IOException {
        var trace = run(dir, "packet.arrival.us=0,10", "packet.2.type=BK", "wifi.ap.qos.BE.deadline.us=295",
                "wifi.ap.qos.BK.deadline.us=1000");
        var plans = events(trace, DEFER);
        assertEquals(List.of("OPEN", "MERGE", "RELEASE"), fields(plans, "phase"));
        assertEquals(List.of("29525", "29525", "29525"), fields(plans, "due_ns"));
        assertEquals(List.of("1", "1", "1"), fields(plans, "defer_id"));
        assertEquals(1, events(trace, B4 + "REQUEST").size());
        assertEquals(2, events(trace, "DELIVERED").size());
    }

    @Test
    void urgentVoiceReleasesThePendingRequestEarly(@TempDir Path dir) throws IOException {
        var trace = run(dir, "packet.arrival.us=0,10", "packet.2.type=VO", "wifi.ap.qos.BE.deadline.us=295",
                "wifi.ap.qos.VO.deadline.us=250");
        var plans = events(trace, DEFER);
        assertEquals(List.of("OPEN", "RELEASE"), fields(plans, "phase"));
        assertEquals("10000", plans.get(1).get("active_delay_ns"));
        assertEquals("URGENT_QUEUE", plans.get(1).get("trigger"));
        assertEquals(10_000, stamp(only(trace, B4 + "REQUEST")));
        assertEquals(2, events(trace, "DELIVERED").size());
    }

    @Test
    void tighterStillPositiveBudgetMovesTheSameTimerForward(@TempDir Path dir) throws IOException {
        var trace = run(dir, "packet.arrival.us=0,10", "packet.2.type=BK", "wifi.ap.qos.BE.deadline.us=295",
                "wifi.ap.qos.BK.deadline.us=520");
        var plans = events(trace, DEFER);
        assertEquals(List.of("OPEN", "ADVANCE", "RELEASE"), fields(plans, "phase"));
        assertEquals(List.of("29525", "21675", "21675"), fields(plans, "due_ns"));
        assertEquals(List.of("1", "1", "1"), fields(plans, "defer_id"));
        assertEquals(21_675, stamp(only(trace, B4 + "REQUEST")));
        assertEquals(2, events(trace, "DELIVERED").size());
    }

    @Test
    void letsLcmTransmitWhileDeferredAndCancelsWhenTheRequestIsObsolete(@TempDir Path dir) throws IOException {
        var trace = run(dir, "packet.arrival.us=0", "wifi.ap.qos.BE.deadline.us=320");
        var plans = events(trace, DEFER);
        assertEquals(List.of("OPEN", "CANCEL"), fields(plans, "phase"));
        assertEquals(43_000, stamp(only(trace, "WIFI_TX_START")));
        assertEquals(54_525, stamp(plans.get(1)));
        assertTrue(events(trace, B4 + "REQUEST").isEmpty());
        assertEquals(235_800, stamp(only(trace, "DELIVERED")));
    }

    @Test
    void separatesKnownPassiveAckWaitFromActiveDelay(@TempDir Path dir) throws IOException {
        var trace = run(dir, "packet.arrival.us=0,50", "packet.2.type=VO", "wifi.ap.qos.VO.deadline.us=480");
        assertTrue(events(trace, DEFER).isEmpty());
        assertEquals(50_000, stamp(only(trace, B4 + "REQUEST")));
        assertEquals(279_800, stamp(only(trace, B4 + "START")));
        assertEquals("229800", only(trace, B4 + "START").get("wait_ns"));
        assertEquals(319_925, stamp(only(trace, B4 + "END")));
    }

    @Test
    void accountsForExternalCcaWhenDecodingWouldSucceed(@TempDir Path dir) throws IOException {
        var trace = run(dir, "packet.arrival.us=100", "packet.1.type=VO", "wifi.ap.qos.VO.deadline.us=254.225",
                "wifi.phy.per.enabled=true", "wifi.phy.per.source=NO_ERRORS_TEST", "wifi.phy.per.curves=7,-20,0;7,30,0",
                "wifi.phy.per.frequency.model=subchannel-blocks", "wifi.channel.interference.enabled=true",
                "wifi.channel.truth=1,1,0,0,30,-1,false,-95;1,1,1,0,30,-1,false,-95;1,1,2,0,30,-1,false,-95;1,1,3,0,30,-1,false,-95",
                "wifi.channel.interference.timeline=1,0,0,200,-70,-95");
        var decision = events(trace, CHOOSE).stream().filter(row -> "HCM".equals(row.get("candidate_mode")))
                .findFirst().orElseThrow();
        assertEquals(100_000, stamp(decision));
        assertTrue(Double.parseDouble(decision.get("qos_cca_wait_ns")) > 0);
        assertEquals("0", decision.get("max_delay_ns"));
        assertTrue(stamp(only(trace, "WIFI_TX_START")) >= 200_000);
        assertEquals(1, events(trace, "DELIVERED").size());
    }

    @Test
    void sameTimestampIngressAndPeriodicRecheckIssueOnlyOneRequest(@TempDir Path dir) throws IOException {
        var trace = run(dir, "wifi.ap.control.period.us=100", "wifi.ap.activity.window.us=200",
                "packet.arrival.us=100", "packet.1.type=VO", "wifi.ap.qos.VO.deadline.us=254.225");
        assertEquals(2, events(trace, CHOOSE).size());
        assertEquals(List.of("PERIODIC", "PERIODIC"), fields(events(trace, CHOOSE), "trigger"));
        assertEquals(100_000, stamp(only(trace, B4 + "REQUEST")));
    }

    @Test
    void firstDecisionIsIndependentOfAnUnseenLaterBurst(@TempDir Path dir) throws IOException {
        var first = run(dir, "packet.arrival.us=0", "packet.1.type=VO", "wifi.ap.qos.VO.deadline.us=254.225");
        var extra = run(dir, "packet.arrival.us=0,300,350,350", "packet.1.type=VO", "wifi.ap.qos.VO.deadline.us=254.225");
        assertEquals(events(first, CHOOSE).stream().filter(row -> stamp(row) == 0).collect(Collectors.toList()),
                events(extra, CHOOSE).stream().filter(row -> stamp(row) == 0).collect(Collectors.toList()));
    }

    @Test
    void d4StartsEconomicResidenceAtActualB4Completion(@TempDir Path dir) throws IOException {
        var trace = run(dir, D4, "traffic.mode=poisson", "traffic.duration.us=6000", "traffic.sfu.1.rate.pps=0",
                "sfu.1.wifi.ap.mode=HCM", "wifi.ap.activity.window.us=6000");
        var result = only(trace, D4_DECISION);
        assertEquals("767573", result.get("economic_residency_ns"));
        assertEquals("ECONOMICAL_SWITCH", result.get("reason"));
        var start = events(trace, "WIFI_AP_D4_RESIDENCY").get(1);
        assertEquals(stamp(only(trace, B4 + "END")), stamp(start));
        assertEquals("5792823", start.get("residency_until_ns"));
        assertTrue(events(trace, "DELIVERED").isEmpty());
    }

    @Test
    void d4ImmediatelyRescuesVoiceButMaintainsResidenceForLaterUrgentArrivals(@TempDir Path dir) throws IOException {
        var trace = run(dir, D4, "packet.arrival.us=0,400,800", "packet.1.type=VO", "packet.2.type=VO", "packet.3.type=VO",
                "wifi.ap.qos.VO.deadline.us=254.225", "wifi.ap.control.residency.minimum.us=5000");
        assertEquals("URGENT_EXCEPTION", events(trace, D4_DECISION).get(0).get("reason"));
        assertEquals("1", events(trace, D4_DECISION).get(0).get("urgent_packet_id"));
        assertEquals(0, stamp(only(trace, B4 + "REQUEST")));
        assertEquals(40_125, stamp(only(trace, B4 + "END")));
        assertEquals(3, events(trace, "DELIVERED").size());
        for (var row : events(trace, "DELIVERED")) {
            long generation = (Long.parseLong(row.get("packet_id")) - 1) * 400_000;
            assertTrue(stamp(row) - generation <= 254_225);
        }
    }

    @Test
    void d4StillDeliversAlreadyOverdueIngressWithoutTriggeringEmergency(@TempDir Path dir) throws IOException {
        var trace = run(dir, D4, "fiber.delay.us=300", "packet.arrival.us=0", "packet.1.type=VO",
                "wifi.ap.qos.VO.deadline.us=254.225");
        assertEquals(300_000, stamp(only(trace, "WIFI_AP_D4_EXPIRED_INGRESS")));
        assertTrue(events(trace, D4_DECISION).isEmpty());
        assertTrue(events(trace, B4 + "REQUEST").isEmpty());
        assertTrue(stamp(only(trace, "DELIVERED")) > 300_000);
    }

    @Test
    void d4DoesNotWaitJustBecauseD3AllowsPositiveSlack(@TempDir Path dir) throws IOException {
        var trace = run(dir, D4, "packet.arrival.us=0", "wifi.ap.qos.BE.deadline.us=295",
                "wifi.ap.control.residency.minimum.us=0");
        var choice = only(trace, D4_DECISION);
        assertEquals("29525", choice.get("allowed_delay_ns"));
        assertEquals("0", choice.get("chosen_delay_ns"));
        assertEquals("0", choice.get("deferred_batches"));
        assertEquals(0, stamp(only(trace, B4 + "REQUEST")));
        assertTrue(events(trace, DEFER).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wifi.ap.control.residency.minimum.us=-1", "wifi.ap.control.residency.reverse.margin.uj=-0.01"})
    void d4RejectsInvalidResidencyConfiguration(String setting, @TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> run(dir, D4, "packet.arrival.us=0", setting));
    }

    @Test
    void completeControllerDoesNotUseUnseenArrivals(@TempDir Path dir) throws IOException {
        var first = run(dir, D5, "packet.arrival.us=0", "packet.1.type=VO", "wifi.ap.qos.VO.deadline.us=254.225");
        var withFuture = run(dir, D5, "packet.arrival.us=0,300,310,320", "packet.1.type=VO", "wifi.ap.qos.VO.deadline.us=254.225");
        for (String type : List.of(D4_DECISION, D5_DECISION, B4 + "REQUEST")) {
            assertEquals(events(first, type).stream().filter(row -> stamp(row) == 0).collect(Collectors.toList()),
                    events(withFuture, type).stream().filter(row -> stamp(row) == 0).collect(Collectors.toList()));
        }
        assertEquals(0, stamp(only(first, B4 + "REQUEST")));
        assertEquals(1, events(first, "DELIVERED").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"channel", "qos", "deferral", "residency"})
    void completeControllerLoadsIndependentAblationSwitches(String feature, @TempDir Path dir) throws IOException {
        var trace = run(dir, D5, "wifi.ap.control.period.us=125", "packet.arrival.us=0", "packet.1.type=VO",
                "wifi.ap.qos.VO.deadline.us=254.225", "wifi.ap.control.complete." + feature + ".enabled=false");
        var record = events(trace, D5_DECISION).get(0);
        for (var key : List.of("channel", "qos", "deferral", "residency")) {
            assertEquals(Boolean.toString(!key.equals(feature)), record.get(key));
        }
        if (feature.equals("qos")) {
            assertTrue(events(trace, D4_DECISION).stream().noneMatch(row -> "URGENT_QUEUE".equals(row.get("trigger"))));
        }
        if (feature.equals("residency")) {
            assertEquals("0", events(trace, "WIFI_AP_D4_RESIDENCY").get(0).get("residency_until_ns"));
        }
        assertEquals(1, events(trace, "DELIVERED").size());
    }

    @Test
    void auxiliaryObservationCanKeepScanTimingWithZeroExtraPower(@TempDir Path dir) throws IOException {
        var trace = run(dir, D5, "packet.arrival.us=0", "wifi.channel.observation.enabled=true",
                "wifi.channel.scan.power.w=0", "wifi.channel.scan.schedule=1,1,100,400,6000");
        var scan = events(trace, "WIFI_CHANNEL_SCAN");
        assertEquals(List.of("START", "END"), fields(scan, "phase"));
        assertEquals(500_000L, stamp(scan.get(1)));
        assertEquals(0, new BigDecimal(scan.get(1).get("extra_energy_uj")).signum());
        assertEquals(1, events(trace, "DELIVERED").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wifi.ap.control.defer.max.us=-1", "wifi.ap.control.qos.margin.us=-1",
            "wifi.ap.control.service.model=unknown"})
    void rejectsInvalidOptions(String setting, @TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> run(dir, "packet.arrival.us=0", setting));
    }

    private static List<Map<String, String>> run(Path dir, String... extra) throws IOException {
        List<String> lines = new ArrayList<>(BASE);
        if (List.of(extra).contains("traffic.mode=poisson")) {
            lines.remove("packet.bytes=1500");
        }
        lines.addAll(List.of(extra));
        var file = Files.write(dir.resolve("d3.properties"), lines, StandardCharsets.UTF_8);
        var buffer = new ByteArrayOutputStream();
        try (var output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(file), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(line -> Arrays.stream(line.split(" "))
                .filter(item -> item.contains("=")).map(item -> item.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]))).collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static Map<String, String> only(List<Map<String, String>> trace, String event) {
        var matching = events(trace, event);
        assertEquals(1, matching.size(), event);
        return matching.get(0);
    }

    private static List<String> fields(List<Map<String, String>> rows, String key) {
        return rows.stream().map(row -> row.get(key)).collect(Collectors.toList());
    }

    private static long stamp(Map<String, String> row) {
        return row.containsKey("t_ns") ? Long.parseLong(row.get("t_ns")) : Math.round(Double.parseDouble(row.get("t_us")) * 1000);
    }
}
