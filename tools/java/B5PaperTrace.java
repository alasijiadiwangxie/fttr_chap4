import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.simulator.Simulator;
import edu.suda.fttr.trace.TraceLogger;

import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/** 实验专用日志投影。沿用现有 ConfigLoader、Simulator 和 TraceLogger，只裁减文本输出。 */
public final class B5PaperTrace {
    private B5PaperTrace() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: B5PaperTrace CONFIG");
        }
        try (PrintStream output = new ResearchTrace()) {
            Simulator.run(ConfigLoader.load(Path.of(args[0])), new TraceLogger(output));
        }
    }

    private static final class ResearchTrace extends PrintStream {
        private boolean firstStart = true;
        private boolean firstEnd = true;

        private ResearchTrace() {
            super(new BufferedOutputStream(System.out, 65536), false, StandardCharsets.UTF_8);
        }

        @Override
        public PrintStream printf(Locale locale, String format, Object... args) {
            if (format.startsWith("t_us=%s node=%s event=%s packet_id=%d")) {
                String event = (String) args[2];
                if (event.equals("ARRIVAL") && args[1].equals("MFU")) {
                    println("G " + args[0] + " " + args[3] + " " + args[4] + " " + args[5] + " " + args[6]);
                } else if (event.equals("WIFI_COLLISION_DROP") || event.equals("WIFI_RETRY_LIMIT_DROP")) {
                    println("L " + args[0] + " " + args[3]);
                } else if (event.equals("WIFI_TX_START") && firstStart) {
                    println("X " + args[0] + " " + args[3]);
                    firstStart = false;
                } else if (event.equals("WIFI_DATA_END") && firstEnd) {
                    println("Q " + args[0] + " " + args[3]);
                    firstEnd = false;
                }
            } else if (format.startsWith("t_us=%s node=STA event=DELIVERED packet_id=%d")) {
                println("D " + args[0] + " " + args[1]);
            } else if (format.startsWith("summary=WIFI_AP_ACTIVITY ")
                    || format.startsWith("summary=WIFI_AP_ENERGY ")
                    || format.startsWith("delivered_packets=")) {
                super.printf(locale, format, args);
            }
            return this;
        }
    }
}
