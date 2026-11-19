package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.PonUplinkReport;

/** 事件不可变。生成事件标记报文的入口时刻，进入链路后的事件可携带 SFU ID。 */
public final class Event {
    private final long timeTicks;
    private final boolean nanosecondClock;
    private final EventType type;
    private final Packet packet;
    private final int sfuId;
    private final PonUplinkReport uplinkReport;

    Event(long timeUs, EventType type, Packet packet) {
        this(timeUs, type, packet, 0);
    }

    Event(long timeUs, EventType type, Packet packet, int sfuId) {
        this(timeUs, false, type, packet, sfuId, null);
    }

    private Event(long timeTicks, boolean nanosecondClock, EventType type, Packet packet, int sfuId,
                  PonUplinkReport uplinkReport) {
        this.timeTicks = timeTicks;
        this.nanosecondClock = nanosecondClock;
        this.type = type;
        this.packet = packet;
        this.sfuId = sfuId;
        this.uplinkReport = uplinkReport;
    }

    /** 统一的输入事件。执行时由仿真器根据报文方向和源节点选择入口。 */
    public static Event packetGenerated(Packet packet) {
        return new Event(packet.generationTimeUs(), EventType.PACKET_GENERATED, packet);
    }

    static Event atTicks(long timeTicks, boolean nanosecondClock, EventType type, Packet packet, int sfuId) {
        return new Event(timeTicks, nanosecondClock, type, packet, sfuId, null);
    }

    static Event uplinkReportReception(long timeNs, Packet carrier, PonUplinkReport report) {
        return new Event(timeNs, true, EventType.PON_UPLINK_REPORT_RX, carrier, report.sfuId(), report);
    }

    /** 配置中的输入事件使用整数微秒。内部的小数微秒事件不得用此方法取整。 */
    public long timeUs() {
        if (!nanosecondClock) {
            return timeTicks;
        }
        if (timeTicks % 1_000 != 0) {
            throw new IllegalStateException("Event time is not a whole microsecond");
        }
        return timeTicks / 1_000;
    }

    /** 同一队列内的事件使用相同的时钟刻度。 */
    public long timeTicks() {
        return timeTicks;
    }

    public EventType type() {
        return type;
    }

    public Packet packet() {
        return packet;
    }

    /** 0 表示该事件尚未关联 SFU。 */
    public int sfuId() {
        return sfuId;
    }

    /** REPORT 接收事件携带不可变快照，packet() 仍标识所捎带的 DATA。 */
    public PonUplinkReport uplinkReport() {
        return uplinkReport;
    }
}
