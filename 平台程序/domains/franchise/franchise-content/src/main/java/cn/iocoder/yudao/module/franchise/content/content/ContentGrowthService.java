package cn.iocoder.yudao.module.franchise.content.content;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/**
 * 获客聚合（D-1.5-11，1.16 零新表）：fr_lead/fr_customer 按来源（内容发布:{publicationId}）×内容×转化的
 * SQL 聚合。客资登记复用 /franchise/leads（source 携带约定前缀），本域只读聚合。
 */
@Service
public class ContentGrowthService {

    /** leads source 约定前缀（D-1.5-11）；登记入口在 CRM /franchise/leads，此处只认领。 */
    public static final String SOURCE_PREFIX = "内容发布:";

    private final JdbcTemplate jdbcTemplate;

    public ContentGrowthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Customer-visible video consultation sources. A publication must still be published and belong to
     * the current organization; lead rows are additionally filtered by the current sales visibility.
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
        String sql = "SELECT p.id::text AS publicationId, p.work_id::text AS workId, p.title, "
                + "p.published_url AS publishedUrl, l.id::text AS leadId, l.need, l.status, "
                + "l.create_time AS createTime FROM fr_lead l "
                + "JOIN fr_content_publication p ON p.company_id = l.company_id "
                + "AND l.source = ? || p.id AND p.status = 'published' "
                + "WHERE l.company_id = ? AND l.customer_id = ? ORDER BY l.create_time DESC";
        return jdbcTemplate.queryForList(sql, SOURCE_PREFIX, post.orgId(), customerId);
    }

    public Map<String, Object> summary(long companyId) {
        // 发布统计：五态计数
        Map<String, Object> publications = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS total FROM fr_content_publication "
                        + "WHERE company_id = ? GROUP BY status", companyId)
                .forEach(row -> publications.put(String.valueOf(row.get("status")),
                        ((Number) row.get("total")).intValue()));

        // 内容来源客资：source 以「内容发布:」开头的 leads 状态计数（LINK_REQUIRED=待关联即 capture 面）
        Map<String, Object> leads = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS total FROM fr_lead "
                        + "WHERE company_id = ? AND source LIKE ? GROUP BY status",
                companyId, SOURCE_PREFIX + "%")
                .forEach(row -> leads.put(String.valueOf(row.get("status")),
                        ((Number) row.get("total")).intValue()));

        // 转化：source 命中前缀且已关联客户（fr_customer.created_by_lead 或 status=LINKED 语义按 crm 表实构）
        Integer converted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fr_lead WHERE company_id = ? AND source LIKE ? "
                        + "AND status IN ('LINKED','CONVERTED')",
                Integer.class, companyId, SOURCE_PREFIX + "%");

        // 按发布记录维度：每条 published 的客资数
        List<Map<String, Object>> perPublication = jdbcTemplate.queryForList(
                "SELECT p.id::text AS id, p.id::text AS publicationId, p.work_id::text AS workId, p.title, p.published_url AS publishedUrl, COUNT(l.id) AS leads, "
                        + "SUM(CASE WHEN l.status IN ('LINKED','CONVERTED') THEN 1 ELSE 0 END) AS converted "
                        + "FROM fr_content_publication p "
                        + "LEFT JOIN fr_lead l ON l.company_id = p.company_id "
                        + "AND l.source = ? || p.id "
                        + "WHERE p.company_id = ? AND p.status = 'published' "
                        + "GROUP BY p.id, p.title, p.published_url ORDER BY p.id",
                SOURCE_PREFIX, companyId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("publications", publications);
        summary.put("leads", leads);
        summary.put("converted", converted == null ? 0 : converted);
        summary.put("perPublication", perPublication);
        return summary;
    }

}
