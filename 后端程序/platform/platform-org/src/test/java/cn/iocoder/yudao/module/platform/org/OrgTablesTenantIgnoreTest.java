package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.platform.org.dal.OrgTypeDO;
import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateDO;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 平台表单租户；未标注 @TenantIgnore 的实体会被 yudao 多租户拦截器追加 tenant_id，线上报错 */
class OrgTablesTenantIgnoreTest {

    @Test
    void allOrgEntitiesAreTenantIgnored() {
        for (Class<?> entity : List.of(OrgTypeDO.class, PostTemplateDO.class, OrgUnitDO.class, StaffPostDO.class)) {
            assertThat(entity.isAnnotationPresent(TenantIgnore.class)).as(entity.getSimpleName()).isTrue();
        }
    }

}
