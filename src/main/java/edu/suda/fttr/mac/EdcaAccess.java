package edu.suda.fttr.mac;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 为同一 SFU 信道上的各发送请求维护接入计时。 */
public final class EdcaAccess {
    public enum ApPhase { NONE, AIFS, BACKOFF, FROZEN }
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
    private final Map<Integer, Long> navDeadlines = new HashMap<>();
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
        startAifs(attempt);
        pending.put(packet, attempt);
    }

    public boolean isWaiting() {
        return !pending.isEmpty();
    }

    /** AP 自己的 EDCA 计时状态。多个 AC 同时活动时只记一份时间。 */
    public ApPhase apPhase() {
        ApPhase phase = ApPhase.NONE;
        for (Attempt attempt : pending.values()) {
            if (attempt.senderKey() != 0) {
                continue;
            }
            if (attempt.timer != null) {
                if (!attempt.waitingForAifs) {
                    return ApPhase.BACKOFF;
                }
                phase = ApPhase.AIFS;
            } else if (phase == ApPhase.NONE
                    && (channel.state() == WifiChannel.State.BUSY || suspendedSenders.contains(0))) {
                phase = ApPhase.FROZEN;
            }
        }
        return phase;
    }

    /** 设备键 0 表示 SFU，其余设备键为关联 STA 的 ID。 */
    public long navUntilTicks(int listenerKey) {
        return navDeadlines.getOrDefault(listenerKey, 0L);
    }

    /** 处理侦听到的有效 MAC Duration，时长采用当前仿真时钟的刻度。 */
    public void receiveMacDuration(int listenerKey, int frameSenderKey, int frameRecipientKey, long durationTicks,
                                   SimulationContext context) {
        if (durationTicks < 0) {
            throw new IllegalArgumentException("MAC Duration must be non-negative");
        }
        if (durationTicks == 0 || listenerKey == frameSenderKey || listenerKey == frameRecipientKey) {
            return;
        }
        long deadline = Math.addExact(context.nowTicks(), durationTicks);
        if (deadline <= navUntilTicks(listenerKey)) {
            return;
        }
        navDeadlines.put(listenerKey, deadline);
        this.context = context;
        if (channel.state() == WifiChannel.State.BUSY || suspendedSenders.contains(listenerKey)) {
            // 计时器已冻结。信道释放或 ACK 等待结束时再检查 NAV。
            return;
        }

        // NAV 只冻结侦听设备。即使其计数在同刻归零，也要将它移出本次仲裁。
        ready.removeIf(attempt -> attempt.senderKey() == listenerKey);
        if (ready.isEmpty()) {
            cancelResolveEvent();
        }
        for (Attempt attempt : pending.values()) {
            if (attempt.senderKey() == listenerKey) {
                cancelTimer(attempt);
                startAifs(attempt);
            }
        }
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
        pending.values().stream().filter(attempt -> attempt.senderKey() == sender).forEach(this::startAifs);
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
            pending.values().forEach(this::startAifs);
            return;
        }

        // BUSY 冻结未完成的请求。只取消计时，已完成的时隙和剩余计数器保留。
        pending.values().forEach(this::cancelTimer);
        cancelResolveEvent();
        ready.clear();
    }

    private void cancelTimer(Attempt attempt) {
        if (attempt.timer != null) {
            context.cancel(attempt.timer);
            attempt.timer = null;
        }
    }

    private void cancelResolveEvent() {
        if (resolveEvent != null) {
            context.cancel(resolveEvent);
            resolveEvent = null;
        }
    }

    private void startAifs(Attempt attempt) {
        if (attempt.timer != null || channel.state() != WifiChannel.State.IDLE
                || suspendedSenders.contains(attempt.senderKey())) {
            return;
        }
        attempt.waitingForAifs = true;
        // 直接安排 NAV 到期后的完整 AIFS，空队列无须专门的 NAV 到期事件。
        long navRemainingTicks = Math.max(0, navUntilTicks(attempt.senderKey()) - context.nowTicks());
        long aifsTicks = context.microsecondsToTicks(parameters.aifsUs(attempt.packet.type()));
        attempt.timer = context.scheduleAfterTicks(Math.addExact(navRemainingTicks, aifsTicks),
                EventType.WIFI_ACCESS_TIMER, attempt.packet, sfuId);
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
