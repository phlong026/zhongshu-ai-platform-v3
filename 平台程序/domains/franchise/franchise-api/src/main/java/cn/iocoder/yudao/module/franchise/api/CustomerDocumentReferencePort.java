package cn.iocoder.yudao.module.franchise.api;

import java.util.Set;

/** Additional immutable business references for the existing customer-document ACL. Never authorizes bytes itself. */
public interface CustomerDocumentReferencePort {
    Set<Long> customerIds(long tenantId, long companyId, long assetId);
}
