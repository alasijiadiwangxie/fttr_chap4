package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDemandLedger;
import edu.suda.fttr.link.PonGrantPacketFiller;
import edu.suda.fttr.link.PonGrantPacketFiller.FillReason;
import edu.suda.fttr.link.PonGrantPacketFiller.PacketCheck;
import edu.suda.fttr.link.PonGrantPacketFiller.PacketView;
import edu.suda.fttr.link.PonGrantPacketFiller.QueueView;
import edu.suda.fttr.link.PonGrantPacketFiller.Result;
import edu.suda.fttr.link.PonLinkTiming;
import edu.suda.fttr.link.PonUplink;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 11 步只读预算装载验收。静态视图覆盖逐包取整和队首阻塞；真实 Port 场景
 * 验证待授权 FIFO、实际 FCFS 预约和 MFU 账本不会因预演而改变。
 */
class PonGrantPacketFillerTest {
    private static final BigDecimal RATE = BigDecimal.valueOf(2500);
    private static final long SLOT_START = 250_000;

    @Test
    void selectsOnlyTheFirstThreeOfFourPacketsForA3600ByteGrant() {
        Result result = fill(3600, 11520, queue(1200, 1200, 1200, 1200));
        assertEquals(List.of(1, 2, 3), result.selectedPacketIds());
        assertTotals(result, 3600, 11520, 0, 0, 1);
        assertEquals(FillReason.BUDGET_EXHAUSTED, result.fillReason());
        assertEquals(4, result.packetChecks().size());
        assertTrue(result.packetChecks().get(2).selected());
        assertFalse(result.packetChecks().get(3).selected());
    }

    @Test
    void leavesThreeHundredBytesWhenTheNextWholePacketDoesNotFit() {
        Result result = fill(1500, 4800, queue(1200, 1200));
        assertEquals(List.of(1), result.selectedPacketIds());
        assertTotals(result, 1200, 3840, 300, 960, 1);
        assertEquals(FillReason.HEAD_BYTES_EXCEED_BUDGET, result.fillReason());
        assertFalse(result.packetChecks().get(1).selected());
        assertEquals(2, result.packetChecks().get(1).fifoPosition());
    }

    @Test
    void sameTimestampPacketsKeepQueueOrderInsteadOfBeingSortedByPacketId() {
        QueueView view = new QueueView(1, 0, List.of(new PacketView(9, 1200, 0),
                new PacketView(3, 1200, 0), new PacketView(7, 1200, 0)));
        Result result = fill(2400, 7680, view);
        assertEquals(List.of(9, 3), result.selectedPacketIds());
        assertEquals(List.of(9, 3, 7), ids(view));
        assertEquals(1, result.packetChecks().get(0).fifoPosition());
        assertEquals(2, result.packetChecks().get(1).fifoPosition());
        assertEquals(3, result.packetChecks().get(2).fifoPosition());
    }

    @Test
    void cannotFitA39062ByteHeadInThe38998ByteBudget() {
        Result result = fill(38998, 124794, queue(39062));
        assertTrue(result.selectedPacketIds().isEmpty());
        assertTotals(result, 0, 0, 38998, 124794, 1);
        assertEquals(FillReason.HEAD_BYTES_EXCEED_BUDGET, result.fillReason());
    }

    @Test
    void cannotBypassTheBlockedHeadToSelectALaterSmallPacket() {
        Result result = fill(1500, 4800, queue(2000, 100));
        assertTrue(result.selectedPacketIds().isEmpty());
        assertEquals(FillReason.HEAD_BYTES_EXCEED_BUDGET, result.fillReason());
        assertFalse(result.packetChecks().get(1).selected());
        assertEquals("NOT_REACHED", result.packetChecks().get(1).rejectReason());
        assertEquals(2, result.remainingPacketCount());
    }

    @Test
    void anEmptyQueueLeavesBothBudgetsUnused() {
        Result result = fill(3600, 11520, queue());
        assertTrue(result.selectedPacketIds().isEmpty());
        assertTrue(result.packetChecks().isEmpty());
        assertTotals(result, 0, 0, 3600, 11520, 0);
        assertEquals(FillReason.QUEUE_EMPTY, result.fillReason());
    }

