package cn.iocoder.yudao.module.platform.headquarters;

import cn.iocoder.yudao.module.franchise.api.HeadquartersDocumentAccessPort;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowAssetPolicy;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Independent HQ read grants. Every asset owner is derived from a tenant-owned business record. */
@Component
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class HeadquartersDocumentAccess implements HeadquartersDocumentAccessPort {
    private final HeadquartersReadGuard guard;
    private final HeadquartersReadService reads;
    private final CustomerWorkflowAssetPolicy workflows;
    private final JdbcTemplate jdbc;

    public HeadquartersDocumentAccess(HeadquartersReadGuard guard, HeadquartersReadService reads,
                                      CustomerWorkflowAssetPolicy workflows, JdbcTemplate jdbc) {
        this.guard = guard;
        this.reads = reads;
        this.workflows = workflows;
        this.jdbc = jdbc;
    }

    @Override
    public Scope requireReadable(Request request) {
        var actor = guard.require();
        if (request == null || request.resource() == null || request.resourceId() <= 0
                || request.referenceId() <= 0 || request.section() == null) throw HeadquartersReadGuard.missing();
        String dataset = switch (request.resource()) {
            case CUSTOMER -> "customers";
            case PROJECT -> "projects";
            case RECEIPT -> "receipts";
            case PAYMENT -> "payments";
            case REFUND -> "refunds";
        };
        Map<String,Object> record = reads.detail(actor, dataset, request.resourceId());
        long company = Long.parseLong(record.get("companyId").toString());
        if (request.resource() == Resource.CUSTOMER) {
            CustomerWorkflowModel.Step.parse(request.section());
            if ("CONTRACT".equals(request.section())) {
                Boolean linked = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM fr_sale_contract WHERE company_id=? AND customer_id=?
                          AND asset_id=? AND status='SIGNED' AND deleted=FALSE)
                        """, Boolean.class, company, request.resourceId(), request.referenceId());
                if (!Boolean.TRUE.equals(linked)) throw HeadquartersReadGuard.missing();
                return scope(actor, company, request.referenceId(), Set.of("CONTRACT_ATTACHMENT"));
            }
            workflows.requireReferenced(actor.tenantId(), company, request.resourceId(), request.section(), request.referenceId());
            return scope(actor, company, request.referenceId(), Set.of("BUSINESS_DOCUMENT", "DESIGN_REFERENCE"));
        }
        if (request.resource() != Resource.PROJECT) {
            if (!"VOUCHER".equals(request.section()) || !sameId(record.get("assetId"), request.referenceId()))
                throw HeadquartersReadGuard.missing();
            return scope(actor, company, request.referenceId(), Set.of("FIN_VOUCHER"));
        }
        return project(actor, company, record, request);
    }

    private Scope project(HeadquartersReadGuard.Actor actor, long company, Map<String,Object> project, Request request) {
        long id = request.resourceId(), asset = request.referenceId();
        return switch (request.section()) {
            case "DOCUMENT" -> document(actor, company, project, id, request.referenceId());
            case "CONTRACT" -> {
                boolean linked = sameId(project.get("contractAssetId"), asset);
                if (!linked && project.get("saleContractId") != null) {
                    linked = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM fr_sale_contract
                              WHERE id=? AND company_id=? AND asset_id=? AND deleted=FALSE)
                            """, Boolean.class, Long.parseLong(project.get("saleContractId").toString()), company, asset));
                }
                if (!linked) throw HeadquartersReadGuard.missing();
                yield scope(actor, company, asset, Set.of("CONTRACT_ATTACHMENT"));
            }
            case "SITE_RECORD" -> {
                Boolean linked = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM fr_eng_site_record r,
                          LATERAL jsonb_array_elements_text(CASE WHEN jsonb_typeof(r.file_asset_ids)='array'
                            THEN r.file_asset_ids ELSE '[]'::jsonb END) a(id)
                          WHERE r.company_id=? AND r.project_id=? AND a.id=?)
                        """, Boolean.class, company, id, Long.toString(asset));
                if (!Boolean.TRUE.equals(linked)) throw HeadquartersReadGuard.missing();
                yield scope(actor, company, asset, Set.of("ENG_SITE_PHOTO"));
            }
            case "CLOSURE_OWNER", "CLOSURE_FILE", "CLOSURE_SETTLEMENT" -> {
                String column = switch (request.section()) {
                    case "CLOSURE_OWNER" -> "owner_asset_id";
                    case "CLOSURE_FILE" -> "file_asset_id";
                    default -> "settle_asset_id";
                };
                Boolean linked = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_eng_closure WHERE company_id=? AND project_id=? AND "
                        + column + "=?)", Boolean.class, company, id, asset);
                if (!Boolean.TRUE.equals(linked)) throw HeadquartersReadGuard.missing();
                yield scope(actor, company, asset, "CLOSURE_SETTLEMENT".equals(request.section())
                        ? Set.of("ENG_SITE_PHOTO", "ENG_DRAWING", "FIN_VOUCHER", "CONTRACT_ATTACHMENT")
                        : Set.of("ENG_SITE_PHOTO", "ENG_DRAWING"));
            }
            default -> throw HeadquartersReadGuard.missing();
        };
    }

    private Scope document(HeadquartersReadGuard.Actor actor, long company, Map<String,Object> project,
                           long projectId, long documentId) {
        var documents = jdbc.queryForList("SELECT file_ref FROM fr_eng_document WHERE id=? AND project_id=? AND company_id=?",
                documentId, projectId, company);
        var reference = documents.size() == 1
                ? HeadquartersDocumentReference.parse(Objects.toString(documents.get(0).get("file_ref"), "")) : null;
        if (reference == null) throw HeadquartersReadGuard.missing();
        if (reference.commissionId() == null) {
            return scope(actor, company, reference.assetId(), Set.of("ENG_DRAWING", "BUSINESS_DOCUMENT", "CONTRACT_ATTACHMENT"));
        }
        // Old engineering handovers contain a JSON reference to an immutable dispatch, not a public URL.
        // The project, commission and both companies must still belong to this tenant.
        // Increment projects may retain a copied drawing without their own source commission ID.
        // The persisted document plus the commission's company is still required in every branch below.
        if (project.get("commissionId") != null && !sameId(project.get("commissionId"), reference.commissionId()))
            throw HeadquartersReadGuard.missing();
        if ("CONFIRMATION".equals(reference.slot())) {
            Boolean linked = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fr_commission_handover h
                      JOIN fr_commission c ON c.id=h.commission_id AND c.company_id=h.company_id
                      WHERE h.company_id=? AND h.commission_id=? AND h.dispatch_seq=?
                        AND h.confirmation_asset_id=? AND h.status='ACCEPTED')
                    """, Boolean.class, company, reference.commissionId(), reference.dispatchSeq(), reference.assetId());
            if (!Boolean.TRUE.equals(linked)) throw HeadquartersReadGuard.missing();
            return scope(actor, company, reference.assetId(), Set.of("CONTRACT_ATTACHMENT"));
        }
        String column = switch (reference.slot()) {
            case "RESULT" -> "result_asset_id";
            case "DRAWING" -> "drawing_asset_id";
            case "REVIEW" -> "review_asset_id";
            default -> throw HeadquartersReadGuard.missing();
        };
        var owners = jdbc.queryForList("SELECT m.design_org_id FROM fr_commission_dispatch_manifest m "
                + "JOIN fr_commission c ON c.id=m.commission_id WHERE c.company_id=? AND c.id=? "
                + "AND m.dispatch_seq=? AND m." + column + "=?", Long.class,
                company, reference.commissionId(), reference.dispatchSeq(), reference.assetId());
        if (owners.size() != 1) throw HeadquartersReadGuard.missing();
        guard.company(actor, owners.get(0));
        return scope(actor, owners.get(0), reference.assetId(), Set.of("DESIGN_DELIVERABLE"));
    }

    private static boolean sameId(Object value, long id) { return Long.toString(id).equals(Objects.toString(value, "")); }

    private static Scope scope(HeadquartersReadGuard.Actor actor, long owner, long asset, Set<String> types) {
        return new Scope(actor.tenantId(), actor.userId(), actor.postId(), actor.orgId(), owner, asset, types);
    }
}
