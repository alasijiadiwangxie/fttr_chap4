package edu.suda.fttr.dba;

import edu.suda.fttr.link.PonLinkTiming;

import java.math.BigDecimal;
import java.util.List;

/** 按最坏同帧到期的保守条件验收 GIANT 的固定与保证额度。 */
public final class GiantSlaAdmission {
    private GiantSlaAdmission() {
    }

    public static void validate(List<TcontProfile> profiles, long framePeriodNs, BigDecimal rateMbps, int pollBytes) {
        try {
            long requiredNs = pollBytes == 0 ? 0 : PonLinkTiming.serializationNs(pollBytes, rateMbps);
            for (TcontProfile profile : profiles) {
                // 即使两个周期不同，也为同帧到期留足空间。一个 Alloc-ID 的两份额度合并后取整。
                long guaranteedBytes = Math.addExact(profile.abFixedBytes(), profile.assuredBytes());
                if (guaranteedBytes > 0) {
                    requiredNs = Math.addExact(requiredNs, PonLinkTiming.serializationNs(guaranteedBytes, rateMbps));
                }
            }
            if (requiredNs > framePeriodNs) {
                throw new IllegalArgumentException("GIANT SLA infeasible: fixed/assured and REPORT-only require "
                        + requiredNs + " ns in one frame, exceeding " + framePeriodNs + " ns");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("GIANT SLA infeasible: fixed/assured capacity exceeds representable range", e);
        }
    }
}
