package fttr.chap4;

import java.io.PrintStream;
import java.util.Locale;

/** 一条报文在 MFU、SFU、STA 之间按因果顺序交付。 */
final class DownlinkSimulation {
    private final SimulationConfig config;
    private final PrintStream output;
    private final EventQueue events = new EventQueue();

    private DownlinkSimulation(SimulationConfig config, PrintStream output) {
        this.config = config;
        this.output = output;
    }

    static void run(SimulationConfig config, PrintStream output) {
        DownlinkSimulation simulation = new DownlinkSimulation(config, output);
        simulation.events.scheduleAt(config.packetArrivalUs, simulation::arriveAtMfu);
        simulation.events.run();
    }

    private void arriveAtMfu() {
        // 端到端时延从 MFU 实际收到报文的时刻起算。
        Packet packet = new Packet(1, config.packetBytes, events.nowUs());
        trace("MFU", "ARRIVAL", packet);
        events.scheduleAfter(config.fiberDelayUs, () -> arriveAtSfu(packet));
    }

    private void arriveAtSfu(Packet packet) {
        trace("SFU", "ARRIVAL", packet);

        // SFU 先收到同一条报文，再安排无线链路的交付事件。
        events.scheduleAfter(config.wifiDelayUs, () -> deliverAtSta(packet));
    }

    private void deliverAtSta(Packet packet) {
        trace("STA", "DELIVERED", packet);
        output.printf(Locale.ROOT, "delivered_packets=1 delivered_bytes=%d e2e_delay_us=%d%n",
                packet.bytes, events.nowUs() - packet.mfuArrivalUs);
    }

    private void trace(String node, String action, Packet packet) {
        output.printf(Locale.ROOT, "t_us=%d node=%s event=%s packet_id=%d bytes=%d%n",
                events.nowUs(), node, action, packet.id, packet.bytes);
    }

    // 原始到达时刻随报文一起传递，SFU 转发时保持不变。
    private static final class Packet {
        private final int id;
        private final int bytes;
        private final long mfuArrivalUs;

        private Packet(int id, int bytes, long mfuArrivalUs) {
            this.id = id;
            this.bytes = bytes;
            this.mfuArrivalUs = mfuArrivalUs;
        }
    }
}
