package edu.suda.fttr.entity;

import edu.suda.fttr.phy.ApCapabilityMode;

/**
 * 一台 SFU 的 AP 无线活动账本。B2 中能力模式固定，所有状态时长均归入该模式。
 */
public final class ApActivity {
    public enum State { IDLE, TX, RX }

    private final ApCapabilityMode mode;
    private final long fixedObservationEndNs;
    private final long[] stateDurationsNs = new long[State.values().length];
    private State state = State.IDLE;
    private long lastEventNs;
    private int transmittingFrames;
    private int receivingFrames;
    private boolean finished;

    /** 从 0 ns 开始观察。终点为 -1 时使用仿真的自然结束时间。 */
    public ApActivity(ApCapabilityMode mode, long fixedObservationEndNs) {
        if (fixedObservationEndNs != -1 && fixedObservationEndNs <= 0) {
            throw new IllegalArgumentException("AP activity observation end must be positive or -1");
        }
        this.mode = mode;
        this.fixedObservationEndNs = fixedObservationEndNs;
    }

    /** 原有未配置 LCM/HCM 的 SFU 返回 null。 */
    public ApCapabilityMode mode() {
        return mode;
    }

    public Transition begin(long timeNs, State frameState) {
        return changeFrameCount(timeNs, frameState, 1);
    }

    public Transition end(long timeNs, State frameState) {
        return changeFrameCount(timeNs, frameState, -1);
    }

    /** 同一场碰撞中的所有 DATA 同刻开始，以一次操作确定 AP 的活动状态。 */
    public Transition beginCollision(long timeNs, int txFrames, int rxFrames) {
        if (txFrames < 0 || txFrames > 1 || rxFrames < 2 - txFrames) {
            throw new IllegalArgumentException("An AP collision requires at least two simultaneous frames");
        }
        return update(timeNs, txFrames, rxFrames);
    }

    private Transition changeFrameCount(long timeNs, State frameState, int delta) {
        switch (frameState) {
            case TX:
                return update(timeNs, delta, 0);
            case RX:
                return update(timeNs, 0, delta);
            default:
                throw new IllegalArgumentException("A frame can only begin or end AP TX or RX");
        }
    }

    private Transition update(long timeNs, int txDelta, int rxDelta) {
        requireEventTime(timeNs);
        int nextTx = Math.addExact(transmittingFrames, txDelta);
        int nextRx = Math.addExact(receivingFrames, rxDelta);
        if (nextTx < 0 || nextTx > 1 || nextRx < 0) {
            throw new IllegalStateException("Unbalanced AP frame activity at " + timeNs + " ns");
        }
        long intervalEndNs = inWindow(timeNs);
        stateDurationsNs[state.ordinal()] += intervalEndNs - inWindow(lastEventNs);
        lastEventNs = timeNs;
        transmittingFrames = nextTx;
        receivingFrames = nextRx;

        // AP 为半双工。碰撞中的自发 DATA 优先记作 TX，其余指向 AP 的重叠 DATA 只记一份 RX。
        State previous = state;
        state = nextTx > 0 ? State.TX : nextRx > 0 ? State.RX : State.IDLE;
        if (state == previous || (fixedObservationEndNs >= 0 && timeNs >= fixedObservationEndNs)) {
            return null;
        }
        return new Transition(previous, state);
    }

    /** 结算最后一段状态。固定窗口短于仿真时只计窗口内，长于仿真时延续最后一个状态。 */
    public Totals finish(long naturalEndNs) {
        requireEventTime(naturalEndNs);
        if (transmittingFrames != 0 || receivingFrames != 0) {
            throw new IllegalStateException("AP frame activity remains open at simulation end");
        }
        long observationEndNs = fixedObservationEndNs < 0 ? naturalEndNs : fixedObservationEndNs;
        stateDurationsNs[state.ordinal()] += observationEndNs - inWindow(lastEventNs);
        finished = true;
        return new Totals(observationEndNs, stateDurationsNs[State.IDLE.ordinal()],
                stateDurationsNs[State.TX.ordinal()], stateDurationsNs[State.RX.ordinal()]);
    }

    private void requireEventTime(long timeNs) {
        if (finished || timeNs < lastEventNs) {
            throw new IllegalStateException("AP activity must use chronological execution before observation is finalized");
        }
    }

    private long inWindow(long timeNs) {
        return fixedObservationEndNs < 0 ? timeNs : Math.min(timeNs, fixedObservationEndNs);
    }

    public static final class Transition {
        private final State from;
        private final State to;

        private Transition(State from, State to) {
            this.from = from;
            this.to = to;
        }

        public State from() { return from; }
        public State to() { return to; }
    }

    public static final class Totals {
        private final long observationEndNs;
        private final long idleNs;
        private final long txNs;
        private final long rxNs;

        private Totals(long observationEndNs, long idleNs, long txNs, long rxNs) {
            this.observationEndNs = observationEndNs;
            this.idleNs = idleNs;
            this.txNs = txNs;
            this.rxNs = rxNs;
        }

        public long observationEndNs() { return observationEndNs; }
        public long idleNs() { return idleNs; }
        public long txNs() { return txNs; }
        public long rxNs() { return rxNs; }
    }
}
