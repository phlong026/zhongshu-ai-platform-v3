package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngDocumentDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngDocumentMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanVersionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanVersionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.api.EngineeringRectificationAssignments.ACTIVE_POSTS;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 工程档案：正式合同建档（查重=同组织未归档项目名唯一 008）、移交自动建档（内部 Port）、
 * 换负责人（在岗校验 010+CAS+审计）、详情聚合（按身份脱敏 D-13）、状态机固化。
 * 两建档入口初始化一致：模板计划（按层数展开、日期留空）+约定未生成（D-4）。
 */
@Service
public class EngProjectService {

    public static final String AGGREGATE = "fr-eng-project";

    private final FrEngProjectMapper projects;
    private final FrEngDocumentMapper documents;
    private final FrEngPlanVersionMapper planVersions;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngClosureMapper closures;
    private final FrEngCollectionMapper collections;
    private final FrEngCollectionTermMapper collectionTerms;
    private final FranchiseCommands commands;
    private final StaffPostService staffPosts;
    private final JdbcTemplate jdbcTemplate;
    private final SystemDirectory systemDirectory;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.iocoder.yudao.module.franchise.api.CommissionQueryPort commissionQuery;

    public EngProjectService(FrEngProjectMapper projects, FrEngDocumentMapper documents,
                             FrEngPlanVersionMapper planVersions, FrEngPlanNodeMapper planNodes,
                             FrEngClosureMapper closures, FrEngCollectionMapper collections,
                             FrEngCollectionTermMapper collectionTerms, FranchiseCommands commands,
                             StaffPostService staffPosts, JdbcTemplate jdbcTemplate, SystemDirectory systemDirectory) {
        this.projects = projects;
        this.documents = documents;
        this.planVersions = planVersions;
        this.planNodes = planNodes;
        this.closures = closures;
        this.collections = collections;
        this.collectionTerms = collectionTerms;
        this.commands = commands;
        this.staffPosts = staffPosts;
        this.jdbcTemplate = jdbcTemplate;
        this.systemDirectory = systemDirectory;
    }

    public record CreateCommand(String name, String customer, String city, String phone,
                                BigDecimal area, Integer floors, Long parentProjectId, boolean independent) {
        public CreateCommand(String name, String customer, String city, String phone, BigDecimal area,
                             Integer floors, Long parentProjectId) {
            this(name,customer,city,phone,area,floors,parentProjectId,false);
        }
    }

    public record ManagerCommand(long engineerUserId, String note) {
    }

    // ===================== 建档 =====================

