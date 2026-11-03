package cn.iocoder.yudao.module.platform.org;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用的 yudao 系统目录：部门树、角色、用户及用户角色都在内存中。
 */
class FakeSystemDirectory implements SystemDirectory {

    static final AtomicLong DEPT_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L); // 容器跨测试类共享且不清库：static 共享保证全 JVM 唯一（Codex R1 P3：实例字段跨类重启段）
    final Map<Long, Long> deptParent = new HashMap<>();
    final Map<String, Long> roleIdsByCode = new HashMap<>();
    final Set<Long> users = new HashSet<>();
    final Map<Long, Set<Long>> userRoles = new HashMap<>();
    final Map<String, Set<String>> rolePermissions = new HashMap<>();
    final Map<Long, String> userNames = new HashMap<>();
    final Map<Long, String> userMobiles = new HashMap<>();
    final Map<Long, Boolean> userActive = new HashMap<>();
    final Map<Long, String> deptNames = new HashMap<>();
    long nextUserId = 9000;

    @Override
    public long createDept(Long parentDeptId, String name) {
        long id = DEPT_IDS.incrementAndGet();
        deptParent.put(id, parentDeptId == null ? 0L : parentDeptId);
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
                if (result.contains(e.getValue()) && result.add(e.getKey())) {
                    added = true;
                }
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
    public boolean userUsable(long userId) {
        return users.contains(userId) && userActive.getOrDefault(userId, true);
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
        return rolePermissions.getOrDefault(roleCode, Set.of()).contains(permission);
    }


    @Override
    public Long userIdByMobile(String mobile) {
        return userMobiles.entrySet().stream().filter(e -> e.getValue().equals(mobile))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    @Override
    public long createUser(String name, String mobile, Long deptId) {
        long id = ++nextUserId;
        users.add(id); // M01-T05：与真实 YudaoSystemDirectory 语义对齐——建号后 userExists/userUsable 为真
        userNames.put(id, name);
        userMobiles.put(id, mobile);
        userActive.put(id, true);
        return id;
    }

    @Override
    public java.util.List<UserBrief> usersByIds(java.util.Collection<Long> userIds) {
        java.util.List<UserBrief> result = new java.util.ArrayList<>();
        for (Long id : userIds) {
            if (userNames.containsKey(id)) {
                result.add(new UserBrief(id, userNames.get(id), userMobiles.get(id),
                        userActive.getOrDefault(id, true)));
            }
        }
        return result;
    }

    @Override
    public void setUserActive(long userId, boolean active) {
        userActive.put(userId, active);
    }

    @Override
    public String deptName(Long deptId) {
        return deptId == null ? "" : deptNames.getOrDefault(deptId, "");
    }

    @Override
    public java.util.List<Long> findRootDeptIdsByName(String name) {
        return deptParent.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() == 0L)
                .map(java.util.Map.Entry::getKey)
                .filter(id -> name != null && name.equals(deptNames.get(id)))
                .toList();
    }
}
