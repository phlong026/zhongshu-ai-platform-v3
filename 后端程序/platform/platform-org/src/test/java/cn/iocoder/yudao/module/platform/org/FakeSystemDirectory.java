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

    private final AtomicLong deptIds = new AtomicLong(900_000);
    final Map<Long, Long> deptParent = new HashMap<>();
    final Map<String, Long> roleIdsByCode = new HashMap<>();
    final Set<Long> users = new HashSet<>();
    final Map<Long, Set<Long>> userRoles = new HashMap<>();

    @Override
    public long createDept(Long parentDeptId, String name) {
        long id = deptIds.incrementAndGet();
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
    public Set<Long> userRoleIds(long userId) {
        return new HashSet<>(userRoles.getOrDefault(userId, Set.of()));
    }

    @Override
    public void replaceUserRoles(long userId, Set<Long> roleIds) {
        userRoles.put(userId, new HashSet<>(roleIds));
    }

}
