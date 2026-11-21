package edu.suda.fttr.simulator;

import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 通过生产计划校验器验证合法预算和拒绝边界的测试数据。 */
final class DbaPlanValidationCases {
    private static final long FRAME_NS = 125_000;

    private DbaPlanValidationCases() { }

    static List<Outcome> runCases() {
        List<Outcome> results = new ArrayList<>();
        DbaContext ordinary = context(205, allocation(2, 1, 6_000));
        DbaContext capacity = context(205, allocation(2, 1, 50_000));
        evaluate(results, "normal_6000", ordinary, plan(grant(2, 1, 6_000, 19_200)), true);
        evaluate(results, "capacity_38998", capacity, plan(grant(2, 1, 38_998, 124_794)), true);
        evaluate(results, "invalid_allocation", ordinary, plan(grant(99, 1, 1_200, 3_840)), false);
        evaluate(results, "mapping_mismatch", ordinary, plan(grant(2, 2, 1_200, 3_840)), false);
        evaluate(results, "negative_bytes", ordinary, plan(grant(2, 1, -1, 0)), false);
        evaluate(results, "negative_duration", ordinary, plan(grant(2, 1, 1_200, -1)), false);
        evaluate(results, "demand_exceeded", ordinary, plan(grant(2, 1, 6_001, 19_204)), false);
        evaluate(results, "unknown_positive", context(205,
                new DbaContext.Allocation(2, 1, null, null, null)),
                plan(grant(2, 1, 1_200, 3_840)), false);
        evaluate(results, "frame_capacity_exceeded", capacity,
                plan(grant(2, 1, 38_999, 124_797)), false);
        evaluate(results, "duplicate_allocation", ordinary,
                plan(grant(2, 1, 1_200, 3_840), grant(2, 1, 1_200, 3_840)), false);
        evaluate(results, "duration_mismatch", ordinary, plan(grant(2, 1, 1_200, 3_839)), false);
        evaluate(results, "zero_bytes_nonzero_duration", ordinary, plan(grant(2, 1, 0, 1)), false);
        evaluate(results, "target_frame_mismatch", ordinary,
                new DbaPlan(125_000, 3, List.of(grant(2, 1, 1_200, 3_840)), "TEST"), false);
        evaluate(results, "decision_time_mismatch", ordinary,
                new DbaPlan(125_001, 2, List.of(grant(2, 1, 1_200, 3_840)), "TEST"), false);
        evaluate(results, "two_allocations_nonoverlap", context(205,
                allocation(2, 1, 1_200), allocation(4, 2, 2_400)),
                plan(grant(2, 1, 1_200, 3_840), grant(4, 2, 2_400, 7_680)), true);
        evaluate(results, "explicit_overlapping_windows", context(205,
                allocation(2, 1, 1_200), allocation(4, 2, 2_400)),
                plan(new DbaPlan.Grant(2, 1, 1_200, 3_840, 205L),
                        new DbaPlan.Grant(4, 2, 2_400, 7_680, 205L)), false);
        evaluate(results, "explicit_window_outside_frame", ordinary,
                plan(new DbaPlan.Grant(2, 1, 1_200, 3_840, 124_000L)), false);
        // allocationId 与 SFU ID 是两层身份；未来一台 SFU 的多个对象同样可以预览。
        evaluate(results, "same_sfu_two_allocations", context(205,
                allocation(101, 1, 1_200), allocation(102, 1, 2_400)),
                plan(grant(101, 1, 1_200, 3_840), grant(102, 1, 2_400, 7_680)), true);
        evaluate(results, "empty_plan", ordinary, plan(), true);
        evaluate(results, "zero_budget", ordinary, plan(grant(2, 1, 0, 0)), true);
        return List.copyOf(results);
    }

    private static void evaluate(List<Outcome> results, String id, DbaContext context,
                                 DbaPlan plan, boolean expectedAccepted) {
        String expected = expectedAccepted ? "ACCEPTED" : "REJECTED";
        String actual;
        String reason;
        try {
            List<DbaPlanValidator.Slot> slots = new DbaPlanValidator().validateAndPreview(context, plan);
            actual = "ACCEPTED";
            reason = "VALID: read-only preview slots=" + slots.size();
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException exception) {
            actual = "REJECTED";
            reason = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        results.add(new Outcome(id, expected, actual, reason));
    }

    private static DbaContext context(long controlNs, DbaContext.Allocation... allocations) {
        return new DbaContext(125_000, 2, BigDecimal.valueOf(2500), FRAME_NS, controlNs,
                List.of(allocations));
    }

    private static DbaContext.Allocation allocation(long id, int sfuId, long bytes) {
        return new DbaContext.Allocation(id, sfuId, bytes, 1L, 0L);
    }

    private static DbaPlan plan(DbaPlan.Grant... grants) {
        return new DbaPlan(125_000, 2, List.of(grants), "TEST");
    }

    private static DbaPlan.Grant grant(long id, int sfuId, long bytes, long durationNs) {
        return new DbaPlan.Grant(id, sfuId, bytes, durationNs);
    }

    static final class Outcome {
        private final String id;
        private final String expected;
        private final String actual;
        private final String reason;

        private Outcome(String id, String expected, String actual, String reason) {
            this.id = id;
            this.expected = expected;
            this.actual = actual;
            this.reason = reason;
        }

        boolean matchesExpectation() {
            return expected.equals(actual);
        }

        String diagnostic() {
            return id + ": expected " + expected + ", got " + actual + " (" + reason + ")";
        }
    }
}
