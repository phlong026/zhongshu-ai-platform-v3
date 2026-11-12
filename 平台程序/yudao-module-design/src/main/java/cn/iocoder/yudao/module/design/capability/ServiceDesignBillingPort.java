package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.franchise.api.DesignServicePort.Actor;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Settlement;

import java.util.Optional;

/** Service-only cost accounting. Implementations must never debit a design consumer wallet. */
public interface ServiceDesignBillingPort {
    record ServiceScope(String callerAppId, long orgId, long tenantId, long serviceAccountId,
                        String receiptId, String requestKey, Actor actor) { }

    /** Immutable reference to durable preparation; an external call result must be recoverable by receiptId. */
    record Prepared(String preparationId) { }

    /** Fail startup unless service ledger, task billing subject and settlement recovery are all wired. */
    void requireReady();

    /** No enclosing DB transaction. Retries must reuse scope.receiptId as the upstream business key. */
    Prepared prepare(ServiceScope scope, Operation operation, Object typedPayload);

    /** Local DB work only, REQUIRED in the provider receipt transaction; return operation.resultType(). */
    Object execute(ServiceScope scope, Operation operation, Object typedPayload, Prepared prepared);

    /** Query durable preparation/task facts after an uncertain outcome; never blindly submit again. */
    default Optional<Prepared> recoverPreparation(ServiceScope scope, Operation operation, Object typedPayload) {
        return recoverPreparation(scope, operation, typedPayload, true);
    }

    /** allowDispatch=false queries only an already dispatched NOTE_POLISH key; absent facts stay unknown. */
    Optional<Prepared> recoverPreparation(ServiceScope scope, Operation operation, Object typedPayload, boolean allowDispatch);

    /** Receipt transaction only: finalize an existing NOTE_POLISH READY/EXECUTED fact, never generic new work. */
    String completeRecoveredPolish(ServiceScope scope,
                                  cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Note payload,
                                  Prepared prepared);

    record Rejection(int code, String message) { }

    /** In a new receipt transaction after rollback: use persisted phases/upstream receipts, never exception type alone.
     * Return a rejection only after atomically undoing service cost preparation; empty means outcome unknown. */
    Optional<Rejection> resolveFailure(ServiceScope scope, Operation operation, Object typedPayload, RuntimeException failure);

    /** Financial version comes from a persistent cumulative ledger, never a constant or job progress. */
    Settlement settlement(ServiceScope scope, long jobId);
}
