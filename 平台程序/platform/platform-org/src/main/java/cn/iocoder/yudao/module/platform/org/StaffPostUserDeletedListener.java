package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.system.event.AdminUserDeletedEvent;
import cn.iocoder.yudao.module.system.event.AdminUserDisabledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import java.util.List;

/**
 * M01-T01 授权回收契约的平台侧监听：后台用户删除提交后回收其全部业务任职与钱包会话。
 *
 * <p>system 模块删除用户时同步发布 {@link AdminUserDeletedEvent}（依赖方向 platform → system，
 * 反向事件通知避免依赖成环）。
 *
 * <p><b>AFTER_COMMIT + 每次尝试独立 REQUIRES_NEW 事务（Codex R1 P1-1 锁序消解）</b>：删除事务
 * 只持 system 侧行锁，本监听在提交后只取 pf 岗位锁——与成员停用（pf 锁→用户锁）、撤岗
 * （pf 锁→角色锁）均无「持用户锁等 pf 锁」的反向环节，结构性消除死锁环。单次尝试失败整体回滚
 * （任职不得半撤），重试 3 次；最终失败记 error 日志人工介入——userUsable 门禁兜底业务侧，
 * 属可见残留而非静默漏洞。{@code fallbackExecution = true}：无事务上下文发布（如测试直发）同样触发。
 */
@Slf4j
@Component
public class StaffPostUserDeletedListener {

    /** 回收重试次数与间隔（PG 竞争的兜底；revokeAll 幂等可安全重放） */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 200;

    @Resource
    private StaffPostService staffPostService;
    /** 自注入走代理：使每次重试的 {@link #revokeInNewTransaction} 获得独立事务 */
    @Resource
    @Lazy
    private StaffPostUserDeletedListener self;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserDeleted(AdminUserDeletedEvent event) {
        List<Long> userIds = List.copyOf(event.userIds());
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                self.revokeInNewTransaction(userIds);
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    // 最终失败：删除已提交而任职/钱包回收未完成——userUsable 门禁兜底业务侧，
                    // 需人工核对 pf_staff_post 与钱包会话，不静默吞掉
                    log.error("M01-T01 用户删除后任职/钱包回收失败（已重试 {} 次，userIds={}）",
                            MAX_ATTEMPTS, userIds, e);
                    return;
                }
                log.warn("M01-T01 用户删除回收第 {} 次尝试失败，将重试（userIds={}）：{}",
                        attempt, userIds, e.getMessage());
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 单次回收尝试：独立事务，任一 userId 回收失败即整体回滚（不留半撤权状态） */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void revokeInNewTransaction(List<Long> userIds) {
        for (Long userId : userIds) {
            staffPostService.revokeAllForDeletedUser(userId);
        }
    }

    /**
     * 全局账号停用（M01-T02 / R01：仅平台管理员经 Web 底座触发）：作用范围=全部组织，
     * 回收契约与删除一致（撤销全部任职+钱包会话）。AFTER_COMMIT+REQUIRES_NEW 结构与删除路径相同。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserDisabled(AdminUserDisabledEvent event) {
        onUserDeleted(new AdminUserDeletedEvent(event.userIds(), event.tokenCacheKeys()));
    }
}

