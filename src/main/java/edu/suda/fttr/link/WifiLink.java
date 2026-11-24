package edu.suda.fttr.link;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.phy.DownlinkDecoder;
import edu.suda.fttr.phy.DownlinkDecoder.ChannelSample;
import edu.suda.fttr.phy.SinrPerModel;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.IntUnaryOperator;

/** SFU 和关联 STA 之间的无线链路。上下行使用相同的传输时间模型。 */
public final class WifiLink {
    private final LinkDelayModel delayModel;
    private final WirelessPhy wirelessPhy;
    private final WifiFrameExchange frameExchange;
    private final Map<Integer, WirelessPhy> stationPhys;
    private final DownlinkDecoder decoder;
    private final Map<Packet, DecodeAttempt> downlinkAttempts;

    public WifiLink(LinkDelayModel delayModel) {
        this.delayModel = Objects.requireNonNull(delayModel);
        this.wirelessPhy = null;
        this.frameExchange = null;
        this.stationPhys = Map.of();
        this.decoder = null;
        this.downlinkAttempts = Map.of();
    }

    public WifiLink(WirelessPhy wirelessPhy) {
        this(wirelessPhy, null);
    }

    public WifiLink(WirelessPhy wirelessPhy, WifiFrameExchange frameExchange) {
        this(wirelessPhy, frameExchange, Map.of());
    }

    /** 有静态 AP 模式时，每个关联 STA 使用已经校验的实际 DATA PHY。 */
    public WifiLink(WirelessPhy wirelessPhy, WifiFrameExchange frameExchange, Map<Integer, WirelessPhy> stationPhys) {
        this(wirelessPhy, frameExchange, stationPhys, null);
    }

    public WifiLink(WirelessPhy wirelessPhy, WifiFrameExchange frameExchange, Map<Integer, WirelessPhy> stationPhys,
                    DownlinkDecoder decoder) {
        this.delayModel = null;
        this.wirelessPhy = Objects.requireNonNull(wirelessPhy);
        this.frameExchange = frameExchange;
        this.stationPhys = Map.copyOf(stationPhys);
        this.decoder = decoder;
        this.downlinkAttempts = decoder == null ? Map.of() : new HashMap<>();
    }

    public boolean modelsDownlinkErrors(Packet head) {
        return decoder != null && !head.isUplink();
    }

    public void beginDownlinkDecode(Packet packet, int sfuId, int retries, boolean collision,
                                    SimulationContext context) {
        if (modelsDownlinkErrors(packet)) {
            beginDownlinkDecode(packet, sfuId, List.of(packet), index -> retries, collision, context);
        }
    }

