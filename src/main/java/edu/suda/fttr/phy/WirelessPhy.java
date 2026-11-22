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
    // §27.3.11.10 引用 802.11-2020 表 21-13：按 NSTS 查符号数。无 STBC 时 NSTS=NSS。
    private static final int[] LTF_SYMBOLS = {1, 2, 4, 4, 6, 6, 8, 8};

    // 图 27-8：L-STF 8、L-LTF 8、L-SIG 4、RL-SIG 4、HE-SIG-A 8、HE-STF 4 μs。
    private static final long PREAMBLE_WITHOUT_LTF_NS = 36_000;
    private static final int SERVICE_BITS = 16;
    private static final int MAX_APEP_BYTES = 11_454 + 4; // 单 MPDU 最大长度和它的 4 B delimiter。
    private static final long MAX_PPDU_NS = 5_484_000;

    private final int bandwidthMhz;
    private final int nss;
    private final int macOverheadBytes;
    private final int codingNumerator;
    private final int codingDenominator;
    private final int codedBits;
    private final int dataBits;
    private final int shortCodedBits;
    private final int shortDataBits;
    private final long preambleNs;
    private final long symbolNs;
    private final int nominalPacketPaddingUs;

    /** MAC 开销含单 MPDU 的 MAC 首部、FCS 及 A-MPDU delimiter，合计默认 34 B。 */
    public WirelessPhy(int bandwidthMhz, int mcs, int nss, int giNs, int macOverheadBytes) {
        this(bandwidthMhz, mcs, nss, giNs, macOverheadBytes, defaultHeLtfType(giNs), 0);
    }

    public WirelessPhy(int bandwidthMhz, int mcs, int nss, int giNs, int macOverheadBytes, int heLtfType,
                       int nominalPacketPaddingUs) {
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
        if (nominalPacketPaddingUs != 0 && nominalPacketPaddingUs != 8 && nominalPacketPaddingUs != 16) {
            throw new IllegalArgumentException("HE SU nominal packet padding must be 0, 8 or 16 microseconds");
        }

        this.bandwidthMhz = bandwidthMhz;
        this.nss = nss;
        this.macOverheadBytes = macOverheadBytes;
        codingNumerator = CODING_NUMERATOR[mcs];
        codingDenominator = CODING_DENOMINATOR[mcs];
        codedBits = DATA_TONES[bandwidthIndex] * BITS_PER_TONE[mcs] * nss;
        dataBits = codedBits * codingNumerator / codingDenominator;
        shortCodedBits = SHORT_DATA_TONES[bandwidthIndex] * BITS_PER_TONE[mcs] * nss;
        shortDataBits = shortCodedBits * codingNumerator / codingDenominator;
        preambleNs = PREAMBLE_WITHOUT_LTF_NS + LTF_SYMBOLS[nss - 1] * heLtfSymbolNs(heLtfType, giNs);
        symbolNs = 12_800 + giNs;
        this.nominalPacketPaddingUs = nominalPacketPaddingUs;
    }

    /** 保留原有实验的训练字段选择。 */
    public static int defaultHeLtfType(int giNs) {
        return giNs == 3_200 ? 4 : 2;
    }

    private static long heLtfSymbolNs(int heLtfType, int giNs) {
        // 表 27-19 和 27-31：4x + 0.8 μs 对应 HE-SIG-A 的特殊组合。DCM 和 STBC 信号位均置 1，
        // 但不实际应用 DCM 或 STBC。这里只建模时间，无须构造信号位。
        boolean supported = (heLtfType == 1 && giNs == 800)
                || (heLtfType == 2 && (giNs == 800 || giNs == 1_600))
                || (heLtfType == 4 && (giNs == 800 || giNs == 3_200));
        if (!supported) {
            throw new IllegalArgumentException("Invalid HE SU HE-LTF type/GI combination: "
                    + heLtfType + "x with " + giNs + " ns GI");
        }
        return 3_200L * heLtfType + giNs;
    }

    public double phyRateMbps() {
        return (1_000.0 * dataBits) / symbolNs;
    }

    public int bandwidthMhz() {
        return bandwidthMhz;
    }

    public int nss() {
        return nss;
    }

    /** 每包视作一个数据 MPDU。无中间训练字段及 2.4 GHz 信号延长。 */
    public long ppduDurationNs(int payloadBytes) {
        return ppduTiming(payloadBytes).durationNs();
    }

    /** 返回前导码、数据符号和 PE 三部分，以及用于核对的填充规则结果。 */
    public PpduTiming ppduTiming(int payloadBytes) {
        long apepBytes = (long) payloadBytes + macOverheadBytes;
        if (payloadBytes <= 0 || apepBytes > MAX_APEP_BYTES) {
            throw new IllegalArgumentException("HE SU packet and overhead must fit in a single MPDU (APEP limit "
                    + MAX_APEP_BYTES + " bytes)");
        }

        // 27.3.12.2：由 APEP 和 SERVICE 算数据符号数量，再确定最后一个符号的预 FEC 填充边界。
        // 单一子帧的 APEP 为 payload + overhead，末尾填充由该边界决定，不提前凑成 4 B 倍数。
        long neededBits = 8 * apepBytes + SERVICE_BITS;
        long precedingSymbols = ceilDivide(neededBits, dataBits) - 1;
        long lastDataBits = neededBits - precedingSymbols * dataBits;
        int initialFactor = (int) Math.min(4, ceilDivide(lastDataBits, shortDataBits));
        long paddedDataBits = precedingSymbols * dataBits + (initialFactor == 4 ? dataBits : initialFactor * shortDataBits);
        long availableCodedBits = precedingSymbols * codedBits + lastSymbolCodedBits(initialFactor);

        // 27.3.12.5.2：截短和打孔的判定使用初始边界。若需追加一段，预 FEC 的输入不增加。
        // a<4 时额外段占原来的后 FEC 填充位置；只有 a=4 才跨到下一个 OFDM 符号。
        boolean ldpcExtra = needsExtraLdpcSegment(paddedDataBits, availableCodedBits);
        int finalFactor = initialFactor;
        long symbols = precedingSymbols + 1;
        if (ldpcExtra) {
            finalFactor = initialFactor == 4 ? 1 : initialFactor + 1;
            if (initialFactor == 4) {
                symbols++;
            }
        }
        long postFecPaddingBits = codedBits - lastSymbolCodedBits(finalFactor);

        // 27.3.13：使用接收端的 nominal padding 规则（未启用 PPE Thresholds），按最终 a 选 PE。
        long packetExtensionNs = 1_000L * Math.max(0, nominalPacketPaddingUs - (4 - finalFactor) * 4);
        PpduTiming timing = new PpduTiming(preambleNs, symbols, symbolNs, packetExtensionNs, initialFactor,
                finalFactor, ldpcExtra, paddedDataBits - neededBits, postFecPaddingBits);
        if (timing.durationNs() > MAX_PPDU_NS) {
            throw new IllegalArgumentException("HE SU PPDU exceeds 5484 microseconds for payload " + payloadBytes + " bytes");
        }
        return timing;
    }

    private int lastSymbolCodedBits(int paddingFactor) {
        // 最后一段补足到完整的 N_CBPS，未必与前三段等长。
        return paddingFactor == 4 ? codedBits : paddingFactor * shortCodedBits;
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

    public static final class PpduTiming {
        private final long preambleNs;
        private final long dataSymbols;
        private final long symbolNs;
        private final long packetExtensionNs;
        private final int initialPaddingFactor;
        private final int paddingFactor;
        private final boolean extraLdpcSegment;
        private final long preFecPaddingBits;
        private final long postFecPaddingBits;

        private PpduTiming(long preambleNs, long dataSymbols, long symbolNs, long packetExtensionNs,
                           int initialPaddingFactor, int paddingFactor, boolean extraLdpcSegment,
                           long preFecPaddingBits, long postFecPaddingBits) {
            this.preambleNs = preambleNs;
            this.dataSymbols = dataSymbols;
            this.symbolNs = symbolNs;
            this.packetExtensionNs = packetExtensionNs;
            this.initialPaddingFactor = initialPaddingFactor;
            this.paddingFactor = paddingFactor;
            this.extraLdpcSegment = extraLdpcSegment;
            this.preFecPaddingBits = preFecPaddingBits;
            this.postFecPaddingBits = postFecPaddingBits;
        }

        public long preambleNs() { return preambleNs; }
        public long dataSymbols() { return dataSymbols; }
        public long dataDurationNs() { return dataSymbols * symbolNs; }
        public long packetExtensionNs() { return packetExtensionNs; }
        public int initialPaddingFactor() { return initialPaddingFactor; }
        public int paddingFactor() { return paddingFactor; }
        public boolean hasExtraLdpcSegment() { return extraLdpcSegment; }
        public long preFecPaddingBits() { return preFecPaddingBits; }
        public long postFecPaddingBits() { return postFecPaddingBits; }

        public long durationNs() {
            return preambleNs + dataDurationNs() + packetExtensionNs;
        }
    }
}
