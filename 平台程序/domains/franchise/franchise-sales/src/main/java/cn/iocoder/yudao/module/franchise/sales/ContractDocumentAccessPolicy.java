package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.ContractDocumentAccessPort;
import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** Contract attachments retain their customer ACL, including files replaced in immutable evidence history. */
@Component
public class ContractDocumentAccessPolicy implements ContractDocumentAccessPort {
    private final JdbcTemplate jdbc;
    private final CurrentPost posts;
    private final CustomerSchemePort customers;
    public ContractDocumentAccessPolicy(JdbcTemplate jdbc, CurrentPost posts, CustomerSchemePort customers) {
        this.jdbc = jdbc; this.posts = posts; this.customers = customers;
    }

    @Override public void requireReadable(PostSnapshot post, long contractId, long assetId) {
        long tenant = requireActor(post);
        var rows = jdbc.queryForList("""
                SELECT c.customer_id FROM fr_sale_contract c JOIN system_dept d ON d.id=c.company_id
                WHERE c.id=? AND c.company_id=? AND c.asset_id=? AND NOT c.deleted
                  AND d.tenant_id=? AND d.status=0 AND d.deleted=0
                """, Long.class, contractId, post.orgId(), assetId, tenant);
        if (rows.size() != 1) throw exception(RESOURCE_FORBIDDEN);
        requireCustomer(post, rows.get(0));
    }

    @Override public void requireAssetReadable(PostSnapshot post, long assetId) {
        requireActor(post);
        var customerIds = jdbc.queryForList("""
                SELECT DISTINCT c.customer_id FROM fr_sale_contract c
                WHERE c.company_id=? AND (c.asset_id=? OR EXISTS(
                    SELECT 1 FROM fr_sale_contract_evidence_event e
                    WHERE e.contract_id=c.id AND e.company_id=c.company_id
                      AND (e.before_evidence->>'assetId'=? OR e.after_evidence->>'assetId'=?)))
                """, Long.class, post.orgId(), assetId, Long.toString(assetId), Long.toString(assetId));
        // Unlinked historical channels retain their existing behavior. Once linked, generic URLs cannot bypass CRM visibility.
        for (long customerId : customerIds) requireCustomer(post, customerId);
    }

    private void requireCustomer(PostSnapshot post, long customerId) {
        try { customers.requireDocumentReadable(post, customerId); }
        catch (cn.iocoder.yudao.framework.common.exception.ServiceException error) {
            if (error.getCode() == 1_110_000_000 || error.getCode() == 1_110_000_001) throw exception(RESOURCE_FORBIDDEN);
            throw error;
        }
    }

    private long requireActor(PostSnapshot post) {
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null || !post.equals(posts.require()) || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM system_dept d JOIN pf_org_unit o ON o.dept_id=d.id
                WHERE d.id=? AND d.tenant_id=? AND d.status=0 AND d.deleted=0 AND o.kind='ORG' AND o.status='ACTIVE')
                """, Boolean.class, post.orgId(), tenant))) throw exception(RESOURCE_FORBIDDEN);
        return tenant;
    }
}
