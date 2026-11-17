package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentDraftService;
import cn.iocoder.yudao.module.franchise.content.content.CourseAccess;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentCourseProgressDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentVideoDraftDO;
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

@Tag(name = "加盟 - 内容创作（草稿/课程）")
@RestController
@RequestMapping("/franchise/content")
@Validated
public class ContentDraftController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private ContentDraftService drafts;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @GetMapping("/drafts")
    @Operation(summary = "我的作品列表")
    @org.springframework.security.access.prepost.PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> drafts() {
        PostSnapshot post = post();
        return success(drafts.listWorks(post.orgId(), post.userId()).stream()
                .map(row -> Map.<String, Object>of(
                        "id", row.getId(),
                        "taskId", row.getTaskId() == null ? 0L : row.getTaskId(),
                        "kind", row.getKind(),
                        "title", row.getTitle(),
                        "status", row.getStatus()))
                .toList());
    }

    @PutMapping("/drafts/{id}/status")
    @Operation(summary = "作品状态（draft|saved）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> updateDraftStatus(@PathVariable("id") Long id,
                                                               @RequestBody Map<String, Object> body) {
        PostSnapshot post = post();
        FrContentDraftDO row = drafts.updateWorkStatus(post.orgId(), id, String.valueOf(body.get("status")));
        return success(Map.of("id", row.getId(), "status", row.getStatus()));
    }

    @GetMapping("/video-draft")
    @Operation(summary = "视频草稿（一人一份）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> videoDraft(@RequestParam(value="draftKey",defaultValue="new") String draftKey) {
        PostSnapshot post = post();
        FrContentVideoDraftDO row = drafts.videoDraft(post.orgId(), post.userId(),draftKey);
        if (row == null) {
            return success(Map.of("exists", false,"revision",0L));
        }
        return success(Map.of(
                "exists", true,
                "revision",row.getRevision(),
                "draftKey",row.getDraftKey(),
                "mode", row.getMode(),
                "description", row.getDescription() == null ? "" : row.getDescription(),
                "firstFrameAssetId", row.getFirstFrameAssetId() == null ? 0L : row.getFirstFrameAssetId(),
                "lastFrameAssetId", row.getLastFrameAssetId() == null ? 0L : row.getLastFrameAssetId(),
                "parameters", row.getParameters() == null ? Map.of()
                        : cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(row.getParameters(), Map.class),
                "references", row.getReferenceMedia() == null ? Map.of()
                        : cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(row.getReferenceMedia(), Object.class)));
    }

    @PutMapping("/video-draft")
    @Operation(summary = "保存视频草稿（upsert）")
    @PreAuthorize("@post.has('" + ContentPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> saveVideoDraft(@RequestBody Map<String, Object> body) {
        PostSnapshot post = post();
        FrContentVideoDraftDO row = drafts.saveVideoDraft(post.orgId(), post.userId(), body);
        return success(Map.of("id", row.getId(), "mode", row.getMode(),"revision",row.getRevision()));
    }

    @PostMapping("/course-progress")
    @Operation(summary = "课程章节完成（幂等）")
    @PreAuthorize("@post.has('" + ContentPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> completeChapter(@RequestBody Map<String, Object> body) {
        PostSnapshot post = post();
        CourseAccess.requireChapter(post.roleCode(), String.valueOf(body.get("courseId")),
                Integer.parseInt(String.valueOf(body.get("chapterIndex"))));
        drafts.completeChapter(post.orgId(), post.userId(),
                String.valueOf(body.get("courseId")),
                Integer.parseInt(String.valueOf(body.get("chapterIndex"))));
        return success(Map.of("ok", true));
    }

    @GetMapping("/course-progress")
    @Operation(summary = "课程进度（所有有效任职均可查看本人当前组织记录）")
    public CommonResult<List<Map<String, Object>>> courseProgress(@RequestParam(required = false) String courseId) {
        // 学习记录是员工个人数据，不授予内容业务权限；有效任职与本人/org 边界由 CurrentPost 权威提供。
        PostSnapshot post = post();
        if (courseId != null) CourseAccess.require(post.roleCode(), courseId);
        List<FrContentCourseProgressDO> rows = drafts.courseProgress(post.orgId(), post.userId(), courseId);
        return success(rows.stream().filter(row -> CourseAccess.allows(post.roleCode(), row.getCourseId())).map(row -> Map.<String, Object>of(
                "courseId", row.getCourseId(),
                "chapterIndex", row.getChapterIndex(),
                "completedAt", row.getCompletedAt() == null ? "" : row.getCompletedAt().toString())).toList());
    }

}
