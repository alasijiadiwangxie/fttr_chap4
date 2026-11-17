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
    void defersTiedDevicesWithoutMergingTheirQueuesOrGrowingTheirWindows(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0,0,0", "traffic.direction=BOTH",
                "sta.count=2", "packet.1.direction=DL", "packet.1.type=BK", "packet.2.direction=UL",
                "packet.2.sta.id=2", "packet.2.type=VO", "packet.3.direction=UL", "packet.3.type=VO",
                "packet.4.direction=UL", "packet.4.type=VO", "packet.5.direction=UL", "packet.5.sta.id=2",
                "packet.5.type=VO", "wifi.mac.edca.BK.aifsn=2", "wifi.mac.edca.BK.cwmin=0", "wifi.mac.edca.VO.cwmin=0");

        String trace = run(config);
        // 同刻先安排 SFU，再按 STA ID。STA 2 的包先入队，仍独立等待自己那份 FIFO。
        assertEquals(List.of("WIFI_TX_START:1@34", "WIFI_ACK_END:1@142", "WIFI_TX_START:3@176",
                "WIFI_ACK_END:3@284", "WIFI_TX_START:4@318", "WIFI_ACK_END:4@426",
                "WIFI_TX_START:2@460", "WIFI_ACK_END:2@568", "WIFI_TX_START:5@602",
                "WIFI_ACK_END:5@710"), exchangeBoundaries(trace));
        assertTrue(collisions(trace).isEmpty());
    }

    @Test
    void resolvesATiedStationsTwoCategoriesEvenWhenItsWinnerDefers(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0,0", "traffic.direction=BOTH",
                "packet.1.direction=UL", "packet.2.direction=UL", "packet.2.type=VO", "packet.3.direction=DL",
                "packet.3.type=BK", "wifi.mac.edca.BE.aifsn=2", "wifi.mac.edca.BK.aifsn=2",
                "wifi.mac.edca.BE.cwmin=0", "wifi.mac.edca.BE.cwmax=0", "wifi.mac.edca.BK.cwmin=0",
                "wifi.mac.edca.VO.cwmin=0");

        String trace = run(config);
        assertEquals(List.of("WIFI_TX_START:3@34", "WIFI_ACK_END:3@142", "WIFI_TX_START:2@176",
                "WIFI_ACK_END:2@284", "WIFI_TX_START:1@318", "WIFI_ACK_END:1@426"), exchangeBoundaries(trace));
        // 两次都是 STA 内部的 BE 输给 VO。SFU 在首轮获准上空口，但不参与 STA 的虚拟碰撞。
        assertEquals(List.of("34:UL:1:1:2", "176:UL:1:1:2"), collisions(trace).stream()
                .map(collision -> String.join(":", collision.get("t_us"), collision.get("direction"),
                        collision.get("sta_id"), collision.get("packet_id"), collision.get("winner_packet_id")))
                .collect(Collectors.toList()));
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
        Pattern event = Pattern.compile("t_us=(\\S+) node=\\S+ event=(WIFI_TX_START|WIFI_ACK_END) packet_id=(\\d+) ");
        return event.matcher(trace).results().map(row -> row.group(2) + ":" + row.group(3) + "@" + row.group(1))
                .collect(Collectors.toList());
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
