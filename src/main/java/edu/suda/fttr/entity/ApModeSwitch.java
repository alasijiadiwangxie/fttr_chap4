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
        context.recordApSwitchEvent("WIFI_AP_SWITCH_REQUEST", sfuId, request, mode, null, 0, null);
    }

    /** 仅在 SFU 已确认不存在当前帧交换时调用。相同目标仅在轮到执行时判定。 */
    public boolean startPending(SimulationContext context) {
        while (!pending.isEmpty()) {
            Request request = pending.removeFirst();
            if (request.target == mode) {
                context.recordApSwitchEvent("WIFI_AP_SWITCH_SKIPPED", sfuId, request, mode, null, 0, null);
                continue;
            }
            Profile profile = settings.profileFrom(mode);
            context.scheduleAfterNanoseconds(profile.delayNs, EventType.WIFI_AP_SWITCH_END, null, sfuId);
            active = request;
            startNs = context.nowTicks();
            context.recordApSwitchEvent("WIFI_AP_SWITCH_START", sfuId, request, mode, profile, 0, null);
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
        context.recordApSwitchEvent("WIFI_AP_SWITCH_END", sfuId, active, source, profile, observedNs, energyUj);
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

    public static final class Request {
        private final int id;
        private final long timeNs;
        private final ApCapabilityMode target;

        public Request(int id, long timeNs, ApCapabilityMode target) {
            this.id = id;
            this.timeNs = timeNs;
            this.target = target;
        }

        public int id() { return id; }
        public long timeNs() { return timeNs; }
        public ApCapabilityMode target() { return target; }
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
