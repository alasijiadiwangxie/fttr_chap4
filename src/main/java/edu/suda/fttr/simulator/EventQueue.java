package edu.suda.fttr.simulator;

import java.util.Comparator;
import java.util.PriorityQueue;

/** 按时间取出事件。同一微秒发生的事件按先入先出处理。 */
final class EventQueue {
    private final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry entry) -> entry.event.timeUs())
                    .thenComparingLong(entry -> entry.sequence));
    private long nextSequence;

    void add(Event event) {
        // 时间相同时，处理期间新加入的事件排在原有同刻事件之后。
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
