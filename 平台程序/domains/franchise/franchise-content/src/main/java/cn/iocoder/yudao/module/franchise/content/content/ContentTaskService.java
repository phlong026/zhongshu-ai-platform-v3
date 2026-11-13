package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.CONTENT_PROVIDER_UNAVAILABLE;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.INPUT_INVALID;
import static cn.iocoder.yudao.module.franchise.content.enums.FranchiseContentErrorCodes.TASK_NOT_FOUND;

/**
 * 七态任务机（D-1.5-7）：QUEUED→SUBMITTING→RUNNING→（ARCHIVING 本批无下载转存故不经）→
 * SUCCEEDED/FAILED/SUBMISSION_UNCERTAIN。五 kind 统一编排；提交/轮询全部由后台 sweep 驱动
 * （事务外外呼 + 单语句 CAS 收口，付费外呼绝不进事务——红线）。
 * 失败三分法（A2.2）：白名单/供应商明确失败→FAILED+释放配额；网络类→SUBMISSION_UNCERTAIN
 * （冻结计费，绝不自动付费重投，UNCERTAIN 带任务号者由 sweep 仅查询对账）；未配置→FAILED 中性文案。
 */
@Service
public class ContentTaskService {

    private static final Logger log = LoggerFactory.getLogger(ContentTaskService.class);
    /** 总轮询窗 2h 超窗转人工（A2.6；provider 层单任务连续轮询 5s×1080≈1.5h 由 client 内部控制）。 */
    public static final int POLL_WINDOW_HOURS = 2;

    private final ContentTaskStore store;
    private final ContentQuotaService quota;
    private final LinkParsePort linkParse;
    private final AsrPort asr;
    private final ScriptRewritePort rewrite;
    private final OralVideoPort oral;
    private final VideoGenerationPort video;
    private final AssetAccessPort assets;
    private final StoreServiceAccountPort accounts;
    private final FranchiseCommands commands;
    private final ContentProviderProperties providerProperties;
    private final OrgPointPort orgPoint;

    public ContentTaskService(ContentTaskStore store, ContentQuotaService quota,
                              LinkParsePort linkParse, AsrPort asr, ScriptRewritePort rewrite,
                              OralVideoPort oral, VideoGenerationPort video,
                              AssetAccessPort assets, StoreServiceAccountPort accounts,
                              FranchiseCommands commands, ContentProviderProperties providerProperties,
                              OrgPointPort orgPoint) {
        this.store = store;
        this.quota = quota;
        this.linkParse = linkParse;
        this.asr = asr;
        this.rewrite = rewrite;
        this.oral = oral;
        this.video = video;
        this.assets = assets;
        this.accounts = accounts;
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
            case "digital" -> createDigital(post.orgId(), post.userId(),
                    str(body.get("voice")), str(body.get("text")), str(body.get("avatar")),
                    str(body.get("title")),
                    body.get("subtitle") == null ? null : castSubtitle(body.get("subtitle")));
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

    public FrContentTaskDO createLinkParse(long companyId, long authorUserId, String url) {
        if (url == null || url.isBlank()) {
            throw exception(INPUT_INVALID);
        }
        quota.reserve(companyId, ContentBillingCatalog.LINK_RESOLUTION.key(), 1);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId,
                ContentBillingCatalog.LINK_RESOLUTION.key(), 1, id, "content_task", id);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("url", url.trim());
        return insertWithPoolKey(id, companyId, authorUserId, "link_parse", input, null, poolKey);
    }

