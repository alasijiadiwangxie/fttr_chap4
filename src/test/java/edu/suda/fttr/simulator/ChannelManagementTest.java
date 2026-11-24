package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelManagementTest {
    private static final List<String> BASE = List.of(
            "traffic.mode=manual", "packet.bytes=1500", "packet.arrival.us=200", "fiber.delay.us=0",
            "wifi.model=he-su", "wifi.mac.access=edca-multi-ac", "wifi.mac.exchange=data-sifs-ack",
            "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0",
            "sfu.1.wifi.ap.mode=LCM", "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=1000",
            "wifi.ap.energy.enabled=true", "wifi.channel.observation.enabled=true");

    @Test
    void activeFeedbackHasAirtimeAndCannotBeReadEarlyOrAfterExpiry(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory,
                "wifi.channel.truth=1,1,0,0,27,8,false",
                "wifi.channel.feedback=1,1,0,0,5,20;1,1,0,30,5,50,drop",
                "wifi.channel.feedback.airtime.us=10",
                "wifi.channel.observe.us=14.999,15,20,45",
                "wifi.channel.passive.enabled=true");

        assertTrace(trace, "event=WIFI_CHANNEL_OBSERVATION", "t_ns=14999", "subchannel=0", "status=UNKNOWN");
        assertTrace(trace, "event=WIFI_CHANNEL_OBSERVATION", "t_ns=15000", "subchannel=0", "status=VISIBLE");
        assertTrace(trace, "event=WIFI_CHANNEL_OBSERVATION", "t_ns=20000", "subchannel=0", "status=EXPIRED");
        assertTrace(trace, "event=WIFI_CHANNEL_FEEDBACK", "t_ns=45000", "phase=LOST");
        assertTrace(trace, "event=WIFI_CHANNEL_OBSERVATION", "t_ns=45000", "subchannel=0", "status=EXPIRED");
        assertTrace(trace, "event=WIFI_CHANNEL_PASSIVE", "t_ns=14999", "cca=BUSY");
        assertTrace(trace, "summary=WIFI_CHANNEL_OVERHEAD", "feedback_airtime_ns=20000", "scan_energy_uj=0");
        assertTrace(trace, "summary=WIFI_AP_ACTIVITY", "rx_ns=48000");
        assertEquals(2L, trace.stream().filter(line -> line.contains("reason=FEEDBACK_RX_START")).count());
    }

    @Test
    void auxiliaryScanTracksEnvironmentAndEnergyWithoutChangingDataOrCreatingCsi(@TempDir Path directory)
            throws IOException {
        List<String> withScan = run(directory,
                "packet.arrival.us=0", "wifi.channel.scan.schedule=1,1,60,30,100",
                "wifi.channel.scan.power.w=0.5", "wifi.channel.observe.us=89.999,90");
        List<String> baseline = run(directory, "packet.arrival.us=0", "wifi.channel.observe.us=89.999,90");
        assertTrace(withScan, "event=WIFI_CHANNEL_SCAN", "t_ns=60000", "phase=START");
        assertTrace(withScan, "event=WIFI_CHANNEL_SCAN", "t_ns=90000", "phase=END",
                "busy_ns=0", "extra_energy_uj=15.0000");
        assertTrace(withScan, "event=WIFI_CHANNEL_OBSERVATION", "t_ns=90000", "subchannel=1",
                "status=MODE_LIMIT", "sinr_db=-");
        assertEquals(baseline, withScan.stream().filter(line -> !line.contains("WIFI_CHANNEL_SCAN")
                && !line.contains("WIFI_CHANNEL_OVERHEAD")).collect(java.util.stream.Collectors.toList()));
    }

    private static List<String> run(Path directory, String... settings) throws IOException {
        List<String> config = new ArrayList<>(BASE);
        config.addAll(List.of(settings));
        Path file = Files.write(directory.resolve("observation.properties"), config, StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(file), new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(java.util.stream.Collectors.toList());
    }

    private static void assertTrace(List<String> trace, String... fields) {
        assertTrue(trace.stream().anyMatch(line -> List.of(fields).stream().allMatch(line::contains)),
                () -> "Missing trace fields " + List.of(fields));
    }
}
