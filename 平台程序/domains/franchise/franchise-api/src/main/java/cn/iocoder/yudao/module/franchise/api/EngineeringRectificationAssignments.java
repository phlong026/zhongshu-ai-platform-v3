package cn.iocoder.yudao.module.franchise.api;

/** Live eligibility for field rectification responsibilities across the engineering and workbench projections. */
public final class EngineeringRectificationAssignments {
    private EngineeringRectificationAssignments() {}

    public static final String ACTIVE_POSTS = """
        FROM pf_staff_post s JOIN pf_org_unit org ON org.dept_id=s.org_id AND org.status='ACTIVE'
        WHERE s.status='ACTIVE' AND s.role_code IN ('engineer','project-manager')
        """;

    public static final String CURRENT_POST = "SELECT s.id "+ACTIVE_POSTS+" AND s.id=? AND s.org_id=? AND s.user_id=?";

    /** Join after the business row r and its project p. Personal work can only be routed to an active participant. */
    public static final String ROUTING = """
        CROSS JOIN LATERAL (
          SELECT bool_or(s.user_id=r.owner_user_id) AS owner_active,bool_or(s.user_id=p.manager_user_id) AS manager_active
        """+ACTIVE_POSTS+"""
          AND s.org_id=r.company_id AND s.user_id IN (p.manager_user_id,p.assigned_engineer_user_id)
        ) assigned
        CROSS JOIN LATERAL (
          SELECT CASE WHEN assigned.owner_active THEN r.owner_user_id WHEN assigned.manager_active THEN p.manager_user_id END AS handler,
                 CASE WHEN assigned.owner_active THEN 'ASSIGNED' WHEN NOT coalesce(assigned.manager_active,false) THEN 'ORG_UNASSIGNED'
                      WHEN r.owner_user_id IS NULL THEN 'UNASSIGNED' ELSE 'REASSIGN' END AS state
        ) routing
        """;

    public static final String STATE_LABEL = """
        CASE routing.state WHEN 'ORG_UNASSIGNED' THEN ' · 待组织分派' WHEN 'UNASSIGNED' THEN ' · 待分派'
             WHEN 'REASSIGN' THEN ' · 待重新分派' ELSE '' END
        """;
}
