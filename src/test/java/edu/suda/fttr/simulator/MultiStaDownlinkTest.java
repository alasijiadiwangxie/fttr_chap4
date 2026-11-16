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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiStaDownlinkTest {
    @Test
    void unicastsUsingOneNonPreemptiveRadioAndGlobalClassFifo() throws IOException {
        // 第一个配置项最后到达，其中的目标 STA 仍随原配置项绑定。
        // 首包 BE 开始发送后，高优先级 VO 从不同 STA 的业务流进入同一队列。
        List<Map<String, String>> trace = run(Path.of("configs", "phase1-multi-sta-downlink.properties"));

        // 无线起止时刻在所有 STA 之间连续且不重叠，三条 VO 按入队顺序发送。
        assertEquals(List.of("1:BE@100", "4:VO@140", "5:VO@180", "6:VO@220", "3:VI@260", "2:BK@300"),
                events(trace, "WIFI_TX_START").stream()
                        .map(row -> row.get("packet_id") + ":" + row.get("type") + "@" + row.get("t_us"))
                        .collect(Collectors.toList()));
        assertEquals(List.of("1:2>2@140", "4:3>3@180", "5:2>2@220", "6:1>1@260", "3:1>1@300", "2:2>2@340"),
                events(trace, "DELIVERED").stream()
                        .map(row -> row.get("packet_id") + ":" + row.get("destination_sta_id") + ">"
                                + row.get("sta_id") + "@" + row.get("t_us"))
                        .collect(Collectors.toList()));

        assertStaStats(trace, 1, 2, 1800, 42.35, 255.00);
        assertStaStats(trace, 2, 3, 2400, 56.47, 226.67);
        assertStaStats(trace, 3, 1, 200, 4.71, 170.00);
        assertStaStats(trace, 4, 0, 0, 0.00, 0.00);
        assertEquals("4400", trace.get(trace.size() - 1).get("delivered_bytes"));
        assertEquals("240", trace.get(trace.size() - 1).get("wifi_busy_us"));
    }

    @Test
    void defaultsToStaOneAndHandlesZeroObservationWindow(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = run(writeScenario(directory, List.of(
                "packet.arrival.us=0", "packet.bytes=1000", "fiber.delay.us=0", "wifi.delay.us=0")));

        List<Map<String, String>> deliveries = events(trace, "DELIVERED");
        assertEquals(1, deliveries.size());
        assertEquals("1", deliveries.get(0).get("destination_sta_id"));
        assertEquals("1", deliveries.get(0).get("sta_id"));
        assertStaStats(trace, 1, 1, 1000, 0.00, 0.00);
    }

    @Test
    void rejectsTargetsOutsideConfiguredStaIds(@TempDir Path directory) throws IOException {
        for (int id : List.of(0, 3)) {
            Path scenario = writeScenario(directory, List.of(
                    "sta.count=2", "packet.arrival.us=0", "packet.bytes=1000",
                    "fiber.delay.us=100", "wifi.delay.us=40", "packet.1.sta.id=" + id));
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(scenario), "STA ID=" + id);
        }
    }

    private Path writeScenario(Path directory, List<String> properties) throws IOException {
        return Files.write(directory.resolve("multista.properties"), properties, StandardCharsets.UTF_8);
    }

    private List<Map<String, String>> run(Path path) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(this::fields).collect(Collectors.toList());
    }

    private Map<String, String> fields(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String item : line.split(" ")) {
            int separator = item.indexOf('=');
            if (separator >= 0) {
                fields.put(item.substring(0, separator), item.substring(separator + 1));
            }
        }
        return fields;
    }

    private List<Map<String, String>> events(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private void assertStaStats(List<Map<String, String>> trace, int id, int packets, long bytes,
                                double throughputMbps, double averageDelayUs) {
        List<Map<String, String>> summaries = trace.stream()
                .filter(row -> "STA".equals(row.get("summary")) && Integer.toString(id).equals(row.get("sta_id")))
                .collect(Collectors.toList());
        assertEquals(1, summaries.size(), "STA " + id);
        Map<String, String> summary = summaries.get(0);
        assertEquals(Integer.toString(packets), summary.get("received_packets"), "STA " + id);
        assertEquals(Long.toString(bytes), summary.get("received_bytes"), "STA " + id);
        assertEquals(throughputMbps, Double.parseDouble(summary.get("throughput_mbps")), 0.0001, "STA " + id);
        assertEquals(averageDelayUs, Double.parseDouble(summary.get("avg_e2e_delay_us")), 0.0001, "STA " + id);
    }
}
