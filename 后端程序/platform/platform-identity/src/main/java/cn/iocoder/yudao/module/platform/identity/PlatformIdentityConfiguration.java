package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.module.platform.authz.OrgScopeCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformWechatProperties.class)
public class PlatformIdentityConfiguration {

    /** 绑定审计按组织过滤：老板只看到本组织员工的记录 */
    @Bean
    public OrgScopeCustomizer platformIdentityOrgScope() {
        return rule -> rule.register("pf_wechat_binding_event", "org_id");
    }

}
