package cn.iocoder.yudao.module.franchise.org;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;

/**
 * 加盟域命令键公共规范化（B2 板块 A / P-4）：
 * 幂等键必填、长度≤200；锁键与收据键同源（sha256 映射），
 * 避免 FranchiseCommands.scopedKey 的 80 字符截断在长键上碰撞（Codex R3）。
 */
public final class FranchiseCommandKeys {

    public static final int MAX_LENGTH = 200;

    private FranchiseCommandKeys() {
    }

    /** 校验并规范化幂等键；非法输入抛 IllegalArgumentException（参数校验层拦截） */
    public static String normalize(String key) {
        if (StrUtil.isBlank(key)) {
            throw new IllegalArgumentException("幂等键必填");
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("幂等键超长");
        }
        return trimmed;
    }

    /** advisory 锁键：sha256(scopedKey) 前 63 位转 long（pg_advisory_xact_lock(bigint) 用） */
    public static long lockKey(PostKeyScope scope, String normalizedKey) {
        String hex = DigestUtil.sha256Hex(scope.scoped(normalizedKey));
        return Long.parseUnsignedLong(hex.substring(0, 15), 16) & 0x7fffffffffffffffL;
    }

    /** 命令收据键（FranchiseCommands.scopedKey 之外需要显式控制前缀时使用） */
    public static String scopedKey(String domainPrefix, long userId, String normalizedKey) {
        return domainPrefix + ":" + userId + ":" + normalizedKey;
    }

    /** 键作用域：与 FranchiseCommands 同源的「域 + 用户」前缀 */
    public record PostKeyScope(String domain, long userId) {
        public String scoped(String normalizedKey) {
            return domain + ":" + userId + ":" + normalizedKey;
        }
    }
}