    /** PHY 在 PPDU 起点冻结快照。SFU 只能在 DATA 结束时收到解码位图。 */
    public void beginDownlinkDecode(Packet head, int sfuId, List<Packet> members, IntUnaryOperator retries,
                                    boolean collision, SimulationContext context) {
        WirelessPhy phy = dataPhy(head);
        long startNs = context.nowNanoseconds();
        int bandwidthMhz = phy.bandwidthMhz();
        ChannelSample sample = collision ? null : decoder.snapshotAtStart(sfuId, head.staId(), startNs, bandwidthMhz);
        int frequencyBlocks = decoder.frequencyBlocks()
                ? DownlinkDecoder.subchannelCountForBandwidth(bandwidthMhz) : 0;
        List<DecodeMember> results = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            Packet packet = members.get(index);
            int attempt = retries.applyAsInt(index);
            int mpduBytes = phy.mpduBytes(packet.bytes());
            SinrPerModel.Decision decision = collision ? null : decoder.evaluate(phy.mcs(), sample, mpduBytes,
                    sfuId, packet.staId(), packet.id(), attempt);
            results.add(new DecodeMember(packet, attempt, mpduBytes, decision));
        }
        DecodeAttempt pending = new DecodeAttempt(startNs, phy.mcs(), sample, frequencyBlocks, results);
        if (downlinkAttempts.putIfAbsent(head, pending) != null) {
            throw new IllegalStateException("DATA PPDU already pending for packet " + head.id());
        }
    }

    /** 空值表示现有无模型分支，0 表示这次 PPDU 没有可交付的成员。 */
    public OptionalLong finishDownlinkDecode(Packet head, int sfuId, SimulationContext context) {
        if (!modelsDownlinkErrors(head)) {
            return OptionalLong.empty();
        }
        DecodeAttempt attempt = downlinkAttempts.remove(head);
        if (attempt == null) {
            throw new IllegalStateException("No DATA PPDU pending for packet " + head.id());
        }
        long bitmap = 0;
        for (int index = 0; index < attempt.members.size(); index++) {
            DecodeMember member = attempt.members.get(index);
            context.recordWifiMpduDecode(head, member.packet, sfuId, attempt.startNs, member.attempt,
                    attempt.mcs, member.mpduBytes, attempt.sample, attempt.frequencyBlocks, member.decision);
            if (member.decision != null && member.decision.decoded()) {
                bitmap |= 1L << index;
            }
        }
        return OptionalLong.of(bitmap);
    }

    public boolean hasAckExchange() {
        return frameExchange != null;
    }

    public long sifsNs() {
        return requireAckExchange().sifsNs();
    }

    /** 成功接收 DATA 时，从接收结束到正常 ACK 结束的预约时间。 */
    public long ackResponseDurationNs() {
        return requireAckExchange().responseDurationNs();
    }

    public long blockAckNs() {
        return requireAckExchange().blockAckNs();
    }

    public long blockAckResponseDurationNs() {
        return requireAckExchange().blockAckResponseDurationNs();
    }

    /** APEP 只对后面还有 MPDU 的子帧补齐 0–3 B。末尾由 PHY 处理填充。 */
    public int ampduBytes(Packet head, List<Packet> packets) {
        WirelessPhy phy = dataPhy(head);
        int apepBytes = 0;
        for (Packet packet : packets) {
            int alignment = (4 - apepBytes % 4) % 4;
            apepBytes = Math.addExact(apepBytes, alignment + phy.ampduSubframeBytes(packet.bytes()));
        }
        return apepBytes;
    }

    public long ampduDurationNs(Packet head, int apepBytes) {
        return dataPhy(head).ampduPpduTiming(apepBytes).durationNs();
    }

    /** 由实际 DATA 与 ACK 时长组成的完整单帧交换，供 TXOP 判断剩余预算。 */
    public long exchangeDurationNs(Packet packet) {
        long responseNs = ackResponseDurationNs();
        return Math.addExact(dataDurationNs(packet), responseNs);
    }

    public void transmit(Packet packet, int sfuId, SimulationContext context) {
        EventType arrival = packet.isUplink() ? EventType.WIFI_ARRIVAL_AT_SFU : EventType.PACKET_ARRIVAL_AT_STA;
        scheduleDataEnd(packet, sfuId, context, arrival);
    }

    /** 碰撞的 DATA 发完整个 PPDU 后结束，完成事件只用于记录失败。 */
    public void transmitCollidedData(Packet packet, int sfuId, SimulationContext context) {
        scheduleDataEnd(packet, sfuId, context, EventType.WIFI_COLLISION_DATA_END);
    }

    private void scheduleDataEnd(Packet packet, int sfuId, SimulationContext context, EventType dataEnd) {
        if (wirelessPhy != null) {
            // DATA 到达独立于后续的 SIFS 和 ACK。此处使用含 PE 的完整数据 PPDU 时长。
            context.scheduleAfterNanoseconds(dataDurationNs(packet), dataEnd, packet, sfuId);
        } else {
            context.scheduleAfter(delayModel.delayUs(packet), dataEnd, packet, sfuId);
        }
    }

    private long dataDurationNs(Packet packet) {
        // 上行取源 STA，下行取目的 STA。正常 DATA、碰撞 DATA 和 TXOP 都复用这一次选取规则。
        return dataPhy(packet).ppduDurationNs(packet.bytes());
    }

    private WirelessPhy dataPhy(Packet packet) {
        return stationPhys.getOrDefault(packet.staId(), wirelessPhy);
    }

    private WifiFrameExchange requireAckExchange() {
        if (!hasAckExchange()) {
            throw new IllegalStateException("DATA-SIFS-ACK exchange is required");
        }
        return frameExchange;
    }

    /** DATA 抵达后，由接收端等待 SIFS，再反向发送立即 ACK。 */
    public boolean scheduleAck(Packet packet, int sfuId, SimulationContext context) {
        if (frameExchange == null) {
            return false;
        }
        context.recordWifiDataEnd(packet, sfuId);
        context.scheduleAfterNanoseconds(frameExchange.sifsNs(), EventType.WIFI_ACK_START, packet, sfuId);
        return true;
    }

    public void transmitAck(Packet packet, int sfuId, SimulationContext context) {
        context.scheduleAfterNanoseconds(frameExchange.ackNs(), EventType.WIFI_ACK_END, packet, sfuId);
    }

    private static final class DecodeAttempt {
        private final long startNs;
        private final int mcs;
        private final ChannelSample sample;
        private final int frequencyBlocks;
        private final List<DecodeMember> members;

        private DecodeAttempt(long startNs, int mcs, ChannelSample sample, int frequencyBlocks,
                              List<DecodeMember> members) {
            this.startNs = startNs;
            this.mcs = mcs;
            this.sample = sample;
            this.frequencyBlocks = frequencyBlocks;
            this.members = members;
        }
    }

    private static final class DecodeMember {
        private final Packet packet;
        private final int attempt;
        private final int mpduBytes;
        private final SinrPerModel.Decision decision;

        private DecodeMember(Packet packet, int attempt, int mpduBytes, SinrPerModel.Decision decision) {
            this.packet = packet;
            this.attempt = attempt;
            this.mpduBytes = mpduBytes;
            this.decision = decision;
        }
    }
}
