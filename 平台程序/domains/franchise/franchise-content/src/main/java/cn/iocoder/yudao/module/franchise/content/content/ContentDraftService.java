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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        if (!"SUCCEEDED".equals(task.getStatus()) || (java.util.Set.of("copy","asr","link_parse").contains(task.getKind()) && !"READY".equals(task.getResultState()))) {
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
                    title == null || title.isBlank() ? ContentTaskTitle.forViewer(task, companyId, userId) : clip(title, 200),
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
        List<FrContentDraftDO> rows = drafts.selectList(new LambdaQueryWrapper<FrContentDraftDO>()
                .eq(FrContentDraftDO::getCompanyId, companyId)
                .eq(FrContentDraftDO::getAuthorUserId, userId)
                .eq(FrContentDraftDO::getDeleted, false)
                .orderByDesc(FrContentDraftDO::getUpdateTime));
        List<FrContentDraftDO> recoverable = rows.stream()
                .filter(row -> blankTitle(row) || legacyDefaultTitle(row))
                .toList();
        List<Long> taskIds = recoverable.stream().map(FrContentDraftDO::getTaskId).filter(Objects::nonNull).distinct().toList();
        Map<Long, WorkTitle> titles = new HashMap<>();
        if (!taskIds.isEmpty()) {
            // Match both author and organization before reading any input for a legacy title.
            new NamedParameterJdbcTemplate(jdbcTemplate).query(
                    "SELECT id, kind, input_snapshot FROM fr_content_task "
                            + "WHERE company_id = :companyId AND author_user_id = :userId AND id IN (:taskIds)",
                    Map.of("companyId", companyId, "userId", userId, "taskIds", taskIds), row -> {
                        String kind = row.getString("kind");
                        String title = ContentTaskTitle.fromSnapshot(kind, row.getString("input_snapshot"));
                        titles.put(row.getLong("id"), new WorkTitle(title, !ContentTaskTitle.untitled(kind).equals(title)));
                    });
        }
        for (FrContentDraftDO row : recoverable) {
            WorkTitle candidate = titles.get(row.getTaskId());
            if (blankTitle(row)) {
                row.setTitle(candidate == null ? ContentTaskTitle.untitled(row.getKind()) : candidate.title());
            } else if (candidate != null && candidate.hasTheme()) {
                row.setTitle(candidate.title());
            }
        }
        return rows;
    }

    private record WorkTitle(String title, boolean hasTheme) {}

    private static boolean blankTitle(FrContentDraftDO row) {
        return row.getTitle() == null || row.getTitle().isBlank();
    }

    private static boolean legacyDefaultTitle(FrContentDraftDO row) {
        return switch (row.getKind() == null ? "" : row.getKind()) {
            case "digital" -> "数字人口播作品".equals(row.getTitle());
            case "ai_video" -> "AI 视频作品".equals(row.getTitle());
            case "remix" -> "口播文案作品".equals(row.getTitle());
            default -> false;
        };
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

    public FrContentVideoDraftDO videoDraft(long companyId, long userId) { return videoDraft(companyId,userId,"new"); }

    public FrContentVideoDraftDO videoDraft(long companyId, long userId, String key) {
        checkVideoKey(companyId,userId,key);
        return videoDrafts.selectOne(new LambdaQueryWrapper<FrContentVideoDraftDO>()
                .eq(FrContentVideoDraftDO::getCompanyId,companyId).eq(FrContentVideoDraftDO::getAuthorUserId,userId)
                .eq(FrContentVideoDraftDO::getDraftKey,key));
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public FrContentVideoDraftDO saveVideoDraft(long companyId, long userId, Map<String, Object> body) {
        String key=String.valueOf(body.getOrDefault("draftKey","new"));
        checkVideoKey(companyId,userId,key);
        Object value=body.get("revision");
        if(!(value instanceof Number n)||n.longValue()<0||n.longValue()>=9_007_199_254_740_990L||n.doubleValue()!=n.longValue())throw exception(INPUT_INVALID);
        long revision=((Number)value).longValue();
        String mode=String.valueOf(body.getOrDefault("mode","frames"));
        if(!java.util.Set.of("frames","reference").contains(mode))throw exception(INPUT_INVALID);
        jdbcTemplate.update("INSERT INTO fr_content_video_draft(id,company_id,author_user_id,draft_key,mode) VALUES(?,?,?,?,'frames') "
                +"ON CONFLICT(company_id,author_user_id,draft_key) DO NOTHING",IdWorker.getId(),companyId,userId,key);
        var existing=videoDraft(companyId,userId,key);
        String parameters=body.containsKey("parameters")?JsonUtils.toJsonString(body.get("parameters")):existing.getParameters();
        String refs=body.containsKey("references")?JsonUtils.toJsonString(body.get("references")):existing.getReferenceMedia();
        int changed=jdbcTemplate.update("UPDATE fr_content_video_draft SET mode=?,description=?,first_frame_asset_id=?,last_frame_asset_id=?, "
                +"parameters=CAST(? AS jsonb),reference_media=CAST(? AS jsonb),revision=revision+1,update_time=now() "
                +"WHERE company_id=? AND author_user_id=? AND draft_key=? AND revision=?",mode,body.get("description"),
                body.containsKey("firstFrameAssetId")?longOrNull(body.get("firstFrameAssetId")):existing.getFirstFrameAssetId(),
                body.containsKey("lastFrameAssetId")?longOrNull(body.get("lastFrameAssetId")):existing.getLastFrameAssetId(),
                parameters,refs,companyId,userId,key,revision);
        if(changed!=1)throw exception(cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.VIDEO_DRAFT_CONFLICT);
        return videoDraft(companyId,userId,key);
    }

    /** Editable input is a projection, never the internal authorization/provider snapshot. */
    public Map<String,Object> videoTaskInput(long companyId,long userId,long taskId) {
        var task=checkVideoKey(companyId,userId,"task:"+taskId);
        Map<String,Object> input=JsonUtils.parseObject(task.getInputSnapshot(),Map.class);
        if(input==null||!(input.get("description") instanceof String description)
                ||!(input.get("resolution") instanceof String resolution)||!(input.get("duration") instanceof Number duration)
                ||(input.get("ratio")!=null&&!(input.get("ratio") instanceof String)))throw exception(INPUT_INVALID);
        var view=new java.util.LinkedHashMap<String,Object>();
        view.put("taskId",String.valueOf(task.getId()));
        view.put("kind","ai_video");
        view.put("description",description);
        view.put("parameters",Map.of("resolution",resolution,"duration",duration,"ratio",input.get("ratio")==null?"16:9":input.get("ratio")));
        String first="",last="";
        var references=new java.util.ArrayList<Map<String,Object>>();
        Object raw=input.get("references");
        if(raw!=null&&!(raw instanceof List<?>))throw exception(INPUT_INVALID);
        if(raw instanceof List<?> refs)for(Object value:refs) {
            if(!(value instanceof Map<?,?> ref)||!(ref.get("role") instanceof String role)
                    ||!java.util.Set.of("first_frame","last_frame","reference_image","reference_video","reference_audio").contains(role))throw exception(INPUT_INVALID);
            String assetId;
            try {
                long id=Long.parseLong(String.valueOf(ref.get("assetId")));
                if(id<=0)throw new NumberFormatException();
                assetId=Long.toString(id);
            } catch(NumberFormatException e){throw exception(INPUT_INVALID);}
            if("first_frame".equals(role)){if(!first.isEmpty())throw exception(INPUT_INVALID);first=assetId;}
            else if("last_frame".equals(role)){if(!last.isEmpty())throw exception(INPUT_INVALID);last=assetId;}
            else references.add(Map.of("assetId",assetId,"role",role,"name",ref.get("name") instanceof String name?name:""));
        }
        // The editor has two modes. Reject unsupported historical mixtures instead of silently omitting media.
        if(!references.isEmpty()&&(!first.isEmpty()||!last.isEmpty()))throw exception(INPUT_INVALID);
        view.put("mode",references.isEmpty()?"frames":"reference");
        view.put("firstFrameAssetId",first);
        view.put("lastFrameAssetId",last);
        view.put("references",references);
        return view;
    }

    private FrContentTaskDO checkVideoKey(long companyId,long userId,String key) {
        if("new".equals(key))return null;
        if(key==null||!key.matches("task:[1-9][0-9]{0,18}"))throw exception(INPUT_INVALID);
        try {
            var task=tasks.requireOwned(companyId,Long.parseLong(key.substring(5)));
            if(task.getAuthorUserId()!=userId||!"ai_video".equals(task.getKind()))throw exception(TASK_NOT_FOUND);
            return task;
        } catch(NumberFormatException e){throw exception(INPUT_INVALID);}
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
