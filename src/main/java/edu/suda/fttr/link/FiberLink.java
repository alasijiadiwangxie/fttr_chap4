package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

/** MFU 和 SFU 之间的逻辑光链路。每跳使用固定时延。 */
public final class FiberLink {
    private final LinkDelayModel delayModel;

    public FiberLink(LinkDelayModel delayModel) {
        this.delayModel = delayModel;
    }

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        EventType arrival = packet.isUplink() ? EventType.PACKET_ARRIVAL_AT_MFU : EventType.PACKET_ARRIVAL_AT_SFU;
        context.scheduleAfter(delayModel.delayUs(packet), arrival, packet, sfuId);
    }
}
