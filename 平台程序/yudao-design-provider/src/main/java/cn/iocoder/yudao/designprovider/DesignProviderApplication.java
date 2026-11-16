package cn.iocoder.yudao.designprovider;

import com.binarywang.spring.starter.wxjava.mp.config.WxMpAutoConfiguration;
import cn.iocoder.yudao.framework.apilog.config.YudaoApiLogAutoConfiguration;
import cn.iocoder.yudao.framework.dict.config.YudaoDictAutoConfiguration;
import cn.iocoder.yudao.framework.operatelog.config.YudaoOperateLogConfiguration;
import cn.iocoder.yudao.framework.quartz.config.YudaoQuartzAutoConfiguration;
import cn.iocoder.yudao.framework.security.config.YudaoSecurityAutoConfiguration;
import cn.iocoder.yudao.framework.security.config.YudaoWebSecurityConfigurerAdapter;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.framework.websocket.config.YudaoWebSocketAutoConfiguration;
import cn.iocoder.yudao.module.design.LegacyAssetAccessAdapter;
import cn.iocoder.yudao.module.design.capability.DesignCapabilityController;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.RuntimeCallTraceInternalController;
import org.dromara.trans.config.TransServiceConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * Isolated design capability process. Controller discovery is deny-by-default:
 * only the signed capability and Runtime service controllers are imported explicitly. Exclusions remove A's user-facing
 * logging, dictionary, WebSocket, Quartz, WeChat and EasyTrans surfaces; capability rejection logs,
 * durable receipts and cost ledgers remain in the scanned capability implementation.
 */
@EnableAutoConfiguration(exclude = {
        YudaoWebAutoConfiguration.class,
        YudaoApiLogAutoConfiguration.class,
        YudaoDictAutoConfiguration.class,
        YudaoOperateLogConfiguration.class,
        YudaoQuartzAutoConfiguration.class,
        YudaoSecurityAutoConfiguration.class,
        YudaoWebSecurityConfigurerAdapter.class,
        YudaoWebSocketAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        QuartzAutoConfiguration.class,
        WxMpAutoConfiguration.class,
        TransServiceConfig.class
})
@ComponentScan(basePackages = {
        "cn.iocoder.yudao.module.design",
        "cn.iocoder.yudao.module.aiorchestration",
        "cn.iocoder.yudao.module.commerce.pricing",
        "cn.iocoder.yudao.module.commerce.points",
        "cn.iocoder.yudao.module.infra.zhongshu"
}, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {RestController.class, Controller.class}),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = LegacyAssetAccessAdapter.class)
})
@Import({DesignCapabilityController.class, ProviderDependencyConfiguration.class,
        ProviderSecurityConfiguration.class, ProviderDriverReadinessConfiguration.class,
        ProviderMigrationReadinessConfiguration.class,
        ProviderCapabilityBoundaryConfiguration.class, ProviderRequiredSecretConfiguration.class,
        ProviderRuntimeSecurityConfiguration.class, ProviderRuntimeAssetController.class,
        ProviderRuntimeAuthSmokeController.class,
        ProviderPlatformAuthSmokeController.class,
        ProviderAiJobInternalController.class, ProviderPromptEventService.class, ProviderOutputTicketService.class,
        PublishedCaseReferenceImporter.class,
        RuntimeCallTraceInternalController.class,
        ProviderDesignJobDriver.class})
@EnableScheduling
public class DesignProviderApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        if (args.length > 0 && "migrate".equals(args[0])) {
            var migration = new SpringApplication(DesignProviderMigrationConfiguration.class);
            migration.setWebApplicationType(WebApplicationType.NONE);
            migration.setAdditionalProfiles("migration");
            try (var ignored = migration.run(Arrays.copyOfRange(args, 1, args.length))) {
                // Migration runner completes before run returns.
            }
            return;
        }
        SpringApplication.run(DesignProviderApplication.class, args);
    }
}
