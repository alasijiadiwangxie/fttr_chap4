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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonUplinkReportTest {
    @Test
    void reportsZeroOneAndThreePacketsWithActualLengthsBeforeGrant(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "true", List.of("wifi.delay.us=0", "packet.arrival.us=0,130,130,132",
                "packet.bytes=1100", "packet.2.bytes=800", "packet.3.bytes=950", "packet.4.bytes=1201"));

        String empty = report(0, 1, 0, 0, 0, 0, 0);
        String one = report(1, 1, 125_000, 1, 1_100, 1, 1_100);
        String three = report(2, 1, 250_000, 3, 2_951, 3, 2_951);
        assertEquals(List.of(empty, one, three), reports(trace));
        // 核对完整仿真日志。已发完的第 1 包未进入下一帧快照，旧记录仍保留授权前的原始值。
        assertTrue(trace.indexOf(one) < firstMatchingLine(trace, "event=PON_UPLINK_GRANT frame_id=1"));
        assertTrue(trace.indexOf(three) < firstMatchingLine(trace, "event=PON_UPLINK_GRANT frame_id=2"));
    }

    @Test
    void keepsEachSfuSnapshotIndependentIncludingAnIdleSfu(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "true", List.of("sfu.count=3", "sta.count=2", "sta.2.sfu.id=2",
                "wifi.delay.us=0", "packet.arrival.us=0,0,10", "packet.bytes=1400",
                "packet.2.sta.id=2", "packet.2.bytes=1450", "packet.3.bytes=600"));

        String first = report(1, 1, 125_000, 2, 2_000, 2, 2_000);
        String second = report(1, 2, 125_000, 1, 1_450, 1, 1_450);
        String idle = report(1, 3, 125_000, 0, 0, 0, 0);
        assertEquals(List.of(report(0, 1, 0, 0, 0, 0, 0), report(0, 2, 0, 0, 0, 0, 0),
                report(0, 3, 0, 0, 0, 0, 0), first, second, idle), reports(trace));
        int firstGrant = firstMatchingLine(trace, "event=PON_UPLINK_GRANT frame_id=1");
        assertTrue(trace.indexOf(first) < firstGrant);
        assertTrue(trace.indexOf(second) < firstGrant);
        assertTrue(trace.indexOf(idle) < firstGrant);
    }

    @Test
    void excludesSameTickOpticalEnqueueFromEligibleEvenWhenAlreadyPending(@TempDir Path directory)
            throws IOException {
        List<String> trace = run(directory, "true", List.of("wifi.delay.us=100", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "packet.arrival.us=0,25", "packet.bytes=1200",
                "packet.2.sta.id=2", "packet.2.bytes=900"));

        // 包 2 在 125 μs 先完成无线接收并入队，再处理该边界的帧事件。
        String boundary = report(1, 2, 125_000, 1, 900, 0, 0);
        assertEquals(List.of(report(0, 1, 0, 0, 0, 0, 0), report(0, 2, 0, 0, 0, 0, 0),
                report(1, 1, 125_000, 1, 1_200, 1, 1_200), boundary,
                report(2, 1, 250_000, 0, 0, 0, 0), report(2, 2, 250_000, 1, 900, 1, 900)), reports(trace));
        assertTrue(firstMatchingLine(trace, "t_us=125 node=SFU event=PON_UPLINK_ENQUEUE packet_id=2")
                < trace.indexOf(boundary));
        assertEquals(1, matchingLines(trace, "event=PON_UPLINK_GRANT frame_id=1").size());
        assertEquals(1, matchingLines(trace, "event=PON_UPLINK_GRANT frame_id=2 packet_id=2").size());
    }

    @Test
    void preservesCarryOverDemandAndKeepsTheThirtyFivePacketTraceIdentical(@TempDir Path directory)
            throws IOException {
        List<String> properties = new ArrayList<>(List.of("wifi.delay.us=0", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "fiber.uplink.rate.mbps=2500", "fiber.uplink.propagation.us=10.025",
                "fiber.uplink.frame.period.us=125", "packet.bytes=1200",
                "packet.arrival.us=" + String.join(",", Collections.nCopies(33, "0")) + ",250,260"));
        for (int packetId = 1; packetId <= 35; packetId += 2) {
            properties.add("packet." + packetId + ".sta.id=2");
        }

        List<String> enabled = run(directory, "true", properties);
        List<String> disabled = run(directory, "false", properties);
        assertEquals(disabled, run(directory, null, properties));
        assertEquals(List.of(), reports(disabled));
        assertEquals(disabled, enabled.stream().filter(line -> !line.contains("event=PON_UPLINK_REPORT "))
                .collect(Collectors.toList()));

        // 包 33 在前两轮报告中仍等待授权，但只发生一次真实光侧入队。包 34 在帧 2 的授权之后才入光队列。
        assertEquals(List.of(report(0, 1, 0, 0, 0, 0, 0), report(0, 2, 0, 0, 0, 0, 0),
                report(1, 1, 125_000, 16, 19_200, 16, 19_200), report(1, 2, 125_000, 17, 20_400, 17, 20_400),
                report(2, 1, 250_000, 0, 0, 0, 0), report(2, 2, 250_000, 1, 1_200, 1, 1_200),
                report(3, 1, 375_000, 1, 1_200, 1, 1_200), report(3, 2, 375_000, 1, 1_200, 1, 1_200)), reports(enabled));
        assertEquals(35, matchingLines(enabled, "event=PON_UPLINK_ENQUEUE ").size());
        assertEquals(35, matchingLines(enabled, "node=MFU event=DELIVERED ").size());
        assertEquals(1, matchingLines(enabled, "event=PON_UPLINK_ENQUEUE packet_id=33 ").size());
        assertEquals(1, matchingLines(enabled, "event=PON_UPLINK_GRANT frame_id=2 packet_id=33 ").size());
        assertTrue(firstMatchingLine(enabled, "event=PON_UPLINK_GRANT frame_id=2 packet_id=33 ")
                < firstMatchingLine(enabled, "event=PON_UPLINK_ENQUEUE packet_id=34 "));
    }

    @Test
    void reconstructsSkippedIdleFramesWithoutMovingTheSimulationClock(@TempDir Path directory) throws IOException {
        List<String> properties = List.of("wifi.delay.us=0", "packet.arrival.us=375", "packet.bytes=1200");
        List<String> trace = run(directory, "true", properties);

        assertEquals(List.of(report(0, 1, 0, 0, 0, 0, 0), report(1, 1, 125_000, 0, 0, 0, 0),
                report(2, 1, 250_000, 0, 0, 0, 0), report(3, 1, 375_000, 0, 0, 0, 0),
                report(4, 1, 500_000, 1, 1_200, 1, 1_200)), reports(trace));
        String summary = trace.get(trace.size() - 1);
        assertTrue(summary.contains("simulation_end_us=503.84"), summary);
        assertEquals(run(directory, "false", properties).get(trace.size() - reports(trace).size() - 1), summary);
    }

    @Test
    void noTrafficDoesNotCreateFrameOrReportEvents(@TempDir Path directory) throws IOException {
        List<String> trace = run(directory, "true", List.of("wifi.delay.us=0", "traffic.mode=poisson",
                "traffic.duration.us=1000", "traffic.sta.1.rate.pps=0"));

        assertEquals(List.of(), reports(trace));
        assertEquals(List.of(), matchingLines(trace, "summary=PON_UPLINK_FRAME "));
        assertEquals(1, matchingLines(trace, "summary=PON_UPLINK_FRAMES frames=0 ").size());
        String summary = trace.get(trace.size() - 1);
        assertTrue(summary.endsWith("simulation_end_us=0"), summary);
    }

    private static String report(long frameId, int sfuId, long timeNs, int pendingPackets, long pendingBytes,
                                 int eligiblePackets, long eligibleBytes) {
        return "node=SFU event=PON_UPLINK_REPORT frame_id=" + frameId + " sfu_id=" + sfuId
                + " snapshot_time_ns=" + timeNs + " pending_packets=" + pendingPackets + " pending_bytes=" + pendingBytes
                + " eligible_packets=" + eligiblePackets + " eligible_bytes=" + eligibleBytes;
    }

    private static List<String> run(Path directory, String reportEnabled, List<String> properties) throws IOException {
        List<String> scenario = new ArrayList<>(List.of("traffic.direction=UL", "fiber.uplink.mode=pon-framed"));
        if (reportEnabled != null) {
            scenario.add("fiber.uplink.report.enabled=" + reportEnabled);
        }
        scenario.addAll(properties);
        Path path = Files.write(directory.resolve("pon-uplink-report.properties"), scenario, StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(ConfigLoader.load(path), new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

    private static List<String> reports(List<String> trace) {
        return matchingLines(trace, "event=PON_UPLINK_REPORT ");
    }

    private static List<String> matchingLines(List<String> trace, String fragment) {
        return trace.stream().filter(line -> line.contains(fragment)).collect(Collectors.toList());
    }

    private static int firstMatchingLine(List<String> trace, String fragment) {
        List<String> matching = matchingLines(trace, fragment);
        assertTrue(!matching.isEmpty(), "Missing trace line containing " + fragment);
        return trace.indexOf(matching.get(0));
    }
}
