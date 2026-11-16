package edu.suda.fttr.entity;

import edu.suda.fttr.link.FiberLink;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

/** MFU 接收入口报文，并提交给下行光链路。 */
public final class Mfu {
    private final FiberLink fiber;

    public Mfu(FiberLink fiber) {
        this.fiber = fiber;
    }

    public void receive(Packet packet, SimulationContext context) {
        // 固定时延场景直接发送，链路起点仍作为独立事件保留。
        context.scheduleAfter(0, EventType.FIBER_TX_START, packet);
    }

    public void transmit(Packet packet, SimulationContext context) {
        fiber.transmit(packet, context);
    }
}
