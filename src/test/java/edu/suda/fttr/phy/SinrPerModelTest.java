package edu.suda.fttr.phy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinrPerModelTest {
    private static final int MCS = 7;
    private static final int REFERENCE_BYTES = 1_000;
    private static final List<SinrPerModel.Knot> CURVE = List.of(
            knot(0, 1), knot(10, .5), knot(20, 0));

    @Test
    void interpolatesAndClampsAgainstEachMcsCurve() {
        SinrPerModel model = new SinrPerModel(9, REFERENCE_BYTES,
                Map.of(MCS, CURVE, 0, List.of(knot(0, .2), knot(20, .1))));
        assertEquals(1.0, model.packetErrorRate(MCS, -50, REFERENCE_BYTES));
        assertEquals(.75, model.packetErrorRate(MCS, 5, REFERENCE_BYTES));
        assertEquals(.50, model.packetErrorRate(MCS, 10, REFERENCE_BYTES));
        assertEquals(.25, model.packetErrorRate(MCS, 15, REFERENCE_BYTES));
        assertEquals(0.0, model.packetErrorRate(MCS, 50, REFERENCE_BYTES));
        assertEquals(.15, model.packetErrorRate(0, 10, REFERENCE_BYTES), 1e-15);
        assertTrue(model.supportsMcs(0));
        assertFalse(model.supportsMcs(1));
        assertThrows(IllegalArgumentException.class, () -> model.packetErrorRate(1, 10, REFERENCE_BYTES));
    }

    @Test
    void scalesFailureProbabilityUsingActualMpduLength() {
        SinrPerModel model = model(9);
        assertEquals(1.0 - Math.sqrt(.50), model.packetErrorRate(MCS, 10, 500), 1e-15);
        assertEquals(.75, model.packetErrorRate(MCS, 10, 2_000), 1e-15);
        assertEquals(0.0, model.packetErrorRate(MCS, 20, 500));
        assertEquals(1.0, model.packetErrorRate(MCS, 0, 500));
        SinrPerModel small = new SinrPerModel(9, 1500, Map.of(MCS, List.of(knot(0, 1e-20), knot(20, 1e-20))));
        assertEquals(2e-20, small.packetErrorRate(MCS, 10, 3_000), 1e-30);
    }

    @Test
    void repeatsWithTheSameSeedAndSeparatesDifferentLinksMembersAndRetries() {
        SinrPerModel model = model(101);
        SinrPerModel.Decision initial = model.evaluate(MCS, 10, REFERENCE_BYTES, 1, 1, 1, 0);
        assertTrue(initial.draw() >= 0 && initial.draw() < 1);
        assertNotEquals(initial.draw(), model.evaluate(MCS, 10, REFERENCE_BYTES, 1, 1, 2, 0).draw());
        assertNotEquals(initial.draw(), model.evaluate(MCS, 10, REFERENCE_BYTES, 1, 1, 1, 1).draw());
        assertNotEquals(initial.draw(), model.evaluate(MCS, 10, REFERENCE_BYTES, 2, 1, 1, 0).draw());
        assertNotEquals(initial.draw(), model.evaluate(MCS, 10, REFERENCE_BYTES, 1, 2, 1, 0).draw());
        assertNotEquals(initial.draw(), model(102).evaluate(MCS, 10, REFERENCE_BYTES, 1, 1, 1, 0).draw());
        assertEquals(initial.draw(), model(101).evaluate(MCS, 10, REFERENCE_BYTES, 1, 1, 1, 0).draw());
        assertEquals(initial.draw(), model.evaluate(MCS, 10, REFERENCE_BYTES, 1, 1, 1, 0).draw());
        assertEquals(initial.draw() >= initial.per(), initial.decoded());
    }

    @Test
    void givesPartialDecodeAtIntermediateSinrButCertainOutcomesAtExtremeKnots() {
        SinrPerModel model = model(101);
        long decoded = IntStream.rangeClosed(1, 16)
                .filter(packet -> model.evaluate(MCS, 10, REFERENCE_BYTES, 1, 1, packet, 0).decoded())
                .count();
        assertTrue(decoded > 0 && decoded < 16);
        assertFalse(model.evaluate(MCS, 0, REFERENCE_BYTES, 1, 1, 1, 0).decoded());
        assertTrue(model.evaluate(MCS, 20, REFERENCE_BYTES, 1, 1, 1, 0).decoded());
    }

    @Test
    void rejectsIllegalCurveDefinitionsAndLookupInputs() {
        assertThrows(IllegalArgumentException.class, () -> knot(Double.NaN, .5));
        assertThrows(IllegalArgumentException.class, () -> knot(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> knot(0, 1.1));
        assertThrows(IllegalArgumentException.class, () -> new SinrPerModel(9, 0, Map.of(MCS, CURVE)));
        assertThrows(IllegalArgumentException.class, () -> new SinrPerModel(9, REFERENCE_BYTES, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SinrPerModel(9, REFERENCE_BYTES, Map.of(12, CURVE)));
        assertThrows(IllegalArgumentException.class,
                () -> new SinrPerModel(9, REFERENCE_BYTES, Map.of(MCS, List.of(knot(0, .5)))));
        assertThrows(IllegalArgumentException.class,
                () -> new SinrPerModel(9, REFERENCE_BYTES, Map.of(MCS, List.of(knot(0, .5), knot(0, .4)))));
        assertThrows(IllegalArgumentException.class,
                () -> new SinrPerModel(9, REFERENCE_BYTES, Map.of(MCS, List.of(knot(0, .5), knot(1, .6)))));

        SinrPerModel model = model(9);
        assertThrows(IllegalArgumentException.class, () -> model.packetErrorRate(MCS, Double.POSITIVE_INFINITY, 20));
        assertThrows(IllegalArgumentException.class, () -> model.packetErrorRate(MCS, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(MCS, 1, 20, 1, 1, 1, -1));
    }

    @Test
    void retainsImmutableCurveSnapshot() {
        List<SinrPerModel.Knot> knots = new ArrayList<>(CURVE);
        Map<Integer, List<SinrPerModel.Knot>> curves = new HashMap<>();
        curves.put(MCS, knots);
        SinrPerModel model = new SinrPerModel(9, REFERENCE_BYTES, curves);
        knots.clear();
        curves.clear();
        assertEquals(.5, model.packetErrorRate(MCS, 10, REFERENCE_BYTES));
    }

    private static SinrPerModel model(long seed) {
        return new SinrPerModel(seed, REFERENCE_BYTES, Map.of(MCS, CURVE));
    }

    private static SinrPerModel.Knot knot(double sinrDb, double per) {
        return new SinrPerModel.Knot(sinrDb, per);
    }
}
