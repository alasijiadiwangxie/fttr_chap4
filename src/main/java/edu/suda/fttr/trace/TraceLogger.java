package edu.suda.fttr.trace;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.Event;

import java.io.PrintStream;
import java.util.Locale;

/** 每行保留时刻、节点、动作和报文身份，便于逐事件核对。 */
public final class TraceLogger {
    private final PrintStream output;

    public TraceLogger(PrintStream output) {
        this.output = output;
    }

    public void recordEvent(Event event) {
        switch (event.type()) {
            case PACKET_ARRIVAL_AT_MFU:
                write(event, "MFU", "ARRIVAL");
                break;
            case FIBER_TX_START:
                write(event, "MFU", "FIBER_TX_START");
                break;
            case PACKET_ARRIVAL_AT_SFU:
                write(event, "SFU", "ARRIVAL");
                break;
            case WIFI_TX_START:
                write(event, "SFU", "WIFI_TX_START");
                break;
            case PACKET_ARRIVAL_AT_STA:
                write(event, "STA", "DELIVERED");
                break;
            default:
                throw new IllegalStateException("Unhandled trace event: " + event.type());
        }
    }

    private void write(Event event, String node, String action) {
        output.printf(Locale.ROOT, "t_us=%d node=%s event=%s packet_id=%d bytes=%d%n",
                event.timeUs(), node, action, event.packet().id(), event.packet().bytes());
    }

    public void recordDelivery(Packet packet, long delayUs) {
        output.printf(Locale.ROOT, "delivered_packets=1 delivered_bytes=%d e2e_delay_us=%d%n",
                packet.bytes(), delayUs);
    }
}
