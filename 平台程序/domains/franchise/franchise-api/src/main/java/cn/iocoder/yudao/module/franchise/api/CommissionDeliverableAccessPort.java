package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** Scope grants are derived from immutable dispatch/packet facts, never from caller asset IDs. */
public interface CommissionDeliverableAccessPort {
    enum Slot {
        RESULT, DRAWING, REVIEW, CONFIRMATION, EXT_RESULT, EXT_DRAWING, EXT_REVIEW, EXT_CONFIRM;
        /** EXT_* scopes use the immutable external handover version, not a legacy dispatch sequence. */
        public boolean external() { return name().startsWith("EXT_"); }
    }
    record DeliverableRef(long assetId, long ownerOrgId, long commissionId, int dispatchSeq, Slot slot) { }
    DeliverableRef requireReadable(PostSnapshot actor, long commissionId, int dispatchSeq, Slot slot);
}
