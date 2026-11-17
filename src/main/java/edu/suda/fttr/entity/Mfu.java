package edu.suda.fttr.entity;

import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.List;

/** MFU 按固定的 STA–SFU 关联发出下行报文，并接收上行报文。 */
public final class Mfu {
    private final List<Integer> staSfuIds;

    public Mfu(List<Integer> staSfuIds) {
        this.staSfuIds = List.copyOf(staSfuIds);
    }

    public void receive(Packet packet, SimulationContext context) {
        // 固定时延场景直接发送，链路起点仍作为独立事件保留。
        int sfuId = staSfuIds.get(packet.destinationStaId() - 1);
        context.scheduleAfter(0, EventType.FIBER_TX_START, packet, sfuId);
    }

    public void receiveUplink(Packet packet, int sfuId, SimulationContext context) {
        if (packet.destinationNodeId() != Packet.DEFAULT_MFU_ID || staSfuIds.get(packet.staId() - 1) != sfuId) {
            throw new IllegalStateException("Uplink packet " + packet.id() + " reached MFU on the wrong route");
        }
        context.recordUplinkDelivery(packet, sfuId);
    }
}
