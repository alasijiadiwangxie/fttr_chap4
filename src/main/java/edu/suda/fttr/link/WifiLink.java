package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

/** SFU 到 STA 的无线链路。第一阶段将一次成功服务折算为固定时长。 */
public final class WifiLink {
    private final LinkDelayModel delayModel;

    public WifiLink(LinkDelayModel delayModel) {
        this.delayModel = delayModel;
    }

    public void transmit(Packet packet, SimulationContext context) {
        context.scheduleAfter(delayModel.delayUs(packet), EventType.PACKET_ARRIVAL_AT_STA, packet);
    }
}
