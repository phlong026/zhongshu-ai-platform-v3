package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.capability.security.ServicePrincipal;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Optional;

/** Full typed dispatcher. Consumer-priced adapter methods are unreachable for every paid operation. */
@Slf4j
public class DesignCapabilityProvider {
    private final DesignServicePort design;
    private final cn.iocoder.yudao.module.design.asset.AssetService assets;
    private final CapabilityOrganizationMapping organizations;
    private final CapabilityReceiptStore receipts;
    private final ServiceDesignBillingPort billing;
    private final JdbcTemplate jdbc;

    public DesignCapabilityProvider(DesignServicePort serviceOperations, CapabilityOrganizationMapping organizations,
                                    CapabilityReceiptStore receipts, ServiceDesignBillingPort billing, DataSource source,
                                    cn.iocoder.yudao.module.design.asset.AssetService assets) {
        this.design = serviceOperations;
        this.assets = assets;
        this.organizations = organizations;
        this.receipts = receipts;
        this.billing = billing;
        jdbc = new JdbcTemplate(source);
    }

    public Reply<Object> invoke(ServicePrincipal principal, Operation route, Request<?> request) {
        if (!principal.callerAppId().equals(request.callerAppId()) || principal.orgId() != request.orgId()
                || route != request.operation() || !route.payloadType().isInstance(request.payload())) {
            throw CapabilityException.forbidden();
        }
        if (route == Operation.QUERY_RECEIPT) {
            organizations.requireHistoricalScope(principal, request);
            ReceiptQuery query = (ReceiptQuery) request.payload();
            Receipt receipt = organizations.active(principal) ? reconcile(principal, query)
                    : reconcileHistoricalPolish(principal, query);
            return new Reply<>(0, "", receipt, null, false);
        }
        if (route == Operation.GET_SETTLEMENT) {
            organizations.requireHistoricalScope(principal, request);
            return new Reply<>(0, "", billing.settlement(scope(principal, request, null), ((Job) request.payload()).jobId()), null, false);
        }
        organizations.authorize(principal, request.payload());
        if (!route.requiresReceipt(request.payload())) {
            return new Reply<>(0, "", dispatch(principal, request, null), null, false);
        }
        if (route.paid() || route == Operation.COMPLETE_UPLOAD) return external(principal, request);
        return receipts.executeAtomic(request, receiptId -> {
            organizations.lockCurrent(principal);
            organizations.authorize(principal, request.payload());
            return dispatch(principal, request, receiptId);
        });
    }

    private Reply<Object> external(ServicePrincipal principal, Request<?> request) {
        var lease = receipts.claim(request);
        if (lease.receipt().status() == ReceiptStatus.SUCCEEDED) {
            return new Reply<>(0, "", lease.receipt().result(), lease.receipt(), true);
        }
        if (lease.receipt().status() == ReceiptStatus.FAILED) {
            return new Reply<>(lease.receipt().errorCode(), lease.receipt().errorMessage(), null, lease.receipt(), true);
        }
        if (!lease.acquired()) return pending(lease.receipt());
        try {
            if (request.operation() == Operation.COMPLETE_UPLOAD) {
                return completeUpload(principal, request, lease);
            }
            var prepared = billing.prepare(scope(principal, request, lease.receipt().receiptId()), request.operation(), request.payload());
            return receipts.completePrepared(lease, receiptId -> {
                organizations.lockCurrent(principal);
                organizations.authorize(principal, request.payload());
                return billing.execute(scope(principal, request, receiptId), request.operation(), request.payload(), prepared);
            });
        } catch (RuntimeException failure) {
            var rejection = resolveFailure(principal, request, lease, failure);
            if (rejection.isPresent()) return rejection.get();
            receipts.unknown(lease, failure);
            throw new CapabilityException(503, 1_112_100_002, "执行结果待核实，请查询原业务键收据");
        }
    }

    private Receipt reconcile(ServicePrincipal principal, ReceiptQuery query) {
        var lease = receipts.claimRecovery(principal.callerAppId(), principal.orgId(), query);
        if (!lease.acquired()) return lease.receipt();
        Request<?> original = receipts.storedRequest(lease.receipt());
        organizations.authorize(principal, original.payload());
        try {
            if (query.operation() == Operation.COMPLETE_UPLOAD) {
                return completeUpload(principal, original, lease).receipt();
            } else if (query.operation().paid()) {
                var prepared = billing.recoverPreparation(scope(principal, original, lease.receipt().receiptId()),
                        original.operation(), original.payload());
                if (prepared.isPresent()) {
                    return receipts.completePrepared(lease, receiptId -> {
                        organizations.lockCurrent(principal);
                        organizations.authorize(principal, original.payload());
                        return billing.execute(scope(principal, original, receiptId), original.operation(), original.payload(), prepared.get());
                    }).receipt();
                }
            }
            receipts.pending(lease);
        } catch (RuntimeException failure) {
            var rejection = resolveFailure(principal, original, lease, failure);
            if (rejection.isPresent()) return rejection.get().receipt();
            receipts.unknown(lease, failure);
        }
        return receipts.query(principal.callerAppId(), principal.orgId(), query);
    }

