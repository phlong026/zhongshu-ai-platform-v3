package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.design.remote.RemoteDesignGateway;
import cn.iocoder.yudao.module.franchise.design.publiccase.PublicCaseAccessService;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort;
import cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.ServiceAccountInactiveException;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes.SCHEME_SIZE_REQUIRED;

/**
 * 设计赋能编排（B2 板块 A）：身份→服务账号→Port 调用→同事务审计→响应组装。
 *
 * <p>规则（需求文档 §3.1/§3.4/§五）：
 * <ul>
 *   <li>服务账号按 post.orgId() 服务端解析（接口永不收 accountId 入参，IDOR 防线一）；
 *       停用信号（{@link ServiceAccountInactiveException}）唯一换译点=1_112_000_004；</li>
 *   <li>付费命令：外层事务同时覆盖 Port 调用与 {@link FranchiseCommands#record}（业务与事件同事务）；
 *       replayed=true 不重复落审计（P-2 收据）；审计键=design-audit:{动作}:{uuid}（独立键空间），
 *       revision=雪花 ID（全局唯一，防同毫秒撞唯一约束）；</li>
 *   <li>save-to-customer：advisory lock（锁键与收据键同源，P-4 规范化）串行化同键请求，
 *       锁内二次 replayed 检查（防并发双写）；首次结果写事件 aggregateId=schemeId、revision=version。</li>
 * </ul>
 */
@Slf4j
@Service
public class DesignAccessService {

