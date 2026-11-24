package edu.suda.fttr.link;

import java.util.Objects;
import java.util.function.Consumer;

/** 单个 SFU 及其关联 STA 的接入资源。拥有者占用与外部 CCA 并联。 */
public final class WifiChannel {
    public enum State {
        IDLE, BUSY
    }

    private boolean occupied;
    private boolean externalBusy;
    private Consumer<State> stateListener;

    public State state() {
        return occupied || externalBusy ? State.BUSY : State.IDLE;
    }

    /** 每个信道由一个接入控制器处理忙闲变化。状态更新后同步通知。 */
    public void onStateChange(Consumer<State> listener) {
        if (stateListener != null) {
            throw new IllegalStateException("Wi-Fi channel already has an access controller");
        }
        stateListener = Objects.requireNonNull(listener);
    }

    public void occupy() {
        if (state() == State.BUSY) {
            throw new IllegalStateException("Cannot start DATA on a busy Wi-Fi channel");
        }
        occupied = true;
        notifyChange(State.IDLE);
    }

    public void release() {
        if (!occupied) {
            throw new IllegalStateException("Cannot release a Wi-Fi channel without an owner");
        }
        occupied = false;
        notifyChange(State.BUSY);
    }

    /** 干扰在已有帧交换中出现时，不释放帧交换所持有的资源。 */
    public void setExternalBusy(boolean busy) {
        State before = state();
        externalBusy = busy;
        notifyChange(before);
    }

    public boolean externalBusy() { return externalBusy; }

    private void notifyChange(State previous) {
        State current = state();
        if (stateListener != null && current != previous) {
            stateListener.accept(current);
        }
    }
}
