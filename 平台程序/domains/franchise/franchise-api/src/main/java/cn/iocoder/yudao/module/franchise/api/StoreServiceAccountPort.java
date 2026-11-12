package cn.iocoder.yudao.module.franchise.api;

/**
 * 组织服务账号端口（B2 板块 A / A9）：每组织一个 identity C 端占位账号，
 * 仅满足 design_project.user_id NOT NULL 与资产归属，无业务归属含义。
 *
 * <p>解析唯一路径=identity {@code account.external_key}（"fr-service:{orgId}"，UNIQUE），
 * 无第二份绑定映射（Codex R6 评审删除双映射）；identity 是账号唯一写方。
 * 每次解析都执行状态校验——被管理端停用的账号返回 {@link ServiceAccountInactiveException}，
 * 禁止惰性复活或换建新号（恢复=identity 管理端显式启用，M5 待建）。
 */
public interface StoreServiceAccountPort {

    /** 返回本组织服务账号 account.id（ACTIVE）；惰性创建（ON CONFLICT 语义，并发安全） */
    long requireServiceAccount(long orgId);

    /**
     * 已持久化后台任务专用：只读 ACTIVE 映射，并复核组织所属租户；不创建账号、不依赖员工会话。
     * 调用方的 tenant/org 必须来自服务端持久化任务与组织目录，禁止直接透传 HTTP 入参。
     */
    default long requireExistingServiceAccountForTask(long tenantId, long orgId) {
        throw new ServiceAccountInactiveException(orgId);
    }

    /** 服务账号被停用/关闭时的可辨识信号（不携带敏感信息；调用方换译 1_112_000_004） */
    class ServiceAccountInactiveException extends RuntimeException {
        public ServiceAccountInactiveException(long orgId) {
            super("service account inactive for org " + orgId);
        }
    }
}
