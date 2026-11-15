package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import java.util.List;

/** Current-version net settlement. Implementations of mutations/locking reads must join the caller transaction. */
public interface DesignFeeSettlementPort {
    enum Mode { AREA_FULL, LEGACY_SPLIT }
    enum Stage { FULL, FIRST, TAIL }

    record StageState(Stage stage, long expectedCents, long grossPaidCents,
                      long expenseRefundCents, long storePaidNetCents,
                      long grossReceivedCents, long refundCents, long headquartersNetCents,
                      boolean storeReady, boolean headquartersReady, boolean fullyReady) { }

    record Settlement(Mode mode, int submissionVersion, List<StageState> stages) {
        public boolean readyToComplete() {
            return mode == Mode.AREA_FULL ? ready(Stage.FULL, true) : ready(Stage.FIRST, false);
        }
        public boolean readyToDispatch() {
            return mode == Mode.AREA_FULL ? ready(Stage.FULL, true)
                    : ready(Stage.FIRST, true) && ready(Stage.TAIL, true);
        }
        private boolean ready(Stage stage, boolean both) {
            return stages.stream().filter(item -> item.stage() == stage && item.expectedCents() > 0)
                    .anyMatch(item -> both ? item.fullyReady() : item.storeReady());
        }
    }

    void ensureInitialPayable(long commissionId, int submissionVersion, PostSnapshot actor);
    void ensureTailPayable(long commissionId, int submissionVersion, PostSnapshot actor);
    Settlement read(long commissionId, int submissionVersion);
    /** MANDATORY transaction; lock FULL or FIRST then TAIL, retain locks through caller commit. */
    Settlement lockAndRead(long commissionId, int submissionVersion, PostSnapshot actor);
}
