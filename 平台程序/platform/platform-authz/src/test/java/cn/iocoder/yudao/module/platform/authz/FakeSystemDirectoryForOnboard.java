package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.module.platform.org.SystemDirectory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 组织开通集成测试用系统目录（含部门树按名查重）；独立于 authz 既有 Stub 体系。 */
public class FakeSystemDirectoryForOnboard implements SystemDirectory {

    private final AtomicLong deptIds = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    final Map<Long, Long> deptParent = new HashMap<>();
    final Map<Long, String> deptNames = new HashMap<>();
    public final Map<String, Long> roleIdsByCode = new ConcurrentHashMap<>();
    public final Set<Long> users = ConcurrentHashMap.newKeySet();
    public final Map<Long, String> userMobiles = new ConcurrentHashMap<>();
    public final Map<Long, Boolean> userActive = new ConcurrentHashMap<>();
    final Map<Long, Set<Long>> userRoles = new HashMap<>();
    private final AtomicLong userIds = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Override
    public long createDept(Long parentDeptId, String name) {
        // 对齐底座 validateDeptNameUnique(parentId+name)：根层级同名拒绝（Codex R1 P2-1——Fake 需复刻该约束）
        boolean rootDuplicate = (parentDeptId == null || parentDeptId == 0L)
                && deptNames.entrySet().stream()
                        .anyMatch(e -> name.equals(e.getValue())
                                && deptParent.getOrDefault(e.getKey(), 0L) == 0L);
        if (rootDuplicate) {
            throw new RuntimeException("DEPT_NAME_DUPLICATE");
        }
        long id = deptIds.incrementAndGet();
        deptParent.put(id, parentDeptId == null ? 0L : parentDeptId);
        deptNames.put(id, name);
        return id;
    }

    @Override
    public Set<Long> deptAndDescendants(long deptId) {
        Set<Long> result = new HashSet<>();
        result.add(deptId);
        boolean added = true;
        while (added) {
            added = false;
            for (var e : deptParent.entrySet()) {
                if (result.contains(e.getValue()) && result.add(e.getKey())) added = true;
            }
        }
        return result;
    }

    @Override
    public Long roleIdByCode(String roleCode) {
        return roleIdsByCode.get(roleCode);
    }

    @Override
    public boolean userExists(long userId) {
        return users.contains(userId);
    }

    @Override
    public Set<Long> userRoleIds(long userId) {
        return new HashSet<>(userRoles.getOrDefault(userId, Set.of()));
    }

    @Override
    public void replaceUserRoles(long userId, Set<Long> roleIds) {
        userRoles.put(userId, new HashSet<>(roleIds));
    }

    @Override
    public boolean roleHasPermission(String roleCode, String permission) {
        return false;
    }

    @Override
    public Long userIdByMobile(String mobile) {
        return userMobiles.entrySet().stream().filter(e -> e.getValue().equals(mobile))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    @Override
    public long createUser(String name, String mobile, Long deptId) {
        long id = userIds.incrementAndGet();
        users.add(id);
        userMobiles.put(id, mobile);
        userActive.put(id, true);
        return id;
    }

    @Override
    public List<UserBrief> usersByIds(java.util.Collection<Long> userIds) {
        List<UserBrief> result = new java.util.ArrayList<>();
        for (Long id : userIds) {
            if (users.contains(id)) result.add(new UserBrief(id, "员工" + id, userMobiles.get(id), userActive.getOrDefault(id, true)));
        }
        return result;
    }

    @Override
    public void setUserActive(long userId, boolean active) {
        userActive.put(userId, active);
    }

    @Override
    public boolean userUsable(long userId) {
        return users.contains(userId) && userActive.getOrDefault(userId, true);
    }

    @Override
    public String deptName(Long deptId) {
        return deptId == null ? "" : deptNames.getOrDefault(deptId, "");
    }

    @Override
    public List<Long> findRootDeptIdsByName(String name) {
        return deptParent.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() == 0L)
                .map(Map.Entry::getKey)
                .filter(id -> name != null && name.equals(deptNames.get(id)))
                .toList();
    }
}
