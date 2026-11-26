package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** Read the immutable formal sales quote selected for a customer's follow-up. */
public interface CustomerWorkflowQuotePort {
    record Quote(long totalCents, String versionLabel) { }

    Quote requireCustomerQuote(PostSnapshot post, long customerId, long quoteId);
}
