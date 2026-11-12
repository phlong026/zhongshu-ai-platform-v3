package cn.iocoder.yudao.module.franchise.design.remote.transport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapabilityTransportConfiguration.Properties.class)
public class CapabilityTransportConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "zhongshu.design.remote", name = "enabled", havingValue = "true")
    DesignCapabilityTransport designCapabilityTransport(Properties properties) {
        String encoded = properties.getKeys().get(properties.getActiveKeyId());
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Dedicated active capability key is required");
        }
        return new DesignCapabilityTransport(new CapabilityTransportSettings(properties.getOrigin(),
                properties.getConnectTimeout(), properties.getRequestTimeout(), properties.getMaxRequestBytes(),
                properties.getMaxResponseBytes(), properties.getActiveKeyId(), Base64.getDecoder().decode(encoded)));
    }

    @ConfigurationProperties("zhongshu.design.remote")
    public static class Properties {
        private boolean enabled;
        private URI origin;
        private String activeKeyId;
        private Map<String, String> keys = new LinkedHashMap<>();
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxRequestBytes = 262144;
        private int maxResponseBytes = 2097152;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public URI getOrigin() { return origin; }
        public void setOrigin(URI value) { origin = value; }
        public String getActiveKeyId() { return activeKeyId; }
        public void setActiveKeyId(String value) { activeKeyId = value; }
        public Map<String, String> getKeys() { return keys; }
        public void setKeys(Map<String, String> value) { keys = value; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration value) { connectTimeout = value; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration value) { requestTimeout = value; }
        public int getMaxRequestBytes() { return maxRequestBytes; }
        public void setMaxRequestBytes(int value) { maxRequestBytes = value; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
    }
}
