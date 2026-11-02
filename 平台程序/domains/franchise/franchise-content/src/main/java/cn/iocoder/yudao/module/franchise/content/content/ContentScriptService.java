package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentScriptDO;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentScriptMapper;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskMapper;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.DeepSeekChatClient;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_NOT_FOUND;

/**
 * 口播文案编排（D-1.5-2/3/4）：解析→提取→改写三入口都任务化（走 ContentTaskService），
 * 并把产物落 fr_content_script（含 engine/model/prompt 快照——产物可审计）。
 * 共享转写缓存：同素材 sha256 命中已成功 ASR 任务即复用 raw_text（只付一次，AC4）。
 */
@Service
public class ContentScriptService {

    private final ContentTaskService tasks;
    private final FrContentScriptMapper scripts;
    private final FrContentTaskMapper taskMapper;
    private final ContentPersonaService personas;
    private final JdbcTemplate jdbcTemplate;
    private final FranchiseCommands commands;

    public ContentScriptService(ContentTaskService tasks, FrContentScriptMapper scripts,
                                FrContentTaskMapper taskMapper, ContentPersonaService personas,
                                JdbcTemplate jdbcTemplate, FranchiseCommands commands) {
        this.tasks = tasks;
        this.scripts = scripts;
        this.taskMapper = taskMapper;
        this.personas = personas;
        this.jdbcTemplate = jdbcTemplate;
        this.commands = commands;
    }

    // ---------------- 三入口（幂等键重放；任务由 sweep 在事务外提交外呼） ----------------

