package edu.suda.fttr.phy;

/** IEEE 802.11ax 全带宽 HE SU 的单数据报文 airtime 模型，时间单位统一为纳秒。 */
public final class WirelessPhy {
    // 表 27-13 和表 27-33：20/40/80/160 MHz 的数据子载波数及短符号分段子载波数。
    private static final int[] DATA_TONES = {234, 468, 980, 1960};
    private static final int[] SHORT_DATA_TONES = {60, 120, 240, 492};
    // 27.5 的 MCS 表：每子载波的调制位数与 LDPC 码率。按 MCS 索引取值。
    private static final int[] BITS_PER_TONE = {1, 2, 2, 4, 4, 6, 6, 6, 8, 8, 10, 10};
    private static final int[] CODING_NUMERATOR = {1, 1, 3, 1, 3, 2, 3, 5, 3, 5, 3, 5};
    private static final int[] CODING_DENOMINATOR = {2, 2, 4, 2, 4, 3, 4, 6, 4, 6, 4, 6};
    private static final int[] LTF_SYMBOLS = {1, 2, 4, 4, 6, 6, 8, 8};

    // 图 27-8：L-STF 8、L-LTF 8、L-SIG 4、RL-SIG 4、HE-SIG-A 8、HE-STF 4 μs。
    private static final long PREAMBLE_WITHOUT_LTF_NS = 36_000;
    private static final int SERVICE_BITS = 16;
    private static final int MAX_APEP_BYTES = 11_454 + 4; // 单 MPDU 最大长度和它的 4 B delimiter。
    private static final long MAX_PPDU_NS = 5_484_000;

    private final int macOverheadBytes;
    private final int codingNumerator;
    private final int codingDenominator;
    private final int codedBits;
    private final int dataBits;
    private final int shortCodedBits;
    private final int shortDataBits;
    private final long preambleNs;
    private final long symbolNs;

    /** MAC 开销含单 MPDU 的 MAC 首部、FCS 及 A-MPDU delimiter，合计默认 34 B。 */
    public WirelessPhy(int bandwidthMhz, int mcs, int nss, int giNs, int macOverheadBytes) {
        int bandwidthIndex;
        switch (bandwidthMhz) {
            case 20: bandwidthIndex = 0; break;
            case 40: bandwidthIndex = 1; break;
            case 80: bandwidthIndex = 2; break;
            case 160: bandwidthIndex = 3; break;
            default: throw new IllegalArgumentException("HE SU bandwidth must be 20, 40, 80 or 160 MHz");
        }
        if (mcs < 0 || mcs >= BITS_PER_TONE.length) {
            throw new IllegalArgumentException("HE SU MCS must be in 0..11");
        }
        if (nss < 1 || nss > LTF_SYMBOLS.length) {
            throw new IllegalArgumentException("HE SU NSS must be in 1..8");
        }
        if (giNs != 800 && giNs != 1_600 && giNs != 3_200) {
            throw new IllegalArgumentException("HE SU GI must be 0.8, 1.6 or 3.2 microseconds");
        }
        if (macOverheadBytes < 0 || macOverheadBytes >= MAX_APEP_BYTES) {
            throw new IllegalArgumentException("HE SU MAC overhead must leave room for a data MPDU");
        }

        this.macOverheadBytes = macOverheadBytes;
        codingNumerator = CODING_NUMERATOR[mcs];
        codingDenominator = CODING_DENOMINATOR[mcs];
        codedBits = DATA_TONES[bandwidthIndex] * BITS_PER_TONE[mcs] * nss;
        dataBits = codedBits * codingNumerator / codingDenominator;
        shortCodedBits = SHORT_DATA_TONES[bandwidthIndex] * BITS_PER_TONE[mcs] * nss;
        shortDataBits = shortCodedBits * codingNumerator / codingDenominator;
        // 表 27-19、27-31：短 GI 采用 2x HE-LTF，3.2 μs GI 采用 4x HE-LTF。此模型无 STBC。
        long ltfSymbolNs = (giNs == 3_200 ? 12_800 : 6_400) + giNs;
        preambleNs = PREAMBLE_WITHOUT_LTF_NS + LTF_SYMBOLS[nss - 1] * ltfSymbolNs;
        symbolNs = 12_800 + giNs;
    }

