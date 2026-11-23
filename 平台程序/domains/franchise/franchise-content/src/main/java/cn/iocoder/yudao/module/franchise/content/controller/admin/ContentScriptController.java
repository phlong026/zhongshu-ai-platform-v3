package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentScriptService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskTitle;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentScriptDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentScriptMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "加盟 - 内容创作（文案链）")
@RestController
@RequestMapping("/franchise/content")
@Validated
public class ContentScriptController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private ContentScriptService scripts;
    @Resource
    private FrContentScriptMapper scriptMapper;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/links/parse")
    @Operation(summary = "分享链接解析（豆一豆同款接口，任务化）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> parseLink(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        PostSnapshot post = post();
        String key = requireIdem(idem);
        var task = scripts.parseLink(post, String.valueOf(body.get("url")), key);
        return success(Map.of("taskId", task.getId(), "status", task.getStatus(),
                "title", ContentTaskTitle.forViewer(task, post.orgId(), post.userId())));
    }

    @PostMapping("/scripts/extract")
    @Operation(summary = "文案提取（ASR，同素材 sha256 命中缓存零外呼）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> extract(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        PostSnapshot post = post();
        String key = requireIdem(idem);
        var task = scripts.extract(post,
                longOrNull(body.get("assetId")),
                body.get("sourceUrl") == null ? null : String.valueOf(body.get("sourceUrl")),
                doubleOrNull(body.get("durationSec")),
                body.get("sha256") == null ? null : String.valueOf(body.get("sha256")), key);
        return success(Map.of("taskId", task.getId(), "status", task.getStatus(),
                "title", ContentTaskTitle.forViewer(task, post.orgId(), post.userId())));
    }

    @PostMapping("/scripts/optimize")
    @Operation(summary = "口播文案改写（DeepSeek 同款，rules 兜底 engine 标注）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> optimize(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        PostSnapshot post = post();
        String key = requireIdem(idem);
        var task = scripts.optimize(post, body, key);
        return success(Map.of("taskId", task.getId(), "status", task.getStatus(),
                "title", ContentTaskTitle.forViewer(task, post.orgId(), post.userId())));
    }

    @GetMapping("/scripts")
    @Operation(summary = "文案历史列表")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> listScripts(@RequestParam(required = false) Long taskId) {
        PostSnapshot post = post();
        List<FrContentScriptDO> rows = scriptMapper.selectList(new LambdaQueryWrapper<FrContentScriptDO>()
                .eq(FrContentScriptDO::getCompanyId, post.orgId())
                .eq(FrContentScriptDO::getAuthorUserId, post.userId())
                .eq(taskId != null, FrContentScriptDO::getTaskId, taskId)
                .orderByDesc(FrContentScriptDO::getCreateTime)
                .last("LIMIT 100"));
        return success(rows.stream().map(row -> Map.<String, Object>of(
                "id", row.getId(),
                "taskId", row.getTaskId() == null ? 0L : row.getTaskId(),
                "sourceType", row.getSourceType(),
                "platform", row.getPlatform() == null ? "" : row.getPlatform(),
                "rawText", row.getRawText(),
                "optimizedText", row.getOptimizedText() == null ? "" : row.getOptimizedText(),
                "engine", row.getEngine() == null ? "" : row.getEngine(),
                "model", row.getModel() == null ? "" : row.getModel(),
                "durationSeconds", row.getDurationSeconds() == null ? 0 : row.getDurationSeconds())).toList());
    }

    @GetMapping("/scripts/{id}")
    @Operation(summary = "文案详情（含 engine/model/prompt 快照）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> script(@PathVariable("id") Long id) {
        PostSnapshot post = post();
        FrContentScriptDO row = scriptMapper.selectById(id);
        if (row == null || row.getCompanyId() != post.orgId()) {
            throw exception(cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_NOT_FOUND);
        }
        return success(Map.of(
                "id", row.getId(),
                "taskId", row.getTaskId() == null ? 0L : row.getTaskId(),
                "sourceType", row.getSourceType(),
                "platform", row.getPlatform() == null ? "" : row.getPlatform(),
                "rawText", row.getRawText(),
                "optimizedText", row.getOptimizedText() == null ? "" : row.getOptimizedText(),
                "engine", row.getEngine() == null ? "" : row.getEngine(),
                "model", row.getModel() == null ? "" : row.getModel(),
                "durationSeconds", row.getDurationSeconds() == null ? 0 : row.getDurationSeconds()));
    }

    static String requireIdem(String idem) {
        if (idem == null || idem.isBlank()) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, "缺少 Idempotency-Key 请求头"));
        }
        return FranchiseCommandKeys.normalize(idem);
    }

    static Long longOrNull(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : Long.parseLong(String.valueOf(value));
    }

    static Double doubleOrNull(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : Double.parseDouble(String.valueOf(value));
    }

}
