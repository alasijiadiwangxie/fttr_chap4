package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCcaSimulationTest {
    private static final String ENABLE = "wifi.channel.interference.enabled=true";
    private static final String TIMELINE = "wifi.channel.interference.timeline=";
    private static final String MODE = "sfu.1.wifi.ap.mode=";

    @Test
    void freezesAtSameTimeAsAifsAndRestartsOnlyOccupiedBandwidth(@TempDir Path directory) throws IOException {
        String source = TIMELINE + "1,3,43,90,-70,-120";
        String lcm = run(directory, MODE + "LCM", ENABLE, source);
        assertTrue(lcm.contains("event=WIFI_EXTERNAL_CCA t_ns=43000 sfu_id=1 observed_mask=8 access_mask=1 busy=false"));
        assertTrue(lcm.contains("ppdu_start_ns=43000"));
        String hcm = run(directory, MODE + "HCM", ENABLE, source);
        assertTrue(hcm.contains("event=WIFI_EXTERNAL_CCA t_ns=43000 sfu_id=1 observed_mask=8 access_mask=15 busy=true"));
        assertTrue(hcm.contains("ppdu_start_ns=133000"));
        assertFalse(hcm.contains("ppdu_start_ns=43000"));
        assertEquals(1, decodeRows(hcm).size());
    }

    @Test
    void separatesBelowThresholdReceiverInterferenceFromBusyDeferral(@TempDir Path directory) throws IOException {
        String source = TIMELINE + "1,3,0,80,-100,-75";
        String lcm = run(directory, MODE + "LCM", ENABLE, source);
        String hcm = run(directory, MODE + "HCM", ENABLE, source);
        assertTrue(decodeRows(lcm).get(0).contains("per=0.0"));
        assertTrue(decodeRows(hcm).get(0).contains("outcome=SINR_ERROR"));
        assertTrue(decodeRows(hcm).get(0).contains("ppdu_start_ns=43000"));
        assertFalse(hcm.contains("busy=true"));
        double observed = Double.parseDouble(field(decodeRows(hcm).get(0), "subchannel_sinr_db").split(",")[3]);
        assertEquals(30 - 10 * Math.log10(101), observed, 1e-12);
        assertTrue(hcm.contains("event=WIFI_RETRY_LIMIT_DROP packet_id=1"));
    }

    @Test
    void finishesStartedExchangeButPreventsReservedTxopContinuation(@TempDir Path directory) throws IOException {
        String trace = run(directory, MODE + "HCM", ENABLE, "packet.arrival.us=0,0",
                "wifi.mac.edca.BE.txop.limit.us=500", TIMELINE + "1,3,170,210,-70,-120");
        assertEquals(List.of("43000", "253000"), decodeRows(trace).stream()
                .map(row -> field(row, "ppdu_start_ns")).collect(Collectors.toList()));
        assertTrue(trace.contains("t_us=164.6 node=SFU event=WIFI_ACK_END packet_id=1"));
        assertTrue(trace.contains("t_us=170 node=SFU event=WIFI_TXOP_END packet_id=1"));
        assertFalse(trace.contains("t_us=180.6 node=SFU event=WIFI_TX_START"));
    }

    @Test
    void preservesConsumedBackoffSlotsAcrossAnExternalBusyGap(@TempDir Path directory) throws IOException {
        String cw = "wifi.mac.edca.BE.cwmin=15";
        String seed = "wifi.mac.edca.seed=101";
        String baseline = run(directory, MODE + "HCM", cw, seed);
        long nominalStartNs = Long.parseLong(field(decodeRows(baseline).get(0), "ppdu_start_ns"));
        assertTrue(nominalStartNs >= 52_000L);
        String interruptedUs = BigDecimal.valueOf(nominalStartNs - 1, 3).toPlainString();
        String interrupted = run(directory, MODE + "HCM", cw, seed, ENABLE,
                TIMELINE + "1,0," + interruptedUs + ",500,-70,-120");
        // 最后一个槽未到期就被冻结。忙结束后重新等待 43 μs AIFS 后只剩 1 个 9 μs 槽。
        assertEquals("552000", field(decodeRows(interrupted).get(0), "ppdu_start_ns"));
    }

    @Test
    void keepsExistingExchangeAliveAndDoesNotReleaseOverlappingExternalBusy(@TempDir Path directory)
            throws IOException {
        String trace = run(directory, MODE + "HCM", ENABLE, "packet.arrival.us=0,0",
                TIMELINE + "1,3,60,250,-70,-75");
        assertEquals(List.of("43000", "293000"), decodeRows(trace).stream()
                .map(row -> field(row, "ppdu_start_ns")).collect(Collectors.toList()));
        assertTrue(trace.contains("t_us=164.6 node=SFU event=WIFI_ACK_END packet_id=1"));
        assertTrue(decodeRows(trace).stream().allMatch(row -> row.contains("outcome=DECODED")));
    }

    @Test
    void timesActiveFeedbackAroundSameTimeCcaAndDoesNotReleaseDuringOverlappingBusy(@TempDir Path directory)
            throws IOException {
        String trace = run(directory, MODE + "HCM", ENABLE,
                TIMELINE + "1,3,43,90,-70,-120;1,3,95,110,-70,-120",
                "wifi.channel.observation.enabled=true", "wifi.channel.feedback.airtime.us=10",
                "wifi.channel.feedback=1,1,0,0,43,300;1,1,0,0,90,300",
                "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=500");
        assertEvent(trace, "event=WIFI_CHANNEL_FEEDBACK", "t_ns=43000", "phase=CCA_BUSY_DROP");
        assertEvent(trace, "event=WIFI_CHANNEL_FEEDBACK", "t_ns=90000", "phase=TX_START");
        assertEvent(trace, "event=WIFI_CHANNEL_FEEDBACK", "t_ns=100000", "phase=ACCEPTED");
        assertEvent(trace, "summary=WIFI_CHANNEL_OVERHEAD", "feedback_airtime_ns=10000");
        assertEvent(trace, "summary=WIFI_AP_ACTIVITY", "rx_ns=38000");
        assertEquals("153000", field(decodeRows(trace).get(0), "ppdu_start_ns"));
    }

    @Test
    void retainsC2b1TraceWhenNewInterferenceIsDisabled(@TempDir Path directory) throws IOException {
        String baseline = run(directory, MODE + "HCM", "wifi.phy.per.frequency.model=flat");
        assertEquals(baseline, run(directory, MODE + "HCM", "wifi.phy.per.frequency.model=flat",
                "wifi.channel.interference.enabled=false", TIMELINE + "1,0,0,100,-70,-60"));
    }

    private static List<String> decodeRows(String output) {
        return output.lines().filter(row -> row.startsWith("event=WIFI_PHY_MPDU_DECODE ")).collect(Collectors.toList());
    }

    private static void assertEvent(String trace, String... details) {
        assertTrue(trace.lines().anyMatch(line -> List.of(details).stream().allMatch(line::contains)),
                () -> "Missing event " + List.of(details));
    }

    private static String field(String row, String key) {
        for (String column : row.split(" ")) {
            if (column.startsWith(key + "=")) {
                return column.substring(key.length() + 1);
            }
        }
        throw new AssertionError("Missing field " + key + " in " + row);
    }

    private static String run(Path directory, String... overrides) throws IOException {
        List<String> properties = new ArrayList<>(List.of(
                "traffic.mode=manual", "traffic.direction=DL", "packet.arrival.us=0", "packet.bytes=1500",
                "fiber.delay.us=0", "wifi.model=he-su", "wifi.mac.access=edca-multi-ac",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0",
                "wifi.mac.ack.rate.mbps=24", "wifi.mac.retry.max=0", "wifi.phy.per.seed=101",
                "wifi.phy.per.enabled=true", "wifi.phy.per.frequency.model=subchannel-blocks",
                "wifi.phy.per.source=SYNTHETIC_TEST", "wifi.phy.per.curves=7,-12,1;7,10,0.5;7,30,0",
                "wifi.channel.truth=1,1,0,0,30,-1,false,-95;1,1,1,0,30,-1,false,-95;"
                        + "1,1,2,0,30,-1,false,-95;1,1,3,0,30,-1,false,-95"));
        properties.addAll(List.of(overrides));
        Path file = Files.write(directory.resolve("external-cca.properties"), properties, StandardCharsets.UTF_8);
        ByteArrayOutputStream trace = new ByteArrayOutputStream();
        try (PrintStream logger = new PrintStream(trace, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(file), new TraceLogger(logger));
        }
        return trace.toString(StandardCharsets.UTF_8);
    }
}
