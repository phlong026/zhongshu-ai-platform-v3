package cn.iocoder.yudao.module.franchise.design.publiccase;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicCaseAdmissionTest {

    @Test
    void perScopeLimitUsesTenantAndOrganizationTogether() {
        var admission = new PublicCaseAdmission(2, 1);
        try (var firstTenant = admission.enter(31, 10)) {
            assertThatThrownBy(() -> admission.enter(31, 10))
                    .isInstanceOf(PublicCaseAdmission.BusyException.class);
            assertThatCode(() -> {
                try (var otherTenant = admission.enter(32, 10)) {
                    // same org id in another tenant is an independent scope
                }
            }).doesNotThrowAnyException();
        }
    }
}
