package edu.suda.fttr.mac;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 为同一 SFU 信道上的各发送请求维护接入计时。 */
public final class EdcaAccess {
    // 按设备分组并依照 AC 优先级仲裁，每组的获胜 AC 都参与本次发送。
    private static final Comparator<Attempt> READY_ORDER = Comparator.comparingInt(Attempt::senderKey)
            .thenComparing(attempt -> attempt.packet.type());

    private final int sfuId;
    private final WifiChannel channel;
    private final EdcaParameters parameters;
    private final boolean multiAc;
    private final Map<Packet, Attempt> pending = new LinkedHashMap<>();
    private final List<Attempt> ready = new ArrayList<>();
    private final Set<Integer> suspendedSenders = new HashSet<>();
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
        if (channel.state() == WifiChannel.State.IDLE && !suspendedSenders.contains(attempt.senderKey())) {
            startAifs(attempt);
        }
        pending.put(packet, attempt);
    }

    public boolean isWaiting() {
        return !pending.isEmpty();
    }

    /** 碰撞 DATA 开始后，发送设备的所有 AC 都须等到 ACK 超时才能继续竞争。 */
    public void suspendSender(Packet packet) {
        // 信道已经进入 BUSY，忙态回调已冻结各 AC 的计时器。
        if (channel.state() != WifiChannel.State.BUSY || !suspendedSenders.add(senderKey(packet))) {
            throw new IllegalStateException("Cannot suspend Wi-Fi sender for packet " + packet.id());
        }
    }

    public void resumeSender(Packet packet) {
        int sender = senderKey(packet);
        if (!suspendedSenders.remove(sender)) {
            throw new IllegalStateException("No suspended Wi-Fi sender for packet " + packet.id());
        }
        if (channel.state() == WifiChannel.State.IDLE) {
            pending.values().stream().filter(attempt -> attempt.senderKey() == sender).forEach(this::startAifs);
        }
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

    /** 返回同一时刻具备发送资格的报文，每个设备至多一个。 */
    public List<Packet> resolve(Event event) {
        if (resolveEvent == null || event != resolveEvent) {
            return List.of();
        }
        ready.sort(READY_ORDER);
        List<Packet> contenders = new ArrayList<>();
        Attempt deviceWinner = null;
        for (Attempt attempt : ready) {
            if (deviceWinner == null || attempt.senderKey() != deviceWinner.senderKey()) {
                deviceWinner = attempt;
                contenders.add(attempt.packet);
                pending.remove(attempt.packet);
                continue;
            }
            int cwBefore = attempt.state.currentCw();
            attempt.state.onContentionFailure();
            int backoff = attempt.state.drawBackoff();
            context.recordVirtualCollision(attempt.packet, deviceWinner.packet, sfuId, cwBefore,
                    attempt.state.currentCw(), backoff);
        }
        ready.clear();
        resolveEvent = null;
        return contenders;
    }

    private void onChannelChange(WifiChannel.State state) {
        if (state == WifiChannel.State.IDLE) {
            pending.values().stream().filter(attempt -> !suspendedSenders.contains(attempt.senderKey()))
                    .forEach(this::startAifs);
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

    private static int senderKey(Packet packet) {
        return packet.isUplink() ? packet.staId() : 0;
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
            return EdcaAccess.senderKey(packet);
        }
    }
}
