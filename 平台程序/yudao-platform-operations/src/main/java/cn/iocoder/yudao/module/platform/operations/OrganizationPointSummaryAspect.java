package cn.iocoder.yudao.module.platform.operations;

import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

/** Preserve directory authorization, then report settled consumption separately from pending reserves. */
@Aspect
@Component
public class OrganizationPointSummaryAspect {
    private final JdbcTemplate jdbc;
    public OrganizationPointSummaryAspect(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @AfterReturning(value="execution(* cn.iocoder.yudao.module.platform.authz.service.OrgDirectoryService.pointSummary(..)) && args(orgId)",returning="result",argNames="orgId,result")
    public void settledSummary(long orgId,Map<String,Object> result) {
        String month=YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        // All five financial values come from one statement and one database snapshot.
        // billing_month remains the original task month, including cross-month settlements.
        result.putAll(jdbc.queryForMap("""
          SELECT (SELECT balance::text FROM org_point_account WHERE org_id=?) AS balance,
            (SELECT status FROM org_point_account WHERE org_id=?) AS status,
            (SELECT COALESCE(-SUM(r.available_delta),0)::text FROM org_point_ledger r
              WHERE r.org_id=? AND r.type='RESERVE' AND NOT EXISTS
                (SELECT 1 FROM org_point_ledger t WHERE t.org_id=r.org_id AND t.operation_id=r.operation_id AND t.type IN ('SETTLE','RELEASE'))) AS in_flight,
            (SELECT COALESCE(SUM(-r.available_delta-t.available_delta),0)::text
              FROM org_point_ledger t JOIN org_point_ledger r ON r.org_id=t.org_id AND r.operation_id=t.operation_id AND r.type='RESERVE'
              WHERE t.org_id=? AND t.type='SETTLE' AND t.billing_month=?) AS month_used,
            (SELECT COALESCE(SUM(-r.available_delta-t.available_delta),0)::text
              FROM org_point_ledger t JOIN org_point_ledger r ON r.org_id=t.org_id AND r.operation_id=t.operation_id AND r.type='RESERVE'
              WHERE t.org_id=? AND t.type='SETTLE') AS total_used
          """,orgId,orgId,orgId,orgId,month,orgId));
    }
}
