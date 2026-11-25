package cn.iocoder.yudao.module.franchise.api;

import java.util.Map;
import java.util.Set;

/** Internal read-only proof lookup for references taken from authorized, persisted business records. */
public interface AssetEvidenceQueryPort {
    /** Returns actual asset owners so a corrupt reference cannot replace another company's valid proof. */
    Map<Long, Long> acceptedOwners(long tenantId, Set<Long> assetIds, Set<String> allowedTypes);
}
