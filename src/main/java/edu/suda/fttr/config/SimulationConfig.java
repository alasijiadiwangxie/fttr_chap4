package edu.suda.fttr.config;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.traffic.TrafficGenerator;

import java.util.List;
import java.util.stream.Collectors;

/** 场景的已校验输入。时间统一使用整数微秒，长度使用字节。 */
public final class SimulationConfig {
    private final List<Event> inputEvents;
    private final int sfuCount;
    private final List<Integer> staSfuIds;
    private final long fiberDelayUs;
    private final long wifiDelayUs;
    private final List<TrafficGenerator.Source> downlinkPoissonSources;
    private final List<TrafficGenerator.Source> uplinkPoissonSources;
    private final boolean uplinkEnabled;

    SimulationConfig(List<Event> inputEvents, int sfuCount, List<Integer> staSfuIds, long fiberDelayUs, long wifiDelayUs,
                     List<TrafficGenerator.Source> downlinkPoissonSources,
                     List<TrafficGenerator.Source> uplinkPoissonSources, boolean uplinkEnabled) {
        this.inputEvents = List.copyOf(inputEvents);
        this.sfuCount = sfuCount;
        this.staSfuIds = List.copyOf(staSfuIds);
        this.fiberDelayUs = fiberDelayUs;
        this.wifiDelayUs = wifiDelayUs;
        this.downlinkPoissonSources = List.copyOf(downlinkPoissonSources);
        this.uplinkPoissonSources = List.copyOf(uplinkPoissonSources);
        this.uplinkEnabled = uplinkEnabled;
    }

    /** 首个报文的长度，兼容原有单包调用。多包场景从各报文读取长度。 */
    public int packetBytes() {
        return inputEvents.get(0).packet().bytes();
    }

    /** 兼容单包调用，多包时返回最早的生成时刻。 */
    public long packetArrivalUs() {
        return inputEvents.get(0).timeUs();
    }

    public List<Long> packetArrivalsUs() {
        return inputEvents.stream().map(Event::timeUs).collect(Collectors.toUnmodifiableList());
    }

    public List<Packet> packets() {
        return inputEvents.stream().map(Event::packet).collect(Collectors.toUnmodifiableList());
    }

    /** 手动和泊松输入均按生成时刻排列，由仿真器注入对应源节点。 */
    public List<Event> inputEvents() {
        return inputEvents;
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

    /** 下行泊松流按 SFU ID 排列，包含零到达率的流。下行未开启或使用手动流量时为空。 */
    public List<TrafficGenerator.Source> downlinkPoissonSources() {
        return downlinkPoissonSources;
    }

    /** 上行泊松流按 STA ID 排列，包含零到达率的流。上行未开启或使用手动流量时为空。 */
    public List<TrafficGenerator.Source> uplinkPoissonSources() {
        return uplinkPoissonSources;
    }

    public boolean uplinkEnabled() {
        return uplinkEnabled;
    }
}
