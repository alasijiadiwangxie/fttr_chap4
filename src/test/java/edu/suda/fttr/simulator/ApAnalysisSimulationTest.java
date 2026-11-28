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

final class ApAnalysisSimulationTest {
    private static final List<String> BASE = List.of(
            "traffic.mode=manual", "traffic.direction=DL", "packet.arrival.us=0,60,180,201", "packet.bytes=1500",
            "fiber.delay.us=0", "wifi.model=he-su", "sfu.1.wifi.ap.mode=HCM",
            "wifi.mac.access=edca-multi-ac", "wifi.mac.exchange=data-sifs-ack", "wifi.mac.ack.rate.mbps=24",
            "wifi.mac.edca.BE.aifsn=3", "wifi.mac.edca.BE.cwmin=0", "wifi.mac.edca.BE.cwmax=0",
            "wifi.ap.activity.enabled=true", "wifi.ap.activity.window.us=200", "wifi.ap.energy.enabled=true",
            "wifi.ap.observation.enabled=true", "wifi.ap.observation.period.us=50");

    private static final List<String> EXTERNAL_CCA = List.of(
            "wifi.channel.observation.enabled=true", "wifi.channel.passive.enabled=true",
            "wifi.channel.interference.enabled=true", "wifi.phy.per.enabled=true",
            "wifi.phy.per.frequency.model=subchannel-blocks", "wifi.phy.per.source=SYNTHETIC_TEST",
            "wifi.phy.per.curves=7,-12,1;7,10,0.5;7,30,0",
            "wifi.channel.truth=1,1,0,0,30,-1,false,-95;1,1,1,0,30,-1,false,-95;"
                    + "1,1,2,0,30,-1,false,-95;1,1,3,0,30,-1,false,-95");

    @Test
    void revisedMacObservationDoesNotAlterProtocolAndIgnoresFutureTraffic(@TempDir Path folder) throws IOException {
        String enabled = "wifi.ap.prediction.enabled=true";
        String model = "wifi.ap.prediction.model=revised";
        List<String> before = run(folder.resolve("legacy.properties"), enabled, "wifi.ap.prediction.model=legacy");
        List<String> after = run(folder.resolve("revised.properties"), enabled, model);
        assertEquals(protocol(before), protocol(after));
        assertTrue(after.stream().anyMatch(line -> line.startsWith("event=WIFI_AP_MAC_EVIDENCE t_ns=50000 ")
                && line.contains("eligible_ns=50000 occupied_ns=7000 blocked_ns=0 aifs_ns=43000")));
        List<String> differentFuture = run(folder.resolve("future.properties"), enabled, model,
                "packet.arrival.us=0,71,189,225");
        assertEquals(firstForecasts(after), firstForecasts(differentFuture));
        assertEquals(2, firstForecasts(after).size());
    }

    private static List<String> protocol(List<String> trace) {
        return trace.stream().filter(line -> !line.startsWith("event=WIFI_AP_PREDICTION ")
                && !line.startsWith("event=WIFI_AP_QOS_FEASIBILITY ")
                && !line.startsWith("event=WIFI_AP_CCA_HISTORY ")
                && !line.startsWith("event=WIFI_AP_MAC_EVIDENCE ")).collect(java.util.stream.Collectors.toList());
    }

