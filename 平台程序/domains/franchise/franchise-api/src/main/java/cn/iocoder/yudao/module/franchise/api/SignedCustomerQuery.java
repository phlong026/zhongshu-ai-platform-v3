package cn.iocoder.yudao.module.franchise.api;

import java.util.List;
import java.util.Set;

/** 批量查询指定公司中已有生效建房合同的客户。 */
public interface SignedCustomerQuery {

    Set<Long> signedCustomers(long companyId, List<Long> customerIds);
}
