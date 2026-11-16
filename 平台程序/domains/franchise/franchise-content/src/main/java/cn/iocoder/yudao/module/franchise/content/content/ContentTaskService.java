package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.franchise.content.content.ContentRulesRewrite.Rewritten;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentTaskDO;
import cn.iocoder.yudao.module.franchise.content.provider.AsrPort;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderProperties;
import cn.iocoder.yudao.module.franchise.content.provider.ContentProviderException;
import cn.iocoder.yudao.module.franchise.content.provider.LinkParsePort;
import cn.iocoder.yudao.module.franchise.content.provider.OralVideoPort;
import cn.iocoder.yudao.module.franchise.content.provider.ProviderCallAudit;
import cn.iocoder.yudao.module.franchise.content.provider.ScriptRewritePort;
import cn.iocoder.yudao.module.franchise.content.provider.VideoGenerationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.CONTENT_PROVIDER_UNAVAILABLE;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_NOT_FOUND;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.CONTENT_FORBIDDEN;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_VERIFICATION_CONFLICT;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_POOL_FACT_MISSING;

/**
 * 七态任务机（D-1.5-7）：QUEUED→SUBMITTING→RUNNING→（ARCHIVING 本批无下载转存故不经）→
 * SUCCEEDED/FAILED/SUBMISSION_UNCERTAIN。五 kind 统一编排；提交/轮询全部由后台 sweep 驱动
 * （事务外外呼 + 单语句 CAS 收口，付费外呼绝不进事务——红线）。
 * 失败三分法（A2.2）：白名单/供应商明确失败→FAILED+释放积分预扣；网络类→SUBMISSION_UNCERTAIN
 * （冻结计费，绝不自动付费重投，UNCERTAIN 带任务号者由 sweep 仅查询对账）；
 * copy 明确未受理/未配置时转本地 rules 免费产物，其他科目未配置→FAILED 中性文案。
 */
