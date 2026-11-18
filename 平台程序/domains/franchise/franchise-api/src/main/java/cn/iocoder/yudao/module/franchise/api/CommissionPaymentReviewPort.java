package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

public interface CommissionPaymentReviewPort {
    void requirePayable(PostSnapshot post, long payableId);
}
