package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;

/** 事件不可变。报文事件携带原报文，选队事件只负责唤起 SFU 调度。 */
public final class Event {
    private final long timeUs;
    private final EventType type;
    private final Packet packet;

    Event(long timeUs, EventType type, Packet packet) {
        this.timeUs = timeUs;
        this.type = type;
        this.packet = packet;
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
}
