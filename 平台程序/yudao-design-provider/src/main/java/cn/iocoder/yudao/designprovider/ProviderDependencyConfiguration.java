package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.identity.account.JdbcAccountStateAdapter;
import cn.iocoder.yudao.module.design.ai.NotePolishProperties;
import cn.iocoder.yudao.module.design.ai.NotePolishService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Explicit aliases needed by reused services without scanning the identity application's other beans. */
@Configuration(proxyBeanMethods = false)
class ProviderDependencyConfiguration {
    @Bean("jdbcAccountStateAdapter")
    @Primary
    JdbcAccountStateAdapter jdbcAccountStateAdapter(DataSource source) {
        return new JdbcAccountStateAdapter(source);
    }

    @Bean
    @Primary
    NotePolishService providerNotePolishService(NotePolishProperties properties,
            @Value("${zhongshu.ai.internal-secret:}") String internalSecret,
            @Value("${zhongshu.design.provider.note-polish.per-minute:10}") int perMinute,
            @Value("${zhongshu.design.provider.note-polish.max-concurrent:8}") int maxConcurrent) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var limiter = new ProviderNotePolishLimiter(perMinute, maxConcurrent, System::currentTimeMillis);
        return new NotePolishService(properties, internalSecret, (url, body, headers) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(request::header);
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new NotePolishService.Response(response.statusCode(), response.body());
        }, limiter);
    }
}
