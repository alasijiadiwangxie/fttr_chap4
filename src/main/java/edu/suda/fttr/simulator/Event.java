package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;

/** 队列中的事件不可变，携带发生时刻、动作和同一条下行报文。 */
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
