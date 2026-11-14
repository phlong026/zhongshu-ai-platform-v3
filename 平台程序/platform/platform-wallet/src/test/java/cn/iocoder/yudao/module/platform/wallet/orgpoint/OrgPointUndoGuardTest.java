package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class OrgPointUndoGuardTest extends PlatformPgIntegrationTest {

    @Autowired private DataSource dataSource;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = dataSource.getConnection();
        String schema = "orgundo_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        forward(911);
        forward(912);
        forward(913);
        forward(914);
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void nonEmptyLedgerRefusesPoolRollbackAndPreservesTables() throws Exception {
        execute("INSERT INTO org_point_account(id,org_id,balance) VALUES (1,1,10)");
        execute("INSERT INTO org_point_ledger(id,org_id,biz_key,type,available_delta) "
                + "VALUES (1,1,'opening','MANUAL_ADJUST',10)");

        assertThatThrownBy(() -> undo(911)).isInstanceOf(SQLException.class)
                .hasMessageContaining("company point pool rollback refused");
        execute("ROLLBACK");
        assertThat(value("SELECT count(*) FROM org_point_ledger")).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM org_point_account")).isEqualTo(1);
    }

    @Test
    void nonEmptyBindingAndReceiptRefuseTheirRollback() throws Exception {
        execute("INSERT INTO design_billing_binding(id,origin,org_id,actor_user_id,ref_type,ref_id,subject,operation_id,biz_key) "
                + "VALUES (1,'BRIDGE',1,2,'ai_job',3,'copy',4,'binding')");
        execute("INSERT INTO design_prompt_receipt(id,job_id,attempt_no,outcome) VALUES (1,3,1,'UNKNOWN')");

        assertThatThrownBy(() -> undo(913)).isInstanceOf(SQLException.class)
                .hasMessageContaining("prompt receipt rollback refused");
        execute("ROLLBACK");
        assertThatThrownBy(() -> undo(912)).isInstanceOf(SQLException.class)
                .hasMessageContaining("billing binding rollback refused");
        execute("ROLLBACK");
        assertThat(value("SELECT count(*) FROM design_billing_binding")).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM design_prompt_receipt")).isEqualTo(1);
    }

    @Test
    void emptySchemasCanRollbackInReverseOrder() throws Exception {
        undo(914);
        undo(913);
        undo(912);
        undo(911);
        assertThat(regclass("org_point_subject_price")).isNull();
        assertThat(regclass("design_prompt_receipt")).isNull();
        assertThat(regclass("design_billing_binding")).isNull();
        assertThat(regclass("org_point_ledger")).isNull();
    }

    @Test
    void activePriceRefusesRollbackAndPreservesConfiguration() throws Exception {
        execute("INSERT INTO org_point_subject_price(id,subject,unit,price_micros,min_charge,version,status) "
                + "VALUES (1,'ai_flat','CALL',2000000,1,1,'ACTIVE')");

        assertThatThrownBy(() -> undo(914)).isInstanceOf(SQLException.class)
                .hasMessageContaining("subject price rollback refused");
        execute("ROLLBACK");

        assertThat(value("SELECT count(*) FROM org_point_subject_price WHERE status='ACTIVE'"))
                .isEqualTo(1);
    }

    private void forward(int version) throws Exception { execute(script("migration", version)); }
    private void undo(int version) throws Exception { execute(script("migration-undo", version)); }

    private static String script(String kind, int version) throws Exception {
        String name = switch (version) {
            case 911 -> "org_point_pool";
            case 912 -> "design_billing_binding";
            case 913 -> "design_prompt_receipt";
            case 914 -> "org_point_subject_price";
            default -> throw new IllegalArgumentException("unsupported version " + version);
        };
        String undo = "migration-undo".equals(kind) ? "undo_" : "";
        var resource = new ClassPathResource("db/" + kind + "/platform-wallet/V20261114."
                + version + "__" + undo + name + ".sql");
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }

    private int value(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String regclass(String relation) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT to_regclass('" + relation + "')::text")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
