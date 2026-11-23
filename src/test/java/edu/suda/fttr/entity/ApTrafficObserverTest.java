package edu.suda.fttr.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static edu.suda.fttr.entity.AccessCategory.BE;
import static edu.suda.fttr.entity.AccessCategory.BK;
import static edu.suda.fttr.entity.AccessCategory.VO;
import static edu.suda.fttr.entity.Packet.Direction.DL;
import static edu.suda.fttr.entity.Packet.Direction.UL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApTrafficObserverTest {
    @Test
    void boundaryEventsBelongToTheClosingWindowAndEwmaUsesOnlyPastSamples() {
        ApTrafficObserver observer = new ApTrafficObserver(1_000_000, 0.25);
        assertEquals(List.of(), observer.latest());
        observer.enqueue(new Packet(1, 1_000, 0), 0);
        observer.enqueue(new Packet(2, 1_500, 0), 1_000_000);
        List<ApTrafficObserver.Observation> first = observer.sample(1_000_000, 0, 0);
        assertEquals(first, observer.latest());
        assertEquals(8, first.size());
        ApTrafficObserver.Observation initial = row(first, DL, BE);
        assertEquals(0, initial.startNs());
        assertEquals(1_000_000, initial.endNs());
        assertEquals(2, initial.arrivalPackets());
        assertEquals(2_500, initial.arrivalBytes());
        assertEquals(2_000.0, initial.arrivalPps());
        assertEquals(20.0, initial.arrivalMbps());
        assertEquals(2_000.0, initial.ewmaArrivalPps());
        assertEquals(20.0, initial.ewmaArrivalMbps());
        assertEquals(1_000_000, initial.headWaitNs());

        ApTrafficObserver.Observation quiet = row(observer.sample(2_000_000, 0, 0), DL, BE);
        assertEquals(0, quiet.arrivalPackets());
        assertEquals(15.0, quiet.ewmaArrivalMbps());
        assertEquals(1_500.0, quiet.ewmaArrivalPps());
        observer.enqueue(new Packet(3, 500, 0), 2_250_000);
        ApTrafficObserver.Observation partial = row(observer.sample(2_500_000, 0, 0), DL, BE);
        assertEquals(8.0, partial.arrivalMbps());
        assertEquals(13.25, partial.ewmaArrivalMbps());
        assertEquals(1_625.0, partial.ewmaArrivalPps());
        assertEquals(2, initial.queuePackets());
        assertEquals(2_500, initial.arrivalBytes());
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test
    void partialBlockAckKeepsThePendingAndInFlightProjectionDisjoint() {
        ApTrafficObserver observer = new ApTrafficObserver(100, 0.2);
        Packet acked = new Packet(1, 1_000, 0);
        Packet retried = new Packet(2, 800, 0);
        Packet next = new Packet(3, 600, 0);
        observer.enqueue(acked, 0);
        observer.enqueue(retried, 1);
        observer.enqueue(next, 2);
        observer.start(acked, 90);
        observer.start(retried, 90);
        observer.recordPpdu(acked, 2, 90);
        ApTrafficObserver.QueueEvent delivered = observer.deliver(acked, 95);
        assertEquals(ApTrafficObserver.Kind.DELIVERED, delivered.kind());
        assertEquals(1, delivered.awaitingConfirmPackets());
        ApTrafficObserver.Observation first = row(observer.sample(100, 5, 0), DL, BE);
        assertEquals(3, first.arrivalPackets());
        assertEquals(1, first.queuePackets());
        assertEquals(600, first.queueBytes());
        assertEquals(2, first.inFlightPackets());
        assertEquals(1_800, first.inFlightBytes());
        assertEquals(1, first.deliveredPackets());
        assertEquals(0, first.confirmedPackets());
        assertEquals(-1.0, first.deliverySuccessRatio());
        assertEquals(1, first.awaitingConfirmPackets());
        assertEquals(2.0, first.averageDepth());

        observer.confirm(acked, 101);
        observer.retry(retried, 101);
        ApTrafficObserver.Observation second = row(observer.sample(200, 5, 4), DL, BE);
        assertEquals(0, second.arrivalPackets());
        assertEquals(0, second.deliveredPackets());
        assertEquals(0, second.inFlightPackets());
        assertEquals(1, second.confirmedPackets());
        assertEquals(1.0, second.deliverySuccessRatio());
        assertEquals(2, second.queuePackets());
        assertEquals(1_400, second.queueBytes());
        assertEquals(199, second.headWaitNs());
        assertEquals(1, second.requeuedPackets());
        assertEquals(0, second.retriedPackets());

        assertEquals(ApTrafficObserver.Kind.RETRANSMIT_START, observer.start(retried, 201).kind());
        observer.recordPpdu(retried, 1, 201);
        observer.drop(retried, 202);
        ApTrafficObserver.Observation third = row(observer.sample(275, 6, 4), DL, BE);
        assertEquals(0, third.arrivalPackets());
        assertEquals(1, third.droppedPackets());
        assertEquals(0.0, third.deliverySuccessRatio());
        assertEquals(800, third.droppedBytes());
        assertEquals(1, third.retriedPackets());
        assertEquals(800, third.retriedBytes());
        assertEquals(0, third.inFlightPackets());
        assertEquals(1, third.queuePackets());
        assertEquals(273, third.headWaitNs());
        assertEquals(3, second.confirmedPackets() + third.droppedPackets() + third.queuePackets());
        assertEquals(2_400, second.confirmedBytes() + third.droppedBytes() + third.queueBytes());
        assertEquals(2, delivered.inFlightPackets());
    }

    @Test
    void uplinkIsVisibleOnlyWhenDecodedAtTheAp() {
        ApTrafficObserver observer = new ApTrafficObserver(100, 1.0);
        Packet uplink = new Packet(1, 1_200, 0, VO, UL, 1, 1);
        assertNull(observer.enqueue(uplink, 0));
        assertNull(observer.start(uplink, 20));
        assertNull(observer.retry(uplink, 70));
        ApTrafficObserver.Observation hidden = row(observer.sample(100, 0, 25), UL, VO);
        assertEquals(0, hidden.arrivalPackets());
        assertEquals(0, hidden.queuePackets());
        assertEquals(0, hidden.inFlightPackets());
        assertEquals(0, hidden.retriedPackets());
        assertEquals(-1, hidden.headWaitNs());
        assertEquals(25, hidden.rxNs());

        assertEquals(ApTrafficObserver.Kind.UL_RECEIVED, observer.deliver(uplink, 140).kind());
        observer.recordPpdu(uplink, 1, 140);
        assertNull(observer.confirm(uplink, 165));
        ApTrafficObserver.Observation decoded = row(observer.sample(200, 10, 45), UL, VO);
        assertEquals(1, decoded.arrivalPackets());
        assertEquals(1, decoded.deliveredPackets());
        assertEquals(1_200, decoded.deliveredBytes());
        assertEquals(1, decoded.ppduMembers());
        assertEquals(0, decoded.confirmedPackets());
        assertEquals(-1.0, decoded.deliverySuccessRatio());
        assertEquals(0, decoded.queueBytes());
    }

    @Test
    void successRatioUsesSettledOutcomesAndExcludesPendingPackets() {
        ApTrafficObserver observer = new ApTrafficObserver(100, 0.25);
        Packet acked = new Packet(1, 1_200, 0);
        Packet dropped = new Packet(2, 1_200, 0);
        observer.enqueue(acked, 2);
        observer.enqueue(dropped, 2);
        observer.enqueue(new Packet(3, 1_200, 0), 2);
        observer.start(acked, 20);
        observer.start(dropped, 20);
        observer.deliver(acked, 30);
        observer.confirm(acked, 40);
        observer.drop(dropped, 40);
        assertEquals(0.5, row(observer.sample(100, 10, 4), DL, BE).deliverySuccessRatio());
    }

    @Test
    void accessCategoriesAndApInstancesAreIndependent() {
        ApTrafficObserver first = new ApTrafficObserver(100, 0.2);
        ApTrafficObserver second = new ApTrafficObserver(100, 0.2);
        first.enqueue(new Packet(1, 300, 0, VO), 10);
        first.enqueue(new Packet(2, 900, 0, BE), 20);
        second.enqueue(new Packet(3, 700, 0, BK), 60);
        List<ApTrafficObserver.Observation> firstRows = first.sample(100, 20, 17);
        List<ApTrafficObserver.Observation> secondRows = second.sample(100, 0, 3);
        assertEquals(300, row(firstRows, DL, VO).queueBytes());
        assertEquals(900, row(firstRows, DL, BE).queueBytes());
        assertEquals(0, row(firstRows, DL, BK).queueBytes());
        assertEquals(0, row(secondRows, DL, VO).queueBytes());
        assertEquals(700, row(secondRows, DL, BK).queueBytes());
        assertEquals(20, row(firstRows, DL, BK).txNs());
        assertEquals(3, row(secondRows, DL, BE).rxNs());
    }

    @Test
    void airtimeIsTheExactDifferenceOfCumulativeIntegerDurations() {
        ApTrafficObserver observer = new ApTrafficObserver(1_000, 0.5);
        ApTrafficObserver.Observation first = row(observer.sample(1_000, 100, 42), DL, BE);
        ApTrafficObserver.Observation second = row(observer.sample(2_000, 1_034, 99), DL, BE);
        ApTrafficObserver.Observation last = row(observer.sample(2_101, 1_035, 149), DL, BE);
        assertEquals(1_035, first.txNs() + second.txNs() + last.txNs());
        assertEquals(149, first.rxNs() + second.rxNs() + last.rxNs());
        assertEquals(1, last.txNs());
        assertEquals(50, last.rxNs());
        assertEquals(0.0, last.averageDepth());
    }

    @Test
    void invalidSettingsTimeAndTransitionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ApTrafficObserver(0, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new ApTrafficObserver(100, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ApTrafficObserver(100, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ApTrafficObserver(100, Double.POSITIVE_INFINITY));
        ApTrafficObserver observer = new ApTrafficObserver(100, 0.2);
        Packet packet = new Packet(1, 1_000, 0);
        observer.enqueue(packet, 0);
        assertThrows(IllegalStateException.class, () -> observer.enqueue(packet, 0));
        assertThrows(IllegalStateException.class, () -> observer.confirm(packet, 1));
        assertThrows(IllegalArgumentException.class, () -> observer.sample(100, 80, 25));
        assertThrows(IllegalArgumentException.class, () -> observer.sample(101, 80, 20));
        observer.start(packet, 100);
        observer.sample(100, 20, 0);
        assertThrows(IllegalStateException.class, () -> observer.deliver(packet, 100));
        observer.deliver(packet, 101);
        assertThrows(IllegalStateException.class, () -> observer.deliver(packet, 101));
    }

    private static ApTrafficObserver.Observation row(List<ApTrafficObserver.Observation> rows, Packet.Direction direction,
                                                     AccessCategory category) {
        return rows.stream().filter(row -> row.direction() == direction && row.category() == category)
                .findFirst().orElseThrow();
    }
}
