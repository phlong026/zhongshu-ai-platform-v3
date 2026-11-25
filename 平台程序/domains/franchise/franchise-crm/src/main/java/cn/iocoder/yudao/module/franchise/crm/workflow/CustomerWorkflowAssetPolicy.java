package cn.iocoder.yudao.module.franchise.crm.workflow;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.AssetAccessPort;
import cn.iocoder.yudao.module.franchise.api.AssetVisibilityPort;
import cn.iocoder.yudao.module.franchise.api.CustomerWorkflowAssetScopePort;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;

/** Customer ownership is derived from immutable workflow references, including replaced historical files. */
@Component
public class CustomerWorkflowAssetPolicy implements AssetVisibilityPort, CustomerWorkflowAssetScopePort {
    private final JdbcTemplate jdbc;
    private final CurrentPost posts;
    public CustomerWorkflowAssetPolicy(JdbcTemplate jdbc, CurrentPost posts) { this.jdbc = jdbc; this.posts = posts; }

    @Override public boolean supports(String type) { return "BUSINESS_DOCUMENT".equals(type) || "DESIGN_REFERENCE".equals(type); }

    @Override public void requireReadable(long tenant, AssetAccessPort.Actor actor, Resource resource) {
        PostSnapshot post = requireActor(tenant, actor);
        List<CustomerDO> owners = owners(tenant, actor.orgId(), resource.assetId());
        if (owners.isEmpty()) {
            // Before attachment, a business upload belongs only to its creator. Old design-reference behavior is unchanged.
            if ("BUSINESS_DOCUMENT".equals(resource.assetType()) && resource.creatorUserId() != actor.userId())
                throw exception(RESOURCE_FORBIDDEN);
            return;
        }
        if (owners.stream().anyMatch(customer -> !CustomerAccess.canRead(post, customer))) throw exception(RESOURCE_FORBIDDEN);
    }

    @Override public void requireReadable(long tenant, AssetAccessPort.Actor actor, long assetId) {
        PostSnapshot post = requireActor(tenant, actor);
        var owners = owners(tenant, actor.orgId(), assetId);
        if (owners.isEmpty() || owners.stream().anyMatch(customer -> !CustomerAccess.canRead(post, customer))) throw exception(RESOURCE_FORBIDDEN);
    }

    @Override public void requireReadable(PostSnapshot post, long customerId, String code, long assetId) {
        if (!post.equals(posts.require()) || !CustomerWorkflowModel.Step.parse(code).manual()) throw exception(RESOURCE_FORBIDDEN);
        long tenant = CustomerWorkflowReadService.tenant();
        var customers = jdbc.query("""
                SELECT c.id,c.company_id,c.owner_user_id,c.team_id FROM fr_customer c
                  JOIN system_dept d ON d.id=c.company_id
                  JOIN pf_org_unit o ON o.dept_id=d.id AND o.kind='ORG' AND o.status='ACTIVE'
                WHERE c.id=? AND c.company_id=? AND d.tenant_id=? AND d.status=0 AND d.deleted=0
                """, (rs, n) -> new CustomerDO().setId(rs.getLong("id")).setCompanyId(rs.getLong("company_id"))
                        .setOwnerUserId(rs.getLong("owner_user_id")).setTeamId(rs.getObject("team_id", Long.class)), customerId, post.orgId(), tenant);
        if (customers.size() != 1 || !CustomerAccess.canRead(post, customers.get(0))) throw exception(RESOURCE_FORBIDDEN);
        requireReferenced(tenant, post.orgId(), customerId, code, assetId);
    }

    /** Called inside the workflow write transaction before adding references; serializes competing first attachments. */
    public void requireAttachable(PostSnapshot post, long customerId, List<Attachment> attachments) {
        long tenant = CustomerWorkflowReadService.tenant();
        for (long asset : attachments.stream().mapToLong(attachment -> Long.parseLong(attachment.assetId())).sorted().toArray()) {
            long lock = FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("fr-workflow-asset", tenant), Long.toString(asset));
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, lock);
            if (owners(tenant, post.orgId(), asset).stream().anyMatch(customer -> customer.getId() != customerId))
                throw CustomerWorkflowRules.invalid("该附件已归属其他客户，请为当前客户重新上传");
        }
    }

    /** The caller must authorize the customer first; this only proves the exact node/history reference. */
    public void requireReferenced(long tenant, long company, long customerId, String code, long assetId) {
        if (tenant != CustomerWorkflowReadService.tenant() || assetId <= 0) throw exception(RESOURCE_FORBIDDEN);
        String json = reference(assetId);
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM fr_customer_workflow w JOIN fr_customer_workflow_step s
                    ON s.workflow_id=w.id AND s.tenant_id=w.tenant_id AND s.company_id=w.company_id
                    WHERE w.tenant_id=? AND w.company_id=? AND w.customer_id=? AND s.step_code=? AND s.attachments @> CAST(? AS jsonb)
                  UNION ALL
                  SELECT 1 FROM fr_customer_workflow w JOIN fr_customer_workflow_event e
                    ON e.workflow_id=w.id AND e.tenant_id=w.tenant_id AND e.company_id=w.company_id
                    WHERE w.tenant_id=? AND w.company_id=? AND w.customer_id=? AND e.step_code=? AND e.attachments @> CAST(? AS jsonb))
                """, Boolean.class, tenant, company, customerId, code, json, tenant, company, customerId, code, json);
        if (!Boolean.TRUE.equals(exists)) throw exception(RESOURCE_FORBIDDEN);
    }

    private List<CustomerDO> owners(long tenant, long company, long asset) {
        String json = reference(asset);
        return jdbc.query("""
                SELECT c.id,c.company_id,c.owner_user_id,c.team_id FROM fr_customer c WHERE c.company_id=? AND c.id IN (
                  SELECT w.customer_id FROM fr_customer_workflow w JOIN fr_customer_workflow_step s
                    ON s.workflow_id=w.id AND s.tenant_id=w.tenant_id AND s.company_id=w.company_id
                    WHERE w.tenant_id=? AND w.company_id=? AND s.attachments @> CAST(? AS jsonb)
                  UNION
                  SELECT w.customer_id FROM fr_customer_workflow w JOIN fr_customer_workflow_event e
                    ON e.workflow_id=w.id AND e.tenant_id=w.tenant_id AND e.company_id=w.company_id
                    WHERE w.tenant_id=? AND w.company_id=? AND e.attachments @> CAST(? AS jsonb))
                """, (rs, n) -> new CustomerDO().setId(rs.getLong("id")).setCompanyId(rs.getLong("company_id"))
                        .setOwnerUserId(rs.getLong("owner_user_id")).setTeamId(rs.getObject("team_id", Long.class)),
                company, tenant, company, json, tenant, company, json);
    }

    private PostSnapshot requireActor(long tenant, AssetAccessPort.Actor actor) {
        PostSnapshot post = posts.require();
        if (!java.util.Objects.equals(TenantContextHolder.getTenantId(), tenant) || actor == null
                || post.orgId() != actor.orgId() || post.userId() != actor.userId() || post.postId() != actor.postId()) throw exception(RESOURCE_FORBIDDEN);
        return post;
    }
    private static String reference(long asset) { return JsonUtils.toJsonString(List.of(Map.of("assetId", Long.toString(asset)))); }
}