    private final DesignServicePort designService;
    private final CustomerSchemePort customerSchemes;
    private final StoreServiceAccountPort serviceAccounts;
    private final FranchiseCommands commands;
    private final CurrentPost currentPost;
    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbcTemplate;
    private RemoteDesignGateway remote;
    private cn.iocoder.yudao.module.franchise.design.remote.RemoteReferenceReader referenceReader;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferenceReader(cn.iocoder.yudao.module.franchise.design.remote.RemoteReferenceReader reader) {
        this.referenceReader = reader;
    }
    private PublicCaseAccessService publicCases;
    private AssetAccessPort localAssets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRemoteGateway(RemoteDesignGateway remote) {
        this.remote = remote;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPublicCaseAccess(PublicCaseAccessService publicCases) {
        this.publicCases = publicCases;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLocalAssets(AssetAccessPort localAssets) {
        this.localAssets = localAssets;
    }

    @jakarta.annotation.PostConstruct
    public void verifyCapabilityWiring() {
        if (remote == null && (designService == null || serviceAccounts == null)) {
            throw new IllegalStateException("Design capability requires either remote gateway or the legacy bridge");
        }
        if (remote != null && publicCases == null) {
            throw new IllegalStateException("Remote design requires B-local public case access");
        }
        if (remote != null && (localAssets == null || serviceAccounts == null)) {
            throw new IllegalStateException("Remote design requires B-local asset access");
        }
    }

    public DesignAccessService(@org.springframework.lang.Nullable DesignServicePort designService, CustomerSchemePort customerSchemes,
                               @org.springframework.lang.Nullable StoreServiceAccountPort serviceAccounts, FranchiseCommands commands,
                               CurrentPost currentPost, PlatformTransactionManager transactionManager,
                               DataSource dataSource) {
        this.designService = designService;
        this.customerSchemes = customerSchemes;
        this.serviceAccounts = serviceAccounts;
        this.commands = commands;
        this.currentPost = currentPost;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public PostSnapshot post() {
        return currentPost.require();
    }

    /** 服务账号解析（每次都过状态校验；停用 → 1_112_000_004） */
    public long serviceAccount(PostSnapshot post) {
        try {
            return serviceAccounts.requireServiceAccount(post.orgId());
        } catch (ServiceAccountInactiveException e) {
            throw exception(cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes.SERVICE_ACCOUNT_INACTIVE);
        }
    }

    /** CRM 客户可见/可办错误统一换译 1_112_000_002（跟随 CRM 不区分暴露语义，Codex R1/R2） */
    private <T> T customerAccessible(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException e) {
            if (e.getCode() == 1_110_000_000 || e.getCode() == 1_110_000_001) {
                throw exception(cn.iocoder.yudao.module.franchise.api.FranchiseDesignErrorCodes.CUSTOMER_NOT_ACCESSIBLE);
            }
            throw e;
        }
    }

    /** 代理类审计（P-2 收据 replayed=false 才记；与能力调用同事务） */
    private void audit(PostSnapshot post, String action, long aggregateId, Map<String, Object> resources) {
        var input = new LinkedHashMap<String, Object>();
        input.put("actorUserId", post.userId());
        input.put("actorPostId", post.postId());
        input.put("actorRoleCode", post.roleCode());
        input.putAll(resources);
        commands.record(post, "fr-design-" + action, aggregateId, IdWorker.getId(),
                "design-" + action, input, "design-audit:" + action + ":" + java.util.UUID.randomUUID());
    }

    // ========== 项目与任务 ==========

    public DesignServicePort.ProjectCreated createProject(PostSnapshot post, DesignServicePort.DesignProjectCreate cmd) {
        if (remote != null) {
            var ids = cn.iocoder.yudao.module.franchise.design.remote.RemoteReferenceReader.referenceIds(cmd.requirementInputs());
            if (!ids.isEmpty()) {
                if (referenceReader == null || localAssets == null) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(
                        1_112_100_003, "参考图读取协议尚未配置");
                if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400,"创建参考图项目需要稳定业务键");
                var translated = referenceReader.translate(post, cmd, localAssets, new AssetAccessPort.Actor(post.orgId(), serviceAccount(post), post.userId(), post.postId()));
                return remote.call(post, Operation.CREATE_PROJECT, cmd.idempotencyKey(), translated);
            }
            return remote.call(post, Operation.CREATE_PROJECT, cmd.idempotencyKey(), cmd);
        }
        long accountId = serviceAccount(post);
        return txTemplate.execute(status -> {
            var created = designService.createProject(accountId, cmd, actor(post));
            if (!created.replayed()) {
                audit(post, "create-project", created.projectId(),
                        Map.of("projectId", created.projectId(), "sourceType", String.valueOf(cmd.sourceType()),
                                "line", String.valueOf(cmd.line())));
            }
            return created;
        });
    }

    public DesignServicePort.ProjectPageView listProjects(PostSnapshot post, String cursor, int limit) {
        if (remote != null) return remote.call(post, Operation.LIST_PROJECTS, null, new Page(cursor, limit));
        return designService.listProjects(serviceAccount(post), cursor, limit);
    }

    /** R10 带 customerId：先查关联集合（CRM 可读校验在前），再在集合内分页（分页前过滤，不漏项） */
    public Map<String, Object> listProjectsForCustomer(PostSnapshot post, long customerId, String cursor, int limit) {
        Set<Long> linked = new HashSet<>(customerAccessible(() -> customerSchemes.listDesignProjectIds(post, customerId)));
        List<DesignServicePort.ProjectListItem> collected = new java.util.ArrayList<>();
        String pageCursor = cursor;
        int guard = 0;
        while (guard++ < 50) {
            var page = listProjects(post, pageCursor, Math.max(limit, 50));
            for (var item : page.list()) {
                if (linked.contains(item.projectId())) {
                    collected.add(item);
                }
            }
            if (page.nextCursor() == null || page.list().isEmpty()) {
                pageCursor = null;
                break;
            }
            pageCursor = page.nextCursor();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (collected.size() <= limit) {
            result.put("list", collected);
            result.put("nextCursor", null);
        } else {
            // design 项目游标=id 降序：以第 limit 条的 id 续页
            result.put("list", collected.subList(0, limit));
            result.put("nextCursor", String.valueOf(collected.get(limit - 1).projectId()));
        }
        return result;
    }

    public DesignServicePort.ProjectDetailView getProject(PostSnapshot post, long projectId, Long jobId) {
        if (remote != null) return remote.call(post, Operation.GET_PROJECT,
                jobId == null ? null : "promotion:" + projectId + ":" + jobId + ":" + post.userId() + ":" + post.postId() + ":" + post.roleCode(),
                new Project(projectId, jobId));
        return designService.getProject(serviceAccount(post), projectId, jobId);
    }

    public DesignServicePort.AiJobView getAiJob(PostSnapshot post, long jobId) {
        if (remote != null) return remote.call(post, Operation.GET_AI_JOB, null, new Job(jobId));
        return designService.getAiJob(serviceAccount(post), jobId);
    }

    public DesignServicePort.AiJobView cancelAiJob(PostSnapshot post, long jobId) {
        return cancelAiJob(post, jobId, null);
    }

    public DesignServicePort.AiJobView cancelAiJob(PostSnapshot post, long jobId, String key) {
        if (remote != null) return remote.call(post, Operation.CANCEL_AI_JOB, key, new Job(jobId));
        return designService.cancelAiJob(serviceAccount(post), jobId);
    }

    public String notePolish(PostSnapshot post, String note, String line) {
        return notePolish(post, note, line, null);
    }

    public String notePolish(PostSnapshot post, String note, String line, String key) {
        if (remote != null) return remote.call(post, Operation.NOTE_POLISH, key, new Note(note, line));
        return designService.notePolish(serviceAccount(post), note, line);
    }

    public List<DesignServicePort.ResultVersionView> listResultVersions(PostSnapshot post, long projectId) {
        if (remote != null) return remote.call(post, Operation.LIST_RESULT_VERSIONS, null, new Project(projectId, null));
        return designService.listResultVersions(serviceAccount(post), projectId);
    }

    public DesignServicePort.JobSubmitted createFlatJob(PostSnapshot post, long projectId,
                                                        DesignServicePort.FlatJobCreate cmd) {
        if (remote != null) return remote.call(post, Operation.CREATE_FLAT_JOB, cmd.idempotencyKey(), new FlatJob(projectId, cmd));
        long accountId = serviceAccount(post);
        return txTemplate.execute(status -> {
            var submitted = designService.createFlatJob(accountId, projectId, cmd, actor(post));
            if (!submitted.replayed()) {
                audit(post, "flat-job", submitted.jobId(),
                        Map.of("projectId", projectId, "jobId", submitted.jobId(), "count", cmd.count()));
            }
            return submitted;
        });
    }

    public DesignServicePort.JobSubmitted createElevationJob(PostSnapshot post, long projectId,
                                                             DesignServicePort.ElevationJobCreate cmd) {
        if (remote != null) return remote.call(post, Operation.CREATE_ELEVATION_JOB, cmd.idempotencyKey(), new ElevationJob(projectId, cmd));
        long accountId = serviceAccount(post);
        return txTemplate.execute(status -> {
            var submitted = designService.createElevationJob(accountId, projectId, cmd, actor(post));
            if (!submitted.replayed()) {
                audit(post, "elevation-job", submitted.jobId(),
                        Map.of("projectId", projectId, "jobId", submitted.jobId(),
                                "styleCode", String.valueOf(cmd.styleCode()), "count", cmd.count()));
            }
            return submitted;
        });
    }

    /** 参考图必须在项目创建时冻结；旧任务级字段不能被静默忽略后收费。 */
    public void requireReferenceInputSupported(Map<String, Object> requirementInputs) {
        if (remote == null || requirementInputs == null || !requirementInputs.containsKey("referenceAssetIds")) return;
        Object references = requirementInputs.get("referenceAssetIds");
        if (references instanceof java.util.Collection<?> collection && collection.isEmpty()) return;
        throw new cn.iocoder.yudao.framework.common.exception.ServiceException(1_112_100_003,
                "参考图必须在创建项目时配置并冻结，请重新创建项目后重试");
    }

    public DesignServicePort.SelectionSaved confirmFlatCandidate(PostSnapshot post, long projectId,
                                                                 Long expectedJobId, long candidateId) {
        return confirmFlatCandidate(post, projectId, expectedJobId, candidateId, null);
    }

    public DesignServicePort.SelectionSaved confirmFlatCandidate(PostSnapshot post, long projectId,
                                                                 Long expectedJobId, long candidateId, String key) {
        if (remote != null) return remote.call(post, Operation.CONFIRM_FLAT_CANDIDATE, key, new Selection(projectId, expectedJobId, candidateId));
        long accountId = serviceAccount(post);
        return txTemplate.execute(status -> {
            var saved = designService.confirmFlatCandidate(accountId, projectId, expectedJobId, candidateId, actor(post));
            if (!saved.replayed()) {
                audit(post, "flat-selection", projectId,
                        Map.of("projectId", projectId, "candidateId", candidateId,
                                "versionId", saved.resultVersionId() == null ? 0L : saved.resultVersionId()));
            }
            return saved;
        });
    }

    public DesignServicePort.SelectionSaved selectElevationCandidate(PostSnapshot post, long projectId,
                                                                     Long expectedJobId, long candidateId) {
        return selectElevationCandidate(post, projectId, expectedJobId, candidateId, null);
    }

    public DesignServicePort.SelectionSaved selectElevationCandidate(PostSnapshot post, long projectId,
                                                                     Long expectedJobId, long candidateId, String key) {
        if (remote != null) return remote.call(post, Operation.SELECT_ELEVATION_CANDIDATE, key, new Selection(projectId, expectedJobId, candidateId));
        long accountId = serviceAccount(post);
        return txTemplate.execute(status -> {
            var saved = designService.selectElevationCandidate(accountId, projectId, expectedJobId, candidateId, actor(post));
            if (!saved.replayed()) {
                audit(post, "elevation-selection", projectId,
                        Map.of("projectId", projectId, "candidateId", candidateId,
                                "versionId", saved.resultVersionId() == null ? 0L : saved.resultVersionId()));
            }
            return saved;
        });
    }

    public DesignServicePort.RevisionSubmitted createRevisionRequest(PostSnapshot post, long projectId,
                                                                     DesignServicePort.RevisionCreate cmd) {
        if (remote != null) return remote.call(post, Operation.CREATE_REVISION_REQUEST, cmd.idempotencyKey(), new Revision(projectId, cmd));
        long accountId = serviceAccount(post);
        return txTemplate.execute(status -> {
            var submitted = designService.createRevisionRequest(accountId, projectId, cmd, actor(post));
            if (!submitted.replayed()) {
                audit(post, "revision", submitted.requestId(),
                        Map.of("projectId", projectId, "jobId", submitted.newJobId()));
            }
            return submitted;
        });
    }

    // ========== 回写（R21 / §五） ==========

    /**
     * save-to-customer：advisory lock（同键串行化）→ 锁内 replayed 检查 → 引用/kind 校验（Port）→
     * 回写（CRM 行锁+对象权限）→ 收据事件（payload=规范化请求，首次结果入 aggregateId/revision）。
     * 同键重放读回首结果；同键异内容 → 1099000000；replayed 不重复审计。
     */
    public Map<String, Object> saveToCustomer(PostSnapshot post, SaveToCustomerCommand cmd) {
        long accountId = remote == null ? serviceAccount(post) : 0;
        String idemKey = FranchiseCommandKeys.normalize(cmd.idempotencyKey());
        var scope = new FranchiseCommandKeys.PostKeyScope("franchise", post.userId());
        var fingerprint = new LinkedHashMap<String, Object>();
        fingerprint.put("operation", "save-to-customer");
        fingerprint.put("projectId", cmd.projectId());
        fingerprint.put("customerId", cmd.customerId());
        fingerprint.put("kind", cmd.kind());
        fingerprint.put("resultVersionId", cmd.resultVersionId());
        fingerprint.put("estimateId", cmd.estimateId());
        fingerprint.put("width", cmd.width() == null ? null : cmd.width().stripTrailingZeros().toPlainString());
        fingerprint.put("depth", cmd.depth() == null ? null : cmd.depth().stripTrailingZeros().toPlainString());
        fingerprint.put("floors", cmd.floors());
        DesignServicePort.SchemeRef remoteRef;
        if (remote != null) {
            customerAccessible(() -> { customerSchemes.requireWritable(post, cmd.customerId()); return null; });
            var existing = commands.replayed(post, idemKey, fingerprint);
            if (existing.isPresent()) return Map.of("schemeId", existing.get().getAggregateId(), "version", existing.get().getRevision(), "replayed", true);
            remoteRef = remote.call(post, Operation.VALIDATE_SCHEME_REF, null,
                    new Scheme(cmd.projectId(), cmd.kind(), cmd.resultVersionId(), cmd.estimateId()));
        } else {
            remoteRef = null;
        }
        return txTemplate.execute(status -> {
            jdbcTemplate.queryForMap("SELECT pg_advisory_xact_lock(?)",
                    FranchiseCommandKeys.lockKey(scope, idemKey));
            var existing = commands.replayed(post, idemKey, fingerprint);
            if (existing.isPresent()) {
                CommandEventDO event = existing.get();
                log.info("[save-to-customer][replay scheme={} version={}]", event.getAggregateId(), event.getRevision());
                return Map.of("schemeId", event.getAggregateId(), "version", event.getRevision(), "replayed", true);
            }
            // §五校验顺序：①CRM 对象权限 → ②引用归属/kind → ④执行（跨组织客户先撞权限，统一 1_112_000_002）
            customerAccessible(() -> {
                customerSchemes.requireWritable(post, cmd.customerId());
                return null;
            });
            var ref = remoteRef != null ? remoteRef : designService.validateSchemeRef(accountId, cmd.projectId(), cmd.kind(),
                    cmd.resultVersionId(), cmd.estimateId());
            BigDecimal width = cmd.width();
            BigDecimal depth = cmd.depth();
            Integer floors = cmd.floors();
            BigDecimal area = ref.estimateArea() == null ? null : BigDecimal.valueOf(ref.estimateArea());
            if ("BUDGET".equals(cmd.kind())) {
                // 尺寸前置校验（1_112_000_001）：DB CHECK 对 NULL 不生效，显式拦截（area 服务端取快照）
                validateBudgetDimensions(width, depth, floors, area);
            } else if (width == null || depth == null || floors == null) {
                // PLAN/FACADE：缺省时从结果快照继承（faceWidthM/depthM/floorCount 或 width/depth/floors）
                var dims = dimsFromSnapshot(ref.configSnapshotJson());
                width = width != null ? width : dims[0];
                depth = depth != null ? depth : dims[1];
                floors = floors != null ? floors : (dims[2] == null ? null : dims[2].intValue());
            }
            var save = new CustomerSchemePort.SchemeSave(cmd.kind(), title(cmd), payload(ref, cmd),
                    width, depth, floors, area,
                    "BUDGET".equals(cmd.kind()) ? ref.totalLowCents() : null,
                    cmd.projectId());
            var saved = customerAccessible(() -> customerSchemes.saveScheme(post, cmd.customerId(), save));
            commands.record(post, "fr_customer_scheme", saved.schemeId(), saved.version(),
                    "design-save-to-customer", fingerprint, idemKey);
            log.info("[save-to-customer][actor={} account={} scheme={} version={}]",
                    post.userId(), accountId, saved.schemeId(), saved.version());
            return Map.of("schemeId", saved.schemeId(), "version", saved.version(), "replayed", false);
        });
    }

    /**
     * BUDGET 回写尺寸校验（P-8 可测抽取）：范围/精度与 CRM normalize 同规
     * （宽深 (0,999.99]、层数 [1,20] 整数、面积 (0,99999.99]、小数至多两位）。
     */
    static void validateBudgetDimensions(BigDecimal width, BigDecimal depth, Integer floors, BigDecimal area) {
        if (width == null || depth == null || floors == null || area == null
                || width.signum() <= 0 || width.compareTo(new BigDecimal("999.99")) > 0
                || depth.signum() <= 0 || depth.compareTo(new BigDecimal("999.99")) > 0
                || floors < 1 || floors > 20
                || area.signum() <= 0 || area.compareTo(new BigDecimal("99999.99")) > 0
                || width.stripTrailingZeros().scale() > 2 || depth.stripTrailingZeros().scale() > 2) {
            throw exception(SCHEME_SIZE_REQUIRED);
        }
    }

    public record SaveToCustomerCommand(long projectId, long customerId, String kind, Long resultVersionId,
                                        Long estimateId, BigDecimal width, BigDecimal depth, Integer floors,
                                        String idempotencyKey) {
    }

    /** title 生成规则（§五）：AI设计·{平面方案|立面方案|参考预算}·{yyyyMMdd HH:mm} */
    private String title(SaveToCustomerCommand cmd) {
        String label = switch (cmd.kind()) {
            case "PLAN" -> "平面方案";
            case "FACADE" -> "立面方案";
            case "BUDGET" -> "参考预算";
            default -> cmd.kind();
        };
        return "AI设计·" + label + "·" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd HH:mm")
                .format(java.time.LocalDateTime.now());
    }

    /** payload（TEXT 列 JSON 字符串，ID 引用不存时效 URL，含 operation/projectId 幂等指纹项） */
    private String payload(DesignServicePort.SchemeRef ref, SaveToCustomerCommand cmd) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("operation", "save-to-customer");
        payload.put("projectId", cmd.projectId());
        payload.put("designProjectId", cmd.projectId());
        payload.put("kind", cmd.kind());
        if (ref.resultVersionId() != null) payload.put("resultVersionId", ref.resultVersionId());
        payload.put("flatCandidateIds", ref.flatCandidateIds());
        if (ref.selectedFlatAssetId() != null) payload.put("selectedFlatAssetId", ref.selectedFlatAssetId());
        if (ref.selectedElevationAssetId() != null) payload.put("selectedElevationAssetId", ref.selectedElevationAssetId());
        if (ref.configSnapshotJson() != null) {
            try {
                payload.put("configSnapshot", new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(ref.configSnapshotJson(), Map.class));
            } catch (java.io.IOException ignored) {
                payload.put("configSnapshotRaw", ref.configSnapshotJson());
            }
        }
        if (ref.estimateId() != null) {
            payload.put("estimateId", ref.estimateId());
            payload.put("totalLowCents", ref.totalLowCents());
            payload.put("totalHighCents", ref.totalHighCents());
        }
        return cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(payload);
    }

    /** PLAN/FACADE 尺寸缺省回退：解析结果快照（宽/深/层数，键名兼容两套口径） */
    private BigDecimal[] dimsFromSnapshot(String configSnapshotJson) {
        BigDecimal width = null, depth = null;
        BigDecimal floors = null;
        if (configSnapshotJson != null) {
            try {
                var snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readValue(configSnapshotJson, Map.class);
                width = firstDecimal(snapshot, "width", "faceWidthM", "faceWidth");
                depth = firstDecimal(snapshot, "depth", "depthM");
                floors = firstDecimal(snapshot, "floors", "floorCount");
            } catch (java.io.IOException ignored) {
                // 快照损坏按无尺寸处理
            }
        }
        return new BigDecimal[]{width, depth, floors};
    }

    private BigDecimal firstDecimal(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (value instanceof String s && !s.isBlank()) {
                try {
                    return new BigDecimal(s.trim());
                } catch (NumberFormatException ignored) {
                    // 非法值跳过
                }
            }
        }
        return null;
    }

