package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

/** MFU 到 SFU 的光链路。 */
public final class FiberLink {
    private final LinkDelayModel delayModel;

    public FiberLink(LinkDelayModel delayModel) {
        this.delayModel = delayModel;
    }

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        context.scheduleAfter(delayModel.delayUs(packet), EventType.PACKET_ARRIVAL_AT_SFU, packet, sfuId);
    }
}