    /** POST /content/links/parse：分享链接解析任务。 */
    @Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO parseLink(PostSnapshot post, String url, String idempotencyKey) {
        // LinkedHashMap 固定插入序：Map.of 跨 JVM 迭代顺序随机（SALT），同键重放哈希会漂移
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "link-parse");
        input.put("url", url == null ? "" : url.trim());
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return tasks.requireOwned(post.orgId(), replay.get().getAggregateId());
        }
        FrContentTaskDO task = tasks.createLinkParse(post.orgId(), post.userId(), url);
        commands.record(post, "fr_content_task", task.getId(), 1, "content-link-parse", input, idempotencyKey);
        return task;
    }

    /** POST /content/scripts/extract：素材/解析结果 → ASR 提取 raw_text（同 sha 命中缓存零外呼）。 */
    @Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO extract(PostSnapshot post, Long assetId, String sourceUrl, Double durationSec,
                                   String mediaSha256, String idempotencyKey) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "asr-extract");
        input.put("assetId", assetId);
        input.put("sourceUrl", sourceUrl);
        input.put("sha256", mediaSha256);
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return tasks.requireOwned(post.orgId(), replay.get().getAggregateId());
        }
        FrContentTaskDO task = extract(post.orgId(), post.userId(), assetId, sourceUrl, durationSec, mediaSha256);
        commands.record(post, "fr_content_task", task.getId(), 1, "content-asr-extract", input, idempotencyKey);
        return task;
    }

    FrContentTaskDO extract(long companyId, long userId, Long assetId, String sourceUrl,
                            Double durationSec, String mediaSha256) {
        if ((assetId == null && (sourceUrl == null || sourceUrl.isBlank()))
                || (assetId != null && (sourceUrl != null && !sourceUrl.isBlank()))) {
            throw exception(INPUT_INVALID); // assetId 与 sourceUrl 二选一
        }
        FrContentTaskDO cached = mediaSha256 == null ? null
                : taskMapper.selectSucceededBySha(companyId, "asr", mediaSha256);
        return tasks.createAsr(companyId, userId, assetId, sourceUrl, mediaSha256, durationSec, cached);
    }

    /** POST /content/scripts/optimize：原文+requirement+personaId → 改写任务（rules 兜底在任务机内）。 */
    @Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO optimize(PostSnapshot post, Map<String, Object> body, String idempotencyKey) {
        String sourceText = text(body.get("sourceText"));
        String requirement = body.get("requirement") == null ? null : String.valueOf(body.get("requirement"));
        Long personaId = body.get("personaId") == null || String.valueOf(body.get("personaId")).isBlank()
                ? null : Long.parseLong(String.valueOf(body.get("personaId")));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "copy-optimize");
        input.put("sourceText", sourceText);
        input.put("requirement", requirement);
        input.put("personaId", personaId);
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return tasks.requireOwned(post.orgId(), replay.get().getAggregateId());
        }
        FrContentTaskDO task = optimize(post.orgId(), post.userId(), sourceText, requirement, personaId,
                text(body.get("sourceType")), text(body.get("sourceUrl")), text(body.get("platform")));
        commands.record(post, "fr_content_task", task.getId(), 1, "content-copy-optimize", input, idempotencyKey);
        return task;
    }

    FrContentTaskDO optimize(long companyId, long userId, String sourceText, String requirement,
                             Long personaId, String sourceType, String sourceUrl, String platform) {
        ContentPersonaService.PersonaSnapshot persona = personas.snapshotOf(companyId, personaId);
        FrContentTaskDO task = tasks.createCopy(companyId, userId, sourceText, requirement, personaId,
                persona == null ? null : persona.promptJson());
        // 脚本行先落 raw（产物行随任务完成由 sweep 落 optimized——见 ContentTaskJob）
        insertScript(task.getId(), companyId, userId, sourceType, sourceUrl, platform, sourceText,
                requirement, personaId, persona == null ? null : persona.snapshotJson(),
                promptSnapshotJson(requirement, persona));
        return task;
    }

    // ---------------- 任务完成后的脚本落库（job 回调） ----------------

    /** copy 任务成功后补写 optimized_text/engine/model。 */
    public void recordOptimizeResult(FrContentTaskDO task, String optimizedText,
                                     String engine, String model) {
        jdbcTemplate.update(
                "UPDATE fr_content_script SET optimized_text = ?, engine = ?, model = ?, duration_seconds = ?, "
                        + "update_time = now() WHERE company_id = ? AND task_id = ?",
                optimizedText, engine, model,
                ContentBillingCatalog.oralSeconds(optimizedText), task.getCompanyId(), task.getId());
    }

    /** ASR/解析任务成功后落脚本行（source_type=link|upload，raw_text=上游文案/转写）。 */
    public void recordExtractedScript(FrContentTaskDO task, String sourceType, String sourceUrl,
                                      String platform, String rawText, Integer durationSeconds) {
        insertScript(task.getId(), task.getCompanyId(), task.getAuthorUserId(), sourceType, sourceUrl,
                platform, rawText, null, null, null, null);
        if (durationSeconds != null) {
            jdbcTemplate.update(
                    "UPDATE fr_content_script SET duration_seconds = ? WHERE company_id = ? AND task_id = ?",
                    durationSeconds, task.getCompanyId(), task.getId());
        }
    }

    private void insertScript(long taskId, long companyId, long userId, String sourceType, String sourceUrl,
                              String platform, String rawText, String requirement, Long personaId,
                              String personaSnapshotJson, String promptSnapshotJson) {
        jdbcTemplate.update(
                "INSERT INTO fr_content_script (id, company_id, author_user_id, task_id, source_type, source_url, "
                        + "platform, raw_text, requirement, persona_id, persona_snapshot, prompt_snapshot) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                IdWorker.getId(), companyId, userId, taskId,
                sourceType == null ? "manual" : sourceType, sourceUrl, platform,
                rawText, requirement, personaId, personaSnapshotJson, promptSnapshotJson);
    }

    /** prompt 快照（D-1.5-2 产物可审计）：system 提示词 + persona 段 + 本次要求。 */
    static String promptSnapshotJson(String requirement, ContentPersonaService.PersonaSnapshot persona) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("system", DeepSeekChatClient.SYSTEM_PROMPT);
        if (persona != null) {
            prompt.put("persona", persona.promptText());
        }
        if (requirement != null && !requirement.isBlank()) {
            prompt.put("requirement", requirement);
        }
        return JsonUtils.toJsonString(prompt);
    }

    public FrContentScriptDTO scriptOfTask(long companyId, long taskId) {
        var rows = scripts.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FrContentScriptDO>()
                .eq(FrContentScriptDO::getCompanyId, companyId)
                .eq(FrContentScriptDO::getTaskId, taskId)
                .last("LIMIT 1"));
        if (rows.isEmpty()) {
            throw exception(TASK_NOT_FOUND);
        }
        return FrContentScriptDTO.of(rows.get(0));
    }

    public record FrContentScriptDTO(Long id, Long taskId, String sourceType, String platform,
                                     String rawText, String optimizedText, String requirement,
                                     Long personaId, String engine, String model, Integer durationSeconds) {
        static FrContentScriptDTO of(FrContentScriptDO row) {
            return new FrContentScriptDTO(row.getId(), row.getTaskId(), row.getSourceType(), row.getPlatform(),
                    row.getRawText(), row.getOptimizedText(), row.getRequirement(), row.getPersonaId(),
                    row.getEngine(), row.getModel(), row.getDurationSeconds());
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String clean = String.valueOf(value).trim();
        return clean.isEmpty() ? null : clean;
    }

}
