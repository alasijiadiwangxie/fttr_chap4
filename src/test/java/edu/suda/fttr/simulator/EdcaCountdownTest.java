package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.mac.EdcaAccess;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaState;
import edu.suda.fttr.mac.EdcaState.AcState;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdcaCountdownTest {
    @Test
    void zeroBackoffStillWaitsTheFullAifs(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0", "packet.1.type=VO", "wifi.mac.edca.VO.cwmin=0");

        // 默认 VO 的 AIFS 为 34 μs。DATA、SIFS 和 ACK 合计仍为 108 μs。
        assertEquals(List.of("WIFI_TX_START:1@34", "WIFI_ACK_END:1@142"), exchangeBoundaries(run(config)));
    }

    @Test
    void seededUplinkAndDownlinkWaitTheirOwnSlotsWithoutPreemptingTheSelectedPacket(@TempDir Path directory)
            throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,1", "traffic.direction=BOTH", "sta.count=2",
                "packet.1.direction=DL", "packet.2.direction=UL", "packet.2.type=VO", "packet.2.sta.id=2",
                "wifi.mac.slot.us=11", "wifi.mac.edca.seed=0");
        EdcaParameters parameters = config.edcaParameters();
        int downlinkSlots = EdcaState.forSfu(1, parameters).forCategory(AccessCategory.BE).drawBackoff();
        int uplinkSlots = EdcaState.forSta(2, parameters).forCategory(AccessCategory.VO).drawBackoff();
        assertTrue(downlinkSlots > 0);
        long downlinkStart = parameters.aifsUs(AccessCategory.BE) + downlinkSlots * parameters.slotTimeUs();
        long downlinkAck = downlinkStart + 108;
        long uplinkStart = downlinkAck + parameters.aifsUs(AccessCategory.VO) + uplinkSlots * parameters.slotTimeUs();

        String trace = run(config);
        assertEquals(trace, run(config));
        // 迟到的 VO 在 BE 等待 AIFS 时入队，须等该次交换完成再接入。
        assertEquals(List.of("WIFI_TX_START:1@" + downlinkStart, "WIFI_ACK_END:1@" + downlinkAck,
                "WIFI_TX_START:2@" + uplinkStart, "WIFI_ACK_END:2@" + (uplinkStart + 108)), exchangeBoundaries(trace));
    }

    @Test
    void cancelsInterruptedTimersAndResumesTheExistingCounterAfterAFullAifs(@TempDir Path directory)
            throws IOException {
        EdcaParameters parameters = load(directory, "packet.arrival.us=0", "wifi.mac.edca.seed=2").edcaParameters();
        Packet packet = new Packet(1, 1500, 0);
        AcState state = EdcaState.forSfu(1, parameters).forCategory(packet.type());
        AcState reference = EdcaState.forSfu(1, parameters).forCategory(packet.type());
        int firstDraw = state.drawBackoff();
        assertEquals(firstDraw, reference.drawBackoff());
        assertTrue(firstDraw >= 2);
        WifiChannel channel = new WifiChannel();
        EdcaAccess access = new EdcaAccess(1, channel, parameters);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, false);
            access.start(packet, state, context);
            assertEquals(firstDraw, state.backoffCounter().orElseThrow());

            // 在初次 AIFS 中打断。到 100 μs 恢复后重新等待完整的 70 μs。
            moveTo(context, packet, 20, 20);
            channel.occupy();
            moveTo(context, packet, 80, 100);
            assertEquals(firstDraw, state.backoffCounter().orElseThrow());
            channel.release();
            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 170));
            assertEquals(firstDraw, state.backoffCounter().orElseThrow());
            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 179));
            assertEquals(firstDraw - 1, state.backoffCounter().orElseThrow());

            // 同刻的 BUSY 边界先于已入队的第二个时隙事件，该时隙不递减。
            moveTo(context, packet, 9, 188);
            channel.occupy();
            moveTo(context, packet, 62, 250);
            assertEquals(firstDraw - 1, state.backoffCounter().orElseThrow());
            channel.release();
            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 320));
            assertEquals(firstDraw - 1, state.backoffCounter().orElseThrow());
            for (int slot = 1; slot < firstDraw; slot++) {
                access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 320 + 9L * slot));
                assertEquals(firstDraw - 1 - slot, state.backoffCounter().orElseThrow());
            }
            assertSame(packet, next(context, EventType.WIFI_TX_START, 320 + 9L * (firstDraw - 1)).packet());
            assertNull(context.nextEvent());

            // 两次冻结和恢复都未消耗额外随机数。
            state.onTransmissionSuccess();
            reference.onTransmissionSuccess();
            for (int draw = 0; draw < 4; draw++) {
                assertEquals(reference.drawBackoff(), state.drawBackoff());
            }
        }
    }

    @Test
    void grantsIndependentSfusAtTheSameTimestampExactlyOnce(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "packet.arrival.us=0,0", "sfu.count=2", "sta.count=2",
                "sta.2.sfu.id=2", "packet.1.type=VO", "packet.2.type=VO", "packet.2.sta.id=2", "wifi.mac.edca.VO.cwmin=0");

        assertEquals(List.of("WIFI_TX_START:1@34", "WIFI_TX_START:2@34", "WIFI_ACK_END:1@142", "WIFI_ACK_END:2@142"),
                exchangeBoundaries(run(config)));
    }

    @Test
    void rejectsAnUnknownAccessMode(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class,
                () -> load(directory, "packet.arrival.us=0", "wifi.mac.access=unknown"));
    }

    private static void moveTo(SimulationContext context, Packet packet, long delay, long time) {
        context.scheduleAfter(delay, EventType.PACKET_GENERATED, packet, 0);
        next(context, EventType.PACKET_GENERATED, time);
    }

    private static Event next(SimulationContext context, EventType type, long time) {
        Event event = context.nextEvent();
        assertNotNull(event);
        assertEquals(type, event.type());
        assertEquals(time, event.timeUs());
        return event;
    }

    private static SimulationConfig load(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=0", "packet.bytes=1500",
                "wifi.mac.exchange=data-sifs-ack", "wifi.mac.access=edca-countdown"));
        properties.addAll(List.of(settings));
        return ConfigLoader.load(Files.write(directory.resolve("countdown.properties"), properties, StandardCharsets.UTF_8));
    }

    private static List<String> exchangeBoundaries(String trace) {
        Pattern event = Pattern.compile("t_us=(\\S+) node=\\S+ event=(WIFI_TX_START|WIFI_ACK_END) packet_id=(\\d+) ");
        return event.matcher(trace).results().map(row -> row.group(2) + ":" + row.group(3) + "@" + row.group(1))
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
