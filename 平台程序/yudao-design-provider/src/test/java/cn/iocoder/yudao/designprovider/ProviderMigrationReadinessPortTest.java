package cn.iocoder.yudao.designprovider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderMigrationReadinessPortTest {

    @Test
    void webPortIsUnavailableWhileSchemaValidationIsBlocked() throws Exception {
        int port;
        try (var reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        BlockingDataSource.entered = entered;
        BlockingDataSource.release = release;
        var application = new SpringApplication(ProbeApplication.class);
        var future = CompletableFuture.runAsync(() -> application.run("--server.port=" + port,
                "--spring.main.banner-mode=off", "--spring.main.lazy-initialization=true"));
        try {
            if (!entered.await(10, TimeUnit.SECONDS)) future.join();
            assertThatThrownBy(() -> {
                try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 300); }
            }).isInstanceOf(java.io.IOException.class);
        } finally {
            release.countDown();
        }
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class);
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration(ServletWebServerFactoryAutoConfiguration.class)
    @Import(ProviderMigrationReadinessConfiguration.class)
    static class ProbeApplication {
        @Bean DataSourceHolder dataSourceHolder() { return new DataSourceHolder(); }
        @Bean javax.sql.DataSource dataSource(DataSourceHolder ignored) { return new BlockingDataSource(); }
    }

    static final class DataSourceHolder { }

    static final class BlockingDataSource extends AbstractDataSource {
        static CountDownLatch entered;
        static CountDownLatch release;
        @Override public Connection getConnection() throws SQLException {
            entered.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            throw new SQLException("intentional schema probe failure");
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }
}
