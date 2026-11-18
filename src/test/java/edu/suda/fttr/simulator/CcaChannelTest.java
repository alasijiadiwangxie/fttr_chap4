package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.FixedDelayModel;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.link.WifiChannel.State;
import edu.suda.fttr.phy.WirelessPhy;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CcaChannelTest {
    @Test
    void sharesBusyStateThroughDataSifsAndAckAcrossBothDirections() {
        Sta receiver = new Sta(1, 1);
        Sta otherStation = new Sta(2, 1);
        WifiLink wifi = new WifiLink(new WirelessPhy(160, 11, 2, 800, 34), new WifiFrameExchange(16, 24));
        Sfu sfu = new Sfu(1, wifi, Map.of(1, receiver, 2, otherStation));
        Packet downlink = new Packet(1, 1500, 0);
        Packet uplink = new Packet(2, 1500, 70, AccessCategory.VO, Packet.Direction.UL, 2, Packet.DEFAULT_MFU_ID);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, true);
            assertSharedState(State.IDLE, sfu, receiver, otherStation);
            sfu.enqueueForWifi(downlink, context);
            context.scheduleInputEvent(Event.packetGenerated(uplink));
            next(context, EventType.WIFI_SELECT_NEXT, 0);
            sfu.selectNext(context);
            assertSame(downlink, next(context, EventType.WIFI_TX_START, 0).packet());
            sfu.transmit(downlink, context);
            assertSharedState(State.BUSY, sfu, receiver, otherStation);
            assertThrows(IllegalStateException.class, () -> sfu.transmit(downlink, context));

            next(context, EventType.PACKET_ARRIVAL_AT_STA, 64);
            sfu.receiveData(downlink, context);
            assertSharedState(State.BUSY, sfu, receiver, otherStation);

            // 另一台 STA 在 SIFS 中产生 VO 上行，须让正在交换的下行完成 ACK。
            assertSame(uplink, next(context, EventType.PACKET_GENERATED, 70).packet());
            sfu.enqueueForWifi(uplink, context);
            assertSharedState(State.BUSY, sfu, receiver, otherStation);
            next(context, EventType.WIFI_ACK_START, 80);
            sfu.transmitAck(downlink, context);
            assertSharedState(State.BUSY, sfu, receiver, otherStation);
            next(context, EventType.WIFI_ACK_END, 108);
            assertSharedState(State.BUSY, sfu, receiver, otherStation);
            sfu.completeTransmission(downlink, context);
            assertSharedState(State.IDLE, sfu, receiver, otherStation);

            next(context, EventType.WIFI_SELECT_NEXT, 108);
            sfu.selectNext(context);
            assertSame(uplink, next(context, EventType.WIFI_TX_START, 108).packet());
            sfu.transmit(uplink, context);
            assertSharedState(State.BUSY, sfu, receiver, otherStation);
            next(context, EventType.WIFI_ARRIVAL_AT_SFU, 172);
            sfu.receiveData(uplink, context);
            next(context, EventType.FIBER_TX_START, 172);
            next(context, EventType.WIFI_ACK_START, 188);
            sfu.transmitAck(uplink, context);
            next(context, EventType.WIFI_ACK_END, 216);
            sfu.completeTransmission(uplink, context);
            assertSharedState(State.IDLE, sfu, receiver, otherStation);
            assertNull(context.nextEvent());
        }
    }

    @Test
    void differentSfusCanBeBusyTogetherAndReleaseIndependently() {
        // 两台 SFU 共用固定时延模型。第一个 DATA 结束不会释放第二台的信道。
        WifiLink wifi = new WifiLink(new FixedDelayModel(40));
        Sta firstStation = new Sta(1, 1);
        Sta secondStation = new Sta(2, 2);
        Sfu firstSfu = new Sfu(1, wifi, Map.of(1, firstStation));
        Sfu secondSfu = new Sfu(2, wifi, Map.of(2, secondStation));
        Packet first = new Packet(1, 1200, 0);
        Packet second = new Packet(2, 1200, 0, AccessCategory.BE, 2);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, false);
            startData(context, firstSfu, first);
            assertSharedState(State.BUSY, firstSfu, firstStation);
            assertSharedState(State.IDLE, secondSfu, secondStation);
            startData(context, secondSfu, second);
            assertSharedState(State.BUSY, firstSfu, firstStation);
            assertSharedState(State.BUSY, secondSfu, secondStation);

            assertSame(first, next(context, EventType.PACKET_ARRIVAL_AT_STA, 40).packet());
            firstSfu.receiveData(first, context);
            assertSharedState(State.IDLE, firstSfu, firstStation);
            assertSharedState(State.BUSY, secondSfu, secondStation);
            assertSame(second, next(context, EventType.PACKET_ARRIVAL_AT_STA, 40).packet());
            secondSfu.receiveData(second, context);
            assertSharedState(State.IDLE, secondSfu, secondStation);
            assertNull(context.nextEvent());
        }
    }

    @Test
    void replaysTheSameOrderWhenUplinkArrivesAtDownlinkAckEnd(@TempDir Path directory) throws IOException {
        Path file = Files.write(directory.resolve("cca.properties"), List.of("fiber.delay.us=0",
                "packet.arrival.us=0,0,108", "packet.bytes=1500", "wifi.mac.exchange=data-sifs-ack",
                "traffic.direction=BOTH", "sta.count=2", "packet.1.direction=DL", "packet.2.direction=DL",
                "packet.3.direction=UL", "packet.3.type=VO", "packet.3.sta.id=2"), StandardCharsets.UTF_8);
        SimulationConfig config = ConfigLoader.load(file);

        String trace = run(config);
        assertEquals(trace, run(config));
        Pattern boundaries = Pattern.compile("t_us=(\\S+) node=\\S+ event=(WIFI_TX_START|WIFI_ACK_END) packet_id=(\\d+) ");
        // 同为 108 μs 的上行 VO 到达及下行 ACK 结束都先于重新选队，VO 先于已等待的 BE。
        assertEquals(List.of("WIFI_TX_START:1@0", "WIFI_ACK_END:1@108", "WIFI_TX_START:3@108",
                "WIFI_ACK_END:3@216", "WIFI_TX_START:2@216", "WIFI_ACK_END:2@324"),
                boundaries.matcher(trace).results().map(row -> row.group(2) + ":" + row.group(3) + "@" + row.group(1))
                        .collect(Collectors.toList()));
    }

    private static Event next(SimulationContext context, EventType type, long timeUs) {
        Event event = context.nextEvent();
        assertNotNull(event);
        assertEquals(type, event.type());
        assertEquals(timeUs, event.timeUs());
        return event;
    }

    private static void startData(SimulationContext context, Sfu sfu, Packet packet) {
        sfu.enqueueForWifi(packet, context);
        next(context, EventType.WIFI_SELECT_NEXT, 0);
        sfu.selectNext(context);
        assertSame(packet, next(context, EventType.WIFI_TX_START, 0).packet());
        sfu.transmit(packet, context);
    }

    private static void assertSharedState(State state, Sfu sfu, Sta... stations) {
        assertEquals(state, sfu.ccaState());
        for (Sta station : stations) {
            assertEquals(state, station.ccaState());
        }
    }

    private static String run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
