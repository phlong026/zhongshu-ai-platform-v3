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
    @Test void unifiedExpectedAndNetAmountsOverrideMissingOrStaleEngineeringTerms() {
        var jdbc=mock(JdbcTemplate.class);
        var account=mock(UnifiedStageAccountPort.class);
        var post=new PostSnapshot(1,2,3,"store","finance",null,false,Set.of());
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(3L),eq(7L),eq("SIGN_AFTER"))).thenReturn(List.of(999L));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(7L))).thenReturn(8L);
        when(account.activeContractId(post,8L)).thenReturn(9L);
        var service=new PerfCommissionService(null,null,null,null,jdbc,null,account);
        when(account.stageBalances(post,9L)).thenReturn(List.of(new UnifiedStageAccountPort.StageBalance("SIGN_AFTER",100000,100000)));
        var full=service.stageFacts(post,7,"SIGN_AFTER");
        assertThat(full.required()).isEqualTo(100000);
        assertThat(full.eligible()).isTrue();
        when(account.stageBalances(post,9L)).thenReturn(List.of(new UnifiedStageAccountPort.StageBalance("SIGN_AFTER",100000,90000)));
        var refund=service.stageFacts(post,7,"SIGN_AFTER");
        assertThat(refund.received()).isEqualTo(90000);
        assertThat(refund.eligible()).isFalse();
        assertThat(refund.signature()).isNotEqualTo(full.signature());
    }
}
