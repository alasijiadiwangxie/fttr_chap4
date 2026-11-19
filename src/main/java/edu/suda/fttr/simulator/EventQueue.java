package edu.suda.fttr.simulator;

import java.util.Comparator;
import java.util.PriorityQueue;

/** 按时间取出事件。同刻先处理报文和收发边界，再选队或推进接入计时器。 */
final class EventQueue {
    private final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry entry) -> entry.event.timeTicks())
                    .thenComparingInt(entry -> accessPriority(entry.event.type()))
                    .thenComparingLong(entry -> entry.sequence));
    private long nextSequence;

    private static int accessPriority(EventType type) {
        // 先处理报文与忙闲边界，再推进所有计时器，最后合并同刻归零的候选。
        if (type == EventType.WIFI_ACCESS_RESOLVE) {
            return 2;
        }
        // 一包恰好在光帧边界发完时，先释放前一帧资源，再执行本帧的授权。
        return type == EventType.WIFI_SELECT_NEXT || type == EventType.WIFI_ACCESS_TIMER
                || type == EventType.PON_UPLINK_FRAME ? 1 : 0;
    }

    void add(Event event) {
        // 同刻新生成的报文事件也先于选队，例如零光纤时延下的 SFU 到达。
        pending.add(new Entry(event, nextSequence++));
    }

    void cancel(Event event) {
        // 从队列删除未到期的事件，避免失效的计时器拉长仿真观察窗口。
        pending.removeIf(entry -> entry.event == event);
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
