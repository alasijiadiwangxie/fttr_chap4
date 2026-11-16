package edu.suda.fttr.entity;

import edu.suda.fttr.simulator.SimulationContext;

/** 只在报文完整到达 STA 时记录有效交付。 */
public final class Sta {
    public void receive(Packet packet, SimulationContext context) {
        context.recordDelivery(packet);
    }
}
