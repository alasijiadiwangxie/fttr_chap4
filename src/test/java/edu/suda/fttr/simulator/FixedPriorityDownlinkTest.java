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

class FixedPriorityDownlinkTest {
    @Test
    void selectsHighestPriorityAfterAllSameTimeArrivalsEnterQueues(@TempDir Path directory) throws IOException {
        // 光侧时延为零，MFU 到达会在同一时刻派生光发送和 SFU 入队事件。
        List<Map<String, String>> trace = runScenario(directory, 0, 15, "0,0,0,0", List.of(
                "packet.1.type=BE", "packet.2.type=BK", "packet.3.type=VI", "packet.4.type=VO"));

        assertEquals(List.of("4:VO@0", "3:VI@15", "1:BE@30", "2:BK@45"), starts(trace));
        assertEquals(List.of("1:BE@0", "2:BK@0", "3:VI@0", "4:VO@0"), timeline(trace, "SFU_ENQUEUE"));
        Map<String, String> firstSelection = events(trace, "QUEUE_SELECT").get(0);
        for (String queue : List.of("vo_queue", "vi_queue", "be_queue", "bk_queue")) {
            assertEquals("1", firstSelection.get(queue), queue);
        }
    }

    @Test
    void finishesActiveBestEffortPacketBeforeServingVoice(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = runScenario(directory, 6, 100, "0,10",
                List.of("packet.1.type=BE", "packet.2.type=VO"));

        assertEquals(List.of("1:BE@6", "2:VO@106"), starts(trace));
        assertEquals(List.of("1:BE@106", "2:VO@206"), timeline(trace, "WIFI_TX_END"));
        assertEquals("90", events(trace, "WIFI_TX_END").get(1).get("wifi_queue_wait_us"));
        assertEquals("196", events(trace, "WIFI_TX_END").get(1).get("e2e_delay_us"));
    }

    @Test
    void preservesFifoWithinVoiceIncludingTiedArrivals(@TempDir Path directory) throws IOException {
        List<Map<String, String>> trace = runScenario(directory, 3, 20, "0,5,5", List.of(
                "packet.1.type=VO", "packet.2.type=VO", "packet.3.type=VO",
                "packet.1.bytes=300", "packet.2.bytes=420", "packet.3.bytes=180"));

        assertEquals(List.of("1:VO@3", "2:VO@23", "3:VO@43"), starts(trace));
        assertEquals(List.of("300", "420", "180"), events(trace, "DELIVERED").stream()
                .map(event -> event.get("bytes")).collect(Collectors.toList()));
        assertClassStats(trace, "VO", 3, 50.0 / 3, 119.0 / 3);
    }

    @Test
    void picksHighestWaitingClassAtEveryCompletionAndReportsAccurateAverages(@TempDir Path directory)
            throws IOException {
        List<String> overrides = List.of(
                "packet.1.type=VI", "packet.1.bytes=700", "packet.2.type=BE",
                "packet.3.type=BK", "packet.4.type=VO", "packet.4.bytes=300",
                "packet.5.type=VI", "packet.5.bytes=500", "packet.6.type=VO", "packet.6.bytes=900");

        // 两个光时延让同刻到达分别先于和晚于此前安排的完成事件入队。
        for (long fiberDelay : List.of(10L, 50L)) {
            List<Map<String, String>> trace = runScenario(directory, fiberDelay, 40,
                    "55,0,5,40,9,80", overrides);
            assertEquals(List.of("1:BE@" + fiberDelay, "4:VO@" + (fiberDelay + 40),
                    "6:VO@" + (fiberDelay + 80), "3:VI@" + (fiberDelay + 120),
                    "5:VI@" + (fiberDelay + 160), "2:BK@" + (fiberDelay + 200)), starts(trace));
            assertEquals(List.of("1200", "300", "900", "500", "700", "1200"),
                    events(trace, "DELIVERED").stream().map(event -> event.get("bytes")).collect(Collectors.toList()));
            assertClassStats(trace, "VO", 2, 0, fiberDelay + 40);
            assertClassStats(trace, "VI", 2, 108, fiberDelay + 148);
            assertClassStats(trace, "BE", 1, 0, fiberDelay + 40);
            assertClassStats(trace, "BK", 1, 195, fiberDelay + 235);
            assertEquals("4800", trace.get(trace.size() - 1).get("delivered_bytes"));
        }
    }

