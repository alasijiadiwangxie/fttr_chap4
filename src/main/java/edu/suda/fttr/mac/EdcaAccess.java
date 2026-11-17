package edu.suda.fttr.mac;

import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiChannel;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

/** 每个 SFU 的接入计时器，同一时刻只处理一个已选中的发送请求。 */
public final class EdcaAccess {
    private final int sfuId;
    private final WifiChannel channel;
    private final EdcaParameters parameters;
    private Packet pendingPacket;
    private EdcaState.AcState acState;
    private SimulationContext context;
    private Event timer;
    private boolean waitingForAifs;

    public EdcaAccess(int sfuId, WifiChannel channel, EdcaParameters parameters) {
        this.sfuId = sfuId;
        this.channel = channel;
        this.parameters = parameters;
        channel.onStateChange(this::onChannelChange);
    }

    public void start(Packet packet, EdcaState.AcState state, SimulationContext context) {
        if (pendingPacket != null) {
            throw new IllegalStateException("SFU " + sfuId + " already has a pending Wi-Fi request");
        }
        pendingPacket = packet;
        acState = state;
        this.context = context;
        if (acState.backoffCounter().isEmpty()) {
            acState.drawBackoff();
        }
        if (channel.state() == WifiChannel.State.IDLE) {
            startAifs();
        }
    }

    public boolean isWaiting() {
        return pendingPacket != null;
    }

    public void onTimer(Event event) {
        if (timer == null || event != timer) {
            return;
        }
        // AIFS 到期不消耗退避时隙，之后每个完整时隙才递减一格。
        int remaining = waitingForAifs ? acState.backoffCounter().getAsInt() : acState.decrementBackoff();
        if (remaining == 0) {
            context.scheduleAfter(0, EventType.WIFI_TX_START, pendingPacket, sfuId);
            timer = null;
            pendingPacket = null;
            acState = null;
            context = null;
        } else {
            waitingForAifs = false;
            timer = context.scheduleAfter(parameters.slotTimeUs(), EventType.WIFI_ACCESS_TIMER, pendingPacket, sfuId);
        }
    }

    private void onChannelChange(WifiChannel.State state) {
        if (pendingPacket == null) {
            return;
        }
        if (state == WifiChannel.State.IDLE) {
            startAifs();
        } else if (timer != null) {
            // 忙态打断正在等待的 AIFS 或时隙。已完成的时隙保留，剩余计数器不重抽。
            context.cancel(timer);
            timer = null;
        }
    }

    private void startAifs() {
        waitingForAifs = true;
        timer = context.scheduleAfter(parameters.aifsUs(pendingPacket.type()), EventType.WIFI_ACCESS_TIMER,
                pendingPacket, sfuId);
    }
}
