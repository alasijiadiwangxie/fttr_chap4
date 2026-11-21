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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiantDbaExecutionTest {
    private static final long FRAME_NS = 125_000;
    private static final String BWMAP_TX = "PON_DBA_BWMAP_TX";
    private static final String SETTLEMENT = "PON_DBA_SETTLEMENT_RX";

    @Test
    void giantCapsAndDelaysAssuredServiceComparedWithRrButDeliversTheSameTraffic(@TempDir Path directory)
            throws IOException {
        String settings = mixedConfiguration();
        List<Map<String, String>> giant = run(directory, settings);
        List<Map<String, String>> rr = run(directory, settings.replace("dba.mode=giant", "dba.mode=rr-multi"));

        List<Map<String, String>> giantAssured = rowsFor(events(giant, BWMAP_TX), "101");
        List<Map<String, String>> rrAssured = rowsFor(events(rr, BWMAP_TX), "101");
        assertEquals(List.of("1200", "1200"), values(giantAssured, "budget_bytes"));
        assertEquals(List.of("2400"), values(rrAssured, "budget_bytes"));
        assertTrue(number(giantAssured.get(0), "target_frame_id") > number(rrAssured.get(0), "target_frame_id"));
        assertEquals(Set.of("1", "2", "3", "4"), new HashSet<>(values(events(giant, "DELIVERED"), "packet_id")));
        assertEquals(new HashSet<>(values(events(rr, "DELIVERED"), "packet_id")),
                new HashSet<>(values(events(giant, "DELIVERED"), "packet_id")));

        Map<String, String> type3 = rowsFor(events(giant, "PON_GIANT_DECISION"), "102").stream()
                .filter(row -> "800".equals(row.get("assured_bytes")) && "400".equals(row.get("surplus_bytes")))
                .findFirst().orElseThrow();
        List<Map<String, String>> matchingType3 = rowsFor(events(giant, BWMAP_TX), "102").stream()
                .filter(row -> type3.get("target_frame_id").equals(row.get("target_frame_id")))
                .collect(Collectors.toList());
        assertEquals(List.of("1200"), values(matchingType3, "budget_bytes"), "Both phases use one real BWmap");
        assertTrue(events(rr, "PON_GIANT_DECISION").isEmpty());
        assertEquals(giant, run(directory, settings), "Every simulation run owns fresh service counters");
        assertExecutionConsistency(giant, 10_025, 4);
    }

    @Test
    void delayedReportsAndOutstandingUnderfilledGrantsDoNotDuplicateDataOrDecisions(@TempDir Path directory)
            throws IOException {
        String settings = baseSettings("160")
                + "sfu.count=1\nsta.count=1\nsta.1.sfu.id=1\npacket.arrival.us=0\n"
                + "fiber.uplink.tcont.alloc.ids=101\n"
                + profile(101, 1, 2, "VO,VI,BE,BK", 1, 0, 800, 0);
        List<Map<String, String>> trace = run(directory, settings);
        long firstReport = number(events(trace, "TCONT_REPORT_RX").get(0), "time_ns");
        assertEquals(410_205, firstReport);
        List<Map<String, String>> decisions = events(trace, "PON_GIANT_DECISION");
        assertTrue(decisions.stream().anyMatch(row -> "UNKNOWN".equals(row.get("assured_reason"))));
        assertTrue(decisions.stream().anyMatch(row -> "AWAITING_SETTLEMENT".equals(row.get("assured_reason"))));

        List<Map<String, String>> grants = events(trace, BWMAP_TX);
        Map<String, Map<String, String>> settledById = events(trace, SETTLEMENT).stream()
                .collect(Collectors.toMap(row -> row.get("grant_id"), row -> row));
        assertTrue(grants.size() >= 2, "Test must observe the return of an unused grant through settlement");
        long previousSettlement = firstReport;
        for (Map<String, String> grant : grants) {
            assertTrue(number(grant, "time_ns") >= previousSettlement);
            assertEquals(800, number(grant, "budget_bytes"));
            Map<String, String> settled = settledById.get(grant.get("grant_id"));
            assertEquals(800, number(settled, "unused_bytes"));
            assertEquals(0, number(settled, "actual_bytes"));
            previousSettlement = number(settled, "time_ns");
        }
        assertTrue(events(trace, "PON_DBA_DATA_START").isEmpty(), "An 800 B grant cannot load a 1200 B packet");
        assertExecutionConsistency(trace, 160_000, 0);
    }

    @Test
    void configRejectsGiantWithoutTcontAndWithoutRequiredReportPath(@TempDir Path directory) throws IOException {
        String valid = mixedConfiguration();
        String noTcont = valid.lines().filter(line -> !line.startsWith("fiber.uplink.tcont."))
                .collect(Collectors.joining("\n"));
        assertThrows(IllegalArgumentException.class, () -> load(directory, noTcont));
        assertThrows(IllegalArgumentException.class, () -> load(directory,
                valid.replace("fiber.uplink.poll.enabled=true", "fiber.uplink.poll.enabled=false")));
        assertThrows(IllegalArgumentException.class, () -> load(directory,
                valid.replace("fiber.uplink.tcont.alloc.101.type=2", "fiber.uplink.tcont.alloc.101.type=1")));
    }

    private static void assertExecutionConsistency(List<Map<String, String>> trace, long propagationNs,
                                                   int expectedDeliveries) {
        List<Map<String, String>> budgets = events(trace, BWMAP_TX);
        List<String> budgetIds = values(budgets, "grant_id");
        Set<String> distinctBudgets = new HashSet<>(budgetIds);
        assertEquals(budgets.size(), distinctBudgets.size());
        assertEquals(budgets.size(), budgets.stream().map(row -> row.get("target_frame_id") + "/"
                + row.get("allocation_id")).distinct().count(), "Every object gets at most one budget per frame");
        for (String phase : List.of("PON_DBA_LEDGER_CREATED", "PON_DBA_BWMAP_RX", "PON_DBA_FILL",
                "PON_DBA_SLOT_END", SETTLEMENT)) {
            List<String> actual = values(events(trace, phase), "grant_id");
            assertEquals(budgets.size(), actual.size(), phase);
            assertEquals(distinctBudgets, new HashSet<>(actual), phase);
        }
        for (Map<String, String> settled : events(trace, SETTLEMENT)) {
            assertEquals(number(settled, "tx_end_ns") + propagationNs, number(settled, "time_ns"));
            assertEquals(number(settled, "budget_bytes"),
                    number(settled, "actual_bytes") + number(settled, "unused_bytes"));
        }
        List<String> delivered = values(events(trace, "DELIVERED"), "packet_id");
        assertEquals(expectedDeliveries, delivered.size());
        assertEquals(expectedDeliveries, new HashSet<>(delivered).size());

        List<Map<String, String>> windows = new ArrayList<>(budgets);
        windows.addAll(events(trace, "PON_POLL_BWMAP_TX"));
        assertFalse(windows.isEmpty());
        windows.sort(Comparator.comparingLong(row -> number(row, "tx_start_ns")));
        long previousEnd = 0;
        for (Map<String, String> window : windows) {
            long start = number(window, "tx_start_ns");
            long end = number(window, "tx_end_ns");
            long frame = number(window, "target_frame_id");
            assertTrue(start >= previousEnd && start < end && start >= frame * FRAME_NS
                    && end <= (frame + 1) * FRAME_NS, window.toString());
            previousEnd = end;
        }
    }

    private static String mixedConfiguration() {
        // 两个 VO 包进入 Alloc-ID 101，BE/BK 分别进入两个不同 SFU 的 Alloc-ID 102 和 201。
        return baseSettings("10.025")
                + "sfu.count=2\nsta.count=2\nsta.1.sfu.id=1\nsta.2.sfu.id=2\n"
                + "packet.arrival.us=0,0,0,0\npacket.1.type=VO\npacket.2.type=VO\n"
                + "packet.3.type=BE\npacket.4.type=BK\npacket.4.sta.id=2\n"
                + "fiber.uplink.tcont.alloc.ids=101,102,201\n"
                + profile(101, 1, 2, "VO,VI", 2, 0, 1200, 0)
                + profile(102, 1, 3, "BE,BK", 1, 1, 800, 400)
                + profile(201, 2, 4, "VO,VI,BE,BK", 1, 4, 0, 1200);
    }

    private static String baseSettings(String propagationUs) {
        return String.join("\n", "traffic.direction=UL", "traffic.mode=manual", "packet.bytes=1200",
                "wifi.model=fixed", "wifi.delay.us=0", "wifi.mac.access=fixed-priority",
                "fiber.downlink.mode=pon-shared", "fiber.downlink.rate.mbps=10000",
                "fiber.downlink.propagation.us=10", "fiber.uplink.mode=pon-framed",
                "fiber.uplink.rate.mbps=2500", "fiber.uplink.frame.period.us=125",
                "fiber.uplink.propagation.us=" + propagationUs, "fiber.uplink.report.enabled=true",
                "fiber.uplink.bwmap.enabled=true", "fiber.uplink.demand.ledger.enabled=true",
                "fiber.uplink.poll.enabled=true", "fiber.uplink.poll.bytes=64",
                "fiber.uplink.poll.interval.frames=1", "fiber.uplink.poll.end.us=2500",
                "fiber.uplink.dba.mode=giant", "fiber.uplink.tcont.enabled=true") + "\n";
    }

    private static String profile(long id, int sfuId, int type, String classes, int siMax, int siMin,
                                  long abMin, long abSur) {
        String key = "fiber.uplink.tcont.alloc." + id + ".";
        return key + "sfu.id=" + sfuId + "\n" + key + "type=" + type + "\n" + key + "classes=" + classes + "\n"
                + key + "si.max.frames=" + siMax + "\n" + key + "si.min.frames=" + siMin + "\n"
                + key + "ab.min.bytes=" + abMin + "\n" + key + "ab.sur.bytes=" + abSur + "\n";
    }

    private static edu.suda.fttr.config.SimulationConfig load(Path directory, String settings) throws IOException {
        Path config = Files.writeString(directory.resolve("giant-db.properties"), settings, StandardCharsets.UTF_8);
        return ConfigLoader.load(config);
    }

    private static List<Map<String, String>> run(Path directory, String settings) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(load(directory, settings), new TraceLogger(out));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().map(GiantDbaExecutionTest::fields)
                .collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> row = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2) {
                row.put(pair[0], pair[1]);
            }
        }
        return row;
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static List<Map<String, String>> rowsFor(List<Map<String, String>> rows, String allocation) {
        return rows.stream().filter(row -> allocation.equals(row.get("allocation_id"))).collect(Collectors.toList());
    }

    private static List<String> values(List<Map<String, String>> rows, String field) {
        return rows.stream().map(row -> row.get(field)).collect(Collectors.toList());
    }

    private static long number(Map<String, String> row, String field) {
        return Long.parseLong(row.get(field));
    }
}
