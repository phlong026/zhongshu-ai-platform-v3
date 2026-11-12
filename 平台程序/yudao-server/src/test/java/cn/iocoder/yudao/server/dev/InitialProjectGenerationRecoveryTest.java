package cn.iocoder.yudao.server.dev;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.commerce.pricing.*;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class InitialProjectGenerationRecoveryTest {
    @Test void explicitGenerationKeyIsPreservedAndOnlyUnkeyedFirstJobUsesProjectKey() {
        try(var pg=new PostgreSQLContainer<>("postgres:17-alpine")) {
            pg.start();var ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);
            ds.setUrl(pg.getJdbcUrl());ds.setUsername(pg.getUsername());ds.setPassword(pg.getPassword());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/design","classpath:db/migration/commerce","classpath:db/migration/ai-orchestration").load().migrate();
            var tx=new DataSourceTransactionManager(ds);var jdbc=new JdbcTemplate(ds);
            var points=new PointAccountService(ds,tx);var ledger=new PointLedgerPortAdapter(points);var events=new JdbcReliableEventPort(ds);
            var orch=new AiJobOrchestrationService(ds,tx,events,null,null);var rules=new PriceRuleService(ds);
            long rule=rules.createRule("FLAT",10,1,4,Instant.now().minusSeconds(1),null);
            var settle=new AiJobSettlementService(ds,tx,new PricingPortAdapter(rules),ledger,orch,events);
            var projects=new DesignProjectService(ds,tx,new AiJobPortAdapter(settle,orch),null,null,null,
                    org.mockito.Mockito.mock(AccountStatePort.class));
            points.credit(7,"RECHARGE_BASE_CREDIT",100,"fixture","7","fixture-credit",null,"fixture");
            var inputs=Map.<String,Object>of("prompt","original intent");
            long project=projects.createProjectWithKey(7,"SELF_UPLOAD",null,null,inputs,"A");
            assertThat(projects.initialGenerationKey(project,7)).isEqualTo("A");
            assertThat(projects.initialGenerationKey(project,8)).isNull();
            var price=new PricingPort.PriceConfirmation(String.valueOf(rule),1);
            var options=GenerationImageOptions.normalize("FLAT","2K","LANDSCAPE");
            // P-10：项目创建键 A 不覆盖显式任务键 B；丢响应后继续使用 B 恢复原任务。
            var first=projects.createFlatJob(7,project,2,"B",price,options);
            assertThat(first.created()).isTrue();
            assertThat(projects.createProjectWithKey(7,"SELF_UPLOAD",null,null,inputs,"A")).isEqualTo(project);
            for(int retry=0;retry<2;retry++) {
                var recovered=projects.createFlatJob(7,project,2,"B",price,options);
                assertThat(recovered.jobId()).isEqualTo(first.jobId());
                assertThat(recovered.created()).isFalse();
            }
            assertThatThrownBy(()->projects.createFlatJob(7,project,1,"B",price,options))
                    .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                            error->assertThat(error.getCode()).isEqualTo(
                                    cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT idempotency_key FROM ai_job",String.class)).isEqualTo("B");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_charge",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='FLAT_GENERATION_DEBIT'",Integer.class)).isEqualTo(1);
            assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(80);

            // 显式换键是新请求，即使该键恰好等于项目创建键，也不能误合并到 B。
            var another=projects.createFlatJob(7,project,2,"A",price,options);
            assertThat(another.jobId()).isNotEqualTo(first.jobId());
            assertThat(another.created()).isTrue();
            assertThat(projects.createFlatJob(7,project,2,"A",price,options).jobId()).isEqualTo(another.jobId());
            assertThat(projects.initialGenerationKey(project,7)).isEqualTo("A");
            assertThat(jdbc.queryForList("SELECT idempotency_key FROM ai_job",String.class)).containsExactlyInAnyOrder("A","B");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_charge",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='FLAT_GENERATION_DEBIT'",Integer.class)).isEqualTo(2);
            assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(60);

            // 无显式键首次生成仍回退项目键，恢复时可按该持久键重试。
            long unkeyedProject=projects.createProjectWithKey(7,"SELF_UPLOAD",null,null,inputs,"C");
            var unkeyed=projects.createFlatJob(7,unkeyedProject,1,null,price,options);
            assertThat(unkeyed.created()).isTrue();
            assertThat(jdbc.queryForObject("SELECT idempotency_key FROM ai_job WHERE id=?",String.class,unkeyed.jobId())).isEqualTo("C");
            var resumed=projects.createFlatJob(7,unkeyedProject,1,"C",price,options);
            assertThat(resumed.jobId()).isEqualTo(unkeyed.jobId());
            assertThat(resumed.created()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job",Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_charge",Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='FLAT_GENERATION_DEBIT'",Integer.class)).isEqualTo(3);
            assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(50);
        }
    }
}
