package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

/**
 * M06-T03：统一建房合同查询适配器——同 C 唯一活跃合同守卫（selectActiveByCustomer）下取 SIGNED 合同；
 * WAIT_SIGN/VOIDED 不算关联合同（未签订不冻结移交、作废不关联）。
 */
@Component
public class SaleContractQueryAdapter implements SaleContractQueryPort {

    private final SaleContractMapper contracts;

    public SaleContractQueryAdapter(SaleContractMapper contracts) {
        this.contracts = contracts;
    }

    @Override
    public Long findActiveSignedId(PostSnapshot post, long customerId) {
        SaleContractDO active = contracts.selectActiveByCustomer(customerId);
        if (active == null || active.getCompanyId() != post.orgId()
                || !SaleContractDO.STATUS_SIGNED.equals(active.getStatus())) {
            return null;
        }
        return active.getId();
    }
}