    /** 缓存命中时直接 SUCCEEDED：零外呼、零配额（AC4）。 */
    public FrContentTaskDO createAsr(long companyId, long authorUserId, Long assetId, String sourceUrl,
                                     String mediaSha256, Double durationSec, FrContentTaskDO cached) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("assetId", assetId);
        input.put("sourceUrl", sourceUrl);
        input.put("durationSec", durationSec);
        if (cached != null) {
            long id = ContentTaskStore.newId();
            FrContentTaskDO task = store.insert(id, companyId, authorUserId, "asr",
                    JsonUtils.toJsonString(input), mediaSha256);
            store.succeedSync(id, companyId, cached.getProvider(), "cache", null,
                    cached.getResultUrl(), cached.getResultSnapshot());
            task.setStatus("SUCCEEDED").setEngine("cache").setProvider(cached.getProvider())
                    .setResultSnapshot(cached.getResultSnapshot());
            return task;
        }
        quota.reserve(companyId, ContentBillingCatalog.ASR.key(), 1);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, ContentBillingCatalog.ASR.key(), 1, id,
                "content_task", id);
        return insertWithPoolKey(id, companyId, authorUserId, "asr", input, mediaSha256, poolKey);
    }

    public FrContentTaskDO createCopy(long companyId, long authorUserId, String sourceText,
                                      String requirement, Long personaId, String personaPromptJson) {
        if (sourceText == null || sourceText.isBlank()) {
            throw exception(INPUT_INVALID);
        }
        quota.reserve(companyId, ContentBillingCatalog.COPY.key(), 1);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceText", sourceText);
        input.put("requirement", requirement);
        input.put("personaId", personaId);
        input.put("personaPrompt", personaPromptJson);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, ContentBillingCatalog.COPY.key(), 1, id,
                "content_task", id);
        return insertWithPoolKey(id, companyId, authorUserId, "copy", input, null, poolKey);
    }

    public FrContentTaskDO createDigital(long companyId, long authorUserId, String voice, String text,
                                         String avatar, String title, Map<String, Object> subtitle) {
        if (text == null || text.isBlank() || voice == null || voice.isBlank() || avatar == null || avatar.isBlank()) {
            throw exception(INPUT_INVALID);
        }
        int seconds = ContentBillingCatalog.oralSeconds(text);
        quota.reserve(companyId, ContentBillingCatalog.DIGITAL.key(), seconds);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("voice", voice);
        input.put("text", text);
        input.put("avatar", avatar);
        input.put("title", title == null || title.isBlank() ? "数字人口播" : title);
        input.put("subtitle", subtitle);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, ContentBillingCatalog.DIGITAL.key(),
                seconds, id, "content_task", id);
        return insertWithPoolKey(id, companyId, authorUserId, "digital", input, null, poolKey);
    }

    public FrContentTaskDO createAiVideo(long companyId, long authorUserId, String description,
                                         List<Map<String, Object>> references, String resolution,
                                         Integer durationSeconds, String ratio) {
        if (description == null || description.isBlank() || durationSeconds == null
                || durationSeconds < 4 || durationSeconds > 15) {
            throw exception(INPUT_INVALID);
        }
        String subject = "2K".equals(resolution)
                ? ContentBillingCatalog.AI_VIDEO_2K.key() : ContentBillingCatalog.AI_VIDEO_768P.key();
        quota.reserve(companyId, subject, durationSeconds);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("description", description);
        input.put("references", references);
        input.put("resolution", "2K".equals(resolution) ? "2K" : "768P");
        input.put("duration", durationSeconds);
        input.put("ratio", ratio);
        long id = ContentTaskStore.newId();
        String poolKey = reservePool(companyId, authorUserId, subject, durationSeconds, id,
                "content_task", id);
        return insertWithPoolKey(id, companyId, authorUserId, "ai_video", input, null, poolKey);
    }

    private FrContentTaskDO insert(long id, long companyId, long authorUserId, String kind,
                                   Map<String, Object> input, String sha) {
        return store.insert(id, companyId, authorUserId, kind, JsonUtils.toJsonString(input), sha);
    }

    /** 公司积分池预扣（24 号 B-13 content 批 A）：biz_key=content:{taskId}:{subject}，任务级幂等。
     *  返回预扣键并落库到任务行——终态后结算失败可由 reconcilePool 幂等重试（评审 P1-4）。 */
    private String reservePool(long companyId, long authorUserId, String subject, int units,
                               long taskId, String refType, long refId) {
        String bizKey = "content:" + taskId + ":" + subject;
        orgPoint.reserve(new OrgPointPort.ReserveCommand(
                bizKey, companyId, authorUserId, null, subject, units, refType, refId));
        return bizKey;
    }

    /** 先 INSERT 再落池预扣键（评审 A-P1-4：顺序颠倒会导致 UPDATE 0 行、重试扫描漏选）。 */
    private FrContentTaskDO insertWithPoolKey(long id, long companyId, long authorUserId, String kind,
                                              Map<String, Object> input, String sha, String poolKey) {
        FrContentTaskDO task = insert(id, companyId, authorUserId, kind, input, sha);
        store.markPoolBizKey(id, companyId, poolKey);
        return task;
    }

    public FrContentTaskDO requireOwned(long companyId, long taskId) {
        FrContentTaskDO task = store.byId(companyId, taskId);
        if (task == null) {
            throw exception(TASK_NOT_FOUND);
        }
        return task;
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
            releaseReserved(task);
        } catch (ContentProviderException.Unavailable e) {
            store.fail(task.getId(), task.getCompanyId(), "内容生成服务暂不可用，请稍后重试或联系管理员");
            releaseReserved(task);
        } catch (ContentProviderException.Uncertain e) {
            // 提交不确定：冻结配额（不释放），任务停 SUBMISSION_UNCERTAIN，绝不自动付费重投
            store.markUncertain(task.getId(), task.getCompanyId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("content task {} submit failed: {}", task.getId(), e.getMessage());
            store.fail(task.getId(), task.getCompanyId(), "任务处理异常，请稍后重试");
            releaseReserved(task);
        }
        settlePool(task);
    }

    private void submitLinkParse(FrContentTaskDO task, ProviderCallAudit audit) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
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
            ScriptRewritePort.Result result = rewrite.rewrite(new ScriptRewritePort.RewriteCall(
                    sourceText, requirement, personaPrompt, audit));
            rewritten = result.rewrittenText();
            engine = "deepseek";
            model = result.model();
        } catch (ContentProviderException e) {
            // 「永不空手」降级：上游失败/未配置 Key → 规则改写，engine='rules' 落库标注（D-1.5-2）
            log.warn("copy task {} fell back to rules engine: {}", task.getId(), e.getMessage());
            Rewritten fallback = ContentRulesRewrite.rewrite(sourceText, requirement);
            rewritten = fallback.text();
            engine = "rules";
        }
        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("text", rewritten);
        resultJson.put("engine", engine);
        store.succeedSync(task.getId(), task.getCompanyId(), engine, engine, model, null,
                JsonUtils.toJsonString(resultJson));
    }

    private void submitDigital(FrContentTaskDO task, ProviderCallAudit audit) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> subtitle = input.get("subtitle") == null ? null
                : (Map<String, Object>) input.get("subtitle");
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
        video.submit(new VideoGenerationPort.H3Request(
                        String.valueOf(input.get("description")), media,
                        String.valueOf(input.get("resolution")),
                        Integer.parseInt(String.valueOf(input.get("duration"))),
                        input.get("ratio") == null ? "16:9" : String.valueOf(input.get("ratio"))),
                providerTaskId -> store.persistProviderTask(task.getId(), task.getCompanyId(),
                        channel("metaso"), providerTaskId),
                audit);
    }

    /** 素材地址解析：assetId 优先（基-1 签名 URL，生产=COS），否则原样透传 sourceUrl。 */
    private String resolveMediaUrl(long companyId, Object assetId, Object sourceUrl) {
        if (assetId != null && !String.valueOf(assetId).isBlank()) {
            long account = accounts.requireServiceAccount(companyId);
            DesignServicePort.DownloadUrlView view = assets.createDownloadUrl(account,
                    Long.parseLong(String.valueOf(assetId)));
            return view.downloadUrl();
        }
        if (sourceUrl != null && !String.valueOf(sourceUrl).isBlank()) {
            return String.valueOf(sourceUrl);
        }
        throw exception(INPUT_INVALID);
    }

    // ---------------- sweep：轮询（含 UNCERTAIN 对账——只查询，绝不重投） ----------------

    public void pollOne(FrContentTaskDO task) {
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
            releaseReserved(task);
        } catch (ContentProviderException.Unavailable e) {
            log.warn("content task {} poll unavailable: {}", task.getId(), e.getMessage()); // 下轮再试
        } catch (ContentProviderException.Uncertain e) {
            store.markUncertain(task.getId(), task.getCompanyId(), e.getMessage());
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
                releaseReserved(task); // 供应商明确失败回退预扣配额，与 ai_video 同口径（评审十三 P2-1）
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
                releaseReserved(task);
            }
            default -> log.debug("ai_video task {} still running", task.getId());
        }
    }

    // ---------------- 公司积分池结算对账（24 号 B-13） ----------------

    /**
     * 按任务最终状态对账公司池：SUCCEEDED→settle（按计量单位实收）；FAILED/CANCELLED→release；
     * 其余（含 SUBMISSION_UNCERTAIN）保持 PENDING——未知结果可能已被供应商受理，绝不自动退款。
     * 幂等：settleByBizKey/releaseByBizKey 命中既有终态直接返回；上线前存量任务无 RESERVE 行则 no-op。
     */
    private void settlePool(FrContentTaskDO task) {
        try {
            FrContentTaskDO latest = store.byId(task.getCompanyId(), task.getId());
            if (latest == null) {
                return;
            }
            String bizKey = latest.getPoolBizKey();
            String subject = poolSubject(latest);
            if (bizKey == null) {
                if (subject == null) {
                    return;
                }
                bizKey = "content:" + latest.getId() + ":" + subject;
            }
            switch (latest.getStatus()) {
                case "SUCCEEDED" -> orgPoint.settleByBizKey(bizKey, deliveredUnitsOf(latest));
                case "FAILED", "CANCELLED" -> orgPoint.releaseByBizKey(bizKey);
                default -> { /* QUEUED/SUBMITTING/RUNNING/SUBMISSION_UNCERTAIN：保持在途占用 */ }
            }
        } catch (RuntimeException e) {
            // 不静默：任务已终态但结算失败，由 ContentTaskJob 的 reconcile 扫描幂等重试（评审 P1-4）
            log.warn("org pool settle failed for task {}, will retry by reconcile sweep: {}",
                    task.getId(), e.getMessage());
        }
    }

    /** 终态后结算失败的重试入口（ContentTaskJob 扫描调用；幂等）。 */
    public void reconcilePool(FrContentTaskDO task) {
        settlePool(task);
    }

    /**
     * 实际交付计量（评审 P1-3）：优先取结果快照的真实值（digital.durationSec / ai_video.outputSeconds），
     * 缺失才回退到输入预估——否则按预估结算会吃掉应退差额。
     */
    private long deliveredUnitsOf(FrContentTaskDO task) {
        if (task.getResultSnapshot() != null && !task.getResultSnapshot().isBlank()) {
            Map<String, Object> result = JsonUtils.parseObject(task.getResultSnapshot(), Map.class);
            Object actual = switch (task.getKind()) {
                case "digital" -> result.get("durationSec");
                case "ai_video" -> result.get("outputSeconds");
                default -> null;
            };
            if (actual instanceof Number number && number.longValue() > 0) {
                return number.longValue();
            }
        }
        return estimatedUnitsOf(task);
    }

    private String poolSubject(FrContentTaskDO task) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        return switch (task.getKind()) {
            case "link_parse" -> ContentBillingCatalog.LINK_RESOLUTION.key();
            case "asr" -> ContentBillingCatalog.ASR.key();
            case "copy" -> ContentBillingCatalog.COPY.key();
            case "digital" -> ContentBillingCatalog.DIGITAL.key();
            case "ai_video" -> "2K".equals(input.get("resolution"))
                    ? ContentBillingCatalog.AI_VIDEO_2K.key() : ContentBillingCatalog.AI_VIDEO_768P.key();
            default -> null;
        };
    }

    // ---------------- 配额回退 ----------------

    private void releaseReserved(FrContentTaskDO task) {
        try {
            Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
            String subject = switch (task.getKind()) {
                case "link_parse" -> ContentBillingCatalog.LINK_RESOLUTION.key();
                case "asr" -> ContentBillingCatalog.ASR.key();
                case "copy" -> ContentBillingCatalog.COPY.key();
                case "digital" -> ContentBillingCatalog.DIGITAL.key();
                case "ai_video" -> "2K".equals(input.get("resolution"))
                        ? ContentBillingCatalog.AI_VIDEO_2K.key() : ContentBillingCatalog.AI_VIDEO_768P.key();
                default -> null;
            };
            if (subject != null) {
                quota.release(task.getCompanyId(), subject, estimatedUnitsOf(task));
            }
        } catch (RuntimeException e) {
            log.warn("quota release failed for task {}: {}", task.getId(), e.getMessage());
        }
    }

    private int estimatedUnitsOf(FrContentTaskDO task) {
        Map<String, Object> input = JsonUtils.parseObject(task.getInputSnapshot(), Map.class);
        return switch (task.getKind()) {
            case "digital" -> ContentBillingCatalog.oralSeconds(
                    input.get("text") == null ? null : String.valueOf(input.get("text")));
            case "ai_video" -> Integer.parseInt(String.valueOf(input.get("duration")));
            default -> 1;
        };
    }

}
