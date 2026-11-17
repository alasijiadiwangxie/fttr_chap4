package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

/** SFU 和关联 STA 之间的无线链路。收发均使用固定服务时长。 */
public final class WifiLink {
    private final LinkDelayModel delayModel;

    public WifiLink(LinkDelayModel delayModel) {
        this.delayModel = delayModel;
    }

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        EventType arrival = packet.isUplink() ? EventType.WIFI_ARRIVAL_AT_SFU : EventType.PACKET_ARRIVAL_AT_STA;
        context.scheduleAfter(delayModel.delayUs(packet), arrival, packet, sfuId);
    }
}
