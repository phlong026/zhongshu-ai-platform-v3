package cn.iocoder.yudao.platformserver.wiring;

import cn.iocoder.yudao.module.platform.identity.PlatformWechatProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** B validates only its own configuration; no design credentials or workers are required. */
public final class PlatformEnvironmentPolicy {

    public static final List<String> MIGRATION_LOCATIONS = List.of(
            "classpath:db/migration/platform-bootstrap",
            "classpath:db/migration/platform", "classpath:db/migration/pf",
            "classpath:db/migration/franchise", "classpath:db/migration/franchise-system",
            "classpath:db/migration/platform-wallet", "classpath:db/migration/franchise-asset",
            "classpath:db/migration/franchise-design-remote");
    private static final String DATABASE = "zhongshu_platform_b";

    private PlatformEnvironmentPolicy() {
    }

    public static void validate(ConfigurableEnvironment environment, boolean migration) {
        List<String> problems;
        try {
            problems = configurationProblems(environment, migration);
        } catch (RuntimeException error) {
            // Binding and placeholder exceptions embed raw values in their messages and causes.
            throw new IllegalStateException("赋能平台配置无法解析；请检查配置键、格式和环境变量（敏感值已隐藏）");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("赋能平台启动配置校验失败：\n - " + String.join("\n - ", problems));
        }
    }

    private static List<String> configurationProblems(ConfigurableEnvironment environment, boolean migration) {
        List<String> problems = new ArrayList<>();
        String[] active = environment.getActiveProfiles();
        Set<String> profiles = new LinkedHashSet<>(Arrays.asList(
                active.length == 0 ? environment.getDefaultProfiles() : active));
        boolean production = profiles.contains("prod") || profiles.contains("production");
        if (!profiles.contains("pg")) {
            problems.add("必须显式启用 pg profile");
        }
        requireValue(environment, problems, "platform.legacy-design-bridge.enabled", "false");
        List<String> locations = Binder.get(environment).bind("spring.flyway.locations", Bindable.listOf(String.class))
                .orElse(List.of());
        if (locations.size() != MIGRATION_LOCATIONS.size()
                || !new LinkedHashSet<>(locations).equals(new LinkedHashSet<>(MIGRATION_LOCATIONS))) {
            problems.add("spring.flyway.locations 必须与 B 发行包的迁移目录清单完全一致");
        }
        if (environment.containsProperty("spring.flyway.ignore-migration-patterns")
                || environment.containsProperty("spring.flyway.ignore-migration-patterns[0]")) {
            problems.add("禁止设置 spring.flyway.ignore-migration-patterns 掩盖迁移历史差异");
        }
        requireValue(environment, problems, "spring.flyway.baseline-on-migrate", "false");
        requireValue(environment, problems, "spring.flyway.clean-disabled", "true");
        requireValue(environment, problems, "spring.flyway.validate-on-migrate", "true");
        if (migration) {
            if (!profiles.contains("migration")) {
                problems.add("migrate 子命令必须启用 migration profile");
            }
            requireDatabase(environment, problems, "spring.flyway.url");
            requireValue(environment, problems, "spring.flyway.user", "platform_owner");
            requirePresent(environment, problems, "spring.flyway.password");
        } else {
            if (profiles.contains("migration")) {
                problems.add("migration profile 只能由 migrate 子命令使用，不能启动 API");
            }
            requireValue(environment, problems, "spring.flyway.enabled", "false");
            requireValue(environment, problems, "spring.sql.init.mode", "never");
            requireValue(environment, problems, "spring.jpa.hibernate.ddl-auto", "none");
            requireValue(environment, problems, "spring.quartz.jdbc.initialize-schema", "never");
            requireDatabase(environment, problems, "spring.datasource.dynamic.datasource.master.url");
            requireValue(environment, problems, "spring.datasource.dynamic.datasource.master.username", "platform_app");
            requirePresent(environment, problems, "spring.datasource.dynamic.datasource.master.password");
            requirePresent(environment, problems, "spring.data.redis.host");
            requireValue(environment, problems, "spring.data.redis.username", "platform_app");
            requirePresent(environment, problems, "spring.data.redis.password");
            // The runtime account must not receive the owner's password even when migrations are disabled.
            if (environment.containsProperty("spring.flyway.password")) {
                problems.add("API 不得加载 spring.flyway.password；迁移凭据仅属于 migrate 命令");
            }
            validateProviders(environment, problems, profiles, production);
        }
        if (production) {
            for (String profile : List.of("local", "dev", "zsdev")) {
                if (profiles.contains(profile)) {
                    problems.add("生产禁止启用 " + profile + " profile");
                }
            }
            if (!migration) {
                requireValue(environment, problems, "yudao.security.mock-enable", "false");
                requireValue(environment, problems, "spring.datasource.druid.stat-view-servlet.enabled", "false");
                requireValue(environment, problems, "management.endpoint.env.show-values", "never");
                requireValue(environment, problems, "management.endpoint.configprops.show-values", "never");
                requireValue(environment, problems, "management.endpoint.health.show-details", "never");
                requireValue(environment, problems, "management.endpoint.health.show-components", "never");
                List<String> exposure = Binder.get(environment)
                        .bind("management.endpoints.web.exposure.include", Bindable.listOf(String.class)).orElse(List.of());
                if (exposure.isEmpty() || !Set.of("health", "info", "prometheus").containsAll(exposure)) {
                    problems.add("management.endpoints.web.exposure.include 仅允许 health,info,prometheus");
                }
            }
        }
        return problems;
    }

