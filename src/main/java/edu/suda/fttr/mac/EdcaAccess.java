package edu.suda.fttr.mac;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为同一 SFU 信道上的各发送请求维护接入计时。 */
public final class EdcaAccess {
    // 设备内依照 AC 优先级；跨设备同刻就绪时按 SFU、STA ID 升序串行化。
    private static final Comparator<Attempt> READY_ORDER = Comparator.comparingInt(Attempt::senderKey)
            .thenComparing(attempt -> attempt.packet.type());

    private final int sfuId;
    private final WifiChannel channel;
    private final EdcaParameters parameters;
    private final boolean multiAc;
    private final Map<Packet, Attempt> pending = new LinkedHashMap<>();
    private final List<Attempt> ready = new ArrayList<>();
    private SimulationContext context;
    private Event resolveEvent;

    public EdcaAccess(int sfuId, WifiChannel channel, EdcaParameters parameters) {
        this(sfuId, channel, parameters, false);
    }

    public EdcaAccess(int sfuId, WifiChannel channel, EdcaParameters parameters, boolean multiAc) {
        this.sfuId = sfuId;
        this.channel = channel;
        this.parameters = parameters;
        this.multiAc = multiAc;
        channel.onStateChange(this::onChannelChange);
    }

    public void start(Packet packet, EdcaState.AcState state, SimulationContext context) {
        if ((!multiAc && isWaiting()) || pending.containsKey(packet)) {
            throw new IllegalStateException("Duplicate Wi-Fi access request for packet " + packet.id());
        }
        this.context = context;
        if (state.backoffCounter().isEmpty()) {
            state.drawBackoff();
        }
        Attempt attempt = new Attempt(packet, state);
        if (channel.state() == WifiChannel.State.IDLE) {
            startAifs(attempt);
        }
        pending.put(packet, attempt);
    }

    public boolean isWaiting() {
        return !pending.isEmpty();
    }

    public void onTimer(Event event) {
        Attempt attempt = pending.get(event.packet());
        if (attempt == null || attempt.timer != event) {
            return;
        }
        // AIFS 到期不消耗退避时隙，之后每个完整时隙才递减一格。
        int remaining = attempt.waitingForAifs ? attempt.state.backoffCounter().getAsInt()
                : attempt.state.decrementBackoff();
        if (remaining > 0) {
            attempt.waitingForAifs = false;
            scheduleTimer(attempt, parameters.slotTimeUs());
            return;
        }
        attempt.timer = null;
        if (!multiAc) {
            context.scheduleAfter(0, EventType.WIFI_TX_START, attempt.packet, sfuId);
            pending.remove(attempt.packet);
            return;
        }

        // 仲裁排在同刻所有退避事件之后，不能按计时事件的入队顺序决定获胜 AC。
        ready.add(attempt);
        if (resolveEvent == null) {
            resolveEvent = context.scheduleAfter(0, EventType.WIFI_ACCESS_RESOLVE, attempt.packet, sfuId);
        }
    }

    public Packet resolve(Event event) {
        if (resolveEvent == null || event != resolveEvent) {
            return null;
        }
        ready.sort(READY_ORDER);
        Attempt winner = ready.get(0);
        Attempt deviceWinner = winner;
        for (int i = 1; i < ready.size(); i++) {
            Attempt attempt = ready.get(i);
            if (attempt.senderKey() != deviceWinner.senderKey()) {
                // 其他设备的本地获胜请求保留计数器 0，下一次空闲时重新等待 AIFS。
                deviceWinner = attempt;
                continue;
            }
            int cwBefore = attempt.state.currentCw();
            attempt.state.onContentionFailure();
            int backoff = attempt.state.drawBackoff();
            context.recordVirtualCollision(attempt.packet, deviceWinner.packet, sfuId, cwBefore,
                    attempt.state.currentCw(), backoff);
        }
        pending.remove(winner.packet);
        ready.clear();
        resolveEvent = null;
        return winner.packet;
    }

    private void onChannelChange(WifiChannel.State state) {
        if (state == WifiChannel.State.IDLE) {
            pending.values().forEach(this::startAifs);
            return;
        }

        // BUSY 冻结未完成的请求。只取消计时，已完成的时隙和剩余计数器保留。
        for (Attempt attempt : pending.values()) {
            if (attempt.timer != null) {
                context.cancel(attempt.timer);
                attempt.timer = null;
            }
        }
        if (resolveEvent != null) {
            context.cancel(resolveEvent);
            resolveEvent = null;
            ready.clear();
        }
    }

    private void startAifs(Attempt attempt) {
        attempt.waitingForAifs = true;
        scheduleTimer(attempt, parameters.aifsUs(attempt.packet.type()));
    }

    private void scheduleTimer(Attempt attempt, long delayUs) {
        attempt.timer = context.scheduleAfter(delayUs, EventType.WIFI_ACCESS_TIMER, attempt.packet, sfuId);
    }

    private static final class Attempt {
        private final Packet packet;
        private final EdcaState.AcState state;
        private Event timer;
        private boolean waitingForAifs;

        private Attempt(Packet packet, EdcaState.AcState state) {
            this.packet = packet;
            this.state = state;
        }

        private int senderKey() {
            return packet.isUplink() ? packet.staId() : 0;
        }
    }
}
