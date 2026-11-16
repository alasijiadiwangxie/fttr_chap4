package edu.suda.fttr.entity;

import edu.suda.fttr.link.FiberLink;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.List;

/** MFU 按固定的 STA–SFU 关联选择报文的逻辑下行光链路。 */
public final class Mfu {
    private final List<Integer> staSfuIds;
    private final List<FiberLink> downstreamFibers;

    public Mfu(List<Integer> staSfuIds, List<FiberLink> downstreamFibers) {
        this.staSfuIds = List.copyOf(staSfuIds);
        this.downstreamFibers = List.copyOf(downstreamFibers);
    }

    public void receive(Packet packet, SimulationContext context) {
        // 固定时延场景直接发送，链路起点仍作为独立事件保留。
        int sfuId = staSfuIds.get(packet.destinationStaId() - 1);
        context.scheduleAfter(0, EventType.FIBER_TX_START, packet, sfuId);
    }

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        downstreamFibers.get(sfuId - 1).transmit(packet, sfuId, context);
    }
}
