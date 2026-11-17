package edu.suda.fttr.mac;

/** 碰撞后的 ACK 等待与重传上限。首次发送不计入重传次数。 */
public final class WifiRetryPolicy {
    private final long ackTimeoutUs;
    private final int maxRetries;

    public WifiRetryPolicy(long ackTimeoutUs, int maxRetries) {
        if (ackTimeoutUs <= 0 || ackTimeoutUs > Long.MAX_VALUE / 1_000L) {
            throw new IllegalArgumentException("wifi.mac.ack.timeout.us must be positive and fit the nanosecond clock");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("wifi.mac.retry.max must be non-negative");
        }
        this.ackTimeoutUs = ackTimeoutUs;
        this.maxRetries = maxRetries;
    }

    public long ackTimeoutUs() {
        return ackTimeoutUs;
    }

    public int maxRetries() {
        return maxRetries;
    }
}