    // ========== 灵感库 / 预算 / 资产（只读或票据类，无审计） ==========

    public DesignServicePort.CasePage listCases(PostSnapshot post, DesignServicePort.CaseQuery query) {
        if (publicCases != null) return publicCases.listCases(post, query);
        if (remote != null) return remote.call(post, Operation.LIST_CASES, null, query);
        return designService.listCases(query);
    }

    public List<DesignServicePort.StyleOption> listCaseStyles(PostSnapshot post) {
        if (publicCases != null) return publicCases.listCaseStyles(post);
        if (remote != null) return remote.call(post, Operation.LIST_CASE_STYLES, null, new Empty());
        return designService.listCaseStyles();
    }

    public DesignServicePort.CaseDetailView getCaseDetail(PostSnapshot post, long caseId) {
        if (publicCases != null) return publicCases.getCaseDetail(post, caseId);
        if (remote != null) return remote.call(post, Operation.GET_CASE_DETAIL, null, new Case(caseId));
        return designService.getCaseDetail(caseId);
    }

    public DesignServicePort.DownloadUrlView caseAssetDownloadUrl(PostSnapshot post,long caseId,long assetId) {
        if (publicCases == null) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(503,"公开案例图片通道尚未配置");
        return publicCases.caseAssetDownloadUrl(post,caseId,assetId);
    }

