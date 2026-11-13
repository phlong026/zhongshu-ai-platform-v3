package cn.iocoder.yudao.module.design.project;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.DESIGN_PROJECT_DELETE_CONFLICT;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.DESIGN_PROJECT_NAME_INVALID;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.DESIGN_JOB_NOT_TERMINAL;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.DESIGN_STAGE_CONFLICT;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.GENERATION_REFERENCE_NOT_AUTHORIZED;

/**
 * 设计项目与平面竖切（架构 §6.3、§8.2；蓝图 P5）
 *
 * 合同：
 * - 建项目记录来源快照；参考案例仅 PUBLISHED 可引用；
 * - 创建平面任务同事务：锁定案例版本、校验当前有效 GENERATION_REFERENCE 授权并冻结
 *   grant_id + rights_version 快照（仅公开展示不可生成）→ 委托 AiJobPort 计价扣点建任务；
 *   幂等键保证重复点击返回同一任务；授权撤回后新任务拒绝、已建任务凭快照审计不追溯；
 * - 立面任务输入 = 已选平面候选（空间约束）+（CASE_REFERENCE 时）有有效生成参考授权的案例立面图与封面图
 *   （风格参考，未授权立面资产自动排除），参考图总数不超过引擎契约上限 8；
 * - 候选晋升：任务终态后 ACCEPTED 结果晋升为资产（所有者=用户）+ design_candidate，幂等；
 * - 选择：候选属本人项目、任务终态才可选；每阶段一条有效选择，重复选择返回既有。
 */
@Slf4j
@Service
public class DesignProjectService {
    @jakarta.annotation.Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort accountStatePort;

    public record ProjectSnapshot(long projectId, long userId, String sourceType, Long refCaseId,
                                  String stage, String status, String name) {
    }

    public record CandidateRow(long candidateId, long jobId, int slotNo, long assetId) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final AiJobPort aiJobPort;

    /** 设计桥公司池端口（D6 v2）：仅桥侧传 OrgBilling 时使用；App 链 null 走个人池。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.iocoder.yudao.module.franchise.api.OrgPointPort orgPointPort;

    /** 桥侧计费上下文；null = App 链（个人池，行为不变）。 */
    public record OrgBilling(long orgId, long actorUserId, long actorPostId) {
    }

    private final AssetService assetService;

    private final RightsGrantService rightsGrantService;

    private final cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort quarantineObjectPort;

