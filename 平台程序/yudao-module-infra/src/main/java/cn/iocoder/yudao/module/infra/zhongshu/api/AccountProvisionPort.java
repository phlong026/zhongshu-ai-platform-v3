package cn.iocoder.yudao.module.infra.zhongshu.api;

/**
 * 账号供给端口（B2 板块 A / P-5，identity 实现）：为 B 端组织供给占位 C 端账号。
 *
 * <p>identity 是 account 表唯一写方；幂等源=external_key 稳定业务键（普通 UNIQUE 约束，
 * PG 唯一约束允许多 NULL，与 ON CONFLICT 推断匹配）。并发/重启幂等：
 * {@code INSERT ... ON CONFLICT (external_key) DO NOTHING} 后同事务查询，禁止「异常后重查」。
 */
public interface AccountProvisionPort {

    /**
     * 取或建账号并返回其状态。externalKey 为稳定业务键（如 fr-service:{orgId}）；
     * 已存在（含他方并发创建）返回既有账号，不复活已停用/关闭账号——active=false 由调用方换译业务码。
     */
    EnsuredAccount ensureActiveAccount(String externalKey, String nickname);

    /** active=false 表示账号存在但被管理端停用/关闭；调用方据此拒绝而非换建 */
    record EnsuredAccount(long accountId, boolean active) {
    }
}