    /** 正式合同建档；旧独立/增项请求仅允许重放已成功的原命令。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(PostSnapshot post, CreateCommand cmd, String idempotencyKey) {
        var replayed = commands.replayed(post, idempotencyKey, normalizeCreate(cmd));
        if (replayed.isPresent()) {
            return filingResult(post, replayed.get().getAggregateId(), !"eng-project-create".equals(replayed.get().getCommand()));
        }
        if (cmd.independent() || cmd.parentProjectId() != null) {
            if (cmd.parentProjectId() != null) EngGuard.requireOwner(requireProject(post, cmd.parentProjectId()), post.userId());
            throw exception(ENGINEERING_INPUT_INVALID, "独立建档和独立增项暂无单独收款依据；请在原工程办理增项，新主工程须先签订正式施工合同");
        }
        String name = StrUtil.trim(cmd.name());
        String customer = StrUtil.trim(cmd.customer());
        String city = StrUtil.trim(cmd.city());
        if (StrUtil.isBlank(name) || name.length() > 128 || StrUtil.isBlank(customer) || StrUtil.isBlank(city)) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写项目名称、客户称呼和所在城市");
        }
        String phone = StrUtil.trimToEmpty(cmd.phone());
        if (!phone.isBlank() && !phone.matches("^1\\d{10}$")) {
            throw exception(ENGINEERING_INPUT_INVALID, "请输入11位手机号");
        }
        Integer floors = cmd.floors() == null ? null
                : Math.max(1, Math.min(5, cmd.floors()));
        var existing = projects.findActiveByName(post.orgId(), name);
        if (existing != null) {
            return linkExistingProject(post, projects.lockById(existing.getId()), customer, phone, cmd, idempotencyKey);
        }
        // Locate the original main project before taking its contract lock (same order as prepare/start).
        // A different proposed name must not duplicate a contract or rename/transfer the original project.
        var linked = jdbcTemplate.queryForList("""
                SELECT e.id FROM fr_eng_project e
                JOIN fr_sale_contract s ON s.company_id=e.company_id AND s.id=e.sale_contract_id AND s.status='SIGNED' AND NOT s.deleted
                JOIN fr_customer c ON c.company_id=s.company_id AND c.id=s.customer_id
                WHERE e.company_id=? AND c.name=? AND c.phone=? ORDER BY e.id LIMIT 1
                """, Long.class, post.orgId(), customer, phone);
        if (!linked.isEmpty()) {
            return linkExistingProject(post, projects.lockById(linked.get(0)), customer, phone, cmd, idempotencyKey);
        }
        FrEngProjectDO project = new FrEngProjectDO()
                .setCompanyId(post.orgId())
                .setCustomerName(customer).setPhone(phone.isBlank() ? null : phone)
                .setSourceType(FrEngProjectDO.SOURCE_MANUAL)
                .setManagerUserId(post.userId()).setAssignedEngineerUserId(post.userId())
                .setCity(city).setArea(cmd.area()).setFloors(floors).setName(name)
                .setStatus(FrEngProjectDO.STATUS_PENDING_START)
                .setRevision(1L).setAuthorUserId(post.userId());
        linkSignedContract(post.orgId(), project, customer, phone);
        projects.insert(project);
        initTemplatePlan(post, project);
        commands.record(post, AGGREGATE, project.getId(), 1,
                "eng-project-create",
                normalizeCreate(cmd), idempotencyKey);
        return filingResult(post, project.getId(), false);
    }

    private Map<String, Object> filingResult(PostSnapshot post, long id, boolean reused) {
        var result = detail(post, id);
        result.put("reused", reused);
        return result;
    }

    /** 原档案只按 V993 已批准的唯一匹配及资金/约定边界补关联，不重建计划或改合同。 */
    private Map<String, Object> linkExistingProject(PostSnapshot post, FrEngProjectDO project, String customer,
                                                    String phone, CreateCommand cmd, String key) {
        if (project == null || project.getCompanyId() != post.orgId() || FrEngProjectDO.STATUS_ARCHIVED.equals(project.getStatus())) {
            throw exception(ENGINEERING_INPUT_INVALID, "原工程不存在或已归档，请核对原档案，不能重复承接应收");
        }
        EngGuard.requireOwner(project, post.userId());
        if (FrEngProjectDO.SOURCE_INCREMENT.equals(project.getSourceType()) || project.getParentProjectId() != null
                || !customer.equals(project.getCustomerName()) || !phone.equals(project.getPhone())) {
            throw exception(ENGINEERING_INPUT_INVALID, "同名档案不是该客户的主工程，请从原工程核对，不新建增项收款项目");
        }
        var target = new FrEngProjectDO().setId(project.getId()).setCustomerId(project.getCustomerId()).setSaleContractId(project.getSaleContractId());
        linkSignedContract(post.orgId(), target, customer, phone);
        boolean backfill = project.getCustomerId() == null || project.getSaleContractId() == null;
        if (backfill && project.getManagerUserId() != post.userId()) throw exception(ENGINEERING_FORBIDDEN);
        if (backfill && !verifiedHistoricalLink(post.orgId(), project.getId(), target.getCustomerId(), target.getSaleContractId())) {
            throw exception(ENGINEERING_INPUT_INVALID, "原工程存在待核对的来源、收款或付款约定，请先核对原档案；不会新建工程或修改已签合同");
        }
        long eventRevision = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        if (backfill) {
            Revisions.requireUpdated(projects.updateByRevision(project.getId(), project.getRevision(), u -> u
                    .set(FrEngProjectDO::getCustomerId, target.getCustomerId()).set(FrEngProjectDO::getSaleContractId, target.getSaleContractId())));
            eventRevision = project.getRevision() + 1;
        }
        commands.record(post, AGGREGATE, project.getId(), eventRevision, backfill ? "eng-project-link-contract" : "eng-project-reuse", normalizeCreate(cmd), key);
        return filingResult(post, project.getId(), true);
    }

