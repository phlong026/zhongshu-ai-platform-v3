package cn.iocoder.yudao.module.franchise.commission;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommissionSimulatedPaymentsModeTest {
    @Test void globalMockModeEnablesDesignSimulationForEveryOrganization() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        CommissionSimulatedPayments payments = new CommissionSimulatedPayments(jdbc);
        assertThat(payments.allowed(101L, 202L)).isTrue();
        verify(jdbc).queryForObject(contains("pf_payment_runtime_mode"), eq(Boolean.class));
    }

    @Test void globalRealModeDisablesDesignSimulationRegardlessOfLegacyAllowList() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        CommissionSimulatedPayments payments = new CommissionSimulatedPayments(jdbc);
        assertThat(payments.allowed(101L, 202L)).isFalse();
    }
}
