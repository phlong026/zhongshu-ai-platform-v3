package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.module.franchise.content.dal.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PG/Flyway/MyBatis：定位入库、清空、隔离以及二创快照冻结。 */
class ContentIpProfilePgIntegrationTest extends FranchisePgIntegrationTest {
    @Resource FrContentPersonaMapper mapper;
    @Resource DataSource dataSource;
    @Resource org.springframework.transaction.PlatformTransactionManager transactionManager;
    ContentPersonaService personas;
    JdbcTemplate jdbc;
    @BeforeEach void setup(){jdbc=new JdbcTemplate(dataSource);jdbc.execute("TRUNCATE fr_content_persona");personas=new ContentPersonaService(mapper);}
    Map<String,Object> profile(){return new LinkedHashMap<>(Map.of(
        "name","张工","who","乡墅设计师","sell","泸州自建房设计","audience","准备回乡建房的家庭",
        "expressionStyle","专业、通俗","audienceNeeds","预算和采光","factualBackground","已核实的从业资料",
        "sampleScript","先讲清预算，再确定适合家人的方案。","forbiddenClaims","全网最低、保证零增项"));}

    @Test void allIpFieldsRoundTripAndLegacyPartialUpdatesPreserveThem(){
        var person=personas.create(33,77,profile());
        var loaded=personas.list(33).get(0);
        assertThat(loaded.getAudienceNeeds()).isEqualTo("预算和采光");
        assertThat(loaded.getExpressionStyle()).isEqualTo("专业、通俗");
        assertThat(loaded.getFactualBackground()).isEqualTo("已核实的从业资料");
        assertThat(loaded.getSampleScript()).contains("先讲清预算");
        assertThat(loaded.getForbiddenClaims()).contains("保证零增项");
        personas.update(33,person.getId(),Map.of("who","乡墅顾问"));
        assertThat(personas.list(33).get(0).getAudienceNeeds()).isEqualTo("预算和采光");
        assertThat(personas.list(34)).isEmpty();
        assertThatThrownBy(()->personas.snapshotOf(34,person.getId())).isInstanceOf(RuntimeException.class);
    }
    @Test void clearingSupplementActuallyClearsDatabaseAndRejectsOversizedInput(){
        var person=personas.create(33,77,profile());
        personas.update(33,person.getId(),Map.of("sampleScript","", "forbiddenClaims"," ","who","","sell","","audience",""));
        assertThat(personas.list(33).get(0).getSampleScript()).isNull();
        assertThat(personas.list(33).get(0).getForbiddenClaims()).isNull();
        assertThat(personas.list(33).get(0).getWho()).isNull();
        assertThat(personas.list(33).get(0).getSell()).isNull();
        assertThat(personas.list(33).get(0).getAudience()).isNull();
        assertThatThrownBy(()->personas.update(33,person.getId(),Map.of("audienceNeeds","x".repeat(601)))).isInstanceOf(RuntimeException.class);
        assertThat(personas.list(33).get(0).getAudienceNeeds()).isEqualTo("预算和采光");
    }
    @Test void rewritingReceivesIpFieldsAndKeepsHistoricalSnapshotAfterProfileChanges(){
        var person=personas.create(33,77,profile());
        var before=personas.snapshotOf(33,person.getId());
        ContentTaskService tasks=mock(ContentTaskService.class);
        var task=new FrContentTaskDO().setId(987654321L);
        when(tasks.createCopy(eq(33L),eq(77L),eq("原始口播"),eq("更口语"),eq(person.getId()),anyString())).thenReturn(task);
        var scripts=new ContentScriptService(tasks,mock(FrContentScriptMapper.class),mock(FrContentTaskMapper.class),personas,jdbc,null);
        scripts.optimize(33,77,"原始口播","更口语",person.getId(),"link","https://example.test/video","douyin");
        verify(tasks).createCopy(33,77,"原始口播","更口语",person.getId(),before.promptJson());
        String frozen=jdbc.queryForObject("SELECT persona_snapshot::text FROM fr_content_script WHERE task_id=?",String.class,task.getId());
        assertThat(frozen).contains("预算和采光","已核实的从业资料","全网最低");
        assertThat(before.promptText()).contains("不编造资质","不得移植为本人事实","禁用表达");
        personas.update(33,person.getId(),Map.of("audienceNeeds","抗震与动线"));
        assertThat(jdbc.queryForObject("SELECT persona_snapshot::text FROM fr_content_script WHERE task_id=?",String.class,task.getId())).isEqualTo(frozen);
        assertThat(personas.snapshotOf(33,person.getId()).snapshotJson()).contains("抗震与动线");
    }
    @Test void selectedDisabledPersonCannotSilentlyFallBackToNoPersona(){
        var person=personas.create(33,77,profile());personas.update(33,person.getId(),Map.of("enabled",false));
        assertThatThrownBy(()->personas.snapshotOf(33,person.getId())).isInstanceOf(RuntimeException.class);
        assertThat(personas.snapshotOf(33,null)).isNull();
    }
    String undoSql() throws Exception {try(var input=getClass().getResourceAsStream("/db/migration-undo/franchise/V20261116.923__undo_fr_content_persona_ip_profile.sql")){return new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    @Test void rollbackAllowsOnlyEmptyFieldsOrStyleAlreadyPreservedInLegacyColumn() throws Exception {
        var person=personas.create(33,77,Map.of("name","旧人物","differentiator","专业通俗"));
        jdbc.update("UPDATE fr_content_persona SET expression_style=differentiator WHERE id=?",person.getId());
        String sql=undoSql();new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{jdbc.execute(sql);assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='fr_content_persona' AND column_name='expression_style'",Long.class)).isZero();status.setRollbackOnly();});
        assertThat(personas.list(33).get(0).getExpressionStyle()).isEqualTo("专业通俗");
    }
    @Test void rollbackCannotEraseSavedIpDetails() throws Exception {
        personas.create(33,77,profile());String sql=undoSql();
        assertThatThrownBy(()->jdbc.execute(sql)).rootCause().hasMessageContaining("IP定位资料已存在，禁止回滚923");
        assertThat(personas.list(33).get(0).getAudienceNeeds()).isEqualTo("预算和采光");
    }
}
