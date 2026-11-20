package edu.suda.fttr.dba;

import edu.suda.fttr.entity.AccessCategory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一个 Alloc-ID 的不可变逻辑 T-CONT 配置。GIANT 使用带宽服务参数决定授权机会和额度，
 * RR 继续根据已知需求及帧剩余容量轮询。
 */
public final class TcontProfile {
    private final long allocationId;
    private final int sfuId;
    private final int type;
    private final Set<AccessCategory> classes;
    private final int siMaxFrames;
    private final int siMinFrames;
    private final long abMinBytes;
    private final long abSurBytes;
    private final int siFixedFrames;
    private final long abFixedBytes;
    private final int siBeFrames;
    private final long abBeBytes;

    public TcontProfile(long allocationId, int sfuId, int type, Set<AccessCategory> classes,
                        int siMaxFrames, int siMinFrames, long abMinBytes, long abSurBytes) {
        this(allocationId, sfuId, type, classes, siMaxFrames, siMinFrames, abMinBytes, abSurBytes, 0, 0, 0, 0);
    }

    /**
     * Type 1 的固定额度和 Type 5 的组合服务均使用独立的周期与字节额度。
     * Type 5 的四份参数分别用于固定、保证、非保证、尽力而为阶段。
     */
    public TcontProfile(long allocationId, int sfuId, int type, Set<AccessCategory> classes,
                        int siMaxFrames, int siMinFrames, long abMinBytes, long abSurBytes,
                        int siFixedFrames, long abFixedBytes, int siBeFrames, long abBeBytes) {
        if (allocationId < 1 || sfuId < 1 || type < 1 || type > 5) {
            throw new IllegalArgumentException("T-CONT requires positive allocation/SFU IDs and type 1 through 5");
        }
        Objects.requireNonNull(classes, "T-CONT business classes");
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("T-CONT must map at least one nonnull business class");
        }
        for (AccessCategory category : classes) {
            Objects.requireNonNull(category, "T-CONT business class");
        }
        if (siMaxFrames < 0 || siMinFrames < 0 || abMinBytes < 0 || abSurBytes < 0
                || siFixedFrames < 0 || abFixedBytes < 0 || siBeFrames < 0 || abBeBytes < 0) {
            throw new IllegalArgumentException("T-CONT SI/AB parameters must be nonnegative");
        }
        if (type != 1 && siMaxFrames == 0) {
            throw new IllegalArgumentException("T-CONT Type 2–5 require positive SImax");
        }
        if (type == 1 && (siFixedFrames == 0 || abFixedBytes == 0 || siMaxFrames != 0 || abMinBytes != 0
                || siMinFrames != 0 || abSurBytes != 0 || siBeFrames != 0 || abBeBytes != 0)) {
            throw new IllegalArgumentException("Type 1 requires positive fixed SI/AB and zero other SI/AB parameters");
        }
        if (type == 2 && (abMinBytes == 0 || siMinFrames != 0 || abSurBytes != 0)) {
            throw new IllegalArgumentException("Type 2 requires positive ABmin; unused SImin and ABsur must be zero");
        }
        if (type == 3 && (siMinFrames == 0 || abMinBytes == 0 || abSurBytes == 0)) {
            throw new IllegalArgumentException("Type 3 requires positive SImax, SImin, ABmin and ABsur");
        }
        if (type == 4 && (siMinFrames == 0 || abSurBytes == 0)) {
            throw new IllegalArgumentException("Type 4 requires positive SImax, SImin and ABsur");
        }
        if (type >= 2 && type <= 4 && (siFixedFrames != 0 || abFixedBytes != 0 || siBeFrames != 0 || abBeBytes != 0)) {
            throw new IllegalArgumentException("Type 2–4 do not use independent fixed or best-effort parameters");
        }
        if (type == 5 && (siFixedFrames == 0 || abFixedBytes == 0 || abMinBytes == 0 || siMinFrames == 0
                || abSurBytes == 0 || siBeFrames == 0 || abBeBytes == 0)) {
            throw new IllegalArgumentException("Type 5 requires positive SI/AB pairs for all four stages");
        }
        // SImax/ABmin 与 SImin/ABsur 属于不同服务角色，不按名称附会大小关系。
        // Type 4 的 SImax/ABmin 是本地统一配置的预留字段，不驱动当前轮询，
        // 不表示保证用户 DATA 带宽，也不声称它们是 Type 4 的标准必填参数。
        this.allocationId = allocationId;
        this.sfuId = sfuId;
        this.type = type;
        this.classes = Collections.unmodifiableSet(EnumSet.copyOf(classes));
        this.siMaxFrames = siMaxFrames;
        this.siMinFrames = siMinFrames;
        this.abMinBytes = abMinBytes;
        this.abSurBytes = abSurBytes;
        this.siFixedFrames = siFixedFrames;
        this.abFixedBytes = abFixedBytes;
        this.siBeFrames = siBeFrames;
        this.abBeBytes = abBeBytes;
    }

    public long allocationId() { return allocationId; }
    public int sfuId() { return sfuId; }
    public int type() { return type; }
    public Set<AccessCategory> classes() { return classes; }

    /** SI 使用上行帧数，不能误当成微秒；默认每帧为 125 μs。 */
    public int siMaxFrames() { return siMaxFrames; }
    public int siMinFrames() { return siMinFrames; }

    /** AB 使用整数字节，GIANT 按对应服务间隔使用此额度。 */
    public long abMinBytes() { return abMinBytes; }
    public long abSurBytes() { return abSurBytes; }
    public int siFixedFrames() { return siFixedFrames; }
    public long abFixedBytes() { return abFixedBytes; }
    public int siBeFrames() { return siBeFrames; }
    public long abBeBytes() { return abBeBytes; }

    /** Type 4 的 ABmin 是历史预留字段，不能计作保证 DATA 资源。 */
    public long assuredBytes() { return type == 2 || type == 3 || type == 5 ? abMinBytes : 0; }
}
