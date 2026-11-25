package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionMapper;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 财务域查询设计委托费用的实现：读当前提交版本（version 最高）的费用快照与主表状态。
 */
@Component
public class CommissionQueryAdapter implements CommissionQueryPort {

    @Resource
    private CommissionSubmissionMapper submissionMapper;
    @Resource
    private CommissionMapper commissionMapper;
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override public HandoverContext findHandoverContext(long companyId,long commissionId) {
        return jdbc.query("""
                SELECT h.source_mode,h.version,h.payment_plan_snapshot::text AS payment_plan
                FROM fr_commission c JOIN fr_commission_handover h ON h.commission_id=c.id AND h.company_id=c.company_id
                  AND h.version=c.handover_version
                JOIN system_dept d ON d.id=c.company_id
                WHERE c.id=? AND c.company_id=? AND d.tenant_id=? AND d.status=0 AND d.deleted=0 AND h.status='ACCEPTED'
                """,(rs,n) -> new HandoverContext(rs.getString("source_mode"),rs.getInt("version"),
                rs.getString("payment_plan")==null?null:cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                        rs.getString("payment_plan"),cn.iocoder.yudao.module.franchise.api.CustomerPaymentPlanDraftPort.Snapshot.class)),
                commissionId,companyId,ExternalCommissionService.tenant()).stream().findFirst().orElse(null);
    }

    @Override
    public java.util.Map<Long, String> customerDesignStatuses(long companyId, java.util.Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) return java.util.Map.of();
        java.util.Map<Long, String> statuses = new java.util.HashMap<>();
        for (CommissionDO row : commissionMapper.selectList(new LambdaQueryWrapperX<CommissionDO>()
                .eq(CommissionDO::getCompanyId, companyId).in(CommissionDO::getCustomerId, customerIds))) {
            if (row.getStatus() != null && (row.getSubmissionVersion() != null && row.getSubmissionVersion() > 0
                    || "EXTERNAL".equals(row.getWorkflowMode()) && row.getExternalRequestVersion() != null && row.getExternalRequestVersion() > 0))
                statuses.put(row.getCustomerId(), row.getStatus());
        }
        return statuses;
    }

    @Override
    public CommissionFeeView findCurrentFee(long commissionId) {
        Optional<CommissionSubmissionDO> latest = submissionMapper.selectList(
                        new LambdaQueryWrapperX<CommissionSubmissionDO>()
                                .eq(CommissionSubmissionDO::getCommissionId, commissionId)
                                .orderByDesc(CommissionSubmissionDO::getVersion))
                .stream().findFirst();
        return latest.map(this::toView).orElse(null);
    }

    private CommissionFeeView toView(CommissionSubmissionDO submission) {
        CommissionDO commission = commissionMapper.selectById(submission.getCommissionId());
        String status = commission == null ? "FEE_PENDING" : commission.getStatus();
        return new CommissionFeeView(submission.getCommissionId(), submission.getCompanyId(),
                submission.getDesignOrgId(), submission.getVersion(), status,
                submission.getTotalCents() == null ? 0 : submission.getTotalCents(),
                submission.getFirstCents() == null ? 0 : submission.getFirstCents(),
                cn.iocoder.yudao.module.franchise.api.DesignFeeSettlementPort.Mode.valueOf(submission.getMode()==null?"LEGACY_SPLIT":submission.getMode()),
                commission==null?0:commission.getCustomerId(),submission.getBudgetVersion(),submission.getPricingVersion(),
                submission.getArea(),submission.getRateCents(),submission.getRecipient());
    }

}
