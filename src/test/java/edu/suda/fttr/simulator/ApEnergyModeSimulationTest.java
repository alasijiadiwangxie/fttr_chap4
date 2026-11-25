package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApEnergyModeSimulationTest {
    private static final String DECISION = "WIFI_AP_ENERGY_MODE_DECISION";
    private static final String SWITCH = "WIFI_AP_SWITCH_";
    private static final String CTRL = "wifi.ap.control.policy=";
    private static final String END_US = "wifi.ap.activity.window.us=";
    private static final List<String> BASE = List.of("fiber.delay.us=0", "wifi.model=he-su",
            "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac", "wifi.mac.edca.BE.aifsn=3",
            "wifi.mac.edca.BE.cwmin=0", "wifi.mac.edca.seed=101", "wifi.mac.ack.timeout.us=60",
            "wifi.mac.ampdu.enabled=true", "wifi.mac.ampdu.max-mpdus=8", "wifi.ap.activity.enabled=true",
            "wifi.ap.energy.enabled=true", "wifi.ap.switch.enabled=true", "wifi.ap.qos.enabled=true",
            "wifi.ap.switch.lcm-to-hcm.delay.us=40.125", "wifi.ap.switch.lcm-to-hcm.power.w=11.50",
            "wifi.ap.switch.hcm-to-lcm.delay.us=25.250", "wifi.ap.switch.hcm-to-lcm.power.w=8.75");
    private static final List<String> PER = List.of("wifi.phy.per.enabled=true", "wifi.phy.per.seed=101",
            "wifi.phy.per.frequency.model=subchannel-blocks", "wifi.phy.per.source=SYNTHETIC_D2_TEST",
            "wifi.phy.per.curves=7,-12,1;7,30,0", "wifi.channel.observation.enabled=true");

    @Test
    void defaultScheduleAndAnalyticIdleEnergyAgree(@TempDir Path dir) throws IOException {
        var trace = run(dir, List.of(CTRL + "e-aware", "traffic.mode=poisson", "traffic.duration.us=6000",
                "traffic.sfu.1.rate.pps=0", "sfu.1.wifi.ap.mode=HCM", END_US + "6000"));
        Map<String, String> low = candidate(trace, 5_000_000, "LCM");
        Map<String, String> high = candidate(trace, 5_000_000, "HCM");
        assertEquals(2, events(trace, DECISION).size());
        assertEquals("5000000", low.get("horizon_ns"));
        assertEquals("LCM", low.get("target_mode"));
        assertEquals(25.25 * 8.75 + 4974.75 * 7.38, number(low, "total_cost_uj"), 1e-8);
        assertEquals(5000 * 7.62, number(high, "total_cost_uj"), 1e-8);
        assertEquals("e-aware", only(trace, SWITCH + "END").get("policy"));
        assertEquals(5000000, stamp(only(trace, SWITCH + "START")));
        assertEquals(5025250, stamp(only(trace, SWITCH + "END")));
    }

    @Test
    void pricesTheActiveExchangeOnceAndStartsAfterAck(@TempDir Path dir) throws IOException {
        var trace = run(dir, List.of(CTRL + "e-aware", "sfu.1.wifi.ap.mode=HCM", END_US + "6000",
                "packet.bytes=1500", "packet.arrival.us=4930"));
        Map<String, String> low = candidate(trace, 5_000_000, "LCM");
        Map<String, String> high = candidate(trace, 5_000_000, "HCM");
        assertEquals("94600", low.get("local_wait_ns"));
        assertEquals("50600", low.get("prefix_tx_ns"));
        assertEquals("28000", low.get("prefix_rx_ns"));
        assertEquals("1", low.get("dl_inflight"));
        assertEquals(12_000, number(low, "demand_bits"), 1e-8); // 未来估计一包，已在途的报文不再进服务预算。
        assertEquals(low.get("demand_bits"), high.get("demand_bits"));
        assertEquals(5_000_000, number(low, "tx_ns") + number(low, "rx_ns") + number(low, "idle_ns")
                + number(low, "new_switch_ns"), 1e-7);
        assertEquals(5_094_600, stamp(only(trace, "WIFI_ACK_END")));
        assertEquals(5_094_600, stamp(only(trace, SWITCH + "START")));
        assertEquals(1, events(trace, "DELIVERED").size());
    }

    @Test
    void countsKnownAckTimeoutAsIdleWaitAndRetriesAfterTheSwitch(@TempDir Path dir) throws IOException {
        var settings = new ArrayList<>(PER);
        settings.addAll(List.of(CTRL + "e-aware", "sfu.1.wifi.ap.mode=HCM", END_US + "6000",
                "packet.bytes=1500", "packet.arrival.us=4860",
                "wifi.channel.truth=" + truth(0, -20, -20) + ";" + truth(5000, 35, 35)));
        var trace = run(dir, settings);
        var low = candidate(trace, 5_000_000, "LCM");
        assertEquals("40600", low.get("local_wait_ns"));
        assertEquals("0", low.get("prefix_tx_ns"));
        assertEquals("0", low.get("prefix_rx_ns"));
        assertEquals(5_040_600, stamp(only(trace, "WIFI_ACK_TIMEOUT")));
        assertEquals(5_040_600, stamp(only(trace, SWITCH + "START")));
        assertEquals(1, events(trace, "WIFI_MAC_RETRY").size());
        assertEquals(1, events(trace, "DELIVERED").size());
    }

    @Test
    void waitsForAnAlreadyStartedChannelReportAndChargesOnlyRemainingRx(@TempDir Path dir) throws IOException {
        var trace = run(dir, List.of(CTRL + "e-aware", "sfu.1.wifi.ap.mode=HCM", END_US + "6000",
                "traffic.mode=poisson", "traffic.duration.us=6000", "traffic.sfu.1.rate.pps=0",
                "wifi.channel.observation.enabled=true", "wifi.channel.truth=" + truth(0, 30, 30),
                "wifi.channel.feedback=1,1,0,4500,0,2000", "wifi.channel.feedback.airtime.us=1000"));
        assertEquals("500000", candidate(trace, 5_000_000, "LCM").get("local_wait_ns"));
        assertEquals("500000", candidate(trace, 5_000_000, "LCM").get("prefix_rx_ns"));
        assertEquals(5_500_000, stamp(only(trace, SWITCH + "START")));
    }

    @Test
    void estimatesExternalWaitFromPastBusyFractionAndLetsB4WaitForTheRealEnd(@TempDir Path dir) throws IOException {
        var settings = new ArrayList<>(PER);
        settings.addAll(List.of(CTRL + "ec-aware", "sfu.1.wifi.ap.mode=HCM", END_US + "6000",
                "traffic.mode=poisson", "traffic.duration.us=6000", "traffic.sfu.1.rate.pps=0",
                "wifi.channel.truth=" + truth(0, 30, 30), "wifi.channel.interference.enabled=true",
                "wifi.channel.interference.timeline=1,3,4000,5500,-73,-82"));
        var trace = run(dir, settings);
        var low = candidate(trace, 5_000_000, "LCM");
        var high = candidate(trace, 5_000_000, "HCM");
        assertEquals(.2, number(high, "observed_busy_fraction"), 1e-10);
        assertEquals(1_000_000, number(low, "cca_wait_ns"), 1e-8);
        assertEquals(0, number(high, "cca_wait_ns"));
        assertEquals(5_500_000, stamp(only(trace, SWITCH + "START")));
    }

    @Test
    void sharesTheRemainingActiveSwitchAndKeepsC1ClockIndependent(@TempDir Path dir) throws IOException {
        var trace = run(dir, List.of(CTRL + "e-aware", "sfu.1.wifi.ap.mode=HCM", END_US + "4600",
                "wifi.ap.control.period.us=1000", "wifi.ap.observation.period.us=1300",
                "wifi.ap.switch.hcm-to-lcm.delay.us=2000", "wifi.ap.switch.hcm-to-lcm.power.w=7",
                "traffic.mode=poisson", "traffic.duration.us=4600", "traffic.sfu.1.rate.pps=0"));
        var low = candidate(trace, 2_000_000, "LCM");
        var high = candidate(trace, 2_000_000, "HCM");
        assertEquals("1300000", low.get("window_start_ns"));
        assertEquals("LCM", low.get("projected_mode"));
        assertEquals("1000000", low.get("active_switch_ns"));
        assertEquals(7000, number(low, "active_switch_uj"));
        assertEquals(low.get("active_switch_uj"), high.get("active_switch_uj"));
        assertEquals("SWITCHING", low.get("action"));
        assertEquals(1_000_000, stamp(only(trace, SWITCH + "REQUEST")));
        assertEquals(3_000_000, stamp(only(trace, SWITCH + "END")));
    }

    @Test
    void ablationUsesTheSameDemandButRejectsAnImpairedSecondaryBandOnlyWhenObserved(@TempDir Path dir) throws IOException {
        var settings = new ArrayList<>(PER);
        settings.addAll(List.of("packet.bytes=1500", "packet.arrival.us=" + stream(6000), END_US + "6000",
                "wifi.channel.truth=" + truth(0, 30, -20), "wifi.channel.feedback=" + reports(100, 25, 10_000)));
        var e = run(dir, with(settings, CTRL + "e-aware"));
        var ec = run(dir, with(settings, CTRL + "ec-aware"));
        var blind = candidate(e, 5_000_000, "HCM");
        var aware = candidate(ec, 5_000_000, "HCM");
        assertEquals(blind.get("demand_bits"), aware.get("demand_bits"));
        assertEquals("HCM", blind.get("target_mode"));
        assertEquals("LCM", aware.get("target_mode"));
        assertEquals("FEEDBACK", aware.get("channel_confidence"));
        assertEquals(1, number(aware, "mean_failure"));
        assertEquals(0, number(candidate(ec, 5_000_000, "LCM"), "mean_failure"));
        assertEquals(0, number(aware, "success_bits"));
        assertEquals(Double.POSITIVE_INFINITY, number(aware, "tail_uj"));
    }

    @Test
    void delaysVisibilityUntilFeedbackArrivesAndDropsExpiredInformation(@TempDir Path dir) throws IOException {
        var settings = new ArrayList<>(PER);
        settings.addAll(List.of(CTRL + "ec-aware", "packet.bytes=1500", END_US + "17000",
                "wifi.channel.truth=" + truth(0, 30, -20), "wifi.channel.feedback=" + reports(1000, 4500, 10_000)));
        var trace = run(dir, with(settings, "packet.arrival.us=" + stream(17_000)));
        var counterfactual = run(dir, with(settings, "packet.arrival.us=" + stream(5500),
                "wifi.channel.truth=" + truth(0, 30, -20) + ";" + truth(6800, -20, 30)));
        for (String mode : List.of("LCM", "HCM")) {
            assertEquals(candidate(trace, 5_000_000, mode), candidate(counterfactual, 5_000_000, mode));
            assertEquals("true", candidate(trace, 5_000_000, mode).get("channel_incomplete"));
            assertEquals("FEEDBACK", candidate(trace, 10_000_000, mode).get("channel_confidence"));
            assertEquals("true", candidate(trace, 15_000_000, mode).get("channel_incomplete"));
        }
    }

    @Test
    void twoSfusUseTheirOwnPolicyAndStartupMode(@TempDir Path dir) throws IOException {
        var trace = run(dir, List.of(CTRL + "e-aware", "sfu.count=2", "sta.count=2", "sta.2.sfu.id=2",
                "sfu.2.wifi.ap.control.policy=ec-aware", "sfu.1.wifi.ap.mode=HCM", END_US + "6000",
                "traffic.mode=poisson", "traffic.duration.us=6000", "traffic.sfu.1.rate.pps=0",
                "traffic.sfu.2.rate.pps=0"));
        assertEquals("1", only(trace, SWITCH + "END").get("sfu_id"));
        assertEquals(List.of("e-aware", "e-aware", "ec-aware", "ec-aware"), events(trace, DECISION).stream()
                .map(row -> row.get("policy")).collect(Collectors.toList()));
    }

    @Test
    void decisionPreviewIsImmutableAndDoesNotConsumeC1State() {
        ApTrafficObserver observer = new ApTrafficObserver(5_000_000, .25);
        observer.enqueue(new Packet(1, 500, 0, AccessCategory.BE, 1), 0);
        var preview = observer.preview(1_000_000, 0, 0);
        observer.enqueue(new Packet(2, 1500, 1200, AccessCategory.BE, 1), 1_200_000);
        var sample = observer.sample(5_000_000, 0, 0);
        assertEquals(500, preview.stream().mapToLong(ApTrafficObserver.Observation::arrivalBytes).sum());
        assertEquals(2000, sample.stream().mapToLong(ApTrafficObserver.Observation::arrivalBytes).sum());
        assertEquals(2000, observer.downlinkArrivalBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wifi.ap.control.energy.margin.uj=-0.1", "wifi.ap.control.energy.margin.uj=NaN",
            "wifi.ap.prediction.window.us=0", "wifi.ap.switch.enabled=false"})
    void rejectsInvalidConfiguration(String setting, @TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> run(dir, List.of(CTRL + "ec-aware", "packet.bytes=1500",
                "packet.arrival.us=0", setting)));
    }

    private static String reports(int measured, int delay, int lifetime) {
        return IntStream.range(0, 4).mapToObj(sub -> "1,1," + sub + "," + measured + "," + delay + "," + lifetime)
                .collect(Collectors.joining(";"));
    }

    private static String truth(int time, int primary, int secondary) {
        return IntStream.range(0, 4).mapToObj(sub -> "1,1," + sub + "," + time + ","
                + (sub == 3 ? secondary : primary) + ",-1,false,-95").collect(Collectors.joining(";"));
    }

    private static String stream(int endUs) {
        return IntStream.iterate(0, t -> t < endUs, t -> t + 240).mapToObj(Integer::toString)
                .collect(Collectors.joining(","));
    }

    private static List<String> with(List<String> settings, String... additions) {
        var combined = new ArrayList<>(settings);
        combined.addAll(List.of(additions));
        return combined;
    }

    private static List<Map<String, String>> run(Path dir, List<String> settings) throws IOException {
        var file = Files.write(dir.resolve("d2.properties"), with(BASE, settings.toArray(new String[0])), StandardCharsets.UTF_8);
        var buffer = new ByteArrayOutputStream();
        try (var trace = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(file), new TraceLogger(trace));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(line -> Arrays.stream(line.split(" "))
                .filter(token -> token.contains("=")).map(token -> token.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]))).collect(Collectors.toList());
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static Map<String, String> only(List<Map<String, String>> trace, String event) {
        var selected = events(trace, event);
        assertEquals(1, selected.size(), event);
        return selected.get(0);
    }

    private static Map<String, String> candidate(List<Map<String, String>> trace, long time, String mode) {
        return events(trace, DECISION).stream().filter(row -> stamp(row) == time && mode.equals(row.get("candidate_mode")))
                .findFirst().orElseThrow();
    }

    private static double number(Map<String, String> row, String key) {
        return Double.parseDouble(row.get(key));
    }

    private static long stamp(Map<String, String> row) {
        return row.containsKey("t_ns") ? Long.parseLong(row.get("t_ns")) : Math.round(number(row, "t_us") * 1000);
    }
}
