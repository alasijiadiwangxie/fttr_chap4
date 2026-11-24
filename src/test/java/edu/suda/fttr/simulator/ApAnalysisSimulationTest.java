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
