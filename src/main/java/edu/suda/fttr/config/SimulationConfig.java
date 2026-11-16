package edu.suda.fttr.config;

/** 单报文场景的已校验输入。时间统一使用整数微秒，长度使用字节。 */
public final class SimulationConfig {
    private final int packetBytes;
    private final long packetArrivalUs;
    private final long fiberDelayUs;
    private final long wifiDelayUs;

    SimulationConfig(int packetBytes, long packetArrivalUs, long fiberDelayUs, long wifiDelayUs) {
        this.packetBytes = packetBytes;
        this.packetArrivalUs = packetArrivalUs;
        this.fiberDelayUs = fiberDelayUs;
        this.wifiDelayUs = wifiDelayUs;
    }

    public int packetBytes() {
        return packetBytes;
    }

    public long packetArrivalUs() {
        return packetArrivalUs;
    }

    public long fiberDelayUs() {
        return fiberDelayUs;
    }

    public long wifiDelayUs() {
        return wifiDelayUs;
    }
}
