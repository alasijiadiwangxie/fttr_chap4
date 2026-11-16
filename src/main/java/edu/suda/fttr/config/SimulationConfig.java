package edu.suda.fttr.config;

import java.util.List;

/** 单 SFU 场景的已校验输入。时间统一使用整数微秒，长度使用字节。 */
public final class SimulationConfig {
    private final int packetBytes;
    private final List<Long> packetArrivalsUs;
    private final long fiberDelayUs;
    private final long wifiDelayUs;

    SimulationConfig(int packetBytes, List<Long> packetArrivalsUs, long fiberDelayUs, long wifiDelayUs) {
        this.packetBytes = packetBytes;
        this.packetArrivalsUs = List.copyOf(packetArrivalsUs);
        this.fiberDelayUs = fiberDelayUs;
        this.wifiDelayUs = wifiDelayUs;
    }

    public int packetBytes() {
        return packetBytes;
    }

    /** 兼容单包调用，多包时返回最早的 MFU 到达时刻。 */
    public long packetArrivalUs() {
        return packetArrivalsUs.get(0);
    }

    public List<Long> packetArrivalsUs() {
        return packetArrivalsUs;
    }

    public long fiberDelayUs() {
        return fiberDelayUs;
    }

    public long wifiDelayUs() {
        return wifiDelayUs;
    }
}
