package edu.suda.fttr.entity;

import edu.suda.fttr.link.PonLinkTiming;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.IntFunction;

/** 只依据 MFU 可见需求提出字节预算。规划器没有远端队列或真实授权的写入接口。 */
public final class PonDbaShadowPlanner {
    private static final BigDecimal BITS_PER_BYTE_NS = BigDecimal.valueOf(8_000);

    private final List<Integer> sfuIds;
    private final BigDecimal rateMbps;
    private final long framePeriodNs;
    private int nextIndex;

    public PonDbaShadowPlanner(List<Integer> sfuIds, BigDecimal rateMbps, long framePeriodNs) {
        Objects.requireNonNull(sfuIds);
        this.rateMbps = Objects.requireNonNull(rateMbps);
        if (rateMbps.signum() <= 0 || framePeriodNs <= 0) {
            throw new IllegalArgumentException("Shadow planner rate and frame period must be positive");
        }
        if (sfuIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("Shadow planner SFU IDs must be positive");
        }
        this.sfuIds = List.copyOf(new TreeSet<>(sfuIds));
        this.framePeriodNs = framePeriodNs;
    }

    public Decision plan(long decisionTimeNs, long targetFrameId, long controlReservedNs,
                         IntFunction<PonDemandLedger.Estimate> estimates) {
        if (decisionTimeNs < 0 || targetFrameId < 0 || controlReservedNs < 0 || controlReservedNs > framePeriodNs) {
            throw new IllegalArgumentException("Shadow planner time or frame reservation is outside its valid range");
        }
        Objects.requireNonNull(estimates);
        long availableNs = framePeriodNs - controlReservedNs;
        // BigInteger 保留容量原值。高 PHY 速率或大帧不能让中间字节乘法溢出 long。
        BigInteger capacityBytes = BigDecimal.valueOf(availableNs).multiply(rateMbps)
                .divide(BITS_PER_BYTE_NS, 0, RoundingMode.FLOOR).toBigIntegerExact();
        List<PonDemandLedger.Estimate> visible = new ArrayList<>(sfuIds.size());
        int unknown = 0;
        int knownZero = 0;
        for (int sfuId : sfuIds) {
            PonDemandLedger.Estimate estimate = Objects.requireNonNull(estimates.apply(sfuId));
            visible.add(estimate);
            if (!estimate.known()) {
                unknown++;
            } else if (estimate.effectiveKnownBytes() == 0) {
                knownZero++;
            }
        }

        if (capacityBytes.signum() == 0) {
            return new Decision(decisionTimeNs, targetFrameId, 0, null, controlReservedNs, availableNs,
                    capacityBytes, 0, 0, "NO_DATA_CAPACITY", unknown, knownZero);
        }
        for (int offset = 0; offset < sfuIds.size(); offset++) {
            int index = (nextIndex + offset) % sfuIds.size();
            PonDemandLedger.Estimate estimate = visible.get(index);
            if (estimate.known() && estimate.effectiveKnownBytes() > 0) {
                long demandBytes = estimate.effectiveKnownBytes();
                long plannedBytes = BigInteger.valueOf(demandBytes).min(capacityBytes).longValueExact();
                long plannedNs = PonLinkTiming.serializationNs(plannedBytes, rateMbps);
                if (plannedNs > availableNs) {
                    throw new IllegalStateException("Shadow budget serialization exceeds the remaining frame");
                }
                // 只推进影子 RR 游标。后续帧仍须重新查询实际 REPORT–Grant 账本。
                nextIndex = (index + 1) % sfuIds.size();
                String reason = plannedBytes < demandBytes ? "FRAME_CAPACITY_LIMIT" : "KNOWN_DEMAND";
                return new Decision(decisionTimeNs, targetFrameId, sfuIds.get(index), estimate, controlReservedNs,
                        availableNs, capacityBytes, plannedBytes, plannedNs, reason, unknown, knownZero);
            }
        }
        String reason = unknown == sfuIds.size() ? "ALL_UNKNOWN" : "NO_KNOWN_POSITIVE_DEMAND";
        return new Decision(decisionTimeNs, targetFrameId, 0, null, controlReservedNs, availableNs,
                capacityBytes, 0, 0, reason, unknown, knownZero);
    }

    /** 预算仅为按字节估计的建议，未表示任何完整报文已经获得真实时隙。 */
    public static final class Decision {
        private final long decisionTimeNs;
        private final long targetFrameId;
        private final int selectedSfuId;
        private final PonDemandLedger.Estimate estimate;
        private final long controlReservedNs;
        private final long availableDataNs;
        private final BigInteger dataCapacityBytes;
        private final long plannedGrantBytes;
        private final long plannedGrantNs;
        private final String decisionReason;
        private final int unknownSfus;
        private final int knownZeroSfus;

        private Decision(long decisionTimeNs, long targetFrameId, int selectedSfuId,
                         PonDemandLedger.Estimate estimate, long controlReservedNs, long availableDataNs,
                         BigInteger dataCapacityBytes, long plannedGrantBytes, long plannedGrantNs,
                         String decisionReason, int unknownSfus, int knownZeroSfus) {
            this.decisionTimeNs = decisionTimeNs;
            this.targetFrameId = targetFrameId;
            this.selectedSfuId = selectedSfuId;
            this.estimate = estimate;
            this.controlReservedNs = controlReservedNs;
            this.availableDataNs = availableDataNs;
            this.dataCapacityBytes = dataCapacityBytes;
            this.plannedGrantBytes = plannedGrantBytes;
            this.plannedGrantNs = plannedGrantNs;
            this.decisionReason = decisionReason;
            this.unknownSfus = unknownSfus;
            this.knownZeroSfus = knownZeroSfus;
        }

        public long decisionTimeNs() {
            return decisionTimeNs;
        }

        public long targetFrameId() {
            return targetFrameId;
        }

        public int selectedSfuId() {
            return selectedSfuId;
        }

        public PonDemandLedger.Estimate estimate() {
            return estimate;
        }

        public Long reportFrameId() {
            return estimate == null ? null : estimate.report().report().frameId();
        }

        public Long reportAgeNs() {
            return estimate == null ? null : estimate.reportAgeNs();
        }

        public long effectiveKnownBytes() {
            return estimate == null ? 0 : estimate.effectiveKnownBytes();
        }

        public long controlReservedNs() {
            return controlReservedNs;
        }

        public long availableDataNs() {
            return availableDataNs;
        }

        public BigInteger dataCapacityBytes() {
            return dataCapacityBytes;
        }

        public long plannedGrantBytes() {
            return plannedGrantBytes;
        }

        public long plannedGrantNs() {
            return plannedGrantNs;
        }

        public String decisionReason() {
            return decisionReason;
        }

        public int unknownSfus() {
            return unknownSfus;
        }

        public int knownZeroSfus() {
            return knownZeroSfus;
        }
    }
}
