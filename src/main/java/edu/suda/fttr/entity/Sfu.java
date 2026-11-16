package edu.suda.fttr.entity;

import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

/** SFU 到达后才安排 Wi-Fi 服务，为后续队列和 AP 状态提供边界。 */
public final class Sfu {
    private final WifiLink wifi;

    public Sfu(WifiLink wifi) {
        this.wifi = wifi;
    }

    public void receive(Packet packet, SimulationContext context) {
        context.scheduleAfter(0, EventType.WIFI_TX_START, packet);
    }

    public void transmit(Packet packet, SimulationContext context) {
        wifi.transmit(packet, context);
    }
}
