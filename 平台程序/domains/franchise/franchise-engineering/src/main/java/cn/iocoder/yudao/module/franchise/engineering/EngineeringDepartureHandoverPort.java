package cn.iocoder.yudao.module.franchise.engineering;

import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverPort;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.ENGINEERING_INPUT_INVALID;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;

/** 当前负责人仍掌握归档后催收等办理权，按项目承接，历史经办/完工/归档/延期记录保留。 */
@Component
public class EngineeringDepartureHandoverPort implements DepartureHandoverPort {

    private static final String RESPONSIBILITY = """
            p.company_id = :org AND (p.manager_user_id = :from OR p.assigned_engineer_user_id = :from
              OR EXISTS (SELECT 1 FROM fr_eng_plan_node n WHERE n.company_id = :org
                AND n.project_id = p.id AND n.status <> 'DONE' AND n.delay_owner_user_id = :from))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final StaffPostService staffPosts;

    public EngineeringDepartureHandoverPort(JdbcTemplate jdbc, StaffPostService staffPosts) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.staffPosts = staffPosts;
    }

    @Override public String domain() { return "franchise-engineering"; }
    @Override public String label() { return "工程任务"; }

    @Override
    public long scanInFlight(long fromUserId, long orgId) {
        // 同人兼任两个负责人、同项目多个延期节点，都只统计一次项目。
        return jdbc.queryForObject("SELECT COUNT(*) FROM fr_eng_project p WHERE " + RESPONSIBILITY,
                args(fromUserId, orgId), Long.class);
    }

    @Override
    public void validateReceiver(long toUserId, long orgId) {
        boolean valid = staffPosts.listActivePosts(toUserId).stream()
                .filter(p -> p.getOrgId() == orgId)
                .filter(p -> FranchiseRoles.ENGINEER.equals(p.getRoleCode())
                        || FranchiseRoles.PROJECT_MANAGER.equals(p.getRoleCode()))
                .anyMatch(p -> staffPosts.findActivePost(toUserId, p.getId()).isPresent());
        if (!valid) throw exception(HANDOVER_TARGET_ROLE_MISMATCH);
    }

    @Override
    public int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId) {
        var params = args(fromUserId, orgId).addValue("to", toUserId);
        try {
            // 现有工程写入有项目→节点和节点→项目两种顺序；交接只尝试锁，不等待反向锁。
            List<Project> projects = jdbc.query("SELECT p.id, p.manager_user_id, p.assigned_engineer_user_id "
                    + "FROM fr_eng_project p WHERE " + RESPONSIBILITY + " ORDER BY p.id FOR UPDATE OF p NOWAIT",
                    params, (rs, row) -> new Project(rs.getLong("id"), rs.getLong("manager_user_id"),
                            (Long) rs.getObject("assigned_engineer_user_id")));
            if (projects.isEmpty()) return 0;
            params.addValue("ids", projects.stream().map(Project::id).toList());
            List<DelayedNode> nodes = jdbc.query("""
                    SELECT id, project_id FROM fr_eng_plan_node WHERE company_id = :org
                      AND project_id IN (:ids) AND status <> 'DONE' AND delay_owner_user_id = :from
                    ORDER BY id FOR UPDATE NOWAIT
                    """, params, (rs, row) -> new DelayedNode(rs.getLong("id"), rs.getLong("project_id")));
            Set<Long> delayedProjects = nodes.stream().map(DelayedNode::projectId).collect(Collectors.toSet());
            projects = projects.stream().filter(p -> p.ownedBy(fromUserId) || delayedProjects.contains(p.id())).toList();
            if (projects.isEmpty()) return 0;
            for (Project project : projects) {
                if (!project.ownedBy(fromUserId) && !project.ownedBy(toUserId)) {
                    // 历史换负责人可能遗留旧延期责任，不能覆盖另一位现任负责人来强行交接。
                    throw exception(ENGINEERING_INPUT_INVALID,
                            "存在归属其他负责人的延期事项，请由该项目现负责人先调整延期责任，或选择其接收后再交接");
                }
            }
            if (!nodes.isEmpty()) {
                params.addValue("nodeIds", nodes.stream().map(DelayedNode::id).toList());
                jdbc.update("""
                        UPDATE fr_eng_plan_node SET delay_owner_user_id = :to, update_time = now()
                        WHERE company_id = :org AND id IN (:nodeIds)
                        """, params);
            }
            params.addValue("ids", projects.stream().map(Project::id).toList());
            return jdbc.update("""
                    UPDATE fr_eng_project SET
                      manager_user_id = CASE WHEN manager_user_id = :from THEN :to ELSE manager_user_id END,
                      assigned_engineer_user_id = CASE WHEN assigned_engineer_user_id = :from
                                                       THEN :to ELSE assigned_engineer_user_id END,
                      revision = revision + 1, update_time = now()
                    WHERE company_id = :org AND id IN (:ids)
                    """, params);
        } catch (PessimisticLockingFailureException conflict) {
            throw exception(HANDOVER_TRANSFER_INCOMPLETE);
        }
    }

    private record Project(long id, long manager, Long assigned) {
        boolean ownedBy(long userId) { return manager == userId || assigned != null && assigned == userId; }
    }
    private record DelayedNode(long id, long projectId) { }

    private static MapSqlParameterSource args(long fromUserId, long orgId) {
        return new MapSqlParameterSource("org", orgId).addValue("from", fromUserId);
    }
}
