import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.simulator.Simulator;
import edu.suda.fttr.trace.TraceLogger;

import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 实验日志投影，保留复核所需的事件，不修改 Java 仿真行为。 */
public final class D5Trace {
    private static final Set<String> EVENTS = Set.of("ARRIVAL", "DELIVERED", "FIBER_TX_START", "FIBER_TX_END", "TCONT_ENQUEUE",
            "WIFI_MAC_ATTEMPT", "WIFI_MAC_SUCCESS", "WIFI_MAC_RETRY", "WIFI_RETRY_LIMIT_DROP", "WIFI_COLLISION_DROP",
            "WIFI_PHY_MPDU_DECODE", "WIFI_EXTERNAL_CCA", "WIFI_CHANNEL_SCAN", "WIFI_AP_ACTIVITY_CHANGE", "WIFI_AP_QUEUE_EVENT",
            "WIFI_AP_QOS", "WIFI_AP_MODE_INITIAL", "WIFI_AP_MODE_DECISION", "WIFI_AP_ENERGY_MODE_DECISION",
            "WIFI_AP_QOS_MODE_DECISION", "WIFI_AP_QOS_DEADLINE_PREDICTION", "WIFI_AP_D4_DECISION", "WIFI_AP_D5_DECISION",
            "WIFI_AP_D4_RESIDENCY", "WIFI_AP_D4_EXPIRED_INGRESS", "WIFI_AP_HCM_DEFER", "WIFI_AP_SWITCH_REQUEST",
            "WIFI_AP_SWITCH_CANCELLED", "WIFI_AP_SWITCH_START", "WIFI_AP_SWITCH_END", "PON_DBA_BWMAP_TX", "PON_DBA_BWMAP_RX",
            "PON_DBA_DATA_START", "PON_DBA_DATA_END", "PON_DBA_SLOT_END");

    private D5Trace() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: D5Trace CONFIG");
        }
        try (PrintStream out = new SelectedTrace()) {
            Simulator.run(ConfigLoader.load(Path.of(args[0])), new TraceLogger(out));
        }
    }

    private static final class SelectedTrace extends PrintStream {
        // 负值表示静态的保留/舍弃格式，非负值定位 event=%s 对应的参数。
        private static final int KEEP = -1;
        private static final int SKIP = -2;
        private final Map<String, Integer> formatRules = new HashMap<>();

        private SelectedTrace() {
            super(new BufferedOutputStream(System.out, 65536), false, StandardCharsets.UTF_8);
        }

        @Override
        public PrintStream printf(Locale locale, String format, Object... args) {
            int rule = formatRules.computeIfAbsent(format, SelectedTrace::classify);
            if (rule == KEEP || rule >= 0 && EVENTS.contains(args[rule])) {
                super.printf(locale, format, args);
            }
            return this;
        }

        @Override
        public void println(String ignored) {
            // 冻结 DBA 的大型控制快照不参与这些统计。
        }

        private static int classify(String format) {
            if (format.startsWith("summary=WIFI_") || format.startsWith("summary=PON_%s ")
                    || format.startsWith("summary=PON_DBA_EXECUTION ")) {
                return KEEP;
            }
            int marker = format.indexOf("event=");
            if (marker < 0) {
                return SKIP;
            }
            if (format.startsWith("event=%s", marker)) {
                int argument = 0;
                for (int i = 0; i < marker; i++) {
                    if (format.charAt(i) == '%') {
                        argument++;
                    }
                }
                return argument;
            }
            String event = format.substring(marker + 6).split(" ", 2)[0];
            return EVENTS.contains(event) ? KEEP : SKIP;
        }
    }
}
