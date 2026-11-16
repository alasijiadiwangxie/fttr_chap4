package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.trace.TraceLogger;

/** 每次运行使用独立的事件队列、微秒时钟和日志。 */
public final class SimulationContext {
    private final EventQueue events = new EventQueue();
    private final TraceLogger trace;
    private long nowUs;

    SimulationContext(TraceLogger trace) {
        this.trace = trace;
    }

    public void scheduleAfter(long delayUs, EventType type, Packet packet) {
        if (delayUs < 0) {
            throw new IllegalArgumentException("Event delay must be non-negative");
        }
        // 以当前事件发生的时刻为基准安排下一跳。零时延允许，但时钟不能倒退。
        events.add(new Event(Math.addExact(nowUs, delayUs), type, packet));
    }

    Event nextEvent() {
        Event event = events.poll();
        if (event != null) {
            nowUs = event.timeUs();
            trace.recordEvent(event);
        }
        return event;
    }

    public void recordDelivery(Packet packet) {
        trace.recordDelivery(packet, nowUs - packet.mfuArrivalUs());
    }
}
