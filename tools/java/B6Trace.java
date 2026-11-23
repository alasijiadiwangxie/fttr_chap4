import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.simulator.Simulator;
import edu.suda.fttr.trace.TraceLogger;

import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/** B6 单信道下行扫描的实验日志投影器，保留真实的帧边界和逐包守恒信息。 */
public final class B6Trace {
    public static void main(String[] args) throws Exception {
        try (PrintStream output = new ResearchTrace()) {
            Simulator.run(ConfigLoader.load(Path.of(args[0])), new TraceLogger(output));
        }
    }

    private static final class ResearchTrace extends PrintStream {
        private long startNs;
        private long dataEndNs;
        private long controlStartNs;
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
                } else if (event.equals("WIFI_RETRY_LIMIT_DROP") || event.equals("WIFI_COLLISION_DROP")) {
                    println("L " + args[0] + " " + args[3]);
                } else if (event.equals("WIFI_TX_START")) {
                    startNs = ticks((String) args[0]);
                    if (firstStart) {
                        println("X " + args[0] + " " + args[3]);
                        firstStart = false;
                    }
                } else if (event.equals("WIFI_DATA_END")) {
                    recordDataEnd(args[0], ticks((String) args[0]), args[3]);
                } else if (event.equals("WIFI_ACK_START")) {
                    controlStartNs = ticks((String) args[0]);
                } else if (event.equals("WIFI_ACK_END")) {
                    recordExchange(ticks((String) args[0]), 1);
                }
            } else if (format.startsWith("t_us=%s t_ns=%d node=SFU event=WIFI_AMPDU_%s")) {
                long timeNs = (Long) args[1];
                switch ((String) args[2]) {
                    case "DATA_END":
                        recordDataEnd(args[0], timeNs, args[4]);
                        break;
                    case "BA_START":
                        controlStartNs = timeNs;
                        break;
                    case "BA_END":
                        recordExchange(timeNs, (Integer) args[8]);
                        break;
                    default:
                        break;
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

        private void recordDataEnd(Object timeUs, long timeNs, Object packetId) {
            dataEndNs = timeNs;
            if (firstEnd) {
                println("Q " + timeUs + " " + packetId);
                firstEnd = false;
            }
        }

        private void recordExchange(long endNs, int members) {
            println("A " + startNs + " " + dataEndNs + " " + controlStartNs + " " + endNs + " " + members);
        }

        private static long ticks(String micros) {
            int dot = micros.indexOf('.');
            if (dot < 0) {
                return Long.parseLong(micros) * 1_000;
            }
            int fractionalDigits = micros.length() - dot - 1;
            long fractionalNs = Long.parseLong(micros.substring(dot + 1));
            for (int i = fractionalDigits; i < 3; i++) {
                fractionalNs *= 10;
            }
            return Long.parseLong(micros.substring(0, dot)) * 1_000 + fractionalNs;
        }
    }
}
