package edu.suda.fttr.channel;

import edu.suda.fttr.channel.ApChannelObservations.LocalReception;
import edu.suda.fttr.channel.ApChannelObservations.Receipt;
import edu.suda.fttr.channel.ApChannelObservations.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChannelObservationTest {
    @Test
    void truthUsesOnlyEffectiveSamplesOfTheRequestedLinkAndSubchannel() {
        ChannelTruth.Change first = new ChannelTruth.Change(1, 1, 0, 500, 24.5, 12, false);
        ChannelTruth.Change changed = new ChannelTruth.Change(1, 1, 0, 750, -4.5, 2, true);
        ChannelTruth.Change secondary = new ChannelTruth.Change(1, 1, 2, 500, 14.0, 7, true);
        ChannelTruth.Change otherSta = new ChannelTruth.Change(1, 2, 0, 500, 10.0, 5, false);
        ChannelTruth.Change otherSfu = new ChannelTruth.Change(2, 3, 0, 500, 18.0, 8, false);
        ChannelTruth truth = new ChannelTruth(List.of(changed, otherSfu, secondary, otherSta, first));

        assertTrue(truth.stateAt(1, 1, 0, 499).isEmpty());
        assertSame(first, truth.stateAt(1, 1, 0, 500).orElseThrow());
        assertSame(first, truth.stateAt(1, 1, 0, 749).orElseThrow());
        assertSame(changed, truth.stateAt(1, 1, 0, 750).orElseThrow());
        assertTrue(changed.interfered());
        assertEquals(-4.5, changed.sinrDb());
        assertSame(secondary, truth.stateAt(1, 1, 2, 900).orElseThrow());
        assertSame(otherSta, truth.stateAt(1, 2, 0, 900).orElseThrow());
        assertSame(otherSfu, truth.stateAt(2, 3, 0, 900).orElseThrow());
        assertTrue(truth.stateAt(2, 3, 1, 900).isEmpty());
        assertEquals(List.of(first, secondary, otherSta, otherSfu, changed), truth.changes());
        assertThrows(UnsupportedOperationException.class, () -> truth.changes().clear());
    }

    @Test
    void rejectsAmbiguousTruthAndInvalidReportFields() {
        ChannelTruth.Change first = new ChannelTruth.Change(1, 1, 0, 0, 25.0, 12, false);
        ChannelTruth.Change sameCoordinate = new ChannelTruth.Change(1, 1, 0, 0, 19.0, 8, true);
        assertThrows(IllegalArgumentException.class, () -> new ChannelTruth(List.of(first, sameCoordinate)));
        assertThrows(IllegalArgumentException.class, () -> new ChannelTruth.Change(1, 1, 0, -1, 24.0, 12, false));
        assertThrows(IllegalArgumentException.class, () -> new ChannelReport(1, 1, 4, 0, 1, 1, 24.0, 12));
        assertThrows(IllegalArgumentException.class, () -> new ChannelReport(1, 1, 0, 3, 1, 1, 24.0, 12));
        assertThrows(IllegalArgumentException.class, () -> new ChannelReport(1, 1, 0, 3, 3, 0, 24.0, 12));
        assertThrows(IllegalArgumentException.class, () -> new ChannelReport(1, 1, 0, 0, 1, 1, Double.NaN, 12));
        assertThrows(IllegalArgumentException.class, () -> new ChannelReport(1, 1, 0, 0, 1, 1, 24.0, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelReport(1, 1, 0, Long.MAX_VALUE, Long.MAX_VALUE, 1, 24.0, 12));
    }

    @Test
    void feedbackRequiresActualReceptionAndExpiresFromMeasurementAtTheExactBoundary() {
        ApChannelObservations ap = new ApChannelObservations(1, Set.of(1, 2));
        ChannelReport report = report(1, 1, 0, 1_000, 2_500, 3_000, 10.0);

        assertEquals(Visibility.UNKNOWN, ap.visibility(1, 0, 4, 2_499));
        assertThrows(IllegalArgumentException.class, () -> ap.receive(report, 2_499));
        assertEquals(Visibility.UNKNOWN, ap.visibility(1, 0, 4, 2_500));
        assertEquals(Receipt.ACCEPTED, ap.receive(report, 2_500));
        assertTrue(ap.latest(1, 0, 1, 2_499).isEmpty());
        assertSame(report, ap.latest(1, 0, 1, 2_500).orElseThrow());
        assertSame(report, ap.latest(1, 0, 1, 3_999).orElseThrow());
        assertEquals(Visibility.EXPIRED, ap.visibility(1, 0, 1, 4_000));
        assertTrue(ap.latest(1, 0, 1, 4_000).isEmpty());
        assertEquals(Visibility.UNKNOWN, ap.visibility(2, 0, 4, 2_500));
        assertEquals(Receipt.EXPIRED, ap.receive(report(1, 2, 0, 3_000, 4_000, 1_000, 7.0), 4_000));
        assertEquals(Visibility.UNKNOWN, ap.visibility(2, 0, 4, 4_000));
    }

    @Test
    void delayedOldMeasurementsCannotReplaceOrResurrectTheLatestValue() {
        ApChannelObservations ap = new ApChannelObservations(1, Set.of(1));
        ChannelReport newest = report(1, 1, 3, 2_000, 2_100, 1_000, 16.0);
        ChannelReport older = report(1, 1, 3, 1_500, 2_200, 5_000, 20.0);
        ChannelReport duplicate = report(1, 1, 3, 2_000, 2_400, 5_000, 25.0);
        ChannelReport oldAfterExpiry = report(1, 1, 3, 1_800, 3_001, 5_000, 14.0);

        assertEquals(Receipt.ACCEPTED, ap.receive(newest, 2_100));
        assertEquals(Receipt.STALE, ap.receive(older, 2_200));
        assertEquals(Receipt.STALE, ap.receive(duplicate, 2_400));
        assertSame(newest, ap.latest(1, 3, 4, 2_900).orElseThrow());
        assertEquals(Receipt.STALE, ap.receive(oldAfterExpiry, 3_001));
        assertTrue(ap.latest(1, 3, 4, 3_001).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ap.receive(duplicate, 2_400));
    }

    @Test
    void limitsSecondaryObservationsAndRejectsForeignReports() {
        ApChannelObservations ap = new ApChannelObservations(1, Set.of(1, 2));
        ApChannelObservations otherAp = new ApChannelObservations(2, Set.of(3));
        ChannelReport secondary = report(1, 1, 1, 0, 200, 1_000, 18.0);
        assertEquals(Receipt.ACCEPTED, ap.receive(secondary, 200));

        assertEquals(Visibility.MODE_LIMIT, ap.visibility(1, 1, 1, 200));
        assertFalse(ap.latest(1, 1, 1, 200).isPresent());
        assertEquals(Visibility.VISIBLE, ap.visibility(1, 1, 2, 200));
        assertSame(secondary, ap.latest(1, 1, 4, 200).orElseThrow());
        assertEquals(Visibility.UNKNOWN, ap.visibility(1, 3, 4, 200));
        assertEquals(Visibility.MODE_LIMIT, ap.visibility(1, 1, 1, 1_000));
        assertEquals(Visibility.UNKNOWN, otherAp.visibility(3, 1, 4, 200));
        assertThrows(IllegalArgumentException.class, () -> otherAp.receive(secondary, 200));
        assertThrows(IllegalArgumentException.class, () -> ap.receive(report(1, 3, 0, 0, 200, 1_000, 10.0), 200));
    }

    @Test
    void localDataAndAcknowledgmentsAreSeparateFromChannelQuality() {
        ApChannelObservations ap = new ApChannelObservations(1, Set.of(1, 2));
        ap.recordLocalReception(1, LocalReception.UL_DATA, 100, 3);
        ap.recordLocalReception(1, LocalReception.DL_ACK, 200, 1);
        ap.recordLocalReception(1, LocalReception.DL_BLOCK_ACK, 300, 0);

        assertEquals(Visibility.UNKNOWN, ap.visibility(1, 0, 1, 400));
        assertTrue(ap.localReception(2, LocalReception.UL_DATA, 100).isEmpty());
        assertTrue(ap.localReception(1, LocalReception.UL_DATA, 99).isEmpty());
        assertEquals(100, ap.localReception(1, LocalReception.UL_DATA, 100).orElseThrow().receivedAtNs());
        assertEquals(3, ap.localReception(1, LocalReception.UL_DATA, 300).orElseThrow().observedMpdus());
        assertEquals(1, ap.localReception(1, LocalReception.DL_ACK, 300).orElseThrow().observedMpdus());
        assertEquals(0, ap.localReception(1, LocalReception.DL_BLOCK_ACK, 300).orElseThrow().observedMpdus());
        assertThrows(IllegalArgumentException.class, () -> ap.recordLocalReception(1, LocalReception.UL_DATA, 99, 1));
        assertThrows(IllegalArgumentException.class, () -> ap.recordLocalReception(1, LocalReception.DL_ACK, 301, 0));
    }

    private static ChannelReport report(int sfuId, int staId, int subchannel, long measuredAtNs, long arrivedAtNs,
                                        long validForNs, double sinrDb) {
        return new ChannelReport(sfuId, staId, subchannel, measuredAtNs, arrivedAtNs,
                validForNs, sinrDb, ChannelReport.UNKNOWN_CQI);
    }
}