    public double phyRateMbps() {
        return (1_000.0 * dataBits) / symbolNs;
    }

    /** 每包视作一个数据 MPDU。使用 LDPC、0 μs PE、无信号延长，无中间训练字段。 */
    public long ppduDurationNs(int payloadBytes) {
        long apepBytes = (long) payloadBytes + macOverheadBytes;
        if (payloadBytes <= 0 || apepBytes > MAX_APEP_BYTES) {
            throw new IllegalArgumentException("HE SU packet and overhead must fit in a single MPDU (APEP limit "
                    + MAX_APEP_BYTES + " bytes)");
        }

        // 27.3.12.2：由 APEP 和 SERVICE 算数据符号数量，再确定最后一个符号的预 FEC 填充边界。
        // 单一子帧的 APEP 为 payload + overhead，末尾填充由该边界决定，不提前凑成 4 B 倍数。
        long neededBits = 8 * apepBytes + SERVICE_BITS;
        long symbols = ceilDivide(neededBits, dataBits);
        long lastDataBits = neededBits - (symbols - 1) * dataBits;
        long paddingFactor = Math.min(4, ceilDivide(lastDataBits, shortDataBits));

        // 27.3.12.5.2：只有初始 a=4 时，额外的 LDPC 分段才需要新发一个完整的 OFDM 符号。
        // 如果 a<4，额外的分段使用最后一个符号中原本用于后 FEC 填充的位置，发送时间不变。
        if (paddingFactor == 4 && needsExtraLdpcSegment(symbols * dataBits, symbols * codedBits)) {
            symbols++;
        }
        long durationNs = preambleNs + symbols * symbolNs;
        if (durationNs > MAX_PPDU_NS) {
            throw new IllegalArgumentException("HE SU PPDU exceeds 5484 microseconds for payload " + payloadBytes + " bytes");
        }
        return durationNs;
    }

    private boolean needsExtraLdpcSegment(long paddedDataBits, long availableCodedBits) {
        // HE 引用 HT LDPC 编码的码字选择表（802.11-2020 §19.3.11.7.5）。
        int codewordBits;
        long codewordCount;
        if (availableCodedBits <= 648) {
            codewordCount = 1;
            codewordBits = enoughParitySpace(availableCodedBits, paddedDataBits, 912) ? 1296 : 648;
        } else if (availableCodedBits <= 1296) {
            codewordCount = 1;
            codewordBits = enoughParitySpace(availableCodedBits, paddedDataBits, 1464) ? 1944 : 1296;
        } else if (availableCodedBits <= 1944) {
            codewordCount = 1;
            codewordBits = 1944;
        } else if (availableCodedBits <= 2592) {
            codewordCount = 2;
            codewordBits = enoughParitySpace(availableCodedBits, paddedDataBits, 2916) ? 1944 : 1296;
        } else {
            codewordBits = 1944;
            codewordCount = ceilDivide(paddedDataBits, 1944 * codingNumerator / codingDenominator);
        }

        long codedTotal = codewordCount * codewordBits;
        long information = codedTotal * codingNumerator / codingDenominator;
        long parity = codedTotal - information;
        long shortening = Math.max(0, information - paddedDataBits);
        long puncturing = Math.max(0, codedTotal - availableCodedBits - shortening);
        // 使用整数交叉相乘保留严格不等式：10%、30% 和 1.2×N_punc×R/(1−R)。
        return 10 * puncturing > 3 * parity || (10 * puncturing > parity
                && 5 * shortening * (codingDenominator - codingNumerator) < 6 * puncturing * codingNumerator);
    }

    private boolean enoughParitySpace(long codedBits, long dataBits, int threshold) {
        return (codedBits - dataBits) * codingDenominator >= (long) threshold * (codingDenominator - codingNumerator);
    }

    private static long ceilDivide(long positiveDividend, int positiveDivisor) {
        return 1 + (positiveDividend - 1) / positiveDivisor;
    }
}
