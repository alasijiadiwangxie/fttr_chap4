import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.simulator.Simulator;
import edu.suda.fttr.trace.TraceLogger;

import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 导出正式实验所需的原始事件。同一进程内重放多个独立配置，仿真仍使用原 JAR。 */
public final class E2Trace {
    private static final Set<String> EVENTS = Set.of(
            "ARRIVAL", "DELIVERED", "FIBER_TX_START", "FIBER_TX_END", "TCONT_ENQUEUE",
            "PON_DBA_BWMAP_RX", "PON_DBA_DATA_START",
            "WIFI_MAC_ATTEMPT", "WIFI_MAC_SUCCESS", "WIFI_MAC_RETRY", "WIFI_RETRY_LIMIT_DROP", "WIFI_COLLISION_DROP",
            "WIFI_AP_ACTIVITY_CHANGE", "WIFI_AP_MODE_INITIAL", "WIFI_AP_SWITCH_REQUEST", "WIFI_AP_SWITCH_CANCELLED",
            "WIFI_AP_SWITCH_START", "WIFI_AP_SWITCH_END", "WIFI_AP_HCM_DEFER", "WIFI_AP_QOS", "WIFI_CHANNEL_SCAN",
            "WIFI_EXTERNAL_CCA");

    private static final Set<String> SUMMARIES = Set.of(
            "PON_DBA_EXECUTION", "PON_POLLING", "WIFI_AP_ACTIVITY", "WIFI_AP_ENERGY", "WIFI_AP_ENERGY_NETWORK",
            "WIFI_CHANNEL_OVERHEAD");
    private static final int FRAME_NS = 125_000;

    private E2Trace() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException("E2Trace CONFIG TRACE [CONFIG TRACE ...]");
        }
        for (int i = 0; i < args.length; i += 2) {
            Path config = Path.of(args[i]);
            try (SelectedTrace trace = new SelectedTrace(Path.of(args[i + 1]))) {
                Simulator.run(ConfigLoader.load(config), new TraceLogger(trace));
                trace.finish();
                if (trace.checkError()) {
                    throw new IllegalStateException("Cannot write trace for " + config);
                }
            }
        }
    }

    private static final class SelectedTrace extends PrintStream {
        private final Map<String, String> eventNames = new HashMap<>();
        private final Map<String, Integer> eventPositions = new HashMap<>();
        private final Map<String, Boolean> summaryFormats = new HashMap<>();
        private long frames, previousFrame = -1, grants, received, ended, settled, budgetBytes, actualBytes, unusedBytes;
        private long reservationsNs, actualNs, decodeFailures, decoded, collisionFailures, retries;
        private long lastSlotEnd = -1;

        SelectedTrace(Path output) throws Exception {
            super(new BufferedOutputStream(Files.newOutputStream(output), 64 * 1024), false, StandardCharsets.UTF_8);
        }

        @Override
        public PrintStream printf(Locale locale, String format, Object... args) {
            int position = eventPositions.computeIfAbsent(format, SelectedTrace::dynamicEventField);
            String event = position < 0 ? eventNames.computeIfAbsent(format, key -> field(key, "event="))
                    : args[position].toString();
            switch (event) {
                case "PON_DBA_MULTI_PLAN": // 只核查每帧计划的边界和容量，无需输出各空帧的完整日志。
                    long target = number(args, 1);
                    if (target <= previousFrame || target * FRAME_NS != number(args, 0) + FRAME_NS
                            || number(args, 4) + number(args, 5) > FRAME_NS) {
                        throw new IllegalStateException("Invalid DBA frame reservation: " + target);
                    }
                    previousFrame = target;
                    frames++;
                    grants += number(args, 2);
                    reservationsNs += number(args, 4);
                    break;
                case "PON_DBA_BWMAP_RX":
                    long slotStart = number(args, 8);
                    long slotEnd = number(args, 9);
                    long frameStart = number(args, 7) * FRAME_NS;
                    if (number(args, 2) >= slotStart || slotStart < frameStart || slotEnd > frameStart + FRAME_NS
                            || slotStart < lastSlotEnd || slotEnd <= slotStart) {
                        throw new IllegalStateException("Invalid received BWmap grant: " + args[3]);
                    }
                    lastSlotEnd = slotEnd;
                    received++;
                    break;
                case "PON_DBA_SLOT_END":
                    if (number(args, 10) != number(args, 12) + number(args, 13)) {
                        throw new IllegalStateException("Unbalanced DBA budget: " + args[3]);
                    }
                    ended++;
                    budgetBytes += number(args, 10);
                    actualBytes += number(args, 12);
                    unusedBytes += number(args, 13);
                    break;
                case "PON_DBA_SETTLEMENT_RX":
                    settled++;
                    break;
                case "WIFI_PHY_MPDU_DECODE":
                    if ("SINR_ERROR".equals(args[12])) {
                        decodeFailures++;
                    } else if ("DECODED".equals(args[12])) {
                        decoded++;
                    } else {
                        collisionFailures++;
                    }
                    break;
                case "WIFI_MAC_RETRY":
                    retries++;
                    break;
                default:
                    break;
            }
            if (format.startsWith("summary=PON_DBA_EXECUTION ")) {
                actualNs = number(args, 5);
            }
            if (EVENTS.contains(event) || summaryFormats.computeIfAbsent(format, key ->
                    SUMMARIES.contains(field(key, "summary=")) || key.contains("simulation_end_us="))) {
                // 除逐包事件外，只保留窗口末尾的 QoS 对账及必要摘要由 Python 复算。
                super.printf(locale, format, args);
            }
            return this;
        }

        private void finish() {
            if (received != grants || ended != grants || settled != grants) {
                throw new IllegalStateException("Incomplete DBA grants: " + grants + "/" + received + "/" + ended + "/" + settled);
            }
            super.printf(Locale.ROOT, "summary=E2_AUDIT frames=%d grants=%d budget_bytes=%d actual_bytes=%d unused_bytes=%d "
                            + "reserved_ns=%d actual_data_ns=%d sinr_errors=%d decoded=%d collision_errors=%d retries=%d%n",
                    frames, grants, budgetBytes, actualBytes, unusedBytes, reservationsNs, actualNs,
                    decodeFailures, decoded, collisionFailures, retries);
        }

        private static long number(Object[] args, int index) {
            return ((Number) args[index]).longValue();
        }

        private static int dynamicEventField(String format) {
            int event = format.indexOf("event=%s");
            if (event < 0) {
                return -1;
            }
            int fields = 0;
            for (int i = 0; i < event; i++) {
                if (format.charAt(i) == '%') {
                    fields++;
                }
            }
            return fields;
        }

        private static String field(String line, String token) {
            int start = line.indexOf(token);
            if (start == -1) {
                return "";
            }
            start += token.length();
            int end = line.indexOf(' ', start);
            return line.substring(start, end == -1 ? line.length() : end).strip();
        }
    }
}
