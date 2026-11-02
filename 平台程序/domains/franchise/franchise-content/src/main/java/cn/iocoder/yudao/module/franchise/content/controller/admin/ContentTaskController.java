package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentDraftService;
import cn.iocoder.yudao.module.franchise.content.content.ContentTaskService;
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
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

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
    private ContentDraftService drafts;
    @Resource
    private FrContentTaskMapper taskMapper;

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
        return success(rows.stream().map(ContentTaskController::viewOf).toList());
    }

    @GetMapping("{id}")
    @Operation(summary = "任务详情（轮询；七态 + result 快照）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> detail(@PathVariable("id") Long id) {
        PostSnapshot post = post();
        return success(viewOf(tasks.requireOwned(post.orgId(), id)));
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

    static Map<String, Object> viewOf(FrContentTaskDO task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("kind", task.getKind());
        view.put("status", task.getStatus());
        view.put("provider", task.getProvider() == null ? "" : task.getProvider());
        view.put("providerTaskId", task.getProviderTaskId() == null ? "" : task.getProviderTaskId());
        view.put("engine", task.getEngine() == null ? "" : task.getEngine());
        view.put("model", task.getModel() == null ? "" : task.getModel());
        view.put("resultUrl", task.getResultUrl() == null ? "" : task.getResultUrl());
        view.put("failedReason", task.getFailedReason() == null ? "" : task.getFailedReason());
        view.put("result", task.getResultSnapshot() == null ? Map.of()
                : JsonUtils.parseObject(task.getResultSnapshot(), Map.class));
        return view;
    }

}
