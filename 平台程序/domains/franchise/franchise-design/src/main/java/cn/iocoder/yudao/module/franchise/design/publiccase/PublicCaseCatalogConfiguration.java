package cn.iocoder.yudao.module.franchise.design.publiccase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "zhongshu.design.remote.enabled", havingValue = "true")
@EnableConfigurationProperties(PublicCaseCatalogProperties.class)
public class PublicCaseCatalogConfiguration {

    @Bean
    PublicCaseCatalogClient publicCaseCatalogClient(PublicCaseCatalogProperties properties, ObjectMapper mapper) {
        if (properties.getOrigin() == null) {
            throw new IllegalStateException("zhongshu.design.public-cases.origin is required");
        }
        return new PublicCaseCatalogClient(new PublicCaseCatalogSettings(properties.getOrigin(), "1",
                properties.getConnectTimeout(), properties.getRequestTimeout(), properties.getMaxResponseBytes()), mapper);
    }

    @Bean(destroyMethod = "close")
    PublicCaseAccessService publicCaseAccessService(PublicCaseCatalogClient catalog, ObjectMapper mapper,
                                                     DataSource dataSource, PublicCaseCatalogProperties properties) {
        if (properties.getFavoriteHydrationConcurrency() > properties.getMaxInFlightPerTenantOrg()) {
            throw new IllegalStateException("favorite hydration concurrency must not exceed tenant-org admission");
        }
        return new PublicCaseAccessService(catalog, mapper, dataSource,
                new PublicCaseAdmission(properties.getMaxInFlight(), properties.getMaxInFlightPerTenantOrg()),
                properties.getFavoritePageTimeout(), properties.getFavoriteHydrationConcurrency(),
                properties.getMaxQueuedHydrations());
    }
}
