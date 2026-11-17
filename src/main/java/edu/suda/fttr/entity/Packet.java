package edu.suda.fttr.entity;

import java.util.Objects;

/** 报文在传输过程中保持同一身份，端点是最初发送节点和最终接收节点。 */
public final class Packet {
    public static final int DEFAULT_MFU_ID = 1;

    /** DL 从 MFU 发往 STA，UL 从 STA 发往 MFU。节点 ID 在各自类型内编号。 */
    public enum Direction {
        DL,
        UL
    }

    private final int id;
    private final int bytes;
    private final long generationTimeUs;
    private final AccessCategory type;
    private final Direction direction;
    private final int sourceNodeId;
    private final int destinationNodeId;

    public Packet(int id, int bytes, long mfuArrivalUs) {
        this(id, bytes, mfuArrivalUs, AccessCategory.BE);
    }

    public Packet(int id, int bytes, long mfuArrivalUs, AccessCategory type) {
        this(id, bytes, mfuArrivalUs, type, 1);
    }

    public Packet(int id, int bytes, long mfuArrivalUs, AccessCategory type, int destinationStaId) {
        this(id, bytes, mfuArrivalUs, type, Direction.DL, DEFAULT_MFU_ID, destinationStaId);
    }

    public Packet(int id, int bytes, long generationTimeUs, AccessCategory type, Direction direction,
                  int sourceNodeId, int destinationNodeId) {
        if (sourceNodeId < 1 || destinationNodeId < 1) {
            throw new IllegalArgumentException("Source and destination node IDs must be positive");
        }
        this.id = id;
        this.bytes = bytes;
        this.generationTimeUs = generationTimeUs;
        this.type = Objects.requireNonNull(type);
        this.direction = Objects.requireNonNull(direction);
        this.sourceNodeId = sourceNodeId;
        this.destinationNodeId = destinationNodeId;
    }

    public int id() {
        return id;
    }

    public int bytes() {
        return bytes;
    }

    public long generationTimeUs() {
        return generationTimeUs;
    }

    public AccessCategory type() {
        return type;
    }

    public Direction direction() {
        return direction;
    }

    public boolean isUplink() {
        return direction == Direction.UL;
    }

    /** 无线一端的 STA。上行取源节点，下行取目的节点。 */
    public int staId() {
        return isUplink() ? sourceNodeId : destinationNodeId;
    }

    public int sourceNodeId() {
        return sourceNodeId;
    }

    public int destinationNodeId() {
        return destinationNodeId;
    }

    /** 现有下行链路的入口时刻就是 MFU 生成或注入报文的时刻。 */
    public long mfuArrivalUs() {
        requireDownlink();
        return generationTimeUs;
    }

    public int destinationStaId() {
        requireDownlink();
        return destinationNodeId;
    }

    private void requireDownlink() {
        if (direction != Direction.DL) {
            throw new IllegalStateException("This packet accessor requires downlink traffic");
        }
    }
}