    public void favoriteCase(PostSnapshot post, long caseId) { favoriteCase(post, caseId, null); }

    public void favoriteCase(PostSnapshot post, long caseId, String key) {
        if (publicCases != null) { publicCases.favoriteCase(post, caseId); return; }
        if (remote != null) { remote.call(post, Operation.FAVORITE_CASE, key, new Case(caseId)); return; }
        designService.favoriteCase(serviceAccount(post), caseId);
    }

    public void unfavoriteCase(PostSnapshot post, long caseId) { unfavoriteCase(post, caseId, null); }

    public void unfavoriteCase(PostSnapshot post, long caseId, String key) {
        if (publicCases != null) { publicCases.unfavoriteCase(post, caseId); return; }
        if (remote != null) { remote.call(post, Operation.UNFAVORITE_CASE, key, new Case(caseId)); return; }
        designService.unfavoriteCase(serviceAccount(post), caseId);
    }

    public DesignServicePort.CasePage listFavorites(PostSnapshot post, String cursor, int limit) {
        if (publicCases != null) return publicCases.listFavorites(post, cursor, limit);
        if (remote != null) return remote.call(post, Operation.LIST_FAVORITES, null, new Page(cursor, limit));
        return designService.listFavorites(serviceAccount(post), cursor, limit);
    }

