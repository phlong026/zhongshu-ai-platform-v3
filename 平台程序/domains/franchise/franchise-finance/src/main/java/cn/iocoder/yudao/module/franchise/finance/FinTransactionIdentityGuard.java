package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import org.springframework.stereotype.Component;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.TXN_NO_OCCUPIED;

/** 历史付款未写永久身份表时仍占用原流水，作废不释放；不自动迁移或掩盖历史冲突。 */
@Component
public class FinTransactionIdentityGuard {
    private final FinPaymentMapper payments;
    public FinTransactionIdentityGuard(FinPaymentMapper payments) { this.payments=payments; }
    public void requireNoPayment(long companyId,String transactionNo) {
        String normalized=FinReceiptService.normalize(transactionNo);
        if(normalized!=null && payments.selectCount(new LambdaQueryWrapperX<FinPaymentDO>()
                .eq(FinPaymentDO::getCompanyId,companyId).eq(FinPaymentDO::getTransactionNo,normalized))>0) {
            throw exception(TXN_NO_OCCUPIED);
        }
    }
}
