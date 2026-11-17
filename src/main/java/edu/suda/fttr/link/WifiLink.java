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

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        EventType arrival = packet.isUplink() ? EventType.WIFI_ARRIVAL_AT_SFU : EventType.PACKET_ARRIVAL_AT_STA;
        if (wirelessPhy != null) {
            // DATA 到达独立于后续的 SIFS 和 ACK。此处使用含 PE 的完整数据 PPDU 时长。
            context.scheduleAfterNanoseconds(wirelessPhy.ppduDurationNs(packet.bytes()), arrival, packet, sfuId);
        } else {
            context.scheduleAfter(delayModel.delayUs(packet), arrival, packet, sfuId);
        }
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