    private boolean verifiedHistoricalLink(long org, long projectId, long customerId, long contractId) {
        // V993 used IS NULL, but V814 stores untouched monetary fields as NOT NULL DEFAULT 0.
        // Require the whole untouched shape instead of interpreting an arbitrary zero balance as unprepared.
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                WITH candidates AS (
                    SELECT e.id AS project_id,c.id AS customer_id,s.id AS contract_id,e.company_id,
                           count(*) OVER (PARTITION BY e.company_id,s.id) AS project_count
                    FROM fr_eng_project e
                    JOIN fr_customer c ON c.company_id=e.company_id AND c.phone=e.phone AND c.name=e.customer_name
                    JOIN fr_sale_contract s ON s.company_id=c.company_id AND s.customer_id=c.id AND s.status='SIGNED' AND NOT s.deleted
                    WHERE e.company_id=? AND e.source_type='MANUAL' AND e.parent_project_id IS NULL
                      AND e.sale_contract_id IS NULL AND (e.customer_id IS NULL OR e.customer_id=c.id)
                )
                SELECT EXISTS(SELECT 1 FROM candidates v JOIN fr_eng_project e ON e.id=v.project_id JOIN fr_sale_contract s ON s.id=v.contract_id
                    WHERE v.project_id=? AND v.customer_id=? AND v.contract_id=? AND v.project_count=1
                      AND NOT EXISTS(SELECT 1 FROM fr_eng_project other WHERE other.company_id=v.company_id AND other.sale_contract_id=v.contract_id)
                      AND NOT EXISTS(SELECT 1 FROM fr_eng_collection ec WHERE ec.company_id=v.company_id AND ec.project_id=e.id)
                      AND ((e.prepared_at IS NULL AND e.actual_start_at IS NULL AND e.status='PENDING_START'
                            AND e.contract_total_cents=0 AND e.first_cents=0 AND e.contract_date IS NULL
                            AND e.contract_version IS NULL AND e.contract_asset_id IS NULL AND s.signed_date<=e.create_time::date)
                           OR (e.contract_date=s.signed_date AND e.contract_total_cents=s.total_cents
                            AND EXISTS(SELECT 1 FROM fr_eng_collection_term t WHERE t.company_id=e.company_id AND t.project_id=e.id)
                            AND NOT EXISTS(
                                (SELECT t.stage,t.amount_cents FROM fr_eng_collection_term t WHERE t.company_id=e.company_id AND t.project_id=e.id
                                 EXCEPT SELECT f.stage,f.amount_cents FROM fr_sale_payment_stage f WHERE f.company_id=e.company_id AND f.contract_id=s.id AND NOT f.deleted)
                                UNION ALL
                                (SELECT f.stage,f.amount_cents FROM fr_sale_payment_stage f WHERE f.company_id=e.company_id AND f.contract_id=s.id AND NOT f.deleted
                                 EXCEPT SELECT t.stage,t.amount_cents FROM fr_eng_collection_term t WHERE t.company_id=e.company_id AND t.project_id=e.id)))))
                """, Boolean.class, org, projectId, customerId, contractId));
    }

    /** 主工程只接收同门店、姓名和完整手机号均匹配的唯一已签合同。 */
    private void linkSignedContract(long org, FrEngProjectDO project, String customer, String phone) {
        // The contract lock serializes filings. Do not invert the signing trigger's contract/customer lock order.
        var matches = jdbcTemplate.query("""
                SELECT c.id,c.name FROM fr_customer c WHERE c.company_id=? AND c.phone=?
                """, (rs, i) -> new ProjectCustomer(rs.getLong("id"), rs.getString("name")), org, phone);
        if (matches.isEmpty()) {
            boolean signedName = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fr_customer c JOIN fr_sale_contract s
                      ON s.company_id=c.company_id AND s.customer_id=c.id
                      WHERE c.company_id=? AND c.name=? AND s.status='SIGNED' AND NOT s.deleted)
                    """, Boolean.class, org, customer));
            if (signedName) throw exception(ENGINEERING_INPUT_INVALID, "有同名签约客户，请核对原客户姓名和完整手机号");
            throw exception(ENGINEERING_INPUT_INVALID, "未找到对应正式施工合同，请先由业务签约并核对客户手机号");
        }
        ProjectCustomer matched = matches.get(0);
        if (matches.size() != 1 || !customer.equals(matched.name())) {
            throw exception(ENGINEERING_INPUT_INVALID, "客户姓名与该手机号的档案不一致，请核对");
        }
        if (project.getCustomerId() != null && project.getCustomerId() != matched.id()) {
            throw exception(ENGINEERING_INPUT_INVALID, "原工程客户关联不一致，请先核对原档案");
        }
        project.setCustomerId(matched.id());
        var contracts = jdbcTemplate.queryForList("""
                SELECT id FROM fr_sale_contract WHERE company_id=? AND customer_id=? AND status='SIGNED'
                  AND NOT deleted FOR UPDATE
                """, Long.class, org, matched.id());
        if (contracts.size() != 1) {
            throw exception(ENGINEERING_INPUT_INVALID, "请先由业务核对该客户唯一有效的已签正式施工合同");
        }
        long contractId = contracts.get(0);
        if (project.getSaleContractId() != null && project.getSaleContractId() != contractId) {
            throw exception(ENGINEERING_INPUT_INVALID, "原工程合同关联不一致，请先核对原档案");
        }
        Long linked = contractProject(org, contractId);
        if (linked != null && !linked.equals(project.getId())) {
            throw exception(ENGINEERING_INPUT_INVALID, "该正式合同已关联工程，请打开原工程继续办理；负责人变更需先办理交接");
        }
        project.setSaleContractId(contractId);
    }

    private record ProjectCustomer(long id, String name) {}

    private Long contractProject(long org, long contractId) {
        var linked = jdbcTemplate.queryForList("""
                SELECT id FROM fr_eng_project WHERE company_id=? AND sale_contract_id=?
                ORDER BY id LIMIT 2
                """, Long.class, org, contractId);
        if (linked.size() > 1) throw exception(ENGINEERING_INPUT_INVALID, "该合同存在多个工程关联，请先核对原档案，不能重复承接应收");
        return linked.stream().findFirst().orElse(null);
    }

    private static Map<String, Object> normalizeCreate(CreateCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", cmd.name());
        m.put("customer", cmd.customer());
        m.put("city", cmd.city());
        m.put("phone", cmd.phone());
        m.put("area", cmd.area() == null ? null : cmd.area().toPlainString());
        m.put("floors", cmd.floors());
        m.put("parentProjectId", cmd.parentProjectId());
        if (cmd.independent()) m.put("independent", true);
        return m;
    }

    /** 移交自动建档（内部 Port；幂等=部分唯一兜底+命中刷新交底资料，蓝本 merge 语义） */
    @Transactional(rollbackFor = Exception.class)
    public long receiveFromHandover(PostSnapshot post, long commissionId, Long customerId, String customerName,
                                    String city, String phone, BigDecimal area, Integer floors,
                                    long managerUserId, String drawingVersion, String drawingFile,
                                    List<Map<String, String>> documentRows,
                                    Long saleContractId, String frozenSchemeVersion) {
        List<FrEngProjectDO> existing = projects.selectByCommission(post.orgId(), commissionId);
        Long linked = saleContractId == null ? null : contractProject(post.orgId(), saleContractId);
        // Prepare/start already lock project then contract. Reuse must use that same order.
        var lockedProjects=new java.util.TreeSet<Long>();
        existing.forEach(p -> lockedProjects.add(p.getId()));
        if (linked!=null) lockedProjects.add(linked);
        lockedProjects.forEach(projects::lockById);
        if (saleContractId != null) {
            var contractCustomers = jdbcTemplate.queryForList("SELECT customer_id FROM fr_sale_contract WHERE company_id=? AND id=? AND status='SIGNED' AND NOT deleted FOR UPDATE",
                    Long.class, post.orgId(), saleContractId);
            if (contractCustomers.size() != 1 || customerId == null || !customerId.equals(contractCustomers.get(0))) {
                throw exception(ENGINEERING_INPUT_INVALID, "移交客户与有效的已签正式施工合同不匹配，请退回核对");
            }
            // A manual project may have been created while this transaction waited for its contract.
            // Retry with that project first instead of taking a newly discovered project lock in reverse order.
            Long freshLinked=contractProject(post.orgId(),saleContractId);
            if (!java.util.Objects.equals(linked,freshLinked)) throw exception(
                    cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT);
        }
        if (linked != null && !existing.isEmpty() && !linked.equals(existing.get(0).getId())) {
            throw exception(ENGINEERING_INPUT_INVALID, "该委托与合同已关联不同工程，请核对移交档案");
        }
        if (linked != null && existing.isEmpty()) {
            FrEngProjectDO manual = projects.lockById(linked);
            if (manual == null || !FrEngProjectDO.SOURCE_MANUAL.equals(manual.getSourceType())
                    || manual.getSourceCommissionId() != null || FrEngProjectDO.STATUS_ARCHIVED.equals(manual.getStatus())
                    || manual.getCustomerId() != null && !java.util.Objects.equals(manual.getCustomerId(), customerId)) {
                throw exception(ENGINEERING_INPUT_INVALID, "销售合同已关联其他工程或已归档，请核对移交档案");
            }
            if (manual.getManagerUserId() != managerUserId) {
                throw exception(HANDOVER_TRANSFER_REQUIRED, manual.getName());
            }
            Revisions.requireUpdated(projects.updateByRevision(linked, manual.getRevision(), u -> u
                    .set(FrEngProjectDO::getSourceCommissionId, commissionId)));
            existing = List.of(manual);
        }
        if (!existing.isEmpty()) {
            // 重复回调：刷新交底资料（不动负责人/现场记录——蓝本「工程经办人、已签施工合同和现场记录由工程维护」）；
            // R1 P2：合同关联只补全不为改写（原 NULL 本次有值才补）；冻结版本变更留刷新事件供追溯
            FrEngProjectDO project = projects.lockById(existing.get(0).getId());
            if (project.getParentProjectId() != null || FrEngProjectDO.SOURCE_INCREMENT.equals(project.getSourceType())
                    || customerId != null && project.getCustomerId() != null && !customerId.equals(project.getCustomerId())
                    || saleContractId != null && project.getSaleContractId() != null && !saleContractId.equals(project.getSaleContractId())) {
                throw exception(ENGINEERING_INPUT_INVALID, "原工程的客户、合同或主工程关系与移交不一致，请核对原档案");
            }
            if (project.getManagerUserId() != managerUserId) {
                throw exception(HANDOVER_TRANSFER_REQUIRED, project.getName());
            }
            // R2：合同补全用 NULL 条件原子更新（先读后写实体更新在并发回调下后写覆盖先写，违反「只补全不改写」）
            boolean contractBackfill = project.getSaleContractId() == null && saleContractId != null;
            boolean customerBackfill = project.getCustomerId() == null && customerId != null;
            if (FrEngProjectDO.SOURCE_MANUAL.equals(project.getSourceType())) {
                jdbcTemplate.update("""
                        UPDATE fr_eng_project SET drawing_version=COALESCE(?,drawing_version),
                            customer_id=COALESCE(customer_id,?),sale_contract_id=COALESCE(sale_contract_id,?),update_time=now()
                        WHERE company_id=? AND id=?
                        """, drawingVersion,customerId,saleContractId,post.orgId(),project.getId());
                if (documentRows != null) {
                    var prior = documents.selectByProject(post.orgId(), project.getId());
                    for (var row : documentRows) {
                        boolean recorded = prior.stream().anyMatch(d -> java.util.Objects.equals(d.getFileRef(), row.get("fileRef"))
                                && java.util.Objects.equals(d.getName(), row.get("name"))
                                && java.util.Objects.equals(d.getVersion(), row.get("version")));
                        if (!recorded) insertDocument(post, project.getId(), row);
                    }
                }
            } else {
                jdbcTemplate.update("UPDATE fr_eng_project SET city=?,phone=?,area=?,floors=?,drawing_version=?,"
                                + "customer_id=COALESCE(customer_id,?),sale_contract_id=COALESCE(sale_contract_id,?),update_time=now() WHERE id=? AND company_id=?",
                        city,phone,area,floors,drawingVersion,customerId,saleContractId,project.getId(),post.orgId());
                refreshDocuments(post, project.getId(), documentRows);
            }
            // revision 取新雪花 id：refresh 与首建不同命令链，不走 aggregate 版本递增（避免与首建 revision=1 冲突）
            commands.record(post, AGGREGATE, project.getId(), com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                    "eng-project-handover-refresh",
                    Map.of("commissionId", commissionId,
                            "drawingVersion", StrUtil.nullToEmpty(drawingVersion),
                            "frozenSchemeVersion", StrUtil.nullToEmpty(frozenSchemeVersion),
                            "contractBackfill", contractBackfill, "customerBackfill", customerBackfill), null);
            return project.getId();
        }
        if (customerId == null || saleContractId == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请先补齐客户及已签正式施工合同，再接收主工程");
        }
        String cityPart = StrUtil.isBlank(city) ? "" : StrUtil.trim(city).replaceFirst("市$", "");
        String name = StrUtil.trim(customerName) + " · " + cityPart + "乡墅";
        FrEngProjectDO project = new FrEngProjectDO()
                .setCompanyId(post.orgId())
                .setCustomerId(customerId).setCustomerName(customerName).setPhone(phone)
                .setSourceCommissionId(commissionId).setSourceType(FrEngProjectDO.SOURCE_HANDOVER)
                .setManagerUserId(managerUserId).setAssignedEngineerUserId(managerUserId)
                .setCity(city).setArea(area).setFloors(floors).setName(name)
                .setStatus(FrEngProjectDO.STATUS_PENDING_START)
                .setDrawingVersion(drawingVersion)
                // M06-T03：统一合同关联（NULL 兼容）+接收冻结包快照留档
                .setSaleContractId(saleContractId)
                .setRevision(1L).setAuthorUserId(managerUserId);
        projects.insert(project);
        refreshDocuments(post, project.getId(), documentRows);
        initTemplatePlan(post, project);
        commands.record(post, AGGREGATE, project.getId(), 1, "eng-project-from-handover",
                Map.of("commissionId", commissionId, "managerUserId", managerUserId,
                        "drawingVersion", StrUtil.nullToEmpty(drawingVersion),
                        "frozenSchemeVersion", StrUtil.nullToEmpty(frozenSchemeVersion),
                        "saleContractId", saleContractId == null ? "" : String.valueOf(saleContractId)), null);
        return project.getId();
    }

    private void refreshDocuments(PostSnapshot post, long projectId, List<Map<String, String>> rows) {
        jdbcTemplate.update("DELETE FROM fr_eng_document WHERE company_id = ? AND project_id = ?", post.orgId(), projectId);
        for (Map<String, String> row : rows) {
            insertDocument(post, projectId, row);
        }
    }

    private void insertDocument(PostSnapshot post, long projectId, Map<String,String> row) {
        documents.insert(new FrEngDocumentDO().setCompanyId(post.orgId()).setProjectId(projectId)
                .setName(row.get("name")).setFileRef(row.get("fileRef"))
                .setKind(row.getOrDefault("kind", FrEngDocumentDO.KIND_DOCUMENT)).setVersion(row.get("version")));
    }

    /** 两入口共用的模板初始化：v1 快照（TEMPLATE）+ 当前节点行（日期留空待填） */
    private void initTemplatePlan(PostSnapshot post, FrEngProjectDO project) {
        // A signed contract may predate its project. Its node numbers cannot be inferred from creation time.
        boolean essential = project.getSaleContractId() != null && Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fr_sale_contract c
                  WHERE c.id=? AND c.company_id=? AND c.status='SIGNED' AND NOT c.deleted AND c.payment_plan_mode='CUSTOM'
                    AND EXISTS(SELECT 1 FROM fr_sale_payment_stage s WHERE s.contract_id=c.id AND s.company_id=c.company_id
                      AND NOT s.deleted AND s.plan_version=c.payment_plan_version)
                    AND NOT EXISTS(SELECT 1 FROM fr_sale_payment_stage s WHERE s.contract_id=c.id AND s.company_id=c.company_id
                      AND NOT s.deleted AND (s.plan_version<>c.payment_plan_version OR s.trigger_node_index IS NOT NULL
                        OR s.trigger_type NOT IN ('ON_SIGN','BEFORE_START','ON_FINISH','DATE','MANUAL'))))
                """, Boolean.class, project.getSaleContractId(), post.orgId()));
        List<EngTemplate.NodeDraft> drafts = essential ? EngTemplate.buildEssentialNodes(project.getFloors())
                : EngTemplate.buildNodes(project.getFloors());
        long version = 1;
        insertVersionSnapshot(post, project.getId(), version,
                drafts.stream().map(EngTemplate.PlanSnapshotRow::from).toList(),
                FrEngPlanVersionDO.SOURCE_TEMPLATE);
        for (EngTemplate.NodeDraft draft : drafts) {
            planNodes.insert(new FrEngPlanNodeDO().setCompanyId(post.orgId()).setProjectId(project.getId())
                    .setNodeIndex(draft.nodeIndex()).setName(draft.name())
                    .setStatus(FrEngPlanNodeDO.STATUS_PENDING).setMilestone(draft.milestone()));
        }
        // closure 1:1 骨架行
        closures.insert(new FrEngClosureDO().setCompanyId(post.orgId()).setProjectId(project.getId()));
    }

    /** 版本快照落库（JSONB 经 JdbcTemplate CAST，惯例） */
    void insertVersionSnapshot(PostSnapshot post, long projectId, long version,
                               List<EngTemplate.PlanSnapshotRow> rows, String source) {
        jdbcTemplate.update("INSERT INTO fr_eng_plan_version (id, company_id, project_id, version, nodes, source, "
                        + "compiled_at, author_user_id, create_time) VALUES (?,?,?,?,CAST(? AS jsonb),?,?,?,now())",
                cn.hutool.core.util.IdUtil.getSnowflakeNextId(), post.orgId(), projectId,
                version, EngTemplate.snapshotJson(rows), source, java.time.OffsetDateTime.now(), post.userId());
    }

    /** 包级访问（同域服务复用 CAS/查询） */
    FrEngProjectMapper projects() {
        return projects;
    }

    List<Long> activeParticipantIds(FrEngProjectDO project) {
        return jdbcTemplate.queryForList("SELECT DISTINCT s.user_id "+ACTIVE_POSTS+
                " AND s.org_id=? AND s.user_id IN (?,?) ORDER BY s.user_id",Long.class,
                project.getCompanyId(),project.getManagerUserId(),project.getAssignedEngineerUserId());
    }

    void requireActiveParticipant(FrEngProjectDO project,Long userId) {
        if (userId==null || !(userId.equals(project.getManagerUserId()) || userId.equals(project.getAssignedEngineerUserId()))
                || jdbcTemplate.queryForList("SELECT s.id "+ACTIVE_POSTS+" AND s.org_id=? AND s.user_id=? ORDER BY s.id FOR SHARE OF s",
                    Long.class,project.getCompanyId(),userId).isEmpty())
            throw exception(ENGINEERING_INPUT_INVALID,"请选择本项目仍有有效工程任职的负责人或指定工程师");
    }

    // ===================== 换负责人 =====================

    public List<Map<String, Object>> managerCandidates(PostSnapshot post, long projectId) {
        var project = requireOwned(post, projectId, "project");
        if (project.getManagerUserId() != post.userId()) throw exception(ENGINEERING_FORBIDDEN);
        var ids = activeManagerIds(post);
        ids.remove(post.userId());
        if (ids.isEmpty()) return List.of();
        var users = systemDirectory.usersByIds(ids).stream()
                .filter(SystemDirectory.UserBrief::active)
                .sorted(java.util.Comparator.comparing((SystemDirectory.UserBrief u) -> StrUtil.nullToEmpty(u.name()))
                        .thenComparingLong(SystemDirectory.UserBrief::userId))
                .toList();
        return users.stream().map(u -> {
            String name = StrUtil.blankToDefault(u.name(), "未填写姓名");
            boolean duplicate = users.stream().filter(other -> name.equals(StrUtil.blankToDefault(other.name(), "未填写姓名"))).count() > 1;
            return Map.<String, Object>of("id", Long.toString(u.userId()),
                    "name", duplicate ? name + "（编号 " + u.userId() + "）" : name);
        }).toList();
    }

    private java.util.Set<Long> activeManagerIds(PostSnapshot post) {
        var ids = new java.util.LinkedHashSet<Long>();
        for (String role : List.of(FranchiseRoles.ENGINEER, FranchiseRoles.PROJECT_MANAGER)) {
            staffPosts.listActivePostsInOrg(post.orgId(), role).forEach(p -> ids.add(p.getUserId()));
        }
        return ids;
    }

    /** 换负责人（在岗校验、CAS、审计；旧负责人立即失权）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeManager(PostSnapshot post, long projectId, ManagerCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projects.lockById(projectId);
        if (project == null || project.getCompanyId() != post.orgId())
            throw exception(ENGINEERING_INPUT_INVALID, "工程不存在或无权查看");
        // 原操作者才可能发起转交。用其稳定 ID 恢复旧指纹，不使用已改变的当前负责人。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUserId", post.userId());
        payload.put("toUserId", cmd.engineerUserId());
        payload.put("note", StrUtil.nullToEmpty(cmd.note()));
        var prior = commands.previous(post, idempotencyKey, "eng-project-manager", projectId);
        if (prior.isPresent()) {
            if (!java.util.Objects.equals(prior.get().getActorPostId(), post.postId()))
                throw exception(ENGINEERING_FORBIDDEN);
            commands.replayed(post, idempotencyKey, payload);
            return detail(post, projectId);
        }
        requireOwned(post, projectId, "project");
        if (project.getManagerUserId() != post.userId()) throw exception(ENGINEERING_FORBIDDEN);
        if (cmd.engineerUserId() == post.userId() || !activeManagerIds(post).contains(cmd.engineerUserId())
                || !systemDirectory.userUsable(cmd.engineerUserId())) {
            throw exception(MANAGER_INVALID);
        }
        long revision = project.getRevision();
        Revisions.requireUpdated(projects.updateByRevision(projectId, revision, u -> u
                .set(FrEngProjectDO::getManagerUserId, cmd.engineerUserId())
                .set(FrEngProjectDO::getAssignedEngineerUserId, cmd.engineerUserId())));
        commands.record(post, AGGREGATE, projectId, revision + 1, "eng-project-manager",
                payload, idempotencyKey);
        return detail(post, projectId);
    }

    // ===================== 读 =====================

    public List<Map<String, Object>> list(PostSnapshot post, String status) {
        return list(post, status, null, 50);
    }

    public List<Map<String,Object>> list(PostSnapshot post, String status, Integer page, int size) {
        return list(post,status,page,size,null);
    }

    public List<Map<String,Object>> list(PostSnapshot post,String status,Integer page,int size,String keyword) {
        if (page != null && (page < 1 || size < 1 || size > 100)) throw exception(ENGINEERING_INPUT_INVALID);
        var args = new ArrayList<Object>();
        String where = "p.company_id=?";
        args.add(post.orgId());
        if (StrUtil.isNotBlank(status)) { where += " AND p.status=?"; args.add(status); }
        if (keyword!=null && !keyword.isBlank()) {
            where += " AND (strpos(lower(p.name),lower(?))>0 OR strpos(lower(p.customer_name),lower(?))>0"
                    +" OR strpos(lower(p.city),lower(?))>0)";
            args.add(keyword.trim()); args.add(keyword.trim()); args.add(keyword.trim());
        }
        if (!isFinanceOrAbove(post)) {
            where += " AND (p.manager_user_id=? OR p.assigned_engineer_user_id=?)";
            args.add(post.userId()); args.add(post.userId());
        }
        String paging = "";
        if (page != null) { paging = " LIMIT ? OFFSET ?"; args.add(size); args.add((page-1L)*size); }
        String sql = "WITH selected AS (SELECT p.* FROM fr_eng_project p WHERE " + where
                + " ORDER BY p.update_time DESC,p.id DESC" + paging + """
                ), counts AS (
                    SELECT n.project_id,count(*) total,count(*) FILTER (WHERE n.status='DONE') completed
                    FROM fr_eng_plan_node n JOIN selected p ON p.company_id=n.company_id AND p.id=n.project_id
                    GROUP BY n.project_id
                )
                SELECT p.id,p.name,p.customer_name AS customer,p.city,p.area,p.floors,p.status,
                       p.manager_user_id AS "managerUserId",p.actual_start_at AS "actualStartAt",
                       p.source_type AS "sourceType",p.contract_total_cents AS "contractTotalCents",
                       p.first_cents AS "firstCents",COALESCE(c.total,0)::int AS "totalNodes",
                       COALESCE(c.completed,0)::int AS "completedNodes"
                FROM selected p LEFT JOIN counts c ON c.project_id=p.id ORDER BY p.update_time DESC,p.id DESC
                """;
        return jdbcTemplate.queryForList(sql,args.toArray());
    }

    /** 详情聚合（档案+计划+材料汇总+收款汇总+尾段，按身份脱敏 D-13/F11） */
    public Map<String, Object> detail(PostSnapshot post, long projectId) {
        FrEngProjectDO project = requireProject(post, projectId);
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngClosureDO closure = closures.selectByProject(post.orgId(), projectId);
        boolean own = post.userId() == project.getManagerUserId()
                || (project.getAssignedEngineerUserId() != null && post.userId() == project.getAssignedEngineerUserId());
        boolean full = isFinanceOrAbove(post) || own;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", project.getId());
        view.put("name", project.getName());
        view.put("customer", project.getCustomerName());
        view.put("city", project.getCity());
        view.put("area", project.getArea());
        view.put("floors", project.getFloors());
        view.put("sourceType", project.getSourceType());
        view.put("sourceCommissionId", project.getSourceCommissionId());
        var handover = commissionQuery==null || project.getSourceCommissionId()==null ? null
                : commissionQuery.findHandoverContext(post.orgId(),project.getSourceCommissionId());
        boolean externalHandover=handover!=null && "EXTERNAL".equals(handover.mode());
        view.put("handoverMode",handover==null?null:handover.mode());
        view.put("externalHandoverVersion",externalHandover?handover.version():null);
        view.put("canReadHandover",full && externalHandover);
        view.put("handoverPaymentPlan",full && handover!=null?handover.paymentPlan():null);
        view.put("parentProjectId", project.getParentProjectId());
        view.put("managerUserId", project.getManagerUserId());
        String managerName = project.getManagerUserId() == null ? "" : systemDirectory.usersByIds(List.of(project.getManagerUserId())).stream()
                .map(SystemDirectory.UserBrief::name).filter(java.util.Objects::nonNull).findFirst().orElse("");
        view.put("managerName", managerName);
        view.put("assignedEngineerUserId", project.getAssignedEngineerUserId());
        view.put("status", project.getStatus());
        view.put("drawingVersion", project.getDrawingVersion());
        view.put("actualStartAt", project.getActualStartAt());
        view.put("preparedAt", project.getPreparedAt());
        view.put("preparedNote", project.getPreparedNote());
        view.put("preparedWorkers", project.getPreparedWorkers());
        if (full) {
            view.put("phone", project.getPhone());
            view.put("contractTotalCents", project.getContractTotalCents());
            view.put("firstCents", project.getFirstCents());
            view.put("saleContractId", project.getSaleContractId());
            view.put("customerId", project.getCustomerId());
            view.put("contractVersion", project.getContractVersion());
            view.put("paymentPlanVersion",project.getPaymentPlanVersion());
            view.put("contractDate", project.getContractDate());
            view.put("contractAssetId", project.getContractAssetId());
        }
        view.put("documents", externalHandover && !full ? List.of() : documents.selectByProject(post.orgId(), projectId).stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("name", d.getName());
            row.put("kind", d.getKind());
            row.put("version", d.getVersion());
            row.put("fileRef", d.getFileRef());
            return row;
        }).toList());
        var progressOwners = new java.util.LinkedHashSet<Long>();
        nodes.stream().map(FrEngPlanNodeDO::getDelayOwnerUserId).filter(java.util.Objects::nonNull).forEach(progressOwners::add);
        var progressNames = new java.util.HashMap<Long,String>();
        if (!progressOwners.isEmpty()) systemDirectory.usersByIds(new java.util.ArrayList<>(progressOwners))
                .forEach(u -> progressNames.put(u.userId(),java.util.Objects.toString(u.name(),"")));
        view.put("nodes", nodes.stream().map(n -> {
            var row=nodeView(n);
            row.put("delayOwnerName", progressNames.getOrDefault(n.getDelayOwnerUserId(),""));
            return row;
        }).toList());
        // R2 P1-4：现场记录透出（批次#36 在线 site/site-correct 链数据源；corrections/file_asset_ids 原样 JSONB 文本）
        view.put("siteRecords", jdbcTemplate.queryForList(
                "SELECT id, node_id, occurred_at, note, completion, author_user_id::text, "
                        + "file_asset_ids::text AS file_asset_ids, corrections::text AS corrections "
                        + "FROM fr_eng_site_record WHERE company_id = ? AND project_id = ? "
                        + "ORDER BY occurred_at DESC, id DESC",
                post.orgId(), projectId));
        var actors = jdbcTemplate.queryForList("""
                SELECT DISTINCT ON (command, COALESCE(payload::jsonb->>'nodeId','')) command,
                       payload::jsonb->>'nodeId' AS node, actor_user_id AS actor
                FROM pf_command_event WHERE domain='franchise' AND org_id=? AND aggregate_type=?
                  AND aggregate_id=? AND command IN ('eng-project-from-handover','eng-prepare','eng-start',
                      'eng-node-complete','eng-deliver','eng-settle','eng-archive')
                ORDER BY command,COALESCE(payload::jsonb->>'nodeId',''),revision DESC
                """,post.orgId(),AGGREGATE,projectId);
        var actorIds=actors.stream().map(r->((Number)r.get("actor")).longValue()).distinct().toList();
        var names=new java.util.HashMap<Long,String>();
        if(!actorIds.isEmpty()) systemDirectory.usersByIds(actorIds).forEach(u->names.put(u.userId(),u.name()));
        var timelineActors=new java.util.HashMap<String,String>();
        for(var r:actors) {
            long actor=((Number)r.get("actor")).longValue();
            String key=r.get("command")+(r.get("node")==null?"":":"+r.get("node"));
            timelineActors.put(key,java.util.Objects.requireNonNullElse(names.get(actor),"经办人 "+actor));
        }
        view.put("timelineActors",timelineActors);
        if (closure != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("deliveredAt", closure.getDeliveredAt());
            c.put("recipient", closure.getRecipient());
            c.put("deliverNote", closure.getDeliverNote());
            // M09-T02 批次#37：签收凭据真实可读——交付/业主签收 assetId 透出（downloadTicket 授权查看链）
            c.put("fileAssetId", closure.getFileAssetId());
            c.put("ownerAssetId", closure.getOwnerAssetId());
            c.put("settledAt", closure.getSettledAt());
            c.put("settleNote", closure.getSettleNote());
            c.put("archivedAt", closure.getArchivedAt());
            view.put("closure", c);
        }
        return view;
    }

    static Map<String, Object> nodeView(FrEngPlanNodeDO n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", n.getId());
        row.put("nodeIndex", n.getNodeIndex());
        row.put("name", n.getName());
        row.put("startDate", n.getStartDate());
        row.put("endDate", n.getEndDate());
        row.put("status", n.getStatus());
        row.put("milestone", n.getMilestone());
        row.put("completedAt", n.getCompletedAt());
        row.put("baselineEndDate", n.getBaselineEndDate());
        row.put("deviationDays", EngProgress.deviationDays(n, EngProgress.today()));
        row.put("delayReason", n.getDelayReason());
        row.put("delayOwnerUserId", n.getDelayOwnerUserId());
        row.put("recoveryDate", n.getRecoveryDate());
        row.put("recoveryAction", n.getRecoveryAction());
        row.put("delayHistory", n.getDelayHistory());
        row.put("corrections", n.getCorrections());
        row.put("reopenHistory", n.getReopenHistory());
        return row;
    }

    /** 财务线与老板全量；其余工程岗仅本人（D-13：finance/boss/owner 全量） */
    static boolean isFinanceOrAbove(PostSnapshot post) {
        return List.of(FranchiseRoles.FINANCE, FranchiseRoles.FINANCE_LEAD,
                FranchiseRoles.BOSS, FranchiseRoles.OWNER).contains(post.roleCode());
    }

    // ===================== 公共 =====================

    cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureDO closureOf(PostSnapshot post, long projectId) {
        return closures.selectByProject(post.orgId(), projectId);
    }

    FrEngProjectDO requireProject(PostSnapshot post, long projectId) {
        FrEngProjectDO project = projects.selectById(projectId);
        if (project == null || project.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "工程不存在或无权查看");
        }
        return project;
    }

    /** owner 动作公共前置：对象归属+白名单+owner（D-14） */
    FrEngProjectDO requireOwned(PostSnapshot post, long projectId, String action) {
        FrEngProjectDO project = requireProject(post, projectId);
        EngGuard.requireActionAllowed(closures.selectByProject(post.orgId(), projectId), action);
        EngGuard.requireOwner(project, post.userId());
        return project;
    }

    /** 工程聚合版本递增（CAS 空更新仅推 revision；command_event 按聚合+revision 防重须单调） */
    long bumpRevision(PostSnapshot post, FrEngProjectDO project) {
        long revision = project.getRevision() == null ? 0 : project.getRevision();
        Revisions.requireUpdated(projects.updateByRevision(project.getId(), revision, u -> {
        }));
        project.setRevision(revision + 1);
        return revision + 1;
    }

    /** 状态机固化（各动作收尾调用） */
    void recalcStatus(PostSnapshot post, FrEngProjectDO project) {
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), project.getId());
        FrEngClosureDO closure = closures.selectByProject(post.orgId(), project.getId());
        String status = EngGuard.deriveStatus(project, nodes, closure);
        if (!status.equals(project.getStatus())) {
            projects.updateById(new FrEngProjectDO().setId(project.getId()).setStatus(status));
            project.setStatus(status);
        }
    }
}
