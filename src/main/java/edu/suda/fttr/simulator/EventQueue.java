package edu.suda.fttr.simulator;

import java.util.Comparator;
import java.util.PriorityQueue;

/** 按时间取出事件。同刻先处理报文事件，再选队，各组内保持先入先出。 */
final class EventQueue {
    private final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry entry) -> entry.event.timeUs())
                    .thenComparingInt(entry -> entry.event.type() == EventType.WIFI_SELECT_NEXT ? 1 : 0)
                    .thenComparingLong(entry -> entry.sequence));
    private long nextSequence;

    void add(Event event) {
        // 同刻新生成的报文事件也先于选队，例如零光纤时延下的 SFU 到达。
        pending.add(new Entry(event, nextSequence++));
    }

    Event poll() {
        Entry entry = pending.poll();
        return entry == null ? null : entry.event;
    }

    private static final class Entry {
        private final Event event;
        private final long sequence;

        private Entry(Event event, long sequence) {
            this.event = event;
            this.sequence = sequence;
        }
    }
}
