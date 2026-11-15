package cn.iocoder.yudao.module.franchise.crm.controller.admin.vo;

import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeViewTest {

    @Test
    void returnsPersistedStageAndDefaultsLegacyRowsToReference() {
        CustomerSchemeDO scheme = new CustomerSchemeDO()
                .setId(1L).setCustomerId(2L).setKind("PLAN").setVersion(1)
                .setTitle("平面").setAuthorUserId(3L);

        assertThat(CrmRespVos.Scheme.of(scheme).stage()).isEqualTo("reference");

        scheme.setStage("formal");
        assertThat(CrmRespVos.Scheme.of(scheme).stage()).isEqualTo("formal");

        scheme.setStage("  ");
        assertThat(CrmRespVos.Scheme.of(scheme).stage()).isEqualTo("reference");

        scheme.setStage("draft");
        assertThat(CrmRespVos.Scheme.of(scheme).stage()).isEqualTo("reference");
    }
}
