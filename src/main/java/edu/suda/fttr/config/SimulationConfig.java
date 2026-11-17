package edu.suda.fttr.config;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.Event;

import java.util.List;
import java.util.stream.Collectors;

/** 场景的已校验输入。时间统一使用整数微秒，长度使用字节。 */
public final class SimulationConfig {
    private final List<Event> inputEvents;
    private final int sfuCount;
    private final List<Integer> staSfuIds;
    private final long fiberDelayUs;
    private final long wifiDelayUs;
    private final List<Long> downlinkTrafficWindowsUs;
    private final List<Long> uplinkTrafficWindowsUs;
    private final boolean uplinkEnabled;

    SimulationConfig(List<Event> inputEvents, int sfuCount, List<Integer> staSfuIds, long fiberDelayUs, long wifiDelayUs,
                     List<Long> downlinkTrafficWindowsUs, List<Long> uplinkTrafficWindowsUs, boolean uplinkEnabled) {
        this.inputEvents = List.copyOf(inputEvents);
        this.sfuCount = sfuCount;
        this.staSfuIds = List.copyOf(staSfuIds);
        this.fiberDelayUs = fiberDelayUs;
        this.wifiDelayUs = wifiDelayUs;
        this.downlinkTrafficWindowsUs = List.copyOf(downlinkTrafficWindowsUs);
        this.uplinkTrafficWindowsUs = List.copyOf(uplinkTrafficWindowsUs);
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

    /** 各 SFU 的下行泊松生成窗口，按 ID 排列。手动模式或未开启下行时为空。 */
    public List<Long> downlinkTrafficWindowsUs() {
        return downlinkTrafficWindowsUs;
    }

    /** 各 STA 的上行泊松生成窗口，按 ID 排列。手动模式或未开启上行时为空。 */
    public List<Long> uplinkTrafficWindowsUs() {
        return uplinkTrafficWindowsUs;
    }

    public boolean uplinkEnabled() {
        return uplinkEnabled;
    }
}
