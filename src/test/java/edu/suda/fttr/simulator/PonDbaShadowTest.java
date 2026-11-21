package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDbaShadowPlanner;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.link.PonUplinkReport;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonDbaShadowTest {
    @Test
    void plansSixThousandBytesWithoutDebitingReportsOrCreatingRealGrants() {
        try (PrintStream output = quietOutput()) {
            SimulationContext context = context(output);
            Mfu mfu = new Mfu(List.of(1), null, true);
            PonDbaShadowPlanner planner = planner(List.of(1));
            advanceTo(context, 130_000);
            PonUplinkReport report = report(1, 1, 125_000, 6_000);
            mfu.receiveUplinkReport(report, carrier(1, 1, 1_200), context);

            PonDbaShadowPlanner.Decision first = plan(planner, mfu, 130_000, 2, 205);
            assertEquals(1, first.selectedSfuId());
            assertEquals(6_000, first.plannedGrantBytes());
            assertEquals(19_200, first.plannedGrantNs());
            assertEquals("KNOWN_DEMAND", first.decisionReason());
            assertEquals(1L, first.reportFrameId());
            assertEquals(5_000L, first.reportAgeNs());

            // 下一帧可以再次提出同样预算。它仍是观测，账本没有真实预约可扣减。
            PonDbaShadowPlanner.Decision repeated = plan(planner, mfu, 250_000, 3, 205);
            assertEquals(6_000, repeated.plannedGrantBytes());
            PonDemandLedger.Estimate estimate = mfu.estimateUplinkDemand(1, 250_000);
            assertEquals(0, estimate.grantedBytesSinceSnapshot());
            assertEquals(6_000, estimate.effectiveKnownBytes());
            assertEquals(6_000, report.pendingBytes());
        }
    }

    @Test
    void limitsByteBudgetToThirtyEightThousandNineHundredNinetyEightWithControlSlot() {
        try (PrintStream output = quietOutput()) {
            SimulationContext context = context(output);
            Mfu mfu = new Mfu(List.of(1), null, true);
            advanceTo(context, 130_000);
            mfu.receiveUplinkReport(report(1, 1, 125_000, 39_062), carrier(1, 1, 39_062), context);

            PonDbaShadowPlanner.Decision decision = plan(planner(List.of(1)), mfu, 130_000, 2, 205);
            assertEquals(124_795, decision.availableDataNs());
            assertEquals(BigInteger.valueOf(38_998), decision.dataCapacityBytes());
            assertEquals(38_998, decision.plannedGrantBytes());
            assertEquals(124_794, decision.plannedGrantNs());
            assertEquals("FRAME_CAPACITY_LIMIT", decision.decisionReason());
            assertEquals(1, 125_000 - decision.controlReservedNs() - decision.plannedGrantNs());
            assertTrue(decision.plannedGrantBytes() < 39_062);
        }
    }

    @Test
    void rotatesSortedKnownPositiveSfusWhileDistinguishingUnknownAndZero() {
        try (PrintStream output = quietOutput()) {
            SimulationContext context = context(output);
            Mfu mfu = new Mfu(List.of(1, 2, 3, 4), null, true);
            PonDbaShadowPlanner planner = planner(List.of(4, 2, 1, 3, 2));
            PonDbaShadowPlanner.Decision unknown = plan(planner, mfu, 0, 1, 205);
            assertEquals(0, unknown.selectedSfuId());
            assertEquals(0, unknown.plannedGrantBytes());
            assertEquals("ALL_UNKNOWN", unknown.decisionReason());
            assertEquals(4, unknown.unknownSfus());
            assertNull(unknown.reportFrameId());

            advanceTo(context, 130_000);
            mfu.receiveUplinkReport(report(1, 2, 125_000, 0), carrier(1, 2, 1), context);
            PonDbaShadowPlanner.Decision zero = plan(planner, mfu, 130_000, 2, 205);
            assertEquals(0, zero.selectedSfuId());
            assertEquals("NO_KNOWN_POSITIVE_DEMAND", zero.decisionReason());
            assertEquals(3, zero.unknownSfus());
            assertEquals(1, zero.knownZeroSfus());

            mfu.receiveUplinkReport(report(1, 1, 125_000, 1_200), carrier(2, 1, 1_200), context);
            mfu.receiveUplinkReport(report(1, 3, 125_000, 2_400), carrier(3, 3, 1_200), context);
            List<Integer> selected = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                selected.add(plan(planner, mfu, 130_000 + round, 2 + round, 205).selectedSfuId());
            }
            assertEquals(List.of(1, 3, 1, 3), selected);
            assertEquals(1_200, mfu.estimateUplinkDemand(1, 140_000).effectiveKnownBytes());
            assertFalse(mfu.estimateUplinkDemand(4, 140_000).known());
        }
    }

    @Test
    void waitsForReportReceptionAndUsesTheNetDemandAfterActualGrantHistory() {
        try (PrintStream output = quietOutput()) {
            SimulationContext context = context(output);
            Mfu mfu = new Mfu(List.of(1), null, true);
            PonDbaShadowPlanner planner = planner(List.of(1));
            Packet reserved = carrier(1, 1, 2_400);
            advanceTo(context, 125_000);
            mfu.recordUplinkGrantCreated(1, reserved, context);
            mfu.recordUplinkGrantCreated(1, reserved, context);
            assertEquals(0, plan(planner, mfu, 150_000, 2, 205).plannedGrantBytes());

            advanceTo(context, 200_000);
            PonUplinkReport snapshot = report(1, 1, 125_000, 6_000);
            mfu.receiveUplinkReport(snapshot, reserved, context);
            PonDbaShadowPlanner.Decision known = plan(planner, mfu, 200_000, 3, 205);
            assertEquals(3_600, known.effectiveKnownBytes());
            assertEquals(3_600, known.plannedGrantBytes());
            assertEquals(2_400, mfu.estimateUplinkDemand(1, 200_000).grantedBytesSinceSnapshot());
            // 规划器没有新 REPORT 输入，稍后的查询仍只能使用原有已知需求。
            assertEquals(3_600, plan(planner, mfu, 210_000, 4, 205).plannedGrantBytes());
            assertEquals(6_000, snapshot.pendingBytes());
        }
    }

    @Test
    void zeroAvailableCapacityDoesNotAdvanceThePositiveDemandRoundRobin() {
        try (PrintStream output = quietOutput()) {
            SimulationContext context = context(output);
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            advanceTo(context, 130_000);
            mfu.receiveUplinkReport(report(1, 1, 125_000, 1_200), carrier(1, 1, 1_200), context);
            mfu.receiveUplinkReport(report(1, 2, 125_000, 1_200), carrier(2, 2, 1_200), context);
            PonDbaShadowPlanner planner = planner(List.of(1, 2));

            PonDbaShadowPlanner.Decision full = plan(planner, mfu, 130_000, 2, 125_000);
            assertEquals("NO_DATA_CAPACITY", full.decisionReason());
            assertEquals(0, full.plannedGrantBytes());
            assertEquals(0, full.plannedGrantNs());
            assertEquals(1, plan(planner, mfu, 250_000, 3, 205).selectedSfuId());
        }
    }

    @Test
    void byteBudgetDoesNotExecuteTheLargeWholePacketOrChangeTheExistingPollingTrace(@TempDir Path directory)
            throws IOException {
        List<String> traffic = List.of("traffic.mode=manual", "wifi.delay.us=0",
                "packet.arrival.us=0", "packet.bytes=39062");
        List<String> enabled = run(directory, "shadow-rr", traffic);
        List<String> baseline = run(directory, "fcfs", traffic);
        assertEquals(baseline, withoutShadow(enabled));
        assertEquals(baseline, run(directory, null, traffic));

        List<Map<String, String>> decisions = events(enabled, "PON_DBA_SHADOW");
        Map<String, String> unknown = decisionAt(decisions, 125_000);
        assertEquals("0", unknown.get("planned_grant_bytes"));
        Map<String, String> known = decisionAt(decisions, 375_000);
        assertEquals("1", known.get("selected_sfu_id"));
        assertEquals("39062", known.get("effective_known_bytes"));
        assertEquals("38998", known.get("planned_grant_bytes"));
        assertEquals("124794", known.get("planned_grant_ns"));
        assertEquals("205", known.get("control_reserved_ns"));
        assertEquals("FRAME_CAPACITY_LIMIT", known.get("decision_reason"));
        assertEquals(List.of("750"), values(events(enabled, "FIBER_TX_START"), "t_us"));
        assertEquals(1, events(enabled, "PON_UPLINK_GRANT").size());
        assertEquals(1, events(enabled, "DELIVERED").size());
        assertEquals(List.of("39062"), values(events(enabled, "PON_DEMAND_LEDGER_GRANT"), "grant_bytes"));
    }

    @Test
    void seededMixedEdcaTrafficReplaysExactlyWithAndWithoutShadowOutput(@TempDir Path directory) throws IOException {
        for (int seed : List.of(11, 29, 47)) {
            List<String> traffic = List.of("traffic.mode=poisson", "traffic.direction=BOTH",
                    "traffic.duration.us=1000", "wifi.model=he-su", "wifi.mac.access=edca-multi-ac",
                    "wifi.mac.exchange=data-sifs-ack", "wifi.mac.edca.seed=" + seed,
                    "traffic.sfu.1.rate.pps=5000", "traffic.sfu.1.packet.bytes=1200",
                    "traffic.sfu.1.seed=" + seed, "traffic.sfu.1.class.weights=1,1,1,1",
                    "traffic.sta.1.rate.pps=8000", "traffic.sta.1.packet.bytes=1200",
                    "traffic.sta.1.seed=" + (seed + 100), "traffic.sta.1.class.weights=1,1,1,1",
                    "traffic.sta.2.rate.pps=8000", "traffic.sta.2.packet.bytes=1200",
                    "traffic.sta.2.seed=" + (seed + 200), "traffic.sta.2.class.weights=1,1,1,1");
            List<String> enabled = run(directory, "shadow-rr", traffic);
            assertEquals(enabled, run(directory, "shadow-rr", traffic), "Repeated seed " + seed);
            assertEquals(run(directory, "fcfs", traffic), withoutShadow(enabled), "On/off seed " + seed);
            assertTrue(events(enabled, "PON_DBA_SHADOW").size() >= 4);
        }
    }

    @Test
    void rejectsShadowWithoutItsRequiredVisibilityAndControlModules(@TempDir Path directory) {
        for (String missing : List.of("fiber.uplink.report.enabled=false", "fiber.uplink.bwmap.enabled=false",
                "fiber.uplink.demand.ledger.enabled=false", "fiber.uplink.poll.enabled=false")) {
            List<String> input = new ArrayList<>(settings("shadow-rr", List.of(
                    "traffic.mode=poisson", "traffic.duration.us=1")));
            if (missing.startsWith("fiber.uplink.poll.enabled=")) {
                input.remove("fiber.uplink.poll.end.us=625");
            }
            input.add(missing);
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(write(directory, input)), missing);
        }
        assertThrows(IllegalArgumentException.class, () -> run(directory, "giant", List.of(
                "traffic.mode=poisson", "traffic.duration.us=1")));
    }

    private static PonDbaShadowPlanner planner(List<Integer> sfus) {
        return new PonDbaShadowPlanner(sfus, BigDecimal.valueOf(2500), 125_000);
    }

    private static PonDbaShadowPlanner.Decision plan(PonDbaShadowPlanner planner, Mfu mfu, long timeNs,
                                                    long targetFrame, long controlNs) {
        return planner.plan(timeNs, targetFrame, controlNs, sfu -> mfu.estimateUplinkDemand(sfu, timeNs));
    }

    private static PrintStream quietOutput() {
        return new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8);
    }

    private static SimulationContext context(PrintStream output) {
        return new SimulationContext(new TraceLogger(output), true, true);
    }

    private static void advanceTo(SimulationContext context, long timeNs) {
        context.scheduleAfterNanoseconds(timeNs - context.nowTicks(), EventType.PON_UPLINK_FRAME, null, 0);
        context.nextEvent();
    }

    private static Packet carrier(int id, int sfu, int bytes) {
        return new Packet(id, bytes, 0, AccessCategory.BE, Packet.Direction.UL, sfu, Packet.DEFAULT_MFU_ID);
    }

    private static PonUplinkReport report(long frame, int sfu, long snapshotNs, long bytes) {
        return new PonUplinkReport(frame, sfu, snapshotNs, bytes == 0 ? 0 : 1, bytes,
                bytes == 0 ? 0 : 1, bytes);
    }

    private static List<String> settings(String mode, List<String> traffic) {
        List<String> settings = new ArrayList<>(List.of("traffic.direction=UL", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "fiber.uplink.mode=pon-framed", "fiber.uplink.report.enabled=true",
                "fiber.uplink.bwmap.enabled=true", "fiber.uplink.demand.ledger.enabled=true",
                "fiber.uplink.poll.enabled=true", "fiber.uplink.poll.end.us=625",
                "fiber.uplink.propagation.us=10.025", "fiber.downlink.mode=pon-shared",
                "fiber.downlink.propagation.us=10"));
        if (mode != null) {
            settings.add("fiber.uplink.dba.mode=" + mode);
        }
        settings.addAll(traffic);
        return settings;
    }

    private static Path write(Path directory, List<String> settings) throws IOException {
        return Files.write(directory.resolve("dba-shadow.properties"), settings, StandardCharsets.UTF_8);
    }

    private static List<String> run(Path directory, String mode, List<String> traffic) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(write(directory, settings(mode, traffic))), new TraceLogger(output));
        }
        return bytes.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<String> withoutShadow(List<String> trace) {
        return trace.stream().filter(line -> !line.contains("event=PON_DBA_SHADOW ")
                && !line.contains("event=PON_DBA_POLICY ")).collect(Collectors.toList());
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String token : line.split(" ")) {
            String[] pair = token.split("=", 2);
            result.put(pair[0], pair[1]);
        }
        return result;
    }

    private static List<Map<String, String>> events(List<String> trace, String event) {
        return trace.stream().map(PonDbaShadowTest::fields).filter(row -> event.equals(row.get("event")))
                .collect(Collectors.toList());
    }

    private static Map<String, String> decisionAt(List<Map<String, String>> decisions, long timeNs) {
        return decisions.stream().filter(row -> Long.toString(timeNs).equals(row.get("decision_time_ns")))
                .findFirst().orElseThrow();
    }

    private static List<String> values(List<Map<String, String>> rows, String key) {
        return rows.stream().map(row -> row.get(key)).collect(Collectors.toList());
    }
}
