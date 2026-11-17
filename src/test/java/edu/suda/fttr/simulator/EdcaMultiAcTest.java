package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaState;
import edu.suda.fttr.mac.EdcaState.AcState;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdcaMultiAcTest {
    @Test
    void fourCategoriesCompeteByTheirAifsBeforeTheirPriority(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0,0", "packet.1.type=VO",
                "packet.2.type=VI", "packet.3.type=BE", "packet.4.type=BK",
                "wifi.mac.edca.VO.aifsn=9", "wifi.mac.edca.BK.aifsn=2",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VI.cwmin=0",
                "wifi.mac.edca.BE.cwmin=0", "wifi.mac.edca.BK.cwmin=0");

        String trace = run(config);
        // 四个 AC 同时排队。各次交换占用 108 μs，空闲后分别重等自己的 AIFS。
        assertEquals(List.of("WIFI_TX_START:4@34", "WIFI_ACK_END:4@142", "WIFI_TX_START:2@185",
                "WIFI_ACK_END:2@293", "WIFI_TX_START:3@363", "WIFI_ACK_END:3@471",
                "WIFI_TX_START:1@568", "WIFI_ACK_END:1@676"), exchangeBoundaries(trace));
        assertTrue(collisions(trace).isEmpty());
    }

    @Test
    void resolvesAllFourTiedCategoriesAndRedrawsOnlyTheLosers(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0,0", "packet.1.type=BK",
                "packet.2.type=BE", "packet.3.type=VI", "packet.4.type=VO", "wifi.mac.edca.seed=17",
                "wifi.mac.edca.VI.aifsn=2", "wifi.mac.edca.BE.aifsn=2", "wifi.mac.edca.BK.aifsn=2",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VI.cwmin=0", "wifi.mac.edca.BE.cwmin=0",
                "wifi.mac.edca.BK.cwmin=0", "wifi.mac.edca.BK.cwmax=0");

        String trace = run(config);
        assertEquals(trace, run(config));
        List<Map<String, String>> firstCollisions = collisions(trace).stream()
                .filter(entry -> "34".equals(entry.get("t_us"))).collect(Collectors.toList());
        assertEquals(3, firstCollisions.size());
        EdcaState reference = EdcaState.forSfu(1, config.edcaParameters());
        for (Map<String, String> collision : firstCollisions) {
            // 每个失败 AC 在预期 CW 上消费各自的第二次抽取。BK 已达其配置上限。
            AccessCategory type = AccessCategory.valueOf(collision.get("type"));
            AcState state = reference.forCategory(type);
            assertEquals(0, state.drawBackoff());
            state.onContentionFailure();
            assertEquals("0", collision.get("cw_before"));
            assertEquals(type == AccessCategory.BK ? "0" : "1", collision.get("cw_after"));
            assertEquals(Integer.toString(state.drawBackoff()), collision.get("backoff"));
            assertEquals("4", collision.get("winner_packet_id"));
        }
        assertEquals(List.of("1", "2", "3"), firstCollisions.stream().map(entry -> entry.get("packet_id"))
                .sorted().collect(Collectors.toList()));
        assertEquals(List.of("WIFI_TX_START:4@34", "WIFI_ACK_END:4@142"), exchangeBoundaries(trace).subList(0, 2));
        assertEquals(List.of(1, 2, 3, 4), sentPacketIds(trace));
    }

    @Test
    void resumesTheRemainingSlotsWhenAnotherCategoryWinsMidSlot(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,4", "packet.1.type=BE", "packet.2.type=VO",
                "wifi.mac.edca.seed=2", "wifi.mac.edca.BE.aifsn=2", "wifi.mac.edca.VO.aifsn=3",
                "wifi.mac.edca.VO.cwmin=0");
        EdcaParameters parameters = config.edcaParameters();
        int beInitial = EdcaState.forSfu(1, parameters).forCategory(AccessCategory.BE).drawBackoff();
        assertTrue(beInitial >= 2);

        String trace = run(config);
        // BE 在 43 μs 减掉第一格。VO 在 47 μs 占用信道，BE 在 52 μs 的事件被取消。
        long resumed = 155 + parameters.aifsUs(AccessCategory.BE) + (beInitial - 1) * parameters.slotTimeUs();
        assertEquals(List.of("WIFI_TX_START:2@47", "WIFI_ACK_END:2@155",
                "WIFI_TX_START:1@" + resumed, "WIFI_ACK_END:1@" + (resumed + 108)), exchangeBoundaries(trace));
        assertTrue(collisions(trace).isEmpty());
    }

    @Test
    void collidesAllTiedDevicesAndThenArmsEachSeparateQueueHead(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0,0,0", "traffic.direction=BOTH",
                "sta.count=2", "packet.1.direction=DL", "packet.1.type=BK", "packet.2.direction=UL",
                "packet.2.sta.id=2", "packet.2.type=VO", "packet.3.direction=UL", "packet.3.type=VO",
                "packet.4.direction=UL", "packet.4.type=VO", "packet.5.direction=UL", "packet.5.sta.id=2",
                "packet.5.type=VO", "wifi.mac.edca.BK.aifsn=2", "wifi.mac.edca.BK.cwmin=0", "wifi.mac.edca.VO.cwmin=0",
                "wifi.mac.ack.timeout.us=20", "wifi.mac.retry.max=0");

        String trace = run(config);
        // 首轮三台设备同时发送。超时后，第二轮只有两台 STA 的下一条 VO 队首参与。
        assertTrue(exchangeBoundaries(trace).isEmpty());
        List<String> starts = packetBoundaries(trace, "WIFI_COLLISION_START");
        assertEquals(5, starts.size());
        assertEquals(Set.of("WIFI_COLLISION_START:1@34", "WIFI_COLLISION_START:2@34", "WIFI_COLLISION_START:3@34",
                "WIFI_COLLISION_START:4@152", "WIFI_COLLISION_START:5@152"), Set.copyOf(starts));
        assertEquals(List.of("98", "216"), collisionReleaseTimes(trace));
        assertTrue(collisions(trace).isEmpty());
        assertTrue(trace.contains("summary=WIFI_COLLISION collision_events=2 dropped_packets=5 dropped_bytes=7500"));
        assertTrue(trace.contains("delivered_packets=0 delivered_bytes=0 wifi_busy_us=128 wifi_idle_us=108 simulation_end_us=236"));
    }

    @Test
    void resolvesVirtualCollisionBeforeTheWinningAcCollidesWithAnotherDevice(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0", "traffic.direction=BOTH",
                "packet.1.direction=UL", "packet.2.direction=UL", "packet.2.type=VO", "packet.3.direction=DL",
                "packet.3.type=BK", "wifi.mac.edca.BE.aifsn=2", "wifi.mac.edca.BK.aifsn=2",
                "wifi.mac.edca.BE.cwmin=0", "wifi.mac.edca.BE.cwmax=0", "wifi.mac.edca.BK.cwmin=0",
                "wifi.mac.edca.VO.cwmin=0", "wifi.mac.ack.timeout.us=20", "wifi.mac.retry.max=0");

        String trace = run(config);
        assertEquals(List.of("WIFI_TX_START:1@152", "WIFI_ACK_END:1@260"), exchangeBoundaries(trace));
        // STA 的 BE 因虚拟碰撞留下。STA 的 VO 与 SFU 的 BK 同时发送，超时后丢弃。
        assertEquals(List.of("34:UL:1:1:2"), collisions(trace).stream()
                .map(collision -> String.join(":", collision.get("t_us"), collision.get("direction"),
                        collision.get("sta_id"), collision.get("packet_id"), collision.get("winner_packet_id")))
                .collect(Collectors.toList()));
        List<String> drops = packetBoundaries(trace, "WIFI_RETRY_LIMIT_DROP");
        assertEquals(2, drops.size());
        assertEquals(Set.of("WIFI_RETRY_LIMIT_DROP:2@118", "WIFI_RETRY_LIMIT_DROP:3@118"), Set.copyOf(drops));
        assertEquals(List.of("DELIVERED:1@216"), packetBoundaries(trace, "DELIVERED"));
    }

    @Test
    void keepsTwoStaCollisionBusyUntilTheLongestDataEnds(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,9,80", "traffic.direction=UL", "sta.count=2",
                "wifi.phy.bandwidth.mhz=80", "wifi.phy.nss=1", "wifi.mac.edca.seed=2", "packet.1.type=VO",
                "packet.1.bytes=215", "packet.2.type=VO", "packet.2.sta.id=2", "packet.3.type=VO", "packet.3.bytes=215",
                "wifi.mac.ack.timeout.us=5", "wifi.mac.retry.max=0");

        String trace = run(config);
        assertEquals(trace, run(config));
        // 两台 STA 分别抽取 3 和 2 格，结合各自到达时刻在 61 μs 同时发送。
        List<String> starts = packetBoundaries(trace, "WIFI_COLLISION_START");
        assertEquals(2, starts.size());
        assertEquals(Set.of("WIFI_COLLISION_START:1@61", "WIFI_COLLISION_START:2@61"), Set.copyOf(starts));
        assertEquals(List.of("WIFI_COLLISION_DATA_END:1@117.8", "WIFI_COLLISION_DATA_END:2@131.4"),
                packetBoundaries(trace, "WIFI_COLLISION_DATA_END"));
        assertEquals(List.of("WIFI_RETRY_LIMIT_DROP:1@122.8", "WIFI_RETRY_LIMIT_DROP:2@136.4"),
                packetBoundaries(trace, "WIFI_RETRY_LIMIT_DROP"));
        assertEquals(List.of("131.4"), collisionReleaseTimes(trace));
        // 短帧先超时。STA 1 的下一帧从 CWmin 抽到 2 格，在长帧结束后等待 34 + 2 × 9 μs。
        assertEquals(List.of("WIFI_TX_START:3@183.4", "WIFI_ACK_END:3@284.2"), exchangeBoundaries(trace));
        assertEquals(List.of("FIBER_TX_START:3@240.2", "DELIVERED:3@240.2", "WIFI_ACK_START:3@256.2"),
                packetBoundaries(trace, "DELIVERED|FIBER_TX_START|WIFI_ACK_START"));
        assertTrue(collisions(trace).isEmpty());
        assertTrue(trace.contains("summary=WIFI_COLLISION collision_events=1 dropped_packets=2 dropped_bytes=1715"));
        assertTrue(trace.contains("delivered_packets=1 delivered_bytes=215 wifi_busy_us=171.2 wifi_idle_us=113 simulation_end_us=284.2"));
    }

    @Test
    void sfuAndStaCollisionDoesNotInterruptAnotherSfusExchange(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0", "traffic.direction=BOTH", "sfu.count=2",
                "sta.count=2", "sta.2.sfu.id=2", "packet.1.direction=DL", "packet.1.type=VO",
                "packet.2.direction=UL", "packet.2.type=VO", "packet.3.direction=DL", "packet.3.type=VO",
                "packet.3.sta.id=2", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.ack.timeout.us=20", "wifi.mac.retry.max=0");

        String trace = run(config);
        assertEquals(List.of("WIFI_TX_START:3@34", "WIFI_ACK_END:3@142"), exchangeBoundaries(trace));
        List<String> drops = packetBoundaries(trace, "WIFI_RETRY_LIMIT_DROP");
        assertEquals(2, drops.size());
        assertEquals(Set.of("WIFI_RETRY_LIMIT_DROP:1@118", "WIFI_RETRY_LIMIT_DROP:2@118"), Set.copyOf(drops));
        assertEquals(List.of("98"), collisionReleaseTimes(trace));
        assertEquals(List.of("DELIVERED:3@98", "WIFI_ACK_START:3@114"), packetBoundaries(trace, "DELIVERED|WIFI_ACK_START"));
        assertTrue(trace.contains("summary=WIFI_COLLISION collision_events=1 dropped_packets=2 dropped_bytes=3000"));
        assertTrue(trace.contains("delivered_packets=1 delivered_bytes=1500 wifi_busy_us=108 wifi_idle_us=34 simulation_end_us=142"));
    }

    @Test
    void independentSfusCanEachResolveATieAndSendAtTheSameTime(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0,0", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "packet.1.type=VO", "packet.2.type=VI",
                "packet.3.type=VO", "packet.3.sta.id=2", "packet.4.type=VI", "packet.4.sta.id=2",
                "wifi.mac.edca.VI.aifsn=2", "wifi.mac.edca.VI.cwmin=0", "wifi.mac.edca.VI.cwmax=0",
                "wifi.mac.edca.VO.cwmin=0");

        String trace = run(config);
        List<String> boundaries = exchangeBoundaries(trace);
        assertEquals(List.of("WIFI_TX_START:1@34", "WIFI_TX_START:3@34",
                "WIFI_ACK_END:1@142", "WIFI_ACK_END:3@142", "WIFI_TX_START:2@176",
                "WIFI_TX_START:4@176", "WIFI_ACK_END:2@284", "WIFI_ACK_END:4@284"), boundaries);
        assertEquals(List.of("2:1", "4:3"), collisions(trace).stream()
                .map(collision -> collision.get("packet_id") + ":" + collision.get("winner_packet_id"))
                .sorted().collect(Collectors.toList()));
    }

    private static SimulationConfig load(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=0", "packet.bytes=1500",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-multi-ac"));
        properties.addAll(List.of(settings));
        return ConfigLoader.load(Files.write(directory.resolve("multi-ac.properties"), properties, StandardCharsets.UTF_8));
    }

    private static List<String> exchangeBoundaries(String trace) {
        return packetBoundaries(trace, "WIFI_TX_START|WIFI_ACK_END");
    }

    private static List<String> packetBoundaries(String trace, String actions) {
        Pattern event = Pattern.compile("t_us=(\\S+) node=\\S+ event=(" + actions + ") packet_id=(\\d+) ");
        return event.matcher(trace).results().map(row -> row.group(2) + ":" + row.group(3) + "@" + row.group(1))
                .collect(Collectors.toList());
    }

    private static List<String> collisionReleaseTimes(String trace) {
        Pattern event = Pattern.compile("t_us=(\\S+) node=\\S+ event=WIFI_COLLISION_END ");
        return event.matcher(trace).results().map(row -> row.group(1)).collect(Collectors.toList());
    }

    private static List<Integer> sentPacketIds(String trace) {
        Pattern event = Pattern.compile("event=WIFI_TX_START packet_id=(\\d+) ");
        return event.matcher(trace).results().map(row -> Integer.parseInt(row.group(1)))
                .sorted().collect(Collectors.toList());
    }

    private static List<Map<String, String>> collisions(String trace) {
        return trace.lines().filter(row -> row.contains("event=EDCA_VIRTUAL_COLLISION "))
                .map(row -> Arrays.stream(row.split(" ")).map(field -> field.split("=", 2))
                        .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1])))
                .collect(Collectors.toList());
    }

    private static String run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
