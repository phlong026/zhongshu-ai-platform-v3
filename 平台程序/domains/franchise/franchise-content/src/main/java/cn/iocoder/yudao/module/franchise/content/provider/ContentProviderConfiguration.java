package cn.iocoder.yudao.module.franchise.content.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 内容域 provider 接线：zhongshu.content.provider=real（默认，密钥缺省时调用点报 1_114_000_006）
 * 或 fake（仅开发环境，e2e 零付费；生产由 RealServiceWiringPolicy + 构造器断言双闸硬禁）。
 */
@Configuration
@EnableConfigurationProperties(ContentProviderProperties.class)
public class ContentProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(ContentProviderTransport.class)
    public ContentProviderTransport contentProviderTransport() {
        return new ContentProviderTransport.Jdk();
    }

    @Configuration
    @ConditionalOnProperty(prefix = "zhongshu.content", name = "provider", havingValue = "real", matchIfMissing = true)
    static class RealClients {

        @Bean
        public LinkParsePort diadiLinkClient(ContentProviderProperties properties,
                                             ContentProviderTransport transport,
                                             ContentProviderCallRecorder recorder) {
            return new DiadiLinkClient(properties.getDiadi(), transport, recorder);
        }

        @Bean
        public AsrPort dashScopeAsrClient(ContentProviderProperties properties,
                                          ContentProviderTransport transport,
                                          ContentProviderCallRecorder recorder) {
            return new DashScopeAsrClient(properties.getDashscope(), transport, recorder);
        }

        @Bean
        public ScriptRewritePort deepSeekChatClient(ContentProviderProperties properties,
                                                    ContentProviderTransport transport,
                                                    ContentProviderCallRecorder recorder) {
            return new DeepSeekChatClient(properties.getDeepseek(), transport, recorder);
        }

        @Bean
        public OralVideoPort hiflyOralClient(ContentProviderProperties properties,
                                             ContentProviderTransport transport,
                                             ContentProviderCallRecorder recorder) {
            return new HiflyOralClient(properties.getHifly(), transport, recorder);
        }

        @Bean
        public VideoGenerationPort metasoH3Client(ContentProviderProperties properties,
                                                  ContentProviderTransport transport,
                                                  ContentProviderCallRecorder recorder) {
            return new MetasoH3Client(properties.getMetaso(), transport, recorder);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "zhongshu.content", name = "provider", havingValue = "fake")
    static class FakeClients {

        @Bean
        public FakeContentProviders fakeContentProviders(ContentProviderProperties properties,
                                                         ContentProviderCallRecorder recorder,
                                                         Environment environment) {
            FakeContentProviders.assertNonProduction(environment);
            return new FakeContentProviders(properties, recorder);
        }

        @Bean
        public LinkParsePort fakeLinkParse(FakeContentProviders fake) {
            return fake.linkParsePort();
        }

        @Bean
        public AsrPort fakeAsr(FakeContentProviders fake) {
            return fake.asrPort();
        }

        @Bean
        public ScriptRewritePort fakeRewrite(FakeContentProviders fake) {
            return fake.scriptRewritePort();
        }

        @Bean
        public OralVideoPort fakeOral(FakeContentProviders fake) {
            return fake.oralVideoPort();
        }

        @Bean
        public VideoGenerationPort fakeVideo(FakeContentProviders fake) {
            return fake.videoGenerationPort();
        }
    }

}
