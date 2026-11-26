package cn.iocoder.yudao.module.franchise.api;

import java.util.Map;
import java.util.Set;

/** Sales-owned evidence for a cohort already authorized by the caller. No contract amounts leave sales. */
public interface ContentCustomerOutcomesPort {
    record SignedCustomer(boolean hasNetReceipts) {}

    /** Returns only customers with an active, evidenced signature in this tenant and company. */
    Map<Long, SignedCustomer> signedCustomers(long tenantId, long companyId, Set<Long> customerIds);
}
