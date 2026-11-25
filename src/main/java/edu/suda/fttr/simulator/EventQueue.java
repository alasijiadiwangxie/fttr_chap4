package edu.suda.fttr.simulator;

import java.util.Comparator;
import java.util.PriorityQueue;

/** 按时间取出事件。同刻先处理报文和收发边界，再选队或推进接入计时器。 */
final class EventQueue {
    private final PriorityQueue<Entry> pending = new PriorityQueue<>(
            Comparator.comparingLong((Entry entry) -> entry.event.timeTicks())
                    .thenComparingInt(entry -> accessPriority(entry.event))
                    .thenComparingLong(entry -> entry.sequence));
    private long nextSequence;

    private static int accessPriority(Event event) {
        EventType type = event.type();
        if (type == EventType.WIFI_EXTERNAL_CCA_EDGE) {
            // [start,end) 的外部 CCA 在同刻任何新的接入或收发动作之前生效。
            return -3;
        }
        if (type == EventType.WIFI_AP_SWITCH_REQUEST) {
            // 先登记同刻请求。已占用的交换照常完成，尚未开始的新竞争需等切换完成。
            return -2;
        }
        if (type == EventType.FIBER_TX_END && event.dbaGrant() != null) {
            return -1; // 真实DATA在半开窗口末先释放资源，下一帧控制/装载可在同刻启动。
        }
        // 入口和计时器先结算。在线切换可以在发射仲裁前安全冻结接入。
        if (type == EventType.WIFI_AP_MODE_DECISION) {
            return 2;
        }
        if (type == EventType.WIFI_ACCESS_RESOLVE) {
            return 3;
        }
        // 一包恰好在光帧边界发完时，先释放前一帧资源，再执行本帧的授权。
        return type == EventType.WIFI_SELECT_NEXT || type == EventType.WIFI_ACCESS_TIMER
                || type == EventType.WIFI_CHANNEL_MANAGEMENT_START
                || type == EventType.PON_UPLINK_FRAME || type == EventType.PON_DBA_SLOT_END ? 1 : 0;
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

    Event peek() {
        Entry entry = pending.peek();
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
