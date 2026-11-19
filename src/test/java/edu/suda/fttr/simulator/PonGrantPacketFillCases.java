package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.PonGrantPacketFiller;
import edu.suda.fttr.link.PonUplink;
import edu.suda.fttr.trace.TraceLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 逐场景运行生产装载组件并导出实测结果。该入口仅存在于 test-classes，
 * 不向生产仿真注册事件，不把本地预演冒充实际 DATA 授权。
 */
public final class PonGrantPacketFillCases {
    private static final BigDecimal RATE = BigDecimal.valueOf(2500);
    private static final long FRAME_NS = 125_000;
    private static final Map<Long, Integer> OWNERS = Map.of(2L, 1, 4L, 2);

    private PonGrantPacketFillCases() { }

    /** 参数为输出目录；两份 CSV 和配置文件全部由本 Java 入口生成。 */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: PonGrantPacketFillCases <output-directory>");
        }
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        List<String> traces = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        List<String> config = new ArrayList<>(List.of(
                "# Actual Java test inputs; manual deterministic cases, no random seed.",
                "# These are local read-only previews, not real DBA DATA transmissions.",
                "fiber.uplink.rate.mbps=" + RATE.toPlainString(),
                "fiber.uplink.frame.period.us=125",
                "fiber.uplink.frame.period.ns=" + FRAME_NS,
                "allocation.2.sfu.id=1", "allocation.4.sfu.id=2",
                "preview.mode=read-only", "random.seed=not-used"));
        traces.add("scenario_id,allocation_id,sfu_id,grant_bytes,grant_duration_ns,selected_packet_ids,"
                + "selected_bytes,selected_duration_ns,unused_bytes,unused_duration_ns,fill_reason,"
                + "rate_mbps,snapshot_time_ns,slot_start_ns,remaining_packet_count,status");
        checks.add("scenario_id,packet_id,packet_bytes,packet_duration_ns,fifo_position,selected,reject_reason");

        List<Scenario> scenarios = scenarios();
        int rejected = 0;
        for (Scenario scenario : scenarios) {
            saveInputs(config, scenario);
            PonGrantPacketFiller filler = new PonGrantPacketFiller(RATE, OWNERS);
            try {
                PonGrantPacketFiller.Result result = filler.fill(scenario.allocationId, scenario.sfuId,
                        scenario.grantBytes, scenario.grantNs, scenario.slotStartNs, scenario.view);
                require(!scenario.expectedRejected, "Expected invalid mapping to be rejected: " + scenario.id);
                require(result.selectedPacketIds().equals(scenario.expectedIds),
                        "Unexpected selected IDs in " + scenario.id + ": " + result.selectedPacketIds());
                // 同一输入重复调用不能消耗预算或改变输入。比较完整汇总和逐候选记录。
                PonGrantPacketFiller.Result repeat = filler.fill(scenario.allocationId, scenario.sfuId,
                        scenario.grantBytes, scenario.grantNs, scenario.slotStartNs, scenario.view);
                require(trace(scenario, result).equals(trace(scenario, repeat)),
                        "Repeated fill differs: " + scenario.id);
                require(packetChecks(scenario, result).equals(packetChecks(scenario, repeat)),
                        "Repeated packet checks differ: " + scenario.id);
                traces.add(trace(scenario, result));
                checks.addAll(packetChecks(scenario, result));
            } catch (IllegalArgumentException exception) {
                require(scenario.expectedRejected, "Unexpected rejection in " + scenario.id + ": " + exception);
                // 校验抛出异常时并未产生 Result，因此选择量留空，记录实际错误而非虚构零选择结果。
                traces.add(scenario.id + "," + scenario.allocationId + "," + scenario.sfuId + ","
                        + scenario.grantBytes + "," + scenario.grantNs + ",,,,,,"
                        + quote(exception.getClass().getSimpleName() + ": " + exception.getMessage())
                        + "," + RATE.toPlainString() + "," + scenario.snapshotNs + ","
                        + scenario.slotStartNs + ",,REJECTED");
                rejected++;
            }
        }
        Files.write(directory.resolve("grant-fill-trace.csv"), traces, StandardCharsets.UTF_8);
        Files.write(directory.resolve("packet-selection-check.csv"), checks, StandardCharsets.UTF_8);
        Files.write(directory.resolve("test-config.properties"), config, StandardCharsets.UTF_8);
        System.out.println("Grant fill scenarios=" + scenarios.size() + ", accepted="
                + (scenarios.size() - rejected) + ", rejected=" + rejected + ", packet_checks="
                + (checks.size() - 1) + ", directory=" + directory);
    }

    private static List<Scenario> scenarios() {
        List<Scenario> cases = new ArrayList<>();
        cases.add(scenario("three_of_four_1200", 2, 1, 3600, 11520, 0, 250000,
                List.of(packet(1, 1200, 0), packet(2, 1200, 0), packet(3, 1200, 0), packet(4, 1200, 0)),
                List.of(1, 2, 3)));
        cases.add(scenario("partial_1500", 2, 1, 1500, 4800, 0, 250000,
                List.of(packet(11, 1200, 0), packet(12, 1200, 0)), List.of(11)));
        cases.add(scenario("head_39062", 2, 1, 38998, 124794, 0, 250000,
                List.of(packet(21, 39062, 0)), List.of()));
        cases.add(scenario("fifo_no_skip", 2, 1, 38998, 124794, 0, 250000,
                List.of(packet(31, 39062, 0), packet(32, 1200, 0)), List.of()));
        cases.add(scenario("empty_queue", 2, 1, 3600, 11520, 0, 250000, List.of(), List.of()));
        // 1 B 在 2.5 Gbps 下理论为3.2 ns，逐包上取整为4 ns；两包不能装入聚合7 ns预算。
        cases.add(scenario("per_packet_ns_rounding", 2, 1, 2, 7, 0, 250000,
                List.of(packet(41, 1, 0), packet(42, 1, 0)), List.of(41)));
        cases.add(scenario("independent_sfu_1", 2, 1, 2400, 7680, 0, 250000,
                List.of(packet(101, 1200, 0), packet(102, 1200, 0)), List.of(101, 102)));
        cases.add(scenario("independent_sfu_2", 4, 2, 1200, 3840, 0, 250000,
                List.of(packet(201, 1200, 0), packet(202, 1200, 0)), List.of(201)));
        cases.add(scenario("future_packet_not_arrived", 2, 1, 1200, 3840, 100, 1000,
                List.of(packet(51, 1200, 500)), List.of()));
        cases.add(scenario("same_time_already_enqueued", 2, 1, 1200, 3840, 100, 1000,
                List.of(packet(61, 1200, 100)), List.of(61)));
        cases.add(scenario("queue_drained_with_budget_left", 2, 1, 3600, 11520, 0, 250000,
                List.of(packet(71, 1200, 0)), List.of(71)));
        cases.add(scenario("zero_budget", 2, 1, 0, 0, 0, 250000,
                List.of(packet(81, 1200, 0)), List.of()));
        Scenario first = scenario("repeat_preview_first", 2, 1, 1500, 4800, 0, 250000,
                List.of(packet(91, 1200, 0), packet(92, 1200, 0)), List.of(91));
        cases.add(first);
        cases.add(new Scenario("repeat_preview_second", first.allocationId, first.sfuId, first.grantBytes,
                first.grantNs, first.snapshotNs, first.slotStartNs, first.view, first.expectedIds, false));
        cases.add(reservedExcluded());
        cases.add(new Scenario("invalid_allocation", 99, 1, 1200, 3840, 0, 250000,
                new PonGrantPacketFiller.QueueView(1, 0, List.of(packet(301, 1200, 0))), List.of(), true));
        cases.add(new Scenario("allocation_sfu_mismatch", 2, 2, 1200, 3840, 0, 250000,
                new PonGrantPacketFiller.QueueView(2, 0, List.of(packet(302, 1200, 0))), List.of(), true));
        return List.copyOf(cases);
    }

    /** 真实 FCFS 已预约的前32包不能再进入本地未授权视图；只有第33包仍可预演。 */
    private static Scenario reservedExcluded() {
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(output), true, true);
            PonUplink uplink = new PonUplink(RATE, 10025, FRAME_NS, true, 10000);
            PonUplink.Port port = uplink.attachSfu(1);
            for (int id = 1; id <= 33; id++) {
                port.enqueue(new Packet(id, 1200, 0, AccessCategory.BE,
                        Packet.Direction.UL, 1, Packet.DEFAULT_MFU_ID), context);
            }
            Event frame = context.nextEvent();
            require(frame != null && frame.type() == EventType.PON_UPLINK_FRAME
                    && context.nowTicks() == FRAME_NS, "Expected first real FCFS frame boundary");
            uplink.scheduleFrame(context);
            PonGrantPacketFiller.QueueView view = port.ungrantedPacketView(context);
            return new Scenario("fcfs_reserved_packets_excluded", 2, 1, 1200, 3840,
                    FRAME_NS, 375000, view, List.of(33), false);
        }
    }

    private static Scenario scenario(String id, long allocation, int sfu, long bytes, long duration,
                                     long snapshot, long start, List<PonGrantPacketFiller.PacketView> packets,
                                     List<Integer> expectedIds) {
        return new Scenario(id, allocation, sfu, bytes, duration, snapshot, start,
                new PonGrantPacketFiller.QueueView(sfu, snapshot, packets), expectedIds, false);
    }

    private static PonGrantPacketFiller.PacketView packet(int id, int bytes, long enqueueNs) {
        return new PonGrantPacketFiller.PacketView(id, bytes, enqueueNs);
    }

    private static String trace(Scenario scenario, PonGrantPacketFiller.Result result) {
        String ids = result.selectedPacketIds().stream().map(String::valueOf).collect(Collectors.joining(";"));
        return String.join(",", scenario.id, Long.toString(scenario.allocationId), Integer.toString(scenario.sfuId),
                Long.toString(scenario.grantBytes), Long.toString(scenario.grantNs), ids,
                Long.toString(result.selectedBytes()), Long.toString(result.selectedDurationNs()),
                Long.toString(result.unusedBytes()), Long.toString(result.unusedDurationNs()),
                result.fillReason().name(), RATE.toPlainString(), Long.toString(scenario.snapshotNs),
                Long.toString(scenario.slotStartNs), Integer.toString(result.remainingPacketCount()), "PREVIEW");
    }

    private static List<String> packetChecks(Scenario scenario, PonGrantPacketFiller.Result result) {
        List<String> rows = new ArrayList<>();
        for (PonGrantPacketFiller.PacketCheck check : result.packetChecks()) {
            rows.add(String.join(",", scenario.id, Integer.toString(check.packetId()),
                    Integer.toString(check.packetBytes()), Long.toString(check.packetDurationNs()),
                    Integer.toString(check.fifoPosition()), Boolean.toString(check.selected()), quote(check.rejectReason())));
        }
        return rows;
    }

    private static void saveInputs(List<String> config, Scenario scenario) {
        String prefix = "scenario." + scenario.id + ".";
        config.add("");
        config.add(prefix + "allocation.id=" + scenario.allocationId);
        config.add(prefix + "sfu.id=" + scenario.sfuId);
        config.add(prefix + "rate.mbps=" + RATE.toPlainString());
        config.add(prefix + "grant.bytes=" + scenario.grantBytes);
        config.add(prefix + "grant.duration.ns=" + scenario.grantNs);
        config.add(prefix + "snapshot.time.ns=" + scenario.snapshotNs);
        config.add(prefix + "slot.start.ns=" + scenario.slotStartNs);
        config.add(prefix + "packets=" + scenario.view.packets().size());
        int index = 0;
        for (PonGrantPacketFiller.PacketView packet : scenario.view.packets()) {
            String packetPrefix = prefix + "packet." + (++index) + ".";
            config.add(packetPrefix + "id=" + packet.packetId());
            config.add(packetPrefix + "bytes=" + packet.packetBytes());
            config.add(packetPrefix + "enqueue.ns=" + packet.enqueueTimeNs());
        }
        if (scenario.id.equals("fcfs_reserved_packets_excluded")) {
            config.add(prefix + "original.enqueued.packets=33");
            config.add(prefix + "original.packet.bytes=1200");
            config.add(prefix + "original.enqueue.ns=0");
            config.add(prefix + "fcfs.reserved.packet.ids=1-32");
            config.add(prefix + "bwmap.propagation.ns=10000");
            config.add(prefix + "uplink.propagation.ns=10025");
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\"";
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class Scenario {
        private final String id;
        private final long allocationId;
        private final int sfuId;
        private final long grantBytes;
        private final long grantNs;
        private final long snapshotNs;
        private final long slotStartNs;
        private final PonGrantPacketFiller.QueueView view;
        private final List<Integer> expectedIds;
        private final boolean expectedRejected;

        private Scenario(String id, long allocationId, int sfuId, long grantBytes, long grantNs,
                         long snapshotNs, long slotStartNs, PonGrantPacketFiller.QueueView view,
                         List<Integer> expectedIds, boolean expectedRejected) {
            this.id = id;
            this.allocationId = allocationId;
            this.sfuId = sfuId;
            this.grantBytes = grantBytes;
            this.grantNs = grantNs;
            this.snapshotNs = snapshotNs;
            this.slotStartNs = slotStartNs;
            this.view = view;
            this.expectedIds = List.copyOf(expectedIds);
            this.expectedRejected = expectedRejected;
        }
    }
}