    @Test
    void sumsEachPacketRoundingInsteadOfRoundingTheTotalBytesOnce() {
        // 2.5 Gb/s 下 1 B 各需 ceil(3.2)=4 ns，而总 2 B 一次取整为 7 ns。
        // 字节预算能容纳两包，时间预算只能容纳一包。
        assertEquals(4, PonLinkTiming.serializationNs(1, RATE));
        assertEquals(7, PonLinkTiming.serializationNs(2, RATE));
        Result result = fill(2, 7, queue(1, 1));
        assertEquals(List.of(1), result.selectedPacketIds());
        assertTotals(result, 1, 4, 1, 3, 1);
        assertEquals(FillReason.HEAD_TIME_EXCEEDS_BUDGET, result.fillReason());
        assertEquals(4, result.packetChecks().get(1).packetDurationNs());
    }

    @Test
    void distinguishesQueueDrainedFromBudgetExhausted() {
        Result drained = fill(2400, 7680, queue(1200));
        assertEquals(FillReason.QUEUE_DRAINED, drained.fillReason());
        assertTotals(drained, 1200, 3840, 1200, 3840, 0);
        Result exhausted = fill(1200, 3840, queue(1200));
        assertEquals(FillReason.BUDGET_EXHAUSTED, exhausted.fillReason());
        assertTotals(exhausted, 1200, 3840, 0, 0, 0);
        Result zeroBudget = fill(0, 0, queue(1200));
        assertEquals(FillReason.BUDGET_EXHAUSTED, zeroBudget.fillReason());
        assertTrue(zeroBudget.selectedPacketIds().isEmpty());
        assertTotals(zeroBudget, 0, 0, 0, 0, 1);
    }

    @Test
    void doesNotUseTheFutureSlotToReadPacketsThatHaveNotArrivedAtTheSnapshot() {
        QueueView view = new QueueView(1, 100, List.of(
                new PacketView(1, 1200, 0), new PacketView(2, 1200, 101)));
        Result result = fill(2400, 7680, view);
        assertEquals(List.of(1), result.selectedPacketIds());
        assertEquals(FillReason.HEAD_NOT_ARRIVED, result.fillReason());
        assertTotals(result, 1200, 3840, 1200, 3840, 1);
        assertFalse(result.packetChecks().get(1).selected());
    }

    @Test
    void requiresAnExplicitAllocationOwnerAndTheMatchingLocalSfuView() {
        PonGrantPacketFiller filler = filler();
        QueueView sfu2 = new QueueView(2, 0, List.of(new PacketView(10, 800, 0)));
        Result second = filler.fill(4, 2, 1200, 3840, SLOT_START, sfu2);
        assertEquals(List.of(10), second.selectedPacketIds());
        assertEquals(800, second.selectedBytes());
        assertThrows(IllegalArgumentException.class,
                () -> filler.fill(99, 1, 1200, 3840, SLOT_START, queue(1200)));
        assertThrows(IllegalArgumentException.class,
                () -> filler.fill(2, 2, 1200, 3840, SLOT_START, sfu2));
        assertThrows(IllegalArgumentException.class,
                () -> filler.fill(4, 2, 1200, 3840, SLOT_START, queue(1200)));

        // 接口不使用 2*sfuId 公式判定合法性，可以传入将来拓扑中明确指定的对象身份。
        PonGrantPacketFiller alternate = new PonGrantPacketFiller(RATE, Map.of(101L, 1, 102L, 1));
        assertEquals(List.of(1), alternate.fill(101, 1, 1200, 3840, SLOT_START, queue(1200))
                .selectedPacketIds());
        assertEquals(List.of(1), alternate.fill(102, 1, 1200, 3840, SLOT_START, queue(1200))
                .selectedPacketIds());
    }

