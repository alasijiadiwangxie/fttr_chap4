package fttr.chap4;

import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/** 仿真时钟只在处理事件时推进，事件可以在执行过程中安排后续事件。 */
final class EventQueue {
    private final PriorityQueue<Event> pending = new PriorityQueue<>(
            Comparator.comparingLong((Event event) -> event.timeUs)
                    .thenComparingLong(event -> event.sequence));
    private long nowUs;
    private long nextSequence;

    long nowUs() {
        return nowUs;
    }

    void scheduleAt(long timeUs, Runnable action) {
        if (timeUs < nowUs) {
            throw new IllegalArgumentException("Cannot schedule an event before the current simulation time");
        }
        // 同一微秒内按入队顺序执行。回调新加的即时事件排在已入队的同刻事件之后。
        pending.add(new Event(timeUs, nextSequence++, Objects.requireNonNull(action)));
    }

    void scheduleAfter(long delayUs, Runnable action) {
        if (delayUs < 0) {
            throw new IllegalArgumentException("Event delay must be non-negative");
        }
        scheduleAt(Math.addExact(nowUs, delayUs), action);
    }

    void run() {
        while (!pending.isEmpty()) {
            Event event = pending.remove();
            // 先推进时钟，回调才能以当前事件的时刻为基准安排下一跳。
            nowUs = event.timeUs;
            event.action.run();
        }
    }

    private static final class Event {
        private final long timeUs;
        private final long sequence;
        private final Runnable action;

        private Event(long timeUs, long sequence, Runnable action) {
            this.timeUs = timeUs;
            this.sequence = sequence;
            this.action = action;
        }
    }
}
