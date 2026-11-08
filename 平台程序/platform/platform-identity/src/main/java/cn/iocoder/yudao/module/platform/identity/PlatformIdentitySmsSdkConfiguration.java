package cn.iocoder.yudao.module.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 平台身份域装配补充（2026-11-08 部署修复）：腾讯短信 SDK 客户端 Bean。
 * 缺陷背景：M02-T04 实现了消费方 TencentSmsClient，但 SDK SmsClient 的 @Bean 装配遗漏——
 * 本地单测直构造、E4 e2e 凭据门卫 SKIP，均未暴露；生产 real 部署首次启动即
 * NoSuchBeanDefinition。本装配按 provider=real 条件创建（region 传 SDK 接入地域）。
 */
@Configuration(proxyBeanMethods = false)
public class PlatformIdentitySmsSdkConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "platform.identity.sms", name = "provider", havingValue = "real")
    public com.tencentcloudapi.sms.v20210111.SmsClient tencentSmsSdkClient(
            @Value("${platform.identity.sms.secret-id}") String secretId,
            @Value("${platform.identity.sms.secret-key}") String secretKey,
            @Value("${platform.identity.sms.region:ap-guangzhou}") String region) {
        com.tencentcloudapi.common.Credential credential =
                new com.tencentcloudapi.common.Credential(secretId, secretKey);
        com.tencentcloudapi.common.profile.HttpProfile httpProfile =
                new com.tencentcloudapi.common.profile.HttpProfile();
        httpProfile.setEndpoint("sms.tencentcloudapi.com");
        com.tencentcloudapi.common.profile.ClientProfile profile =
                new com.tencentcloudapi.common.profile.ClientProfile();
        profile.setHttpProfile(httpProfile);
        return new com.tencentcloudapi.sms.v20210111.SmsClient(credential, region, profile);
    }

}
