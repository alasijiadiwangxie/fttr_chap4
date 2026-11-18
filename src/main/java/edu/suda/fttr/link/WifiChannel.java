package edu.suda.fttr.link;

import java.util.Objects;
import java.util.function.Consumer;

/** 单个 SFU 及其关联 STA 共用的信道。忙态表示资源被当前帧交换占用。 */
public final class WifiChannel {
    public enum State {
        IDLE, BUSY
    }

    private State state = State.IDLE;
    private Consumer<State> stateListener;

    public State state() {
        return state;
    }

    /** 每个信道由一个接入控制器处理忙闲变化。状态更新后同步通知。 */
    public void onStateChange(Consumer<State> listener) {
        if (stateListener != null) {
            throw new IllegalStateException("Wi-Fi channel already has an access controller");
        }
        stateListener = Objects.requireNonNull(listener);
    }

    public void occupy() {
        if (state == State.BUSY) {
            throw new IllegalStateException("Cannot start DATA on a busy Wi-Fi channel");
        }
        changeState(State.BUSY);
    }

    public void release() {
        if (state == State.IDLE) {
            throw new IllegalStateException("Cannot release an idle Wi-Fi channel");
        }
        changeState(State.IDLE);
    }

    private void changeState(State next) {
        state = next;
        if (stateListener != null) {
            stateListener.accept(next);
        }
    }
}
