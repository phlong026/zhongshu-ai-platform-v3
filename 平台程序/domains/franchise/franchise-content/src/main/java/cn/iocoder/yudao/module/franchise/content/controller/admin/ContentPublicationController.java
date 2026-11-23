package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentGrowthService;
import cn.iocoder.yudao.module.franchise.content.content.ContentPublicationService;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentPublicationDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
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

/** 手动发布登记 + 客资聚合；不负责外部账号授权或自动发帖。 */
@Tag(name = "加盟 - 内容创作（发布/获客）")
@RestController
@RequestMapping("/franchise/content")
@Validated
public class ContentPublicationController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private ContentPublicationService publications;
    @Resource
    private ContentGrowthService growth;
    @Resource
    private cn.iocoder.yudao.module.franchise.content.content.ContentDraftService drafts;
    @Resource
    private cn.iocoder.yudao.module.franchise.content.content.ContentTaskService tasks;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/publications")
    @Operation(summary = "创建手动发布登记（不接受定时发布）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> create(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        PostSnapshot post = post();
        String key = ContentScriptController.requireIdem(idem);
        return success(view(publications.createFromPost(post, body, key)));
    }

    @GetMapping("/publications")
    @Operation(summary = "发布列表（?status=）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        PostSnapshot post = post();
        return success(publications.list(post.orgId(), null, status).stream()
                .map(ContentPublicationController::view).toList());
    }

    @GetMapping("/publications/{id}/playback")
    @Operation(summary = "读取已发布作品关联的原视频任务（同组织可见）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> playback(@PathVariable Long id) {
        PostSnapshot post = post();
        FrContentPublicationDO publication = publications.requirePublishedInCompany(post.orgId(), id);
        if (publication.getWorkId() == null) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "该发布记录未关联原作品");
        }
        FrContentDraftDO work = drafts.workOf(post.orgId(), publication.getWorkId());
        if (!java.util.Objects.equals(work.getAuthorUserId(), publication.getAuthorUserId()) || work.getTaskId() == null) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "发布记录与原作品关联无效");
        }
        FrContentTaskDO task = tasks.requireOwned(post.orgId(), work.getTaskId());
        if (!java.util.Objects.equals(task.getAuthorUserId(), work.getAuthorUserId())
                || !"SUCCEEDED".equals(task.getStatus()) || task.getResultUrl() == null || task.getResultUrl().isBlank()) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "原视频暂不可播放");
        }
        return success(Map.of("publicationId", String.valueOf(id), "workId", String.valueOf(work.getId()),
                "taskId", String.valueOf(task.getId()), "resultUrl", task.getResultUrl()));
    }

    @PostMapping("/publications/{id}/verify")
    @Operation(summary = "人工确认外部发布完成，进入链接核验")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> verify(@PathVariable("id") Long id) {
        PostSnapshot post = post();
        return success(view(publications.verify(post.orgId(), post.userId(), id)));
    }

    @PostMapping("/publications/{id}/link")
    @Operation(summary = "登记人工发布链接（全局唯一 1_114_000_003）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> link(@PathVariable("id") Long id,
                                                  @RequestBody Map<String, Object> body) {
        PostSnapshot post = post();
        return success(view(publications.link(post.orgId(), post.userId(), id,
                body.get("publishedUrl") == null ? null : String.valueOf(body.get("publishedUrl")))));
    }

    @PostMapping("/publications/{id}/fail")
    @Operation(summary = "标记发布失败")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> fail(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        PostSnapshot post = post();
        String reason = body == null || body.get("reason") == null ? "发布失败" : String.valueOf(body.get("reason"));
        return success(view(publications.fail(post.orgId(), post.userId(), id, reason)));
    }

    @GetMapping("/growth/customers/{customerId}/sources")
    @Operation(summary = "客户关联的视频咨询来源（按客户可见范围和客资登记人范围过滤）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> customerContentSources(@PathVariable Long customerId) {
        return success(growth.customerSources(post(), customerId));
    }

    @GetMapping("/growth/summary")
    @Operation(summary = "获客聚合（来源×内容×转化；客资登记复用 /franchise/leads）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> growthSummary() {
        PostSnapshot post = post();
        return success(growth.summary(post.orgId()));
    }

    static Map<String, Object> view(FrContentPublicationDO row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("workId", row.getWorkId());
        view.put("authorUserId", row.getAuthorUserId());
        view.put("title", row.getTitle());
        view.put("publishText", row.getPublishText() == null ? "" : row.getPublishText());
        view.put("tags", row.getTags() == null ? "" : row.getTags());
        view.put("accountId", row.getAccountId());
        view.put("platform", row.getPlatform());
        view.put("status", row.getStatus());
        view.put("scheduledAt", row.getScheduledAt() == null ? "" : row.getScheduledAt().toString());
        view.put("publishedUrl", row.getPublishedUrl() == null ? "" : row.getPublishedUrl());
        view.put("publishedAt", row.getPublishedAt() == null ? "" : row.getPublishedAt().toString());
        view.put("failureReason", row.getFailureReason() == null ? "" : row.getFailureReason());
        view.put("signature", row.getSignature() == null ? "" : row.getSignature());
        view.put("createTime", row.getCreateTime() == null ? "" : row.getCreateTime().toString());
        return view;
    }

}