    @Test
    void snapshotsAndResultsAreImmutableAndRepeatedPreviewsAreIdentical() {
        List<PacketView> source = new ArrayList<>(List.of(new PacketView(1, 1200, 0),
                new PacketView(2, 1200, 0)));
        QueueView view = new QueueView(1, 0, source);
        source.clear();
        Result first = fill(1500, 4800, view);
        Result repeated = fill(1500, 4800, view);
        assertEquals(List.of(1, 2), ids(view));
        assertEquals(first.selectedPacketIds(), repeated.selectedPacketIds());
        assertEquals(first.selectedBytes(), repeated.selectedBytes());
        assertEquals(first.selectedDurationNs(), repeated.selectedDurationNs());
        assertEquals(first.unusedBytes(), repeated.unusedBytes());
        assertEquals(first.unusedDurationNs(), repeated.unusedDurationNs());
        assertEquals(first.remainingPacketCount(), repeated.remainingPacketCount());
        assertEquals(first.fillReason(), repeated.fillReason());
        assertEquals(first.packetChecks().size(), repeated.packetChecks().size());
        for (int index = 0; index < first.packetChecks().size(); index++) {
            PacketCheck expected = first.packetChecks().get(index);
            PacketCheck actual = repeated.packetChecks().get(index);
            assertEquals(expected.packetId(), actual.packetId());
            assertEquals(expected.packetBytes(), actual.packetBytes());
            assertEquals(expected.packetDurationNs(), actual.packetDurationNs());
            assertEquals(expected.fifoPosition(), actual.fifoPosition());
            assertEquals(expected.selected(), actual.selected());
            assertEquals(expected.rejectReason(), actual.rejectReason());
        }
        assertThrows(UnsupportedOperationException.class, () -> view.packets().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.selectedPacketIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.packetChecks().clear());
    }

    @Test
    void refusesInvalidBudgetsTimesAndMalformedFifoInputs() {
        PonGrantPacketFiller filler = filler();
        assertThrows(IllegalArgumentException.class,
                () -> filler.fill(2, 1, -1, 0, SLOT_START, queue(1200)));
        assertThrows(IllegalArgumentException.class,
                () -> filler.fill(2, 1, 1200, -1, SLOT_START, queue(1200)));
        assertThrows(IllegalArgumentException.class,
                () -> filler.fill(2, 1, 1200, 3840, 99,
                        new QueueView(1, 100, List.of(new PacketView(1, 1200, 0)))));
        assertThrows(IllegalArgumentException.class,
                () -> filler.fill(2, 1, 1200, 3840, Long.MAX_VALUE, queue(1200)));
        assertThrows(IllegalArgumentException.class,
                () -> fill(1200, 3840, new QueueView(1, 100, List.of(
                        new PacketView(1, 1200, 0), new PacketView(1, 1200, 1)))));
        assertThrows(IllegalArgumentException.class,
                () -> fill(1200, 3840, new QueueView(1, 100, List.of(
                        new PacketView(1, 1200, 10), new PacketView(2, 1200, 0)))));
        assertThrows(IllegalArgumentException.class,
                () -> fill(1200, 3840, new QueueView(1, 0, List.of(new PacketView(1, 0, 0)))));
    }

    @Test
    void freshPortViewsExcludePacketsAlreadyReservedByFcfsAndKeepSfusIndependent() {
        try (PrintStream output = quietOutput()) {
            SimulationContext context = new SimulationContext(new TraceLogger(output), true, true);
            PonUplink uplink = new PonUplink(RATE, 10025, 125000, true, 10000);
            PonUplink.Port first = uplink.attachSfu(1);
            PonUplink.Port second = uplink.attachSfu(2);
            first.enqueue(packet(1, 1200, 1), context);
            first.enqueue(packet(2, 1200, 1), context);
            second.enqueue(packet(3, 800, 2), context);
            QueueView original = first.ungrantedPacketView(context);
            assertEquals(List.of(1, 2), ids(original));
            assertEquals(List.of(3), ids(second.ungrantedPacketView(context)));
            assertEquals(List.of(1), first.previewGrant(filler(), 2, 1200, 3840, SLOT_START, context)
                    .selectedPacketIds());
            assertEquals(List.of(1, 2), ids(first.ungrantedPacketView(context)));

            // 同一共享帧只能预约前 32 包，本地预演只能看到最后一包。
            for (int id = 4; id <= 33; id++) {
                first.enqueue(packet(id, 1200, 1), context);
            }
            assertEquals(EventType.PON_UPLINK_FRAME, context.nextEvent().type());
            uplink.scheduleFrame(context);
            assertEquals(List.of(33), ids(first.ungrantedPacketView(context)));
            assertEquals(List.of(33), first.previewGrant(filler(), 2, 1200, 3840, 375_000, context)
                    .selectedPacketIds());
            assertTrue(second.ungrantedPacketView(context).packets().isEmpty());
            assertEquals(FillReason.QUEUE_EMPTY,
                    second.previewGrant(filler(), 4, 1200, 3840, SLOT_START, context).fillReason());
            // 旧视图只是历史材料，不被真实预约或后续出队反向修改。
            assertEquals(List.of(1, 2), ids(original));
        }
    }

