package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.trace.TraceLogger;

import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 诊断入口。正常重放从原 JAR 加载 Simulator。短窗试验使用 target 下的副本。 */
public final class E0Trace {
    // 这些事件同时用于核对分叉前的实际历史。决策之后继续记录同粒度的实测结果。
    private static final Set<String> PHYSICAL = Set.of(
            "ARRIVAL", "DELIVERED", "WIFI_MAC_ATTEMPT", "WIFI_MAC_SUCCESS", "WIFI_MAC_RETRY",
            "WIFI_RETRY_LIMIT_DROP", "WIFI_COLLISION_DROP", "WIFI_PHY_MPDU_DECODE", "WIFI_AP_QUEUE_EVENT",
            "WIFI_EXTERNAL_CCA", "WIFI_CHANNEL_SCAN", "WIFI_CHANNEL_FEEDBACK", "WIFI_AP_ACTIVITY_CHANGE",
            "WIFI_AP_SWITCH_REQUEST", "WIFI_AP_SWITCH_CANCELLED", "WIFI_AP_SWITCH_START", "WIFI_AP_SWITCH_END");
    private static final Set<String> EVIDENCE = Set.of(
            "WIFI_AP_MODE_INITIAL", "WIFI_AP_MODE_DECISION", "WIFI_AP_D4_DECISION", "WIFI_AP_D5_DECISION",
            "WIFI_AP_ENERGY_MODE_DECISION", "WIFI_AP_QOS_MODE_DECISION", "WIFI_AP_QOS_DEADLINE_PREDICTION",
            "WIFI_AP_HCM_DEFER", "WIFI_AP_QOS", "FIBER_TX_START", "FIBER_TX_END", "TCONT_ENQUEUE",
            "TCONT_DEMAND", "TCONT_REPORT_RX", "PON_DBA_MULTI_PLAN", "PON_GIANT_DECISION", "PON_DBA_BWMAP_TX",
            "PON_DBA_BWMAP_RX", "PON_DBA_LEDGER_CREATED", "PON_DBA_FILL", "PON_DBA_DATA_START",
            "PON_DBA_DATA_END", "PON_DBA_SLOT_END", "PON_DBA_SETTLEMENT_RX");

    private static boolean periodicQosOnly;
    private static Probe probe;
    private static SelectedTrace output;

    private E0Trace() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[1].equals("periodic-qos")) {
            periodicQosOnly = true;
        } else if (args.length == 4) {
            probe = new Probe(Integer.parseInt(args[1]), Long.parseLong(args[2]), Long.parseLong(args[3]));
        } else if (args.length != 1) {
            throw new IllegalArgumentException("E0Trace CONFIG [periodic-qos | SFU START_NS END_NS]");
        }
        try (SelectedTrace trace = new SelectedTrace()) {
            output = trace;
            try {
                Simulator.run(ConfigLoader.load(Path.of(args[0])), new TraceLogger(trace));
            } catch (EndOfProbe end) {
                if (probe == null || !probe.began) {
                    throw new IllegalStateException("Hold probe failed to reach the matched decision", end);
                }
            }
            if (probe != null && !probe.began) {
                throw new IllegalStateException("Hold probe did not reach the matched decision");
            }
        }
    }

    // 仅由实验副本调用。决策仍正常计算，执行动作在这个 AP 的试验窗口中保持当前档位。
    static boolean hold(int sfuId, long nowNs) {
        if (probe == null || sfuId != probe.sfuId || nowNs < probe.startNs || nowNs > probe.endNs) {
            return false;
        }
        if (!probe.began) {
            if (nowNs != probe.startNs) {
                throw new IllegalStateException("The decision did not occur at the matched time");
            }
            probe.began = true;
            output.beginProbe();
        }
        return true;
    }

    static boolean periodicQosOnly() {
        return periodicQosOnly;
    }

    static void stopAfter(long timeNs) {
        if (probe != null && timeNs > probe.endNs) {
            throw new EndOfProbe();
        }
    }

    private static final class Probe {
        private final int sfuId;
        private final long startNs, endNs;
        private boolean began;

        private Probe(int sfuId, long startNs, long endNs) {
            this.sfuId = sfuId;
            this.startNs = startNs;
            this.endNs = endNs;
        }
    }

    private static final class EndOfProbe extends RuntimeException { }

    private static final class SelectedTrace extends PrintStream {
        private final Map<String, Integer> dynamicField = new HashMap<>();
        private final MessageDigest prefix;

        private SelectedTrace() throws NoSuchAlgorithmException {
            super(new BufferedOutputStream(System.out, 64 * 1024), false, StandardCharsets.UTF_8);
            prefix = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public PrintStream printf(Locale locale, String format, Object... args) {
            int eventPosition = dynamicField.computeIfAbsent(format, SelectedTrace::dynamicEventField);
            String event = eventPosition < 0 ? field(format, "event=") : args[eventPosition].toString();
            if (event.isEmpty() && format.startsWith("%s state=")) {
                event = field(args[0].toString(), "event="); // 已知的 PON 需求使用预拼好的事件前缀。
            }
            if (probe != null && !probe.began) {
                if (PHYSICAL.contains(event)) {
                    String actual = String.format(locale, format, args).strip() + "\n";
                    prefix.update(actual.getBytes(StandardCharsets.UTF_8));
                }
            } else if (PHYSICAL.contains(event) || probe == null && (EVIDENCE.contains(event)
                    || format.startsWith("summary=WIFI_") || format.startsWith("summary=PON_"))) {
                super.printf(locale, format, args);
            }
            return this;
        }

        @Override
        public void println(String value) {
            if (probe == null && EVIDENCE.contains(field(value, "event="))) {
                super.println(value);
            }
        }

        private void beginProbe() {
            StringBuilder sha = new StringBuilder();
            for (byte value : prefix.digest()) {
                sha.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            super.println("event=E0_PREFIX sha256=" + sha);
        }

        private static int dynamicEventField(String format) {
            int event = format.indexOf("event=%s");
            if (event < 0) {
                return -1;
            }
            int field = 0;
            for (int index = 0; index < event; index++) {
                if (format.charAt(index) == '%') {
                    field++;
                }
            }
            return field;
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
