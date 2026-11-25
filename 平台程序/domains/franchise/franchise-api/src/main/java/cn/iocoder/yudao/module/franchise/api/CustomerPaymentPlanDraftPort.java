package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import java.time.LocalDate;
import java.util.List;

/** Sales owns the proposal; an entrustment freezes this value without creating receivables. */
public interface CustomerPaymentPlanDraftPort {
    record Stage(String label, long amountCents, Integer ratioBps, LocalDate dueDate,
                 String trigger, Integer nodeIndex, int sequence) {}
    record Snapshot(long customerId, long companyId, String source, long revision, long totalCents,
                    Long contractId, Integer contractPlanVersion, List<Stage> stages) {}
    /** Signed contract first, otherwise the latest proposal; null means no agreement recorded yet. */
    Snapshot snapshotForEntrust(PostSnapshot post, long customerId);
}
