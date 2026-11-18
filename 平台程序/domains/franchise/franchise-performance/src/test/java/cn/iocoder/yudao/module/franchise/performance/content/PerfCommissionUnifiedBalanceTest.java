package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class PerfCommissionUnifiedBalanceTest {
    @Test void repayingOnlyTheReviewedShortfallPreservesTheSettledCommission() {
        String stage = "required:100000|unified:9:SIGN_AFTER:";
        String reviewed = stage + "80000|refunded:30000";
        String paidUp = stage + "100000|refunded:30000";
        assertThat(PerfCommissionService.needsReceiptReview(true, paidUp, reviewed, 15000, 15000, "15000")).isFalse();
        assertThat(PerfCommissionService.needsReceiptReview(false, stage + "90000|refunded:30000", reviewed, 15000, 15000, "15000")).isFalse();
        // 即使再退款后的净额仍高于当初核对的净额，也须重新核对。
        assertThat(PerfCommissionService.needsReceiptReview(false, stage + "90000|refunded:40000", reviewed, 15000, 15000, "15000")).isTrue();
        assertThat(PerfCommissionService.needsReceiptReview(true, paidUp, reviewed, 15000, 10000, "10000")).isTrue();
        assertThat(PerfCommissionService.needsReceiptReview(true, paidUp, reviewed, 15000, 15000, null)).isTrue();
        for (String changed : List.of(paidUp.replace("unified:9:", "unified:99:"), paidUp.replace("required:100000", "required:110000"),
                stage + "100001|refunded:30000", stage + "70000|refunded:30000", "invalid")) {
            assertThat(PerfCommissionService.needsReceiptReview(true, changed, reviewed, 15000, 15000, "15000")).isTrue();
        }
    }

    @Test void engineeringRecoveryRequiresRetainingEveryReviewedReceiptAndRefund() {
        String reviewed = "required:100000|c:1:100000:20000";
        String recovered = reviewed + "|c:2:20000:0";
        assertThat(PerfCommissionService.needsReceiptReview(true, recovered, reviewed, 15000, 15000, "15000")).isFalse();
        for (String changed : List.of(reviewed + "|c:2:22000:2000", recovered.replace("c:1:", "c:3:"),
                recovered.replace("100000:20000", "100000:25000"))) {
            assertThat(PerfCommissionService.needsReceiptReview(false, changed, reviewed, 15000, 15000, "15000")).isTrue();
        }
    }
    @Test void paidInFullReviewClosesOnlyForTheSameReceiptsAndReviewedPayment() {
        String current = "required:100000|unified:9:SIGN_AFTER:80000|refunded:30000";
        String earlier = "required:100000|unified:9:SIGN_AFTER:88000|refunded:22000";
        assertThat(PerfCommissionService.needsReceiptReview(false, current, current, 15000, 15000, "15000")).isFalse();
        assertThat(PerfCommissionService.needsReceiptReview(false, current, earlier, 15000, 15000, "15000")).isTrue();
        assertThat(PerfCommissionService.needsReceiptReview(false, current, current, 15000, 15000, null)).isTrue();
        assertThat(PerfCommissionService.needsReceiptReview(false, current, current, 15000, 15000, "10000")).isTrue();
        assertThat(PerfCommissionService.needsReceiptReview(false, current, current, 15000, 10000, "10000")).isTrue();
    }
    @Test void unifiedExpectedAndNetAmountsOverrideMissingOrStaleEngineeringTerms() {
        var jdbc=mock(JdbcTemplate.class);
        var account=mock(UnifiedStageAccountPort.class);
        var post=new PostSnapshot(1,2,3,"store","finance",null,false,Set.of());
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(3L),eq(7L),eq("SIGN_AFTER"))).thenReturn(List.of(999L));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(3L),eq(7L))).thenReturn(9L);
        var service=new PerfCommissionService(null,null,null,null,jdbc,null,account);
        when(account.stageBalances(post,9L)).thenReturn(List.of(new UnifiedStageAccountPort.StageBalance("SIGN_AFTER",100000,100000)));
        var full=service.stageFacts(post,7,"SIGN_AFTER");
        assertThat(full.required()).isEqualTo(100000);
        assertThat(full.eligible()).isTrue();
        when(account.stageBalances(post,9L)).thenReturn(List.of(new UnifiedStageAccountPort.StageBalance("SIGN_AFTER",100000,90000,10000)));
        var refund=service.stageFacts(post,7,"SIGN_AFTER");
        assertThat(refund.received()).isEqualTo(90000);
        assertThat(refund.eligible()).isFalse();
        assertThat(refund.signature()).isNotEqualTo(full.signature());
        when(account.stageBalances(post,9L)).thenReturn(List.of(new UnifiedStageAccountPort.StageBalance("SIGN_AFTER",100000,100000,10000)));
        var recovered = service.stageFacts(post,7,"SIGN_AFTER");
        assertThat(recovered.eligible()).isTrue();
        assertThat(PerfCommissionService.sameStageSignature(recovered.signature(), full.signature())).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(recovered.signature(), refund.signature())).isFalse();
        assertThat(PerfCommissionService.sameStageSignature(recovered.signature(), service.stageFacts(post,7,"SIGN_AFTER").signature())).isTrue();
        assertThat(full.signature()).isEqualTo("required:100000|unified:9:SIGN_AFTER:100000");
    }
}