    public DesignProjectService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                AiJobPort aiJobPort, AssetService assetService,
                                RightsGrantService rightsGrantService,
                                cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort quarantineObjectPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.aiJobPort = aiJobPort;
        this.assetService = assetService;
        this.rightsGrantService = rightsGrantService;
        this.quarantineObjectPort = quarantineObjectPort;
    }

    /** 创建项目：CASE_REFERENCE 仅可引用已发布案例并冻结版本；SELF_UPLOAD 需草图资产。两种来源都冻结需求快照。
     *  需求①拆分：line=ELEVATION 表示立面独立线路项目（出生即 ELEVATION 阶段，生成不依赖平面选择）。 */
    public long createProject(long userId, String sourceType, Long refCaseId, Long sketchAssetId,
                              Map<String, Object> requirementInputs) {
        return createProject(userId, sourceType, refCaseId, sketchAssetId, requirementInputs, "FLAT");
    }

    public long createProject(long userId, String sourceType, Long refCaseId, Long sketchAssetId,
                              Map<String, Object> requirementInputs, String line) {
        return createProject(userId, sourceType, refCaseId, sketchAssetId, requirementInputs, line, null);
    }

    public long createProject(long userId, String sourceType, Long refCaseId, Long sketchAssetId,
                              Map<String, Object> requirementInputs, String line, Long selectedCaseVersionId) {
        return txTemplate.execute(status -> {
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
            if (selectedCaseVersionId != null && (selectedCaseVersionId <= 0 || !"CASE_REFERENCE".equals(sourceType))) {
                throw new ServiceException(400, "参考案例版本只能用于有效的参考案例请求");
            }
            Long refVersionId = null;
            Map<String, Object> inputs = requirementInputs;
            if ("CASE_REFERENCE".equals(sourceType)) {
                if (refCaseId == null) {
                    throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
                }
                List<Long> versions = jdbcTemplate.query(
                        "SELECT c.current_version_id FROM design_case c JOIN design_case_version v "
                                + "ON v.id = c.current_version_id AND v.case_id = c.id AND v.deleted = FALSE "
                                + "WHERE c.id = ? AND c.deleted = FALSE AND c.publication_status = 'PUBLISHED'"
                                + (selectedCaseVersionId == null ? "" : " AND c.tenant_id=0 AND v.tenant_id=0 FOR SHARE OF c"),
                        (rs, i) -> rs.getLong("current_version_id"), refCaseId);
                if (versions.isEmpty()) {
                    if (selectedCaseVersionId != null) throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
                    throw exception(RESOURCE_FORBIDDEN);
                }
                refVersionId = versions.get(0);
                if (selectedCaseVersionId != null && !selectedCaseVersionId.equals(refVersionId)) {
                    throw exception(STATE_VERSION_CONFLICT);
                }
                if (selectedCaseVersionId != null) {
                    Boolean ready = jdbcTemplate.queryForObject("SELECT count(*) > 0 AND bool_and("
                                    + "ca.tenant_id=0 AND a.id IS NOT NULL AND a.tenant_id=0 AND a.upload_status IS NOT DISTINCT FROM 'ACCEPTED' "
                                    + "AND a.security_scan_status IS NOT DISTINCT FROM 'PASSED' "
                                    + "AND a.moderation_status IS NOT DISTINCT FROM 'PASSED') "
                                    + "FROM design_case_asset ca LEFT JOIN asset a ON a.id=ca.asset_id AND a.deleted=FALSE "
                                    + "WHERE ca.case_version_id=? AND ca.asset_role='FLOOR_PLAN' AND ca.deleted=FALSE",
                            Boolean.class, refVersionId);
                    if (!Boolean.TRUE.equals(ready)) throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
                }
                // T26-07 never turns reference dimensions with an unverified meaning into user inputs.
                // Old clients retain their existing default policy; the versioned chooser preserves entered values.
                inputs = withCaseDefaults(inputs, refVersionId, selectedCaseVersionId == null);
            } else if (!"SELF_UPLOAD".equals(sourceType)) {
                throw exception(RESOURCE_FORBIDDEN);
            }
            Map<String, Object> validatedInputs = BudgetInputs.validateRequirementInputs(inputs);
            // 需求五b：立面参考图资产必须归属当前用户、类型 DESIGN_REFERENCE 且已通过上传校验
            if (validatedInputs != null && validatedInputs.get("referenceAssetIds") instanceof List<?> refs && !refs.isEmpty()) {
                requireOwnedAcceptedAssets(userId, refs.stream().map(r -> Long.parseLong(String.valueOf(r))).toList(),
                        "DESIGN_REFERENCE", selectedCaseVersionId != null);
            }
            // 草图同口径快速失败：否则他人的 assetId 要等 runtime 下发图片时才被拒，任务已冻结点数再走退款
            if (sketchAssetId != null) {
                requireOwnedAcceptedAssets(userId, List.of(sketchAssetId), "USER_SKETCH", selectedCaseVersionId != null);
            }
            long projectId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_project (id, user_id, source_type, ref_case_id, ref_version_id, stage) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    projectId, userId, sourceType, refCaseId, refVersionId,
                    "ELEVATION".equals(line) ? "ELEVATION" : "FLAT");
            jdbcTemplate.update(
                    "INSERT INTO design_requirement_snapshot (id, project_id, inputs, sketch_asset_id) "
                            + "VALUES (?, ?, CAST(? AS jsonb), ?)",
                    IdWorker.getId(), projectId,
                    validatedInputs == null ? null
                            : toStringJson(validatedInputs),
                    sketchAssetId);
            if (selectedCaseVersionId != null) {
                ensureGenerationReference(getProject(projectId).orElseThrow());
            }
            log.info("[createProject][project={} user={} source={} ref={}]",
                    projectId, userId, sourceType, refCaseId);
            return projectId;
        });
    }

    /** 资产必须全部属于该用户、类型匹配且已通过上传校验（ACCEPTED）；重复 id 视为同一个 */
    private void requireOwnedAcceptedAssets(long userId, List<Long> assetIds, String assetType) {
        requireOwnedAcceptedAssets(userId, assetIds, assetType, false);
    }

    private void requireOwnedAcceptedAssets(long userId, List<Long> assetIds, String assetType, boolean businessDomainZero) {
        List<Long> distinct = assetIds.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
        Object[] params = new Object[distinct.size() + 2];
        for (int i = 0; i < distinct.size(); i++) params[i] = distinct.get(i);
        params[distinct.size()] = userId;
        params[distinct.size() + 1] = assetType;
        Integer owned = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset WHERE id IN (" + placeholders + ") "
                        + "AND owner_user_id=? AND asset_type=? "
                        + "AND upload_status='ACCEPTED' AND deleted=FALSE"
                        + (businessDomainZero ? " AND tenant_id=0" : ""), Integer.class, params);
        if (owned == null || owned != distinct.size()) throw exception(RESOURCE_FORBIDDEN);
    }

    public long createProjectWithKey(long userId, String source, Long caseId, Long sketchId,
                                     Map<String,Object> inputs, String key) {
        return createProjectWithKey(userId, source, caseId, sketchId, inputs, key, "FLAT");
    }

    public long createProjectWithKey(long userId, String source, Long caseId, Long sketchId,
                                     Map<String,Object> inputs, String key, String line) {
        return createProjectWithKey(userId, source, caseId, sketchId, inputs, key, line, null);
    }

    public long createProjectWithKey(long userId, String source, Long caseId, Long sketchId,
                                     Map<String,Object> inputs, String key, String line, Long selectedCaseVersionId) {
        return createProjectReceipt(userId, source, caseId, sketchId, inputs, key, line, selectedCaseVersionId).projectId();
    }

    /** 项目创建原子收据（P-10④）：重放判定=design_project_request 收据（收据锁事务内） */
    public record ProjectCreatedReceipt(long projectId, boolean replayed) {
    }

    public ProjectCreatedReceipt createProjectReceipt(long userId, String source, Long caseId, Long sketchId,
                                                      Map<String,Object> inputs, String key, String line) {
        return createProjectReceipt(userId, source, caseId, sketchId, inputs, key, line, null);
    }

    public ProjectCreatedReceipt createProjectReceipt(long userId, String source, Long caseId, Long sketchId,
                                                      Map<String,Object> inputs, String key, String line, Long selectedCaseVersionId) {
        if (key == null || key.isBlank()) {
            return new ProjectCreatedReceipt(createProject(userId, source, caseId, sketchId, inputs, line, selectedCaseVersionId), false);
        }
        if (key.length() > 128) throw new IllegalArgumentException("Request key too long");
        return txTemplate.execute(status -> {
            if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
            var intent = new java.util.LinkedHashMap<String,Object>();
            intent.put("source",source); intent.put("caseId",caseId); intent.put("sketchId",sketchId); intent.put("inputs",inputs); intent.put("line",line);
            // Keep historical fingerprints byte-for-byte compatible when the optional field is absent.
            if (selectedCaseVersionId != null) intent.put("refCaseVersionId", selectedCaseVersionId);
            String fingerprint;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
                fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(intent)));
            } catch (Exception error) { throw new IllegalArgumentException("Invalid project input",error); }
            jdbcTemplate.update("INSERT INTO design_project_request(user_id,request_key,request_hash) VALUES(?,?,?) ON CONFLICT DO NOTHING",userId,key,fingerprint);
            var receipt = jdbcTemplate.queryForMap("SELECT request_hash,project_id FROM design_project_request WHERE user_id=? AND request_key=? FOR UPDATE",userId,key);
            if (!fingerprint.equals(receipt.get("request_hash"))) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409,"同一请求号不能用于不同项目输入");
            if (receipt.get("project_id") != null) {
                return new ProjectCreatedReceipt(((Number)receipt.get("project_id")).longValue(), true);
            }
            long projectId = createProject(userId,source,caseId,sketchId,inputs,line,selectedCaseVersionId);
            jdbcTemplate.update("UPDATE design_project_request SET project_id=? WHERE user_id=? AND request_key=?",projectId,userId,key);
            return new ProjectCreatedReceipt(projectId, false);
        });
    }

    public String initialGenerationKey(long projectId, long userId) {
        var keys = jdbcTemplate.queryForList("SELECT request_key FROM design_project_request WHERE project_id=? AND user_id=?", String.class, projectId, userId);
        return keys.isEmpty() ? null : keys.get(0);
    }

    public Long selectedJobId(long projectId, String phase) {
        var ids = jdbcTemplate.queryForList("SELECT c.job_id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id "
                + "WHERE s.project_id=? AND s.stage=? AND s.active=TRUE AND s.deleted=FALSE AND c.deleted=FALSE",Long.class,projectId,phase);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * T15：参考案例模式下用户未显式给出的尺寸/层数，用案例版本值兜底——
     * 保证 runtime 提示词始终携带真实尺度；用户显式输入永远优先于案例默认。
     */
    private Map<String, Object> withCaseDefaults(Map<String, Object> inputs, long refVersionId, boolean includeDimensions) {
        var merged = new java.util.LinkedHashMap<String, Object>();
        if (inputs != null) merged.putAll(inputs);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT floor_count, style_code, face_width, depth FROM design_case_version "
                        + "WHERE id = ? AND deleted = FALSE", refVersionId);
        if (rows.isEmpty()) return merged;
        Map<String, Object> row = rows.get(0);
        putCaseDefault(merged, "floorCount", row.get("floor_count"));
        if (includeDimensions && row.get("face_width") != null) putCaseDefault(merged, "faceWidthM", String.valueOf(row.get("face_width")));
        if (includeDimensions && row.get("depth") != null) putCaseDefault(merged, "depthM", String.valueOf(row.get("depth")));
        putCaseDefault(merged, "styleCode", row.get("style_code"));
        return merged;
    }

    /**
     * 案例元数据属管理员数据但历史无范围约束：单键试放，越界值跳过兜底而非让建项目整体失败。
     */
    private void putCaseDefault(Map<String, Object> merged, String key, Object value) {
        if (value == null || merged.containsKey(key)) return;
        var probe = new java.util.LinkedHashMap<String, Object>();
        probe.put(key, value);
        try {
            BudgetInputs.validateRequirementInputs(probe);
        } catch (ServiceException ex) {
            log.warn("[withCaseDefaults][忽略越界案例默认值 key={} value={}]", key, value);
            return;
        }
        merged.put(key, value);
    }

    public Optional<ProjectSnapshot> getProject(long projectId, long userId) {
        return getProject(projectId).filter(p -> p.userId() == userId);
    }

    public Optional<AiJobPort.JobView> latestJob(ProjectSnapshot project) {
        // 阶段取线路而非"有无平面选择"：立面线路永远没有平面选择，平面线路选定后仍可重新生成平面
        String phase = "ELEVATION".equals(project.stage()) ? "ELEVATION" : "FLAT";
        return aiJobPort.latestJob(project.userId(), project.projectId(), phase);
    }

    public Long selectedFlatAssetId(long projectId) {
        List<Long> rows = jdbcTemplate.queryForList("SELECT c.asset_id FROM design_selection s JOIN design_candidate c "
                + "ON c.id=s.candidate_id AND c.project_id=s.project_id AND c.deleted=FALSE "
                + "WHERE s.project_id=? AND s.stage='FLAT' AND s.active=TRUE AND s.deleted=FALSE", Long.class, projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record BudgetInputSnapshot(Map<String, Object> inputs, List<String> requirementSnapshotIds) { }

    /** Read existing append-only requirement snapshots; result-bound reads never import later project edits. */
    public BudgetInputSnapshot getBudgetInputSnapshot(long userId, long projectId, Long resultVersionId) {
        requireOwner(userId, projectId);
        java.sql.Timestamp cutoff = null;
        Map<String, Object> resultConfig = Map.of();
        if (resultVersionId != null) {
            List<Map<String, Object>> versions = jdbcTemplate.queryForList(
                    "SELECT config_snapshot::text, create_time FROM design_result_version "
                            + "WHERE id = ? AND project_id = ? AND deleted = FALSE", resultVersionId, projectId);
            if (versions.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
            cutoff = (java.sql.Timestamp) versions.get(0).get("create_time");
            resultConfig = BudgetInputs.readJson((String) versions.get(0).get("config_snapshot"));
        }
        String sql = "SELECT id, inputs::text FROM design_requirement_snapshot WHERE project_id = ? AND deleted = FALSE"
                + (cutoff == null ? "" : " AND create_time <= ?") + " ORDER BY input_version ASC, id ASC";
        List<Map<String, Object>> snapshots = cutoff == null ? jdbcTemplate.queryForList(sql, projectId)
                : jdbcTemplate.queryForList(sql, projectId, cutoff);
        var inputs = new java.util.LinkedHashMap<String, Object>();
        var snapshotIds = new java.util.ArrayList<String>();
        for (var snapshot : snapshots) {
            var fields = BudgetInputs.fromSnapshot(BudgetInputs.readJson((String) snapshot.get("inputs")));
            if (!fields.isEmpty()) {
                inputs.putAll(fields);
                snapshotIds.add(String.valueOf(snapshot.get("id")));
            }
        }
        inputs.putAll(BudgetInputs.fromSnapshot(resultConfig)); // Explicit result values take precedence.
        return new BudgetInputSnapshot(java.util.Collections.unmodifiableMap(inputs), List.copyOf(snapshotIds));
    }

    public Optional<ProjectSnapshot> getProject(long projectId) {
        List<ProjectSnapshot> rows = jdbcTemplate.query(
                "SELECT id, user_id, source_type, ref_case_id, stage, status, name FROM design_project "
                        + "WHERE id = ? AND deleted = FALSE",
                (rs, i) -> new ProjectSnapshot(rs.getLong("id"), rs.getLong("user_id"),
                        rs.getString("source_type"),
                        rs.getObject("ref_case_id") == null ? null : rs.getLong("ref_case_id"),
                        rs.getString("stage"), rs.getString("status"), rs.getString("name")),
                projectId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 方案重命名（2026-10-05）：仅属主可改；名称去首尾空白后 1~60 字符。
     * 名称是展示层标签，不参与任何幂等键/请求指纹计算。
     */
    public String renameProject(long userId, long projectId, String name) {
        requireOwner(userId, projectId);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 60) throw exception(DESIGN_PROJECT_NAME_INVALID);
        jdbcTemplate.update("UPDATE design_project SET name = ?, update_time = now() "
                + "WHERE id = ? AND user_id = ? AND deleted = FALSE", trimmed, projectId, userId);
        return trimmed;
    }

    /**
     * 创建平面任务：参考案例在同事务校验生成参考授权并冻结快照；委托 AiJobPort 扣点建任务（幂等）。
     * 返回 (jobId, 是否新建)。
     */
    public record FlatJobCreated(long jobId, boolean created) {
    }

    public FlatJobCreated createFlatJob(long userId, long projectId, int count, String idempotencyKey) {
        return createFlatJob(userId, projectId, count, idempotencyKey, null);
    }

    public FlatJobCreated createFlatJob(long userId, long projectId, int count, String idempotencyKey,
                                        cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation) {
        return createFlatJob(userId, projectId, count, idempotencyKey, confirmation,
                cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("FLAT", null, null));
    }

    public FlatJobCreated createFlatJob(long userId, long projectId, int count, String idempotencyKey,
                                        cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                        cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options) {
        return createFlatJob(userId, projectId, count, idempotencyKey, confirmation, options, null);
    }

    public FlatJobCreated createFlatJob(long userId, long projectId, int count, String idempotencyKey,
                                        cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                        cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options,
                                        OrgBilling billing) {
        return txTemplate.execute(transaction -> {
        if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
        jdbcTemplate.queryForMap("SELECT id FROM design_project WHERE id=? FOR UPDATE",projectId);
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        // P-10①/②：属主校验后、全部可变前置检查（阶段/快照/报价）之前处理旧键收据——
        // 项目转 ELEVATION 后旧平面键重放仍返回原任务；首个平面任务以调用方传入键落库（不再替换为项目创建键）。
        String operationKey = idempotencyKey;
        if (idempotencyKey!=null && !idempotencyKey.isBlank()) {
            Long replayed = flatReplayReceipt(userId, projectId, idempotencyKey, count, options.resolution());
            if (replayed != null) return new FlatJobCreated(replayed, false);
        } else {
            // C 端「无显式键首次生成」兼容：保持 initialGenerationKey 语义不变
            if (aiJobPort.latestJob(userId, projectId, "FLAT").isEmpty()) {
                String creationKey = initialGenerationKey(projectId, userId);
                if (creationKey != null) operationKey = creationKey;
            }
        }
        if (!"FLAT".equals(project.stage())) {
            throw exception(DESIGN_STAGE_CONFLICT);
        }
        Map<String,Object> frozenInput = existingFlatReplayInput(userId, projectId, operationKey);
        // 平面生成已改为纯文生图：新任务不再校验或刷新案例生成参考授权；
        // 历史任务仍按自身已冻结的 input_snapshot 恢复，不做重建。
        long jobId;
        if (billing != null) {
            if (orgPointPort == null) {
                throw new IllegalStateException("公司池端口未装配，无法以桥侧计费创建任务");
            }
            String subject = "ai_flat";
            long total = orgPointPort.quoteCredits(subject, count);
            long unit = count <= 0 ? total : total / count;
            jobId = aiJobPort.createFlatJobWithOrgCharge(userId, count, operationKey,
                    String.valueOf(projectId), unit, total, options.resolution());
            bindOrgCharge(billing, subject, jobId, count);
        } else {
            jobId = aiJobPort.createFlatJob(userId, count, operationKey, String.valueOf(projectId), confirmation, options);
        }
        boolean created = aiJobPort.freezeInput(jobId,
                runtimeInput(project, "FLAT", null, options, count, null, false, frozenInput));
        jdbcTemplate.update(
                "UPDATE design_project SET update_time = now() WHERE id = ?", projectId);
        return new FlatJobCreated(jobId, created);
        });
    }

    /** 公司池预扣 + 计费归属绑定（与任务创建同事务；D6 v2 §2.1）。 */
    private void bindOrgCharge(OrgBilling billing, String subject, long jobId, int count) {
        String bizKey = "design:job:" + jobId + ":" + subject;
        long operationId = orgPointPort.reserve(new cn.iocoder.yudao.module.franchise.api.OrgPointPort.ReserveCommand(
                bizKey, billing.orgId(), billing.actorUserId(), billing.actorPostId(),
                subject, Math.max(count, 1), "ai_job", jobId));
        orgPointPort.bindOperation("BRIDGE", billing.orgId(), billing.actorUserId(),
                billing.actorPostId(), "ai_job", jobId, subject, operationId, bizKey);
    }

    /**
     * 平面任务旧键重放收据（P-10②）：命中且参数一致返回 jobId；不一致=键重用拒绝。
     * request_resolution 为 NULL 的存量任务跳过该比较（历史默认 2K）。
     */
    private Long flatReplayReceipt(long userId, long projectId, String key, int count, String resolution) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, requested_count, request_resolution, project_ref, phase FROM ai_job "
                        + "WHERE user_id=? AND idempotency_key=? AND deleted=FALSE", userId, key);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        Object storedResolution = row.get("request_resolution");
        if (!String.valueOf(projectId).equals(row.get("project_ref")) || !"FLAT".equals(row.get("phase"))
                || count != ((Number) row.get("requested_count")).intValue()
                || storedResolution != null && !java.util.Objects.equals(resolution, storedResolution)) {
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        }
        return ((Number) row.get("id")).longValue();
    }

    /**
     * 立面任务旧键重放收据（P-10②）：与调整请求共享键空间（唯一索引=user+key），
     * 该键已被调整请求占用=跨操作冲突（R13↔R18 同键拒绝）。
     */
    private Long elevationReplayReceipt(long userId, long projectId, String key, int count, String resolution) {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, requested_count, request_resolution, project_ref, phase FROM ai_job "
                        + "WHERE user_id=? AND idempotency_key=? AND deleted=FALSE", userId, key);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        Object storedResolution = row.get("request_resolution");
        if (!"ELEVATION".equals(row.get("phase")) || !String.valueOf(projectId).equals(row.get("project_ref"))
                || count != ((Number) row.get("requested_count")).intValue()
                || storedResolution != null && !java.util.Objects.equals(resolution, storedResolution)) {
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        }
        long jobId = ((Number) row.get("id")).longValue();
        Integer revisionOwned = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM design_revision_request WHERE new_job_id=? AND deleted=FALSE", Integer.class, jobId);
        if (revisionOwned != null && revisionOwned > 0) {
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        }
        return jobId;
    }

    /** Called under the owned project row lock. The job port still validates key/count/resolution before replay. */
    private Map<String,Object> existingFlatReplayInput(long userId, long projectId, String key) {
        if (key == null || key.isBlank()) return null;
        var rows = jdbcTemplate.queryForList("SELECT input_snapshot::text FROM ai_job WHERE user_id=? "
                        + "AND project_ref=? AND phase='FLAT' AND idempotency_key=? AND tenant_id=0 AND deleted=FALSE",
                String.class, userId, String.valueOf(projectId), key);
        if (rows.isEmpty() || rows.get(0) == null) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(rows.get(0), Map.class);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("INVALID_AI_INPUT_SNAPSHOT");
        }
    }

    /** 校验项目冻结版本的全部平面图及授权属于固定业务域 0，再刷新本次准入的授权证据。 */
    private void ensureGenerationReference(ProjectSnapshot project) {
        // These are raw JDBC queries: the admin session's tenant and MyBatis interceptors do not scope them.
        // Reject a mixed-domain reference as a whole instead of silently using only some of its floor plans.
        Boolean sameDomain = jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM design_project p "
                        + "JOIN design_case_version v ON v.id=p.ref_version_id AND v.case_id=p.ref_case_id "
                        + "JOIN design_case c ON c.id=v.case_id WHERE p.id=? AND p.tenant_id=0 AND v.tenant_id=0 AND c.tenant_id=0 "
                        + "AND p.deleted=FALSE AND v.deleted=FALSE AND c.deleted=FALSE "
                        + "AND NOT EXISTS (SELECT 1 FROM design_case_asset ca LEFT JOIN asset a ON a.id=ca.asset_id "
                        + "AND a.tenant_id=0 AND a.deleted=FALSE WHERE ca.case_version_id=v.id "
                        + "AND ca.asset_role='FLOOR_PLAN' AND ca.deleted=FALSE AND (ca.tenant_id<>0 OR a.id IS NULL)))",
                Boolean.class, project.projectId());
        if (!Boolean.TRUE.equals(sameDomain)) throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
        List<Long> planAssets = jdbcTemplate.queryForList(
                "SELECT a.asset_id FROM design_case_asset a "
                        + "JOIN design_project p ON p.ref_version_id = a.case_version_id "
                        + "WHERE p.id = ? AND p.tenant_id=0 AND a.tenant_id=0 AND a.asset_role = 'FLOOR_PLAN' AND a.deleted = FALSE",
                Long.class, project.projectId());
        if (planAssets.isEmpty()) {
            throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
        }
        Long grantId = null;
        Long rightsVersion = null;
        var snapshot = new java.util.ArrayList<Map<String, Object>>();
        for (Long assetId : planAssets) {
            List<Map<String, Object>> grants = jdbcTemplate.queryForList(
                    "SELECT id, rights_version FROM asset_rights_grant WHERE asset_id = ? AND tenant_id=0 "
                            + "AND scope = 'GENERATION_REFERENCE' AND status = 'ACTIVE' "
                            + "AND effective_at <= now() "
                            + "AND (expires_at IS NULL OR expires_at > now()) AND deleted = FALSE",
                    assetId);
            if (grants.isEmpty()) {
                throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
            }
            long gId = ((Number) grants.get(0).get("id")).longValue();
            long gVer = ((Number) grants.get(0).get("rights_version")).longValue();
            if (grantId == null) {
                grantId = gId;
                rightsVersion = gVer;
            }
            // 审查遗留：逐资产冻结完整快照，而非只记首个 grant
            snapshot.add(Map.of("assetId", assetId, "grantId", gId, "rightsVersion", gVer));
        }
        jdbcTemplate.update(
                "UPDATE design_project SET rights_grant_id = ?, rights_version = ?, "
                        + "rights_snapshot = CAST(? AS jsonb), update_time = now() WHERE id = ?",
                grantId, rightsVersion, toSnapshotJson(snapshot), project.projectId());
        log.info("[ensureGenerationReference][project={} 冻结 grant={} v{} 快照 {} 项]",
                project.projectId(), grantId, rightsVersion, snapshot.size());
    }

    /** 任务终态后晋升 ACCEPTED 结果为资产+候选，仅返回指定任务的候选。 */
    public List<CandidateRow> promoteCandidates(long userId, long projectId, long jobId) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        AiJobPort.JobView job = aiJobPort.getJob(jobId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (job.userId() != userId || !String.valueOf(projectId).equals(job.projectRef())
                || !("FLAT".equals(job.phase()) || "ELEVATION".equals(job.phase()))) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        String status = job.status();
        if (!isTerminal(status)) {
            throw exception(DESIGN_JOB_NOT_TERMINAL);
        }
        promoteAcceptedResults(userId, projectId, jobId);
        return listCandidates(projectId).stream().filter(candidate -> candidate.jobId()==jobId).toList();
    }

    /**
     * 驱动器系统触发晋升（C2）：结算落库后即晋升，不等前端查询。
     * userId/projectId 由驱动器从 ai_job 行本身读出，无会话属主校验；
     * 与拉式路径共用晋升循环，uk_design_candidate_job_slot 保证双路径并发幂等。
     */
    public void promoteCandidatesForSettledJob(long userId, long projectId, long jobId) {
        promoteAcceptedResults(userId, projectId, jobId);
    }

    private void promoteAcceptedResults(long userId, long projectId, long jobId) {
        requireOwner(userId,projectId);
        requireScopedJob(userId,projectId,jobId,null);
        for (AiJobPort.CandidateView result : aiJobPort.listAcceptedResults(jobId)) {
            if (!result.objectKey().startsWith("accepted/ai/")) throw new IllegalStateException("LEGACY_AI_OUTPUT_REQUIRES_REVALIDATION");
            byte[] bytes=quarantineObjectPort.getObject(result.objectKey());
            try {
                if (!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(result.sha256()))
                    throw new IllegalStateException("ACCEPTED_OBJECT_DIGEST");
            } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            final int size=bytes.length;
            try {
                txTemplate.execute(s -> {
                    var writable=jdbcTemplate.queryForList("SELECT id FROM design_project WHERE id=? AND user_id=? AND deleted=FALSE FOR UPDATE",projectId,userId);
                    if (writable.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
                    // 使用校验后独立的 accepted/ai 对象晋升用户资产，并校对实际摘要与大小。
                    long assetId = registerPromotedAsset(userId, result, size);
                    jdbcTemplate.update(
                            "INSERT INTO design_candidate (id, project_id, job_id, slot_no, asset_id, ai_result_id) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)",
                            IdWorker.getId(), projectId, jobId, result.slotNo(), assetId, result.resultId());
                    return null;
                });
            } catch (DuplicateKeyException e) {
                // 已晋升：幂等
            }
        }
    }

    private long registerPromotedAsset(long userId, AiJobPort.CandidateView result, long size) {
        // object key → asset（P3A 资产域），upload 完成态直接 ACCEPTED（Core 校验器已把关）
        long assetId = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                        + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status, "
                        + "stored_sha256, stored_size, width, height) "
                        + "VALUES (?, ?, ?, 'AI_OUTPUT', 'AI_GENERATED', ?, ?, ?, 'ACCEPTED', 'PASSED', 'PASSED', ?, ?, ?, ?) "
                        + "ON CONFLICT (object_key) DO NOTHING",
                assetId, result.objectKey(), userId, result.sha256(), result.mimeType(),
                size, result.sha256(), size, result.width(), result.height());
        Long existing = jdbcTemplate.queryForObject(
                "SELECT id FROM asset WHERE object_key = ?", Long.class, result.objectKey());
        return existing == null ? assetId : existing;
    }

    public List<CandidateRow> listCandidates(long projectId) {
        return jdbcTemplate.query(
                "SELECT id, job_id, slot_no, asset_id FROM design_candidate "
                        + "WHERE project_id = ? AND deleted = FALSE ORDER BY job_id, slot_no",
                (rs, i) -> new CandidateRow(rs.getLong("id"), rs.getLong("job_id"),
                        rs.getInt("slot_no"), rs.getLong("asset_id")),
                projectId);
    }

    /** 选定平面候选：候选属本人项目 + 任务终态 + 幂等（重复选择返回既有选择） */
    public long selectFlatCandidate(long userId, long projectId, long candidateId) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return txTemplate.execute(status -> {
            lockOwnedProject(userId,projectId);
            validateSelectionCandidate(userId,projectId,candidateId,"FLAT");
            // 与立面选择对称（审查遗留项）：同候选幂等返回；不同候选=轮换（旧选择失效，可继续生成立面）
            List<Long> actives = jdbcTemplate.query(
                    "SELECT id, candidate_id FROM design_selection WHERE project_id = ? AND stage = 'FLAT' "
                            + "AND active = TRUE AND deleted = FALSE",
                    (rs, i) -> rs.getLong("id"), projectId);
            if (!actives.isEmpty()) {
                Long activeCandidate = jdbcTemplate.queryForObject(
                        "SELECT candidate_id FROM design_selection WHERE id = ?", Long.class, actives.get(0));
                if (activeCandidate != null && activeCandidate == candidateId) {
                    return actives.get(0); // 同候选：幂等
                }
                jdbcTemplate.update(
                        "UPDATE design_selection SET active = FALSE, update_time = now() WHERE id = ?",
                        actives.get(0));
            }
            long selectionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_selection (id, project_id, stage, candidate_id, selected_by) "
                            + "VALUES (?, ?, 'FLAT', ?, ?)",
                    selectionId, projectId, candidateId, userId);
            jdbcTemplate.update(
                    "UPDATE design_project SET update_time = now() WHERE id = ?", projectId);
            log.info("[selectFlatCandidate][project={} candidate={}]", projectId, candidateId);
            return selectionId;
        });
    }

    /** 用户正式确认平面；旧选择不自动迁移，重复确认在项目锁内返回同一版本。
     *  立面线路的历史平面选择仍走原选择语义，不创建新的平面交付。（旧接口兼容：映射收据） */
    public ResultVersionCreated confirmFlatCandidate(long userId, long projectId, long candidateId) {
        SelectionReceipt receipt = confirmFlatCandidateReceipt(userId, projectId, candidateId);
        return receipt.versionId() == null ? null : new ResultVersionCreated(receipt.versionId(), receipt.version());
    }

    /** 选定收据（P-10④）：重放判定=当前活动候选（项目锁内）；ELEVATION 阶段 selectionSaved=true 且无版本 */
    public record SelectionReceipt(long selectionId, Long versionId, Long version,
                                   boolean selectionSaved, boolean replayed) {
    }

    public SelectionReceipt confirmFlatCandidateReceipt(long userId, long projectId, long candidateId) {
        return txTemplate.execute(status -> {
            lockOwnedProject(userId, projectId);
            Long active = jdbcSelectActive(projectId, "FLAT");
            boolean sameCandidate = false;
            if (active != null) {
                Long activeCandidate = jdbcTemplate.queryForObject(
                        "SELECT candidate_id FROM design_selection WHERE id = ?", Long.class, active);
                sameCandidate = activeCandidate != null && activeCandidate == candidateId;
            }
            long selectionId = selectFlatCandidate(userId, projectId, candidateId);
            var project = getProject(projectId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
            if (!"FLAT".equals(project.stage())) {
                return new SelectionReceipt(selectionId, null, null, true, sameCandidate);
            }
            var existing = jdbcTemplate.queryForList(
                    "SELECT id, version FROM design_result_version WHERE project_id=? "
                            + "AND flat_selection_id=? AND result_line='FLAT' AND deleted=FALSE",
                    projectId, selectionId);
            if (!existing.isEmpty()) {
                return new SelectionReceipt(selectionId,
                        ((Number) existing.get(0).get("id")).longValue(),
                        ((Number) existing.get(0).get("version")).longValue(), true, sameCandidate);
            }
            long jobId = validateSelectionCandidate(userId, projectId, candidateId, "FLAT");
            Long maximum = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(version),0) FROM design_result_version WHERE project_id=?",
                    Long.class, projectId);
            long version = maximum + 1, versionId = IdWorker.getId();
            jdbcTemplate.update("INSERT INTO design_result_version "
                            + "(id,project_id,version,flat_selection_id,flat_candidate_ids,config_snapshot,result_line) "
                            + "VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),'FLAT')",
                    versionId, projectId, version, selectionId, toJsonList(List.of(candidateId)),
                    versionConfig(jobInput(jobId), jobId));
            jdbcTemplate.update("UPDATE design_result_version SET superseded=TRUE,update_time=now() "
                    + "WHERE project_id=? AND version<? AND deleted=FALSE", projectId, version);
            return new SelectionReceipt(selectionId, versionId, version, true, sameCandidate);
        });
    }

    // ========== P6：立面、最终版本、调整 ==========

    /** 创建立面任务：必须已选定平面候选；立面配置写入需求快照 v2 */
    public FlatJobCreated createElevationJob(long userId, long projectId, int count, String idempotencyKey,
                                             Map<String, Object> elevationConfig) {
        return createElevationJob(userId, projectId, count, idempotencyKey, elevationConfig, null);
    }

    public FlatJobCreated createElevationJob(long userId, long projectId, int count, String idempotencyKey,
                                             Map<String, Object> elevationConfig,
                                             cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation) {
        return createElevationJob(userId, projectId, count, idempotencyKey, elevationConfig, confirmation,
                cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("ELEVATION", null, null));
    }

    public FlatJobCreated createElevationJob(long userId, long projectId, int count, String idempotencyKey,
                                             Map<String, Object> elevationConfig,
                                             cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                             cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options) {
        return createElevationJob(userId,projectId,count,idempotencyKey,elevationConfig,confirmation,options,null,false,null);
    }

    /** 桥侧公司池入口（D6 v2）：billing 非空即走公司池；null 保持 App 个人池。 */
    public FlatJobCreated createElevationJob(long userId, long projectId, int count, String idempotencyKey,
                                             Map<String, Object> elevationConfig,
                                             cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                             cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options,
                                             OrgBilling billing) {
        return createElevationJob(userId, projectId, count, idempotencyKey, elevationConfig,
                confirmation, options, null, false, billing);
    }

    private FlatJobCreated createElevationJob(long userId,long projectId,int count,String idempotencyKey,
                                              Map<String,Object> elevationConfig,
                                              cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                              cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options,Long sourceSelection,boolean completeRequirements,
                                              OrgBilling billing) {
        return txTemplate.execute(transaction -> {
        if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
        jdbcTemplate.queryForMap("SELECT id FROM design_project WHERE id=? FOR UPDATE",projectId);
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        // P-10②：重放先于全部可变前置检查（平面选择读取/引用资产校验/快照重建/报价）
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Long replayed = elevationReplayReceipt(userId, projectId, idempotencyKey, count, options.resolution());
            if (replayed != null) return new FlatJobCreated(replayed, false);
        }
        // 需求①拆分：立面不再以「已选平面」为前置——平面选择可选；有选定时仍校验其链路（调整链沿版本快照），
        // 纯立面项目（ELEVATION 线路）无平面参考，走零参考文生图（案例授权立面图可作风格参考，见 runtimeInput）。
        Long flatSelection = sourceSelection==null ? jdbcSelectActive(projectId, "FLAT") : sourceSelection;
        // 线路约束：立面线路项目随时可生成立面；平面线路项目只有已有平面选择（存量"先平面后立面"）才允许进入立面，
        // 否则平面线路会被 API 直接带进立面并把 stage 翻成 ELEVATION
        if (flatSelection == null && !"ELEVATION".equals(project.stage())) {
            throw exception(DESIGN_STAGE_CONFLICT);
        }
        Map<String, Object> validatedConfig = BudgetInputs.validateRequirementInputs(elevationConfig);
        if (validatedConfig != null && validatedConfig.get("referenceAssetIds") instanceof List<?> refs && !refs.isEmpty()) {
            requireOwnedAcceptedAssets(userId, refs.stream().map(r -> Long.parseLong(String.valueOf(r))).toList(), "DESIGN_REFERENCE", true);
        }
        if (flatSelection != null) {
            var flatJobs=jdbcTemplate.queryForList("SELECT c.job_id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id WHERE s.id=? AND s.project_id=? AND s.stage='FLAT' AND s.deleted=FALSE AND c.deleted=FALSE",Long.class,flatSelection,projectId);
            Long flatJobId=flatJobs.isEmpty()?null:flatJobs.get(0);
            if (flatJobId==null) throw exception(DESIGN_STAGE_CONFLICT);
            requireScopedJob(userId,projectId,flatJobId,"FLAT");
        }
        // 方向自选（22号文档 7.2）：不再强制对齐已选平面任务的方向，由立面配置请求给定
        var alignedOptions=cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize(
                "ELEVATION",options.resolution(),options.orientation());
        long jobId;
        if (billing != null) {
            if (orgPointPort == null) {
                throw new IllegalStateException("公司池端口未装配，无法以桥侧计费创建任务");
            }
            String subject = "ai_elevation";
            long total = orgPointPort.quoteCredits(subject, count);
            long unit = count <= 0 ? total : total / count;
            jobId = aiJobPort.createElevationJobWithOrgCharge(userId, count, idempotencyKey,
                    String.valueOf(projectId), unit, total, alignedOptions.resolution());
            bindOrgCharge(billing, subject, jobId, count);
        } else {
            jobId = aiJobPort.createElevationJob(userId, count, idempotencyKey, String.valueOf(projectId), confirmation, alignedOptions);
        }
        var frozen=runtimeInput(project,"ELEVATION",validatedConfig,alignedOptions,count,flatSelection,completeRequirements);
        frozen.put("requestedImageOptions",Map.of("resolution",options.resolution(),"orientation",options.orientation()));
        frozen.put("requestConfig",validatedConfig==null?Map.of():validatedConfig);
        if (!aiJobPort.freezeInput(jobId,frozen)) return new FlatJobCreated(jobId,false);
        jdbcTemplate.update(
                "INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs) "
                        + "SELECT ?, ?, COALESCE(MAX(input_version), 0) + 1, CAST(? AS jsonb) "
                        + "FROM design_requirement_snapshot WHERE project_id = ? AND deleted = FALSE",
                IdWorker.getId(), projectId,
                toStringJson(validatedConfig == null ? Map.of() : validatedConfig), projectId);
        jdbcTemplate.update(
                "UPDATE design_project SET stage = 'ELEVATION', update_time = now() WHERE id = ?", projectId);
        return new FlatJobCreated(jobId, true);
        });
    }

    private Long jdbcSelectActive(long projectId, String stage) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT id FROM design_selection WHERE project_id = ? AND stage = ? "
                        + "AND active = TRUE AND deleted = FALSE",
                (rs, i) -> rs.getLong("id"), projectId, stage);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 选定立面候选：生成不可变最终结果版本（首次 version=1，调整链递增） */
    public record ResultVersionCreated(long versionId, long version) {
    }

    public ResultVersionCreated selectElevationCandidate(long userId, long projectId, long candidateId) {
        SelectionReceipt receipt = selectElevationCandidateReceipt(userId, projectId, candidateId);
        return receipt.versionId() == null ? null : new ResultVersionCreated(receipt.versionId(), receipt.version());
    }

    public SelectionReceipt selectElevationCandidateReceipt(long userId, long projectId, long candidateId) {
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        return txTemplate.execute(status -> {
            lockOwnedProject(userId,projectId);
            long jobId=validateSelectionCandidate(userId,projectId,candidateId,"ELEVATION");
            // 幂等：重复选择同一候选返回既有版本；不同候选=调整轮换（旧 selection 失效，生成新版本）
            Long activeSelection = jdbcSelectActive(projectId, "ELEVATION");
            if (activeSelection != null) {
                Long activeCandidate = jdbcTemplate.queryForObject(
                        "SELECT candidate_id FROM design_selection WHERE id = ?", Long.class, activeSelection);
                if (activeCandidate != null && activeCandidate == candidateId) {
                    Long versionId = jdbcTemplate.queryForObject(
                            "SELECT id FROM design_result_version WHERE project_id = ? AND superseded = FALSE "
                                    + "ORDER BY version DESC LIMIT 1", Long.class, projectId);
                    long v = versionId == null ? -1L : versionId;
                    long ver = v < 0 ? -1 : jdbcTemplate.queryForObject(
                            "SELECT version FROM design_result_version WHERE id = ?", Long.class, v);
                    return new SelectionReceipt(activeSelection, v < 0 ? null : v, ver < 0 ? null : ver, true, true);
                }
                jdbcTemplate.update(
                        "UPDATE design_selection SET active = FALSE, update_time = now() WHERE id = ?",
                        activeSelection);
            }
            // Source and configuration belong to the selected elevation job, even if the
            // current flat selection or a newer generation changed after this job started.
            Map<String,Object> input=jobInput(jobId);
            // 需求①拆分：纯立面任务无平面参考，返回 null，落库 flat_selection_id=NULL
            Long flatSelection=sourceFlatSelection(userId,projectId,jobId,input);
            List<Long> flatCandidates = flatSelection == null ? List.of() : jdbcTemplate.queryForList(
                    "SELECT candidate_id FROM design_selection WHERE id = ? AND project_id = ? AND stage='FLAT' AND deleted=FALSE",
                    Long.class, flatSelection, projectId);
            long selectionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_selection (id, project_id, stage, candidate_id, selected_by) "
                            + "VALUES (?, ?, 'ELEVATION', ?, ?)",
                    selectionId, projectId, candidateId, userId);
            jdbcTemplate.update(
                    "UPDATE design_project SET update_time = now() WHERE id = ?", projectId);
            Integer maxVersion = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(version), 0) FROM design_result_version WHERE project_id = ?",
                    Integer.class, projectId);
            long version = (maxVersion == null ? 0 : maxVersion) + 1;
            long versionId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_result_version (id, project_id, version, flat_selection_id, "
                            + "elevation_selection_id, flat_candidate_ids, elevation_candidate_id, config_snapshot) "
                            + "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb))",
                    versionId, projectId, version, flatSelection, selectionId,
                    toJsonList(flatCandidates), candidateId, versionConfig(input,jobId));
            // 旧版本标记 superseded（不可变：内容不改，只改活跃标记）
            if (version > 1) {
                jdbcTemplate.update(
                        "UPDATE design_result_version SET superseded = TRUE, update_time = now() "
                                + "WHERE project_id = ? AND version < ?", projectId, version);
                // 完成挂起的调整请求
                jdbcTemplate.update(
                        "UPDATE design_revision_request SET status = 'COMPLETED', new_version_id = ?, "
                                + "update_time = now() WHERE project_id = ? AND status = 'PENDING'",
                        versionId, projectId);
            }
            log.info("[selectElevationCandidateReceipt][project={} v{} replayed=false]", projectId, version);
            return new SelectionReceipt(selectionId, versionId, version, true, false);
        });
    }

    /** 版本列表（旧版本只读可查，最新在前） */
    public List<Map<String, Object>> listResultVersions(long userId, long projectId) {
        requireOwner(userId, projectId);
        return jdbcTemplate.queryForList(
                "SELECT v.id, v.version, v.flat_selection_id, v.elevation_selection_id, "
                        + "v.flat_candidate_ids::text, v.elevation_candidate_id, v.config_snapshot::text, "
                        + "v.superseded, v.create_time, fc.asset_id AS selected_flat_asset_id, "
                        + "ec.asset_id AS selected_elevation_asset_id, "
                        + "COALESCE(NULLIF(fj.input_snapshot->'imageOptions','null'::jsonb), CASE WHEN v.result_line='FLAT' "
                        + "THEN v.config_snapshot->'imageOptions' END)::text AS flat_image_options, "
                        + "COALESCE(NULLIF(ej.input_snapshot->'imageOptions','null'::jsonb),v.config_snapshot->'imageOptions')::text AS elevation_image_options, "
                        + "fa.width AS flat_width,fa.height AS flat_height,ea.width AS elevation_width,ea.height AS elevation_height "
                        + "FROM design_result_version v JOIN design_project p ON p.id=v.project_id "
                        + "LEFT JOIN design_selection fs ON fs.id = v.flat_selection_id "
                        + "AND fs.project_id = v.project_id AND fs.deleted = FALSE "
                        + "LEFT JOIN design_candidate fc ON fc.id = fs.candidate_id "
                        + "AND fc.project_id = v.project_id AND fc.deleted = FALSE "
                        + "LEFT JOIN design_candidate ec ON ec.id = v.elevation_candidate_id "
                        + "AND ec.project_id = v.project_id AND ec.deleted = FALSE "
                        + "LEFT JOIN ai_job fj ON fj.id=fc.job_id AND fj.project_ref=v.project_id::text AND fj.user_id=p.user_id AND fj.phase='FLAT' AND fj.deleted=FALSE "
                        + "LEFT JOIN ai_job ej ON ej.id=ec.job_id AND ej.project_ref=v.project_id::text AND ej.user_id=p.user_id AND ej.phase='ELEVATION' AND ej.deleted=FALSE "
                        + "LEFT JOIN asset fa ON fa.id=fc.asset_id AND fa.owner_user_id=p.user_id AND fa.deleted=FALSE "
                        + "LEFT JOIN asset ea ON ea.id=ec.asset_id AND ea.owner_user_id=p.user_id AND ea.deleted=FALSE "
                        + "WHERE v.project_id = ? AND v.deleted = FALSE ORDER BY v.version DESC",
                projectId);
    }

    /** 调整请求：基于当前最新版本发起新立面任务（调整链）；新版本生成后自动 COMPLETED */
    public record RevisionCreated(long requestId, long newJobId) {
    }

    public RevisionCreated createRevisionRequest(long userId, long projectId, String reason,
                                                 Map<String, Object> configUpdates, int count,
                                                 String idempotencyKey) {
        return createRevisionRequest(userId, projectId, reason, configUpdates, count, idempotencyKey, null);
    }

    public RevisionCreated createRevisionRequest(long userId, long projectId, String reason,
                                                 Map<String, Object> configUpdates, int count,
                                                 String idempotencyKey,
                                                 cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation) {
        return createRevisionRequest(userId, projectId, reason, configUpdates, count, idempotencyKey,
                confirmation, null, null);
    }

    public RevisionCreated createRevisionRequest(long userId, long projectId, String reason,
                                                 Map<String, Object> configUpdates, int count,
                                                 String idempotencyKey,
                                                 cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                                 String resolution, String orientation) {
        RevisionReceipt receipt = createRevisionRequestReceipt(userId, projectId, reason, configUpdates, count,
                idempotencyKey, confirmation, resolution, orientation);
        return new RevisionCreated(receipt.requestId(), receipt.newJobId());
    }

    /** 调整请求收据（P-10②④）：重放先于全部可变前置检查（最新版本读取/继承配置重建/报价） */
    public record RevisionReceipt(long requestId, long newJobId, boolean replayed) {
    }

    public RevisionReceipt createRevisionRequestReceipt(long userId, long projectId, String reason,
                                                        Map<String, Object> configUpdates, int count,
                                                        String idempotencyKey,
                                                        cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                                        String resolution, String orientation) {
        return createRevisionRequestReceipt(userId, projectId, reason, configUpdates, count,
                idempotencyKey, confirmation, resolution, orientation, null);
    }

    /** 桥侧修订入口（D6 v2）：billing 非空走公司池。 */
    public RevisionReceipt createRevisionRequestReceipt(long userId, long projectId, String reason,
                                                        Map<String, Object> configUpdates, int count,
                                                        String idempotencyKey,
                                                        cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmation,
                                                        String resolution, String orientation,
                                                        OrgBilling billing) {
        return txTemplate.execute(transaction -> {
        if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
        jdbcTemplate.queryForMap("SELECT id FROM design_project WHERE id=? FOR UPDATE",projectId);
        ProjectSnapshot project = getProject(projectId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (project.userId() != userId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        // P-10②：属主校验后立即查旧键收据——最新版本演进不影响重放（省略的 resolution/orientation 按首次解析规格冻结）
        RevisionReceipt replayed = revisionReplayReceipt(userId, projectId, idempotencyKey, reason, configUpdates, count, resolution);
        if (replayed != null) return replayed;
        List<Long> versionRows = jdbcTemplate.query(
                "SELECT id FROM design_result_version WHERE project_id = ? AND deleted = FALSE "
                        + "AND result_line IS DISTINCT FROM 'FLAT' ORDER BY version DESC LIMIT 1",
                (rs, i) -> rs.getLong("id"), projectId);
        if (versionRows.isEmpty()) {
            throw exception(DESIGN_STAGE_CONFLICT); // 尚无最终版本，无从调整（先拒后建，不扣点）
        }
        Long latestVersionId = versionRows.get(0);
        var sourceVersion=jdbcTemplate.queryForMap("SELECT flat_selection_id,config_snapshot::text FROM design_result_version WHERE id=? AND project_id=? AND deleted=FALSE",latestVersionId,projectId);
        var inherited=new java.util.LinkedHashMap<String,Object>();
        Object rawConfig=sourceVersion.get("config_snapshot");
        if (rawConfig!=null) {
            try { inherited.putAll(new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawConfig.toString(),Map.class)); }
            catch (java.io.IOException error) { throw new IllegalStateException("INVALID_VERSION_CONFIG",error); }
        }
        inherited.remove("imageOptions");inherited.remove("originJobId");inherited.remove("sourceFlat");
        if (configUpdates!=null) inherited.putAll(configUpdates);
        // 调整 = 基于最近版本冻结配置的新一轮立面任务；平面参考沿版本快照（纯立面项目保持零参考，需求①拆分）
        var options = revisionOptions(projectId, resolution, orientation);
        Object rawFlatSelection = sourceVersion.get("flat_selection_id");
        var job = createElevationJob(userId, projectId, count, idempotencyKey, inherited, confirmation, options,
                rawFlatSelection == null ? null : ((Number) rawFlatSelection).longValue(), true, billing);
        var old = jdbcTemplate.queryForList("SELECT id,reason,config_updates::text FROM design_revision_request WHERE new_job_id=? ORDER BY id LIMIT 1",job.jobId());
        if (!old.isEmpty()) {
            String expected=configUpdates==null?null:toStringJson(configUpdates);
            try {
                var mapper=new com.fasterxml.jackson.databind.ObjectMapper();Object existing=old.get(0).get("config_updates");
                if (!java.util.Objects.equals(reason,old.get(0).get("reason")) || !java.util.Objects.equals(existing==null?null:mapper.readTree(existing.toString()),expected==null?null:mapper.readTree(expected)))
                    throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
            } catch (java.io.IOException error) { throw new IllegalStateException("INVALID_REVISION_CONFIG",error); }
            return new RevisionReceipt(((Number)old.get(0).get("id")).longValue(),job.jobId(),true);
        }
        long requestId = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO design_revision_request (id, project_id, from_version_id, reason, "
                        + "config_updates, new_job_id) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)",
                requestId, projectId, latestVersionId, reason,
                configUpdates == null ? null : toStringJson(configUpdates), job.jobId());
        return new RevisionReceipt(requestId, job.jobId(), false);
        });
    }

    /**
     * 调整请求旧键重放收据（P-10②）：收据比较=完整规范化请求（reason/configUpdates/count，
     * resolution 显式给定且存量非空时才比较——省略值不重新继承最新版本）；
     * 同键被普通立面任务占用=跨操作冲突（R13↔R18 共享键空间）。
     */
    private RevisionReceipt revisionReplayReceipt(long userId, long projectId, String key, String reason,
                                                  Map<String,Object> configUpdates, int count, String resolution) {
        if (key == null || key.isBlank()) return null;
        var rows = jdbcTemplate.queryForList(
                "SELECT j.id AS job_id, j.requested_count, j.request_resolution, j.project_ref, j.phase, "
                        + "r.id AS request_id, r.reason, r.config_updates::text AS config_updates "
                        + "FROM ai_job j JOIN design_revision_request r ON r.new_job_id = j.id AND r.deleted=FALSE "
                        + "WHERE j.user_id=? AND j.idempotency_key=? AND j.deleted=FALSE", userId, key);
        if (rows.isEmpty()) {
            var plain = jdbcTemplate.queryForList(
                    "SELECT id FROM ai_job WHERE user_id=? AND idempotency_key=? AND deleted=FALSE", userId, key);
            if (!plain.isEmpty()) {
                // 键已被普通立面任务占用（R13）：R18 用同键=跨操作冲突
                throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
            }
            return null;
        }
        var row = rows.get(0);
        Object storedResolution = row.get("request_resolution");
        if (!"ELEVATION".equals(row.get("phase")) || !String.valueOf(projectId).equals(row.get("project_ref"))
                || count != ((Number) row.get("requested_count")).intValue()
                || !java.util.Objects.equals(reason, row.get("reason"))
                || resolution != null && storedResolution != null && !java.util.Objects.equals(resolution, storedResolution)) {
            throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        }
        String expected = configUpdates == null ? null : toStringJson(configUpdates);
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Object existing = row.get("config_updates");
            if (!java.util.Objects.equals(existing == null ? null : mapper.readTree(existing.toString()),
                    expected == null ? null : mapper.readTree(expected))) {
                throw exception(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("INVALID_REVISION_CONFIG", error);
        }
        return new RevisionReceipt(((Number) row.get("request_id")).longValue(),
                ((Number) row.get("job_id")).longValue(), true);
    }

    private Map<String,Object> runtimeInput(ProjectSnapshot project, String phase, Map<String,Object> overrides,
                                            cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options,
                                            int count) {
        return runtimeInput(project,phase,overrides,options,count,null,false);
    }

    private Map<String,Object> runtimeInput(ProjectSnapshot project,String phase,Map<String,Object> overrides,
                                             cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options,
                                             int count,Long sourceSelection,boolean completeRequirements) {
        return runtimeInput(project, phase, overrides, options, count, sourceSelection, completeRequirements, null);
    }

    private Map<String,Object> runtimeInput(ProjectSnapshot project,String phase,Map<String,Object> overrides,
                                             cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions options,
                                             int count,Long sourceSelection,boolean completeRequirements,
                                             Map<String,Object> frozenFlatInput) {
        var requirements = new java.util.LinkedHashMap<String,Object>();
        // A revision supplies its selected version's complete frozen basis. Missing
        // keys must stay absent, rather than inheriting unrelated later requests.
        if (!completeRequirements) for (String value : jdbcTemplate.queryForList("SELECT inputs::text FROM design_requirement_snapshot WHERE project_id=? AND deleted=FALSE ORDER BY input_version",String.class,project.projectId())) {
            if (value != null) try { requirements.putAll(new com.fasterxml.jackson.databind.ObjectMapper().readValue(value,Map.class)); }
            catch (java.io.IOException e) { throw new IllegalStateException("INVALID_REQUIREMENT_SNAPSHOT"); }
        }
        if (overrides != null) requirements.putAll(overrides);
        List<Long> assetIds;
        if ("ELEVATION".equals(phase)) {
            // 立面参考图合并规则（22 号文档 5b）：用户自传（≤3 张，排最前，不被案例图挤掉）→ 存量已选平面 →
            // 案例授权立面图，总量不超过引擎契约上限 8 张，按 assetId 去重
            var merged = new java.util.LinkedHashSet<Long>();
            if (requirements.get("referenceAssetIds") instanceof List<?> refs) {
                for (Object ref : refs) merged.add(Long.parseLong(String.valueOf(ref)));
            }
            if (sourceSelection != null) {
                merged.addAll(jdbcTemplate.queryForList("SELECT c.asset_id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id WHERE s.project_id=? AND s.id=? AND s.stage='FLAT' AND s.deleted=FALSE AND c.deleted=FALSE",Long.class,project.projectId(),sourceSelection));
            }
            if (requirements.containsKey("referenceCaseId")) {
                String caseId = String.valueOf(requirements.get("referenceCaseId"));
                if (!caseId.isEmpty()) merged.addAll(requestedCaseElevationAssets(Long.parseLong(caseId), 8 - merged.size()));
            } else if ("CASE_REFERENCE".equals(project.sourceType()) && merged.size() < 8) {
                // 仅纳入有有效生成参考授权的立面资产，未授权的直接排除而非报错
                merged.addAll(authorizedCaseElevationAssets(project.projectId(), 8 - merged.size()));
            }
            if (merged.isEmpty() && "SELF_UPLOAD".equals(project.sourceType()) && sourceSelection == null) {
                // 存量纯立面项目（无 referenceAssetIds）回落草图通道；仍为空则零参考文生图
                merged.addAll(jdbcTemplate.queryForList("SELECT sketch_asset_id FROM design_requirement_snapshot "
                        + "WHERE project_id=? AND sketch_asset_id IS NOT NULL AND deleted=FALSE ORDER BY input_version DESC LIMIT 1",
                        Long.class, project.projectId()));
            }
            assetIds = merged.stream().limit(8).toList();
        } else {
            // 平面生成已改为纯文生图：新 FLAT 任务不再读取案例平面、手绘草图或参考授权。
            // 旧任务不会重建，仍按已冻结的 input_snapshot 恢复。
            assetIds = java.util.List.of();
        }
        // Idempotent replay must keep the exact ordered asset list already frozen for that job.
        // New FLAT jobs never reach this branch without a frozen snapshot, so pure text generation
        // is unaffected; legacy jobs can still be replayed without dropping their original evidence.
        if ("FLAT".equals(phase) && frozenFlatInput != null
                && frozenFlatInput.get("assetIds") instanceof List<?> frozenAssets) {
            assetIds = frozenAssets.stream().map(value -> Long.parseLong(String.valueOf(value))).toList();
        }
        var snapshot=new java.util.LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"phase",phase,"projectId",String.valueOf(project.projectId()),
                "sourceType",project.sourceType(),"requirements",requirements,
                "imageOptions",Map.of("resolution",options.resolution(),"orientation",options.orientation()),
                "candidateCount",count,"assetIds",assetIds.stream().map(String::valueOf).toList()));
        if ("FLAT".equals(phase) && frozenFlatInput != null) {
            // These are derived evidence, not new request intent. Reuse them as originally frozen,
            // including their absence in pre-T26-08 jobs. Do not refresh or write the old snapshot.
            // Requirements/options/count are still rebuilt from current intent and compared by
            // freezeInput, so a changed replay remains rejected.
            for (String field : List.of("references", "referenceRights")) {
                if (frozenFlatInput.containsKey(field)) snapshot.put(field, frozenFlatInput.get(field));
            }
        }
        if ("ELEVATION".equals(phase) && sourceSelection != null) {
            var source=jdbcTemplate.queryForMap("SELECT s.id AS selection_id,c.id AS candidate_id,c.asset_id,c.job_id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id WHERE s.project_id=? AND s.id=? AND s.stage='FLAT' AND s.deleted=FALSE AND c.deleted=FALSE",project.projectId(),sourceSelection);
            snapshot.put("sourceFlat",Map.of("selectionId",String.valueOf(source.get("selection_id")),"candidateId",String.valueOf(source.get("candidate_id")),
                    "assetId",String.valueOf(source.get("asset_id")),"jobId",String.valueOf(source.get("job_id"))));
        }
        return snapshot;
    }

    /** 案例当前版本中有有效生成参考授权的立面图（未授权的排除），按关联 id 稳定排序 */
    private List<Long> authorizedCaseElevationAssets(long projectId, int limit) {
        var assets = new java.util.ArrayList<>(jdbcTemplate.queryForList(
                "SELECT a.asset_id FROM design_case_asset a JOIN design_project p ON p.ref_version_id=a.case_version_id "
                + "WHERE p.id=? AND a.asset_role='ELEVATION' AND a.deleted=FALSE AND EXISTS (SELECT 1 FROM asset_rights_grant g "
                + "WHERE g.asset_id=a.asset_id AND g.scope='GENERATION_REFERENCE' AND g.status='ACTIVE' "
                + "AND g.effective_at<=now() AND (g.expires_at IS NULL OR g.expires_at>now()) AND g.deleted=FALSE) "
                + "ORDER BY a.id LIMIT ?", Long.class, projectId, limit));
        if (assets.size() < limit) {
            // 2026-10-16 需求：AI立面设计带参考时封面图与立面图一同带入；封面同样要求有效生成参考授权
            for (Long cover : jdbcTemplate.queryForList(
                    "SELECT a.asset_id FROM design_case_asset a JOIN design_project p ON p.ref_version_id=a.case_version_id "
                    + "WHERE p.id=? AND a.asset_role='COVER' AND a.deleted=FALSE AND EXISTS (SELECT 1 FROM asset_rights_grant g "
                    + "WHERE g.asset_id=a.asset_id AND g.scope='GENERATION_REFERENCE' AND g.status='ACTIVE' "
                    + "AND g.effective_at<=now() AND (g.expires_at IS NULL OR g.expires_at>now()) AND g.deleted=FALSE) "
                    + "ORDER BY a.id LIMIT 1", Long.class, projectId)) {
                if (!assets.contains(cover) && assets.size() < limit) assets.add(cover);
            }
        }
        return assets;
    }

    /** A per-job case choice never rewrites the historical project's flat provenance. */
    private List<Long> requestedCaseElevationAssets(long caseId, int limit) {
        var assets = new java.util.ArrayList<>(jdbcTemplate.queryForList("""
                SELECT ca.asset_id FROM design_case c
                JOIN design_case_version v ON v.id=c.current_version_id AND v.case_id=c.id AND v.deleted=FALSE AND v.tenant_id=0
                JOIN design_case_asset ca ON ca.case_version_id=v.id AND ca.asset_role='ELEVATION' AND ca.deleted=FALSE AND ca.tenant_id=0
                JOIN asset a ON a.id=ca.asset_id AND a.deleted=FALSE AND a.tenant_id=0 AND a.upload_status='ACCEPTED'
                  AND a.security_scan_status='PASSED' AND a.moderation_status='PASSED'
                WHERE c.id=? AND c.deleted=FALSE AND c.tenant_id=0 AND c.publication_status='PUBLISHED'
                  AND EXISTS (SELECT 1 FROM asset_rights_grant g WHERE g.asset_id=a.id AND g.tenant_id=0 AND g.deleted=FALSE
                    AND g.scope='GENERATION_REFERENCE' AND g.status='ACTIVE' AND g.effective_at<=now()
                    AND (g.expires_at IS NULL OR g.expires_at>now()))
                ORDER BY ca.id LIMIT ?
                """, Long.class, caseId, limit));
        if (assets.size() < limit) {
            // 2026-10-16 需求：封面图与立面图两张参考一并带入（封面同样要求授权 + 过审）
            for (Long cover : jdbcTemplate.queryForList("""
                    SELECT ca.asset_id FROM design_case c
                    JOIN design_case_version v ON v.id=c.current_version_id AND v.case_id=c.id AND v.deleted=FALSE AND v.tenant_id=0
                    JOIN design_case_asset ca ON ca.case_version_id=v.id AND ca.asset_role='COVER' AND ca.deleted=FALSE AND ca.tenant_id=0
                    JOIN asset a ON a.id=ca.asset_id AND a.deleted=FALSE AND a.tenant_id=0 AND a.upload_status='ACCEPTED'
                      AND a.security_scan_status='PASSED' AND a.moderation_status='PASSED'
                    WHERE c.id=? AND c.deleted=FALSE AND c.tenant_id=0 AND c.publication_status='PUBLISHED'
                      AND EXISTS (SELECT 1 FROM asset_rights_grant g WHERE g.asset_id=a.id AND g.tenant_id=0 AND g.deleted=FALSE
                        AND g.scope='GENERATION_REFERENCE' AND g.status='ACTIVE' AND g.effective_at<=now()
                        AND (g.expires_at IS NULL OR g.expires_at>now()))
                    ORDER BY ca.id LIMIT 1
                    """, Long.class, caseId)) {
                if (!assets.contains(cover) && assets.size() < limit) assets.add(cover);
            }
        }
        if (assets.isEmpty()) throw exception(GENERATION_REFERENCE_NOT_AUTHORIZED);
        return assets;
    }

    private void lockOwnedProject(long userId,long projectId) {
        if (accountStatePort != null) accountStatePort.requireActiveForWrite(userId);
        var rows=jdbcTemplate.queryForList("SELECT id FROM design_project WHERE id=? AND user_id=? AND deleted=FALSE FOR UPDATE",projectId,userId);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
    }

    private AiJobPort.JobView requireScopedJob(long userId,long projectId,long jobId,String phase) {
        var job=aiJobPort.getJob(jobId).orElseThrow(()->exception(RESOURCE_FORBIDDEN));
        if (job.userId()!=userId || !String.valueOf(projectId).equals(job.projectRef())
                || !("FLAT".equals(job.phase()) || "ELEVATION".equals(job.phase()))
                || phase!=null && !phase.equals(job.phase())) throw exception(RESOURCE_FORBIDDEN);
        if (!isTerminal(job.status())) throw exception(DESIGN_JOB_NOT_TERMINAL);
        return job;
    }

    private long validateSelectionCandidate(long userId,long projectId,long candidateId,String phase) {
        var rows=jdbcTemplate.queryForList("SELECT job_id FROM design_candidate WHERE id=? AND project_id=? AND deleted=FALSE",candidateId,projectId);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        long jobId=((Number)rows.get(0).get("job_id")).longValue();requireScopedJob(userId,projectId,jobId,phase);return jobId;
    }

    private Map<String,Object> jobInput(long jobId) {
        String json=jdbcTemplate.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=? AND deleted=FALSE",String.class,jobId);
        if (json==null) throw new IllegalStateException("AI_INPUT_SNAPSHOT_MISSING");
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,Map.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("INVALID_AI_INPUT_SNAPSHOT",error); }
    }

    /** 解析立面任务的平面参考来源；需求①拆分后纯立面任务（零参考）返回 null，不再视为数据缺失 */
    private Long sourceFlatSelection(long userId,long projectId,long jobId,Map<String,Object> input) {
        java.util.List<Map<String,Object>> rows;
        if (input.get("sourceFlat") instanceof Map<?,?> source) {
            rows=jdbcTemplate.queryForList("SELECT s.id,c.job_id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id WHERE s.id=? AND s.project_id=? AND s.stage='FLAT' AND s.deleted=FALSE AND c.deleted=FALSE AND c.id=? AND c.asset_id=? AND c.job_id=?",
                    Long.parseLong(String.valueOf(source.get("selectionId"))),projectId,Long.parseLong(String.valueOf(source.get("candidateId"))),
                    Long.parseLong(String.valueOf(source.get("assetId"))),Long.parseLong(String.valueOf(source.get("jobId"))));
        } else if (input.get("assetIds") instanceof List<?> assets && !assets.isEmpty()) {
            // 22 号文档 5b 后顶层 assetIds 是「自传参考图 + 存量已选平面 + 案例授权立面」的混合清单。
            // 仅当其中某个资产能解析为建任务之前的 FLAT 选择（旧快照冻结的平面来源）才认定平面谱系；
            // 自传参考图立面（SELF_UPLOAD + referenceAssetIds）解析不到任何 FLAT 选择 = 无平面谱系，
            // 返回 null 正常选图，不再误判 RESOURCE_FORBIDDEN（2026-10-10 生产联调实测回归）。
            rows = java.util.List.of();
            for (Object asset : assets) {
                rows = jdbcTemplate.queryForList("SELECT s.id,c.job_id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id JOIN ai_job j ON j.id=? WHERE s.project_id=? AND s.stage='FLAT' AND c.asset_id=? AND s.deleted=FALSE AND c.deleted=FALSE AND s.create_time<=j.create_time ORDER BY s.create_time DESC,s.id DESC LIMIT 1",
                        jobId, projectId, Long.parseLong(String.valueOf(asset)));
                if (!rows.isEmpty()) break;
            }
            if (rows.isEmpty()) return null;
        } else {
            return null;
        }
        requireScopedJob(userId,projectId,((Number)rows.get(0).get("job_id")).longValue(),"FLAT");
        return ((Number)rows.get(0).get("id")).longValue();
    }

    private String versionConfig(Map<String,Object> input,long jobId) {
        if (!(input.get("requirements") instanceof Map<?,?> requirements)) throw new IllegalStateException("AI_REQUIREMENTS_MISSING");
        var config=new java.util.LinkedHashMap<String,Object>();requirements.forEach((key,value)->config.put(String.valueOf(key),value));
        config.put("imageOptions",input.get("imageOptions"));config.put("originJobId",String.valueOf(jobId));
        if (input.containsKey("sourceFlat")) config.put("sourceFlat",input.get("sourceFlat"));
        return toStringJson(config);
    }

    private cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions revisionOptions(
            long projectId, String resolution, String orientation) {
        String inheritedResolution = null;
        String inheritedOrientation = null;
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(v.config_snapshot->'imageOptions',j.input_snapshot->'imageOptions')::text FROM design_result_version v JOIN design_candidate c ON c.id=v.elevation_candidate_id AND c.project_id=v.project_id JOIN ai_job j ON j.id=c.job_id WHERE v.project_id=? AND v.deleted=FALSE AND c.deleted=FALSE AND j.deleted=FALSE ORDER BY v.version DESC LIMIT 1",
                String.class, projectId);
        if (!rows.isEmpty()) {
            try {
                Map<String, Object> snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rows.get(0), Map.class);
                Object raw = snapshot;
                if (raw instanceof Map<?, ?> imageOptions) {
                    inheritedResolution = stringValue(imageOptions.get("resolution"));
                    inheritedOrientation = stringValue(imageOptions.get("orientation"));
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("INVALID_AI_INPUT_SNAPSHOT", e);
            }
        }
        return cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize("ELEVATION",
                blank(resolution) ? inheritedResolution : resolution,
                blank(orientation) ? inheritedOrientation : orientation);
    }

    public cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions jobImageOptions(long jobId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT phase,input_snapshot::text AS input_snapshot FROM ai_job WHERE id=? AND deleted=FALSE", jobId);
        String phase = (String) row.get("phase");
        String snapshotJson = (String) row.get("input_snapshot");
        if (snapshotJson != null) {
            try {
                Map<String, Object> snapshot = new com.fasterxml.jackson.databind.ObjectMapper().readValue(snapshotJson, Map.class);
                Object raw = snapshot.get("imageOptions");
                if (raw instanceof Map<?, ?> imageOptions) {
                    return cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize(phase,
                            stringValue(imageOptions.get("resolution")), stringValue(imageOptions.get("orientation")));
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("INVALID_AI_INPUT_SNAPSHOT", e);
            }
        }
        return cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions.normalize(phase, null, null);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // ========== 项目列表与聚合读（"我的 → 设计记录"、项目详情） ==========

    public record ProjectListItem(long projectId, String sourceType, Long refCaseId, String stage,
                                  String status, java.time.Instant createTime, String name,
                                  String coverAssetId, String jobStatus, boolean hasResult, long pointsSpent, boolean hasDualImages) {
    }

    public record ProjectPage(List<ProjectListItem> list, String nextCursor) {
    }

    /**
     * 我的设计项目（游标分页，最新在前）。
     * 游标即上一页最后一行的 id：项目 id 由雪花算法生成、单调递增且唯一，
     * 直接用 id 降序翻页不会出现重复或漏行，无需复合游标。
     * 附带封面资产（已选平面 > 最新结果版本平面/立面）、最新任务状态、是否已有结果版本，
     * 供"我的方案"列表直出缩略图与进行中状态（UX 整改：列表不再只有编号与时间）。
     */
    public ProjectPage listProjects(long userId, String cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        Long afterId = parseCursor(cursor);
        StringBuilder sql = new StringBuilder(
                "SELECT id, source_type, ref_case_id, stage, status, name, create_time FROM design_project "
                        + "WHERE user_id = ? AND deleted = FALSE");
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        if (afterId != null) {
            sql.append(" AND id < ?");
            args.add(afterId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size + 1); // 多取一行判断是否还有下一页
        List<ProjectListItem> all = jdbcTemplate.query(sql.toString(),
                (rs, i) -> new ProjectListItem(rs.getLong("id"), rs.getString("source_type"),
                        rs.getObject("ref_case_id") == null ? null : rs.getLong("ref_case_id"),
                        rs.getString("stage"), rs.getString("status"),
                        rs.getTimestamp("create_time") == null ? null
                                : rs.getTimestamp("create_time").toInstant(),
                        rs.getString("name"),
                        null, null, false, 0L, false),
                args.toArray());
        boolean hasMore = all.size() > size;
        List<ProjectListItem> page = hasMore ? all.subList(0, size) : all;
        if (!page.isEmpty()) {
            String inClause = page.stream().map(item -> String.valueOf(item.projectId()))
                    .collect(java.util.stream.Collectors.joining(","));
            var covers = new java.util.HashMap<Long, String>();
            // 封面优先级：活动平面选择 > 最新结果版本的立面/平面资产
            jdbcTemplate.query("SELECT s.project_id, c.asset_id FROM design_selection s "
                            + "JOIN design_candidate c ON c.id = s.candidate_id AND c.deleted = FALSE "
                            + "WHERE s.stage = 'FLAT' AND s.active = TRUE AND s.deleted = FALSE AND c.deleted = FALSE "
                            + "AND s.project_id IN (" + inClause + ")",
                    rs -> { covers.putIfAbsent(rs.getLong("project_id"), String.valueOf(rs.getLong("asset_id"))); });
            jdbcTemplate.query("SELECT DISTINCT ON (rv.project_id) rv.project_id, "
                            + "COALESCE(ec.asset_id, fc.asset_id) AS cover "
                            + "FROM design_result_version rv "
                            + "LEFT JOIN design_candidate ec ON ec.id = rv.elevation_candidate_id AND ec.deleted = FALSE "
                            + "LEFT JOIN design_candidate fc ON fc.id = NULLIF(rv.flat_candidate_ids ->> 0, '')::bigint AND fc.deleted = FALSE "
                            + "WHERE rv.deleted = FALSE AND rv.project_id IN (" + inClause + ") "
                            + "ORDER BY rv.project_id, rv.id DESC",
                    rs -> { covers.putIfAbsent(rs.getLong("project_id"), String.valueOf(rs.getLong("cover"))); });
            // 兜底3：尚无选择与结果版本（如立面已出图待选择），取最近一次任务的候选图作封面
            jdbcTemplate.query("SELECT DISTINCT ON (c.project_id) c.project_id, c.asset_id FROM design_candidate c "
                            + "JOIN ai_job j ON j.id = c.job_id AND j.deleted = FALSE "
                            + "WHERE c.deleted = FALSE AND c.asset_id IS NOT NULL AND c.project_id IN (" + inClause + ") "
                            + "ORDER BY c.project_id, c.id DESC",
                    rs -> { covers.putIfAbsent(rs.getLong("project_id"), String.valueOf(rs.getLong("asset_id"))); });
            var jobs = new java.util.HashMap<Long, String>();
            // ai_job.project_ref 为 varchar 的项目 id（均为服务端生成的纯数字雪花串，可安全内联）
            String quotedIds = page.stream().map(item -> "'" + item.projectId() + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            jdbcTemplate.query("SELECT DISTINCT ON (project_ref) project_ref, status FROM ai_job "
                            + "WHERE deleted = FALSE AND project_ref IN (" + quotedIds + ") ORDER BY project_ref, id DESC",
                    rs -> { jobs.put(Long.parseLong(rs.getString("project_ref")), rs.getString("status")); });
            var pointsSpent = new java.util.HashMap<Long, Long>();
            // 净消耗 = Σ(任务总扣点) − Σ(结算退款)；单任务失败退款经 ai_job_settlement 汇总
            jdbcTemplate.query("SELECT project_ref, COALESCE(SUM(total_point_cost),0) "
                            + "- COALESCE(SUM((SELECT COALESCE(SUM(s.refunded_points),0) FROM ai_job_settlement s "
                            + "WHERE s.job_id = ai_job.id AND s.deleted = FALSE)),0) AS net "
                            + "FROM ai_job WHERE deleted = FALSE AND project_ref IN (" + quotedIds + ") GROUP BY project_ref",
                    rs -> { pointsSpent.put(Long.parseLong(rs.getString("project_ref")), rs.getLong("net")); });
            var results = new java.util.HashSet<Long>(jdbcTemplate.queryForList(
                    "SELECT DISTINCT project_id FROM design_result_version WHERE deleted = FALSE AND project_id IN (" + inClause + ")",
                    Long.class));
            var dualResults = new java.util.HashSet<Long>(jdbcTemplate.queryForList(
                    "SELECT DISTINCT project_id FROM design_result_version WHERE deleted=FALSE "
                            + "AND flat_selection_id IS NOT NULL AND elevation_candidate_id IS NOT NULL "
                            + "AND project_id IN (" + inClause + ")", Long.class));
            page = page.stream().map(item -> new ProjectListItem(item.projectId(), item.sourceType(), item.refCaseId(),
                    item.stage(), item.status(), item.createTime(), item.name(),
                    covers.get(item.projectId()), jobs.get(item.projectId()), results.contains(item.projectId()),
                    pointsSpent.getOrDefault(item.projectId(), 0L), dualResults.contains(item.projectId()))).toList();
        }
        String next = hasMore ? String.valueOf(page.get(page.size() - 1).projectId()) : null;
        return new ProjectPage(List.copyOf(page), next);
    }

    /** 单方案净消耗（详情页费用展示）：与列表聚合同口径（Σ扣点 − Σ退款），无任务返回 0。 */
    public long netPointsSpent(long projectId) {
        Long net = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_point_cost),0) "
                        + "- COALESCE(SUM((SELECT COALESCE(SUM(s.refunded_points),0) FROM ai_job_settlement s "
                        + "WHERE s.job_id = ai_job.id AND s.deleted = FALSE)),0) FROM ai_job "
                        + "WHERE deleted = FALSE AND project_ref = ?",
                Long.class, String.valueOf(projectId));
        return net == null ? 0L : net;
    }

    /** 最新需求快照中的设计键（UX：方案记录详情直出用户输入）；budgetInputs 属预算域不下发。 */
    public Map<String, Object> latestDesignInputs(long projectId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT inputs::text FROM design_requirement_snapshot WHERE project_id = ? AND deleted = FALSE "
                        + "ORDER BY input_version DESC, id DESC LIMIT 1", String.class, projectId);
        if (rows.isEmpty() || rows.get(0) == null) return Map.of();
        try {
            Map<String, Object> inputs = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rows.get(0), Map.class);
            inputs.remove("budgetInputs");
            return inputs;
        } catch (java.io.IOException e) {
            return Map.of();
        }
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            // 游标非法只影响调用方自己的翻页，退化为首页而不是抛错打断列表
            log.warn("[listProjects][非法游标 cursor={}，退化为首页]", cursor);
            return null;
        }
    }

    /** 某阶段当前有效选择所指向的候选 id（无有效选择返回 null） */
    public Long activeSelectionCandidateId(long projectId, String stage) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT candidate_id FROM design_selection WHERE project_id = ? AND stage = ? "
                        + "AND active = TRUE AND deleted = FALSE",
                (rs, i) -> rs.getLong("candidate_id"), projectId, stage);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前最新（未被取代）的结果版本 id；尚无版本返回 null */
    public Long latestResultVersionId(long projectId) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT id FROM design_result_version WHERE project_id = ? AND deleted = FALSE "
                        + "ORDER BY version DESC LIMIT 1",
                (rs, i) -> rs.getLong("id"), projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireOwner(long userId, long projectId) {
        getProject(projectId).filter(p -> p.userId() == userId)
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
    }

    /**
     * 删除我的设计项目（软删，UX 2026-10：我的方案列表滑动/长按删除）。
     * 归属校验后拦截最新任务仍在进行中的项目——结算与退点依赖任务归属，删除会造成悬挂引用；
     * 终态（SUCCEEDED/PARTIALLY_SUCCEEDED/FAILED/CANCELLED）或无任务的项目才允许删除。
     * 已扣设计点与生成产物不做回收，删除仅令项目在列表与读取接口中不可见。
     */
    public void deleteProject(long userId, long projectId) {
        requireOwner(userId, projectId);
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM ai_job WHERE deleted = FALSE AND project_ref = ? ORDER BY id DESC LIMIT 1",
                String.class, String.valueOf(projectId));
        boolean jobInFlight = !statuses.isEmpty()
                && java.util.Set.of("QUEUED", "RUNNING", "VALIDATING", "CANCEL_REQUESTED").contains(statuses.get(0));
        if (jobInFlight) {
            throw exception(DESIGN_PROJECT_DELETE_CONFLICT);
        }
        int updated = jdbcTemplate.update(
                "UPDATE design_project SET deleted = TRUE, update_time = now() WHERE id = ? AND deleted = FALSE",
                projectId);
        if (updated == 0) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        log.info("[deleteProject][project={} user={}]", projectId, userId);
    }

    private String latestConfig(long projectId) {
        List<String> configs = jdbcTemplate.query(
                "SELECT inputs::text FROM design_requirement_snapshot WHERE project_id = ? "
                        + "AND deleted = FALSE ORDER BY input_version DESC LIMIT 1",
                (rs, i) -> rs.getString(1), projectId);
        return configs.isEmpty() ? null : configs.get(0);
    }

    private String toSnapshotJson(java.util.List<Map<String, Object>> items) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJsonList(List<Long> ids) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ids);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIALLY_SUCCEEDED".equals(status);
    }

    private String toStringJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