    public DesignServicePort.RuleInfoView budgetRuleInfo(PostSnapshot post, String regionCode,
                                                         String structureType, String materialGrade) {
        if (remote != null) return remote.call(post, Operation.BUDGET_RULE_INFO, null, new BudgetRule(regionCode, structureType, materialGrade));
        return designService.budgetRuleInfo(regionCode, structureType, materialGrade);
    }

    public DesignServicePort.EstimateCreated createBudgetEstimate(PostSnapshot post, long projectId,
                                                                  DesignServicePort.BudgetEstimateCreate cmd) {
        if (remote != null) return remote.call(post, Operation.CREATE_BUDGET_ESTIMATE, cmd.idempotencyKey(), new Budget(projectId, cmd));
        long accountId = serviceAccount(post);
        return txTemplate.execute(status -> {
            var created = designService.createBudgetEstimate(accountId, projectId, cmd, actor(post));
            if (!created.replayed()) {
                audit(post, "budget-estimate", created.estimateId(),
                        Map.of("projectId", projectId, "estimateId", created.estimateId(),
                                "regionCode", String.valueOf(cmd.regionCode()), "buildingArea", cmd.buildingArea()));
            }
            return created;
        });
    }

    public DesignServicePort.UploadTicketView createUploadTicket(PostSnapshot post,
                                                                 DesignServicePort.UploadTicketCreate cmd) {
        return createUploadTicket(post, cmd, null);
    }

