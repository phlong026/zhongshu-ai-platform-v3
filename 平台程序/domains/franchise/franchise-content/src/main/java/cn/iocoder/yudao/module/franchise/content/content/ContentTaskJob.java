package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 内容任务扫写 Job（对标 AiJobCancelRecoveryJob 样板：开关+间隔+分页扫描+逐条独立收口）：
 * ①提交 QUEUED → SUBMITTING（事务外外呼 + CAS 收口）；
 * ②轮询 RUNNING / UNCERTAIN-with-task-id（UNCERTAIN 只查询对账，绝不重投——A2.2）；
 * ③2h 总窗超时 RUNNING → SUBMISSION_UNCERTAIN 转人工（A2.6）；
 * ④SUCCEEDED 收尾：copy→脚本行 optimized 落库；asr/link_parse→脚本行 raw 落库。
 */
@Component
public class ContentTaskJob {

    private static final Logger log = LoggerFactory.getLogger(ContentTaskJob.class);

    private final ContentTaskStore store;
    private final ContentTaskService tasks;
    private final boolean enabled;
    private final ContentTaskResultService results;

    public ContentTaskJob(ContentTaskStore store, ContentTaskService tasks, ContentTaskResultService results,
                          @Value("${zhongshu.content.task-sweep-enabled:false}") boolean enabled) {
        this.store = store;
        this.tasks = tasks;
        this.results = results;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${zhongshu.content.task-sweep-interval-ms:3000}", initialDelayString = "5000")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            submitQueued();
            pollInFlight();
            timeoutOverdue();
            reconcilePendingPool();
        } catch (RuntimeException e) {
            log.warn("content task sweep failed: {}", e.getMessage());
        }
        // 恢复已成功但尚未保存的任务，包括进程重启和缓存命中；不依赖本轮提交/轮询。
        try {
            deliverPendingResults();
        } catch (RuntimeException e) {
            log.warn("content result delivery sweep failed: {}", e.getMessage());
        }
    }

    /** 恢复终态任务中因瞬时故障未完成的积分池结算。 */
    private void reconcilePendingPool() {
        String leaseToken = UUID.randomUUID().toString();
        for (FrContentTaskDO task : store.claimTerminalWithPendingPool(20, leaseToken, 120)) {
            try {
                tasks.reconcilePool(task, leaseToken);
            } catch (RuntimeException e) {
                log.warn("content task {} pool reconcile failed: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void submitQueued() {
        List<FrContentTaskDO> queued = store.findQueued(20);
        for (FrContentTaskDO task : queued) {
            try {
                tasks.submitOne(task);
            } catch (RuntimeException e) {
                log.warn("content task {} submit crashed: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void pollInFlight() {
        List<FrContentTaskDO> inFlight = store.findInFlight(30);
        for (FrContentTaskDO task : inFlight) {
            try {
                tasks.pollOne(task);
            } catch (RuntimeException e) {
                log.warn("content task {} poll crashed: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void timeoutOverdue() {
        for (FrContentTaskDO task : store.findPollTimedOut(ContentTaskService.POLL_WINDOW_HOURS, 20)) {
            boolean submitted = task.getProviderTaskId() != null && !task.getProviderTaskId().isBlank();
            store.markUncertain(task.getId(), task.getCompanyId(), submitted
                    ? "轮询超窗（" + ContentTaskService.POLL_WINDOW_HOURS + " 小时），已转人工核查；供应商任务号保留可对账"
                    : "提交中断滞留超窗，已转人工核查；未取得供应商任务号，不可自动重投");
            log.warn("content task {} poll window exceeded ({}), parked SUBMISSION_UNCERTAIN",
                    task.getId(), task.getStatus());
        }
    }

    /** 每个结果独立事务；局部保存失败不阻塞下一条，也不重投生成任务。 */
    private void deliverPendingResults() {
        for (var row : results.pending()) {
            long companyId = ((Number) row.get("company_id")).longValue();
            long id = ((Number) row.get("id")).longValue();
            try {
                results.deliver(companyId, id);
            } catch (RuntimeException e) {
                log.warn("content task {} result delivery failed: {}", id, e.getMessage());
                try {
                    results.failed(companyId, id);
                } catch (RuntimeException deferError) {
                    log.warn("content task {} result retry scheduling failed: {}", id, deferError.getMessage());
                }
            }
        }
    }
}
