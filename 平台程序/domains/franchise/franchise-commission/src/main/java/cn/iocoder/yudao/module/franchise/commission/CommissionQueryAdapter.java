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
