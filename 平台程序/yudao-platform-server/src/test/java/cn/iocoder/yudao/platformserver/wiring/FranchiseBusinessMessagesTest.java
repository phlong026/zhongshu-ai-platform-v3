package cn.iocoder.yudao.platformserver.wiring;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommandRecorded;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.OffsetDateTime;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FranchiseBusinessMessagesTest {
    @Container static PostgreSQLContainer<?> pg = cn.iocoder.yudao.module.platform.test.TestPostgres.database("postgres:16-alpine");
    JdbcTemplate jdbc; TransactionTemplate tx; FranchiseBusinessMessages service;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE pf_org_unit(dept_id bigint PRIMARY KEY,status text); CREATE TABLE pf_staff_post(user_id bigint,org_id bigint,role_code text,status text)");
        jdbc.execute("CREATE TABLE pf_message(id bigint PRIMARY KEY,user_id bigint,org_id bigint,sender_domain text,type text,route text,object_id bigint,title text,description text,dedupe_key text,read boolean DEFAULT false,create_time timestamptz DEFAULT now(),received_at timestamptz DEFAULT now(),read_time timestamptz,business_context jsonb DEFAULT '{}',UNIQUE(sender_domain,dedupe_key))");
        jdbc.execute("CREATE TABLE fr_customer(id bigint,company_id bigint,owner_user_id bigint,name text); CREATE TABLE fr_commission(id bigint,company_id bigint,design_org_id bigint,customer_id bigint,customer_name text,status text); CREATE TABLE fr_commission_handover(commission_id bigint,manager_user_id bigint,version int)");
        jdbc.execute("CREATE TABLE fr_content_task(id bigint,company_id bigint,author_user_id bigint,kind text,status text,create_time timestamptz); CREATE TABLE pf_business_message_activation(activated_at timestamptz)");
        jdbc.execute("CREATE TABLE pf_message_delivery_checkpoint(event_id bigint PRIMARY KEY); CREATE TABLE pf_message_center_activation(activated_at timestamptz); INSERT INTO pf_message_center_activation VALUES(now()-interval '1 hour')");
        jdbc.execute("CREATE TABLE fr_commission_simulated_order(commission_id bigint,submission_version int,status text)");
        jdbc.execute("INSERT INTO pf_business_message_activation VALUES (now()-interval '1 hour'); INSERT INTO pf_org_unit VALUES(1,'ACTIVE'),(2,'ACTIVE'),(3,'DISABLED'); INSERT INTO pf_staff_post VALUES(10,1,'sales','ACTIVE'),(20,2,'hq-design-admin','ACTIVE'),(30,1,'engineer','REVOKED'),(40,3,'sales','ACTIVE'); INSERT INTO fr_customer VALUES(100,1,10,'验收客户')");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/pf/V20261118.964__message_read_snapshots.sql")).execute(ds);
        service = new FranchiseBusinessMessages(jdbc);
    }
    FranchiseCommandRecorded event(long id,String type,long object,String command) {
        return new FranchiseCommandRecorded(new CommandEventDO().setId(id).setAggregateType(type).setAggregateId(object).setCommand(command).setOrgId(1L).setActorUserId(99L));
    }
    long count() { return jdbc.queryForObject("SELECT count(*) FROM pf_message", Long.class); }
    @Test void customerCommandIsDeduplicatedAndRolledBackWithBusiness() {
        tx.executeWithoutResult(s -> { service.onCommand(event(1,"customer",100,"customer-follow")); service.onCommand(event(1,"customer",100,"customer-follow")); });
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT user_id,route,object_id,read FROM pf_message")).containsEntry("user_id",10L).containsEntry("route","customer").containsEntry("object_id",100L).containsEntry("read",false);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> { service.onCommand(event(2,"customer",100,"customer-follow")); throw new IllegalStateException("rollback"); })).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isEqualTo(1);
    }
    @Test void commissionRoutesByCommissionIdToBusinessAndActiveDesignStaff() {
        jdbc.execute("INSERT INTO fr_commission VALUES(200,1,2,100,'验收客户','DESIGNING')");
        tx.executeWithoutResult(s -> service.onCommand(event(3,"commission",200,"commission-submit")));
        assertThat(jdbc.queryForList("SELECT user_id FROM pf_message ORDER BY user_id",Long.class)).containsExactly(10L,20L);
        assertThat(jdbc.queryForList("SELECT route FROM pf_message",String.class)).containsOnly("commission");
        assertThat(jdbc.queryForList("SELECT object_id FROM pf_message",Long.class)).containsOnly(200L);
    }
    @Test void taskDeliverySurvivesReplaysAndDoesNotBroadcastHistoryOrRevokedUsers() {
        jdbc.execute("INSERT INTO fr_content_task VALUES(1,1,10,'copy','SUCCEEDED',now()),(2,1,10,'ai_video','RUNNING',now()),(3,1,10,'digital','SUCCEEDED',now()-interval '2 hours'),(4,1,30,'asr','SUCCEEDED',now()),(5,3,40,'copy','FAILED',now())");
        tx.executeWithoutResult(s -> service.deliverTaskResults()); tx.executeWithoutResult(s -> service.deliverTaskResults());
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM pf_message",String.class)).isEqualTo("文案改写已完成");
        jdbc.execute("UPDATE fr_content_task SET status='SUCCEEDED' WHERE id=2");
        tx.executeWithoutResult(s -> service.deliverTaskResults());
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT object_id FROM pf_message ORDER BY object_id",Long.class)).containsExactly(1L,2L);
    }
    @Test void pendingSimulationDoesNotDispatchDesignAndPaidSimulationNotifiesBothFinanceTeams(){
        jdbc.execute("INSERT INTO fr_commission VALUES(200,1,2,100,'验收客户','DESIGNING'); INSERT INTO fr_commission_simulated_order VALUES(200,1,'PENDING'); INSERT INTO pf_staff_post VALUES(50,1,'finance','ACTIVE'),(60,2,'finance','ACTIVE')");
        tx.executeWithoutResult(s->service.onCommand(event(4,"commission",200,"commission-submit")));
        assertThat(jdbc.queryForList("SELECT user_id FROM pf_message",Long.class)).containsExactly(10L);
        jdbc.execute("UPDATE fr_commission_simulated_order SET status='PAID'");
        tx.executeWithoutResult(s->{service.onCommand(event(5,"commission",200,"commission-simulate-pay"));service.onCommand(event(5,"commission",200,"commission-simulate-pay"));});
        assertThat(jdbc.queryForList("SELECT user_id FROM pf_message WHERE route='commission-payment' ORDER BY user_id",Long.class)).containsExactly(50L,60L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_message WHERE user_id=20",Long.class)).isEqualTo(1);
    }
    @Test void actualPlatformBootScanIncludesListenerAndSpringEventDeliversMessage(){
        var scan=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(true);
        var boot=cn.iocoder.yudao.platformserver.YudaoPlatformServerApplication.class.getAnnotation(org.springframework.boot.autoconfigure.SpringBootApplication.class);
        boolean found=java.util.Arrays.stream(boot.scanBasePackages()).flatMap(base->scan.findCandidateComponents(base).stream()).anyMatch(bean->FranchiseBusinessMessages.class.getName().equals(bean.getBeanClassName()));
        assertThat(found).as("Actual B server must scan the message listener").isTrue();
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);context.registerBean(JdbcTemplate.class,()->jdbc);context.register(FranchiseBusinessMessages.class);context.refresh();
            tx.executeWithoutResult(s->context.publishEvent(event(6,"customer",100,"customer-follow")));
        }
        assertThat(count()).isEqualTo(1);
    }
    @Test void committedCommandsRecoverAfterListenerDowntimeWithoutHistoryOrDuplicates(){
        jdbc.execute("CREATE TABLE pf_command_event(id bigint,aggregate_type text,aggregate_id bigint,command text,occurred_at timestamptz); INSERT INTO pf_command_event VALUES(7,'customer',100,'customer-follow',now()),(8,'customer',100,'customer-follow',now()-interval '2 hours'),(9,'customer',100,'unknown',now())");
        tx.executeWithoutResult(s->service.deliverBusinessCommands());tx.executeWithoutResult(s->service.deliverBusinessCommands());
        assertThat(count()).isEqualTo(1);
    }
    cn.iocoder.yudao.module.platform.org.PostSnapshot post(long user,long org,String role){
        return new cn.iocoder.yudao.module.platform.org.PostSnapshot(1L,user,org,"store",role,null,false,java.util.Set.of());
    }
    cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService center(java.util.List<cn.iocoder.yudao.module.platform.workflow.TodoItem> tasks){
        var directory=org.mockito.Mockito.mock(cn.iocoder.yudao.module.platform.org.SystemDirectory.class);
        org.mockito.Mockito.when(directory.roleHasPermission(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("franchise:engineering:query")))
                .thenAnswer(i->java.util.Set.of("engineer","project-manager","boss","owner","finance","finance-lead").contains(i.getArgument(0)));
        return new cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService(jdbc,p->new cn.iocoder.yudao.module.platform.workflow.TodoList(tasks,tasks.size(),java.util.List.of()),java.util.List.of(new FranchiseMessageResolver(jdbc,directory)));
    }
    cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.Query query(String mode,String keyword,Integer limit,Long before){
        return new cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.Query(null,null,keyword,mode,before,limit);
    }
    @Test void inboxReadAndSummaryAreScopedToCurrentOrganizationAndUser(){
        jdbc.execute("INSERT INTO pf_message(id,user_id,org_id,type,route,object_id,title) VALUES(1,10,1,'SYSTEM','privacy',1,'本公司'),(2,10,2,'SYSTEM','privacy',1,'其他公司'),(3,20,1,'SYSTEM','privacy',1,'其他用户')");
        var center=center(java.util.List.of());var current=post(10,1,"sales");
        var page=center.list(current,query("ALL","",30,null));
        assertThat(page.items()).hasSize(1);
        assertThat(center.unread(current)).isEqualTo(1);
        assertThatThrownBy(()->center.open(current,"2")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        var snapshot=OffsetDateTime.parse(page.snapshotAt());
        assertThat(center.readAll(post(10,2,"sales"),snapshot)).isZero();
        assertThat(center.readAll(current,snapshot)).isEqualTo(1);
        assertThat(center.unread(post(10,2,"sales"))).isEqualTo(1);
        assertThat(center.unread(post(20,1,"sales"))).isEqualTo(1);
    }
    @Test void allReadDoesNotConsumeNewlyDeliveredOldEventsAndNeverCompletesBusinessTasks(){
        jdbc.execute("""
            INSERT INTO pf_message(id,user_id,org_id,type,route,object_id,title,create_time,received_at)
            VALUES(2,10,1,'SYSTEM','privacy',1,'旧消息',now()-interval '1 day',now()-interval '1 hour')
            """);
        var task=new cn.iocoder.yudao.module.platform.workflow.TodoItem("fr-perf","perf-manual:9","凭证待提交","pending",null,true,null,"/pages/admin/tasks/index?view=mine",java.time.OffsetDateTime.now());
        var center=center(java.util.List.of(task));var p=post(10,1,"sales");
        var snapshot=OffsetDateTime.parse(center.list(p,query("ALL","",30,null)).snapshotAt());
        // The late insert has both a lower ID and timestamps preceding the snapshot.
        jdbc.execute("""
            INSERT INTO pf_message(id,user_id,org_id,type,route,object_id,title,create_time,received_at)
            VALUES(1,10,1,'SYSTEM','privacy',1,'补投旧事件',now()-interval '2 days',now()-interval '1 day')
            """);
        assertThat(center.readAll(p,snapshot)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM pf_message WHERE NOT read",Long.class)).containsExactly(1L);
        assertThat(center.unread(p)).isEqualTo(1);
        assertThat(center.list(p,query("PENDING","",30,null)).items()).hasSize(1);
        assertThat(center.open(p,"todo:perf-manual:9")).containsEntry("actionable",true);
        assertThatThrownBy(()->center(java.util.List.of()).open(p,"todo:perf-manual:9")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
    @Test void searchRunsBeforePaginationAndTreatsWildcardCharactersLiterally(){
        jdbc.execute("INSERT INTO pf_message(id,user_id,org_id,type,route,object_id,title) SELECT x,10,1,'SYSTEM','privacy',1,CASE WHEN x=1 THEN '客户100%_原单' ELSE '普通消息' END FROM generate_series(1,40) x");
        var c=center(java.util.List.of());var p=post(10,1,"sales");
        assertThat(c.list(p,query("ALL","100%_",2,null)).items()).hasSize(1);
        var first=c.list(p,query("ALL","",2,null));assertThat(first.hasMore()).isTrue();
        assertThat(first.items().get(0).get("id")).isEqualTo("40");
        var second=c.list(p,query("ALL","",2,Long.valueOf(first.nextCursor())));assertThat(second.items().get(0).get("id")).isEqualTo("38");
    }
    @Test void historicalSnapshotPersistsButFormerCustomerOwnerCannotReadBusinessFacts(){
        tx.executeWithoutResult(t->service.onCommand(event(10,"customer",100,"customer-transfer")));
        jdbc.execute("UPDATE fr_customer SET name='新名称' WHERE id=100");
        var c=center(java.util.List.of());var p=post(10,1,"sales");
        var message=c.list(p,query("ALL","",30,null)).items().get(0);
        assertThat(((java.util.Map<?,?>)message.get("context")).get("customerName")).isEqualTo("验收客户");
        jdbc.execute("UPDATE fr_customer SET owner_user_id=90 WHERE id=100");
        var denied=c.list(p,query("ALL","",30,null)).items().get(0);
        assertThat(denied.get("title")).isEqualTo("业务通知权限已变更");
        assertThat((java.util.Map<?,?>)denied.get("context")).isEmpty();
        assertThat(denied.get("actionPath")).isEqualTo("");
    }
    @Test void simulationIsAlwaysInformationalEvenIfCommissionHasARealActionableTodo(){
        jdbc.execute("INSERT INTO fr_commission VALUES(200,1,2,100,'验收客户','DESIGNING'); INSERT INTO fr_commission_simulated_order VALUES(200,1,'PAID')");
        tx.executeWithoutResult(t->service.onCommand(event(11,"commission",200,"commission-simulate-pay")));
        var task=new cn.iocoder.yudao.module.platform.workflow.TodoItem("fr-design","commission:200","登记设计完成","DESIGNING",null,true,null,"/pages/workflow/commission/index?view=complete&commissionId=200",java.time.OffsetDateTime.now());
        var row=center(java.util.List.of(task)).list(post(20,2,"hq-design-admin"),query("ALL","",30,null)).items().get(0);
        assertThat(row).containsEntry("actionable",false).containsEntry("stateLabel","模拟 · 仅知会");
    }
    @Test void employeesOwnRoutineCustomerActionsDoNotCreateNoise(){
        tx.executeWithoutResult(t->service.onCommand(new FranchiseCommandRecorded(event(12,"customer",100,"customer-follow").event().setActorUserId(10L))));
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_message_delivery_checkpoint",Long.class)).isEqualTo(1);
    }

    @Test void broadProviderResultsCannotExposeAnotherSalespersonsCustomer(){
        jdbc.execute("INSERT INTO fr_customer VALUES(101,1,77,'另一业务员客户')");
        var task=new cn.iocoder.yudao.module.platform.workflow.TodoItem("fr-sale","fr-sale:contract-sign:5","合同待签","待签订",100L,true,null,"/pages/workflow/index?view=contract&id=101",null);
        var c=center(java.util.List.of(task));assertThat(c.list(post(10,1,"sales"),query("PENDING","",30,null)).pending()).isZero();
        assertThatThrownBy(()->c.open(post(10,1,"sales"),"todo:fr-sale:contract-sign:5")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test void storedRouteSeparatorsStayLiteralAndScalarCommandPayloadsAreIgnored(){
        assertThat(cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.json("{\"actionPath\":\"/pages/workflow/index?view=contract&id=123\"}").get("actionPath")).isEqualTo("/pages/workflow/index?view=contract&id=123");
        assertThat(cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.json("123")).isEmpty();
    }

    @Test void returnedEngineeringDeclarationUsesTheEngineersProjectInsteadOfSalesContract(){
        jdbc.execute("CREATE TABLE fr_sale_collection_declaration(id bigint,company_id bigint,stage_id bigint,applicant_user_id bigint,return_reason text); CREATE TABLE fr_sale_payment_stage(id bigint,contract_id bigint,customer_id bigint,stage text); CREATE TABLE fr_eng_project(id bigint,company_id bigint,sale_contract_id bigint,manager_user_id bigint,name text); INSERT INTO fr_sale_collection_declaration VALUES(55,1,56,30,'补充凭据'); INSERT INTO fr_sale_payment_stage VALUES(56,57,100,'BASE_DONE'); INSERT INTO fr_eng_project VALUES(58,1,57,30,'测试工程')");
        var todo=new cn.iocoder.yudao.module.platform.workflow.TodoItem("fr-sale-finance","fr-sale-finance:returned:55","资料待补充","待补充",100L,true,null,"/pages/workflow/index?view=contract&id=100",null);
        var c=center(java.util.List.of(todo));var rows=c.list(post(30,1,"engineer"),query("PENDING","",30,null)).items();
        assertThat(rows).hasSize(1);assertThat(rows.get(0).get("actionPath")).isEqualTo("/pages/build/record/index?view=collection&id=58");
        assertThat(c.list(post(31,1,"engineer"),query("PENDING","",30,null)).items()).isEmpty();
    }
    @Test void reassignedPerformanceTaskDoesNotExposeItsSnapshotToFormerAssignee(){
        jdbc.execute("CREATE TABLE fr_perf_task(id bigint,company_id bigint,assignee_user_id bigint,title text); INSERT INTO fr_perf_task VALUES(60,1,10,'任务A')");
        tx.executeWithoutResult(t->service.onCommand(event(61,"perf-task",60,"perf-task-create")));
        jdbc.execute("UPDATE fr_perf_task SET assignee_user_id=90 WHERE id=60");
        var row=center(java.util.List.of()).list(post(10,1,"sales"),query("ALL","",30,null)).items().get(0);
        assertThat(row.get("title")).isEqualTo("业务通知权限已变更");
    }

    void receivedHandoverFixture(){
        jdbc.execute("CREATE TABLE fr_eng_project(id bigint,company_id bigint,source_commission_id bigint,customer_id bigint,customer_name text,manager_user_id bigint,name text); INSERT INTO fr_eng_project VALUES(900,1,200,100,'验收客户',30,'验收工程'),(901,2,200,100,'其他组织客户',30,'其他组织工程'); INSERT INTO fr_commission VALUES(200,1,2,100,'验收客户','ACCEPTED'); INSERT INTO fr_commission_handover VALUES(200,30,1); UPDATE pf_staff_post SET status='ACTIVE' WHERE user_id=30");
        tx.executeWithoutResult(t->service.onCommand(event(70,"commission",200,"packet-receive")));
    }
    @Test void completedHandoverUsesTheScopedProjectForNewAndPreviouslyDeliveredNotices(){
        receivedHandoverFixture();
        String target="/pages/build/record/index?view=overview&id=900";
        assertThat(jdbc.queryForObject("SELECT business_context->>'actionPath' FROM pf_message WHERE user_id=10",String.class)).isEqualTo(target);
        // Simulate a previously delivered notice, including an obsolete foreign project reference.
        jdbc.update("UPDATE pf_message SET business_context=business_context||?::jsonb WHERE user_id=30","{\"actionPath\":\"/pages/workflow/commission/index?view=progress&commissionId=200\",\"actionLabel\":\"查看委托\",\"projectId\":\"901\"}");
        var task=new cn.iocoder.yudao.module.platform.workflow.TodoItem("fr-design","commission:200","新版本待接收","HANDOVER_SUBMITTED",null,true,null,"/pages/workflow/commission/index?view=receive&commissionId=200",java.time.OffsetDateTime.now());
        var c=center(java.util.List.of(task));var p=post(30,1,"engineer");
        var row=c.list(p,query("ALL","",30,null)).items().get(0);
        assertThat(row).containsEntry("actionPath",target).containsEntry("actionLabel","查看工程项目").containsEntry("actionable",false);
        assertThat(c.open(p,String.valueOf(row.get("id")))).containsEntry("actionPath",target).containsEntry("actionLabel","查看工程项目");
        jdbc.update("UPDATE pf_message SET business_context='{}'::jsonb,dedupe_key=NULL WHERE user_id=30");
        assertThat(c.list(p,query("ALL","",30,null)).items().get(0)).containsEntry("actionPath",target);
    }
    @Test void completedHandoverRevalidatesProjectAccessAndNeverFallsBackToTheCommission(){
        receivedHandoverFixture();
        var c=center(java.util.List.of());
        assertThat(c.list(post(30,1,"engineer"),query("ALL","",30,null)).items().get(0)).containsEntry("actionLabel","查看工程项目");
        jdbc.update("UPDATE fr_eng_project SET manager_user_id=31 WHERE id=900");
        var denied=c.list(post(30,1,"engineer"),query("ALL","",30,null)).items().get(0);
        assertThat(denied).containsEntry("title","业务通知权限已变更").containsEntry("actionPath","");
        assertThat((java.util.Map<?,?>)denied.get("context")).isEmpty();
        jdbc.update("DELETE FROM fr_eng_project WHERE id=900");
        var missing=c.list(post(10,1,"sales"),query("ALL","",30,null)).items().get(0);
        assertThat(missing).containsEntry("stateLabel","项目暂不可用").containsEntry("actionPath","").containsEntry("actionLabel","");
    }
    @Test void completedHandoverKeepsTheNoticeWithoutAnInaccessibleProjectButton(){
        receivedHandoverFixture();
        var c=center(java.util.List.of());var p=post(10,1,"sales");
        var row=c.list(p,query("ALL","",30,null)).items().get(0);
        assertThat(row).containsEntry("title","工程已接收业务资料").containsEntry("actionPath","").containsEntry("actionLabel","");
        assertThat(cn.iocoder.yudao.module.platform.workflow.message.MessageCenterService.json(row.get("context"))).containsEntry("notice","当前岗位无工程项目查看权限，请切换有权岗位查看");
        assertThat(c.open(p,String.valueOf(row.get("id")))).containsEntry("actionPath","");
    }

}
