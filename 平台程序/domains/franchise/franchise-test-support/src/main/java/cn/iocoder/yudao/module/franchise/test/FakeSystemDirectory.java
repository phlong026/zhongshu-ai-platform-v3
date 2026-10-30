package cn.iocoder.yudao.module.franchise.test;

import cn.iocoder.yudao.module.platform.org.SystemDirectory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用 yudao 系统目录：部门树、角色、用户都在内存中。
 * 全 JVM 共用一个实例（{@link #INSTANCE}），因为不同测试类的 Spring 上下文共用同一个数据库。
 */
public final class FakeSystemDirectory implements SystemDirectory {

    public static final FakeSystemDirectory INSTANCE = new FakeSystemDirectory();

    private final AtomicLong ids = new AtomicLong(700_000_000L);
    private final Map<Long, Long> deptParent = new ConcurrentHashMap<>();
    private final Map<String, Long> roleIds = new ConcurrentHashMap<>();
    private final Set<Long> users = ConcurrentHashMap.newKeySet();
    private final Map<Long, Set<Long>> userRoles = new ConcurrentHashMap<>();

    private FakeSystemDirectory() {
    }

    /** 新建一个测试用户，返回用户编号 */
    public long newUser() {
        long id = ids.incrementAndGet();
        users.add(id);
        return id;
    }

    @Override
    public long createDept(Long parentDeptId, String name) {
        long id = ids.incrementAndGet();
        deptParent.put(id, parentDeptId == null ? 0L : parentDeptId);
        return id;
    }

    @Override
    public Set<Long> deptAndDescendants(long deptId) {
        Set<Long> result = new java.util.HashSet<>(Set.of(deptId));
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
        return roleIds.computeIfAbsent(roleCode, code -> ids.incrementAndGet());
    }

    @Override
    public boolean userExists(long userId) {
        return users.contains(userId);
    }

    @Override
    public Set<Long> userRoleIds(long userId) {
        return Set.copyOf(userRoles.getOrDefault(userId, Set.of()));
    }

    @Override
    public void replaceUserRoles(long userId, Set<Long> roleIds) {
        userRoles.put(userId, Set.copyOf(roleIds));
    }

    @Override
    public boolean roleHasPermission(String roleCode, String permission) {
        return true; // 功能权限由 @post.has 在接口层校验，服务层测试只关心对象规则
    }

}
