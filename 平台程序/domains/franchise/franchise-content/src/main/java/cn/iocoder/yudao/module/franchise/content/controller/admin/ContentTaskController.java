package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentDraftService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskResultService;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskMapper;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;

@Tag(name = "加盟 - 内容创作（任务）")
@RestController
@RequestMapping("/franchise/content/tasks")
@Validated
public class ContentTaskController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private ContentTaskService tasks;
    @Resource
    private ContentTaskResultService results;
    @Resource
    private ContentDraftService drafts;
    @Resource
    private FrContentTaskMapper taskMapper;
    @Resource
    private cn.iocoder.yudao.module.franchise.content.provider.VideoGenerationPort video;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping
    @Operation(summary = "创建生成任务（kind=digital|ai_video；fake 模式走 fake 通道）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> create(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        PostSnapshot post = post();
        String key = ContentScriptController.requireIdem(idem);
        FrContentTaskDO task = tasks.createFromPost(post, body, key);
        return success(viewOf(task));
    }

    @GetMapping
    @Operation(summary = "任务列表（?status= 过滤）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        PostSnapshot post = post();
        List<FrContentTaskDO> rows = taskMapper.selectList(new LambdaQueryWrapper<FrContentTaskDO>()
                .eq(FrContentTaskDO::getCompanyId, post.orgId())
                .eq(FrContentTaskDO::getAuthorUserId, post.userId())
                .eq(status != null && !status.isBlank(), FrContentTaskDO::getStatus, status)
                .orderByDesc(FrContentTaskDO::getCreateTime)
                .last("LIMIT 100"));
        List<Long> hidden = jdbcTemplate.queryForList(
                "SELECT task_id FROM fr_content_task_hidden WHERE company_id = ? AND author_user_id = ?",
                Long.class, post.orgId(), post.userId());
        if (!hidden.isEmpty()) rows.removeIf(row -> hidden.contains(row.getId()));
        return success(rows.stream().map(ContentTaskController::viewOf).toList());
    }

    @PostMapping("{id}/dismiss")
    @Operation(summary = "从本人任务中心移除任务（不删除任务、不影响生成与积分结算）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Boolean> dismiss(@PathVariable("id") Long id) {
        PostSnapshot post = post();
        // Ownership is checked against the authoritative task row before recording the personal hide action.
        tasks.requireOwned(post.orgId(), id);
        jdbcTemplate.update(
                "INSERT INTO fr_content_task_hidden (company_id, author_user_id, task_id, actor_post_id) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (company_id, author_user_id, task_id) DO NOTHING",
                post.orgId(), post.userId(), id, post.postId());
        return success(Boolean.TRUE);
    }

    @GetMapping("{id}")
    @Operation(summary = "任务详情（轮询；七态 + result 快照）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> detail(@PathVariable("id") Long id) {
        PostSnapshot post = post();
        return success(viewOf(tasks.requireOwned(post.orgId(), id)));
    }

    @GetMapping("{id}/editable-input")
    @Operation(summary = "读取本人 AI 视频任务的可编辑输入副本")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String,Object>> editableInput(@PathVariable("id") Long id) {
        PostSnapshot post=post();
        return success(drafts.videoTaskInput(post.orgId(),post.userId(),id));
    }

    @PostMapping("{id}/manual-verification")
    @Operation(summary = "人工核验 UNKNOWN 任务是否已交付（结论与积分终态幂等）")
    @PreAuthorize("@post.has('" + ContentPermissions.VERIFY + "')")
    public CommonResult<Map<String, Object>> manualVerification(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        Long actualUnits = parseQuantity(body.get("actualQuantity"));
        FrContentTaskDO task = tasks.manualVerify(post(), id,
                body.get("decision") == null ? null : String.valueOf(body.get("decision")),
                actualUnits,
                body.get("reason") == null ? null : String.valueOf(body.get("reason")),
                body.get("evidenceSummary") == null ? null : String.valueOf(body.get("evidenceSummary")),
                ContentScriptController.requireIdem(idem));
        return success(viewOf(task));
    }

    @GetMapping("{id}/manual-verification")
    @Operation(summary = "查看 UNKNOWN 人工核验审计（仅核验岗）")
    @PreAuthorize("@post.has('" + ContentPermissions.VERIFY + "')")
    public CommonResult<Map<String, Object>> manualVerificationAudit(@PathVariable("id") Long id) {
        FrContentTaskDO task = tasks.requireVerificationAudit(post(), id);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("taskId", task.getId());
        audit.put("status", task.getStatus());
        audit.put("decision", task.getManualVerifyDecision() == null ? "" : task.getManualVerifyDecision());
        audit.put("actualQuantity", task.getManualVerifyActualUnits());
        audit.put("reason", task.getManualVerifyReason() == null ? "" : task.getManualVerifyReason());
        audit.put("evidenceSummary", task.getManualVerifyEvidence() == null ? "" : task.getManualVerifyEvidence());
        audit.put("operatorUserId", task.getManualVerifyOperatorUserId());
        audit.put("operatorPostId", task.getManualVerifyOperatorPostId());
        audit.put("verifiedAt", task.getManualVerifiedAt());
        return success(audit);
    }

    private static Long parseQuantity(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw exception(INPUT_INVALID);
        }
    }

    @PostMapping("{id}/retry-save")
    @Operation(summary = "重试已生成文案的本地保存（原任务、不转写、不扣点）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> retrySave(@PathVariable("id") Long id) {
        PostSnapshot post=post();
        return success(viewOf(results.retrySave(post.orgId(),post.userId(),id)));
    }

    @PostMapping("{id}/save-work")
    @Operation(summary = "任务→作品（一任务一件，重复返既有）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> saveWork(@PathVariable("id") Long id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        PostSnapshot post = post();
        String title = body == null || body.get("title") == null ? null : String.valueOf(body.get("title"));
        FrContentDraftDO work = drafts.saveWork(post.orgId(), post.userId(), id, title);
        return success(Map.of("workId", work.getId(), "kind", work.getKind(), "status", work.getStatus()));
    }

    @GetMapping("{id}/result-url-status")
    @Operation(summary = "成片直链 HEAD 过期探测（A2.3：403/404/410=已失效；网络=unknown 不误报）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> resultUrlStatus(@PathVariable("id") Long id) {
        PostSnapshot post = post();
        FrContentTaskDO task = tasks.requireOwned(post.orgId(), id);
        if (task.getResultUrl() == null || task.getResultUrl().isBlank()) {
            return success(Map.of("expired", false, "message", ""));
        }
        Boolean expired = video.probeUrlExpired(task.getResultUrl(),
                new cn.iocoder.yudao.module.franchise.content.provider.ProviderCallAudit(post.orgId(), task.getId()));
        if (Boolean.TRUE.equals(expired)) {
            return success(Map.of("expired", true, "message",
                    "成片文件已失效：生成服务提供的下载链接已过期，无法再预览、下载或保存。如需帮助，请联系客服并提供任务编号。"));
        }
        return success(Map.of("expired", false, "message", ""));
    }

    static Map<String, Object> viewOf(FrContentTaskDO task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("kind", task.getKind());
        // Media is usable as soon as link_parse succeeds, even while its optional caption is saving.
        boolean needsScript = java.util.Set.of("copy","asr").contains(task.getKind());
        boolean pending = needsScript && "SUCCEEDED".equals(task.getStatus()) && !"READY".equals(task.getResultState());
        view.put("status", pending ? ("FAILED".equals(task.getResultState()) ? "FAILED" : "ARCHIVING") : task.getStatus());
        view.put("generationStatus", task.getStatus());
        view.put("saveRetryable", pending && "FAILED".equals(task.getResultState()));
        view.put("resultState", task.getResultState()==null?"PENDING":task.getResultState());
        view.put("statusText", pending ? ("FAILED".equals(task.getResultState())?"生成完成，保存失败":"生成完成，保存中") : "");
        view.put("provider", task.getProvider() == null ? "" : task.getProvider());
        view.put("providerTaskId", task.getProviderTaskId() == null ? "" : task.getProviderTaskId());
        view.put("engine", task.getEngine() == null ? "" : task.getEngine());
        view.put("model", task.getModel() == null ? "" : task.getModel());
        view.put("resultUrl", task.getResultUrl() == null ? "" : task.getResultUrl());
        view.put("failedReason", pending && task.getResultError()!=null ? task.getResultError() : task.getFailedReason() == null ? "" : task.getFailedReason());
        view.put("result", task.getResultSnapshot() == null ? Map.of()
                : JsonUtils.parseObject(task.getResultSnapshot(), Map.class));
        return view;
    }

}
