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
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
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
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 工程档案：手工新建/增项建档（查重=同组织未归档项目名唯一 008）、移交自动建档（内部 Port）、
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

    public EngProjectService(FrEngProjectMapper projects, FrEngDocumentMapper documents,
                             FrEngPlanVersionMapper planVersions, FrEngPlanNodeMapper planNodes,
                             FrEngClosureMapper closures, FrEngCollectionMapper collections,
                             FrEngCollectionTermMapper collectionTerms, FranchiseCommands commands,
                             StaffPostService staffPosts, JdbcTemplate jdbcTemplate) {
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
    }

    public record CreateCommand(String name, String customer, String city, String phone,
                                BigDecimal area, Integer floors, Long parentProjectId) {
    }

    public record ManagerCommand(long engineerUserId, String note) {
    }

    // ===================== 建档 =====================

    /** 手工新建/增项（write 码+owner=本人建档即负责人；蓝本 create() :158） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(PostSnapshot post, CreateCommand cmd, String idempotencyKey) {
        var replayed = commands.replayed(post, idempotencyKey, normalizeCreate(cmd));
        if (replayed.isPresent()) {
            return detail(post, replayed.get().getAggregateId());
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
        FrEngProjectDO parent = null;
        if (cmd.parentProjectId() != null) {
            parent = requireProject(post, cmd.parentProjectId());
            EngGuard.requireOwner(parent, post.userId());
        }
        if (projects.findActiveByName(post.orgId(), name) != null) {
            throw exception(PROJECT_DUPLICATE);
        }
        FrEngProjectDO project = new FrEngProjectDO()
                .setCompanyId(post.orgId())
                .setCustomerName(customer).setPhone(phone.isBlank() ? null : phone)
                .setSourceType(parent == null ? FrEngProjectDO.SOURCE_MANUAL : FrEngProjectDO.SOURCE_INCREMENT)
                .setParentProjectId(parent == null ? null : parent.getId())
                .setManagerUserId(post.userId()).setAssignedEngineerUserId(post.userId())
                .setCity(city).setArea(cmd.area()).setFloors(floors).setName(name)
                .setStatus(FrEngProjectDO.STATUS_PENDING_START)
                .setRevision(1L).setAuthorUserId(post.userId());
        projects.insert(project);
        if (parent != null) {
            // 增项带入原项目图纸与客户/城市（蓝本 create()）
            project.setCustomerName(StrUtil.blankToDefault(parent.getCustomerName(), customer));
            projects.updateById(new FrEngProjectDO().setId(project.getId())
                    .setCustomerName(project.getCustomerName()));
            for (FrEngDocumentDO doc : documents.selectByProject(post.orgId(), parent.getId())) {
                if (FrEngDocumentDO.KIND_DRAWING.equals(doc.getKind())) {
                    documents.insert(new FrEngDocumentDO().setCompanyId(post.orgId())
                            .setProjectId(project.getId()).setName(doc.getName())
                            .setFileRef(doc.getFileRef()).setKind(doc.getKind()).setVersion(doc.getVersion()));
                }
            }
            project.setDrawingVersion(parent.getDrawingVersion());
        }
        initTemplatePlan(post, project);
        commands.record(post, AGGREGATE, project.getId(), 1,
                parent == null ? "eng-project-create" : "eng-project-create-increment",
                normalizeCreate(cmd), idempotencyKey);
        return detail(post, project.getId());
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
        return m;
    }

    /** 移交自动建档（内部 Port；幂等=部分唯一兜底+命中刷新交底资料，蓝本 merge 语义） */
    @Transactional(rollbackFor = Exception.class)
    public long receiveFromHandover(PostSnapshot post, long commissionId, Long customerId, String customerName,
                                    String city, String phone, BigDecimal area, Integer floors,
                                    long managerUserId, String drawingVersion, String drawingFile,
                                    List<Map<String, String>> documentRows) {
        List<FrEngProjectDO> existing = projects.selectByCommission(post.orgId(), commissionId);
        if (!existing.isEmpty()) {
            // 重复回调：刷新交底资料（不动合同/负责人/现场记录——蓝本「工程经办人、已签施工合同和现场记录由工程维护」）
            FrEngProjectDO project = existing.get(0);
            projects.updateById(new FrEngProjectDO().setId(project.getId())
                    .setCity(city).setPhone(phone).setArea(area).setFloors(floors)
                    .setDrawingVersion(drawingVersion).setUpdateTime(java.time.OffsetDateTime.now()));
            refreshDocuments(post, project.getId(), documentRows);
            return project.getId();
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
                .setRevision(1L).setAuthorUserId(managerUserId);
        projects.insert(project);
        refreshDocuments(post, project.getId(), documentRows);
        initTemplatePlan(post, project);
        commands.record(post, AGGREGATE, project.getId(), 1, "eng-project-from-handover",
                Map.of("commissionId", commissionId, "managerUserId", managerUserId,
                        "drawingVersion", StrUtil.nullToEmpty(drawingVersion)), null);
        return project.getId();
    }

    private void refreshDocuments(PostSnapshot post, long projectId, List<Map<String, String>> rows) {
        jdbcTemplate.update("DELETE FROM fr_eng_document WHERE company_id = ? AND project_id = ?", post.orgId(), projectId);
        for (Map<String, String> row : rows) {
            documents.insert(new FrEngDocumentDO().setCompanyId(post.orgId()).setProjectId(projectId)
                    .setName(row.get("name")).setFileRef(row.get("fileRef"))
                    .setKind(row.getOrDefault("kind", FrEngDocumentDO.KIND_DOCUMENT))
                    .setVersion(row.get("version")));
        }
    }

    /** 两入口共用的模板初始化：v1 快照（TEMPLATE）+ 当前节点行（日期留空待填） */
    private void initTemplatePlan(PostSnapshot post, FrEngProjectDO project) {
        List<EngTemplate.NodeDraft> drafts = EngTemplate.buildNodes(project.getFloors());
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

    // ===================== 换负责人 =====================

    /** 换负责人（D-3：在岗校验 010+CAS+审计；旧负责人立即失权） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeManager(PostSnapshot post, long projectId, ManagerCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projects.lockById(projectId);
        requireOwned(post, projectId, "project");
        var replayed = commands.replayed(post, idempotencyKey, Map.of("engineerUserId", cmd.engineerUserId()));
        if (replayed.isPresent()) {
            return detail(post, projectId);
        }
        // 在岗工程线（engineer / project-manager——HandoverService.requireManager 同款口径，F1 继承）
        List<StaffPostDO> engineers = staffPosts.listActivePostsInOrg(post.orgId(), FranchiseRoles.ENGINEER);
        List<StaffPostDO> managers = staffPosts.listActivePostsInOrg(post.orgId(), FranchiseRoles.PROJECT_MANAGER);
        boolean active = engineers.stream().anyMatch(p -> p.getUserId() == cmd.engineerUserId())
                || managers.stream().anyMatch(p -> p.getUserId() == cmd.engineerUserId());
        if (!active) {
            throw exception(MANAGER_INVALID);
        }
        long revision = project.getRevision();
        Revisions.requireUpdated(projects.updateByRevision(projectId, revision, u -> u
                .set(FrEngProjectDO::getManagerUserId, cmd.engineerUserId())
                .set(FrEngProjectDO::getAssignedEngineerUserId, cmd.engineerUserId())));
        commands.record(post, AGGREGATE, projectId, revision + 1, "eng-project-manager",
                Map.of("fromUserId", project.getManagerUserId(), "toUserId", cmd.engineerUserId(),
                        "note", StrUtil.nullToEmpty(cmd.note())), idempotencyKey);
        return detail(post, projectId);
    }

    // ===================== 读 =====================

    public List<Map<String, Object>> list(PostSnapshot post, String status) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FrEngProjectDO>()
                .eq(FrEngProjectDO::getCompanyId, post.orgId())
                .orderByDesc(FrEngProjectDO::getUpdateTime);
        if (StrUtil.isNotBlank(status)) {
            wrapper.eq(FrEngProjectDO::getStatus, status);
        }
        boolean full = isFinanceOrAbove(post);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrEngProjectDO p : projects.selectList(wrapper)) {
            boolean own = post.userId() == p.getManagerUserId()
                    || (p.getAssignedEngineerUserId() != null && post.userId() == p.getAssignedEngineerUserId());
            if (!(full || own)) {
                continue; // 工程岗仅见本人工程（蓝本 allowed=同公司+负责人/财务）
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("customer", p.getCustomerName());
            row.put("city", p.getCity());
            row.put("status", p.getStatus());
            row.put("managerUserId", p.getManagerUserId());
            row.put("actualStartAt", p.getActualStartAt());
            row.put("sourceType", p.getSourceType());
            if (full || own) {
                row.put("contractTotalCents", p.getContractTotalCents());
                row.put("firstCents", p.getFirstCents());
            }
            rows.add(row);
        }
        return rows;
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
        view.put("parentProjectId", project.getParentProjectId());
        view.put("managerUserId", project.getManagerUserId());
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
            view.put("contractVersion", project.getContractVersion());
            view.put("contractDate", project.getContractDate());
            view.put("contractAssetId", project.getContractAssetId());
        }
        view.put("documents", documents.selectByProject(post.orgId(), projectId).stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("name", d.getName());
            row.put("kind", d.getKind());
            row.put("version", d.getVersion());
            row.put("fileRef", d.getFileRef());
            return row;
        }).toList());
        view.put("nodes", nodes.stream().map(EngProjectService::nodeView).toList());
        if (closure != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("deliveredAt", closure.getDeliveredAt());
            c.put("recipient", closure.getRecipient());
            c.put("deliverNote", closure.getDeliverNote());
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
