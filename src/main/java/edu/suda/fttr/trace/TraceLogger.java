package edu.suda.fttr.trace;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sta;
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
                // 接收端确认目的 ID 后，通过 recordDelivery 记录实际接收者。
                break;
            case WIFI_SELECT_NEXT:
                // SFU 实际选队时另记队列快照，此处的调度事件没有报文。
                break;
            default:
                throw new IllegalStateException("Unhandled trace event: " + event.type());
        }
    }

    private void write(Event event, String node, String action) {
        Packet packet = event.packet();
        output.printf(Locale.ROOT, "t_us=%d node=%s event=%s packet_id=%d bytes=%d type=%s destination_sta_id=%d%n",
                event.timeUs(), node, action, packet.id(), packet.bytes(), packet.type(), packet.destinationStaId());
    }

    public void recordDelivery(Packet packet, int staId, long timeUs) {
        output.printf(Locale.ROOT,
                "t_us=%d node=STA event=DELIVERED packet_id=%d bytes=%d type=%s destination_sta_id=%d sta_id=%d%n",
                timeUs, packet.id(), packet.bytes(), packet.type(), packet.destinationStaId(), staId);
    }

    public void recordEnqueue(Packet packet, long timeUs, int queueDepth) {
        output.printf(Locale.ROOT,
                "t_us=%d node=SFU event=SFU_ENQUEUE packet_id=%d bytes=%d type=%s mfu_arrival_us=%d queue_depth=%d destination_sta_id=%d%n",
                timeUs, packet.id(), packet.bytes(), packet.type(), packet.mfuArrivalUs(), queueDepth, packet.destinationStaId());
    }

    public void recordQueueSelection(Packet packet, long timeUs, int vo, int vi, int be, int bk) {
        output.printf(Locale.ROOT,
                "t_us=%d node=SFU event=QUEUE_SELECT packet_id=%d type=%s vo_queue=%d vi_queue=%d be_queue=%d bk_queue=%d destination_sta_id=%d%n",
                timeUs, packet.id(), packet.type(), vo, vi, be, bk, packet.destinationStaId());
    }

    public void recordWifiService(Packet packet, long sfuArrivalUs, long startUs, long endUs) {
        output.printf(Locale.ROOT,
                "t_us=%d node=SFU event=WIFI_TX_END packet_id=%d wifi_tx_start_us=%d wifi_queue_wait_us=%d "
                        + "wifi_service_us=%d e2e_delay_us=%d type=%s destination_sta_id=%d%n",
                endUs, packet.id(), startUs, startUs - sfuArrivalUs, endUs - startUs,
                endUs - packet.mfuArrivalUs(), packet.type(), packet.destinationStaId());
    }

    public void recordClassSummary(AccessCategory type, int sentPackets, double waitUs, double delayUs) {
        output.printf(Locale.ROOT,
                "summary=CLASS type=%s sent_packets=%d avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                type, sentPackets, waitUs, delayUs);
    }

    public void recordGlobalDelay(int sentPackets, double waitUs, double delayUs) {
        output.printf(Locale.ROOT,
                "summary=GLOBAL sent_packets=%d avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                sentPackets, waitUs, delayUs);
    }

    public void recordStaSummary(Sta sta, double throughputMbps) {
        output.printf(Locale.ROOT,
                "summary=STA sta_id=%d received_packets=%d received_bytes=%d throughput_mbps=%.2f avg_e2e_delay_us=%.2f%n",
                sta.id(), sta.receivedPackets(), sta.receivedBytes(), throughputMbps, sta.averageDelayUs());
    }

    public void recordSinglePacketSummary(int deliveredPackets, long deliveredBytes, long delayUs) {
        output.printf(Locale.ROOT, "delivered_packets=%d delivered_bytes=%d e2e_delay_us=%d%n",
                deliveredPackets, deliveredBytes, delayUs);
    }

    public void recordRunSummary(int deliveredPackets, long deliveredBytes, long busyUs, long endUs) {
        output.printf(Locale.ROOT,
                "delivered_packets=%d delivered_bytes=%d wifi_busy_us=%d wifi_idle_us=%d simulation_end_us=%d%n",
                deliveredPackets, deliveredBytes, busyUs, endUs - busyUs, endUs);
    }
}
