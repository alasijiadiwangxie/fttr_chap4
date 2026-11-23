package edu.suda.fttr.link;

/** 理想 5 GHz 链路上单播 DATA 之后的 SIFS、立即 ACK 与压缩 Block ACK 时长。 */
public final class WifiFrameExchange {
    private static final int ACK_BYTES = 14; // Frame Control、Duration、RA 和 FCS。
    // Frame Control、Duration、RA、TA、BA Control、Starting Sequence、64 位位图及 FCS。
    private static final int BLOCK_ACK_BYTES = 32;
    private static final int SERVICE_BITS = 16;
    private static final int BCC_TAIL_BITS = 6;
    private static final int OFDM_PREAMBLE_AND_SIGNAL_US = 20;
    private static final int OFDM_SYMBOL_US = 4;

    private final long sifsNs;
    private final long ackNs;
    private final long blockAckNs;

    public WifiFrameExchange(long sifsUs, int ackRateMbps) {
        if (sifsUs <= 0) {
            throw new IllegalArgumentException("wifi.mac.sifs.us must be positive");
        }
        switch (ackRateMbps) {
            case 6: case 9: case 12: case 18: case 24: case 36: case 48: case 54:
                break;
            default:
                throw new IllegalArgumentException("wifi.mac.ack.rate.mbps must be a 20 MHz non-HT OFDM rate "
                        + "(6, 9, 12, 18, 24, 36, 48 or 54)");
        }

        ackNs = legacyControlDurationNs(ACK_BYTES, ackRateMbps);
        blockAckNs = legacyControlDurationNs(BLOCK_ACK_BYTES, ackRateMbps);
        if (sifsUs > (Long.MAX_VALUE - ackNs) / 1_000L) {
            throw new IllegalArgumentException("SIFS plus ACK exceeds the nanosecond clock range");
        }
        sifsNs = sifsUs * 1_000L;
    }

    /** 20 MHz non-HT OFDM 按完整符号发送，帧长已含 FCS。速率独立于 HE SU DATA。 */
    private static long legacyControlDurationNs(int frameBytes, int rateMbps) {
        int bits = SERVICE_BITS + 8 * frameBytes + BCC_TAIL_BITS;
        int bitsPerSymbol = rateMbps * OFDM_SYMBOL_US;
        int symbols = (bits + bitsPerSymbol - 1) / bitsPerSymbol;
        return 1_000L * (OFDM_PREAMBLE_AND_SIGNAL_US + OFDM_SYMBOL_US * symbols);
    }

    public long sifsNs() {
        return sifsNs;
    }

    public long ackNs() {
        return ackNs;
    }

    public long responseDurationNs() {
        return sifsNs + ackNs;
    }

    public long blockAckNs() {
        return blockAckNs;
    }

    public long blockAckResponseDurationNs() {
        if (sifsNs > Long.MAX_VALUE - blockAckNs) {
            throw new IllegalArgumentException("SIFS plus Block ACK exceeds the nanosecond clock range");
        }
        return sifsNs + blockAckNs;
    }
}