    private Receipt reconcileHistoricalPolish(ServicePrincipal principal, ReceiptQuery query) {
        if (query.operation() != Operation.NOTE_POLISH) return receipts.query(principal.callerAppId(), principal.orgId(), query);
        var lease = receipts.claimRecovery(principal.callerAppId(), principal.orgId(), query);
        if (!lease.acquired()) return lease.receipt();
        Request<?> original = receipts.storedRequest(lease.receipt());
        try {
            var prepared = billing.recoverPreparation(scope(principal, original, lease.receipt().receiptId()),
                    Operation.NOTE_POLISH, original.payload(), false);
            if (prepared.isPresent()) {
                return receipts.completePrepared(lease, receiptId -> billing.completeRecoveredPolish(
                        scope(principal, original, receiptId), (Note) original.payload(), prepared.get())).receipt();
            }
            receipts.pending(lease);
        } catch (RuntimeException failure) {
            var rejection = resolveFailure(principal, original, lease, failure);
            if (rejection.isPresent()) return rejection.get().receipt();
            receipts.unknown(lease, failure);
        }
        return receipts.query(principal.callerAppId(), principal.orgId(), query);
    }

    private Reply<Object> completeUpload(ServicePrincipal principal, Request<?> request, CapabilityReceiptStore.Lease lease) {
        long assetId = ((Asset) request.payload()).assetId();
        if (completedAsset(principal, assetId).isPresent()) {
            return receipts.completePrepared(lease, ignored -> {
                organizations.lockCurrent(principal);
                jdbc.queryForMap("SELECT id FROM asset WHERE id=? AND owner_user_id=? FOR UPDATE", assetId, principal.serviceAccountId());
                return completedAsset(principal, assetId).orElseThrow();
            });
        }
        try {
            assets.completeUploadWithCompletion(principal.serviceAccountId(), assetId, state ->
                    receipts.completeInCurrentTransaction(lease, ignored -> {
                        organizations.lockCurrent(principal);
                        return completedAsset(principal, assetId).orElseThrow();
                    }));
        } catch (cn.iocoder.yudao.module.design.asset.ModerationUnavailableException unavailable) {
            log.warn("[design-capability][moderation pending asset={} receipt={}]", assetId, lease.receipt().receiptId());
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException failure) {
            if (!cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.ASSET_VALIDATION_FAILED.getCode().equals(failure.getCode())) throw failure;
            // Another validator still owns the asset lease; only its committed terminal state may complete the receipt.
        }
        Receipt current = receipts.query(principal.callerAppId(), principal.orgId(), new ReceiptQuery(request.operation(), request.requestKey()));
        if (current.status() == ReceiptStatus.SUCCEEDED) return new Reply<>(0, "", current.result(), current, false);
        receipts.pending(lease);
        return pending(receipts.query(principal.callerAppId(), principal.orgId(), new ReceiptQuery(request.operation(), request.requestKey())));
    }

    private Optional<Reply<Object>> resolveFailure(ServicePrincipal principal, Request<?> request,
                                                    CapabilityReceiptStore.Lease lease, RuntimeException failure) {
        if (!request.operation().paid()) return Optional.empty();
        try {
            return receipts.resolveFailure(lease, () -> billing.resolveFailure(scope(principal, request, lease.receipt().receiptId()),
                    request.operation(), request.payload(), failure));
        } catch (RuntimeException reconciliationFailure) {
            log.warn("[design-capability][failure evidence unavailable receipt={}]", lease.receipt().receiptId(), reconciliationFailure);
            return Optional.empty();
        }
    }

