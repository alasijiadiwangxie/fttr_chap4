package edu.suda.fttr.simulator;

import edu.suda.fttr.entity.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class EventQueueTest {
    @Test
    void pollsByTimeAndKeepsInsertionOrderForTies() {
        Packet packet = new Packet(1, 1200, 5);
        Event late = new Event(9, EventType.PACKET_ARRIVAL_AT_STA, packet);
        Event first = Event.packetGenerated(packet);
        Event second = new Event(5, EventType.FIBER_TX_START, packet);
        Event sameTimeAddedDuringExecution = new Event(5, EventType.PACKET_ARRIVAL_AT_SFU, packet);

        EventQueue queue = new EventQueue();
        queue.add(late);
        queue.add(first);
        queue.add(second);
        assertSame(first, queue.poll());

        // 模拟处理期间又安排一个同刻事件。已在队列中的事件保持优先。
        queue.add(sameTimeAddedDuringExecution);
        assertSame(second, queue.poll());
        assertSame(sameTimeAddedDuringExecution, queue.poll());
        assertSame(late, queue.poll());
        assertNull(queue.poll());
    }
}
