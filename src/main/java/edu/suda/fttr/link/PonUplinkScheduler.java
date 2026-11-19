package edu.suda.fttr.link;

import java.util.Comparator;
import java.util.PriorityQueue;

/** 当前集中调度策略。只仲裁各 SFU 的候选队首，授予后的传输由链路执行。 */
final class PonUplinkScheduler {
    private final PriorityQueue<PonUplink.Request> heads = new PriorityQueue<>(
            Comparator.comparingLong(PonUplink.Request::enqueueTicks)
                    .thenComparingLong(PonUplink.Request::sequence));

    void submitHead(PonUplink.Request request) {
        heads.add(request);
    }

    PonUplink.Request pollHead() {
        return heads.poll();
    }
}
