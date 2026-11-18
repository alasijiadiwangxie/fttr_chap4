package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.mac.EdcaAccess;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaParameters.AcParameters;
import edu.suda.fttr.mac.EdcaState;
import edu.suda.fttr.mac.EdcaState.AcState;
import edu.suda.fttr.mac.WifiAccessMode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdcaNavTest {
    @Test
    void freezesAnInProgressBackoffEvenWhilePhysicalCcaIsIdle() {
        EdcaParameters parameters = parameters(2, 0);
        Packet packet = new Packet(1, 1500, 0);
        AcState state = EdcaState.forSfu(1, parameters).forCategory(packet.type());
        AcState reference = EdcaState.forSfu(1, parameters).forCategory(packet.type());
        int initial = state.drawBackoff();
        assertEquals(initial, reference.drawBackoff());
        assertTrue(initial >= 2);
        WifiChannel channel = new WifiChannel();
        EdcaAccess access = new EdcaAccess(1, channel, parameters);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, false);
            access.start(packet, state, context);
            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 70));
            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 79));

            // 第一个退避时隙已经完成。用无 PHY 占用的预约单独验证 NAV 门控。
            moveTo(context, packet, 4, 83);
            access.receiveMacDuration(0, 1, 2, 70, context);
            assertEquals(WifiChannel.State.IDLE, channel.state());
            assertEquals(153, access.navUntilTicks(0));
            moveTo(context, packet, 20, 103);
            access.receiveMacDuration(0, 1, 2, 100, context);
            moveTo(context, packet, 27, 130);
            access.receiveMacDuration(0, 1, 2, 10, context);
            assertEquals(203, access.navUntilTicks(0));
            assertEquals(initial - 1, state.backoffCounter().orElseThrow());

            // 到期后仍须经过完整 AIFS，再从剩余计数继续退避。
            moveTo(context, packet, 73, 203);
            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 273));
            assertEquals(initial - 1, state.backoffCounter().orElseThrow());
            for (int slot = 1; slot < initial; slot++) {
                access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 273 + 9L * slot));
            }
            assertSame(packet, next(context, EventType.WIFI_TX_START, 273 + 9L * (initial - 1)).packet());
            assertNull(context.nextEvent());

            state.onTransmissionSuccess();
            reference.onTransmissionSuccess();
            assertEquals(reference.drawBackoff(), state.drawBackoff());
        }
    }

    @Test
    void ignoresOwnAndAddressedFramesAndWaitsForBothNavAndCca() {
        EdcaParameters parameters = parameters(2, 0);
        WifiChannel channel = new WifiChannel();
        EdcaAccess access = new EdcaAccess(1, channel, parameters, true);
        EdcaAccess otherSfu = new EdcaAccess(2, new WifiChannel(), parameters, true);
        Packet first = uplink(1, 1);
        Packet second = uplink(2, 2);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, false);
            access.receiveMacDuration(1, 0, 2, 80, context);
            access.receiveMacDuration(0, 0, 2, 200, context);
            access.receiveMacDuration(2, 0, 2, 200, context);
            assertEquals(80, access.navUntilTicks(1));
            assertEquals(0, access.navUntilTicks(0));
            assertEquals(0, access.navUntilTicks(2));
            assertEquals(0, otherSfu.navUntilTicks(1));
            access.start(first, EdcaState.forSta(1, parameters).forCategory(first.type()), context);
            access.start(second, EdcaState.forSta(2, parameters).forCategory(second.type()), context);

            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 34));
            assertEquals(List.of(second), access.resolve(next(context, EventType.WIFI_ACCESS_RESOLVE, 34)));
            channel.occupy();
            moveTo(context, first, 86, 120);
            channel.release();

            // NAV 已于 80 μs 到期，但 AIFS 要从 120 μs 的实际空闲时刻开始。
            access.onTimer(next(context, EventType.WIFI_ACCESS_TIMER, 154));
            assertEquals(List.of(first), access.resolve(next(context, EventType.WIFI_ACCESS_RESOLVE, 154)));
            assertNull(context.nextEvent());
        }
    }

    @Test
    void existingNavBlocksContentionButStillAllowsAnImmediateAck() {
        Sfu sfu = sfu(parameters(2, 0));
        Packet downlink = new Packet(1, 1500, 0, AccessCategory.VO, 1);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, true);
            sfu.receiveMacDuration(1, 2, 0, 200_000, context);
            sfu.enqueueForWifi(downlink, context);
            sfu.enqueueForWifi(uplink(2, 1), context);
            startContentionWinner(sfu, context, 34_000, downlink);

            next(context, EventType.PACKET_ARRIVAL_AT_STA, 98_000);
            sfu.receiveData(downlink, context);
            assertEquals(0, sfu.navUntilTicks(0));
            assertEquals(200_000, sfu.navUntilTicks(1));
            assertEquals(142_000, sfu.navUntilTicks(2));
            completeAck(sfu, context, downlink, 114_000, 142_000);

            assertEquals(WifiChannel.State.IDLE, sfu.ccaState());
            assertEquals(200_000, sfu.navUntilTicks(1));
            sfu.advanceAccess(next(context, EventType.WIFI_ACCESS_TIMER, 234_000));
            sfu.resolveAccess(next(context, EventType.WIFI_ACCESS_RESOLVE, 234_000), context);
            assertEquals(2, next(context, EventType.WIFI_TX_START, 234_000).packet().id());
        }
    }

    @Test
    void reservesTheFittingTxopContinuationAndResumesAfterItsLastAck() {
        Sfu sfu = sfu(parameters(2, 232));
        Packet first = new Packet(1, 1500, 0, AccessCategory.VO, 1);
        Packet second = new Packet(2, 1500, 0, AccessCategory.VO, 1);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, true);
            sfu.enqueueForWifi(first, context);
            sfu.enqueueForWifi(second, context);
            startContentionWinner(sfu, context, 34_000, first);
            sfu.enqueueForWifi(uplink(3, 2), context);

            next(context, EventType.PACKET_ARRIVAL_AT_STA, 98_000);
            sfu.receiveData(first, context);
            assertEquals(266_000, sfu.navUntilTicks(2));
            completeAck(sfu, context, first, 114_000, 142_000);
            assertEquals(WifiChannel.State.BUSY, sfu.ccaState());
            assertEquals(266_000, sfu.navUntilTicks(2));
            assertSame(second, next(context, EventType.WIFI_TXOP_DATA_START, 158_000).packet());
            sfu.transmitWithinTxop(second, context);
            next(context, EventType.PACKET_ARRIVAL_AT_STA, 222_000);
            sfu.receiveData(second, context);
            completeAck(sfu, context, second, 238_000, 266_000);

            assertEquals(WifiChannel.State.IDLE, sfu.ccaState());
            sfu.advanceAccess(next(context, EventType.WIFI_ACCESS_TIMER, 300_000));
            sfu.resolveAccess(next(context, EventType.WIFI_ACCESS_RESOLVE, 300_000), context);
            assertEquals(3, next(context, EventType.WIFI_TX_START, 300_000).packet().id());
        }
    }

    @Test
    void keepsTheTransmittedDurationWhenTheNextPacketArrivesDuringData() {
        Sfu sfu = sfu(parameters(2, 232));
        Packet first = new Packet(1, 1500, 0, AccessCategory.VO, 1);
        Packet second = new Packet(2, 1500, 50, AccessCategory.VO, 1);

        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            SimulationContext context = new SimulationContext(new TraceLogger(sink), true, true);
            sfu.enqueueForWifi(first, context);
            startContentionWinner(sfu, context, 34_000, first);
            moveTo(context, second, 16, 50_000);
            sfu.enqueueForWifi(second, context);

            // 首帧发送时第二帧尚未入队，所以旁听端只获知本次 ACK 的结束时刻。
            next(context, EventType.PACKET_ARRIVAL_AT_STA, 98_000);
            sfu.receiveData(first, context);
            assertEquals(142_000, sfu.navUntilTicks(2));
            completeAck(sfu, context, first, 114_000, 142_000);
            assertEquals(142_000, sfu.navUntilTicks(2));
            assertEquals(WifiChannel.State.BUSY, sfu.ccaState());

            // 晚到报文仍可在预算内续发，它自己成功接收后才更新旁听端的预约。
            next(context, EventType.WIFI_TXOP_DATA_START, 158_000);
            sfu.transmitWithinTxop(second, context);
            next(context, EventType.PACKET_ARRIVAL_AT_STA, 222_000);
            sfu.receiveData(second, context);
            assertEquals(266_000, sfu.navUntilTicks(2));
            completeAck(sfu, context, second, 238_000, 266_000);
            assertEquals(WifiChannel.State.IDLE, sfu.ccaState());
            assertNull(context.nextEvent());
        }
    }

    @Test
    void replaysSeededTxopWithoutAddingNavTimeToPhysicalOccupancy(@TempDir Path directory) throws IOException {
        Path scenario = Files.write(directory.resolve("nav.properties"), List.of("fiber.delay.us=0",
                "packet.bytes=1500", "packet.arrival.us=0,0", "sta.count=2", "packet.1.type=VO", "packet.2.type=VO",
                "wifi.mac.access=edca-multi-ac", "wifi.mac.exchange=data-sifs-ack", "wifi.mac.edca.seed=2",
                "wifi.mac.edca.VO.txop.limit.us=232"), StandardCharsets.UTF_8);
        SimulationConfig config = ConfigLoader.load(scenario);
        String baseline = run(config);
        assertEquals(baseline, run(config));
        assertTrue(baseline.contains("wifi_busy_us=232 "));
        assertTrue(baseline.contains("duration_us=232 packet_count=2"));
    }

    private static EdcaParameters parameters(long seed, long txopLimitUs) {
        EdcaParameters defaults = EdcaParameters.defaults();
        return new EdcaParameters(16, 9, Map.of(AccessCategory.VO, new AcParameters(2, 0, 7, txopLimitUs),
                AccessCategory.VI, defaults.forCategory(AccessCategory.VI),
                AccessCategory.BE, defaults.forCategory(AccessCategory.BE),
                AccessCategory.BK, defaults.forCategory(AccessCategory.BK)), seed);
    }

    private static Sfu sfu(EdcaParameters parameters) {
        WifiLink wifi = new WifiLink(new WirelessPhy(160, 11, 2, 800, 34), new WifiFrameExchange(16, 24));
        return new Sfu(1, wifi, Map.of(1, new Sta(1, 1, parameters), 2, new Sta(2, 1, parameters)),
                parameters, WifiAccessMode.EDCA_MULTI_AC);
    }

    private static Packet uplink(int id, int staId) {
        return new Packet(id, 1500, 0, AccessCategory.VO, Packet.Direction.UL, staId, Packet.DEFAULT_MFU_ID);
    }

    private static void startContentionWinner(Sfu sfu, SimulationContext context, long ticks, Packet packet) {
        sfu.advanceAccess(next(context, EventType.WIFI_ACCESS_TIMER, ticks));
        sfu.resolveAccess(next(context, EventType.WIFI_ACCESS_RESOLVE, ticks), context);
        assertSame(packet, next(context, EventType.WIFI_TX_START, ticks).packet());
        sfu.transmit(packet, context);
    }

    private static void completeAck(Sfu sfu, SimulationContext context, Packet packet, long start, long end) {
        assertSame(packet, next(context, EventType.WIFI_ACK_START, start).packet());
        sfu.transmitAck(packet, context);
        assertSame(packet, next(context, EventType.WIFI_ACK_END, end).packet());
        sfu.completeTransmission(packet, context);
    }

    private static void moveTo(SimulationContext context, Packet packet, long delay, long ticks) {
        context.scheduleAfter(delay, EventType.PACKET_GENERATED, packet, 0);
        next(context, EventType.PACKET_GENERATED, ticks);
    }

    private static Event next(SimulationContext context, EventType type, long ticks) {
        Event event = context.nextEvent();
        assertNotNull(event);
        assertEquals(type, event.type());
        assertEquals(ticks, event.timeTicks());
        return event;
    }

    private static String run(SimulationConfig scenario) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(scenario, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
