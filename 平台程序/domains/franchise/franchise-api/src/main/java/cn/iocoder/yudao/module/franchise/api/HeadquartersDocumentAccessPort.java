package cn.iocoder.yudao.module.franchise.api;

import java.util.Set;

/** Server-side headquarters authorization for one referenced business document, never an arbitrary storage key. */
public interface HeadquartersDocumentAccessPort {
    enum Resource { CUSTOMER, PROJECT, RECEIPT, PAYMENT, REFUND }

    /** For PROJECT/DOCUMENT, referenceId is the document row ID; otherwise it is a referenced asset ID. */
    record Request(Resource resource, long resourceId, String section, long referenceId) { }

    record Scope(long tenantId, long readerUserId, long readerPostId, long headquartersOrgId,
                 long ownerOrgId, long assetId, Set<String> allowedTypes) {
        public Scope { allowedTypes = Set.copyOf(allowedTypes); }
    }

    /** Must recheck the actual current tenant, active headquarters post, company and immutable business reference. */
    Scope requireReadable(Request request);
}
