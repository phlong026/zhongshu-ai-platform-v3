package cn.iocoder.yudao.module.system.event;

import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.Resource;

/**
 * M01-T01：用户删除提交后的令牌缓存二次失效（Codex R1 P1-2 收口）。
 *
 * <p>删除事务内 removeAccessToken 先删库后删缓存；事务提交前并发的令牌校验可能「缓存未命中→
 * 读到未提交的旧行→重新缓存」。本监听在提交后对同一批用户再执行一次移除——此时库行已删，
 * 重缓存来源消失，旧 access token 重放稳定 401。removeAccessToken 幂等（连配对 refresh 重复删）。
 */
@Component
public class AdminUserDeletedTokenCleaner {

    @Resource
    private OAuth2AccessTokenRedisDAO oauth2AccessTokenRedisDAO;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(AdminUserDeletedEvent event) {
        // 直删缓存键（Codex R2 P1：提交后按 userId/String 查库均为空集——removeAccessToken 两变体
        // 都先查库早退，按键清理必须绕开库查询）；键为删除前捕获的 access 与配对 refresh 串，重复删幂等
        event.tokenCacheKeys().forEach(oauth2AccessTokenRedisDAO::delete);
    }
}
