package edu.suda.fttr.trace;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.simulator.Event;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Locale;

/** 每行保留时刻、节点、动作和报文身份，便于逐事件核对。 */
public final class TraceLogger {
    private final PrintStream output;
    private final boolean nanosecondClock;

    public TraceLogger(PrintStream output) {
        this(output, false);
    }

    private TraceLogger(PrintStream output, boolean nanosecondClock) {
        this.output = output;
        this.nanosecondClock = nanosecondClock;
    }

    /** 共享输出流，但单次运行的时钟单位独立于其他运行。 */
    public TraceLogger withNanosecondClock(boolean nanosecondClock) {
        return this.nanosecondClock == nanosecondClock ? this : new TraceLogger(output, nanosecondClock);
    }

    private String formatMicroseconds(long ticks) {
        if (!nanosecondClock) {
            return Long.toString(ticks);
        }
        // 日志仍用 _us。整数微秒保持旧格式，小数部分则不经过 double。
        return ticks % 1_000 == 0 ? Long.toString(ticks / 1_000)
                : BigDecimal.valueOf(ticks, 3).stripTrailingZeros().toPlainString();
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
            case WIFI_TXOP_DATA_START:
                write(event, packet.isUplink() ? "STA" : "SFU", "WIFI_TX_START");
                break;
            case WIFI_ACK_START:
                write(event, packet.isUplink() ? "SFU" : "STA", "WIFI_ACK_START");
                break;
            case WIFI_ACK_END:
                write(event, packet.isUplink() ? "STA" : "SFU", "WIFI_ACK_END");
                break;
            case PACKET_ARRIVAL_AT_STA:
            case PACKET_ARRIVAL_AT_MFU:
                // 接收端确认目的 ID 后，通过 recordDelivery 记录实际接收者。
                break;
            case WIFI_ARRIVAL_AT_SFU:
                // SFU 收齐上行 DATA。链路随后单独记录 DATA 到达和无线资源释放。
                break;
            case WIFI_SELECT_NEXT:
                // SFU 实际选队时另记队列快照，此处的调度事件没有报文。
                break;
            case WIFI_ACCESS_TIMER:
            case WIFI_ACCESS_RESOLVE:
                // 内部接入事件。记录实际选队结果和虚拟碰撞。
                break;
            case WIFI_COLLISION_DATA_END:
            case WIFI_ACK_TIMEOUT:
                // SFU 处理结果后记录失败、重传或信道释放日志。
                break;
            default:
                throw new IllegalStateException("Unhandled trace event: " + event.type());
        }
    }

    private void write(Event event, String node, String action) {
        write(event.packet(), event.timeTicks(), node, action, event.sfuId());
    }

    private void write(Packet packet, long timeTicks, String node, String action, int sfuId) {
        if (packet.isUplink()) {
            writeUplink(packet, timeTicks, node, action, sfuId, "");
            return;
        }
        String route = sfuId == 0 ? "" : " sfu_id=" + sfuId;
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s packet_id=%d bytes=%d type=%s destination_sta_id=%d%s%n",
                formatMicroseconds(timeTicks), node, action, packet.id(), packet.bytes(), packet.type(),
                packet.destinationStaId(), route);
    }

    public void recordWifiDataEnd(Packet packet, int sfuId, long timeTicks) {
        String receiver = packet.isUplink() ? "SFU" : "STA";
        write(packet, timeTicks, receiver, "WIFI_DATA_END", sfuId);
        write(packet, timeTicks, receiver, "WIFI_SIFS_START", sfuId);
    }

    private void writeUplink(Packet packet, long timeTicks, String node, String action, int sfuId, String details) {
        String route = sfuId == 0 ? "" : " sfu_id=" + sfuId;
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s packet_id=%d bytes=%d type=%s "
                        + "direction=UL source_sta_id=%d destination_mfu_id=%d%s%s%n",
                formatMicroseconds(timeTicks), node, action, packet.id(), packet.bytes(), packet.type(), packet.staId(),
                packet.destinationNodeId(), route, details);
    }

    public void recordDelivery(Packet packet, Sta sta, long timeTicks) {
        output.printf(Locale.ROOT,
                "t_us=%s node=STA event=DELIVERED packet_id=%d bytes=%d type=%s destination_sta_id=%d sta_id=%d sfu_id=%d%n",
                formatMicroseconds(timeTicks), packet.id(), packet.bytes(), packet.type(), packet.destinationStaId(),
                sta.id(), sta.sfuId());
    }

    public void recordUplinkDelivery(Packet packet, int sfuId, long timeTicks, long endToEndTicks) {
        writeUplink(packet, timeTicks, "MFU", "DELIVERED", sfuId,
                " e2e_delay_us=" + formatMicroseconds(endToEndTicks));
    }

    public void recordEnqueue(Packet packet, long timeTicks, int sfuId, int queueDepth) {
        if (packet.isUplink()) {
            writeUplink(packet, timeTicks, "STA", "SFU_ENQUEUE", sfuId,
                    " generation_us=" + packet.generationTimeUs() + " queue_depth=" + queueDepth);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%s node=SFU event=SFU_ENQUEUE packet_id=%d bytes=%d type=%s mfu_arrival_us=%d queue_depth=%d destination_sta_id=%d sfu_id=%d%n",
                formatMicroseconds(timeTicks), packet.id(), packet.bytes(), packet.type(), packet.mfuArrivalUs(),
                queueDepth, packet.destinationStaId(), sfuId);
    }

    public void recordQueueSelection(Packet packet, long timeTicks, int sfuId, int vo, int vi, int be, int bk) {
        if (packet.isUplink()) {
            writeUplink(packet, timeTicks, "SFU", "QUEUE_SELECT", sfuId,
                    " vo_queue=" + vo + " vi_queue=" + vi + " be_queue=" + be + " bk_queue=" + bk);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%s node=SFU event=QUEUE_SELECT packet_id=%d type=%s vo_queue=%d vi_queue=%d be_queue=%d bk_queue=%d destination_sta_id=%d sfu_id=%d%n",
                formatMicroseconds(timeTicks), packet.id(), packet.type(), vo, vi, be, bk, packet.destinationStaId(),
                sfuId);
    }

    public void recordVirtualCollision(Packet loser, Packet winner, int sfuId, long ticks, int cwBefore, int cwAfter,
                                       int backoff) {
        output.printf(Locale.ROOT,
                "t_us=%s node=%s event=EDCA_VIRTUAL_COLLISION packet_id=%d type=%s direction=%s sfu_id=%d sta_id=%d "
                        + "winner_packet_id=%d cw_before=%d cw_after=%d backoff=%d%n",
                formatMicroseconds(ticks), loser.isUplink() ? "STA" : "SFU", loser.id(), loser.type(), loser.direction(),
                sfuId, loser.staId(), winner.id(), cwBefore, cwAfter, backoff);
    }

    public void recordCollisionPacket(Packet packet, int sfuId, long ticks, String action) {
        writeMacPacket(packet, sfuId, ticks, action, "");
    }

    public void recordMacRetryPacket(Packet packet, int sfuId, long ticks, String action, int retries) {
        writeMacPacket(packet, sfuId, ticks, action, " retry_count=" + retries);
    }

    public void recordMacRetry(Packet packet, int sfuId, long ticks, int retries, int cwBefore, int cwAfter, int backoff) {
        String detail = " retry_count=" + retries + " cw_before=" + cwBefore + " cw_after=" + cwAfter + " backoff=" + backoff;
        writeMacPacket(packet, sfuId, ticks, "WIFI_MAC_RETRY", detail);
    }

    private void writeMacPacket(Packet packet, int sfuId, long ticks, String action, String detail) {
        output.printf(Locale.ROOT, "t_us=%s node=%s event=%s packet_id=%d bytes=%d type=%s direction=%s sfu_id=%d sta_id=%d%s%n",
                formatMicroseconds(ticks), packet.isUplink() ? "STA" : "SFU", action, packet.id(), packet.bytes(),
                packet.type(), packet.direction(), sfuId, packet.staId(), detail);
    }

    public void recordCollisionEnd(int sfuId, long ticks) {
        output.printf(Locale.ROOT, "t_us=%s node=SFU event=WIFI_COLLISION_END sfu_id=%d%n",
                formatMicroseconds(ticks), sfuId);
    }

    public void recordCollisionSummary(long collisionEvents, int droppedPackets, long droppedBytes) {
        output.printf(Locale.ROOT, "summary=WIFI_COLLISION collision_events=%d dropped_packets=%d dropped_bytes=%d%n",
                collisionEvents, droppedPackets, droppedBytes);
    }

    public void recordRetrySummary(long retransmissions, long ackTimeouts, int retryLimitDrops) {
        output.printf(Locale.ROOT, "summary=WIFI_RETRY retransmissions=%d ack_timeouts=%d retry_limit_drops=%d%n",
                retransmissions, ackTimeouts, retryLimitDrops);
    }

    public void recordWifiTxopStart(Packet packet, int sfuId, long ticks, long limitNs) {
        writeMacPacket(packet, sfuId, ticks, "WIFI_TXOP_START", " limit_us=" + formatMicroseconds(limitNs));
    }

    public void recordWifiTxopEnd(Packet packet, int sfuId, long ticks, long limitNs, long durationTicks,
                                  int packetCount) {
        String detail = " limit_us=" + formatMicroseconds(limitNs) + " duration_us=" + formatMicroseconds(durationTicks)
                + " packet_count=" + packetCount;
        writeMacPacket(packet, sfuId, ticks, "WIFI_TXOP_END", detail);
    }

    public void recordTxopSummary(int txops, double durationUs, int transmittedPackets) {
        output.printf(Locale.ROOT, "summary=WIFI_TXOP txops=%d total_duration_us=%.3f transmitted_packets=%d%n",
                txops, durationUs, transmittedPackets);
    }

    public void recordWifiService(Packet packet, int sfuId, long queueEntryTicks, long startTicks, long endTicks,
                                  long endToEndTicks) {
        String startUs = formatMicroseconds(startTicks);
        String waitUs = formatMicroseconds(startTicks - queueEntryTicks);
        String serviceUs = formatMicroseconds(endTicks - startTicks);
        if (packet.isUplink()) {
            writeUplink(packet, endTicks, "SFU", "WIFI_TX_END", sfuId, " wifi_tx_start_us=" + startUs
                    + " wifi_queue_wait_us=" + waitUs + " wifi_service_us=" + serviceUs);
            return;
        }
        output.printf(Locale.ROOT,
                "t_us=%s node=SFU event=WIFI_TX_END packet_id=%d wifi_tx_start_us=%s wifi_queue_wait_us=%s "
                        + "wifi_service_us=%s e2e_delay_us=%s type=%s destination_sta_id=%d sfu_id=%d%n",
                formatMicroseconds(endTicks), packet.id(), startUs, waitUs, serviceUs,
                formatMicroseconds(endToEndTicks), packet.type(), packet.destinationStaId(), sfuId);
    }

    public void recordWirelessPhyRate(double rateMbps) {
        output.printf(Locale.ROOT, "summary=WIFI_PHY model=HE_SU phy_rate_mbps=%.6f%n", rateMbps);
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

    public void recordSinglePacketSummary(int deliveredPackets, long deliveredBytes, long delayTicks) {
        output.printf(Locale.ROOT, "delivered_packets=%d delivered_bytes=%d e2e_delay_us=%s%n",
                deliveredPackets, deliveredBytes, formatMicroseconds(delayTicks));
    }

    public void recordRunSummary(int deliveredPackets, long deliveredBytes, long busyTicks, long endTicks) {
        output.printf(Locale.ROOT,
                "delivered_packets=%d delivered_bytes=%d wifi_busy_us=%s wifi_idle_us=%s simulation_end_us=%s%n",
                deliveredPackets, deliveredBytes, formatMicroseconds(busyTicks),
                formatMicroseconds(endTicks - busyTicks), formatMicroseconds(endTicks));
    }
}
