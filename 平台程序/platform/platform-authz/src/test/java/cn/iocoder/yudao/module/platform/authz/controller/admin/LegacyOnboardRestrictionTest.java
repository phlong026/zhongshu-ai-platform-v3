package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.module.platform.authz.service.OrgOnboardService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LegacyOnboardRestrictionTest {
    @Test
    void legacyEndpointCannotOpenStoreWithoutLoginCredentials() {
        OrgOnboardController controller = new OrgOnboardController();
        OrgOnboardService service = mock(OrgOnboardService.class);
        ReflectionTestUtils.setField(controller, "orgOnboardService", service);
        assertThatThrownBy(() -> controller.onboard(new OrgOnboardController.OnboardReqVO(
                "store", "旧门店", "老板", "13800000000")))
                .hasMessageContaining("加盟商开通");
        verifyNoInteractions(service);
    }
}
