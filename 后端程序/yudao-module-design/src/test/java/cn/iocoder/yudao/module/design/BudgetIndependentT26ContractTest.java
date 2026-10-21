package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.commerce.pricing.UsagePointPriceService;
import cn.iocoder.yudao.module.design.budget.*;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetDraftController;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_QUOTE_USER_PRICED;

/** Disposable PostgreSQL + real pricing/ledger. No live identity, production data or AI provider. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetIndependentT26ContractTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private SimpleDriverDataSource ds;
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;
    private BudgetDraftService drafts;
    private ItemizedBudgetService budgets;
    private UsagePointPriceService pricing;
    private PointAccountService points;
    private AdminBudgetRevisionService admin;
    private BudgetQuoteService quotes;
    private AppBudgetDraftController controller;
    private org.springframework.test.web.servlet.MockMvc mvc;
    private final Set<Long> blocked = ConcurrentHashMap.newKeySet();
    private static final List<String> OPTIONS = List.of("208001","208006","208009","208012","208015","208020","208023","208026","208029","208030","208032","208034","208035");
    private static final long[] PRICES = {52000,98000,52000,12000,1800000,49000,12000,12000,12000,6000,8500,500000,500000};
    @BeforeAll void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(),PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/commerce","classpath:db/migration/design").load().migrate();
        manager = new DataSourceTransactionManager(ds); jdbc = new JdbcTemplate(ds);
        // Read-only identity projection fixture; identity behavior is tested in its owning module.
        jdbc.execute("CREATE TABLE account(id bigint PRIMARY KEY,nickname varchar(100),deleted boolean DEFAULT FALSE)");
        jdbc.execute("INSERT INTO account(id,nickname) VALUES(1,'独立预算用户'),(2,'其他用户')");
        var projects = new DesignProjectService(ds,manager,mock(AiJobPort.class),null,null,null);
        AccountStatePort accounts = user -> { if(blocked.contains(user)) throw exception(RESOURCE_FORBIDDEN); };
        drafts = new BudgetDraftService(ds,manager,projects,accounts);
        points = new PointAccountService(ds,manager);
        pricing = new UsagePointPriceService(ds,manager,new PointLedgerPortAdapter(points));
        budgets = new ItemizedBudgetService(ds,manager,projects,new JdbcAuditPort(ds),pricing);
        ReflectionTestUtils.setField(budgets,"drafts",drafts);
        admin = new AdminBudgetRevisionService(ds,manager,new JdbcAuditPort(ds));
        quotes = new BudgetQuoteService(ds,manager,new JdbcAuditPort(ds));
        controller = new AppBudgetDraftController();
        ReflectionTestUtils.setField(controller,"drafts",drafts);
        ReflectionTestUtils.setField(controller,"budgets",budgets);
        IdentitySessionPort identities = token -> switch(token == null ? "" : token) {
            case "owner" -> Optional.of(new IdentitySessionPort.SessionContext(1,"test","owner",false));
            case "other" -> Optional.of(new IdentitySessionPort.SessionContext(2,"test","other",false));
            case "restricted" -> Optional.of(new IdentitySessionPort.SessionContext(1,"test","owner",true));
            default -> Optional.empty();
        };
        ReflectionTestUtils.setField(controller,"identities",identities);
        var app=new cn.iocoder.yudao.module.design.controller.app.AppBudgetController();
        ReflectionTestUtils.setField(app,"itemizedBudgetService",budgets);
        ReflectionTestUtils.setField(app,"budgetQuoteService",quotes);
        ReflectionTestUtils.setField(app,"identitySessionPort",identities);
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller,app)
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()))
            .setControllerAdvice(new cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler("independent-budget",mock(cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi.class))).build();
    }
    @BeforeEach void seed() {
        blocked.clear();
        jdbc.execute("TRUNCATE budget_quote,budget_line,budget_revision,budget_estimate,budget_draft,budget_account_price,budget_app_command,budget_catalog_command,budget_item_price,budget_price_book_version,budget_region,design_project,design_requirement_snapshot,design_result_version,audit_event,service_usage_charge,service_usage_price_rule,design_point_ledger,design_point_account,user_message,message_receipt,outbox_event");
        jdbc.update("UPDATE budget_item SET enabled=TRUE,public_selectable=TRUE,deleted=FALSE");
        jdbc.update("UPDATE budget_option SET enabled=TRUE,deleted=FALSE");
        jdbc.update("INSERT INTO budget_region(id,code,name,enabled) VALUES(810001,'TEST_A','测试地区',TRUE)");
        for(int i=0;i<OPTIONS.size();i++) jdbc.update("INSERT INTO budget_item_price(id,region_id,option_id,unit_price_cents,status,effective_at,published_by,published_at) VALUES(?,810001,?,?,'PUBLISHED','2020-01-01',99,now())",820001L+i,Long.parseLong(OPTIONS.get(i)),PRICES[i]);
        pricing.createRule("BUDGET_ESTIMATE",7,Instant.parse("2020-01-01T00:00:00Z"),null,"test");
        points.credit(1,"MANUAL_CREDIT",100,"test","seed","seed-1","test","isolated test");
    }
    private Map<String,Object> inputs() {
        return Map.of("regionCode","TEST_A","footprintArea","120","floorCount",2,"roofArea","112","quantities",Map.of("DOOR_HOUSEHOLDS","1","CULTURE_STONE_LENGTH","44","LIGHTING_WASHER_LENGTH","20","LIGHTING_STRIP_LENGTH","30","WATERPROOF_AREA","20"));
    }
    private BudgetDraftService.Draft ready(String key) {
        var d=drafts.create(1,key);
        return drafts.save(1,id(d),d.version(),Map.of("inputOverrides",inputs(),"optionIds",OPTIONS));
    }
    private long id(BudgetDraftService.Draft d) { return Long.parseLong(d.draftId()); }
    private Map<String,Object> request(BudgetDraftService.Draft d) {
        var q=pricing.quote("BUDGET_ESTIMATE");
        return Map.of("expectedVersion",d.version(),"usageConfirmation",Map.of("product",q.product(),"ruleId",Long.toString(q.ruleId()),"ruleVersion",q.ruleVersion()));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private void forbidden(Runnable work) { assertThatThrownBy(work::run).isInstanceOfSatisfying(ServiceException.class,e->assertThat(e.getCode()).isEqualTo(RESOURCE_FORBIDDEN.getCode())); }
    @Test void customProfileExpectedPriceVersionAcceptsJsonNumber() {
        jdbc.update("INSERT INTO budget_user_region (id,user_id,code,name,base_region_code,base_version,coefficient,"
                + "price_version,configured_at,status,version,tenant_id,creator,updater) "
                + "VALUES (910001,1,'MYHOME','黄冈','WUHAN',1,1,1,now(),'ACTIVE',1,0,'1','1')");
        var draft = drafts.create(1, "custom-number");
        draft = drafts.save(1, id(draft), draft.version(),
                Map.of("inputOverrides", Map.of("regionCode", "MYHOME"), "optionIds", List.of()));
        var body = new LinkedHashMap<String, Object>(request(draft));
        body.put("expectedPriceVersion", 1); // JSON/Jackson number, not String
        var value = budgets.createIndependent(1, id(draft), body, "custom-number-estimate");
        assertThat(value.draftId()).isEqualTo(draft.draftId());
    }

    @Test void twoDraftsAreIndependentAndNeverCreateProjectOrChargeUntilCalculate() {
        var a=ready("a");var b=drafts.create(1,"b");
        assertThat(drafts.create(1,"a").draftId()).isEqualTo(a.draftId());
        assertThat(drafts.get(1,id(b)).state()).isEqualTo(Map.of("inputOverrides",Map.of(),"optionIds",List.of()));
        assertThat(drafts.list(1,null,20)).hasSize(2);assertThat(drafts.list(2,null,20)).isEmpty();
        assertThat(count("design_project")).isZero();assertThat(count("service_usage_charge")).isZero();
        var value=budgets.createIndependent(1,id(a),request(a),"calc");
        assertThat(value.projectId()).isNull();assertThat(value.resultVersionId()).isNull();assertThat(value.draftId()).isEqualTo(a.draftId());
        assertThat(value.totalCents()).isEqualTo(51_658_000L);assertThat(value.completeness()).isEqualTo("COMPLETE");
        assertThat(points.findAccount(1).orElseThrow().availablePoints()).isEqualTo(93);
        assertThat(count("design_project")).isZero();
        budgets.save(1,Long.parseLong(value.budgetId()),"save");
        assertThat(budgets.pageIndependent(1,id(a),true,null,20).getList()).hasSize(1);
        assertThat(budgets.pageIndependent(1,id(b),false,null,20).getList()).isEmpty();
        assertThat(budgets.pageIndependent(2,null,false,null,20).getList()).isEmpty();
    }
    @Test void guessedIdsAndRestrictedOrBlockedAccountsCannotReadOrMutateOthers() {
        var d=ready("owner");var req=request(d);var value=budgets.createIndependent(1,id(d),req,"one");long bid=Long.parseLong(value.budgetId());
        forbidden(()->drafts.get(2,id(d)));forbidden(()->drafts.save(2,id(d),d.version(),d.state()));
        forbidden(()->drafts.importProject(2,id(d),d.version(),999,null));
        forbidden(()->budgets.createIndependent(2,id(d),req,"other"));forbidden(()->budgets.get(2,bid,null));
        forbidden(()->budgets.save(2,bid,"other-save"));forbidden(()->budgets.pageIndependent(2,id(d),false,null,20));
        forbidden(()->quotes.appListIndependent(2,bid));
        assertThatThrownBy(()->controller.create("anonymous",null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->controller.get(d.draftId(),"Bearer restricted")).isInstanceOf(ServiceException.class);
        blocked.add(1L);
        forbidden(()->drafts.create(1,"blocked"));forbidden(()->drafts.save(1,id(d),d.version(),d.state()));
        forbidden(()->budgets.createIndependent(1,id(d),req,"one"));forbidden(()->budgets.save(1,bid,"blocked-save"));
        assertThat(count("service_usage_charge")).isEqualTo(1);
    }
    @Test void duplicateConcurrentCalculationChargesOnceAndDifferentDraftCannotReuseReceipt() throws Exception {
        var d=ready("one");var body=request(d);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<String> work=()->{start.await();return budgets.createIndependent(1,id(d),body,"same-charge").budgetId();};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(b.get(30,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertThat(count("budget_estimate")).isEqualTo(1);assertThat(count("service_usage_charge")).isEqualTo(1);
        assertThat(points.countLedger(1L,"BUDGET_ESTIMATE_DEBIT")).isEqualTo(1);
        var other=ready("two");assertThatThrownBy(()->budgets.createIndependent(1,id(other),request(other),"same-charge")).isInstanceOf(ServiceException.class);
        assertThat(points.findAccount(1).orElseThrow().availablePoints()).isEqualTo(93);
    }
    @Test void stalePriceMissingRuleAndInsufficientBalanceRollbackBudgetReceiptAndChargeTogether() {
        var d=ready("rollback");var req=request(d);
        jdbc.update("UPDATE service_usage_price_rule SET version=version+1");
        assertThatThrownBy(()->budgets.createIndependent(1,id(d),req,"changed")).isInstanceOf(ServiceException.class);
        assertThat(count("budget_estimate")).isZero();assertThat(count("budget_app_command")).isZero();assertThat(count("service_usage_charge")).isZero();
        points.debit(1,"MANUAL_DEBIT",100,"test","empty","empty","test","test");
        assertThatThrownBy(()->budgets.createIndependent(1,id(d),request(d),"poor")).isInstanceOf(ServiceException.class);
        assertThat(count("budget_revision")).isZero();assertThat(count("budget_app_command")).isZero();assertThat(count("service_usage_charge")).isZero();
        jdbc.update("UPDATE service_usage_price_rule SET status='RETIRED'");
        assertThatThrownBy(()->budgets.createIndependent(1,id(d),req,"missing")).isInstanceOf(IllegalStateException.class);
        assertThat(count("budget_estimate")).isZero();
    }
    @Test void saveRetryIsIdempotentButStaleDifferentContentCannotOverwriteAndOldChargeReplays() {
        var initial=drafts.create(1,"save");var d=drafts.save(1,id(initial),1,Map.of("inputOverrides",inputs(),"optionIds",OPTIONS));
        assertThat(drafts.save(1,id(d),1,d.state()).version()).isEqualTo(2);
        assertThatThrownBy(()->drafts.save(1,id(d),1,Map.of("inputOverrides",Map.of(),"optionIds",List.of()))).isInstanceOf(ServiceException.class);
        var req=request(d);var original=budgets.createIndependent(1,id(d),req,"old-charge");
        drafts.save(1,id(d),d.version(),Map.of("inputOverrides",inputs(),"optionIds",List.of()));
        assertThat(budgets.createIndependent(1,id(d),req,"old-charge").budgetId()).isEqualTo(original.budgetId());
        assertThatThrownBy(()->budgets.createIndependent(1,id(d),req,"new-stale")).isInstanceOf(ServiceException.class);
        assertThat(points.countLedger(1L,"BUDGET_ESTIMATE_DEBIT")).isEqualTo(1);
    }
    @Test void optionalImportIsAuthorizedFrozenCopyAndNeverMovesExistingBudgetToProject() {
        jdbc.update("INSERT INTO design_project(id,user_id,source_type) VALUES(901,1,'SELF_UPLOAD'),(902,2,'SELF_UPLOAD')");
        jdbc.update("INSERT INTO design_requirement_snapshot(id,project_id,input_version,inputs) VALUES(903,901,1,CAST(? AS jsonb))",JsonUtils.toJsonString(Map.of("budgetInputs",inputs())));
        var d=ready("copy");var original=budgets.createIndependent(1,id(d),request(d),"before-import");
        forbidden(()->drafts.importProject(1,id(d),d.version(),902,null));
        var imported=drafts.importProject(1,id(d),d.version(),901,null);
        assertThat(imported.sourceProjectId()).isEqualTo("901");assertThat(imported.importedValues().get("footprintArea")).isEqualTo("120");
        jdbc.update("UPDATE design_requirement_snapshot SET inputs='{}' WHERE id=903");
        assertThat(drafts.get(1,id(d)).importedValues().get("footprintArea")).isEqualTo("120");
        assertThat(budgets.get(1,Long.parseLong(original.budgetId()),null).orElseThrow().totalCents()).isEqualTo(51_658_000L);
        assertThat(jdbc.queryForObject("SELECT project_id FROM budget_estimate",Long.class)).isNull();
        assertThat(count("design_project")).isEqualTo(2);
    }
    @Test void actualMvcRoutesRequireUnrestrictedSessionAndRejectCrossAccountIds() throws Exception {
        var d=ready("mvc");var value=budgets.createIndependent(1,id(d),request(d),"mvc-budget");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        String base="/design/v1/budget-drafts", route=base+"/"+d.draftId();
        var routes=List.of(
            Map.of("method","GET","path",base,"body","{}"),
            Map.of("method","POST","path",base,"body","{}"),
            Map.of("method","GET","path",route,"body","{}"),
            Map.of("method","PUT","path",route,"body",JsonUtils.toJsonString(Map.of("expectedVersion",d.version(),"state",d.state()))),
            Map.of("method","POST","path",route+"/import","body",JsonUtils.toJsonString(Map.of("expectedVersion",d.version(),"projectId","999"))),
            Map.of("method","POST","path",route+"/estimates","body",JsonUtils.toJsonString(request(d))),
            Map.of("method","GET","path","/design/v1/budget-estimates/independent","body","{}"),
            Map.of("method","GET","path","/design/v1/budget-estimates/"+value.budgetId()+"/quotes","body","{}"),
            Map.of("method","GET","path","/design/v1/budget-estimates/"+value.budgetId()+"/quotes/999","body","{}"));
        for(String token:List.of("missing","restricted")) for(var r:routes) {
            var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(r.get("method")),r.get("path"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(r.get("body")).header("Idempotency-Key","denied").header("Authorization","Bearer "+token)).andReturn();
            assertThat(mapper.readTree(result.getResponse().getContentAsByteArray()).path("code").asInt()).isNotZero();
        }
        for(String path:List.of(route,"/design/v1/budget-estimates/independent?draftId="+d.draftId(),"/design/v1/budget-estimates/"+value.budgetId(),"/design/v1/budget-estimates/"+value.budgetId()+"/quotes","/design/v1/budget-estimates/"+value.budgetId()+"/quotes/999")) {
            var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer other")).andReturn().getResponse();
            assertThat(mapper.readTree(response.getContentAsByteArray()).path("code").asInt()).isEqualTo(RESOURCE_FORBIDDEN.getCode());
        }
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/design/v1/budget-estimates/independent?savedOnly=false").header("Authorization","Bearer owner")).andReturn().getResponse();
        var json=mapper.readTree(response.getContentAsByteArray());assertThat(json.path("code").asInt()).isZero();
        assertThat(json.path("data").path("list").get(0).path("draftId").asText()).isEqualTo(d.draftId());
        assertThat(count("service_usage_charge")).isEqualTo(1);
    }

    @Test void missingPriceQuantityAndOwnPriceOverrideReuseExistingCalculatorAndFreezeAmounts() {
        var d=ready("price");
        jdbc.update("INSERT INTO budget_account_price(id,account_id,option_id,unit_price_cents) VALUES(1001,1,208001,53000),(1002,2,208006,1)");
        var complete=budgets.createIndependent(1,id(d),request(d),"override");
        assertThat(complete.totalCents()).isEqualTo(51_778_000L);
        // 用户报价配置只供用户参考，不能形成平台正式报价
        assertThatThrownBy(() -> quotes.create(99, complete.budgetId(), "user-priced-quote",
                Map.of("revisionId", complete.revisionId(), "expectedVersion", 1, "reason", "用户自定价报价", "adjustmentCents", 0)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BUDGET_QUOTE_USER_PRICED.getCode()));
        jdbc.update("UPDATE budget_item_price SET status='DRAFT' WHERE option_id=208009");
        var missing=budgets.createIndependent(1,id(d),request(d),"missing-price");
        assertThat(missing.totalCents()).isNull();assertThat(missing.completeness()).isEqualTo("INCOMPLETE");
        assertThat(missing.pricedSubtotalCents()).isEqualTo(45_954_000L);
        var inputs=new LinkedHashMap<>(inputs());inputs.remove("roofArea");
        var updated=drafts.save(1,id(d),d.version(),Map.of("inputOverrides",inputs,"optionIds",OPTIONS));
        var noRoof=budgets.createIndependent(1,id(d),request(updated),"missing-both");
        assertThat(JsonUtils.toJsonString(noRoof)).contains("MISSING_PRICE");
        assertThat(budgets.get(1,Long.parseLong(complete.budgetId()),null).orElseThrow().totalCents()).isEqualTo(51_778_000L);
        assertThat(points.findAccount(1).orElseThrow().availablePoints()).isEqualTo(79);
    }

    @Test void adminRevisionAndPublishedQuoteSupportIndependentRecordWithoutChangingUserSnapshot() {
        var d=ready("admin");var original=budgets.createIndependent(1,id(d),request(d),"admin-budget");
        assertThat(admin.list(null,null,"独立快速预算",1,20).getList()).hasSize(1);
        assertThat(admin.list(null,null,"独立预算用户",1,20).getList()).hasSize(1);
        assertThat(admin.list(null,null,"99999999999999999999",1,20).getList()).isEmpty();
        var detail=admin.get(original.budgetId(),null);assertThat(detail.projectId()).isNull();
        var line=detail.lines().stream().filter(l->"FOUNDATION".equals(l.itemCode())).findFirst().orElseThrow();
        var changed=admin.revise(99,original.budgetId(),"revision",Map.of("expectedVersion",1,"reason","独立预算核价","updates",List.of(Map.of("lineId",line.lineId(),"unitPriceCents",53000)),"additions",List.of(),"removeLineIds",List.of()));
        assertThat(changed.totalCents()).isEqualTo(51_778_000L);
        var quote=quotes.create(99,original.budgetId(),"quote",Map.of("revisionId",changed.revisionId(),"expectedVersion",2,"reason","报价","adjustmentCents",-2000));
        assertThat(quotes.appListIndependent(1,Long.parseLong(original.budgetId()))).isEmpty();
        forbidden(()->quotes.appGetByBudget(1,original.budgetId(),quote.quoteId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type LIKE 'BUDGET_QUOTE_%'",Long.class)).isZero();
        var failingDelivery=new BudgetQuoteService(ds,manager,new JdbcAuditPort(ds),event->{throw new IllegalStateException("fixture-outbox-unavailable");});
        assertThatThrownBy(()->failingDelivery.publish(99,quote.quoteId(),"publish-failed",Map.of("expectedVersion",1,"reason","发布"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM budget_quote WHERE id=?",String.class,Long.parseLong(quote.quoteId()))).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT version FROM budget_quote WHERE id=?",Integer.class,Long.parseLong(quote.quoteId()))).isEqualTo(1);
        quotes.publish(99,quote.quoteId(),"publish",Map.of("expectedVersion",1,"reason","发布"));
        quotes.publish(99,quote.quoteId(),"publish",Map.of("expectedVersion",1,"reason","发布"));
        var published=quotes.appListIndependent(1,Long.parseLong(original.budgetId()));assertThat(published).hasSize(1);assertThat(published.get(0).projectId()).isNull();assertThat(published.get(0).finalPriceCents()).isEqualTo(51_776_000L);
        var exact=quotes.appGetByBudget(1,original.budgetId(),quote.quoteId());
        assertThat(exact.revisionId()).isEqualTo(changed.revisionId());assertThat(exact.projectId()).isNull();
        forbidden(()->quotes.appGetByBudget(2,original.budgetId(),quote.quoteId()));
        forbidden(()->quotes.appGetByBudget(1,"999",quote.quoteId()));
        var messages=new cn.iocoder.yudao.module.design.notification.MessageService(ds);
        var publishEvent=quoteEvent("BUDGET_QUOTE_PUBLISHED",quote.quoteId());
        messages.deliver(publishEvent);messages.deliver(new cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord(990001,"BUDGET_QUOTE_PUBLISHED","budget_quote",quote.quoteId(),publishEvent.getPayload(),"fixture"));
        assertThat(messages.list(1,10)).hasSize(1);assertThat(messages.counts(1).pending()).isZero();
        long messageId=((Number)messages.list(1,10).get(0).get("id")).longValue();
        assertThat(messages.target(1,messageId)).containsEntry("type","QUOTE").containsEntry("quoteId",quote.quoteId()).containsEntry("revisionId",changed.revisionId()).doesNotContainKey("projectId");
        quotes.withdraw(99,quote.quoteId(),"withdraw",Map.of("expectedVersion",2,"reason","撤回"));
        messages.deliver(quoteEvent("BUDGET_QUOTE_WITHDRAWN",quote.quoteId()));
        messages.deliver(publishEvent); // delayed publish must stay the original fact after withdrawal
        assertThat(messages.list(1,10)).hasSize(2);assertThat(messages.target(1,messageId)).containsEntry("status","WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type LIKE 'BUDGET_QUOTE_%'",Long.class)).isEqualTo(2);
        var withdrawn=quotes.appListIndependent(1,Long.parseLong(original.budgetId())).get(0);
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");assertThat(withdrawn.current()).isTrue();
        assertThat(budgets.get(1,Long.parseLong(original.budgetId()),null).orElseThrow().totalCents()).isEqualTo(51_658_000L);
        assertThat(points.countLedger(1L,"BUDGET_ESTIMATE_DEBIT")).isEqualTo(1);
    }

    private cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord quoteEvent(String type,String quote) {
        return jdbc.queryForObject("SELECT id,event_type,biz_type,biz_id,payload::text FROM outbox_event WHERE event_type=? AND biz_id=?",
                (rs,n)->new cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),"fixture"),type,quote);
    }
}
