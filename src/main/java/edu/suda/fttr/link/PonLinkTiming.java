package edu.suda.fttr.link;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 上下行共享光链路使用同一串行化计算及纳秒取整规则。 */
public final class PonLinkTiming {
    private static final BigDecimal MAX_DURATION_NS = BigDecimal.valueOf(Long.MAX_VALUE);

    private PonLinkTiming() {
    }

    /** Mb/s 等于 bit/μs。将结果换算为 ns，非整纳秒向上取整以保留正发送时长。 */
    public static long serializationNs(int packetBytes, BigDecimal rateMbps) {
        return serializationNs((long) packetBytes, rateMbps);
    }

    /** 字节预算可跨多个报文，使用 long 计算，同时避免字节到纳秒换算的乘法溢出。 */
    public static long serializationNs(long packetBytes, BigDecimal rateMbps) {
        if (packetBytes <= 0 || rateMbps.signum() <= 0) {
            throw new IllegalArgumentException("PON packet size and link rate must be positive");
        }
        BigDecimal scaledBits = BigDecimal.valueOf(packetBytes).multiply(BigDecimal.valueOf(8_000));
        // 极端速率先检查边界，避免精确除法构造巨大的十进制数。
        if (scaledBits.compareTo(rateMbps) <= 0) {
            return 1;
        }
        if (scaledBits.compareTo(rateMbps.multiply(MAX_DURATION_NS)) > 0) {
            throw new ArithmeticException("PON serialization exceeds the nanosecond clock range");
        }
        return scaledBits.divide(rateMbps, 0, RoundingMode.CEILING).longValueExact();
    }
}
