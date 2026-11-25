package edu.suda.fttr.entity;

import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.simulator.EventType;
import edu.suda.fttr.simulator.SimulationContext;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 单台 AP 的显式请求队列。SFU 决定何时安全接管及释放无线资源。 */
public final class ApModeSwitch {
    private final int sfuId;
    private final Settings settings;
    private final List<Request> requests;
    private final Map<ApCapabilityMode, WifiLink> links;
    private final Deque<Request> pending = new ArrayDeque<>();
    private ApCapabilityMode mode;
    private int nextRequest;
    private int nextOnlineId = 1;
    private Request active;
    private long startNs;

    public ApModeSwitch(int sfuId, Settings settings, ApCapabilityMode initialMode, WifiLink initialLink,
                        WifiLink alternateLink) {
        this.sfuId = sfuId;
        this.settings = settings;
        this.requests = settings.requestsFor(sfuId);
        this.mode = initialMode;
        this.links = Map.of(initialMode, initialLink, otherMode(initialMode), alternateLink);
    }

    public void receiveRequest(SimulationContext context) {
        Request request = requests.get(nextRequest++);
        if (request.timeNs != context.nowTicks()) {
            throw new IllegalStateException("AP mode switch request executed at the wrong time for SFU " + sfuId);
        }
        pending.addLast(request);
        context.recordApSwitchEvent("WIFI_AP_SWITCH_REQUEST", sfuId, request, mode, null, -1, 0, null);
    }

    /** 在线决策只保留最新的未开始请求。正在执行的切换必须完整结束。 */
    public Outcome requestOnline(ApCapabilityMode target, SimulationContext context) {
        if (!requests.isEmpty()) {
            throw new IllegalStateException("Cannot combine manual and online requests for SFU " + sfuId);
        }
        Request queued = pending.peekFirst();
        if (queued != null) {
            if (queued.target == target) {
                return new Outcome(Action.PENDING, queued.id);
            }
            pending.removeFirst();
            context.recordApSwitchEvent("WIFI_AP_SWITCH_CANCELLED", sfuId, queued, mode, null, -1, 0, null);
        }

        ApCapabilityMode projected = active == null ? mode : active.target;
        if (target != projected) {
            Request request = new Request(nextOnlineId++, context.nowTicks(), target, true);
            pending.addLast(request);
            context.recordApSwitchEvent("WIFI_AP_SWITCH_REQUEST", sfuId, request, mode, null, -1, 0, null);
            return new Outcome(Action.REQUESTED, request.id);
        }
        if (queued != null) {
            return new Outcome(Action.CANCELLED, queued.id);
        }
        return active == null ? new Outcome(Action.HOLD, 0) : new Outcome(Action.SWITCHING, active.id);
    }

    /** 仅在 SFU 已确认不存在当前帧交换时调用。相同目标仅在轮到执行时判定。 */
    public boolean startPending(SimulationContext context) {
        while (!pending.isEmpty()) {
            Request request = pending.removeFirst();
            if (request.target == mode) {
                context.recordApSwitchEvent("WIFI_AP_SWITCH_SKIPPED", sfuId, request, mode, null, -1, 0, null);
                continue;
            }
            Profile profile = settings.profileFrom(mode);
            context.scheduleAfterNanoseconds(profile.delayNs, EventType.WIFI_AP_SWITCH_END, null, sfuId);
            active = request;
            startNs = context.nowTicks();
            context.recordApSwitchEvent("WIFI_AP_SWITCH_START", sfuId, request, mode, profile, startNs, 0, null);
            context.startApModeSwitch(sfuId, request.target);
            return true;
        }
        return false;
    }

    public WifiLink finish(SimulationContext context) {
        if (active == null) {
            throw new IllegalStateException("No AP mode switch to finish for SFU " + sfuId);
        }
        ApCapabilityMode source = mode;
        Profile profile = settings.profileFrom(source);
        long observedNs = context.finishApModeSwitch(sfuId, startNs);
        BigDecimal energyUj = ApEnergyModel.switchEnergyUj(observedNs, profile.powerW);
        context.recordApSwitchEvent("WIFI_AP_SWITCH_END", sfuId, active, source, profile, startNs, observedNs, energyUj);
        mode = active.target;
        active = null;
        return links.get(mode);
    }

    public ApCapabilityMode mode() {
        return mode;
    }

    public static ApCapabilityMode otherMode(ApCapabilityMode mode) {
        return mode == ApCapabilityMode.LCM ? ApCapabilityMode.HCM : ApCapabilityMode.LCM;
    }

    public enum Action { HOLD, REQUESTED, PENDING, CANCELLED, SWITCHING }

    /** 用同一请求 ID 关联策略决策、等待、开始和完成。0 表示没有切换请求。 */
    public static final class Outcome {
        private final Action action;
        private final int requestId;

        private Outcome(Action action, int requestId) {
            this.action = action;
            this.requestId = requestId;
        }

        public Action action() { return action; }
        public int requestId() { return requestId; }
    }

    public static final class Request {
        private final int id;
        private final long timeNs;
        private final ApCapabilityMode target;
        private final boolean online;

        public Request(int id, long timeNs, ApCapabilityMode target) {
            this(id, timeNs, target, false);
        }

        private Request(int id, long timeNs, ApCapabilityMode target, boolean online) {
            this.id = id;
            this.timeNs = timeNs;
            this.target = target;
            this.online = online;
        }

        public int id() { return id; }
        public long timeNs() { return timeNs; }
        public ApCapabilityMode target() { return target; }
        public boolean online() { return online; }
    }

    public static final class Profile {
        private final long delayNs;
        private final BigDecimal powerW;

        public Profile(long delayNs, BigDecimal powerW) {
            this.delayNs = delayNs;
            this.powerW = powerW;
        }

        public long delayNs() { return delayNs; }
        public BigDecimal powerW() { return powerW; }
    }

    public static final class Settings {
        private final Map<Integer, List<Request>> requestsBySfu;
        private final Profile lcmToHcm;
        private final Profile hcmToLcm;

        public Settings(Map<Integer, List<Request>> requestsBySfu, Profile lcmToHcm, Profile hcmToLcm) {
            this.requestsBySfu = Map.copyOf(requestsBySfu);
            this.lcmToHcm = lcmToHcm;
            this.hcmToLcm = hcmToLcm;
        }

        public List<Request> requestsFor(int sfuId) {
            return requestsBySfu.getOrDefault(sfuId, List.of());
        }

        public Profile profileFrom(ApCapabilityMode mode) {
            return mode == ApCapabilityMode.LCM ? lcmToHcm : hcmToLcm;
        }

        public BigDecimal powerFrom(ApCapabilityMode mode) {
            Profile profile = profileFrom(mode);
            return profile == null ? null : profile.powerW;
        }
    }
}
