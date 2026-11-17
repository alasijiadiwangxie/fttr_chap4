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

    public WifiLink(LinkDelayModel delayModel) {
        this.delayModel = Objects.requireNonNull(delayModel);
        this.wirelessPhy = null;
    }

    public WifiLink(WirelessPhy wirelessPhy) {
        this.delayModel = null;
        this.wirelessPhy = Objects.requireNonNull(wirelessPhy);
    }

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        EventType arrival = packet.isUplink() ? EventType.WIFI_ARRIVAL_AT_SFU : EventType.PACKET_ARRIVAL_AT_STA;
        if (wirelessPhy != null) {
            // 这里只占用数据 PPDU 的发送时间。接入等待、传播、SIFS 和 ACK 等各有独立的时序语义。
            context.scheduleAfterNanoseconds(wirelessPhy.ppduDurationNs(packet.bytes()), arrival, packet, sfuId);
        } else {
            context.scheduleAfter(delayModel.delayUs(packet), arrival, packet, sfuId);
        }
    }
}