    @Test
    void sameTimestampEnqueueAndFcfsReservationFollowTheExistingDeterministicOrder() {
        try (PrintStream output = quietOutput()) {
            SimulationContext context = new SimulationContext(new TraceLogger(output), true, true);
            PonUplink uplink = new PonUplink(RATE, 0, 125000, false, 10000);
            PonUplink.Port port = uplink.attachSfu(1);
            port.enqueue(packet(1, 1200, 1), context);
            Packet boundaryArrival = packet(2, 1200, 1);
            context.scheduleAfterNanoseconds(125000, EventType.WIFI_ARRIVAL_AT_SFU, boundaryArrival, 1);
            Event arrival = context.nextEvent();
            assertEquals(EventType.WIFI_ARRIVAL_AT_SFU, arrival.type());
            port.enqueue(arrival.packet(), context);
            // 同刻到达在调用前已处理，本地预演可看到它；后续同刻FCFS仍保留严格边界资格。
            assertEquals(List.of(1, 2), port.previewGrant(filler(), 2, 2400, 7680,
                    SLOT_START, context).selectedPacketIds());
            assertEquals(EventType.PON_UPLINK_FRAME, context.nextEvent().type());
            uplink.scheduleFrame(context);
            assertEquals(List.of(2), ids(port.ungrantedPacketView(context)));
            assertEquals(List.of(2), port.previewGrant(filler(), 2, 1200, 3840,
                    SLOT_START, context).selectedPacketIds());
        }
    }

