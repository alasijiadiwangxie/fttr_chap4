package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.Objects;

/** SFU 和关联 STA 之间的无线链路。上下行使用相同的传输时间模型。 */
public final class WifiLink {
    private final LinkDelayModel delayModel;
    private final WirelessPhy wirelessPhy;
    private final WifiFrameExchange frameExchange;

    public WifiLink(LinkDelayModel delayModel) {
        this.delayModel = Objects.requireNonNull(delayModel);
        this.wirelessPhy = null;
        this.frameExchange = null;
    }

    public WifiLink(WirelessPhy wirelessPhy) {
        this(wirelessPhy, null);
    }

    public WifiLink(WirelessPhy wirelessPhy, WifiFrameExchange frameExchange) {
        this.delayModel = null;
        this.wirelessPhy = Objects.requireNonNull(wirelessPhy);
        this.frameExchange = frameExchange;
    }

    public boolean hasAckExchange() {
        return frameExchange != null;
    }

    public long sifsNs() {
        return requireAckExchange().sifsNs();
    }

    /** 成功接收 DATA 时，从接收结束到正常 ACK 结束的预约时间。 */
    public long ackResponseDurationNs() {
        return requireAckExchange().responseDurationNs();
    }

    /** 由实际 DATA 与 ACK 时长组成的完整单帧交换，供 TXOP 判断剩余预算。 */
    public long exchangeDurationNs(Packet packet) {
        long responseNs = ackResponseDurationNs();
        return Math.addExact(dataDurationNs(packet), responseNs);
    }

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        EventType arrival = packet.isUplink() ? EventType.WIFI_ARRIVAL_AT_SFU : EventType.PACKET_ARRIVAL_AT_STA;
        scheduleDataEnd(packet, sfuId, context, arrival);
    }

    /** 碰撞的 DATA 发完整个 PPDU 后结束，完成事件只用于记录失败。 */
    public void transmitCollidedData(Packet packet, int sfuId, SimulationContext context) {
        scheduleDataEnd(packet, sfuId, context, EventType.WIFI_COLLISION_DATA_END);
    }

    private void scheduleDataEnd(Packet packet, int sfuId, SimulationContext context, EventType dataEnd) {
        if (wirelessPhy != null) {
            // DATA 到达独立于后续的 SIFS 和 ACK。此处使用含 PE 的完整数据 PPDU 时长。
            context.scheduleAfterNanoseconds(dataDurationNs(packet), dataEnd, packet, sfuId);
        } else {
            context.scheduleAfter(delayModel.delayUs(packet), dataEnd, packet, sfuId);
        }
    }

    private long dataDurationNs(Packet packet) {
        return wirelessPhy.ppduDurationNs(packet.bytes());
    }

    private WifiFrameExchange requireAckExchange() {
        if (!hasAckExchange()) {
            throw new IllegalStateException("DATA-SIFS-ACK exchange is required");
        }
        return frameExchange;
    }

    /** DATA 抵达后，由接收端等待 SIFS，再反向发送立即 ACK。 */
    public boolean scheduleAck(Packet packet, int sfuId, SimulationContext context) {
        if (frameExchange == null) {
            return false;
        }
        context.recordWifiDataEnd(packet, sfuId);
        context.scheduleAfterNanoseconds(frameExchange.sifsNs(), EventType.WIFI_ACK_START, packet, sfuId);
        return true;
    }

    public void transmitAck(Packet packet, int sfuId, SimulationContext context) {
        context.scheduleAfterNanoseconds(frameExchange.ackNs(), EventType.WIFI_ACK_END, packet, sfuId);
    }
}
