package edu.suda.fttr.entity;

/** 报文在两段链路间保持同一身份，并携带原始 MFU 到达时刻。 */
public final class Packet {
    private final int id;
    private final int bytes;
    private final long mfuArrivalUs;

    public Packet(int id, int bytes, long mfuArrivalUs) {
        this.id = id;
        this.bytes = bytes;
        this.mfuArrivalUs = mfuArrivalUs;
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
}
