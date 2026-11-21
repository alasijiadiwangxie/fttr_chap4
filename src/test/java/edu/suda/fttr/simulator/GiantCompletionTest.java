package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;
import edu.suda.fttr.dba.GiantDbaPolicy;
import edu.suda.fttr.dba.GiantSlaAdmission;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GiantCompletionTest {
    private static final BigDecimal RATE = BigDecimal.valueOf(2500);
    private static final long FRAME_NS = 125_000;
    private static final Set<AccessCategory> CLASSES = Set.of(AccessCategory.BE);

    @Test
    void fixedAssuredNonAssuredBestEffortAreDecidedInOrderAndMerged() {
        var input = frame(1, FRAME_NS, 205, List.of(
                allocation(legacy(1, 2, 4, 0, 40000), 80000L),
                allocation(legacy(2, 1, 3, 1200, 1200), 5000L),
                allocation(legacy(3, 2, 2, 2400, 0), 7000L),
                allocation(type5(4, 1, 1, 1, 1, 1), 6000L),
                allocation(fixed(5, 1, 1, 1200), null)));
        var policy = new GiantDbaPolicy(true);
        var plan = policy.plan(input);
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L),
                plan.grants().stream().map(DbaPlan.Grant::allocationId).collect(Collectors.toList()));
        var fixedOnly = decision(policy, 5);
        var composite = decision(policy, 4);
        var video = decision(policy, 2);
        var bestEffort = decision(policy, 1);
        assertEquals(1200, fixedOnly.fixedBytes());
        assertEquals(2400, video.budgetBytes());
        assertEquals(1200, composite.fixedBytes());
        assertEquals(1200, composite.assuredBytes());
        assertEquals(1200, composite.nonAssuredBytes());
        // BE group starts at ID 1 and uses up the remaining capacity before ID 4.
        assertEquals(0, composite.bestEffortBytes());
        assertEquals(0, bestEffort.assuredBytes());
        assertEquals(8, bestEffort.bestEffortOrder());
        assertTrue(fixedOnly.fixedOrder() < composite.assuredOrder());
        assertTrue(composite.assuredOrder() < video.nonAssuredOrder());
        assertTrue(video.nonAssuredOrder() < bestEffort.bestEffortOrder());
        assertEquals(5, new DbaPlanValidator().validateAndPreview(input, plan, true).size());
    }

    @Test
    void compositeTimersAdvanceIndependentlyAndSplitBudgetOnlyInDiagnostics() {
        var composite = type5(101, 1, 4, 2, 3, 1);
        var policy = new GiantDbaPolicy(true);
        for (int id = 1; id <= 12; id++) {
            var input = frame(id, FRAME_NS, 205, List.of(allocation(composite, 12000L)));
            var plan = policy.plan(input);
            var row = decision(policy, 101);
            assertEquals(4 - (id - 1) % 4, row.siFixedBefore());
            assertEquals(2 - (id - 1) % 2, row.siMaxBefore());
            assertEquals(3 - (id - 1) % 3, row.siMinBefore());
            assertEquals(1, row.siBeBefore());
            assertEquals(id % 4 == 0 ? 1200 : 0, row.fixedBytes());
            assertEquals(id % 2 == 0 ? 1200 : 0, row.assuredBytes());
            assertEquals(id % 3 == 0 ? 1200 : 0, row.nonAssuredBytes());
            assertEquals(1200, row.bestEffortBytes());
            assertEquals(1, plan.grants().size());
            assertEquals(row.budgetBytes(), plan.grants().get(0).plannedBytes());
            new DbaPlanValidator().validateAndPreview(input, plan, true);
        }
    }

    @Test
    void onlyFixedCanReserveWithoutAReportAndFixedDoesNotHideDemandOverrun() {
        var p = type5(1, 1, 1, 1, 1, 1);
        var input = frame(1, FRAME_NS, 205, List.of(allocation(p, null)));
        var validator = new DbaPlanValidator();
        var plan = new GiantDbaPolicy(true).plan(input);
        assertEquals(1200, validator.validateAndPreview(input, plan, true).get(0).fixedBytes());
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndPreview(input, plan));
        var overFixed = new DbaPlan(0, 1, List.of(new DbaPlan.Grant(1, 1, 2400, 7680, null, 2400)), "TEST");
        var overDemand = new DbaPlan(0, 1, List.of(new DbaPlan.Grant(1, 1, 2400, 7680, null, 1200)), "TEST");
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndPreview(input, overFixed, true));
        assertThrows(IllegalArgumentException.class, () -> validator.validateAndPreview(input, overDemand, true));
    }

    @Test
    void congestedPeerRotationDoesNotCountAnUnusableTailAsFullService() {
        var policy = new GiantDbaPolicy(true);
        var a = legacy(1, 1, 4, 0, 1200);
        var b = legacy(2, 2, 4, 0, 1200);
        for (int id = 1; id <= 8; id++) {
            var input = frame(id, 4000, 0, List.of(allocation(a, 10000L), allocation(b, 10000L)));
            var plan = policy.plan(input);
            assertEquals(2, plan.grants().size());
            assertEquals(id % 2 == 1 ? 1 : 2, plan.grants().get(0).allocationId());
            assertEquals(1200, plan.grants().get(0).plannedBytes());
            assertEquals(50, plan.grants().get(1).plannedBytes());
        }
    }

    @Test
    void absentAssuredDemandLeavesBandwidthToBestEffortInSameDecision() {
        var assured = legacy(2, 1, 2, 1200, 0);
        var be = legacy(1, 2, 4, 0, 10000);
        var idle = frame(1, 4000, 0, List.of(allocation(be, 10000L), allocation(assured, 0L)));
        var busy = frame(1, 4000, 0, List.of(allocation(be, 10000L), allocation(assured, 1200L)));
        assertEquals(1250, new GiantDbaPolicy(true).plan(idle).grants().get(0).plannedBytes());
        var policy = new GiantDbaPolicy(true);
        policy.plan(busy);
        assertEquals(1200, decision(policy, 2).assuredBytes());
        assertEquals(50, decision(policy, 1).bestEffortBytes());
    }

    @Test
    void admissionIncludesPollAndRoundingAndRejectsInfeasibleProfiles() {
        GiantSlaAdmission.validate(List.of(fixed(1, 1, 1, 38998)), FRAME_NS, RATE, 64);
        assertThrows(IllegalArgumentException.class,
                () -> GiantSlaAdmission.validate(List.of(fixed(1, 1, 1, 38999)), FRAME_NS, RATE, 64));
        assertThrows(IllegalArgumentException.class, () -> GiantSlaAdmission.validate(
                List.of(fixed(1, 1, 1, 1), fixed(2, 2, 1, 1)), 7, RATE, 0));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> GiantSlaAdmission.validate(
                List.of(fixed(1, 1, 1, Long.MAX_VALUE)), FRAME_NS, RATE, 64)).getMessage().contains("SLA infeasible"));
    }

    @Test
    void invalidSlaAndMissingType5ComponentRejectedAtConfiguration(@TempDir Path dir) throws IOException {
        var valid = mixedConfiguration();
        var config = ConfigLoader.load(Files.writeString(dir.resolve("valid.properties"), valid));
        assertTrue(config.dbaGiantCompleteEnabled());
        var invalid = valid.replace("101.ab.fixed.bytes=1200", "101.ab.fixed.bytes=39000");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(Files.writeString(dir.resolve("invalid.properties"), invalid)))
                .getMessage().contains("SLA infeasible"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(Files.writeString(
                dir.resolve("incomplete.properties"), valid.replace("102.ab.be.bytes=1200", "102.ab.be.bytes=0"))));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(Files.writeString(
                dir.resolve("legacy.properties"), valid.replace("dba.mode=giant-complete", "dba.mode=giant"))));
    }

    @Test
    void realBwmapKeepsPeriodicFixedWithDelayedReportAndDeliversEachPacketOnce(@TempDir Path dir)
            throws IOException {
        var trace = run(dir, mixedConfiguration());
        var grants = rows(trace, "PON_DBA_BWMAP_TX");
        var fixedGrants = grants.stream().filter(r -> r.get("allocation_id").equals("101")).collect(Collectors.toList());
        var settled = rows(trace, "PON_DBA_SETTLEMENT_RX").stream()
                .collect(Collectors.toMap(r -> r.get("grant_id"), r -> r));
        assertEquals(1200, number(fixedGrants.get(0), "fixed_bytes"));
        assertEquals(0, number(settled.get(fixedGrants.get(0).get("grant_id")), "actual_bytes"));
        assertTrue(number(fixedGrants.get(1), "time_ns")
                < number(settled.get(fixedGrants.get(0).get("grant_id")), "time_ns"));
        for (int n = 1; n < fixedGrants.size(); n++) {
            assertEquals(1, number(fixedGrants.get(n), "target_frame_id") - number(fixedGrants.get(n - 1), "target_frame_id"));
        }
        var delivered = rows(trace, "DELIVERED");
        assertEquals(9, delivered.size());
        assertEquals(9, delivered.stream().map(r -> r.get("packet_id")).distinct().count());
        assertEquals(grants.size(), grants.stream().map(r -> r.get("target_frame_id") + "/" + r.get("allocation_id"))
                .distinct().count());
        for (var grant : grants) {
            var fact = settled.get(grant.get("grant_id"));
            assertEquals(number(grant, "budget_bytes"), number(fact, "actual_bytes") + number(fact, "unused_bytes"));
        }
        assertTrue(rows(trace, "PON_GIANT_DECISION").stream().anyMatch(r -> r.get("allocation_id").equals("102")
                && number(r, "fixed_bytes") == 1200 && number(r, "assured_bytes") == 1200));
    }

    private static TcontProfile legacy(long id, int sfu, int type, long assured, long surplus) {
        return new TcontProfile(id, sfu, type, CLASSES, 1, type == 2 ? 0 : 1, assured, surplus);
    }

    private static TcontProfile fixed(long id, int sfu, int interval, long bytes) {
        return new TcontProfile(id, sfu, 1, CLASSES, 0, 0, 0, 0, interval, bytes, 0, 0);
    }

    private static TcontProfile type5(long id, int sfu, int siFixed, int siMax, int siMin, int siBe) {
        return new TcontProfile(id, sfu, 5, CLASSES, siMax, siMin, 1200, 1200, siFixed, 1200, siBe, 1200);
    }

    private static DbaContext.Allocation allocation(TcontProfile profile, Long known) {
        return new DbaContext.Allocation(profile.allocationId(), profile.sfuId(), known, null, null, false, profile);
    }

    private static DbaContext frame(long id, long duration, long control, List<DbaContext.Allocation> input) {
        return new DbaContext((id - 1) * duration, id, RATE, duration, control, input);
    }

    private static GiantDbaPolicy.Decision decision(GiantDbaPolicy policy, long id) {
        return policy.lastDecisions().stream().filter(d -> d.allocation().allocationId() == id).findFirst().orElseThrow();
    }

    private static String mixedConfiguration() {
        return "traffic.direction=UL\ntraffic.mode=manual\npacket.bytes=1200\n"
                + "packet.arrival.us=0,0,0,0,0,0,0,0,900\n"
                + "packet.1.type=BE\npacket.9.type=VO\n"
                + "wifi.model=fixed\nwifi.delay.us=0\nwifi.mac.access=fixed-priority\n"
                + "fiber.downlink.mode=pon-shared\nfiber.downlink.propagation.us=10\n"
                + "fiber.uplink.mode=pon-framed\nfiber.uplink.propagation.us=320\n"
                + "fiber.uplink.report.enabled=true\nfiber.uplink.bwmap.enabled=true\n"
                + "fiber.uplink.demand.ledger.enabled=true\nfiber.uplink.poll.enabled=true\n"
                + "fiber.uplink.poll.bytes=64\nfiber.uplink.poll.end.us=3500\n"
                + "fiber.uplink.dba.mode=giant-complete\nfiber.uplink.tcont.enabled=true\n"
                + "fiber.uplink.tcont.alloc.ids=101,102\n"
                + profile(101, "type=1", "sfu.id=1", "classes=VO", "si.fixed.frames=1", "ab.fixed.bytes=1200")
                + profile(102, "type=5", "sfu.id=1", "classes=VI,BE,BK", "si.fixed.frames=2", "ab.fixed.bytes=1200",
                        "si.max.frames=1", "ab.min.bytes=1200", "si.min.frames=1", "ab.sur.bytes=1200",
                        "si.be.frames=1", "ab.be.bytes=1200");
    }

    private static String profile(long id, String... keys) {
        return Arrays.stream(keys).map(key -> "fiber.uplink.tcont.alloc." + id + "." + key + "\n")
                .collect(Collectors.joining());
    }

    private static List<Map<String, String>> run(Path dir, String properties) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(Files.writeString(dir.resolve("mixed.properties"), properties)),
                    new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().map(line -> Arrays.stream(line.split(" "))
                .filter(field -> field.contains("=")).map(field -> field.split("=", 2))
                .collect(Collectors.toMap(field -> field[0], field -> field[1]))).collect(Collectors.toList());
    }

    private static List<Map<String, String>> rows(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(r -> event.equals(r.get("event"))).collect(Collectors.toList());
    }

    private static long number(Map<String, String> row, String key) {
        return Long.parseLong(row.get(key));
    }
}
