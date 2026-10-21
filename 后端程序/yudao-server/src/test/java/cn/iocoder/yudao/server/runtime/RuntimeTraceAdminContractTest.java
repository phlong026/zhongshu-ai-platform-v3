package cn.iocoder.yudao.server.runtime;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.aiorchestration.enums.PermissionConstants;
import cn.iocoder.yudao.module.aiorchestration.job.RuntimeCallTraceService;
import cn.iocoder.yudao.server.controller.admin.runtime.RuntimeTraceAdminController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class RuntimeTraceAdminContractTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17-alpine");
    static SimpleDriverDataSource ds;
    static JdbcTemplate jdbc;
    RuntimeTraceAdminController controller;

    @BeforeAll static void migrate() {
        ds=new SimpleDriverDataSource(new org.postgresql.Driver(),PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/design",
                "classpath:db/migration/commerce","classpath:db/migration/ai-orchestration").load().migrate();
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE ai_runtime_call_trace,ai_job,ai_job_attempt,ai_job_result,design_candidate,asset");
        jdbc.execute("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,output_prefix) VALUES(1,7,'100','FLAT','SUCCEEDED',2,'ai-quarantine/1')");
        jdbc.execute("INSERT INTO ai_runtime_call_trace(id,job_id,call_id,source_attempt_no,source_fencing_token,stage,candidate_slot_no,state,request_spec,response_spec) "
                + "VALUES(2,1,'image-1-1',1,3,'GENERATE',1,'SUCCEEDED','{\"promptText\":\"private-fixture-prompt\",\"model\":\"model-v1\",\"size\":\"1536x1024\"}',"
                + "'{\"sha256\":\""+"a".repeat(64)+"\",\"width\":1,\"height\":1}')");
        jdbc.execute("INSERT INTO asset(id,owner_user_id,object_key,asset_type,source_type,sha256,stored_sha256,declared_mime,size_bytes,upload_status,security_scan_status,moderation_status,width,height) "
                + "VALUES(10,7,'accepted/10','AI_OUTPUT','AI_GENERATED',repeat('b',64),repeat('b',64),'image/png',100,'ACCEPTED','PASSED','PASSED',13,7)");
        jdbc.execute("INSERT INTO ai_job_result(id,job_id,attempt_no,provider_code,candidate_slot_no,content_sha256,source_content_sha256,object_key,mime_type,size_bytes,validation_state) "
                + "VALUES(20,1,1,'fixture',1,repeat('b',64),repeat('a',64),'output/20','image/png',100,'ACCEPTED')");
        jdbc.execute("INSERT INTO design_candidate(id,project_id,job_id,slot_no,asset_id,ai_result_id) VALUES(30,100,1,1,10,20)");
        controller=new RuntimeTraceAdminController(new RuntimeCallTraceService(ds,new DataSourceTransactionManager(ds)),ds);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(501L).setUserType(2).setTenantId(1L),new MockHttpServletRequest());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void detailUsesAcceptedAssetPixelsAndResultDigestWithoutSubstitutingRequestedOrRuntimeParsedSize() {
        var detail=controller.get("1").getData();
        assertThat(detail.calls()).hasSize(1);
        assertThat(detail.calls().get(0).request().get("size").asText()).isEqualTo("1536x1024");
        assertThat(detail.calls().get(0).response().get("width").asInt()).isEqualTo(1);
        assertThat(detail.acceptedOutputs()).hasSize(1);
        assertThat(detail.acceptedOutputs().get(0).width()).isEqualTo(13);
        assertThat(detail.acceptedOutputs().get(0).height()).isEqualTo(7);
        assertThat(detail.acceptedOutputs().get(0).assetId()).isEqualTo("10");
        assertThat(detail.acceptedOutputs().get(0).sourceSha256()).isEqualTo("a".repeat(64));
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM ai_job_result WHERE id=20",String.class)).isEqualTo("b".repeat(64));
        jdbc.execute("UPDATE asset SET width=NULL,height=NULL");
        assertThat(controller.get("1").getData().acceptedOutputs().get(0).width()).isNull();
    }

    @Test void unacceptedDeletedOrCrossJobOutputRelationsNeverBecomePixelEvidence() {
        jdbc.execute("UPDATE asset SET security_scan_status='PENDING'");
        assertThat(controller.get("1").getData().acceptedOutputs()).isEmpty();
        jdbc.execute("UPDATE asset SET security_scan_status='PASSED'");
        jdbc.execute("UPDATE asset SET stored_sha256=repeat('c',64)");
        assertThat(controller.get("1").getData().acceptedOutputs()).isEmpty();
        jdbc.execute("UPDATE asset SET stored_sha256=repeat('b',64)");
        jdbc.execute("UPDATE ai_job_result SET job_id=2");
        assertThat(controller.get("1").getData().acceptedOutputs()).isEmpty();
        jdbc.execute("UPDATE ai_job_result SET job_id=1");
        jdbc.execute("UPDATE ai_job SET deleted=TRUE");
        assertThat(controller.get("1").getData().acceptedOutputs()).isEmpty();
        assertThat(controller.get("1").getData().calls()).isEmpty();
        assertThat(controller.get("999").getData().calls()).isEmpty();
    }

    @Configuration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean DataSource dataSource() { return ds; }
        @Bean SecurityFrameworkService ss() { return mock(SecurityFrameworkService.class); }
        @Bean RuntimeCallTraceService runtimeCallTraceService() { return new RuntimeCallTraceService(ds,new DataSourceTransactionManager(ds)); }
        @Bean RuntimeTraceAdminController runtimeTraceAdminController(RuntimeCallTraceService traces) { return new RuntimeTraceAdminController(traces,ds); }
    }

    @Test void realSpringMethodSecurityRequiresTheExistingAiJobQueryPermissionBeforeReturningPrivatePrompts() {
        try(var context=new AnnotationConfigApplicationContext(SecurityConfig.class)) {
            var secured=context.getBean(RuntimeTraceAdminController.class);
            var permissions=context.getBean(SecurityFrameworkService.class);
            assertThatThrownBy(()->secured.get("1")).isInstanceOf(AccessDeniedException.class);
            when(permissions.hasPermission("design:case:query")).thenReturn(true);
            assertThatThrownBy(()->secured.get("1")).isInstanceOf(AccessDeniedException.class);
            when(permissions.hasPermission(PermissionConstants.AI_JOB_QUERY)).thenReturn(true);
            assertThat(secured.get("1").getData().calls().get(0).request().get("promptText").asText()).isEqualTo("private-fixture-prompt");
        }
    }
}
