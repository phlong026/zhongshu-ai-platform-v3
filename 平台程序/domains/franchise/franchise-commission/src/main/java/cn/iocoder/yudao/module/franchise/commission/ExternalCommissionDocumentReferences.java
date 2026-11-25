package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.CustomerDocumentReferencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** Both superseded and current files keep following their customer's current access policy. */
@Component
public class ExternalCommissionDocumentReferences implements CustomerDocumentReferencePort {
    private final JdbcTemplate jdbc;
    public ExternalCommissionDocumentReferences(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Set<Long> customerIds(long tenant, long company, long asset) {
        if (!java.util.Objects.equals(TenantContextHolder.getTenantId(), tenant)) throw exception(RESOURCE_FORBIDDEN);
        String ref = JsonUtils.toJsonString(List.of(Map.of("assetId", Long.toString(asset))));
        return Set.copyOf(jdbc.queryForList("""
                SELECT customer_id FROM fr_commission_external_version
                  WHERE tenant_id=? AND company_id=? AND data->'attachments' @> CAST(? AS jsonb)
                UNION SELECT customer_id FROM fr_commission_external_result
                  WHERE tenant_id=? AND company_id=? AND attachments @> CAST(? AS jsonb)
                """, Long.class, tenant, company, ref, tenant, company, ref));
    }
}
