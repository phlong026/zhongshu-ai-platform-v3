package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.ContentCustomerOutcomesPort;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;

/**
 * 获客聚合（D-1.5-11，1.16 零新表）：fr_lead/fr_customer 按来源（内容发布:{publicationId}）×内容×转化的
 * SQL 聚合。客资登记复用 /franchise/leads（source 携带约定前缀），本域只读聚合。
 */
@Service
public class ContentGrowthService {

    /** leads source 约定前缀（D-1.5-11）；登记入口在 CRM /franchise/leads，此处只认领。 */
    public static final String SOURCE_PREFIX = "内容发布:";

    private final JdbcTemplate jdbcTemplate;

    @Resource
    private ContentCustomerOutcomesPort outcomes;

    public ContentGrowthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Customer-visible video consultation sources. A publication must still be published and belong to
     * the current organization; access follows the existing customer owner/team visibility rules.
     */
    public List<Map<String, Object>> customerSources(PostSnapshot post, long customerId) {
        List<Map<String, Object>> customers = jdbcTemplate.queryForList(
                "SELECT owner_user_id, team_id FROM fr_customer WHERE id = ? AND company_id = ?",
                customerId, post.orgId());
        if (customers.isEmpty()) return List.of();
        Map<String, Object> customer = customers.get(0);
        long ownerId = ((Number) customer.get("owner_user_id")).longValue();
        Long teamId = customer.get("team_id") == null ? null : ((Number) customer.get("team_id")).longValue();
        boolean boss = "boss".equals(post.roleCode()) || "owner".equals(post.roleCode());
        boolean lead = "sales-lead".equals(post.roleCode());
        boolean sales = "sales".equals(post.roleCode());
        boolean visible = boss || sales && ownerId == post.userId()
                || lead && (ownerId == post.userId() || post.teamId() == null
                    || teamId != null && post.managedTeamIds().contains(teamId));
        if (!visible) return List.of();

        // Once a user can read this customer, show the full set of its linked video consultations,
        // including consultations registered by colleagues. Each consultation remains a separate lead.
        String sql = "SELECT p.id::text AS \"publicationId\", p.work_id::text AS \"workId\", p.title, "
                + "p.published_url AS \"publishedUrl\", l.id::text AS \"leadId\", l.need, l.status, "
                + "l.create_time AS \"createTime\" FROM fr_lead l "
                + "JOIN fr_content_publication p ON p.company_id = l.company_id "
                + "AND l.source = ? || p.id AND p.status = 'published' "
                + "WHERE l.company_id = ? AND l.customer_id = ? ORDER BY l.create_time DESC";
        return jdbcTemplate.queryForList(sql, SOURCE_PREFIX, post.orgId(), customerId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> summary(PostSnapshot post) {
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null || !atStore(post) || !(isBoss(post) || has(post, SALES))
                || !Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM system_dept WHERE id=? AND tenant_id=? AND status=0 AND deleted=0)
                """, Boolean.class, post.orgId(), tenant))) {
            throw new ServiceException(403, "当前岗位无权查看本店内容获客统计");
        }
        var args = new MapSqlParameterSource("org", post.orgId()).addValue("user", post.userId())
                .addValue("teams", post.managedTeamIds()).addValue("prefix", SOURCE_PREFIX);
        Map<String, Long> statuses = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT status, COUNT(*) FROM fr_content_publication WHERE company_id=? GROUP BY status",
                rs -> { statuses.put(rs.getString(1), rs.getLong(2)); }, post.orgId());

        // The capture picker needs all published sources. Apply each CRM visibility rule inside its own LEFT JOIN.
        String sql = """
                SELECT p.id, p.work_id, p.title, p.published_url, l.status, c.id AS customer_id, COUNT(l.id) AS events
                FROM fr_content_publication p
                LEFT JOIN fr_lead l ON l.company_id=p.company_id AND l.source=:prefix || p.id
                """ + visibleTo(post, "l.registrar_user_id", "l.team_id") + """
                LEFT JOIN fr_customer c ON c.id=l.customer_id AND c.company_id=p.company_id AND l.status='CONVERTED'
                """ + visibleTo(post, "c.owner_user_id", "c.team_id") + """
                WHERE p.company_id=:org AND p.status='published'
                GROUP BY p.id, l.status, c.id ORDER BY p.id
                """;
        Map<Long, Publication> publications = new LinkedHashMap<>();
        Map<String, Long> leads = new LinkedHashMap<>();
        Cohort total = new Cohort();
        new NamedParameterJdbcTemplate(jdbcTemplate).query(sql, args, rs -> {
            long id = rs.getLong("id");
            Publication publication = publications.get(id);
            if (publication == null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", Long.toString(id));
                row.put("publicationId", Long.toString(id));
                row.put("workId", rs.getString("work_id"));
                row.put("title", rs.getString("title"));
                row.put("publishedUrl", rs.getString("published_url"));
                publication = new Publication(row, new Cohort());
                publications.put(id, publication);
            }
            String status = rs.getString("status");
            if (status == null) return;
            long events = rs.getLong("events");
            leads.merge(status, events, Long::sum);
            if (!"CONVERTED".equals(status) && !"LINK_REQUIRED".equals(status)) return;
            Long customer = rs.getObject("customer_id", Long.class);
            publication.cohort.add(events, customer);
            total.add(events, customer);
        });
        var signed = outcomes.signedCustomers(tenant, post.orgId(), total.customers);
        for (Publication publication : publications.values()) {
            publication.row.putAll(publication.cohort.metrics(signed));
            publication.row.put("leads", publication.cohort.consultations);
            publication.row.put("converted", publication.cohort.customers.size());
        }
        Map<String, Object> summary = new LinkedHashMap<>(total.metrics(signed));
        summary.put("publishedCount", statuses.getOrDefault("published", 0L));
        summary.put("publications", statuses);
        summary.put("leads", leads);
        summary.put("converted", total.customers.size());
        summary.put("perPublication", publications.values().stream().map(Publication::row).toList());
        return summary;
    }

    private static String visibleTo(PostSnapshot post, String owner, String team) {
        if (isBoss(post) || SALES_LEAD.equals(post.roleCode()) && post.teamId() == null) return "";
        if (SALES_LEAD.equals(post.roleCode()) && !post.managedTeamIds().isEmpty())
            return " AND (" + owner + "=:user OR " + team + " IN (:teams))\n";
        return " AND " + owner + "=:user\n";
    }

    private record Publication(Map<String, Object> row, Cohort cohort) {}

    private static class Cohort {
        long consultations;
        final Set<Long> customers = new HashSet<>();

        void add(long count, Long customer) {
            consultations += count;
            if (customer != null) customers.add(customer);
        }

        Map<String, Object> metrics(Map<Long, ContentCustomerOutcomesPort.SignedCustomer> signed) {
            long signedCount = 0, paidCount = 0;
            for (Long customer : customers) {
                var outcome = signed.get(customer);
                if (outcome == null) continue;
                signedCount++;
                if (outcome.hasNetReceipts()) paidCount++;
            }
            return Map.of("consultations", consultations, "linkedCustomers", customers.size(),
                    "signedCustomers", signedCount, "netPaidCustomers", paidCount);
        }
    }
}
