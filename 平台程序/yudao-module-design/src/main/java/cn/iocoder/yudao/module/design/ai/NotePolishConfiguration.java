package cn.iocoder.yudao.module.design.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link NotePolishProperties} 绑定（仿 ai-orchestration 的独立注册范式）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotePolishProperties.class)
public class NotePolishConfiguration {
}
