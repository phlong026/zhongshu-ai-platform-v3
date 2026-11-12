package cn.iocoder.yudao.platformmigration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** First-admin provisioning is an explicit offline command, never an API or a startup seed. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlywayProperties.class)
public class PlatformAdminBootstrapConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrapConfiguration.class);

    @Bean
    ApplicationRunner bootstrapPlatformAdmin(FlywayProperties database, Environment environment) {
        return args -> bootstrap(database.getUrl(), database.getUser(), database.getPassword(),
                credentialPath(environment, "platform.bootstrap.admin-username-file"),
                credentialPath(environment, "platform.bootstrap.admin-password-file"));
    }

    public static long bootstrap(String url, String databaseUser, String databasePassword,
                                 Path usernameFile, Path passwordFile) {
        String username = readCredential(usernameFile);
        String password = readCredential(passwordFile);
        if (!username.matches("[a-zA-Z0-9]{4,30}")) {
            throw new IllegalStateException("初始化用户名须为4至30位字母或数字");
        }
        // The existing management login DTO accepts at most 16 characters. Do not create an unusable account.
        if (password.length() < 12 || password.length() > 16 || password.chars().anyMatch(Character::isWhitespace)
                || password.chars().noneMatch(Character::isUpperCase)
                || password.chars().noneMatch(Character::isLowerCase)
                || password.chars().noneMatch(Character::isDigit)
                || password.chars().allMatch(Character::isLetterOrDigit)) {
            throw new IllegalStateException("初始化密码须为12至16位，包含大小写字母、数字及符号，不得含空白");
        }
        String encoded = new BCryptPasswordEncoder().encode(password);
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", databaseUser);
        connectionProperties.setProperty("password", databasePassword);
        connectionProperties.setProperty("connectTimeout", "10");
        connectionProperties.setProperty("socketTimeout", "30");
        try (Connection connection = DriverManager.getConnection(url, connectionProperties)) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (var identity = statement.executeQuery("SELECT current_database(), current_user")) {
                        if (!identity.next() || !"zhongshu_platform_b".equals(identity.getString(1))
                                || !"platform_owner".equals(identity.getString(2))) {
                            throw new IllegalStateException("初始化仅允许 B 专属数据库的 platform_owner 执行");
                        }
                    }
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                    // Serialize simultaneous first-admin commands and account inserts before checking emptiness.
                    statement.execute("LOCK TABLE system_users IN EXCLUSIVE MODE");
                    try (var users = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM system_users)")) {
                        users.next();
                        if (users.getBoolean(1)) {
                            throw new IllegalStateException("B 已有管理账号，拒绝覆盖或重复初始化");
                        }
                    }
                }
                long roleId;
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (var roles = statement.executeQuery("SELECT r.id FROM system_role r JOIN system_tenant t ON t.id=r.tenant_id "
                            + "WHERE r.code='super_admin' AND r.tenant_id=1 AND r.deleted=0 AND r.status=0 "
                            + "AND t.deleted=0 AND t.status=0 FOR UPDATE OF r,t")) {
                        if (!roles.next()) {
                            throw new IllegalStateException("缺少 B 总部租户或超级管理员角色，请先执行迁移");
                        }
                        roleId = roles.getLong(1);
                        if (roles.next()) {
                            throw new IllegalStateException("B 超级管理员角色不唯一，拒绝初始化");
                        }
                    }
                }
                long userId;
                try (var insert = connection.prepareStatement("INSERT INTO system_users "
                        + "(id,username,password,nickname,dept_id,status,tenant_id,creator,updater) "
                        + "VALUES(nextval('system_users_seq'),?,?,'平台管理员',100,0,1,'bootstrap','bootstrap') RETURNING id")) {
                    insert.setQueryTimeout(10);
                    insert.setString(1, username);
                    insert.setString(2, encoded);
                    try (var result = insert.executeQuery()) {
                        result.next();
                        userId = result.getLong(1);
                    }
                }
                try (var binding = connection.prepareStatement("INSERT INTO system_user_role "
                        + "(id,user_id,role_id,tenant_id,creator,updater) "
                        + "VALUES(nextval('system_user_role_seq'),?,?,1,'bootstrap','bootstrap')")) {
                    binding.setQueryTimeout(10);
                    binding.setLong(1, userId);
                    binding.setLong(2, roleId);
                    binding.executeUpdate();
                }
                try (var tenant = connection.prepareStatement("UPDATE system_tenant SET contact_user_id=?,contact_name='平台管理员' "
                        + "WHERE id=1 AND deleted=0 AND status=0")) {
                    tenant.setQueryTimeout(10);
                    tenant.setLong(1, userId);
                    if (tenant.executeUpdate() != 1) {
                        throw new IllegalStateException("B 总部租户更新失败");
                    }
                }
                try (var department = connection.prepareStatement("UPDATE system_dept SET leader_user_id=? "
                        + "WHERE id=100 AND tenant_id=1 AND deleted=0")) {
                    department.setQueryTimeout(10);
                    department.setLong(1, userId);
                    if (department.executeUpdate() != 1) {
                        throw new IllegalStateException("B 总部部门缺失，请先执行迁移");
                    }
                }
                connection.commit();
                log.warn("[bootstrap-admin][已创建 B 首位管理员，userId={}]", userId);
                return userId;
            } catch (SQLException | RuntimeException error) {
                rollback(connection);
                if (error instanceof SQLException sql) {
                    log.error("[bootstrap-admin][初始化事务失败，sqlState={}]", sql.getSQLState());
                    throw new IllegalStateException("B 管理员初始化事务失败，未保存账号或角色绑定");
                }
                log.warn("[bootstrap-admin][初始化被拒绝，未保存账号或角色绑定]");
                throw (RuntimeException) error;
            }
        } catch (SQLException error) {
            log.error("[bootstrap-admin][数据库连接或关闭失败，sqlState={}]", error.getSQLState());
            throw new IllegalStateException("B 管理员初始化数据库操作失败");
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException error) {
            log.error("[bootstrap-admin][回滚失败，sqlState={}]", error.getSQLState());
            throw new IllegalStateException("B 初始化回滚失败，必须检查数据库状态后再操作");
        }
    }

    private static Path credentialPath(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("必须配置凭据文件路径 " + key);
        }
        return Path.of(value);
    }

    private static String readCredential(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 1024) {
                throw new IOException("Invalid credential file");
            }
            String value = Files.readString(path);
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            return value;
        } catch (IOException error) {
            log.error("[bootstrap-admin][凭据文件读取失败，未连接数据库]");
            throw new IllegalStateException("初始化凭据文件缺失、不可读或超过大小限制");
        }
    }
}
