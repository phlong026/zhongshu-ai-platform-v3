package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 全局积分价格的只读历史，与公司流水及历史设计价目分离。 */
@RestController
@Tag(name = "能力定价")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@RequestMapping("/admin-api/platform/org-points")
public class OrgPointPricingController {
    private final OrgPointHeadquartersService headquarters;
    private final JdbcTemplate jdbc;

    public OrgPointPricingController(OrgPointHeadquartersService headquarters, DataSource dataSource) {
        this.headquarters = headquarters;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record History(String id, String subject, String label, String unit,
                          long priceMicros, long minCharge, int version, String status,
                          Instant effectiveAt, String operatorName, String reason) { }

    @GetMapping("/price-history")
    @PreAuthorize("@ss.hasPermission('platform:org-point:price:query')")
    public CommonResult<List<History>> history(@RequestParam(name = "subject", required = false) String subject) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) throw new ServiceException(401, "未登录");
        // 与现价查询复用总部租户、启用账号与任职/超管校验；不能只靠菜单权限放行。
        Map<String, OrgPointPriceService.CatalogRow> catalog = headquarters.catalog(actor).stream()
                .collect(Collectors.toMap(OrgPointPriceService.CatalogRow::subject, row -> row));
        String filter = subject == null || subject.isBlank() ? null : subject;
        if (filter != null && !catalog.containsKey(filter)) throw new ServiceException(400, "未知计费能力");
        String sql = "SELECT p.*, a.reason, COALESCE(u.nickname,p.operator) AS operator_name "
                + "FROM org_point_subject_price p "
                + "LEFT JOIN org_point_price_audit a ON a.price_id=p.id "
                + "LEFT JOIN system_users u ON u.id::text=p.operator "
                + (filter == null ? "" : "WHERE p.subject=? ")
                + "ORDER BY p.effective_at DESC,p.id DESC LIMIT 200";
        Object[] args = filter == null ? new Object[0] : new Object[]{filter};
        return success(jdbc.query(sql, (rs, index) -> {
            String key = rs.getString("subject");
            var base = catalog.get(key);
            return new History(rs.getString("id"), key, base == null ? key : base.label(),
                    rs.getString("unit"), rs.getLong("price_micros"), rs.getLong("min_charge"),
                    rs.getInt("version"), rs.getString("status"), rs.getTimestamp("effective_at").toInstant(),
                    rs.getString("operator_name"), rs.getString("reason"));
        }, args));
    }
}
