package edu.suda.fttr.entity;

import edu.suda.fttr.phy.ApCapabilityMode;

/** 一台 SFU 的 AP 无线活动账本。常规活动按能力模式计时，切换按方向独立计时。 */
public final class ApActivity {
    public enum State { IDLE, TX, RX, SWITCH }

    private static final int LEGACY_INDEX = ApCapabilityMode.values().length;
    private final long fixedObservationEndNs;
    private final long[][] stateDurationsNs = new long[LEGACY_INDEX + 1][State.SWITCH.ordinal()];
    private final long[] switchDurationsNs = new long[LEGACY_INDEX];
    private ApCapabilityMode mode;
    private ApCapabilityMode switchTarget;
    private State state = State.IDLE;
    private long lastEventNs;
    private long txAfterWindowNs;
    private long rxAfterWindowNs;
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

    /** AP 只在没有收发帧时进入切换状态，切换完成前能力模式仍是源模式。 */
    public Transition beginSwitch(long timeNs, ApCapabilityMode target) {
        requireEventTime(timeNs);
        if (mode == null || target == null || target == mode) {
            throw new IllegalArgumentException("AP switch requires two different capability modes");
        }
        if (state != State.IDLE) {
            throw new IllegalStateException("AP switch requires idle radio activity");
        }
        advanceTo(timeNs);
        switchTarget = target;
        return changeState(timeNs, State.SWITCH);
    }

    public Transition endSwitch(long timeNs) {
        requireEventTime(timeNs);
        if (state != State.SWITCH) {
            throw new IllegalStateException("AP switch completion requires an open switch");
        }
        // 先把窗口内的切换时间记入原方向，再启用目标模式及其常规功率。
        advanceTo(timeNs);
        mode = switchTarget;
        switchTarget = null;
        return changeState(timeNs, State.IDLE);
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
        if (state == State.SWITCH) {
            throw new IllegalStateException("AP frame activity cannot overlap a mode switch");
        }
        int nextTx = Math.addExact(transmittingFrames, txDelta);
        int nextRx = Math.addExact(receivingFrames, rxDelta);
        if (nextTx < 0 || nextTx > 1 || nextRx < 0) {
            throw new IllegalStateException("Unbalanced AP frame activity at " + timeNs + " ns");
        }
        advanceTo(timeNs);
        transmittingFrames = nextTx;
        receivingFrames = nextRx;

        // AP 为半双工。碰撞中的自发 DATA 优先记作 TX，其余指向 AP 的重叠 DATA 只记一份 RX。
        State next = nextTx > 0 ? State.TX : nextRx > 0 ? State.RX : State.IDLE;
        return changeState(timeNs, next);
    }

    private void advanceTo(long timeNs) {
        long observedNs = observedDurationNs(lastEventNs, timeNs);
        accumulate(observedNs);
        // 在线业务观测读取自然时间轴。固定能耗窗口截去的活动仍可由 C1 观测。
        long outsideWindowNs = timeNs - lastEventNs - observedNs;
        if (state == State.TX) {
            txAfterWindowNs += outsideWindowNs;
        } else if (state == State.RX) {
            rxAfterWindowNs += outsideWindowNs;
        }
        lastEventNs = timeNs;
    }

    /** 只读投影到当前采样边界，保留尚未结束的 TX/RX，也不会结算或移动 B2 状态。 */
    public Airtime airtimeAt(long timeNs) {
        requireEventTime(timeNs);
        long txNs = txAfterWindowNs;
        long rxNs = rxAfterWindowNs;
        for (long[] durations : stateDurationsNs) {
            txNs += durations[State.TX.ordinal()];
            rxNs += durations[State.RX.ordinal()];
        }
        long openDurationNs = timeNs - lastEventNs;
        if (state == State.TX) {
            txNs += openDurationNs;
        } else if (state == State.RX) {
            rxNs += openDurationNs;
        }
        return new Airtime(txNs, rxNs);
    }

    public static final class Airtime {
        private final long txNs;
        private final long rxNs;

        private Airtime(long txNs, long rxNs) {
            this.txNs = txNs;
            this.rxNs = rxNs;
        }

        public long txNs() { return txNs; }
        public long rxNs() { return rxNs; }
    }

