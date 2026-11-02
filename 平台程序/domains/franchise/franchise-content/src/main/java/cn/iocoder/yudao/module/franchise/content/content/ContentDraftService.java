package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentCourseProgressDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentCourseProgressMapper;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentDraftMapper;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentVideoDraftDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentVideoDraftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_NOT_FOUND;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.WORK_INVALID;

/**
 * 草稿与作品（§五）：save-work 一任务一件（部分唯一索引兜底，重复返既有——AC9）；
 * 视频草稿一人一份 upsert；课程进度幂等追加。
 */
@Service
public class ContentDraftService {

    private final FrContentDraftMapper drafts;
    private final FrContentVideoDraftMapper videoDrafts;
    private final FrContentCourseProgressMapper progress;
    private final ContentTaskService tasks;
    private final JdbcTemplate jdbcTemplate;

    public ContentDraftService(FrContentDraftMapper drafts, FrContentVideoDraftMapper videoDrafts,
                               FrContentCourseProgressMapper progress, ContentTaskService tasks,
                               JdbcTemplate jdbcTemplate) {
        this.drafts = drafts;
        this.videoDrafts = videoDrafts;
        this.progress = progress;
        this.tasks = tasks;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 任务→作品：SUCCEEDED 任务可转作品；task_kind→draft_kind 映射（digital/ai_video 同名，其余 remix）。 */
    public FrContentDraftDO saveWork(long companyId, long userId, long taskId, String title) {
        FrContentTaskDO task = tasks.requireOwned(companyId, taskId);
        if (!"SUCCEEDED".equals(task.getStatus())) {
            throw exception(WORK_INVALID);
        }
        FrContentDraftDO existing = drafts.selectByTask(taskId);
        if (existing != null) {
            return existing; // 幂等：一任务一件
        }
        String kind = switch (task.getKind()) {
            case "digital" -> "digital";
            case "ai_video" -> "ai_video";
            default -> "remix";
        };
        Map<String, Object> configuration = Map.of(
                "taskId", task.getId(),
                "kind", task.getKind(),
                "engine", task.getEngine() == null ? "" : task.getEngine(),
                "resultUrl", task.getResultUrl() == null ? "" : task.getResultUrl(),
                "result", task.getResultSnapshot() == null ? Map.of()
                        : JsonUtils.parseObject(task.getResultSnapshot(), Map.class));
        long id = IdWorker.getId();
        try {
            jdbcTemplate.update(
                    "INSERT INTO fr_content_draft (id, company_id, author_user_id, task_id, kind, title, script, "
                            + "configuration, status) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'draft')",
                    id, companyId, userId, taskId, kind,
                    title == null || title.isBlank() ? defaultTitle(task) : clip(title, 200),
                    scriptTextOf(task, kind),
                    JsonUtils.toJsonString(configuration));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return drafts.selectByTask(taskId); // 并发兜底：部分唯一索引
        }
        return drafts.selectById(id);
    }

    public FrContentDraftDO workOf(long companyId, long workId) {
        FrContentDraftDO row = drafts.selectById(workId);
        if (row == null || row.getCompanyId() != companyId || Boolean.TRUE.equals(row.getDeleted())) {
            throw exception(WORK_INVALID);
        }
        return row;
    }

    public List<FrContentDraftDO> listWorks(long companyId, long userId) {
        return drafts.selectList(new LambdaQueryWrapper<FrContentDraftDO>()
                .eq(FrContentDraftDO::getCompanyId, companyId)
                .eq(FrContentDraftDO::getAuthorUserId, userId)
                .eq(FrContentDraftDO::getDeleted, false)
                .orderByDesc(FrContentDraftDO::getUpdateTime));
    }

    public FrContentDraftDO updateWorkStatus(long companyId, long workId, String status) {
        if (!"draft".equals(status) && !"saved".equals(status)) {
            throw exception(INPUT_INVALID);
        }
        FrContentDraftDO row = workOf(companyId, workId);
        row.setStatus(status);
        drafts.updateById(row);
        return row;
    }

    // ---------------- 视频草稿（一人一份 upsert） ----------------

    public FrContentVideoDraftDO videoDraft(long companyId, long userId) {
        return videoDrafts.selectOne(new LambdaQueryWrapper<FrContentVideoDraftDO>()
                .eq(FrContentVideoDraftDO::getAuthorUserId, userId).last("LIMIT 1"));
    }

    public FrContentVideoDraftDO saveVideoDraft(long companyId, long userId, Map<String, Object> body) {
        String mode = body.get("mode") == null ? "frames" : String.valueOf(body.get("mode"));
        if (!"frames".equals(mode) && !"reference".equals(mode)) {
            throw exception(INPUT_INVALID);
        }
        FrContentVideoDraftDO existing = videoDraft(companyId, userId);
        long id = existing == null ? IdWorker.getId() : existing.getId();
        String parameters = body.get("parameters") == null ? null : JsonUtils.toJsonString(body.get("parameters"));
        String referenceMedia = body.get("references") == null ? null : JsonUtils.toJsonString(body.get("references"));
        jdbcTemplate.update(
                "INSERT INTO fr_content_video_draft (id, company_id, author_user_id, mode, description, "
                        + "first_frame_asset_id, last_frame_asset_id, parameters, reference_media) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb)) "
                        + "ON CONFLICT (author_user_id) DO UPDATE SET mode = EXCLUDED.mode, "
                        + "description = EXCLUDED.description, first_frame_asset_id = EXCLUDED.first_frame_asset_id, "
                        + "last_frame_asset_id = EXCLUDED.last_frame_asset_id, parameters = EXCLUDED.parameters, "
                        + "reference_media = EXCLUDED.reference_media, update_time = now()",
                id, companyId, userId, mode,
                body.get("description") == null ? null : String.valueOf(body.get("description")),
                longOrNull(body.get("firstFrameAssetId")), longOrNull(body.get("lastFrameAssetId")),
                parameters, referenceMedia);
        return videoDraft(companyId, userId);
    }

    // ---------------- 课程进度 ----------------

    public void completeChapter(long companyId, long userId, String courseId, int chapterIndex) {
        if (courseId == null || courseId.isBlank() || chapterIndex < 0) {
            throw exception(INPUT_INVALID);
        }
        FrContentCourseProgressDO existing = progress.selectOne(
                new LambdaQueryWrapper<FrContentCourseProgressDO>()
                        .eq(FrContentCourseProgressDO::getAuthorUserId, userId)
                        .eq(FrContentCourseProgressDO::getCourseId, courseId)
                        .eq(FrContentCourseProgressDO::getChapterIndex, chapterIndex)
                        .last("LIMIT 1"));
        if (existing != null) {
            return; // 幂等
        }
        progress.insert(new FrContentCourseProgressDO()
                .setId(IdWorker.getId())
                .setCompanyId(companyId)
                .setAuthorUserId(userId)
                .setCourseId(courseId)
                .setChapterIndex(chapterIndex)
                .setCompletedAt(OffsetDateTime.now()));
    }

    public List<FrContentCourseProgressDO> courseProgress(long companyId, long userId, String courseId) {
        return progress.selectList(new LambdaQueryWrapper<FrContentCourseProgressDO>()
                .eq(FrContentCourseProgressDO::getCompanyId, companyId)
                .eq(FrContentCourseProgressDO::getAuthorUserId, userId)
                .eq(courseId != null, FrContentCourseProgressDO::getCourseId, courseId)
                .orderByAsc(FrContentCourseProgressDO::getChapterIndex));
    }

    // ---------------- 工具 ----------------

    private static String defaultTitle(FrContentTaskDO task) {
        return switch (task.getKind()) {
            case "digital" -> "数字人口播作品";
            case "ai_video" -> "AI 视频作品";
            default -> "口播文案作品";
        };
    }

    private static String scriptTextOf(FrContentTaskDO task, String draftKind) {
        if ("remix".equals(draftKind) && task.getResultSnapshot() != null) {
            Object text = JsonUtils.parseObject(task.getResultSnapshot(), Map.class).get("text");
            return text == null ? null : String.valueOf(text);
        }
        return null;
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Long longOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

}
