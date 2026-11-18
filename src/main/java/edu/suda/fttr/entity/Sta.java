package edu.suda.fttr.entity;

import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaState;
import edu.suda.fttr.simulator.SimulationContext;

/** 以唯一 ID 标识终端，并统计实际交付给该终端的下行报文。 */
public final class Sta {
    private final int id;
    private final int sfuId;
    private final EdcaState edcaState;
    private WifiChannel channel;
    private int receivedPackets;
    private long receivedBytes;
    // 单包时间差先用整数刻度计算，再换算为微秒累加。
    private double delaySumUs;

    public Sta(int id, int sfuId) {
        this(id, sfuId, EdcaParameters.defaults());
    }

    public Sta(int id, int sfuId, EdcaParameters edcaParameters) {
        if (id < 1) {
            throw new IllegalArgumentException("STA ID must be positive");
        }
        this.id = id;
        this.sfuId = sfuId;
        this.edcaState = EdcaState.forSta(id, edcaParameters);
    }

    public int id() {
        return id;
    }

    public int sfuId() {
        return sfuId;
    }

    public EdcaState edcaState() {
        return edcaState;
    }

    void attachChannel(int ownerSfuId, WifiChannel channel) {
        if (ownerSfuId != sfuId || this.channel != null) {
            throw new IllegalStateException("Cannot attach STA " + id + " to the channel of SFU " + ownerSfuId);
        }
        this.channel = channel;
    }

    /** STA 查询关联 SFU 的信道状态，关联建立时由 SFU 绑定同一个信道对象。 */
    public WifiChannel.State ccaState() {
        if (channel == null) {
            throw new IllegalStateException("STA " + id + " has no associated Wi-Fi channel");
        }
        return channel.state();
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
