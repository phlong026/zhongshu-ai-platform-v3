package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.capability.security.CallerOrganizationResolver;
import cn.iocoder.yudao.module.design.capability.security.ServicePrincipal;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@ConditionalOnProperty(name = "zhongshu.design.capability.enabled", havingValue = "true")
public class CapabilityOrganizationMapping implements CallerOrganizationResolver,
        cn.iocoder.yudao.module.infra.zhongshu.api.ServiceResourceAuthorizationPort {
    private final JdbcTemplate jdbc;
    private final DataSource source;
    public CapabilityOrganizationMapping(DataSource source) {
        this.source = source;
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public void requireActive(cn.iocoder.yudao.module.infra.zhongshu.api.PlatformCostPort.Subject subject) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("Service authorization requires the owning business transaction");
        }
        var rows = jdbc.queryForList("SELECT o.service_account_id FROM design_capability_organization o "
                        + "JOIN design_capability_receipt r ON r.caller_app_id=o.caller_app_id AND r.org_id=o.org_id "
                        + "WHERE o.caller_app_id=? AND o.org_id=? AND o.tenant_id=? AND o.service_account_id=? AND o.active=true "
                        + "AND r.receipt_id=? AND r.request_key=? AND r.tenant_id=o.tenant_id AND r.service_account_id=o.service_account_id FOR SHARE OF o", Long.class,
                subject.callerAppId(), subject.orgId(), subject.tenantId(), subject.serviceAccountId(), subject.receiptId(), subject.requestKey());
        if (rows.size() != 1) throw CapabilityException.forbidden();
    }

    public void lockCurrent(ServicePrincipal principal) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("Service authorization requires the owning business transaction");
        }
        var rows = jdbc.queryForList("SELECT service_account_id FROM design_capability_organization WHERE caller_app_id=? AND org_id=? "
                        + "AND tenant_id=? AND service_account_id=? AND active=true FOR SHARE", Long.class,
                principal.callerAppId(), principal.orgId(), principal.tenantId(), principal.serviceAccountId());
        if (rows.size() != 1) throw CapabilityException.forbidden();
    }

    @Override
    public ServicePrincipal resolve(String callerAppId, long orgId) {
        var rows = jdbc.queryForList("SELECT tenant_id,service_account_id FROM design_capability_organization "
                + "WHERE caller_app_id=? AND org_id=? AND active=true", callerAppId, orgId);
        if (rows.isEmpty()) throw CapabilityException.forbidden();
        var row = rows.get(0);
        return new ServicePrincipal(callerAppId, orgId, ((Number) row.get("tenant_id")).longValue(),
                ((Number) row.get("service_account_id")).longValue());
    }

    @Override
    public ServicePrincipal resolveRequest(Request<?> request) {
        java.util.List<java.util.Map<String, Object>> rows;
        if (request.operation() == Operation.QUERY_RECEIPT && request.payload() instanceof ReceiptQuery query) {
            rows = jdbc.queryForList("SELECT tenant_id,service_account_id FROM design_capability_receipt WHERE caller_app_id=? AND org_id=? AND operation=? AND request_key=?",
                    request.callerAppId(), request.orgId(), query.operation().name(), query.requestKey());
        } else if (request.operation() == Operation.GET_SETTLEMENT && request.payload() instanceof Job job) {
            rows = jdbc.queryForList("SELECT DISTINCT tenant_id,service_account_id FROM design_capability_receipt WHERE caller_app_id=? AND org_id=? AND job_id=? "
                            + "AND operation IN ('CREATE_FLAT_JOB','CREATE_ELEVATION_JOB','CREATE_REVISION_REQUEST') AND state='SUCCEEDED'",
                    request.callerAppId(), request.orgId(), job.jobId());
        } else {
            return resolve(request.callerAppId(), request.orgId());
        }
        if (rows.isEmpty()) {
            if (request.operation() == Operation.GET_SETTLEMENT) throw CapabilityException.forbidden();
            return resolve(request.callerAppId(), request.orgId());
        }
        if (rows.size() != 1) throw CapabilityException.forbidden();
        return new ServicePrincipal(request.callerAppId(), request.orgId(), ((Number) rows.get(0).get("tenant_id")).longValue(),
                ((Number) rows.get(0).get("service_account_id")).longValue());
    }

    public boolean active(ServicePrincipal principal) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM design_capability_organization WHERE caller_app_id=? AND org_id=? "
                        + "AND tenant_id=? AND service_account_id=? AND active=true", Integer.class,
                principal.callerAppId(), principal.orgId(), principal.tenantId(), principal.serviceAccountId());
        return count != null && count == 1;
    }

    public void requireHistoricalScope(ServicePrincipal principal, Request<?> request) {
        if (request.operation() != Operation.QUERY_RECEIPT && request.operation() != Operation.GET_SETTLEMENT
                || !principal.equals(resolveRequest(request))) throw CapabilityException.forbidden();
    }

    public void requireCurrent(ServicePrincipal principal) {
        var current = resolve(principal.callerAppId(), principal.orgId());
        if (!current.equals(principal)) throw CapabilityException.forbidden();
    }

    /** Defense in depth, also covering legacy adapter branches which reread an asset after an exception. */
    public void authorize(ServicePrincipal principal, Object payload) {
        requireCurrent(principal);
        long owner = principal.serviceAccountId();
        if (payload instanceof Project project) {
            project(owner, project.projectId());
            if (project.jobId() != null) job(owner, project.jobId(), project.projectId());
        } else if (payload instanceof Job job) {
            job(owner, job.jobId(), null);
        } else if (payload instanceof Asset asset) {
            asset(owner, asset.assetId());
        } else if (payload instanceof FlatJob job) {
            project(owner, job.projectId());
        } else if (payload instanceof ElevationJob job) {
            project(owner, job.projectId());
        } else if (payload instanceof Revision revision) {
            project(owner, revision.projectId());
        } else if (payload instanceof Selection selection) {
            project(owner, selection.projectId());
            require(jdbc.queryForObject("SELECT count(*) FROM design_candidate WHERE id=? AND project_id=? AND deleted=false",
                    Integer.class, selection.candidateId(), selection.projectId()));
            if (selection.expectedJobId() != null) job(owner, selection.expectedJobId(), selection.projectId());
        } else if (payload instanceof Scheme scheme) {
            project(owner, scheme.projectId());
            if (scheme.resultVersionId() != null) version(scheme.projectId(), scheme.resultVersionId());
            if (scheme.estimateId() != null) {
                require(jdbc.queryForObject("SELECT count(*) FROM budget_estimate WHERE id=? AND user_id=? AND project_id=? AND deleted=false",
                        Integer.class, scheme.estimateId(), owner, scheme.projectId()));
            }
        } else if (payload instanceof Budget budget) {
            project(owner, budget.projectId());
            if (budget.command().resultVersionId() != null) version(budget.projectId(), budget.command().resultVersionId());
        } else if (payload instanceof DesignServicePort.DesignProjectCreate create && create.sketchAssetId() != null) {
            asset(owner, create.sketchAssetId());
        }
    }

    private void project(long owner, long id) {
        require(jdbc.queryForObject("SELECT count(*) FROM design_project WHERE id=? AND user_id=? AND deleted=false", Integer.class, id, owner));
    }
    private void job(long owner, long id, Long projectId) {
        if (projectId == null) {
            require(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE id=? AND user_id=? AND deleted=false", Integer.class, id, owner));
        } else {
            require(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE id=? AND user_id=? AND project_ref=? AND deleted=false",
                    Integer.class, id, owner, String.valueOf(projectId)));
        }
    }
    private void asset(long owner, long id) {
        require(jdbc.queryForObject("SELECT count(*) FROM asset WHERE id=? AND owner_user_id=? AND deleted=false", Integer.class, id, owner));
    }
    private void version(long project, long version) {
        require(jdbc.queryForObject("SELECT count(*) FROM design_result_version WHERE id=? AND project_id=? AND deleted=false", Integer.class, version, project));
    }
    private void require(Integer count) { if (count == null || count != 1) throw CapabilityException.forbidden(); }
}
