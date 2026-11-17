package edu.suda.fttr.trace;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
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
        Packet packet = event.packet();
        switch (event.type()) {
            case PACKET_GENERATED:
                write(event, packet.isUplink() ? "STA" : "MFU", "ARRIVAL");
                break;
            case FIBER_TX_START:
                write(event, packet.isUplink() ? "SFU" : "MFU", "FIBER_TX_START");
                break;
            case PACKET_ARRIVAL_AT_SFU:
                write(event, "SFU", "ARRIVAL");
                break;
            case WIFI_TX_START:
                write(event, packet.isUplink() ? "STA" : "SFU", "WIFI_TX_START");
                break;
            case PACKET_ARRIVAL_AT_STA:
            case PACKET_ARRIVAL_AT_MFU:
                // 接收端确认目的 ID 后，通过 recordDelivery 记录实际接收者。
                break;
            case WIFI_ARRIVAL_AT_SFU:
                // 此时无线发送结束，SFU 会通过 WIFI_TX_END 记录该边界。
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
        if (packet.isUplink()) {
            writeUplink(packet, event.timeUs(), node, action, event.sfuId(), "");
            return;
        }
        String route = event.sfuId() == 0 ? "" : " sfu_id=" + event.sfuId();
        output.printf(Locale.ROOT, "t_us=%d node=%s event=%s packet_id=%d bytes=%d type=%s destination_sta_id=%d%s%n",
                event.timeUs(), node, action, packet.id(), packet.bytes(), packet.type(), packet.destinationStaId(), route);
    }

    private void writeUplink(Packet packet, long timeUs, String node, String action, int sfuId, String details) {
        String route = sfuId == 0 ? "" : " sfu_id=" + sfuId;
        output.printf(Locale.ROOT, "t_us=%d node=%s event=%s packet_id=%d bytes=%d type=%s "
                        + "direction=UL source_sta_id=%d destination_mfu_id=%d%s%s%n",
                timeUs, node, action, packet.id(), packet.bytes(), packet.type(), packet.staId(),
                packet.destinationNodeId(), route, details);
    }

    public void recordDelivery(Packet packet, Sta sta, long timeUs) {
        output.printf(Locale.ROOT,
                "t_us=%d node=STA event=DELIVERED packet_id=%d bytes=%d type=%s destination_sta_id=%d sta_id=%d sfu_id=%d%n",
                timeUs, packet.id(), packet.bytes(), packet.type(), packet.destinationStaId(), sta.id(), sta.sfuId());
    }

    public void recordUplinkDelivery(Packet packet, int sfuId, long timeUs) {
        writeUplink(packet, timeUs, "MFU", "DELIVERED", sfuId,
                " e2e_delay_us=" + (timeUs - packet.generationTimeUs()));
    }

    public void recordEnqueue(Packet packet, long timeUs, int sfuId, int queueDepth) {
        if (packet.isUplink()) {
            writeUplink(packet, timeUs, "STA", "SFU_ENQUEUE", sfuId,
                    " generation_us=" + packet.generationTimeUs() + " queue_depth=" + queueDepth);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%d node=SFU event=SFU_ENQUEUE packet_id=%d bytes=%d type=%s mfu_arrival_us=%d queue_depth=%d destination_sta_id=%d sfu_id=%d%n",
                timeUs, packet.id(), packet.bytes(), packet.type(), packet.mfuArrivalUs(),
                queueDepth, packet.destinationStaId(), sfuId);
    }

    public void recordQueueSelection(Packet packet, long timeUs, int sfuId, int vo, int vi, int be, int bk) {
        if (packet.isUplink()) {
            writeUplink(packet, timeUs, "SFU", "QUEUE_SELECT", sfuId,
                    " vo_queue=" + vo + " vi_queue=" + vi + " be_queue=" + be + " bk_queue=" + bk);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%d node=SFU event=QUEUE_SELECT packet_id=%d type=%s vo_queue=%d vi_queue=%d be_queue=%d bk_queue=%d destination_sta_id=%d sfu_id=%d%n",
                timeUs, packet.id(), packet.type(), vo, vi, be, bk, packet.destinationStaId(), sfuId);
    }

    public void recordWifiService(Packet packet, int sfuId, long sfuArrivalUs, long startUs, long endUs) {
        if (packet.isUplink()) {
            writeUplink(packet, endUs, "SFU", "WIFI_TX_END", sfuId, " wifi_tx_start_us=" + startUs
                    + " wifi_queue_wait_us=" + (startUs - sfuArrivalUs) + " wifi_service_us=" + (endUs - startUs));
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%d node=SFU event=WIFI_TX_END packet_id=%d wifi_tx_start_us=%d wifi_queue_wait_us=%d "
                        + "wifi_service_us=%d e2e_delay_us=%d type=%s destination_sta_id=%d sfu_id=%d%n",
                endUs, packet.id(), startUs, startUs - sfuArrivalUs, endUs - startUs,
                endUs - packet.mfuArrivalUs(), packet.type(), packet.destinationStaId(), sfuId);
    }

    public void recordClassSummary(AccessCategory type, int sentPackets, double waitUs, double delayUs) {
        output.printf(Locale.ROOT,
                "summary=CLASS type=%s sent_packets=%d avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                type, sentPackets, waitUs, delayUs);
    }

    /** 汇总泊松输入。type 为空时统计该 SFU 的全部业务。 */
    public void recordTrafficSummary(int sfuId, AccessCategory type, int packets, long bytes, long windowUs) {
        String group = type == null ? "summary=TRAFFIC_SFU sfu_id=" + sfuId
                : "summary=TRAFFIC_CLASS sfu_id=" + sfuId + " type=" + type;
        writeTrafficSummary(group, packets, bytes, windowUs);
    }

    public void recordUplinkTrafficSummary(int staId, int sfuId, AccessCategory type, int packets, long bytes,
                                           long windowUs) {
        String group = (type == null ? "summary=TRAFFIC_UL_STA" : "summary=TRAFFIC_UL_CLASS")
                + " sta_id=" + staId + " sfu_id=" + sfuId + (type == null ? "" : " type=" + type);
        writeTrafficSummary(group, packets, bytes, windowUs);
    }

    private void writeTrafficSummary(String group, int packets, long bytes, long windowUs) {
        output.printf(Locale.ROOT, "%s generated_packets=%d generated_bytes=%d window_us=%d "
                        + "actual_rate_pps=%.6f offered_load_mbps=%.6f%n",
                group, packets, bytes, windowUs, packets * 1_000_000.0 / windowUs, bytes * 8.0 / windowUs);
    }

    public void recordGlobalDelay(int sentPackets, double waitUs, double delayUs) {
        output.printf(Locale.ROOT,
                "summary=GLOBAL sent_packets=%d avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                sentPackets, waitUs, delayUs);
    }

    public void recordStaSummary(Sta sta, double throughputMbps) {
        output.printf(Locale.ROOT,
                "summary=STA sta_id=%d sfu_id=%d received_packets=%d received_bytes=%d throughput_mbps=%.2f avg_e2e_delay_us=%.2f%n",
                sta.id(), sta.sfuId(), sta.receivedPackets(), sta.receivedBytes(), throughputMbps, sta.averageDelayUs());
    }

    public void recordSfuSummary(Sfu sfu, double throughputMbps) {
        output.printf(Locale.ROOT,
                "summary=SFU sfu_id=%d sent_packets=%d sent_bytes=%d throughput_mbps=%.2f avg_queue_wait_us=%.2f%n",
                sfu.id(), sfu.sentPackets(), sfu.sentBytes(), throughputMbps, sfu.averageQueueWaitUs());
    }

    public void recordUplinkStaSummary(Sta sta, int packets, long bytes, double throughputMbps, double waitUs,
                                       double delayUs) {
        output.printf(Locale.ROOT, "summary=UL_STA sta_id=%d sfu_id=%d delivered_packets=%d delivered_bytes=%d "
                        + "throughput_mbps=%.2f avg_queue_wait_us=%.2f avg_e2e_delay_us=%.2f%n",
                sta.id(), sta.sfuId(), packets, bytes, throughputMbps, waitUs, delayUs);
    }

    public void recordUplinkGlobalSummary(int packets, long bytes, double delayUs) {
        output.printf(Locale.ROOT, "summary=UL_GLOBAL delivered_packets=%d delivered_bytes=%d avg_e2e_delay_us=%.2f%n",
                packets, bytes, delayUs);
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
