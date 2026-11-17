package edu.suda.fttr.entity;

import edu.suda.fttr.simulator.SimulationContext;

/** 以唯一 ID 标识终端，并统计实际交付给该终端的下行报文。 */
public final class Sta {
    private final int id;
    private final int sfuId;
    private int receivedPackets;
    private long receivedBytes;
    // 单包时间差先用整数刻度计算，再换算为微秒累加。
    private double delaySumUs;

    public Sta(int id, int sfuId) {
        if (id < 1) {
            throw new IllegalArgumentException("STA ID must be positive");
        }
        this.id = id;
        this.sfuId = sfuId;
    }

    public int id() {
        return id;
    }

    public int sfuId() {
        return sfuId;
    }

    public void receive(Packet packet, SimulationContext context) {
        if (packet.destinationStaId() != id) {
            throw new IllegalStateException("Packet " + packet.id() + " reached the wrong STA: " + id);
        }
        receivedPackets++;
        receivedBytes += packet.bytes();
        delaySumUs += context.ticksToMicroseconds(context.elapsedSinceGenerationTicks(packet));
        context.recordDelivery(packet, this);
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
