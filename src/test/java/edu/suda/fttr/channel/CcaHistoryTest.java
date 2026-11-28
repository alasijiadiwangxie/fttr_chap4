package edu.suda.fttr.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CcaHistoryTest {
    @ParameterizedTest
    @CsvSource({"5000000,1500000", "10000000,2250000", "20000000,3750000"})
    void urgentQueriesUseFullWindowsWithoutResettingThem(long window, long expectedBusyNs) {
        CcaHistory history = new CcaHistory(window, 15);
        for (int pulse = 15; pulse <= 35; pulse += 4) {
            long start = pulse * 1_000_000L + 200_000;
            history.listen(start, 15, 8);
            history.listen(start + 750_000, 15, 0);
        }
        // E0 35.984 ms: 750 us / 984 us gave 76.22%. A fixed 5 ms window here includes two pulses.
        var first = history.sample(35_984_000);
        assertEquals(expectedBusyNs, first.wide.busyNs);
        assertEquals(window, first.wide.knownNs);
        assertEquals((double) expectedBusyNs / window, first.wide.expected(), 1e-12);
        assertEquals(expectedBusyNs, history.sample(36_010_000).wide.busyNs);
        assertEquals(0, first.primary.busyNs);
        assertFalse(first.wide.incomplete());
    }

    @Test
    void superposesVisiblePassiveAndScanIntervalsUsingTheirUnion() {
        CcaHistory history = new CcaHistory(5_000_000, 15);
        history.listen(1_200_000, 15, 2);
        history.listen(1_500_000, 15, 10);
        history.listen(1_800_000, 15, 8);
        history.listen(2_000_000, 1, 0);
        history.completeScan(3, 1_400_000, 2_400_000, 8_000_000,
                List.of(new CcaHistory.BusyInterval(1_500_000, 2_150_000)));
        var sample = history.sample(5_000_000);
        assertEquals(950_000, sample.wide.busyNs);
        assertEquals(2_150_000, sample.wide.knownNs);
        assertEquals(2_000_000, sample.wide.fullySensedNs);
        assertEquals(5_000_000, sample.primary.knownNs);
        assertEquals(CcaHistory.Current.UNKNOWN, sample.wide.current);
        assertEquals(.3325, sample.wide.expected(), 1e-12);
        assertTrue(sample.wide.incomplete());
    }

    @Test
    void scanOnlyCoversTheMeasuredBandAfterCompletionAndBeforeExpiry() {
        CcaHistory history = new CcaHistory(5_000_000, 1);
        history.completeScan(3, 1_000_000, 2_000_000, 4_000_000,
                List.of(new CcaHistory.BusyInterval(1_500_000, 1_750_000)));
        assertEquals(0, history.sample(1_900_000).wide.knownNs);
        var received = history.sample(2_000_000).wide;
        assertEquals(250_000, received.busyNs);
        assertEquals(250_000, received.knownNs);
        assertEquals(250_000, received.ageNs);
        assertEquals(0, history.sample(4_000_000).wide.knownNs);
    }

    @Test
    void separateRecentScanMeasurementsDoNotInventJointCoverageOrPreventComparisonForever() {
        CcaHistory history = new CcaHistory(5_000_000, 1);
        history.completeScan(1, 3_100_000, 3_500_000, 9_500_000, List.of());
        history.completeScan(2, 3_530_000, 3_930_000, 9_930_000, List.of());
        history.completeScan(3, 3_960_000, 4_360_000, 10_360_000, List.of());
        var valid = history.sample(5_000_000).wide;
        assertEquals(0, valid.knownNs);
        assertEquals(.25, valid.expected(), 1e-12);
        assertEquals(.50, valid.conservative(), 1e-12);
        assertEquals(1_500_000, valid.oldestBandAgeNs);
        assertTrue(valid.incomplete());
        assertFalse(valid.missingRecentBand());
        assertTrue(history.sample(6_500_000).wide.missingRecentBand());
    }

    @Test
    void separatesCurrentBusyRunFromWindowOccupancyAndUnknownWarmup() {
        CcaHistory history = new CcaHistory(5_000_000, 1);
        history.listen(700_000, 1, 1);
        var sample = history.sample(1_000_000);
        assertEquals(CcaHistory.Current.BUSY, sample.wide.current);
        assertEquals(300_000, sample.wide.ongoingBusyNs);
        assertEquals(300_000, sample.wide.longestBusyNs);
        assertEquals(1_000_000, sample.primary.knownNs);
        assertTrue(sample.primary.incomplete());
        history.listen(1_100_000, 0, 0); // B4 的不可侦听空档。
        var off = history.sample(1_200_000).primary;
        assertEquals(CcaHistory.Current.UNKNOWN, off.current);
        assertEquals(0, off.ongoingBusyNs);
        assertEquals(400_000, off.longestBusyNs);
        assertEquals(100_000, off.ageNs);
    }
}
