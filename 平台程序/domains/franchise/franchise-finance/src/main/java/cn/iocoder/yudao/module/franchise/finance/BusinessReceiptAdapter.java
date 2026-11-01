package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务回款适配器（B2 板块 B / P-2）：franchise-finance 实现 BusinessReceiptPort。
 * 组织从 post.orgId() 派生（B1 惯例，无 companyId 参数）；内部专用入口，手工 REST 不放宽。
 */
@Slf4j
@Component
public class BusinessReceiptAdapter implements BusinessReceiptPort {

    private final FinReceiptService receipts;

    public BusinessReceiptAdapter(FinReceiptService receipts) {
        this.receipts = receipts;
    }

    @Override
    public BusinessReceiptSaved registerBusinessReceipt(PostSnapshot post, BusinessReceipt cmd) {
        return receipts.registerBusinessReceipt(post, "biz-receipt:" + cmd.sourceRef(), cmd);
    }

    @Override
    public BusinessReceiptSaved replayBySourceRef(PostSnapshot post, String sourceRef) {
        return receipts.replayBySourceRef(post, sourceRef);
    }
}
