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
        verify(jdbc).queryForObject(contains("SELECT mode = 'MOCK'"), eq(Boolean.class));
    }

    @Test void globalRealModeDisablesDesignSimulationRegardlessOfLegacyAllowList() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        when(jdbc.queryForObject(contains("to_regclass"), eq(Boolean.class))).thenReturn(true);
        CommissionSimulatedPayments payments = new CommissionSimulatedPayments(jdbc);
        payments.configureSimulationOrganizations("101,202");
        assertThat(payments.allowed(101L, 202L)).isFalse();
    }
}
