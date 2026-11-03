package cn.iocoder.yudao.module.system.event;

import java.util.Collection;

/**
 * 后台用户已被全局停用事件（平台管理员经 Web 底座 update-status 触发；M01-T02）。
 *
 * <p>R01：全局账号停用仅平台管理员执行，作用范围=全部组织。system 侧停用时已移除令牌并
 * 捕获精确缓存键；平台侧监听本事件回收该用户全部业务任职与钱包会话（与删除同契约）。
 * {@code tokenCacheKeys} 语义同 {@link AdminUserDeletedEvent}。
 */
public record AdminUserDisabledEvent(Collection<Long> userIds, Collection<String> tokenCacheKeys) {
}