    private Optional<DesignServicePort.AssetCompleted> completedAsset(ServicePrincipal principal, long assetId) {
        organizations.authorize(principal, new Asset(assetId));
        var row = jdbc.queryForMap("SELECT upload_status,rejected_reason FROM asset WHERE id=? AND owner_user_id=? AND deleted=false",
                assetId, principal.serviceAccountId());
        String status = row.get("upload_status").toString();
        if (!"ACCEPTED".equals(status) && !"REJECTED".equals(status)) return Optional.empty();
        return Optional.of(new DesignServicePort.AssetCompleted(assetId, status,
                row.get("rejected_reason") == null ? null : row.get("rejected_reason").toString()));
    }

    private Reply<Object> pending(Receipt receipt) {
        return new Reply<>(1_112_100_002, "请求尚未确认，请查询原收据", null, receipt, false);
    }

    private ServiceDesignBillingPort.ServiceScope scope(ServicePrincipal principal, Request<?> request, String receiptId) {
        return new ServiceDesignBillingPort.ServiceScope(principal.callerAppId(), principal.orgId(), principal.tenantId(),
                principal.serviceAccountId(), receiptId, request.requestKey(), request.actor());
    }

    private Object dispatch(ServicePrincipal principal, Request<?> request, String receiptId) {
        long owner = principal.serviceAccountId();
        Object payload = request.payload();
        return switch (request.operation()) {
            case CREATE_PROJECT -> {
                var command = (DesignServicePort.DesignProjectCreate) payload;
                yield design.createProject(owner, new DesignServicePort.DesignProjectCreate(command.sourceType(),
                        command.refCaseId(), command.sketchAssetId(), command.requirementInputs(), command.line(), "cap:" + receiptId), request.actor());
            }
            case LIST_PROJECTS -> { var page = (Page) payload; yield design.listProjects(owner, page.cursor(), page.limit()); }
            case GET_PROJECT -> { var project = (Project) payload; yield design.getProject(owner, project.projectId(), project.jobId()); }
            case GET_AI_JOB -> design.getAiJob(owner, ((Job) payload).jobId());
            case CANCEL_AI_JOB -> design.cancelAiJob(owner, ((Job) payload).jobId());
            case LIST_RESULT_VERSIONS -> design.listResultVersions(owner, ((Project) payload).projectId());
            case CONFIRM_FLAT_CANDIDATE -> {
                var selection = (Selection) payload;
                yield design.confirmFlatCandidate(owner, selection.projectId(), selection.expectedJobId(), selection.candidateId(), request.actor());
            }
            case SELECT_ELEVATION_CANDIDATE -> {
                var selection = (Selection) payload;
                yield design.selectElevationCandidate(owner, selection.projectId(), selection.expectedJobId(), selection.candidateId(), request.actor());
            }
            case VALIDATE_SCHEME_REF -> {
                var scheme = (Scheme) payload;
                yield design.validateSchemeRef(owner, scheme.projectId(), scheme.kind(), scheme.resultVersionId(), scheme.estimateId());
            }
            case LIST_CASES -> design.listCases((DesignServicePort.CaseQuery) payload);
            case LIST_CASE_STYLES -> design.listCaseStyles();
            case GET_CASE_DETAIL -> design.getCaseDetail(((Case) payload).caseId());
            case FAVORITE_CASE -> { design.favoriteCase(owner, ((Case) payload).caseId()); yield true; }
            case UNFAVORITE_CASE -> { design.unfavoriteCase(owner, ((Case) payload).caseId()); yield true; }
            case LIST_FAVORITES -> { var page = (Page) payload; yield design.listFavorites(owner, page.cursor(), page.limit()); }
            case BUDGET_RULE_INFO -> {
                var rule = (BudgetRule) payload;
                yield design.budgetRuleInfo(rule.regionCode(), rule.structureType(), rule.materialGrade());
            }
            case CREATE_UPLOAD_TICKET -> design.createUploadTicket(owner, (DesignServicePort.UploadTicketCreate) payload);
            case COMPLETE_UPLOAD -> design.completeUpload(owner, ((Asset) payload).assetId());
            case CREATE_DOWNLOAD_URL -> design.createDownloadUrl(owner, ((Asset) payload).assetId());
            // Only the prepared branch can run paid work, so no consumer wallet call is reachable here.
            case CREATE_FLAT_JOB, CREATE_ELEVATION_JOB, CREATE_REVISION_REQUEST, CREATE_BUDGET_ESTIMATE, NOTE_POLISH ->
                    throw new IllegalStateException("Paid operation must enter the service billing preparation boundary");
            case QUERY_RECEIPT -> receipts.query(principal.callerAppId(), principal.orgId(), (ReceiptQuery) payload);
            case GET_SETTLEMENT -> billing.settlement(scope(principal, request, null), ((Job) payload).jobId());
        };
    }
}
