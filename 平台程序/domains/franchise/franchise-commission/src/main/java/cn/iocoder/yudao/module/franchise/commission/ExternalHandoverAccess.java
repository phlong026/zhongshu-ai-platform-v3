package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.api.CommissionDeliverableAccessPort;
import cn.iocoder.yudao.module.franchise.api.EngineeringProjectContextPort;
import cn.iocoder.yudao.module.franchise.commission.dal.*;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** Persisted reference authorization only; never depends on the asset or commission command services. */
@Component
public class ExternalHandoverAccess {
    private final JdbcTemplate jdbc;
    private final CurrentPost posts;
    private final StaffPostService staff;
    private final SystemDirectory directory;
    private final EngineeringProjectContextPort engineering;
    public ExternalHandoverAccess(JdbcTemplate jdbc, CurrentPost posts, StaffPostService staff, SystemDirectory directory,
            EngineeringProjectContextPort engineering) {
        this.jdbc=jdbc; this.posts=posts; this.staff=staff; this.directory=directory; this.engineering=engineering;
    }
    record Subject(CommissionDO commission, CustomerDO customer) { }

    Subject subject(PostSnapshot actor, long commissionId) {
        if (!actor.equals(posts.require()) || !FranchiseRoles.atStore(actor)
                || !staff.findActivePost(actor.userId(), actor.postId()).filter(actor::equals).isPresent()
                || directory.usersByIds(List.of(actor.userId())).stream().noneMatch(u -> u.active() && u.userId()==actor.userId()))
            throw exception(RESOURCE_FORBIDDEN);
        Boolean company = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM system_dept d JOIN pf_org_unit o ON o.dept_id=d.id
                JOIN pf_staff_post p ON p.org_id=o.dept_id AND p.status='ACTIVE'
                WHERE d.id=? AND d.tenant_id=? AND d.status=0 AND d.deleted=0
                  AND o.kind='ORG' AND o.org_type='store' AND o.status='ACTIVE'
                  AND p.id=? AND p.user_id=? AND p.role_code=? AND p.team_id IS NOT DISTINCT FROM ?)
                """, Boolean.class, actor.orgId(), ExternalCommissionService.tenant(),actor.postId(),actor.userId(),actor.roleCode(),actor.teamId());
        // These fresh JDBC reads also avoid a MyBatis session retaining pre-lock ownership or revision values.
        CommissionDO row = jdbc.query("SELECT * FROM fr_commission WHERE id=? AND company_id=?",(rs,n) -> new CommissionDO()
                .setId(rs.getLong("id")).setCompanyId(rs.getLong("company_id")).setDesignOrgId(rs.getLong("design_org_id"))
                .setCustomerId(rs.getLong("customer_id")).setCustomerName(rs.getString("customer_name")).setStatus(rs.getString("status"))
                .setWorkflowMode(rs.getString("workflow_mode")).setExternalRequestVersion(rs.getObject("external_request_version",Integer.class))
                .setExternalResultVersion(rs.getObject("external_result_version",Integer.class)).setSubmissionVersion(rs.getInt("submission_version"))
                .setHandoverVersion(rs.getObject("handover_version",Integer.class)).setRevision(rs.getLong("revision")),
                commissionId,actor.orgId()).stream().findFirst().orElse(null);
        if (!Boolean.TRUE.equals(company) || row==null || row.getCompanyId()!=actor.orgId()
                || !ExternalCommissionService.external(row)) throw exception(RESOURCE_FORBIDDEN);
        CustomerDO customer = jdbc.query("SELECT * FROM fr_customer WHERE id=? AND company_id=?",(rs,n) -> new CustomerDO()
                .setId(rs.getLong("id")).setCompanyId(rs.getLong("company_id")).setOwnerUserId(rs.getObject("owner_user_id",Long.class))
                .setTeamId(rs.getObject("team_id",Long.class)).setName(rs.getString("name")).setCity(rs.getString("city"))
                .setPhone(rs.getString("phone")),row.getCustomerId(),actor.orgId()).stream().findFirst().orElse(null);
        if (customer==null || customer.getCompanyId()!=actor.orgId()) throw exception(RESOURCE_FORBIDDEN);
        return new Subject(row,customer);
    }

    Subject readable(PostSnapshot actor,long commissionId) {
        Subject subject=subject(actor,commissionId);
        if (!CustomerAccess.canRead(actor,subject.customer()) && packets(commissionId,actor.orgId()).stream()
                .noneMatch(packet -> canReadPacket(actor,subject,packet))) throw exception(RESOURCE_FORBIDDEN);
        return subject;
    }

    Subject writable(PostSnapshot actor,long commissionId) {
        Subject subject=subject(actor,commissionId);
        if (!CustomerAccess.canWrite(actor,subject.customer())) throw exception(RESOURCE_FORBIDDEN);
        return subject;
    }

    boolean canReadPacket(PostSnapshot actor,Subject subject,CommissionHandoverDO packet) {
        if (!"EXTERNAL".equals(packet.getSourceMode()) || !Objects.equals(packet.getCompanyId(),subject.commission().getCompanyId())) return false;
        if (CustomerAccess.canRead(actor,subject.customer())) return true;
        if (!Objects.equals(packet.getVersion(),subject.commission().getHandoverVersion())) return false;
        if (CommissionHandoverDO.STATUS_ACCEPTED.equals(packet.getStatus())) {
            var project=engineering.findBySourceCommission(actor.orgId(),subject.commission().getId());
            if (project==null || project.companyId()!=actor.orgId()) return false;
            return List.of(FranchiseRoles.FINANCE,FranchiseRoles.FINANCE_LEAD).contains(actor.roleCode())
                    || FranchiseRoles.has(actor,FranchiseRoles.ENGINEER)
                    && (Objects.equals(project.managerUserId(),actor.userId()) || Objects.equals(project.assignedEngineerUserId(),actor.userId()));
        }
        return FranchiseRoles.has(actor,FranchiseRoles.ENGINEER) && Objects.equals(packet.getManagerUserId(),actor.userId());
    }

    CommissionHandoverDO packet(PostSnapshot actor,Subject subject,int version) {
        return packets(subject.commission().getId(),actor.orgId()).stream()
                .filter(h -> h.getVersion()==version && canReadPacket(actor,subject,h)).findFirst()
                .orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
    }

    List<CommissionHandoverDO> packets(long commission,long company) {
        return jdbc.query("SELECT * FROM fr_commission_handover WHERE commission_id=? AND company_id=? AND source_mode='EXTERNAL' ORDER BY version DESC",
                (rs,n) -> new CommissionHandoverDO().setId(rs.getLong("id")).setCompanyId(rs.getLong("company_id"))
                .setCommissionId(rs.getLong("commission_id")).setVersion(rs.getInt("version")).setDispatchSeq(rs.getInt("dispatch_seq"))
                .setSourceMode(rs.getString("source_mode")).setExternalResultVersion(rs.getInt("external_result_version"))
                .setResultFile(rs.getString("result_file")).setDrawingFile(rs.getString("drawing_file")).setReviewFile(rs.getString("review_file"))
                .setConfirmationFile(rs.getString("confirmation_file")).setConfirmationAssetId(rs.getLong("confirmation_asset_id"))
                .setDrawingVersion(rs.getString("drawing_version")).setConfirmationDate(rs.getObject("confirmation_date",java.time.LocalDate.class))
                .setNote(rs.getString("note")).setManagerUserId(rs.getLong("manager_user_id")).setStatus(rs.getString("status"))
                .setDecisionNote(rs.getString("decision_note")).setDecidedByUserId(rs.getObject("decided_by_user_id",Long.class))
                .setDecidedAt(rs.getObject("decided_at",java.time.OffsetDateTime.class)).setSubmittedByUserId(rs.getLong("submitted_by_user_id"))
                .setCreateTime(rs.getObject("create_time",java.time.OffsetDateTime.class)),commission,company);
    }

    boolean canDecide(PostSnapshot actor,Subject subject,CommissionHandoverDO packet) {
        return packet!=null && "EXTERNAL".equals(packet.getSourceMode())
                && CommissionDO.STATUS_HANDOVER_SUBMITTED.equals(subject.commission().getStatus())
                && Objects.equals(packet.getVersion(),subject.commission().getHandoverVersion())
                && CommissionHandoverDO.STATUS_PENDING.equals(packet.getStatus())
                && FranchiseRoles.has(actor,FranchiseRoles.ENGINEER) && Objects.equals(packet.getManagerUserId(),actor.userId());
    }

    public CommissionDeliverableAccessPort.DeliverableRef requireReadable(PostSnapshot actor,long commissionId,
            int version,CommissionDeliverableAccessPort.Slot slot) {
        if (slot==null || !slot.external()) throw exception(RESOURCE_FORBIDDEN);
        Subject subject=readable(actor,commissionId);
        var packet=packet(actor,subject,version);
        String value=switch(slot) {
            case EXT_RESULT -> packet.getResultFile();
            case EXT_DRAWING -> packet.getDrawingFile();
            case EXT_REVIEW -> packet.getReviewFile();
            case EXT_CONFIRM -> String.valueOf(packet.getConfirmationAssetId());
            default -> throw exception(RESOURCE_FORBIDDEN);
        };
        try {
            if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return new CommissionDeliverableAccessPort.DeliverableRef(Long.parseLong(value),actor.orgId(),commissionId,version,slot);
        } catch (NumberFormatException invalid) { throw exception(RESOURCE_FORBIDDEN); }
    }
}
