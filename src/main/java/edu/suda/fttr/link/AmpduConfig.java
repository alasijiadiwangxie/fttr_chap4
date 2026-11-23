package edu.suda.fttr.link;

import edu.suda.fttr.phy.WirelessPhy;

import java.util.Set;

/** 启用后的聚合上限和可复现的首次 MPDU 失败场景。 */
public final class AmpduConfig {
    public static final int MAX_MPDUS = 64; // 压缩 Block ACK 采用 64 位确认位图。

    private final int maxMpdus;
    private final int maxBytes;
    private final Set<Integer> firstAttemptErrors;

    public AmpduConfig(int maxMpdus, int maxBytes, Set<Integer> firstAttemptErrors) {
        if (maxMpdus < 1 || maxMpdus > MAX_MPDUS) {
            throw new IllegalArgumentException("wifi.mac.ampdu.max-mpdus must be in 1.." + MAX_MPDUS);
        }
        if (maxBytes < 1 || maxBytes > WirelessPhy.MAX_AMPDU_APEP_BYTES) {
            throw new IllegalArgumentException("wifi.mac.ampdu.max-bytes must be in 1.."
                    + WirelessPhy.MAX_AMPDU_APEP_BYTES);
        }
        if (firstAttemptErrors.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("A-MPDU first-attempt error packet IDs must be positive");
        }
        this.maxMpdus = maxMpdus;
        this.maxBytes = maxBytes;
        this.firstAttemptErrors = Set.copyOf(firstAttemptErrors);
    }

    public int maxMpdus() {
        return maxMpdus;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public boolean failsFirstAttempt(int packetId) {
        return firstAttemptErrors.contains(packetId);
    }
}
