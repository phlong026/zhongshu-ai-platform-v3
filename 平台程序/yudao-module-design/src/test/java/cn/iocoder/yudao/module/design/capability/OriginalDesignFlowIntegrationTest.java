package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.budget.*;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.audit.*;
import cn.iocoder.yudao.module.platform.test.TestPostgres;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real original controllers, DTO validation, drafts, calculator, profiles and PostgreSQL.
 * Only the unrelated AI runtime is mocked; no consumer wallet is available in the migrated flow. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OriginalDesignFlowIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG=TestPostgres.database("postgres:17-alpine");
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;private OriginalDesignFlow flow;private long owner,other;
    private BudgetDraftService baselineDrafts;private ItemizedBudgetService baseline;
    private CapabilityOrganizationMapping mapping;
    private static final List<String> OPTIONS=List.of("208001","208006","208009","208012","208015","208020","208023","208026","208029","208030","208032","208034","208035");
    @BeforeAll void setup(){
        var ds=new SimpleDriverDataSource(new org.postgresql.Driver(),PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE account(id bigint PRIMARY KEY,status varchar(20) NOT NULL)");jdbc.execute("INSERT INTO account VALUES(7,'ACTIVE')");
        Flyway.configure().dataSource(ds).baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/migration/platform","classpath:db/migration/commerce","classpath:db/migration/design","classpath:db/migration/design-capability").load().migrate();
        jdbc.update("INSERT INTO design_capability_organization(caller_app_id,org_id,tenant_id,active) VALUES('parity',11,1,true),('parity',12,1,true)");
        owner=jdbc.queryForObject("SELECT service_account_id FROM design_capability_organization WHERE org_id=11",Long.class);other=jdbc.queryForObject("SELECT service_account_id FROM design_capability_organization WHERE org_id=12",Long.class);
        mapping=new CapabilityOrganizationMapping(ds);
        var manager=new DataSourceTransactionManager(ds);var audit=new JdbcAuditPort(ds);var guard=new CapabilityServiceAccountGuard(ds);
        var projects=new DesignProjectService(ds,manager,mock(AiJobPort.class),mock(AssetService.class),null,null,guard);
        var context=mock(ApplicationContext.class);Map<Class<?>,Object> beans=new HashMap<>();
        beans.put(BudgetCatalogService.class,new BudgetCatalogService(ds,manager,audit));beans.put(BudgetAccountPriceService.class,new BudgetAccountPriceService(ds,manager,audit));beans.put(BudgetPriceProfileService.class,new BudgetPriceProfileService(ds,manager,audit));beans.put(BudgetQuoteService.class,new BudgetQuoteService(ds,manager,audit));
        when(context.getBean(any(Class.class))).thenAnswer(inv->beans.computeIfAbsent(inv.getArgument(0),type->mock(type)));
        flow=new OriginalDesignFlow(context,json,Validation.buildDefaultValidatorFactory().getValidator(),ds,manager,audit,guard,projects,mock(AssetService.class),mock(BudgetService.class));
        var consumerProjects=new DesignProjectService(ds,manager,mock(AiJobPort.class),null,null,null,user->{});
        baselineDrafts=new BudgetDraftService(ds,manager,consumerProjects,user->{});baseline=new ItemizedBudgetService(ds,manager,consumerProjects,audit,true);ReflectionTestUtils.setField(baseline,"drafts",baselineDrafts);
        jdbc.update("INSERT INTO budget_region(id,code,name,enabled) VALUES(810001,'PARITY','同源测试地区',true)");
        jdbc.update("INSERT INTO budget_region(id,code,name,enabled) VALUES(810002,'WUHAN','武汉',true)");
        jdbc.update("UPDATE budget_item SET enabled=true,public_selectable=true,deleted=false");
        jdbc.update("UPDATE budget_option SET enabled=true,deleted=false");
        long[] prices={52000,98000,52000,12000,1800000,49000,12000,12000,12000,6000,8500,500000,500000};
        for(int i=0;i<OPTIONS.size();i++)jdbc.update("INSERT INTO budget_item_price(id,region_id,option_id,unit_price_cents,status,effective_at,published_by,published_at) VALUES(?,810001,?,?,'PUBLISHED','2020-01-01',99,now())",820001L+i,Long.parseLong(OPTIONS.get(i)),prices[i]);
        for(int i=0;i<OPTIONS.size();i++)jdbc.update("INSERT INTO budget_item_price(id,region_id,option_id,unit_price_cents,status,effective_at,published_by,published_at) VALUES(?,810002,?,?,'PUBLISHED','2020-01-01',99,now())",830001L+i,Long.parseLong(OPTIONS.get(i)),prices[i]);
    }
    private JsonNode call(long account,String method,String path,Map<String,Object> body){
        var principal=mapping.resolve("parity",account==owner?11:12);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(principal,null,"SERVICE"));
        try{return json.valueToTree(flow.invoke(account,new DesignFlowCall(method,path,Map.of(),body,"parity-"+UUID.randomUUID())));}
        finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    private Map<String,Object> inputs(){return Map.of("regionCode","PARITY","footprintArea","120","floorCount",2,"roofArea","112","quantities",Map.of("DOOR_HOUSEHOLDS","1","CULTURE_STONE_LENGTH","44","LIGHTING_WASHER_LENGTH","20","LIGHTING_STRIP_LENGTH","30","WATERPROOF_AREA","20"));}
    private JsonNode ready(){var created=call(owner,"POST","/budget-drafts",Map.of());return call(owner,"PUT","/budget-drafts/"+created.path("draftId").asText(),Map.of("expectedVersion",created.path("version").asInt(),"state",Map.of("inputOverrides",inputs(),"optionIds",OPTIONS)));}
    @Test void sameInputsPricesSelectionsAndOriginalCalculatorProduceTheSamePublicBudget(){
        var d=ready();var migrated=call(owner,"POST","/budget-drafts/"+d.path("draftId").asText()+"/estimates",Map.of("expectedVersion",d.path("version").asInt()));
        var source=baselineDrafts.create(7,"source-calculation");source=baselineDrafts.save(7,Long.parseLong(source.draftId()),source.version(),Map.of("inputOverrides",inputs(),"optionIds",OPTIONS));
        JsonNode original=json.valueToTree(baseline.createIndependent(7,Long.parseLong(source.draftId()),Map.of("expectedVersion",source.version()),"source-estimate"));
        assertThat(migrated.path("model").asText()).isEqualTo("ITEMIZED_V1");assertThat(migrated.path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(migrated.path("totalCents").asLong()).isPositive();assertThat(scrub(migrated)).isEqualTo(scrub(original));
        assertThat(migrated.path("inputSummary").has("quoteSummary")).isFalse();
        assertThat(migrated.path("inputSummary").has("calculationProfile")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE user_id=?",Long.class,owner)).isZero();
    }
    @Test void originalQuantityOverridesReplaceTheImportedSetAndRoofAreaNeverUsesWorkbookDerivation(){
        var resolved=cn.iocoder.yudao.module.design.budget.OriginalBudgetRules.resolve(
                Map.of("faceWidth","12","depth","10","floorCount",2,"quantities",Map.of("DOOR_HOUSEHOLDS","1","WINDOW_AREA","20")),
                Map.of("quantities",Map.of("DOOR_HOUSEHOLDS","2")));
        assertThat(resolved.quantities()).containsEntry("DOOR_HOUSEHOLDS","2").doesNotContainKey("WINDOW_AREA");
        assertThat(resolved.missingFields()).contains("roofArea");assertThat(resolved.derivedQuantities()).isEmpty();
    }
    @Test void missingPriceRemainsIncompleteAndNullTotalAndNeverCreatesAProject(){
        var created=call(owner,"POST","/budget-drafts",Map.of());var saved=call(owner,"PUT","/budget-drafts/"+created.path("draftId").asText(),Map.of("expectedVersion",created.path("version").asInt(),"state",Map.of("inputOverrides",inputs(),"optionIds",List.of())));
        var result=call(owner,"POST","/budget-drafts/"+saved.path("draftId").asText()+"/estimates",Map.of("expectedVersion",saved.path("version").asInt()));
        assertThat(result.path("completeness").asText()).isEqualTo("INCOMPLETE");assertThat(result.path("totalCents").isNull()).isTrue();assertThat(result.path("missingItems").size()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project WHERE user_id=?",Long.class,owner)).isZero();
    }
    @Test void negativeServiceOwnersKeepPrivateDraftsVersionChecksProfilesAndPricesIsolated(){
        var draft=ready();String path="/budget-drafts/"+draft.path("draftId").asText();
        assertThatThrownBy(()->call(other,"GET",path,Map.of())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->call(owner,"PUT",path,Map.of("expectedVersion",0,"state",Map.of()))).isInstanceOf(RuntimeException.class);
        var region=call(owner,"POST","/budget/my-regions",Map.of("name","本公司计价地区"));String code=region.path("code").asText();assertThat(code).isNotBlank();
        assertThat(call(owner,"GET","/budget/my-regions/"+code,Map.of()).path("name").asText()).isEqualTo("本公司计价地区");
        assertThatThrownBy(()->call(other,"GET","/budget/my-regions/"+code,Map.of())).isInstanceOf(RuntimeException.class);
    }
    private JsonNode scrub(JsonNode input){JsonNode copy=input.deepCopy();strip(copy);return copy;}
    private void strip(JsonNode value){if(value.isObject())((ObjectNode)value).remove(List.of("budgetId","revisionId","projectId","draftId","resultVersionId","createdAt","lineId","projectName","schemeName"));if(value.isContainerNode())value.forEach(this::strip);}
}