    private static List<String> firstForecasts(List<String> trace) {
        return trace.stream().filter(line -> line.startsWith("event=WIFI_AP_PREDICTION t_ns=50000 "))
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void emitsBothCandidateModesWithoutChangingUnderlyingEventsAndFreezesQosAtCutoff(@TempDir Path folder)
            throws IOException {
        List<String> before = run(folder.resolve("before.properties"));
        List<String> after = run(folder.resolve("after.properties"), "wifi.ap.prediction.enabled=true",
                "wifi.ap.prediction.window.us=100", "wifi.ap.qos.BE.deadline.us=100");
        assertEquals(before, after.stream().filter(line -> !line.startsWith("event=WIFI_AP_PREDICTION ")
                && !line.startsWith("event=WIFI_AP_QOS")).collect(java.util.stream.Collectors.toList()));
        assertTrue(after.stream().anyMatch(line -> line.startsWith("event=WIFI_AP_PREDICTION t_ns=50000 ")
                && line.contains("mode=LCM")));
        assertTrue(after.stream().anyMatch(line -> line.startsWith("event=WIFI_AP_PREDICTION t_ns=50000 ")
                && line.contains("mode=HCM confidence=")));
        String qos = after.stream().filter(line -> line.startsWith("event=WIFI_AP_QOS t_ns=200000 ")
                && line.contains("direction=DL ac=BE")).findFirst().orElseThrow();
        assertTrue(qos.contains("arrived=3 delivered=1 dropped=0 incomplete=2 overdue=1 on_time=0 violations=2"), qos);
        assertTrue(qos.contains("p95_ns=120600 p99_ns=120600"), qos);
    }

    @Test
    void revisedPredictionUsesFiveMillisecondBusyUnionDespiteOneMillisecondSampling(@TempDir Path folder)
            throws IOException {
        List<String> settings = new ArrayList<>(EXTERNAL_CCA);
        settings.addAll(List.of("packet.arrival.us=0,6100", "wifi.ap.activity.window.us=6500",
                "wifi.ap.observation.period.us=1000", "wifi.ap.prediction.window.us=1000", "wifi.ap.qos.enabled=true",
                "wifi.channel.interference.timeline=1,1,500,2000,-70,-120;1,3,1500,3500,-70,-120"));
        List<String> before = run(folder.resolve("static.properties"), settings.toArray(new String[0]));
        settings.addAll(List.of("wifi.ap.prediction.enabled=true", "wifi.ap.prediction.model=revised"));
        List<String> after = run(folder.resolve("history.properties"), settings.toArray(new String[0]));
        assertEquals(protocol(before), protocol(after));
        String wide = cca(after, 5_000_000, "WIDE");
        assertTrue(wide.contains("from_ns=0 window_ns=5000000 busy_ns=3000000 known_ns=5000000"), wide);
        wide = cca(after, 6_000_000, "WIDE");
        assertTrue(wide.contains("from_ns=1000000 window_ns=5000000 busy_ns=2500000 known_ns=5000000"), wide);
        assertTrue(cca(after, 5_000_000, "PRIMARY").contains(" busy_ns=0 known_ns=5000000 "));
        assertTrue(after.stream().anyMatch(line -> line.startsWith("event=WIFI_AP_PREDICTION t_ns=5000000 ")
                && line.contains(" mode=HCM confidence=") && line.contains(" busy_fraction=0.600000000000 ")));
    }

    @Test
    void wideBusyEvidenceIncludesOnlyCompletedAndUnexpiredScans(@TempDir Path folder) throws IOException {
        List<String> settings = new ArrayList<>(EXTERNAL_CCA);
        settings.addAll(List.of("packet.arrival.us=0,3100", "sfu.1.wifi.ap.mode=LCM", "wifi.ap.activity.window.us=3500",
                "wifi.ap.observation.period.us=250", "wifi.ap.prediction.enabled=true", "wifi.ap.prediction.model=revised",
                "wifi.channel.interference.timeline=1,3,450,500,-70,-120",
                "wifi.channel.scan.schedule=1,3,400,200,1500"));
        List<String> trace = run(folder.resolve("scans.properties"), settings.toArray(new String[0]));
        assertTrue(cca(trace, 500_000, "WIDE").contains(" busy_ns=0 known_ns=0 "));
        String wide = cca(trace, 750_000, "WIDE");
        assertTrue(wide.contains(" busy_ns=50000 known_ns=50000 "), wide);
        assertTrue(wide.contains(" current=UNKNOWN "), wide);
        assertTrue(cca(trace, 750_000, "PRIMARY").contains(" busy_ns=0 known_ns=750000 "));
        assertTrue(cca(trace, 2_250_000, "WIDE").contains(" busy_ns=0 known_ns=0 "));
    }

    private static String cca(List<String> trace, long timeNs, String band) {
        return trace.stream().filter(line -> line.startsWith("event=WIFI_AP_CCA_HISTORY t_ns=" + timeNs + " ")
                && line.contains(" band=" + band + " ")).findFirst().orElseThrow();
    }

    private static List<String> run(Path file, String... extras) throws IOException {
        List<String> properties = new ArrayList<>(BASE);
        properties.addAll(List.of(extras));
        Files.write(file, properties, StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(file), new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(java.util.stream.Collectors.toList());
    }
}
