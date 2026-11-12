package cn.iocoder.yudao.module.franchise.design.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

/** 不依赖远端 provider 就绪状态；报表仅使用 B 本地命令、报价快照和已保存的收据成本。 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class DesignOperationsService {
    private static final Logger log = LoggerFactory.getLogger(DesignOperationsService.class);
    private final JdbcTemplate jdbc;
    public record Price(String id, long version, String operation, String resolution, String unit,
                        long promptPoints, long imagePoints, long usagePoints, int maxImageUnits, boolean active) { }
    public record Cost(String id, String orgId, String operation, String status, Long priceVersion,
                       Long reservedPoints, Long chargedPoints, Long refundedPoints, long settlementVersion,
                       String costState, Long chargedCostMicros, Long reservedCostMicros, Long refundedCostMicros,
                       boolean audited, Instant createTime) { }
    public DesignOperationsService(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
    }
    public PageResult<Price> prices(int pageNo, int pageSize) {
        return page("prices", """
                SELECT id,version,operation,resolution,unit,prompt_points,image_points,usage_points,max_image_units,active
                FROM fr_design_price WHERE tenant_id=?
                """, "operation,resolution,version DESC,id DESC", pageNo, pageSize, null,
                (rs, row) -> new Price(rs.getString("id"), rs.getLong("version"), rs.getString("operation"),
                        rs.getString("resolution"), rs.getString("unit"), rs.getLong("prompt_points"), rs.getLong("image_points"),
                        rs.getLong("usage_points"), rs.getInt("max_image_units"), rs.getBoolean("active")));
    }
    public PageResult<Cost> costs(int pageNo, int pageSize, String orgId) {
        return page("costs", """
                SELECT c.command_id,c.org_id,c.operation,c.state,c.settlement_version,c.settlement_json,c.audited,c.created_at,
                       b.price_version,b.reserved_points,b.charged_points,b.refunded_points
                FROM fr_design_remote_command c LEFT JOIN fr_design_billing b
                     ON b.command_id=c.command_id AND b.tenant_id=c.tenant_id
                WHERE c.tenant_id=?
                """, "c.created_at DESC,c.command_id DESC", pageNo, pageSize, orgId, (rs, row) -> {
            String persisted = rs.getString("settlement_json");
            Settlement fact = persisted == null ? null : JsonUtils.parseObject(persisted, Settlement.class);
            return new Cost(rs.getString("command_id"), rs.getString("org_id"), rs.getString("operation"), rs.getString("state"),
                    rs.getObject("price_version", Long.class), rs.getObject("reserved_points", Long.class),
                    rs.getObject("charged_points", Long.class), rs.getObject("refunded_points", Long.class),
                    rs.getLong("settlement_version"), fact == null ? "UNAVAILABLE" : "RECORDED",
                    fact == null ? null : fact.cumulativeChargedCostMicros(), fact == null ? null : fact.cumulativeReservedCostMicros(),
                    fact == null ? null : fact.cumulativeRefundedCostMicros(), rs.getBoolean("audited"), rs.getTimestamp("created_at").toInstant());
        });
    }
    private <T> PageResult<T> page(String operation, String sql, String order, int pageNo, int pageSize,
                                   String orgId, RowMapper<T> mapper) {
        long tenant = requireTenant();
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw new ServiceException(400, "分页范围无效");
        var args = new ArrayList<Object>();
        args.add(tenant);
        if (orgId != null && !orgId.isBlank()) {
            try {
                long org = Long.parseLong(orgId);
                if (org <= 0 || !Long.toString(org).equals(orgId)) throw new NumberFormatException();
                args.add(org);
                sql += " AND c.org_id=?";
            } catch (NumberFormatException error) { throw new ServiceException(400, "组织编号无效"); }
        }
        try {
            Long total = jdbc.queryForObject("SELECT count(*) FROM (" + sql + ") rows", Long.class, args.toArray());
            args.add(pageSize);
            args.add((long) (pageNo - 1) * pageSize);
            return new PageResult<>(jdbc.query(sql + " ORDER BY " + order + " LIMIT ? OFFSET ?", mapper, args.toArray()), total);
        } catch (RuntimeException error) {
            log.error("[designOps][operation={},tenant={}] 本地设计运营查询失败", operation, tenant, error);
            throw error;
        }
    }
    static long requireTenant() {
        var user = SecurityFrameworkUtils.getLoginUser();
        if (user == null || user.getId() == null || !Integer.valueOf(2).equals(user.getUserType())) {
            throw new ServiceException(403, "需要有效管理员身份");
        }
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null || user.getTenantId() == null || !Objects.equals(tenant, user.getTenantId()) || TenantContextHolder.isIgnore()) {
            throw new ServiceException(403, "管理员租户不匹配");
        }
        return tenant;
    }
}