    @Test
    void portRejectsAnInconsistentRateOrANonNanosecondView() {
        try (PrintStream output = quietOutput()) {
            SimulationContext nanoseconds = new SimulationContext(new TraceLogger(output), true, true);
            SimulationContext microseconds = new SimulationContext(new TraceLogger(output), true, false);
            PonUplink uplink = new PonUplink(RATE, 0, 125000, false, 10000);
            PonUplink.Port port = uplink.attachSfu(1);
            assertThrows(IllegalStateException.class, () -> port.ungrantedPacketView(microseconds));
            PonGrantPacketFiller wrongRate = new PonGrantPacketFiller(BigDecimal.valueOf(10000),
                    Map.of(2L, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> port.previewGrant(wrongRate, 2, 1200, 3840, SLOT_START, nanoseconds));
            // BigDecimal 表示不同但数值一致时不应误拒绝同一条链路速率。
            PonGrantPacketFiller equivalentRate = new PonGrantPacketFiller(new BigDecimal("2500.00"),
                    Map.of(2L, 1));
            assertEquals(FillReason.QUEUE_EMPTY,
                    port.previewGrant(equivalentRate, 2, 1200, 3840, SLOT_START, nanoseconds).fillReason());
        }
    }

    @Test
    void repeatedLocalPreviewsLeaveTheCompleteFcfsBwmapReportAndLedgerTraceUntouched() {
        String baseline = runRealPonEvents(false);
        String observed = runRealPonEvents(true);
        assertEquals(baseline, observed);
        assertEquals(observed, runRealPonEvents(true));
        assertTrue(observed.contains("event=PON_BWMAP_TX "));
        assertTrue(observed.contains("event=PON_UPLINK_REPORT_RX "));
        assertTrue(observed.contains("event=PON_DEMAND_LEDGER "));
        assertEquals(4, observed.lines().filter(line -> line.contains("node=MFU event=DELIVERED ")).count());
    }

    /** 直接运行既有光侧事件。预演只同步读取Port，不调度额外事件、不写日志或统计。 */
    private static String runRealPonEvents(boolean preview) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            Mfu mfu = new Mfu(List.of(1, 2), null, true);
            SimulationContext context = new SimulationContext(new TraceLogger(output), true, true, false, mfu);
            PonUplink uplink = new PonUplink(RATE, 10025, 125000, true, 10000);
            List<PonUplink.Port> ports = List.of(uplink.attachSfu(1), uplink.attachSfu(2));
            ports.get(0).enqueue(packet(1, 1200, 1), context);
            ports.get(0).enqueue(packet(2, 800, 1), context);
            ports.get(1).enqueue(packet(3, 1000, 2), context);
            context.scheduleAfterNanoseconds(125000, EventType.WIFI_ARRIVAL_AT_SFU,
                    packet(4, 1200, 2), 2);
            Event event;
            while ((event = context.nextEvent()) != null) {
                if (preview) {
                    previewBoth(ports, context, mfu);
                }
                switch (event.type()) {
                    case WIFI_ARRIVAL_AT_SFU:
                        ports.get(event.sfuId() - 1).enqueue(event.packet(), context);
                        break;
                    case PON_UPLINK_FRAME:
                        uplink.scheduleFrame(context);
                        break;
                    case PON_BWMAP_RX:
                        ports.get(event.sfuId() - 1).receiveBwmap(event.packet(), context);
                        break;
                    case FIBER_TX_START:
                        uplink.start(event.packet(), event.sfuId(), context);
                        break;
                    case FIBER_TX_END:
                        uplink.finish(event.packet(), event.sfuId(), context);
                        break;
                    case PACKET_ARRIVAL_AT_MFU:
                        mfu.receiveUplink(event.packet(), event.sfuId(), context);
                        break;
                    case PON_UPLINK_REPORT_RX:
                        mfu.receiveUplinkReport(event.uplinkReport(), event.packet(), context);
                        break;
                    default:
                        throw new AssertionError("Unexpected test event " + event.type());
                }
                if (preview) {
                    previewBoth(ports, context, mfu);
                }
            }
            uplink.recordSummary(context);
            uplink.recordReportSummary(mfu.receivedUplinkReports(), context);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void previewBoth(List<PonUplink.Port> ports, SimulationContext context, Mfu mfu) {
        for (int i = 0; i < ports.size(); i++) {
            int sfuId = i + 1;
            PonDemandLedger.Estimate before = mfu.estimateUplinkDemand(sfuId, context.nowTicks());
            QueueView view = ports.get(i).ungrantedPacketView(context);
            List<Integer> packetIds = ids(view);
            long slot = Math.addExact(context.nowTicks(), 125000);
            Result first = ports.get(i).previewGrant(filler(), 2L * sfuId, 2400, 7680, slot, context);
            Result second = ports.get(i).previewGrant(filler(), 2L * sfuId, 2400, 7680, slot, context);
            assertEquals(first.selectedPacketIds(), second.selectedPacketIds());
            assertEquals(packetIds, ids(ports.get(i).ungrantedPacketView(context)));
            PonDemandLedger.Estimate after = mfu.estimateUplinkDemand(sfuId, context.nowTicks());
            assertEquals(before.known(), after.known());
            if (before.known()) {
                assertEquals(before.effectiveKnownBytes(), after.effectiveKnownBytes());
                assertEquals(before.grantedBytesSinceSnapshot(), after.grantedBytesSinceSnapshot());
            }
        }
    }

    private static Result fill(long bytes, long durationNs, QueueView view) {
        return filler().fill(2, 1, bytes, durationNs, SLOT_START, view);
    }

    private static PonGrantPacketFiller filler() {
        return new PonGrantPacketFiller(RATE, Map.of(2L, 1, 4L, 2));
    }

    private static QueueView queue(int... packetBytes) {
        List<PacketView> packets = new ArrayList<>();
        for (int i = 0; i < packetBytes.length; i++) {
            packets.add(new PacketView(i + 1, packetBytes[i], 0));
        }
        return new QueueView(1, 0, packets);
    }

    private static List<Integer> ids(QueueView view) {
        return view.packets().stream().map(PacketView::packetId).collect(Collectors.toList());
    }

    private static Packet packet(int id, int bytes, int sourceSta) {
        return new Packet(id, bytes, 0, AccessCategory.BE, Packet.Direction.UL,
                sourceSta, Packet.DEFAULT_MFU_ID);
    }

    private static PrintStream quietOutput() {
        return new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8);
    }

    private static void assertTotals(Result result, long bytes, long durationNs, long unusedBytes,
                                     long unusedNs, int remaining) {
        assertEquals(bytes, result.selectedBytes());
        assertEquals(durationNs, result.selectedDurationNs());
        assertEquals(unusedBytes, result.unusedBytes());
        assertEquals(unusedNs, result.unusedDurationNs());
        assertEquals(remaining, result.remainingPacketCount());
    }
}
