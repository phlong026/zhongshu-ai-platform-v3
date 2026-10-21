package cn.iocoder.yudao.module.platform.command;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台表为单租户表，不带 tenant_id 列。yudao 的多租户拦截器对没有 @TenantIgnore 的实体会自动追加
 * tenant_id 条件，导致线上 SQL 报错（单测里没有加载该拦截器，发现不了），所以这里单独守门。
 */
class PlatformTablesTenantIgnoreTest {

    @Test
    void commandEventIsTenantIgnored() {
        assertThat(CommandEventDO.class.isAnnotationPresent(TenantIgnore.class)).isTrue();
    }

}