    @Test
    void defaultAndExplicitBestEffortReproduceTheExistingSerialScenario(@TempDir Path directory)
            throws IOException {
        List<Map<String, String>> defaultTrace = run(Path.of("configs", "phase1-serial-downlink.properties"));
        List<Map<String, String>> explicitTrace = runScenario(directory, 100, 250, "0,0,250,1000",
                List.of("packet.1.type=BE", "packet.2.type=BE", "packet.3.type=BE", "packet.4.type=BE"));

        assertEquals(defaultTrace, explicitTrace);
        assertEquals(List.of("1:BE@100", "2:BE@350", "3:BE@600", "4:BE@1100"), starts(defaultTrace));
        assertEquals(List.of("1:BE@350", "2:BE@600", "3:BE@850", "4:BE@1350"),
                timeline(defaultTrace, "WIFI_TX_END"));
        assertClassStats(defaultTrace, "BE", 4, 125, 475);
        for (String type : List.of("VO", "VI", "BK")) {
            assertEquals("0", classStats(defaultTrace, type).get("sent_packets"));
        }
        Map<String, String> totals = defaultTrace.get(defaultTrace.size() - 1);
        Map.of("delivered_packets", "4", "delivered_bytes", "4800", "wifi_busy_us", "1000",
                "wifi_idle_us", "350", "simulation_end_us", "1350")
                .forEach((key, value) -> assertEquals(value, totals.get(key), key));
    }

    private List<Map<String, String>> runScenario(Path directory, long fiberDelay, long wifiDelay,
                                                   String arrivals, List<String> overrides) throws IOException {
        Path path = directory.resolve("priority.properties");
        String base = "packet.bytes=1200\npacket.arrival.us=" + arrivals
                + "\nfiber.delay.us=" + fiberDelay + "\nwifi.delay.us=" + wifiDelay + "\n";
        Files.writeString(path, base + String.join("\n", overrides), StandardCharsets.UTF_8);
        return run(path);
    }

    private List<Map<String, String>> run(Path scenario) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(scenario), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().map(this::fields).collect(Collectors.toList());
    }

    // 按字段读取轨迹，新增诊断字段不影响时间和顺序的断言。
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

    private List<String> starts(List<Map<String, String>> trace) {
        return timeline(trace, "WIFI_TX_START");
    }

    private List<String> timeline(List<Map<String, String>> trace, String action) {
        return events(trace, action).stream()
                .map(event -> event.get("packet_id") + ":" + event.get("type") + "@" + event.get("t_us"))
                .collect(Collectors.toList());
    }

    private List<Map<String, String>> events(List<Map<String, String>> trace, String action) {
        return trace.stream().filter(event -> action.equals(event.get("event"))).collect(Collectors.toList());
    }

    private void assertClassStats(List<Map<String, String>> trace, String type, int count,
                                  double meanQueueWait, double meanEndToEndDelay) {
        Map<String, String> stats = classStats(trace, type);
        assertEquals(Integer.toString(count), stats.get("sent_packets"));
        assertEquals(meanQueueWait, Double.parseDouble(stats.get("avg_queue_wait_us")), 0.005);
        assertEquals(meanEndToEndDelay, Double.parseDouble(stats.get("avg_e2e_delay_us")), 0.005);
    }

    private Map<String, String> classStats(List<Map<String, String>> trace, String type) {
        return trace.stream().filter(line -> "CLASS".equals(line.get("summary")) && type.equals(line.get("type")))
                .findFirst().orElseThrow(() -> new AssertionError("Missing class summary: " + type));
    }
}
