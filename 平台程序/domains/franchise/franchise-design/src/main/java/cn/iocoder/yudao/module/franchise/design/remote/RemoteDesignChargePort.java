package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** B-owned retail prices and platform-wallet ledger. No A cost is interpreted as retail wallet points. */
public interface RemoteDesignChargePort {
    void requireReady();
    PlatformPriceSnapshot quote(PostSnapshot actor, Operation operation, Object payload);

    /** Same local transaction as the durable command. Stable wallet reference is commandId. */
    void reserve(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot);

    /** Same local transaction as receipt/audit. Implementations persist cumulative settlement, charge only its difference. */
    void settle(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, Settlement cumulativeFacts);

    /** Synchronous paid success has no AI job. Charge once by commandId using the original B price snapshot. */
    void completeSynchronous(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, Operation operation);

    /** Only a definitive rejection (never UNKNOWN) may release a reservation. */
    void reject(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, int errorCode);
}
