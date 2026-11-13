package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
    private final ContentScriptService scripts;
    private final boolean enabled;

    public ContentTaskJob(ContentTaskStore store, ContentTaskService tasks, ContentScriptService scripts,
                          @Value("${zhongshu.content.task-sweep-enabled:false}") boolean enabled) {
        this.store = store;
        this.tasks = tasks;
        this.scripts = scripts;
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
    }

    /** 终态任务的公司池结算重试（评审 P1-4）：瞬时故障/进程退出后不永久悬空。 */
    private void reconcilePendingPool() {
        for (FrContentTaskDO task : store.findTerminalWithPendingPool(20)) {
            try {
                tasks.reconcilePool(task);
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
                settleIfSucceeded(task); // 同步任务（link_parse/Flash ASR/copy）提交相即终态，当场收尾
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
                settleIfSucceeded(task);
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

    /** SUCCEEDED 收尾：脚本行落库（幂等——已有 task_id 行则跳过）。 */
    private void settleIfSucceeded(FrContentTaskDO task) {
        FrContentTaskDO fresh = store.byId(task.getCompanyId(), task.getId());
        if (fresh == null || !"SUCCEEDED".equals(fresh.getStatus())) {
            return;
        }
        Map<String, Object> result = fresh.getResultSnapshot() == null ? Map.of()
                : JsonUtils.parseObject(fresh.getResultSnapshot(), Map.class);
        try {
            switch (fresh.getKind()) {
                case "copy" -> scripts.recordOptimizeResult(fresh,
                        String.valueOf(result.get("text")),
                        String.valueOf(result.get("engine")),
                        fresh.getModel());
                case "asr" -> scripts.recordExtractedScript(fresh, "upload", null, null,
                        String.valueOf(result.get("text")),
                        result.get("durationSec") == null ? null
                                : (int) Double.parseDouble(String.valueOf(result.get("durationSec"))));
                case "link_parse" -> {
                    String text = String.valueOf(result.get("text"));
                    if (!text.isBlank()) {
                        scripts.recordExtractedScript(fresh, "link",
                                String.valueOf(result.get("videoUrl")),
                                String.valueOf(result.get("platform")), text, null);
                    }
                }
                default -> {
                    // digital/ai_video 无脚本行
                }
            }
        } catch (RuntimeException e) {
            log.warn("content task {} settle failed: {}", fresh.getId(), e.getMessage());
        }
    }

}
