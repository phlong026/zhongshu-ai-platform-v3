package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.commission.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;

/** Mapper-only authorization projection: deliberately has no asset or commission command-service dependency. */
@Component
public class CommissionDeliverableAccessAdapter implements CommissionDeliverableAccessPort {
    private final JdbcTemplate jdbc;
    private final CommissionMapper commissions;
    private final CommissionHandoverMapper handovers;
    private final CommissionAccess access;
    private final EngineeringProjectContextPort engineering;
    private final CommissionSimulatedPayments simulations;
    private final DesignFeeSettlementPort settlement;
    public CommissionDeliverableAccessAdapter(JdbcTemplate jdbc,CommissionMapper commissions,CommissionHandoverMapper handovers,
                                              CommissionAccess access,EngineeringProjectContextPort engineering,
                                              CommissionSimulatedPayments simulations, DesignFeeSettlementPort settlement) {
        this.jdbc=jdbc;this.commissions=commissions;this.handovers=handovers;this.access=access;this.engineering=engineering;this.simulations=simulations;
        this.settlement=settlement;
    }
    @Override public DeliverableRef requireReadable(PostSnapshot actor,long commissionId,int dispatchSeq,Slot slot) {
        CommissionDO c=commissions.selectById(commissionId);
        if(c==null) throw exception(COMMISSION_NOT_FOUND);
        var manifests=jdbc.query("SELECT * FROM fr_commission_dispatch_manifest WHERE commission_id=? AND dispatch_seq=?",(rs,n)->new long[]{rs.getLong("design_org_id"),rs.getLong("result_asset_id"),rs.getLong("drawing_asset_id"),rs.getLong("review_asset_id"),rs.getInt("submission_version")},commissionId,dispatchSeq);
        if(manifests.isEmpty()) throw exception(COMMISSION_FORBIDDEN);
        long[] manifest=manifests.get(0);
        boolean current=c.getDispatchSeq()!=null && c.getDispatchSeq()==dispatchSeq;
        boolean storeParticipant=FranchiseRoles.atStore(actor) && actor.orgId()==c.getCompanyId()
                && (FranchiseRoles.has(actor,FranchiseRoles.SALES) || FranchiseRoles.isBoss(actor))
                && access.canRead(actor,c);
        boolean designParticipant=access.isDesignAdminOf(actor,c) && manifest[0]==actor.orgId();
        var packets=handovers.selectByCommission(commissionId).stream().filter(h->h.getDispatchSeq()==dispatchSeq
                && java.util.Objects.equals(h.getVersion(),c.getHandoverVersion())).toList();
        var project=engineering.findBySourceCommission(c.getCompanyId(),commissionId);
        boolean engineer=current && actor.orgId()==c.getCompanyId() && FranchiseRoles.has(actor,FranchiseRoles.ENGINEER)
                && (packets.stream().anyMatch(h->h.getManagerUserId()==actor.userId())
                || project!=null && (Long.valueOf(actor.userId()).equals(project.managerUserId()) || Long.valueOf(actor.userId()).equals(project.assignedEngineerUserId())));
        if(!storeParticipant && !designParticipant && !engineer) throw exception(COMMISSION_FORBIDDEN);
        int submissionVersion = (int) manifest[4];
        if (simulations.blocked(c, submissionVersion) && (submissionVersion != c.getSubmissionVersion()
                || !simulations.reviewedForSettlement(c) || !settlement.read(c.getId(), submissionVersion).readyToDispatch())) {
            throw exception(HISTORICAL_SETTLEMENT_REQUIRED);
        }
        if(slot==Slot.CONFIRMATION) {
            if(!current) throw exception(COMMISSION_FORBIDDEN);
            var packet=packets.stream().filter(h->h.getConfirmationAssetId()!=null).findFirst().orElseThrow(()->exception(HANDOVER_NOT_FOUND));
            // Customer signatures are not readable by headquarters merely because they own the design.
            if(!storeParticipant && !engineer) throw exception(COMMISSION_FORBIDDEN);
            return new DeliverableRef(packet.getConfirmationAssetId(),c.getCompanyId(),commissionId,dispatchSeq,slot);
        }
        long id=switch(slot) { case RESULT->manifest[1];case DRAWING->manifest[2];case REVIEW->manifest[3];default->throw exception(COMMISSION_FORBIDDEN); };
        return new DeliverableRef(id,manifest[0],commissionId,dispatchSeq,slot);
    }

}