    private Transition changeState(long timeNs, State next) {
        State previous = state;
        state = next;
        if (state == previous || (fixedObservationEndNs >= 0 && timeNs >= fixedObservationEndNs)) {
            return null;
        }
        return new Transition(previous, state);
    }

    private void accumulate(long durationNs) {
        if (state == State.SWITCH) {
            // 当前只有两个能力模式，每个源模式唯一确定一个切换方向。
            switchDurationsNs[mode.ordinal()] += durationNs;
        } else {
            stateDurationsNs[modeIndex(mode)][state.ordinal()] += durationNs;
        }
    }

    /** 结算最后一段状态。固定窗口短于仿真时只计窗口内，长于仿真时延续最后一个状态。 */
    public Totals finish(long naturalEndNs) {
        requireEventTime(naturalEndNs);
        if (state != State.IDLE) {
            throw new IllegalStateException("AP radio operation remains open at simulation end");
        }
        long observationEndNs = fixedObservationEndNs < 0 ? naturalEndNs : fixedObservationEndNs;
        accumulate(observationEndNs - inWindow(lastEventNs));
        finished = true;
        return new Totals(observationEndNs, stateDurationsNs, switchDurationsNs);
    }

    private void requireEventTime(long timeNs) {
        if (finished || timeNs < lastEventNs) {
            throw new IllegalStateException("AP activity must use chronological execution before observation is finalized");
        }
    }

    private long inWindow(long timeNs) {
        return fixedObservationEndNs < 0 ? timeNs : Math.min(timeNs, fixedObservationEndNs);
    }

    /** 返回区间与观察窗口相交的时长，也可用于核对单次切换的统计能耗。 */
    public long observedDurationNs(long startNs, long endNs) {
        if (startNs < 0 || endNs < startNs) {
            throw new IllegalArgumentException("AP observation interval is not chronological");
        }
        return inWindow(endNs) - inWindow(startNs);
    }

    private static int modeIndex(ApCapabilityMode mode) {
        return mode == null ? LEGACY_INDEX : mode.ordinal();
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
        private final Durations[] byMode;
        private final Durations regular;
        private final long[] switchDurationsNs;

        private Totals(long observationEndNs, long[][] stateDurationsNs, long[] switchDurationsNs) {
            this.observationEndNs = observationEndNs;
            this.switchDurationsNs = switchDurationsNs.clone();
            byMode = new Durations[stateDurationsNs.length];
            long idleNs = 0;
            long txNs = 0;
            long rxNs = 0;
            for (int modeIndex = 0; modeIndex < stateDurationsNs.length; modeIndex++) {
                long[] durations = stateDurationsNs[modeIndex];
                Durations regularInMode = new Durations(durations[State.IDLE.ordinal()],
                        durations[State.TX.ordinal()], durations[State.RX.ordinal()]);
                byMode[modeIndex] = regularInMode;
                idleNs += regularInMode.idleNs();
                txNs += regularInMode.txNs();
                rxNs += regularInMode.rxNs();
            }
            regular = new Durations(idleNs, txNs, rxNs);
            if (idleNs + txNs + rxNs + switchNs() != observationEndNs) {
                throw new IllegalStateException("AP activity durations must sum to the observation window");
            }
        }

        public long observationEndNs() { return observationEndNs; }
        public long idleNs() { return regular.idleNs(); }
        public long txNs() { return regular.txNs(); }
        public long rxNs() { return regular.rxNs(); }

        /** 返回该源模式对应的切换方向时长。LEGACY 没有切换。 */
        public long switchNsFrom(ApCapabilityMode source) {
            return source == null ? 0 : switchDurationsNs[source.ordinal()];
        }

        public long switchNs() {
            return switchNsFrom(ApCapabilityMode.LCM) + switchNsFrom(ApCapabilityMode.HCM);
        }

        public Durations forMode(ApCapabilityMode mode) {
            return byMode[modeIndex(mode)];
        }
    }

    public static final class Durations {
        private final long idleNs;
        private final long txNs;
        private final long rxNs;

        private Durations(long idleNs, long txNs, long rxNs) {
            this.idleNs = idleNs;
            this.txNs = txNs;
            this.rxNs = rxNs;
        }

        public long idleNs() { return idleNs; }
        public long txNs() { return txNs; }
        public long rxNs() { return rxNs; }
    }
}
