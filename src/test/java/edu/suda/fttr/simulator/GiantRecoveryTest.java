package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.GiantDbaPolicy;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.entity.AccessCategory;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiantRecoveryTest {
    @Test
    void settlesTheTwoFragmentsThenRecoversFromAFullReport(@TempDir Path dir) throws IOException {
        String input = "packet.arrival.us=25,50\npacket.bytes=1500\n";
        var original = run(dir, false, "103", type3(103, "VO,VI,BE,BK", 2400), input);
        assertEquals(List.of("2400", "600", "900", "600"), rows(original, "PON_DBA_FILL").stream().limit(4)
                .map(row -> row.get("budget_bytes")).collect(Collectors.toList()));
        assertEquals(List.of("1"), delivered(original));

        var recovered = run(dir, true, "103", type3(103, "VO,VI,BE,BK", 2400), input);
        assertEquals(List.of("1", "2"), delivered(recovered));
        assertEquals(List.of("2400", "600", "900", "1500"), rows(recovered, "PON_DBA_FILL").stream()
                .map(row -> row.get("budget_bytes")).collect(Collectors.toList()));
        assertTrue(rows(recovered, "PON_GIANT_DECISION").stream().anyMatch(row -> row.containsValue("RECOVERY_DRAIN")));
        audit(recovered);
    }

    @Test
    void waitsForTwoLegalPhasesWhenOneQuotaCannotFitTheHead(@TempDir Path dir) throws IOException {
        var trace = run(dir, true, "103", type3(103, "VO,VI,BE,BK", 1200),
                "packet.arrival.us=25\npacket.bytes=1500\n");
        assertEquals(List.of("1"), delivered(trace));
        assertTrue(rows(trace, "PON_GIANT_DECISION").stream().anyMatch(row -> number(row, "budget_bytes") == 1500
                && number(row, "assured_bytes") > 0 && number(row, "surplus_bytes") > 0));
        audit(trace);
    }

    @Test
    void withholdsAKnownSmallGrantUntilTheAssuredAndSurplusStagesCanMerge() {
        TcontProfile profile = new TcontProfile(103, 1, 3, EnumSet.allOf(AccessCategory.class), 2, 1, 1200, 1200);
        var object = new DbaContext.Allocation(103, 1, 1500L, 3L, 1000L, false, profile, 1200, false);
        GiantDbaPolicy giant = new GiantDbaPolicy(true);
        assertTrue(giant.plan(new DbaContext(125_000, 2, BigDecimal.valueOf(2500), 125_000, 205, List.of(object)))
                .grants().isEmpty());
        assertEquals(GiantDbaPolicy.Reason.RECOVERY_NEEDS_LARGER_GRANT, giant.lastDecisions().get(0).surplusReason());
        var grant = giant.plan(new DbaContext(250_000, 3, BigDecimal.valueOf(2500), 125_000, 205, List.of(object)))
                .grants().get(0);
        assertEquals(1500, grant.plannedBytes());
        assertEquals(4800, grant.plannedDurationNs());
    }

    @Test
    void reportsAnImpossibleSlotWithoutRepeatingTheSameDynamicBudget(@TempDir Path dir) throws IOException {
        var trace = run(dir, true, "103", type3(103, "VO,VI,BE,BK", 700),
                "packet.arrival.us=25\npacket.bytes=1500\n");
        assertTrue(delivered(trace).isEmpty());
        assertTrue(rows(trace, "PON_DBA_FILL").size() <= 4);
        assertTrue(rows(trace, "PON_GIANT_DECISION").stream().anyMatch(row -> row.containsValue("RECOVERY_UNSERVICEABLE")
                && number(row, "recovery_ceiling_bytes") == 1400 && row.get("recovery_limit").equals("true")));
        audit(trace);
    }

    @Test
    void preservesFixedServiceWhileRecoveringTypeFive(@TempDir Path dir) throws IOException {
        String profile = profile(103, "type=5", "classes=VO,VI,BE,BK", "si.fixed.frames=1", "ab.fixed.bytes=900",
                "si.max.frames=1", "ab.min.bytes=2400", "si.min.frames=1", "ab.sur.bytes=2400",
                "si.be.frames=1", "ab.be.bytes=2400");
        var trace = run(dir, true, "103", profile, "packet.arrival.us=25,50\npacket.bytes=1600\n");
        assertEquals(List.of("1", "2"), delivered(trace));
        var decisions = rows(trace, "PON_GIANT_DECISION");
        assertTrue(decisions.size() > 5);
        assertTrue(decisions.stream().allMatch(row -> number(row, "fixed_bytes") == 900));
        audit(trace);
    }

    @Test
    void distinctAllocationsMakeProgressWithVariablePacketsAndABurstAcrossFrames(@TempDir Path dir) throws IOException {
        String profiles = type3(103, "BE,BK", 2500) + type3(104, "VO,VI", 2200);
        String input = "packet.arrival.us=25,50,65,85,240,400,510,610,730,815,960,1010\npacket.bytes=1750\n"
                + "packet.3.type=VI\npacket.3.bytes=1000\npacket.4.type=VI\npacket.4.bytes=1530\n"
                + "packet.5.bytes=1100\npacket.7.type=VI\npacket.7.bytes=1900\npacket.9.bytes=850\n";
        var trace = run(dir, true, "103,104", profiles, input);
        assertEquals(12, delivered(trace).size());
        var deliveries = delivered(trace);
        assertEquals(List.of("3", "4", "7"), deliveries.stream().filter(Set.of("3", "4", "7")::contains).collect(Collectors.toList()));
        assertEquals(List.of("1", "2", "5", "6", "8", "9", "10", "11", "12"),
                deliveries.stream().filter(packet -> !Set.of("3", "4", "7").contains(packet)).collect(Collectors.toList()));
        assertTrue(rows(trace, "PON_GIANT_DECISION").stream().anyMatch(row -> row.get("budget_outstanding").equals("true")));
        audit(trace);
    }

    private static String type3(long id, String classes, int quota) {
        return profile(id, "type=3", "classes=" + classes, "si.max.frames=2", "si.min.frames=1",
                "ab.min.bytes=" + quota, "ab.sur.bytes=" + quota);
    }

    private static String profile(long id, String... keys) {
        String prefix = "fiber.uplink.tcont.alloc." + id + ".";
        return prefix + "sfu.id=1\n" + Arrays.stream(keys).map(key -> prefix + key + "\n").collect(Collectors.joining());
    }

    private static List<Map<String, String>> run(Path dir, boolean fix, String ids, String profiles, String input) throws IOException {
        String mode = fix ? "giant-complete-recovery" : "giant-complete";
        String settings = "traffic.direction=UL\ntraffic.mode=manual\nwifi.model=fixed\nwifi.delay.us=0\n"
                + "fiber.downlink.mode=pon-shared\nfiber.downlink.propagation.us=10\n"
                + "fiber.uplink.mode=pon-framed\nfiber.uplink.propagation.us=10.025\nfiber.uplink.report.enabled=true\n"
                + "fiber.uplink.bwmap.enabled=true\nfiber.uplink.demand.ledger.enabled=true\nfiber.uplink.poll.enabled=true\n"
                + "fiber.uplink.poll.end.us=8000\nfiber.uplink.dba.mode=" + mode + "\nfiber.uplink.tcont.enabled=true\n"
                + "fiber.uplink.tcont.alloc.ids=" + ids + "\n" + profiles + input;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(Files.writeString(dir.resolve("recovery.properties"), settings)), new TraceLogger(out));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().map(line -> Arrays.stream(line.split(" "))
                .filter(word -> word.contains("=")).map(word -> word.split("=", 2))
                .collect(Collectors.toMap(word -> word[0], word -> word[1]))).collect(Collectors.toList());
    }

    private static List<Map<String, String>> rows(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static List<String> delivered(List<Map<String, String>> trace) {
        return rows(trace, "DELIVERED").stream().map(row -> row.get("packet_id")).collect(Collectors.toList());
    }

    private static long number(Map<String, String> row, String key) {
        return Long.parseLong(row.get(key));
    }

    private static void audit(List<Map<String, String>> trace) {
        Set<String> settled = new HashSet<>();
        for (var row : rows(trace, "PON_DBA_SETTLEMENT_RX")) {
            assertTrue(settled.add(row.get("grant_id")));
            assertEquals(number(row, "budget_bytes"), number(row, "actual_bytes") + number(row, "released_unused_bytes"));
        }
        assertEquals(rows(trace, "PON_DBA_BWMAP_TX").size(), settled.size());
        assertEquals(delivered(trace).size(), new HashSet<>(delivered(trace)).size());
        for (var row : rows(trace, "PON_DBA_MULTI_PLAN")) {
            assertTrue(number(row, "planned_duration_ns") + number(row, "control_reserved_ns") <= 125_000);
        }
    }
}
