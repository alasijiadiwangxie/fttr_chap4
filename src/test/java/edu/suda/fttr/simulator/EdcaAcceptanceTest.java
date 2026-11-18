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
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaParameters.AcParameters;
import edu.suda.fttr.mac.WifiAccessMode;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdcaAcceptanceTest {
    @Test
    void undecodableCollisionNeverUpdatesNavBeforeItsSuccessfulRetry() {
        EdcaParameters defaults = EdcaParameters.defaults();
        EdcaParameters parameters = new EdcaParameters(16, 9, Map.of(
                AccessCategory.VO, new AcParameters(2, 0, 7),
                AccessCategory.VI, defaults.forCategory(AccessCategory.VI),
                AccessCategory.BE, defaults.forCategory(AccessCategory.BE),
                AccessCategory.BK, defaults.forCategory(AccessCategory.BK)), 1);
        Sta first = new Sta(1, 1, parameters);
        Sta second = new Sta(2, 1, parameters);
        Sta observer = new Sta(3, 1, parameters);
        Sfu sfu = new Sfu(1, new WifiLink(new WirelessPhy(160, 11, 2, 800, 34),
                new WifiFrameExchange(16, 24)), Map.of(1, first, 2, second, 3, observer), parameters,
                WifiAccessMode.EDCA_MULTI_AC, new WifiRetryPolicy(53, 3));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            SimulationContext context = new SimulationContext(new TraceLogger(output), true, true, true);
            sfu.enqueueForWifi(new Packet(1, 1500, 0, AccessCategory.VO, Packet.Direction.UL, 1, 1), context);
            sfu.enqueueForWifi(new Packet(2, 1500, 0, AccessCategory.VO, Packet.Direction.UL, 2, 1), context);
            int failedData = 0;
            int successfulData = 0;
            Event event;
            while ((event = context.nextEvent()) != null) {
                Packet packet = event.packet();
                switch (event.type()) {
                    case WIFI_ACCESS_TIMER: sfu.advanceAccess(event); break;
                    case WIFI_ACCESS_RESOLVE: sfu.resolveAccess(event, context); break;
                    case WIFI_TX_START: sfu.transmit(packet, context); break;
                    case WIFI_COLLISION_DATA_END:
                        sfu.finishCollidedData(packet, context);
                        failedData++;
                        // 碰撞帧连发送端之外的 SFU 和旁听 STA 也不能更新 NAV。
                        for (int device = 0; device <= 3; device++) {
                            assertEquals(0, sfu.navUntilTicks(device));
                        }
                        if (failedData == 2) {
                            assertEquals(WifiChannel.State.IDLE, sfu.ccaState());
                        }
                        break;
                    case WIFI_ACK_TIMEOUT:
                        sfu.onAckTimeout(packet, context);
                        if (successfulData == 0) {
                            for (int device = 0; device <= 3; device++) {
                                assertEquals(0, sfu.navUntilTicks(device));
                            }
                        }
                        break;
                    case WIFI_ARRIVAL_AT_SFU:
                        sfu.receiveData(packet, context);
                        successfulData++;
                        assertEquals(0, sfu.navUntilTicks(0)); // 上行 DATA 的寻址接收者。
                        assertEquals(context.nowTicks() + 44_000, sfu.navUntilTicks(3));
                        break;
                    case WIFI_ACK_START: sfu.transmitAck(packet, context); break;
                    case WIFI_ACK_END: sfu.completeTransmission(packet, context); break;
                    case FIBER_TX_START: break; // 本用例只逐事件驱动无线一跳。
                    default: throw new AssertionError("Unexpected event " + event.type());
                }
            }
            assertEquals(2, failedData);
            assertEquals(2, successfulData);
            assertEquals(WifiChannel.State.IDLE, sfu.ccaState());
        }
        List<Map<String, String>> trace = rows(buffer.toString(StandardCharsets.UTF_8));
        assertEquals(2, events(trace, "WIFI_MAC_SUCCESS").size());
        assertTrue(events(trace, "WIFI_RETRY_LIMIT_DROP").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 17, 73})
    void conservesMixedTrafficAndExclusiveChannelIntervals(long seed, @TempDir Path directory) throws IOException {
        int count = 96;
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=7", "packet.bytes=1500",
                "traffic.direction=BOTH", "sfu.count=2", "sta.count=4", "sta.2.sfu.id=1", "sta.3.sfu.id=2",
                "sta.4.sfu.id=2", "wifi.mac.access=edca-multi-ac", "wifi.mac.exchange=data-sifs-ack",
                "wifi.mac.edca.seed=" + seed, "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.txop.limit.us=232",
                "wifi.mac.edca.VI.txop.limit.us=232"));
        List<String> arrivals = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            arrivals.add(Integer.toString(i / 8 * 5));
            String prefix = "packet." + (i + 1) + ".";
            properties.add(prefix + "sta.id=" + (i % 4 + 1));
            properties.add(prefix + "direction=" + (i / 4 % 2 == 0 ? "UL" : "DL"));
            properties.add(prefix + "type=" + AccessCategory.values()[i / 8 % 4]);
        }
        properties.add("packet.arrival.us=" + String.join(",", arrivals));
        SimulationConfig config = ConfigLoader.load(Files.write(directory.resolve("acceptance.properties"), properties,
                StandardCharsets.UTF_8));
        String output = run(config);
        assertEquals(output, run(config));
        List<Map<String, String>> trace = rows(output);
        assertEquals(count, events(trace, "ARRIVAL").stream().filter(row -> !row.containsKey("sfu_id")).count());
        Set<Integer> delivered = uniquePacketIds(events(trace, "DELIVERED"));
        Set<Integer> dropped = uniquePacketIds(events(trace, "WIFI_RETRY_LIMIT_DROP"));
        assertFalse(delivered.isEmpty());
        Set<Integer> both = new HashSet<>(delivered);
        both.retainAll(dropped);
        assertTrue(both.isEmpty());
        assertEquals(count, delivered.size() + dropped.size());
        assertEquals(delivered, uniquePacketIds(events(trace, "WIFI_MAC_SUCCESS")));

        // 把 TXOP 内的单帧区间合并为整段预约，再加真实碰撞区间核对全局忙时。
        List<Interval> txops = paired(trace, "WIFI_TXOP_START", "WIFI_TXOP_END", false);
        assertFalse(txops.isEmpty());
        List<Interval> frames = paired(trace, "WIFI_TX_START", "WIFI_ACK_END", false);
        List<Interval> occupied = new ArrayList<>(txops);
        for (Interval frame : frames) {
            if (txops.stream().noneMatch(txop -> txop.contains(frame))) {
                occupied.add(frame);
            }
        }
        occupied.addAll(paired(trace, "WIFI_COLLISION_START", "WIFI_COLLISION_END", true));
        assertFalse(occupied.isEmpty());
        for (int sfu = 1; sfu <= 2; sfu++) {
            int id = sfu;
            List<Interval> local = occupied.stream().filter(interval -> interval.sfu == id)
                    .sorted(Comparator.comparingLong(interval -> interval.start)).collect(Collectors.toList());
            for (int i = 1; i < local.size(); i++) {
                assertTrue(local.get(i - 1).end <= local.get(i).start, "Overlapping reservations at SFU " + sfu);
            }
        }
        boolean parallel = occupied.stream().anyMatch(first -> occupied.stream().anyMatch(second ->
                first.sfu != second.sfu && first.start < second.end && second.start < first.end));
        assertTrue(parallel);
        Map<String, String> totals = trace.get(trace.size() - 1);
        assertEquals(unionLength(occupied), ticks(totals.get("wifi_busy_us")));
        assertEquals(count, Integer.parseInt(totals.get("delivered_packets")) + dropped.size());
        assertEquals(delivered.size() * 1500L, Long.parseLong(totals.get("delivered_bytes")));
        Map<String, String> retry = trace.stream().filter(row -> "WIFI_RETRY".equals(row.get("summary")))
                .findFirst().orElseThrow();
        assertEquals(events(trace, "WIFI_ACK_TIMEOUT").size(), Integer.parseInt(retry.get("ack_timeouts")));
        assertEquals(events(trace, "WIFI_MAC_ATTEMPT").stream()
                .filter(row -> Integer.parseInt(row.get("retry_count")) > 0).count(),
                Long.parseLong(retry.get("retransmissions")));
    }

    private static List<Interval> paired(List<Map<String, String>> trace, String startEvent, String endEvent,
                                          boolean group) {
        Map<Integer, Interval> open = new HashMap<>();
        List<Interval> complete = new ArrayList<>();
        for (Map<String, String> row : trace) {
            if (!startEvent.equals(row.get("event")) && !endEvent.equals(row.get("event"))) {
                continue;
            }
            int sfu = Integer.parseInt(row.get("sfu_id"));
            int key = group ? sfu : Integer.parseInt(row.get("packet_id"));
            if (startEvent.equals(row.get("event"))) {
                if (!group || !open.containsKey(key)) {
                    assertEquals(null, open.put(key, new Interval(sfu, ticks(row.get("t_us")), 0)));
                }
            } else {
                Interval start = open.remove(key);
                assertTrue(start != null, "Unmatched " + endEvent);
                complete.add(new Interval(sfu, start.start, ticks(row.get("t_us"))));
            }
        }
        assertTrue(open.isEmpty());
        return complete;
    }

    private static long unionLength(List<Interval> intervals) {
        List<Interval> sorted = intervals.stream().sorted(Comparator.comparingLong(interval -> interval.start))
                .collect(Collectors.toList());
        long total = 0;
        long right = 0;
        for (Interval interval : sorted) {
            total += Math.max(0, interval.end - Math.max(right, interval.start));
            right = Math.max(right, interval.end);
        }
        return total;
    }

    private static Set<Integer> uniquePacketIds(List<Map<String, String>> rows) {
        Set<Integer> ids = new HashSet<>();
        for (Map<String, String> row : rows) {
            assertTrue(ids.add(Integer.parseInt(row.get("packet_id"))), "Duplicate terminal packet " + row);
        }
        return ids;
    }

    private static long ticks(String microseconds) {
        return new BigDecimal(microseconds).movePointRight(3).longValueExact();
    }

    private static List<Map<String, String>> events(List<Map<String, String>> trace, String event) {
        return trace.stream().filter(row -> event.equals(row.get("event"))).collect(Collectors.toList());
    }

    private static List<Map<String, String>> rows(String output) {
        return output.lines().map(line -> {
            Map<String, String> row = new HashMap<>();
            for (String field : line.split(" ")) {
                String[] pair = field.split("=", 2);
                row.put(pair[0], pair[1]);
            }
            return row;
        }).collect(Collectors.toList());
    }

    private static String run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static final class Interval {
        private final int sfu;
        private final long start;
        private final long end;

        private Interval(int sfu, long start, long end) {
            this.sfu = sfu;
            this.start = start;
            this.end = end;
        }

        private boolean contains(Interval other) {
            return sfu == other.sfu && start <= other.start && end >= other.end;
        }
    }
}
