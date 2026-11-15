package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.system.api.logger.OperateLogApiImpl;
import cn.iocoder.yudao.module.system.service.logger.OperateLogServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({PerfCommissionExportService.class, OperateLogApiImpl.class, OperateLogServiceImpl.class})
@TestPropertySource(properties = "platform.legacy-design-bridge.enabled=false")
class PerfCommissionExportAuditPgIntegrationTest extends FranchisePgIntegrationTest {

    @Resource private PerfCommissionExportService exports;
    @Resource private JdbcTemplate jdbc;
    @Resource private DataSource dataSource;

    private cn.iocoder.yudao.module.platform.org.PostSnapshot post;

    @BeforeEach
    void setUp() {
        var store = fixture.store();
        post = fixture.post(store.orgId(), "finance", null);
        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS system_operate_log_seq START 1");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_operate_log ("
                + "id bigint PRIMARY KEY DEFAULT nextval('system_operate_log_seq'), trace_id varchar(64) NOT NULL DEFAULT '',"
                + "user_id bigint NOT NULL, user_type smallint NOT NULL DEFAULT 0, type varchar(50) NOT NULL,"
                + "sub_type varchar(50) NOT NULL, biz_id bigint NOT NULL, action varchar(2000) NOT NULL DEFAULT '',"
                + "success boolean NOT NULL DEFAULT true, extra varchar(2000) NOT NULL DEFAULT '',"
                + "request_method varchar(16), request_url varchar(255), user_ip varchar(50), user_agent varchar(512),"
                + "creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "deleted smallint NOT NULL DEFAULT 0, tenant_id bigint NOT NULL DEFAULT 0)");
        jdbc.update("DELETE FROM system_operate_log WHERE type = '加盟商绩效导出' AND biz_id = ?",
                post.orgId());
    }

    @Test
    void attemptIsCommittedBeforeFirstByteAndSuccessIsAppendedAfterFlush() throws Exception {
        AtomicLong attemptsVisibleAtFirstByte = new AtomicLong(-1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream observing = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                observeOnce();
                bytes.write(value);
            }

            @Override
            public void write(byte[] value, int offset, int length) throws IOException {
                observeOnce();
                bytes.write(value, offset, length);
            }

            private void observeOnce() {
                if (attemptsVisibleAtFirstByte.compareAndSet(-1, count("attempt"))) {
                    // 单独 DataSource 连接已可见，证明 attempt 不依赖导出完成后的事务提交。
                }
            }
        };

        exports.writeCsv(post, plan(), observing);

        assertThat(attemptsVisibleAtFirstByte).hasValue(1);
        assertThat(count("attempt")).isEqualTo(1);
        assertThat(count("success")).isEqualTo(1);
        assertThat(bytes.size()).isPositive();
    }

    @Test
    void disconnectedStreamKeepsCommittedAttemptWithoutSuccess() {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        assertThatThrownBy(() -> exports.writeCsv(post, plan(), broken))
                .isInstanceOf(IOException.class).hasMessageContaining("client disconnected");
        assertThat(count("attempt")).isEqualTo(1);
        assertThat(count("success")).isZero();
    }

    private PerfCommissionExportService.ExportPlan plan() {
        return new PerfCommissionExportService.ExportPlan(
                "2031-01", "2031-01-01 00:00:00", "2031-02-01 00:00:00", List.of());
    }

    private long count(String status) {
        JdbcTemplate separateConnection = new JdbcTemplate(dataSource);
        return separateConnection.queryForObject(
                "SELECT count(*) FROM system_operate_log WHERE type = '加盟商绩效导出' "
                        + "AND biz_id = ? AND action LIKE ?", Long.class, post.orgId(), "%状态=" + status + "%");
    }
}
