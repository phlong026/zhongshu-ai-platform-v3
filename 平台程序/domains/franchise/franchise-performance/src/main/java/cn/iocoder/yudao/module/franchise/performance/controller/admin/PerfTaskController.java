package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.franchise.performance.content.PerfTaskService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.performance.controller.admin.PerfCommon.*;

/** 绩效任务机（3.8）：派发/改派/补填/范围查询/三组 summary/上月初始化/归档链。 */
@Tag(name = "加盟 - 绩效提成（任务）")
@RestController
@RequestMapping("/franchise/performance")
@Validated
public class PerfTaskController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private PerfTaskService tasks;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/tasks")
    @Operation(summary = "派发任务（幂等，manage；manual 无目标/auto 十类+target+due 月内）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(tasks.create(post(), new PerfTaskService.TaskCommand(
                str(body.get("period")), str(body.get("title")),
                longOrNull(body.get("assigneeUserId")) == null ? 0 : longOrNull(body.get("assigneeUserId")),
                str(body.get("assigneeName")), str(body.get("assigneeDept")), str(body.get("type")),
                longOrNull(body.get("targetCents")) == null ? 0L : longOrNull(body.get("targetCents")),
                localDate(body.get("due")), map(body.get("suggestion"))), requireIdem(idem)));
    }

    @GetMapping("/tasks")
    @Operation(summary = "任务列表（?period=&scope=mine|company|department|member&dept=&userId=；含实时 auto 进度三态）")
    @PreAuthorize("@post.has('" + PerformancePermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list(@RequestParam String period,
                                                        @RequestParam(required = false, defaultValue = "mine") String scope,
                                                        @RequestParam(required = false) String dept,
                                                        @RequestParam(required = false) Long userId) {
        return success(tasks.list(post(), period, scope, dept, userId));
    }

    @GetMapping("/tasks/summary")
    @Operation(summary = "三组指标汇总（?period=；业务7/工程4/财务1+目标对照）")
    @PreAuthorize("@post.has('" + PerformancePermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> summary(@RequestParam String period) {
        return success(tasks.summary(post(), period));
    }

    @PostMapping("/tasks/{id}/reassign")
    @Operation(summary = "改派（幂等，manage；manualDone 清空+留痕；归档禁改）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> reassign(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(tasks.reassign(post(), id,
                longOrNull(body.get("assigneeUserId")) == null ? 0 : longOrNull(body.get("assigneeUserId")),
                str(body.get("assigneeName")), requireIdem(idem)));
    }

    @PostMapping("/tasks/{id}/manual-complete")
    @Operation(summary = "手动补填（幂等，query=被考核本人；note≤500+photos≤6 随 COS 批降维）")
    @PreAuthorize("@post.has('" + PerformancePermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> completeManual(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(tasks.completeManual(post(), id, new PerfTaskService.ManualDoneCommand(
                str(body.get("note")),
                body.get("photos") instanceof List<?> photos ? photos.stream().map(String::valueOf).toList() : List.of()),
                requireIdem(idem)));
    }

    @PostMapping("/tasks/initialize-previous")
    @Operation(summary = "按上月实绩生成建议任务（幂等，manage；suggested 键去重）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> initializePrevious(@RequestBody Map<String, Object> body,
                                                                @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(tasks.initializePrevious(post(), str(body.get("period")), requireIdem(idem)));
    }

    @PostMapping("/tasks/archive")
    @Operation(summary = "归档（幂等，manage；快照 tasks+sourceSignature；有申诉阻断）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> archive(@RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(tasks.archive(post(), str(body.get("period")), str(body.get("note")),
                false, requireIdem(idem)));
    }

    @PostMapping("/tasks/archive/revise")
    @Operation(summary = "归档修订（幂等，manage；仅当 sourceSignature 变化 003）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> revise(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(tasks.archive(post(), str(body.get("period")), str(body.get("note")),
                true, requireIdem(idem)));
    }

    @GetMapping("/tasks/archives")
    @Operation(summary = "归档快照列表（?period=；含 needsRevision 派生）")
    @PreAuthorize("@post.has('" + PerformancePermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> archives(@RequestParam String period) {
        return success(tasks.archiveView(post(), period));
    }
}