    private static void validateProviders(ConfigurableEnvironment environment, List<String> problems,
                                          Set<String> profiles, boolean production) {
        for (String key : List.of("platform.identity.wechat.provider", "platform.identity.sms.provider")) {
            String provider = environment.getProperty(key);
            if (!"real".equals(provider) && !("stub".equals(provider) && profiles.contains("local") && !production)) {
                problems.add(key + " 必须为 real；stub 只允许 local 且非生产");
            }
        }
        if ("real".equals(environment.getProperty("platform.identity.wechat.provider"))) {
            List<PlatformWechatProperties.App> apps = Binder.get(environment)
                    .bind("platform.identity.wechat.apps", Bindable.listOf(PlatformWechatProperties.App.class))
                    .orElse(List.of());
            if (apps.isEmpty() || apps.stream().anyMatch(app -> blank(app.getAppid()) || blank(app.getSecret()))) {
                problems.add("platform.identity.wechat.apps 必须提供 B 的 appid 和 secret");
            }
        }
        if ("real".equals(environment.getProperty("platform.identity.sms.provider"))) {
            for (String name : List.of("sdk-app-id", "sign-name", "template-id", "secret-id", "secret-key", "region")) {
                requirePresent(environment, problems, "platform.identity.sms." + name);
            }
        }
        String storage = environment.getProperty("zhongshu.franchise.asset.storage.provider");
        if (!"cos".equals(storage) && !("local".equals(storage) && profiles.contains("local") && !production)) {
            problems.add("zhongshu.franchise.asset.storage.provider 必须为 cos；local 只允许本地非生产");
        }
        if ("cos".equals(storage)) {
            for (String name : List.of("endpoint", "region", "bucket", "secret-id", "secret-key")) {
                requirePresent(environment, problems, "zhongshu.franchise.asset.storage.cos." + name);
            }
        }
        String content = environment.getProperty("zhongshu.content.provider");
        if (!"real".equals(content) && !("fake".equals(content) && profiles.contains("local") && !production)) {
            problems.add("zhongshu.content.provider 必须为 real；fake 只允许 local 且非生产");
        }
        if (production) {
            for (String key : List.of("wx.miniapp.appid", "wx.miniapp.secret")) {
                requirePresent(environment, problems, key);
            }
            requireValue(environment, problems, "zhongshu.content.task-sweep-enabled", "true");
        }
    }

    private static void requireDatabase(ConfigurableEnvironment environment, List<String> problems, String key) {
        String value = environment.getProperty(key, "");
        try {
            if (!value.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(value.substring("jdbc:".length()));
            if (uri.getHost() == null || !('/' + DATABASE).equals(uri.getPath())
                    || uri.getUserInfo() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            if (uri.getRawQuery() != null) {
                // PostgreSQL URL parameters take precedence over separately configured connection properties.
                Set<String> identityOptions = Set.of("user", "password", "pghost", "pgport", "pgdbname",
                        "host", "port", "dbname", "service", "options");
                for (String parameter : uri.getRawQuery().split("&")) {
                    String name = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8);
                    if (identityOptions.contains(name.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            problems.add(key + " 必须连接 B 专属 PostgreSQL 数据库 " + DATABASE
                    + "，且 URL 不得覆盖连接身份或包含凭据");
        }
    }

    private static void requireValue(ConfigurableEnvironment environment, List<String> problems, String key, String value) {
        if (!value.equalsIgnoreCase(environment.getProperty(key))) {
            problems.add(key + " 必须为 " + value);
        }
    }

    private static void requirePresent(ConfigurableEnvironment environment, List<String> problems, String key) {
        if (blank(environment.getProperty(key))) {
            problems.add("缺少 " + key);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
