package edu.suda.fttr.simulator;

import edu.suda.fttr.energy.ApModePredictor;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.mac.EdcaAccess.ApPhase;

import java.util.ArrayList;
import java.util.List;

/** C3 专用的因果测量，只记录 AP 可见介质状态，完全不参与接入规则。 */
final class ApMacEvidence {
    private final long[] outstanding = new long[AccessCategory.values().length];
    private final long[] phaseNs = new long[ApPhase.values().length];
    private final List<ApModePredictor.Transmission> completed = new ArrayList<>();
    private long cursorNs;
    private long eligibleNs;
    private long occupiedNs;
    private long blockedNs;
    private long lastDownlinkStartNs;
    private boolean eligible;
    private boolean owned;
    private boolean external;
    private ApPhase phase = ApPhase.NONE;

    private void advance(long nowNs) {
        long elapsed = nowNs - cursorNs;
        if (elapsed < 0) {
            throw new IllegalStateException("AP MAC observation time cannot move backwards");
        }
        phaseNs[phase.ordinal()] += elapsed;
        if (eligible) {
            eligibleNs += elapsed;
            if (owned) {
                occupiedNs += elapsed;
            } else if (external) {
                blockedNs += elapsed;
            }
        }
        cursorNs = nowNs;
    }

    void queueChanged(ApTrafficObserver.QueueEvent event) {
        advance(event.timeNs());
        outstanding[event.packet().type().ordinal()] = event.queuePackets() + event.inFlightPackets();
        eligible = false;
        for (long count : outstanding) {
            eligible |= count > 0;
        }
    }

    void mediumChanged(long nowNs, boolean localOwner, boolean busy, ApPhase apPhase) {
        advance(nowNs);
        owned = localOwner;
        external = busy;
        phase = apPhase;
    }

    void downlinkStarted(long nowNs) {
        lastDownlinkStartNs = nowNs;
    }

    void complete(Packet head, List<Packet> members, long bitmap, long startNs, long dataEndNs,
                  long responseNs, long endNs) {
        // 无法解码的上行身份/成员数不向 AP 暴露，只有本地介质占用被测量。
        int confirmed = Long.bitCount(bitmap);
        if (head.isUplink() && confirmed == 0) {
            return;
        }
        int count = members == null ? 1 : members.size();
        long payloadBytes = 0;
        for (int index = 0; index < count; index++) {
            if (!head.isUplink() || (bitmap & (1L << index)) != 0) {
                payloadBytes += members == null ? head.bytes() : members.get(index).bytes();
            }
        }
        long ppduStartNs = startNs < 0 ? lastDownlinkStartNs : startNs;
        completed.add(new ApModePredictor.Transmission(head.direction(), head.type(), head.staId(),
                head.isUplink() ? confirmed : count, confirmed, payloadBytes, dataEndNs - ppduStartNs,
                responseNs, endNs - ppduStartNs));
    }

    ApModePredictor.Evidence snapshot(long endNs, List<Packet> waiting) {
        advance(endNs);
        return new ApModePredictor.Evidence(waiting, completed, eligibleNs, occupiedNs, blockedNs);
    }

    long phaseNs(ApPhase state) { return phaseNs[state.ordinal()]; }

    void resetWindow() {
        completed.clear();
        eligibleNs = occupiedNs = blockedNs = 0;
        java.util.Arrays.fill(phaseNs, 0);
    }
}
