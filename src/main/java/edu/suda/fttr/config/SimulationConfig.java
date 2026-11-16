package edu.suda.fttr.config;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.traffic.TrafficGenerator;

import java.util.List;
import java.util.stream.Collectors;

/** 下行场景的已校验输入。时间统一使用整数微秒，长度使用字节。 */
public final class SimulationConfig {
    private final List<Packet> packets;
    private final int sfuCount;
    private final List<Integer> staSfuIds;
    private final long fiberDelayUs;
    private final long wifiDelayUs;
    private final List<TrafficGenerator.Source> poissonSources;

    SimulationConfig(List<Packet> packets, int sfuCount, List<Integer> staSfuIds, long fiberDelayUs, long wifiDelayUs,
                     List<TrafficGenerator.Source> poissonSources) {
        this.packets = List.copyOf(packets);
        this.sfuCount = sfuCount;
        this.staSfuIds = List.copyOf(staSfuIds);
        this.fiberDelayUs = fiberDelayUs;
        this.wifiDelayUs = wifiDelayUs;
        this.poissonSources = List.copyOf(poissonSources);
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

    public int staCount() {
        return staSfuIds.size();
    }

    public int sfuCount() {
        return sfuCount;
    }

    /** 列表按 STA ID 排列，STA 1 的关联位于下标 0。 */
    public List<Integer> staSfuIds() {
        return staSfuIds;
    }

    public long fiberDelayUs() {
        return fiberDelayUs;
    }

    public long wifiDelayUs() {
        return wifiDelayUs;
    }

    /** 泊松模式按 SFU ID 排列，包含零到达率的源。手动模式返回空列表。 */
    public List<TrafficGenerator.Source> poissonSources() {
        return poissonSources;
    }
}