    public DesignServicePort.UploadTicketView createUploadTicket(PostSnapshot post,
                                                                 DesignServicePort.UploadTicketCreate cmd, String key) {
        if (remote != null) {
            var ticket = localAssets.createUploadTicket(assetActor(post), cmd.assetType(), cmd.mimeType(), cmd.sizeBytes(), cmd.sha256());
            return new DesignServicePort.UploadTicketView(ticket.assetId(), ticket.uploadUrl());
        }
        return designService.createUploadTicket(serviceAccount(post), cmd);
    }

    public DesignServicePort.AssetCompleted completeUpload(PostSnapshot post, long assetId) {
        return completeUpload(post, assetId, null);
    }

    public DesignServicePort.AssetCompleted completeUpload(PostSnapshot post, long assetId, String key) {
        if (remote != null) {
            var completed = localAssets.completeUpload(assetActor(post), assetId);
            return new DesignServicePort.AssetCompleted(completed.assetId(), completed.uploadStatus(), completed.rejectedReason());
        }
        return designService.completeUpload(serviceAccount(post), assetId);
    }

    public DesignServicePort.DownloadUrlView createDownloadUrl(PostSnapshot post, long assetId) {
        // 设计候选/结果编号属于能力方；B输入图片走独立franchise/assets接口，禁止按同号回退。
        if (remote != null) return remote.call(post, Operation.CREATE_DOWNLOAD_URL, null, new Asset(assetId));
        return designService.createDownloadUrl(serviceAccount(post), assetId);
    }

    private AssetAccessPort.Actor assetActor(PostSnapshot post) {
        return new AssetAccessPort.Actor(post.orgId(), serviceAccount(post), post.userId(), post.postId());
    }

    private DesignServicePort.Actor actor(PostSnapshot post) {
        return new DesignServicePort.Actor(post.userId(), post.postId(), post.roleCode());
    }
}
