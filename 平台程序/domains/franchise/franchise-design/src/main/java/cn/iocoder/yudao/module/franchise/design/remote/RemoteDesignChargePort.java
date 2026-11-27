package cn.iocoder.yudao.module.franchise.design.remote;

import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/** B company-point charging. Provider costs are settlement facts, never retail prices. */
public interface RemoteDesignChargePort {
    void requireReady();

    /** Preserve the member's budget entitlement without quoting, reserving or charging points. */
    void assertBudgetAllowed(PostSnapshot actor);
    PlatformPriceSnapshot quote(PostSnapshot actor, Operation operation, Object payload);

    /** Same local transaction as the durable command. Stable company-pool reference is commandId. */
    void reserve(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot);

    /** Non-terminal facts stay on the command; only a terminal fact may settle or release the pool reservation. */
    void settle(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, Settlement cumulativeFacts);

    /** Synchronous paid success has no AI job. Charge once by commandId using the original B price snapshot. */
    void completeSynchronous(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, Operation operation);

    /** Only a definitive rejection (never UNKNOWN) may release a reservation. */
    void reject(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, int errorCode);
}
