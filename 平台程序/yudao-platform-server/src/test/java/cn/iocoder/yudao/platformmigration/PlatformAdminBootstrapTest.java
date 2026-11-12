package cn.iocoder.yudao.platformmigration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformAdminBootstrapTest {

    @TempDir
    Path secrets;

    @Test
    void createsHashedAdminAndRefusesOverwrite() throws Exception {
        try (var pg = database()) {
            pg.start();
            schema(pg);
            Path username = secrets.resolve("username");
            Path password = secrets.resolve("password");
            Files.writeString(username, "platformadmin\n");
            Files.writeString(password, "OnlyTestPass42!\n");
            long id = PlatformAdminBootstrapConfiguration.bootstrap(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(),
                    username, password);
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT password FROM system_users WHERE id=" + id)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isNotEqualTo("OnlyTestPass42!");
                assertThat(new BCryptPasswordEncoder().matches("OnlyTestPass42!", rows.getString(1))).isTrue();
            }
            assertThatThrownBy(() -> PlatformAdminBootstrapConfiguration.bootstrap(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), username, password))
                    .hasMessageContaining("已有").hasMessageNotContaining("OnlyTestPass42!");
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM system_user_role WHERE user_id=" + id)) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void roleBindingFailureRollsBackCreatedUser() throws Exception {
        try (var pg = database()) {
            pg.start();
            schema(pg);
            Path username = secrets.resolve("username");
            Path password = secrets.resolve("password");
            Files.writeString(username, "platformadmin");
            Files.writeString(password, "OnlyTestPass42!");
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE system_user_role ADD CONSTRAINT reject_bootstrap CHECK (user_id < 0)");
            }
            assertThatThrownBy(() -> PlatformAdminBootstrapConfiguration.bootstrap(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), username, password))
                    .hasMessageNotContaining("OnlyTestPass42!");
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM system_users")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    @Test
    void rejectsMissingAndWeakSecretsBeforeOpeningDatabase() throws Exception {
        Path username = secrets.resolve("username");
        Path password = secrets.resolve("password");
        assertThatThrownBy(() -> PlatformAdminBootstrapConfiguration.bootstrap(
                "jdbc:postgresql://invalid/zhongshu_platform_b", "platform_owner", "unused", username, password))
                .hasMessageContaining("凭据文件");
        Files.writeString(username, "platformadmin");
        Files.writeString(password, "weak");
        assertThatThrownBy(() -> PlatformAdminBootstrapConfiguration.bootstrap(
                "jdbc:postgresql://invalid/zhongshu_platform_b", "platform_owner", "unused", username, password))
                .hasMessageContaining("密码").hasMessageNotContaining("weak");
    }

    private PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>("postgres:16.15-alpine")
                .withDatabaseName("zhongshu_platform_b").withUsername("platform_owner").withPassword("owner-test-password");
    }

    private void schema(PostgreSQLContainer<?> pg) throws Exception {
        try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE system_users_seq; CREATE SEQUENCE system_user_role_seq");
            statement.execute("CREATE TABLE system_users (id bigint PRIMARY KEY, username varchar(30), password varchar(100), "
                    + "nickname varchar(30), dept_id bigint, status int, tenant_id bigint, deleted int DEFAULT 0, creator varchar(64), updater varchar(64))");
            statement.execute("CREATE TABLE system_role (id bigint PRIMARY KEY, code varchar(64), tenant_id bigint, status int, deleted int)");
            statement.execute("INSERT INTO system_role VALUES (1,'super_admin',1,0,0)");
            statement.execute("CREATE TABLE system_user_role (id bigint PRIMARY KEY,user_id bigint,role_id bigint,tenant_id bigint,creator varchar(64),updater varchar(64))");
            statement.execute("CREATE TABLE system_tenant (id bigint PRIMARY KEY,contact_user_id bigint,contact_name varchar(30),status int,deleted int)");
            statement.execute("INSERT INTO system_tenant VALUES (1,NULL,'',0,0)");
            statement.execute("CREATE TABLE system_dept (id bigint PRIMARY KEY,leader_user_id bigint,tenant_id bigint,deleted int)");
            statement.execute("INSERT INTO system_dept VALUES (100,NULL,1,0)");
        }
    }
}
