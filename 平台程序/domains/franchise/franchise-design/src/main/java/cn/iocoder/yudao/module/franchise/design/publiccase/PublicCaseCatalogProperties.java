package cn.iocoder.yudao.module.franchise.design.publiccase;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("zhongshu.design.public-cases")
public class PublicCaseCatalogProperties {

    private URI origin;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration favoritePageTimeout = Duration.ofSeconds(8);
    private int maxResponseBytes = 2 * 1024 * 1024;
    private int maxInFlight = 16;
    private int maxInFlightPerTenantOrg = 4;
    private int favoriteHydrationConcurrency = 4;
    private int maxQueuedHydrations = 128;

    public URI getOrigin() { return origin; }
    public void setOrigin(URI origin) { this.origin = origin; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getFavoritePageTimeout() { return favoritePageTimeout; }
    public void setFavoritePageTimeout(Duration favoritePageTimeout) { this.favoritePageTimeout = favoritePageTimeout; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getMaxInFlight() { return maxInFlight; }
    public void setMaxInFlight(int maxInFlight) { this.maxInFlight = maxInFlight; }
    public int getMaxInFlightPerTenantOrg() { return maxInFlightPerTenantOrg; }
    public void setMaxInFlightPerTenantOrg(int maxInFlightPerTenantOrg) { this.maxInFlightPerTenantOrg = maxInFlightPerTenantOrg; }
    public int getFavoriteHydrationConcurrency() { return favoriteHydrationConcurrency; }
    public void setFavoriteHydrationConcurrency(int favoriteHydrationConcurrency) { this.favoriteHydrationConcurrency = favoriteHydrationConcurrency; }
    public int getMaxQueuedHydrations() { return maxQueuedHydrations; }
    public void setMaxQueuedHydrations(int maxQueuedHydrations) { this.maxQueuedHydrations = maxQueuedHydrations; }
}
