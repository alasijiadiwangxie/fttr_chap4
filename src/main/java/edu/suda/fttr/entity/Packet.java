package edu.suda.fttr.entity;

import java.util.Objects;

/** 报文在两段链路间保持同一身份，并携带原始 MFU 到达时刻。 */
public final class Packet {
    private final int id;
    private final int bytes;
    private final long mfuArrivalUs;
    private final AccessCategory type;

    public Packet(int id, int bytes, long mfuArrivalUs) {
        this(id, bytes, mfuArrivalUs, AccessCategory.BE);
    }

    public Packet(int id, int bytes, long mfuArrivalUs, AccessCategory type) {
        this.id = id;
        this.bytes = bytes;
        this.mfuArrivalUs = mfuArrivalUs;
        this.type = Objects.requireNonNull(type);
    }

    public int id() {
        return id;
    }

    public int bytes() {
        return bytes;
    }

    public long mfuArrivalUs() {
        return mfuArrivalUs;
    }

    public AccessCategory type() {
        return type;
    }
}
