package edu.suda.fttr.config;

import edu.suda.fttr.entity.Packet;

import java.util.List;
import java.util.stream.Collectors;

/** 单 SFU 场景的已校验输入。时间统一使用整数微秒，长度使用字节。 */
public final class SimulationConfig {
    private final List<Packet> packets;
    private final long fiberDelayUs;
    private final long wifiDelayUs;

    SimulationConfig(List<Packet> packets, long fiberDelayUs, long wifiDelayUs) {
        this.packets = List.copyOf(packets);
        this.fiberDelayUs = fiberDelayUs;
        this.wifiDelayUs = wifiDelayUs;
    }

    /** 首个报文的长度，兼容原有单包调用。多包场景从各报文读取长度。 */
    public int packetBytes() {
        return packets.get(0).bytes();
    }

    /** 兼容单包调用，多包时返回最早的 MFU 到达时刻。 */
    public long packetArrivalUs() {
        return packets.get(0).mfuArrivalUs();
    }

    public List<Long> packetArrivalsUs() {
        return packets.stream().map(Packet::mfuArrivalUs).collect(Collectors.toUnmodifiableList());
    }

    public List<Packet> packets() {
        return packets;
    }

    public long fiberDelayUs() {
        return fiberDelayUs;
    }

    public long wifiDelayUs() {
        return wifiDelayUs;
    }
}
