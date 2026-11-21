package edu.suda.fttr.dba;

import edu.suda.fttr.entity.AccessCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 显式区分逻辑 allocationId 与物理 sfuId 的不可变拓扑。
 * 开启时，每台 SFU 的四类业务恰好映射一次；关闭时保留原有每 SFU 一个对象的编号。
 */
public final class TcontTopology {
    private final boolean enabled;
    private final int sfuCount;
    private final List<TcontProfile> profiles;
    private final Map<Long, TcontProfile> byAllocation;
    private final Map<Integer, List<TcontProfile>> bySfu;
    private final Map<Integer, Map<AccessCategory, Long>> byClass;
    private final Map<Long, Integer> allocationOwners;

    public TcontTopology(int sfuCount, List<TcontProfile> profiles) {
        this(sfuCount, profiles, true);
    }

    private TcontTopology(int sfuCount, List<TcontProfile> profiles, boolean enabled) {
        if (sfuCount < 1) {
            throw new IllegalArgumentException("T-CONT topology requires at least one SFU");
        }
        this.enabled = enabled;
        this.sfuCount = sfuCount;
        List<TcontProfile> ordered = new ArrayList<>(Objects.requireNonNull(profiles));
        ordered.sort(Comparator.comparingInt(TcontProfile::sfuId).thenComparingLong(TcontProfile::allocationId));
        Map<Long, TcontProfile> allocations = new LinkedHashMap<>();
        Map<Integer, List<TcontProfile>> owners = new LinkedHashMap<>();
        Map<Integer, Map<AccessCategory, Long>> mappings = new LinkedHashMap<>();
        Map<Long, Integer> identities = new LinkedHashMap<>();
        for (int sfuId = 1; sfuId <= sfuCount; sfuId++) {
            owners.put(sfuId, new ArrayList<>());
            mappings.put(sfuId, new EnumMap<>(AccessCategory.class));
            if (!enabled) {
                // 与第 10 步起使用的稳定逻辑编号一致，不能改成 allocationId = sfuId。
                identities.put(2L * sfuId, sfuId);
                for (AccessCategory category : AccessCategory.values()) {
                    mappings.get(sfuId).put(category, 2L * sfuId);
                }
            }
        }
        for (TcontProfile profile : ordered) {
            if (profile.sfuId() > sfuCount) {
                throw new IllegalArgumentException("T-CONT references an unknown SFU: " + profile.sfuId());
            }
            if (allocations.putIfAbsent(profile.allocationId(), profile) != null) {
                throw new IllegalArgumentException("Duplicate T-CONT allocation ID: " + profile.allocationId());
            }
            identities.put(profile.allocationId(), profile.sfuId());
            owners.get(profile.sfuId()).add(profile);
            for (AccessCategory category : profile.classes()) {
                if (mappings.get(profile.sfuId()).putIfAbsent(category, profile.allocationId()) != null) {
                    throw new IllegalArgumentException("Business class " + category + " maps to multiple T-CONTs on SFU "
                            + profile.sfuId());
                }
            }
        }
        for (int sfuId = 1; sfuId <= sfuCount; sfuId++) {
            if (mappings.get(sfuId).size() != AccessCategory.values().length) {
                throw new IllegalArgumentException("T-CONT mappings must cover VO, VI, BE and BK on SFU " + sfuId);
            }
            owners.put(sfuId, List.copyOf(owners.get(sfuId)));
            mappings.put(sfuId, Collections.unmodifiableMap(mappings.get(sfuId)));
        }
        this.profiles = List.copyOf(ordered);
        this.byAllocation = Collections.unmodifiableMap(allocations);
        this.bySfu = Collections.unmodifiableMap(owners);
        this.byClass = Collections.unmodifiableMap(mappings);
        this.allocationOwners = Collections.unmodifiableMap(identities);
    }

    public static TcontTopology disabled(int sfuCount) {
        return new TcontTopology(sfuCount, List.of(), false);
    }

    public boolean enabled() { return enabled; }
    public List<TcontProfile> profiles() { return profiles; }

    public List<TcontProfile> profilesForSfu(int sfuId) {
        requireSfu(sfuId);
        return bySfu.get(sfuId);
    }

    /** 兼容模式的默认对象没有 T-CONT 服务参数，返回 null；未知对象始终报错。 */
    public TcontProfile profile(long allocationId) {
        if (!allocationOwners.containsKey(allocationId)) {
            throw new IllegalArgumentException("Unknown T-CONT allocation ID: " + allocationId);
        }
        return byAllocation.get(allocationId);
    }

    public long allocationFor(int sfuId, AccessCategory category) {
        requireSfu(sfuId);
        return byClass.get(sfuId).get(Objects.requireNonNull(category));
    }

    /** 按 SFU、allocationId 升序排列的只读所有权映射。 */
    public Map<Long, Integer> allocationOwners() { return allocationOwners; }

    private void requireSfu(int sfuId) {
        if (sfuId < 1 || sfuId > sfuCount) {
            throw new IllegalArgumentException("Unknown SFU: " + sfuId);
        }
    }
}
