package edu.suda.fttr.entity;

import edu.suda.fttr.simulator.SimulationContext;

/** 以唯一 ID 标识终端，并统计实际交付给该终端的下行报文。 */
public final class Sta {
    private final int id;
    private int receivedPackets;
    private long receivedBytes;
    // 单包时延由微秒时钟精确计算，累加用 double 避免跨报文累计时溢出 long。
    private double delaySumUs;

    public Sta(int id) {
        if (id < 1) {
            throw new IllegalArgumentException("STA ID must be positive");
        }
        this.id = id;
    }

    public int id() {
        return id;
    }

    public void receive(Packet packet, SimulationContext context) {
        if (packet.destinationStaId() != id) {
            throw new IllegalStateException("Packet " + packet.id() + " reached the wrong STA: " + id);
        }
        receivedPackets++;
        receivedBytes += packet.bytes();
        delaySumUs += context.nowUs() - packet.mfuArrivalUs();
        context.recordDelivery(packet, id);
    }

    public int receivedPackets() {
        return receivedPackets;
    }

    public long receivedBytes() {
        return receivedBytes;
    }

    public double averageDelayUs() {
        return receivedPackets == 0 ? 0.0 : delaySumUs / receivedPackets;
    }
}
