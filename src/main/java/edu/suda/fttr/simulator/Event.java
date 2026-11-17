package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;

/** 事件不可变。生成事件标记报文的入口时刻，进入链路后的事件可携带 SFU ID。 */
public final class Event {
    private final long timeUs;
    private final EventType type;
    private final Packet packet;
    private final int sfuId;

    Event(long timeUs, EventType type, Packet packet) {
        this(timeUs, type, packet, 0);
    }

    Event(long timeUs, EventType type, Packet packet, int sfuId) {
        this.timeUs = timeUs;
        this.type = type;
        this.packet = packet;
        this.sfuId = sfuId;
    }

    /** 统一的输入事件。执行时由仿真器根据报文方向和源节点选择入口。 */
    public static Event packetGenerated(Packet packet) {
        return new Event(packet.generationTimeUs(), EventType.PACKET_GENERATED, packet);
    }

    public long timeUs() {
        return timeUs;
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
}
