package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;

/** 事件不可变。报文进入 MFU 后，事件携带对应的 SFU ID，选队事件也据此定位。 */
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

    public long timeUs() {
        return timeUs;
    }

    public EventType type() {
        return type;
    }

    public Packet packet() {
        return packet;
    }

    /** 0 表示尚未由 MFU 转发。 */
    public int sfuId() {
        return sfuId;
    }
}