@Service
public class ContentTaskService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ContentCloneService clones;

    private static final Logger log = LoggerFactory.getLogger(ContentTaskService.class);
    /** 总轮询窗 2h 超窗转人工（A2.6；provider 层单任务连续轮询 5s×1080≈1.5h 由 client 内部控制）。 */
    public static final int POLL_WINDOW_HOURS = 2;
    /** provider 单次恢复轮询最长约 1.5h，租约覆盖该窗口，完成后主动释放。 */
    static final long POLL_LEASE_SECONDS = 7_200;

    private final ContentTaskStore store;
    private final LinkParsePort linkParse;
    private final AsrPort asr;
    private final ScriptRewritePort rewrite;
    private final OralVideoPort oral;
    private final VideoGenerationPort video;
    private final AssetAccessPort assets;
    private final FranchiseCommands commands;
    private final ContentProviderProperties providerProperties;
    private final OrgPointPort orgPoint;

    public ContentTaskService(ContentTaskStore store, ContentQuotaService ignoredLegacyDailyUsage,
                              LinkParsePort linkParse, AsrPort asr, ScriptRewritePort rewrite,
                              OralVideoPort oral, VideoGenerationPort video,
                              AssetAccessPort assets,
                              FranchiseCommands commands, ContentProviderProperties providerProperties,
                              OrgPointPort orgPoint) {
        this.store = store;
        this.linkParse = linkParse;
        this.asr = asr;
        this.rewrite = rewrite;
        this.oral = oral;
        this.video = video;
        this.assets = assets;
        this.commands = commands;
        this.providerProperties = providerProperties;
        this.orgPoint = orgPoint;
    }

    /** 任务行 provider 列记实际服务通道：fake 模式统一记 fake（§四 provider 枚举含 fake）。 */
    private String channel(String realProvider) {
        return providerProperties.getProvider() == ContentProviderProperties.ProviderMode.FAKE
                ? "fake" : realProvider;
    }

    // ---------------- 幂等入口（POST /content/tasks，控制器直连） ----------------

    /** 统一任务创建：kind=digital|ai_video（link_parse/asr/copy 走 ScriptService 语义入口）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO createFromPost(PostSnapshot post, Map<String, Object> body, String idempotencyKey) {
        String kind = String.valueOf(body.get("kind"));
        Map<String, Object> input = new LinkedHashMap<>(body);
        input.put("command", "task-create");
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return requireOwned(post.orgId(), replay.get().getAggregateId());
        }
        FrContentTaskDO task = switch (kind) {
            case "digital" -> {
                if(clones==null)throw exception(INPUT_INVALID);
                ContentCloneService.DigitalSelection selection;
                try {
                    selection=clones.select(post,Long.parseLong(String.valueOf(body.get("avatarCloneId"))),Long.parseLong(String.valueOf(body.get("voiceCloneId"))));
                } catch(NumberFormatException e) { throw exception(INPUT_INVALID); }
                yield createDigital(post.orgId(),post.userId(),selection.voice(),str(body.get("text")),selection.avatar(),str(body.get("title")),
                        body.get("subtitle")==null?null:castSubtitle(body.get("subtitle")));
            }
            case "ai_video" -> createAiVideo(post.orgId(), post.userId(),
                    str(body.get("description")),
                    body.get("references") == null ? List.of() : castRefs(body.get("references")),
                    str(body.get("resolution")),
                    body.get("duration") == null ? null : Integer.parseInt(String.valueOf(body.get("duration"))),
                    str(body.get("ratio")));
            default -> throw exception(INPUT_INVALID);
        };
        commands.record(post, "fr_content_task", task.getId(), 1, "content-task-create", input, idempotencyKey);
        return task;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSubtitle(Object raw) {
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRefs(Object raw) {
        return (List<Map<String, Object>>) raw;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------- 创建（入口在 ScriptService / 控制器；均在事务外） ----------------

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO createLinkParse(long companyId, long authorUserId, String url) {
        if (url == null || url.isBlank()) {
            throw exception(INPUT_INVALID);
        }
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId,
                ContentBillingCatalog.LINK_RESOLUTION.key(), 1, id);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("url", url.trim());
        return insertWithPoolKey(id, companyId, authorUserId, "link_parse", input, null, poolKey, 1);
    }

    /** 缓存命中时直接 SUCCEEDED：零外呼、零扣费，但仍校验业务权限（AC4）。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO createAsr(long companyId, long authorUserId, Long assetId, String sourceUrl,
                                     String mediaSha256, Double durationSec, FrContentTaskDO cached) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("assetId", assetId);
        input.put("sourceUrl", sourceUrl);
        input.put("durationSec", durationSec);
        if (cached != null) {
            orgPoint.assertSubjectAllowed(companyId, authorUserId, ContentBillingCatalog.ASR.key());
            long id = ContentTaskStore.newId();
            FrContentTaskDO task = store.insert(id, companyId, authorUserId, "asr",
                    JsonUtils.toJsonString(input), mediaSha256);
            store.succeedSync(id, companyId, cached.getProvider(), "cache", null,
                    cached.getResultUrl(), cached.getResultSnapshot());
            task.setStatus("SUCCEEDED").setEngine("cache").setProvider(cached.getProvider())
                    .setResultSnapshot(cached.getResultSnapshot());
            return task;
        }
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, ContentBillingCatalog.ASR.key(), 1, id);
        return insertWithPoolKey(id, companyId, authorUserId, "asr", input, mediaSha256, poolKey, 1);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO createCopy(long companyId, long authorUserId, String sourceText,
                                      String requirement, Long personaId, String personaPromptJson) {
        if (sourceText == null || sourceText.isBlank()) {
            throw exception(INPUT_INVALID);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceText", sourceText);
        input.put("requirement", requirement);
        input.put("personaId", personaId);
        input.put("personaPrompt", personaPromptJson);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, ContentBillingCatalog.COPY.key(), 1, id);
        return insertWithPoolKey(id, companyId, authorUserId, "copy", input, null, poolKey, 1);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO createDigital(long companyId, long authorUserId, String voice, String text,
                                         String avatar, String title, Map<String, Object> subtitle) {
        if (text == null || text.isBlank() || voice == null || voice.isBlank() || avatar == null || avatar.isBlank()) {
            throw exception(INPUT_INVALID);
        }
        int seconds = ContentBillingCatalog.oralSeconds(text);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("voice", voice);
        input.put("text", text);
        input.put("avatar", avatar);
        input.put("title", title == null || title.isBlank() ? "数字人口播" : title);
        input.put("subtitle", subtitle);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, ContentBillingCatalog.DIGITAL.key(), seconds, id);
        return insertWithPoolKey(id, companyId, authorUserId, "digital", input, null, poolKey, seconds);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO createAiVideo(long companyId, long authorUserId, String description,
                                         List<Map<String, Object>> references, String resolution,
                                         Integer durationSeconds, String ratio) {
        if (description == null || description.isBlank() || durationSeconds == null
                || durationSeconds < 4 || durationSeconds > 15) {
            throw exception(INPUT_INVALID);
        }
        String subject = "2K".equals(resolution)
                ? ContentBillingCatalog.AI_VIDEO_2K.key() : ContentBillingCatalog.AI_VIDEO_768P.key();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("description", description);
        input.put("references", references);
        input.put("resolution", "2K".equals(resolution) ? "2K" : "768P");
        input.put("duration", durationSeconds);
        input.put("ratio", ratio);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, subject, durationSeconds, id);
        return insertWithPoolKey(id, companyId, authorUserId, "ai_video", input, null, poolKey, durationSeconds);
    }

    private FrContentTaskDO insert(long id, long companyId, long authorUserId, String kind,
                                   Map<String, Object> input, String sha) {
        return store.insert(id, companyId, authorUserId, kind, JsonUtils.toJsonString(input), sha);
    }

    private String reservePool(long companyId, long authorUserId, String subject, int units, long taskId) {
        String bizKey = "content:" + taskId + ":" + subject;
        orgPoint.reserve(new OrgPointPort.ReserveCommand(
                bizKey, companyId, authorUserId, null, subject, units, "content_task", taskId));
        return bizKey;
    }

    /** 与预扣处于同一业务事务；先插任务再固化键，避免恢复扫描丢失。 */
    private FrContentTaskDO insertWithPoolKey(long id, long companyId, long authorUserId, String kind,
                                              Map<String, Object> input, String sha, String poolKey,
                                              long reservedUnits) {
        FrContentTaskDO task = insert(id, companyId, authorUserId, kind, input, sha);
        store.markPoolReservation(id, companyId, poolKey, reservedUnits);
        task.setPoolBizKey(poolKey).setPoolReservedUnits(reservedUnits);
        return task;
    }

    public FrContentTaskDO requireOwned(long companyId, long taskId) {
        FrContentTaskDO task = store.byId(companyId, taskId);
        if (task == null) {
            throw exception(TASK_NOT_FOUND);
        }
        return task;
    }

    /** 人工核验审计仅限核验岗，并始终按当前组织隔离。 */
    public FrContentTaskDO requireVerificationAudit(PostSnapshot post, long taskId) {
        requireVerifier(post);
        return requireOwned(post.orgId(), taskId);
    }

    /**
     * 无供应商任务号的 UNKNOWN 只能由组织负责人人工闭环。
     * 任务 CAS、核验审计字段与积分终态在同一 Spring 事务内；端口异常必须向上抛出以回滚任务结论。
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public FrContentTaskDO manualVerify(PostSnapshot post, long taskId, String decision, Long actualUnits,
                                        String reason, String evidence, String idempotencyKey) {
        requireVerifier(post);
        String normalizedDecision = decision == null ? "" : decision.trim().toUpperCase();
        String normalizedReason = requiredText(reason, 500);
        String normalizedEvidence = requiredText(evidence, 1000);
        String normalizedKey = requiredText(idempotencyKey, 80);
        if (!("DELIVERED".equals(normalizedDecision) || "NOT_DELIVERED".equals(normalizedDecision))
                || ("DELIVERED".equals(normalizedDecision) && (actualUnits == null || actualUnits <= 0))
                || ("NOT_DELIVERED".equals(normalizedDecision) && actualUnits != null)) {
            throw exception(INPUT_INVALID);
        }
        FrContentTaskDO replay = store.byManualVerifyKey(post.orgId(), normalizedKey);
        if (replay != null) {
            if (sameManualVerification(replay, taskId, normalizedDecision, actualUnits,
                    normalizedReason, normalizedEvidence)) {
                return replay;
            }
            throw exception(TASK_VERIFICATION_CONFLICT);
        }
        FrContentTaskDO current = requireOwned(post.orgId(), taskId);
        Long settlementUnits = actualUnits;
        if ("DELIVERED".equals(normalizedDecision)) {
            // UNKNOWN 没有可信供应商计量结果；只接受真实预扣时冻结的服务端数量。
            // 后续若接入可验证供应商计量，应在此引入独立可信来源，不能直接信任客户端报数。
            settlementUnits = current.getPoolReservedUnits();
            if (settlementUnits == null || settlementUnits <= 0) {
                throw exception(TASK_POOL_FACT_MISSING);
            }
            if (!settlementUnits.equals(actualUnits)) {
                throw exception(INPUT_INVALID);
            }
        }
        final boolean verifiedByThisRequest;
        try {
            verifiedByThisRequest = store.manualVerify(taskId, post.orgId(), post.userId(), post.postId(),
                    normalizedDecision, settlementUnits, normalizedReason, normalizedEvidence, normalizedKey);
        } catch (DuplicateKeyException e) {
            // 并发复用同一人工核验幂等键时，屏蔽数据库唯一约束细节，返回稳定业务冲突。
            throw exception(TASK_VERIFICATION_CONFLICT);
        }
        if (!verifiedByThisRequest) {
            replay = store.byManualVerifyKey(post.orgId(), normalizedKey);
            if (replay != null && sameManualVerification(replay, taskId, normalizedDecision, actualUnits,
                    normalizedReason, normalizedEvidence)) {
                return replay;
            }
            throw exception(TASK_VERIFICATION_CONFLICT);
        }
        FrContentTaskDO verified = requireOwned(post.orgId(), taskId);
        if ("DELIVERED".equals(normalizedDecision)) {
            requirePoolFact(orgPoint.settleByBizKey(verified.getPoolBizKey(), settlementUnits));
        } else {
            requirePoolFact(orgPoint.releaseByBizKey(verified.getPoolBizKey()));
        }
        return verified;
    }

    private static boolean sameManualVerification(FrContentTaskDO replay, long taskId, String decision,
                                                  Long actualUnits, String reason, String evidence) {
        return replay.getId() == taskId
                && decision.equals(replay.getManualVerifyDecision())
                && java.util.Objects.equals(actualUnits, replay.getManualVerifyActualUnits())
                && reason.equals(replay.getManualVerifyReason())
                && evidence.equals(replay.getManualVerifyEvidence());
    }

    private static void requireVerifier(PostSnapshot post) {
        if (!("boss".equals(post.roleCode()) || "sales-lead".equals(post.roleCode()))) {
            throw exception(CONTENT_FORBIDDEN);
        }
    }

    private static String requiredText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw exception(INPUT_INVALID);
        }
        return value.trim();
    }

    // ---------------- sweep：提交 ----------------

    /** 单个 QUEUED 任务提交（事务外；每条独立 try/catch，失败不拖垮批）。 */
    public void submitOne(FrContentTaskDO task) {
        if (!store.claim(task.getId())) {
            return; // 已被别的实例领取
        }
        ProviderCallAudit audit = new ProviderCallAudit(task.getCompanyId(), task.getId());
        try {
            switch (task.getKind()) {
                case "link_parse" -> submitLinkParse(task, audit);
                case "asr" -> submitAsr(task, audit);
                case "copy" -> submitCopy(task, audit);
                case "digital" -> submitDigital(task, audit);
                case "ai_video" -> submitAiVideo(task, audit);
                default -> store.fail(task.getId(), task.getCompanyId(), "未知任务类型：" + task.getKind());
            }
        } catch (ContentProviderException.Rejection e) {
            store.fail(task.getId(), task.getCompanyId(), e.getMessage());
        } catch (ContentProviderException.Unavailable e) {
            store.fail(task.getId(), task.getCompanyId(), "内容生成服务暂不可用，请稍后重试或联系管理员");
        } catch (ContentProviderException.Uncertain e) {
            // 提交不确定：保留积分预扣（不释放），任务停 SUBMISSION_UNCERTAIN，绝不自动付费重投
            store.markUncertain(task.getId(), task.getCompanyId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("content task {} submit failed: {}", task.getId(), e.getMessage());
            handleUnexpectedSubmitFailure(task);
        }
        settlePool(task);
    }

    /** 外呼前可确定失败；跨过外呼边界后无明确回执必须 UNKNOWN/PENDING。 */
    private void handleUnexpectedSubmitFailure(FrContentTaskDO task) {
        String localFailure = "任务准备失败，请稍后重试";
        if (!store.failBeforeDispatch(task.getId(), task.getCompanyId(), localFailure)) {
            store.markUncertain(task.getId(), task.getCompanyId(),
                    "外部服务可能已受理，但本地结果未知，已转对账核查");
        }
    }

    private void submitLinkParse(FrContentTaskDO task, ProviderCallAudit audit) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        beginDispatch(task);
        LinkParsePort.ParsedLink link = linkParse.parse(String.valueOf(input.get("url")), audit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("videoUrl", link.videoUrl());
        result.put("audioUrl", link.audioUrl());
        result.put("text", link.text());
        result.put("title", link.title());
        result.put("coverUrl", link.coverUrl());
        result.put("author", link.author());
        result.put("platform", link.platform());
        result.put("durationMs", link.durationMs());
        store.succeedSync(task.getId(), task.getCompanyId(), channel("diadi"), null, null, null,
                JsonUtils.toJsonString(result));
    }

    private void submitAsr(FrContentTaskDO task, ProviderCallAudit audit) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        String audioUrl = resolveMediaUrl(task.getCompanyId(), input.get("assetId"), input.get("sourceUrl"));
        Double durationSec = input.get("durationSec") == null ? null
                : Double.parseDouble(String.valueOf(input.get("durationSec")));
        beginDispatch(task);
        Consumer<String> onSubmitted = providerTaskId ->
                store.persistProviderTask(task.getId(), task.getCompanyId(), channel("dashscope"), providerTaskId);
        AsrPort.Transcript transcript = asr.transcribe(new AsrPort.AsrCall(
                audioUrl, durationSec, null, onSubmitted, audit));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", transcript.text());
        result.put("durationSec", transcript.durationSec());
        store.succeedSync(task.getId(), task.getCompanyId(), channel("dashscope"), null, null, null,
                JsonUtils.toJsonString(result));
    }

    private void submitCopy(FrContentTaskDO task, ProviderCallAudit audit) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        String sourceText = String.valueOf(input.get("sourceText"));
        String requirement = input.get("requirement") == null ? null : String.valueOf(input.get("requirement"));
        String personaPrompt = input.get("personaPrompt") == null ? null : String.valueOf(input.get("personaPrompt"));
        String rewritten;
        String engine;
        String model = null;
        try {
            beginDispatch(task);
            ScriptRewritePort.Result result = rewrite.rewrite(new ScriptRewritePort.RewriteCall(
                    sourceText, requirement, personaPrompt, audit));
            rewritten = result.rewrittenText();
            engine = "deepseek";
            model = result.model();
        } catch (ContentProviderException.Rejection | ContentProviderException.Unavailable e) {
            // 只有明确未受理/未配置才免费 rules 兜底；网络未知必须上抛保留 PENDING。
            log.warn("copy task {} fell back to rules engine: {}", task.getId(), e.getMessage());
            Rewritten fallback = ContentRulesRewrite.rewrite(sourceText, requirement);
            rewritten = fallback.text();
            engine = "rules";
        }
        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("text", rewritten);
        resultJson.put("engine", engine);
        String result = JsonUtils.toJsonString(resultJson);
        if ("rules".equals(engine)) {
            store.succeedRules(task.getId(), task.getCompanyId(), result);
        } else {
            store.succeedSync(task.getId(), task.getCompanyId(), engine, engine, model, null, result);
        }
    }

    private void submitDigital(FrContentTaskDO task, ProviderCallAudit audit) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> subtitle = input.get("subtitle") == null ? null
                : (Map<String, Object>) input.get("subtitle");
        beginDispatch(task);
        oral.createByTts(new OralVideoPort.TtsRequest(
                String.valueOf(input.get("voice")),
                String.valueOf(input.get("text")),
                String.valueOf(input.get("avatar")),
                input.get("title") == null ? null : String.valueOf(input.get("title")),
                subtitle,
                providerTaskId -> store.persistProviderTask(task.getId(), task.getCompanyId(),
                        channel("hifly"), providerTaskId),
                audit));
    }

    private void submitAiVideo(FrContentTaskDO task, ProviderCallAudit audit) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        List<VideoGenerationPort.MediaRef> media = new ArrayList<>();
        Object rawRefs = input.get("references");
        if (rawRefs instanceof List<?> refs) {
            for (Object ref : refs) {
                if (ref instanceof Map<?, ?> refMap) {
                    String role = String.valueOf(refMap.get("role"));
                    String url = resolveMediaUrl(task.getCompanyId(), refMap.get("assetId"), refMap.get("url"));
                    media.add(new VideoGenerationPort.MediaRef(role, url,
                            refMap.get("name") == null ? null : String.valueOf(refMap.get("name"))));
                }
            }
        }
        beginDispatch(task);
        video.submit(new VideoGenerationPort.H3Request(
                        String.valueOf(input.get("description")), media,
                        String.valueOf(input.get("resolution")),
                        Integer.parseInt(String.valueOf(input.get("duration"))),
                        input.get("ratio") == null ? "16:9" : String.valueOf(input.get("ratio"))),
                providerTaskId -> store.persistProviderTask(task.getId(), task.getCompanyId(),
                        channel("metaso"), providerTaskId),
                audit);
    }

    private void beginDispatch(FrContentTaskDO task) {
        if (!store.markDispatchStarted(task.getId(), task.getCompanyId())) {
            throw new IllegalStateException("任务状态已变化，取消外呼");
        }
    }

    /** 素材地址解析：assetId 优先（基-1 签名 URL，生产=COS），否则原样透传 sourceUrl。 */
    private String resolveMediaUrl(long companyId, Object assetId, Object sourceUrl) {
        if (assetId != null && !String.valueOf(assetId).isBlank()) {
            AssetAccessPort.DownloadUrlView view = assets.createTaskDownloadUrl(companyId,
                    Long.parseLong(String.valueOf(assetId)), "CONTENT_ASSET");
            return view.downloadUrl();
        }
        if (sourceUrl != null && !String.valueOf(sourceUrl).isBlank()) {
            return String.valueOf(sourceUrl);
        }
        throw exception(INPUT_INVALID);
    }

    // ---------------- sweep：轮询（含 UNCERTAIN 对账——只查询，绝不重投） ----------------

    public void pollOne(FrContentTaskDO task) {
        String leaseToken = UUID.randomUUID().toString();
        if (!store.claimPoll(task.getId(), leaseToken, POLL_LEASE_SECONDS)) {
            return;
        }
        ProviderCallAudit audit = new ProviderCallAudit(task.getCompanyId(), task.getId());
        try {
            switch (task.getKind()) {
                case "asr" -> pollAsr(task, audit);
                case "digital" -> pollDigital(task, audit);
                case "ai_video" -> pollAiVideo(task, audit);
                default -> {
                    // link_parse/copy 为同步任务：若滞留非终态说明提交中断，重新排队提交
                    if ("SUBMITTING".equals(task.getStatus())) {
                        store.markUncertain(task.getId(), task.getCompanyId(), "同步任务提交中断，请人工核查");
                    }
                }
            }
        } catch (ContentProviderException.Rejection e) {
            store.fail(task.getId(), task.getCompanyId(), e.getMessage());
        } catch (ContentProviderException.Unavailable e) {
            log.warn("content task {} poll unavailable: {}", task.getId(), e.getMessage()); // 下轮再试
        } catch (ContentProviderException.Uncertain e) {
            store.markUncertain(task.getId(), task.getCompanyId(), e.getMessage());
        } finally {
            store.releasePollLease(task.getId(), leaseToken);
        }
        settlePool(task);
    }

    private void pollAsr(FrContentTaskDO task, ProviderCallAudit audit) {
        AsrPort.Transcript transcript = asr.transcribe(new AsrPort.AsrCall(
                null, null, task.getProviderTaskId(), null, audit));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", transcript.text());
        result.put("durationSec", transcript.durationSec());
        store.succeedPolled(task.getId(), task.getCompanyId(), null, JsonUtils.toJsonString(result));
    }

    private void pollDigital(FrContentTaskDO task, ProviderCallAudit audit) {
        OralVideoPort.Snapshot snapshot = oral.poll(task.getProviderTaskId(), audit);
        switch (snapshot.status()) {
            case "DONE" -> store.succeedPolled(task.getId(), task.getCompanyId(), snapshot.videoUrl(),
                    JsonUtils.toJsonString(Map.of("videoUrl", snapshot.videoUrl() == null ? "" : snapshot.videoUrl(),
                            "durationSec", snapshot.durationSec() == null ? 0 : snapshot.durationSec())));
            case "FAILED" -> {
                store.fail(task.getId(), task.getCompanyId(), "数字人口播生成失败，请调整内容后重试");
            }
            default -> log.debug("digital task {} still {}", task.getId(), snapshot.status());
        }
    }

    private void pollAiVideo(FrContentTaskDO task, ProviderCallAudit audit) {
        VideoGenerationPort.H3Snapshot snapshot = video.poll(task.getProviderTaskId(), audit);
        switch (snapshot.status()) {
            case "SUCCEEDED" -> store.succeedPolled(task.getId(), task.getCompanyId(), snapshot.resultUrl(),
                    JsonUtils.toJsonString(Map.of("videoUrl", snapshot.resultUrl(),
                            "outputSeconds", snapshot.outputSeconds() == null ? 0 : snapshot.outputSeconds())));
            case "FAILED", "CANCELLED" -> {
                store.fail(task.getId(), task.getCompanyId(),
                        "视频生成失败：" + (snapshot.failureReason() == null ? snapshot.status() : snapshot.failureReason()));
            }
            default -> log.debug("ai_video task {} still running", task.getId());
        }
    }

    // ---------------- 公司积分池终态对账 ----------------

    /**
     * 成功按实际交付量结算，明确失败全额释放；提交未知及其他在途态保持 PENDING。
     * 结算端口按 bizKey 幂等，重复回调和恢复扫描不会二次扣费。
     */
    private void settlePool(FrContentTaskDO task) {
        settlePool(task, null);
    }

    private void settlePool(FrContentTaskDO task, String leaseToken) {
        try {
            FrContentTaskDO latest = store.byId(task.getCompanyId(), task.getId());
            if (latest == null || latest.getPoolBizKey() == null) {
                return;
            }
            switch (latest.getStatus()) {
                case "SUCCEEDED" -> {
                    if ("RELEASE".equals(latest.getPoolTerminalIntent())) {
                        requirePoolFact(orgPoint.releaseByBizKey(latest.getPoolBizKey()));
                    } else {
                        requirePoolFact(orgPoint.settleByBizKey(
                                latest.getPoolBizKey(), settlementUnitsOf(latest)));
                    }
                }
                case "FAILED", "CANCELLED" ->
                        requirePoolFact(orgPoint.releaseByBizKey(latest.getPoolBizKey()));
                default -> { /* UNKNOWN/在途保留预扣 */ }
            }
            store.completePoolReconcile(task.getId(), task.getCompanyId(), leaseToken);
        } catch (RuntimeException e) {
            int attempts = store.deferPoolReconcile(task.getId(), task.getCompanyId(), leaseToken, e.getMessage());
            if (attempts >= 3) {
                log.error("org pool settle repeatedly failed for task {} (attempt {}): {}",
                        task.getId(), attempts, e.getMessage());
            } else {
                log.warn("org pool settle failed for task {}, deferred retry attempt {}: {}",
                        task.getId(), attempts, e.getMessage());
            }
        }
    }

    public void reconcilePool(FrContentTaskDO task, String leaseToken) {
        settlePool(task, leaseToken);
    }

    public void reconcilePool(FrContentTaskDO task) {
        reconcilePool(task, null);
    }

    private static OrgPointPort.Settlement requirePoolFact(OrgPointPort.Settlement settlement) {
        if (settlement == null) {
            throw exception(TASK_POOL_FACT_MISSING);
        }
        return settlement;
    }

    private long settlementUnitsOf(FrContentTaskDO task) {
        Long reserved = task.getPoolReservedUnits();
        if (reserved == null || reserved <= 0) {
            throw exception(TASK_POOL_FACT_MISSING);
        }
        long delivered = reserved;
        if (task.getResultSnapshot() != null && !task.getResultSnapshot().isBlank()) {
            Map<String, Object> result = JsonUtils.parseObject(task.getResultSnapshot(), Map.class);
            Object actual = switch (task.getKind()) {
                case "digital" -> result.get("durationSec");
                case "ai_video" -> result.get("outputSeconds");
                default -> null;
            };
            if (actual instanceof Number number && number.longValue() > 0) {
                delivered = number.longValue();
            }
        }
        return Math.min(delivered, reserved);
    }

}
