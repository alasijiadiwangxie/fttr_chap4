package edu.suda.fttr.simulator;

import edu.suda.fttr.dba.DbaContext;
import edu.suda.fttr.dba.DbaPlan;
import edu.suda.fttr.dba.DbaPlanValidator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 10 步专项复核入口。每一行都实际调用生产计划校验器，CSV 不预填实际结果。
 * 此类只存在于 test-classes，不参与仿真器的协议执行。
 */
public final class DbaPlanValidationCases {
    private static final long FRAME_NS = 125_000;

    private DbaPlanValidationCases() { }

    /** 参数是输出 CSV 的完整路径；有任何预期不一致时，进程以异常结束。 */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: DbaPlanValidationCases <output-csv-path>");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        List<String> lines = new ArrayList<>();
        lines.add("scenario_id,target_frame_id,proposed_bytes,control_reserved_ns,planned_data_ns,"
                + "available_ns,expected_result,actual_result,validation_reason");
        List<Outcome> outcomes = runCases();
        for (Outcome outcome : outcomes) {
            lines.add(outcome.csv());
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
        long failures = outcomes.stream().filter(outcome -> !outcome.matchesExpectation()).count();
        System.out.println("DBA validation scenarios=" + outcomes.size() + ", mismatches=" + failures
                + ", csv=" + output);
        if (failures != 0) {
            throw new IllegalStateException("DBA validation CSV contains " + failures + " unexpected outcomes");
        }
    }

    /** 与单元测试复用同一组实际执行的边界及错误用例。 */
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
        long proposedBytes = 0;
        long duration = 0;
        for (DbaPlan.Grant grant : plan.grants()) {
            proposedBytes += grant.plannedBytes();
            duration += grant.plannedDurationNs();
        }
        results.add(new Outcome(id, plan.targetFrameId(), proposedBytes, context.controlReservedNs(),
                duration, context.availableNs(), expected, actual, reason));
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
        private final long targetFrame;
        private final long bytes;
        private final long controlNs;
        private final long dataNs;
        private final long availableNs;
        private final String expected;
        private final String actual;
        private final String reason;

        private Outcome(String id, long targetFrame, long bytes, long controlNs, long dataNs,
                        long availableNs, String expected, String actual, String reason) {
            this.id = id;
            this.targetFrame = targetFrame;
            this.bytes = bytes;
            this.controlNs = controlNs;
            this.dataNs = dataNs;
            this.availableNs = availableNs;
            this.expected = expected;
            this.actual = actual;
            this.reason = reason;
        }

        boolean matchesExpectation() {
            return expected.equals(actual);
        }

        String id() {
            return id;
        }

        private String csv() {
            return String.join(",", id, Long.toString(targetFrame), Long.toString(bytes),
                    Long.toString(controlNs), Long.toString(dataNs), Long.toString(availableNs),
                    expected, actual, quote(reason));
        }

        private static String quote(String text) {
            return "\"" + text.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\"";
        }
    }
}
