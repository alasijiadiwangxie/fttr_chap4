package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonDownlink;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.List;

/** MFU 按固定的 STA–SFU 关联发出下行报文，并接收上行报文。 */
public final class Mfu {
    private final List<Integer> staSfuIds;
    private final PonDownlink ponDownlink;

    public Mfu(List<Integer> staSfuIds) {
        this(staSfuIds, null);
    }

    public Mfu(List<Integer> staSfuIds, PonDownlink ponDownlink) {
        this.staSfuIds = List.copyOf(staSfuIds);
        this.ponDownlink = ponDownlink;
    }

    public void receive(Packet packet, SimulationContext context) {
        int sfuId = staSfuIds.get(packet.destinationStaId() - 1);
        if (ponDownlink == null) {
            // 固定时延场景仍各自调度光链路起点，没有共享 FIFO。
            context.scheduleAfter(0, EventType.FIBER_TX_START, packet, sfuId);
        } else {
            ponDownlink.enqueue(packet, sfuId, context);
        }
    }

    public void receiveUplink(Packet packet, int sfuId, SimulationContext context) {
        if (packet.destinationNodeId() != Packet.DEFAULT_MFU_ID || staSfuIds.get(packet.staId() - 1) != sfuId) {
            throw new IllegalStateException("Uplink packet " + packet.id() + " reached MFU on the wrong route");
        }
        context.recordUplinkDelivery(packet, sfuId);
    }
}
