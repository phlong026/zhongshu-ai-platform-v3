package cn.iocoder.yudao.module.system.event;

import java.util.Collection;
import java.util.List;

/**
 * 后台用户已删除事件（单删/批删统一，删除事务内同步发布）。
 *
 * <p>M01-T01 授权回收契约：system 模块删除用户后，须同步回收延伸授权——OAuth2 令牌在
 * system 侧直接移除；平台业务任职（pf_staff_post）、钱包会话等由依赖 system 的模块
 * 监听本事件回收（模块依赖方向：platform → system，反向依赖成环，故走事件）。
 * 监听方与发布方同事务执行（{@code @EventListener} 同步语义），事务失败整体回滚。
 *
 * <p>{@code tokenCacheKeys} 是删除前捕获的精确 Redis 键（access 与配对 refresh 令牌串；
 * Codex R2 P1：提交后按 userId/String 查库必空转——removeAccessToken 两变体都先查库早退，
 * 须按键直删缓存）。提交后清理监听 {@link AdminUserDeletedTokenCleaner} 逐键删除。
 */
public record AdminUserDeletedEvent(Collection<Long> userIds, Collection<String> tokenCacheKeys) {

    /** 便捷构造：无精确缓存键（平台侧测试直发等场景；令牌按键清理由 system 发布方填充） */
    public AdminUserDeletedEvent(Collection<Long> userIds) {
        this(userIds, List.of());
    }
}
