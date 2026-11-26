package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Import({EngProjectService.class,EngPlanService.class,EngProgressService.class,EngCameraService.class,EngProgressReminder.class,EngRectificationService.class,
    cn.iocoder.yudao.module.franchise.engineering.todo.EngTodoProvider.class,
    FranchiseCommands.class,CommandLogImpl.class,EngMaterialAndPlanGuardIntegrationTest.Config.class,
    cn.iocoder.yudao.module.franchise.finance.FinPaymentService.class,cn.iocoder.yudao.module.franchise.finance.FinRefundService.class})
class EngProgressCameraIntegrationTest extends FranchisePgIntegrationTest {
    @Resource EngProjectService projects;
    @Resource EngPlanService plans;
    @Resource EngProgressService progress;
    @Resource EngRectificationService issues;
    @Resource FranchiseCommands commands;
    @Resource EngCameraService cameras;
    @Resource EngProgressReminder reminders;
    @Resource cn.iocoder.yudao.module.franchise.engineering.todo.EngTodoProvider todos;
    @Resource JdbcTemplate jdbc;
    PostSnapshot engineer;
    long projectId;
    LocalDate today=LocalDate.now();
    String key(){return UUID.randomUUID().toString();}
    @BeforeEach void setup(){
        var store=fixture.store();engineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());currentPost.use(engineer);
        EngMaterialAndPlanGuardIntegrationTest.Config.CONTRACT_STAGES=null;
        EngMaterialAndPlanGuardIntegrationTest.Config.STAGE_COLLECTED_MAP.clear();
        projectId=((Number)projects.create(engineer,new EngProjectService.CreateCommand("周建军庭院","周建军","武汉","13800138000",new BigDecimal("30"),1,null,true),key()).get("id")).longValue();
        jdbc.execute("CREATE TABLE IF NOT EXISTS eng_test_asset (id BIGINT PRIMARY KEY,asset_type VARCHAR(64),accepted BOOLEAN,org_id BIGINT)");
    }
    private EngRectificationService.Change issue(String action, Long id, Long owner, LocalDate due, String note) {
        var body = new LinkedHashMap<String,Object>();
        body.put("action",action); body.put("issueId",id); body.put("note",note);
        if ("create".equals(action)) body.put("title","二层钢筋整改");
        if (owner!=null) body.put("ownerUserId",owner.toString());
        if (due!=null) body.put("dueDate",due.toString());
        return cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(body),EngRectificationService.Change.class);
    }

    private Map<String,Object> issueDetail(PostSnapshot post,long id) {
        return issues.detail(post,projectId,id);
    }

    @Test void rectificationAssignmentAndHistoryDriveTheRealEngineeringTodo() {
        var assignee=fixture.post(engineer.orgId(),"engineer",engineer.teamId());
        jdbc.update("UPDATE fr_eng_project SET assigned_engineer_user_id=? WHERE id=?",assignee.userId(),projectId);
        LocalDate due=today.minusDays(1);
        var command=issue("create",null,assignee.userId(),due,"钢筋间距需复核");
        String key=key();
        long id=issues.change(engineer,projectId,command,key);
        assertThat(issues.change(engineer,projectId,command,key)).isEqualTo(id);
        assertThat(issues.list(engineer,projectId)).singleElement().satisfies(row->assertThat(row)
                .containsEntry("ownerUserId",Long.toString(assignee.userId())).containsEntry("dueDate",due.toString()));
        assertThat(todos.list(assignee)).anyMatch(t->t.key().equals("fr-eng:rectification:"+id)
                && t.status().contains("逾期") && t.appPath().endsWith("issue="+id));
        assertThat(todos.list(engineer)).noneMatch(t->t.key().equals("fr-eng:rectification:"+id));
        assertThatThrownBy(()->issues.change(engineer,projectId,issue("create",null,engineer.userId(),due,"钢筋间距需复核"),key))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        issues.change(assignee,projectId,issue("submit",id,null,null,"第一次提交复验"),key());
        issues.change(engineer,projectId,issue("return",id,null,null,"尺量未通过"),key());
        issues.change(engineer,projectId,issue("assign",id,engineer.userId(),today.plusDays(2),"变更经办人与期限"),key());
        assertThat(todos.list(engineer)).anyMatch(t->t.key().equals("fr-eng:rectification:"+id));
        assertThat(todos.list(assignee)).noneMatch(t->t.key().equals("fr-eng:rectification:"+id));
        issues.change(engineer,projectId,issue("submit",id,null,null,"第二次复验"),key());
        issues.change(assignee,projectId,issue("pass",id,null,null,"现场复验通过"),key());
        var detail=issueDetail(engineer,id);
        assertThat(detail).containsEntry("state","CLOSED").containsEntry("ownerUserId",Long.toString(engineer.userId()));
        var history=(List<Map<String,Object>>)detail.get("history");
        assertThat(history).extracting(e->e.get("action")).containsExactly("create","submit","return","assign","submit","pass");
        assertThat(history.get(0)).containsEntry("ownerUserId",Long.toString(assignee.userId())).containsEntry("dueDate",due.toString());
        assertThat(history.get(2)).containsEntry("note","尺量未通过").containsEntry("actorUserId",Long.toString(engineer.userId()));
        assertThat(history.get(5).get("occurredAt")).isNotNull();
        assertThat(todos.list(engineer)).noneMatch(t->t.key().equals("fr-eng:rectification:"+id));
        assertThatThrownBy(()->issues.change(engineer,projectId,issue("assign",id,engineer.userId(),today,"已销项后改派"),key()))
                .hasMessageContaining("状态");
    }

    @Test void rectificationDoesNotInventOldAssignmentsOrAcceptAnotherProjectOrCompany() {
        long id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_rectification(id,company_id,project_id,title,state,created_by) VALUES(?,?,?,'旧单','OPEN',?)",id,engineer.orgId(),projectId,engineer.userId());
        jdbc.update("INSERT INTO fr_eng_rectification_event(id,issue_id,actor_id,action,note) VALUES(?,?,?,'create','纸质单转录')",id+1,id,engineer.userId());
        var old=issues.list(engineer,projectId).get(0);
        assertThat(old).containsEntry("ownerUserId",null).containsEntry("dueDate",null);
        record OldChange(String action, Long issueId, Long nodeId, String title, String note) {}
        var oldInput=new LinkedHashMap<String,Object>();
        oldInput.put("project",projectId);
        oldInput.put("change",new OldChange("create",null,null,"二层钢筋整改","纸质单转录"));
        String oldKey=key();
        commands.record(engineer,"site-rectification",id,1,"rectification-create",oldInput,oldKey);
        assertThat(issues.change(engineer,projectId,issue("create",null,null,null,"纸质单转录"),oldKey)).isEqualTo(id);
        assertThatThrownBy(()->issues.change(engineer,projectId,issue("create",null,engineer.userId(),today,"纸质单转录"),oldKey))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(todos.list(engineer)).anyMatch(t->t.key().equals("fr-eng:rectification:"+id) && t.status().contains("待分派"));
        var other=fixture.post(engineer.orgId(),"engineer",engineer.teamId());
        assertThatThrownBy(()->issues.change(engineer,projectId,issue("create",null,null,null,"缺少分派"),key())).hasMessageContaining("负责人");
        assertThatThrownBy(()->issues.change(engineer,projectId,issue("assign",id,other.userId(),today,"其他项目人员"),key())).hasMessageContaining("本项目");
        assertThatThrownBy(()->issueDetail(other,id)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        var foreign=fixture.post(fixture.store().orgId(),"engineer",null);
        assertThatThrownBy(()->issueDetail(foreign,id)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        long anotherProject=((Number)projects.create(engineer,new EngProjectService.CreateCommand("第二项目","钱工","武汉",
                "13800000065",new BigDecimal("20"),1,null,true),key()).get("id")).longValue();
        assertThatThrownBy(()->issues.detail(engineer,anotherProject,id)).hasMessageContaining("原整改单");
        assertThatThrownBy(()->issues.change(engineer,anotherProject,issue("assign",id,engineer.userId(),today,"跨项目"),key()))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        var finance=new PostSnapshot(engineer.postId(),engineer.userId(),engineer.orgId(),engineer.orgType(),"finance",null,false,Set.of());
        assertThat(todos.list(finance)).noneMatch(t->t.key().equals("fr-eng:rectification:"+id));
        assertThat(todos.list(foreign)).noneMatch(t->t.key().equals("fr-eng:rectification:"+id));
        issues.change(engineer,projectId,issue("assign",id,engineer.userId(),today,"核对纸单后补录责任人和期限"),key());
        var history=(List<Map<String,Object>>)issueDetail(engineer,id).get("history");
        assertThat(history.get(0)).containsEntry("ownerUserId",null).containsEntry("dueDate",null);
        assertThat(history.get(1)).containsEntry("ownerUserId",Long.toString(engineer.userId())).containsEntry("dueDate",today.toString());
    }

    @Test void suspendedPostCannotReceiveNewOrPersonalWorkWhileOwnershipRemainsOnTheProject() {
        var assigned=fixture.post(engineer.orgId(),"engineer",engineer.teamId()); long target=assigned.userId();
        jdbc.update("UPDATE fr_eng_project SET assigned_engineer_user_id=? WHERE id=?",target,projectId);
        String originalKey=key();
        var original=issue("create",null,target,today,"指派时岗位有效");
        long id=issues.change(engineer,projectId,original,originalKey);
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=? WHERE id=?",engineer.userId(),assigned.postId());
        assertThat(issues.handlers(engineer,projectId)).extracting(row->row.get("id")).containsExactly(Long.toString(engineer.userId()));
        assertThat(issues.change(engineer,projectId,original,originalKey)).isEqualTo(id);
        assertThat(todos.list(engineer)).anyMatch(t->t.key().equals("fr-eng:rectification:"+id)&&t.status().contains("重新分派"));
        assertThat(todos.list(assigned)).noneMatch(t->t.key().equals("fr-eng:rectification:"+id));
        assertThatThrownBy(()->issues.change(engineer,projectId,issue("assign",id,target,today,"岗位已经撤销"),key()))
                .hasMessageContaining("本项目");
        assertThat(issues.detail(engineer,projectId,id)).containsEntry("ownerUserId",Long.toString(target));
    }

    @Test void manualProjectLinksSignedIdentityOnceAndHandoverReusesTheSameProject() {
        long customer = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long contract = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("""
                INSERT INTO fr_customer(id,company_id,owner_user_id,name,phone,source,status)
                VALUES(?,?,?,'郭宁','13900008158','到店','FOLLOWING')
                """, customer, engineer.orgId(), engineer.userId());
        for (String phone : List.of("", "13900008157", "13900008158")) {
            var unsigned = new EngProjectService.CreateCommand("郭宁庭院", "郭宁", "武汉", phone, null, 2, null);
            assertThatThrownBy(() -> projects.create(engineer, unsigned, key())).hasMessageContaining("独立建档");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_project WHERE company_id=? AND name='郭宁庭院'",
                Long.class,engineer.orgId())).isZero();
        jdbc.update("""
                INSERT INTO fr_sale_contract(id,company_id,customer_id,total_cents,first_cents,status,author_user_id)
                VALUES(?,?,?,100000,30000,'SIGNED',?)
                """, contract, engineer.orgId(), customer, engineer.userId());
        var cmd = new EngProjectService.CreateCommand("郭宁庭院", "郭宁", "武汉", "13900008158",
                new BigDecimal("120"), 2, null);
        String createKey = key();
        var project = projects.create(engineer, cmd, createKey);
        long id = ((Number)project.get("id")).longValue();
        assertThat(projects.create(engineer, cmd, createKey).get("id")).isEqualTo(id);
        assertThat(project).containsEntry("customerId",customer).containsEntry("saleContractId",contract);
        var independent = projects.create(engineer, new EngProjectService.CreateCommand(
                "郭宁独立工程","郭宁","武汉","13900008158",null,2,null,true),key());
        assertThat(independent).containsEntry("saleContractId",null).containsEntry("customerId",null);
        var duplicate = new EngProjectService.CreateCommand("另一工程","郭宁","武汉","13900008158",null,1,null);
        assertThatThrownBy(() -> projects.create(engineer, duplicate, key())).hasMessageContaining("已关联工程");
        long commission = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long reused = projects.receiveFromHandover(engineer, commission, customer, "郭宁", "武汉", "13900008158",
                new BigDecimal("120"), 2, engineer.userId(), "V1", "", List.of(), contract, "V1");
        assertThat(reused).isEqualTo(id);
    }

    List<EngPlanService.NodeInput> input(LocalDate first){
        var rows=jdbc.queryForList("SELECT node_index,name FROM fr_eng_plan_node WHERE project_id=? ORDER BY node_index",projectId);
        var result=new ArrayList<EngPlanService.NodeInput>();
        for(int i=0;i<rows.size();i++)result.add(new EngPlanService.NodeInput(((Number)rows.get(i).get("node_index")).intValue(),String.valueOf(rows.get(i).get("name")),first.plusDays(Math.max(0,i-1)),first.plusDays(Math.max(0,i-1))));
        return result;
    }
    long node(int index){return jdbc.queryForObject("SELECT id FROM fr_eng_plan_node WHERE project_id=? AND node_index=?",Long.class,projectId,index);}
    long asset(){long id=cn.hutool.core.util.IdUtil.getSnowflakeNextId();jdbc.update("INSERT INTO eng_test_asset VALUES(?,'ENG_SITE_PHOTO',TRUE,?)",id,engineer.orgId());return id;}
    @Test void formalStartHonorsCollectionGateAndPersistsEvidence(){
        plans.savePlan(engineer,projectId,input(today.minusDays(3)),key());
        jdbc.update("UPDATE fr_eng_project SET prepared_at=now(),customer_id=?,sale_contract_id=900 WHERE id=?",projectId,projectId);
        jdbc.update("INSERT INTO fr_eng_collection_term(id,company_id,project_id,stage,amount_cents) VALUES(?,?,?,'SIGN_AFTER',10000)",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),engineer.orgId(),projectId);
        var cmd=new EngPlanService.NodeRecordCommand(today,"场地交接完成，正式进场",asset(),false);
        assertThatThrownBy(()->plans.start(engineer,projectId,cmd,key())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM fr_eng_plan_node WHERE id=?",String.class,node(1))).isEqualTo("PENDING");
        EngMaterialAndPlanGuardIntegrationTest.Config.STAGE_COLLECTED_MAP.put("SIGN_AFTER",10000L);
        plans.start(engineer,projectId,cmd,key());
        assertThat(jdbc.queryForObject("SELECT completed_at FROM fr_eng_plan_node WHERE id=?",LocalDate.class,node(1))).isEqualTo(today);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_site_record WHERE node_id=? AND completion=TRUE",Integer.class,node(1))).isEqualTo(1);
        assertThatThrownBy(()->plans.reopenNode(engineer,projectId,node(1),"调整",key())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
    @Test void delayPersistsOwnerHistoryAndBaselineAcrossPlanChanges(){
        LocalDate businessDate = EngProgress.today();
        plans.savePlan(engineer,projectId,input(businessDate.minusDays(3)),key());
        var cmd=new EngProgressService.DelayCommand("连续降雨",engineer.userId(),businessDate.plusDays(2),"排水后增加班组");
        String key=key();progress.save(engineer,projectId,node(2),cmd,key);progress.save(engineer,projectId,node(2),cmd,key);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(delay_history) FROM fr_eng_plan_node WHERE id=?",Integer.class,node(2))).isEqualTo(1);
        assertThatThrownBy(()->progress.save(engineer,projectId,node(2),new EngProgressService.DelayCommand("降雨",99999L,businessDate,"排水"),key())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        plans.savePlan(engineer,projectId,input(businessDate.plusDays(1)),key());
        assertThat(jdbc.queryForObject("SELECT baseline_end_date FROM fr_eng_plan_node WHERE id=?",LocalDate.class,node(2))).isEqualTo(businessDate.minusDays(3));
        assertThat(todos.list(engineer)).anyMatch(t->t.key().equals("fr-eng:delay:"+node(2))&&t.title().contains("延期3天"));
        jdbc.update("UPDATE fr_eng_plan_node SET status='DONE',completed_at=? WHERE id=?",businessDate,node(2));
        assertThat(todos.list(engineer)).noneMatch(t->t.key().equals("fr-eng:delay:"+node(2)));
        assertThat(jdbc.queryForObject("SELECT delay_reason FROM fr_eng_plan_node WHERE id=?",String.class,node(2))).isEqualTo("连续降雨");
    }
    @Test void revokedPersonnelCannotReceiveDelayWorkAndRecordedResponsibilitySurvives() {
        var assigned=fixture.post(engineer.orgId(),"engineer",engineer.teamId());
        var foreign=fixture.store();
        fixture.postFor(assigned.userId(),foreign.orgId(),"engineer",foreign.engineeringTeam());
        var finance=fixture.postFor(assigned.userId(),engineer.orgId(),"finance",null);
        jdbc.update("UPDATE fr_eng_project SET assigned_engineer_user_id=? WHERE id=?",assigned.userId(),projectId);
        var date=EngProgress.today();
        plans.savePlan(engineer,projectId,input(date.minusDays(3)),key());
        long id=node(2);
        var original=new EngProgressService.DelayCommand("现场复核",assigned.userId(),date.plusDays(2),"排水");
        var originalKey=key();
        progress.save(engineer,projectId,id,original,originalKey);
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=? WHERE id=?",engineer.userId(),assigned.postId());
        assertThat(progress.handlers(engineer,projectId)).extracting(r->r.get("id")).containsExactly(Long.toString(engineer.userId()));
        assertThatThrownBy(()->progress.save(engineer,projectId,id,original,key())).hasMessageContaining("有效工程任职");
        progress.save(engineer,projectId,id,original,originalKey);
        assertThat(todos.list(assigned)).noneMatch(item->item.key().equals("fr-eng:delay:"+id));
        assertThat(todos.list(finance)).noneMatch(item->item.key().equals("fr-eng:delay:"+id));
        var activeOwner=new EngProgressService.DelayCommand("无此岗位权限",engineer.userId(),date.plusDays(2),"排水");
        assertThatThrownBy(()->progress.save(assigned,projectId,id,activeOwner,key())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->progress.save(finance,projectId,id,activeOwner,key())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(todos.list(engineer)).anyMatch(item->item.key().equals("fr-eng:delay:"+id));
        assertThat(jdbc.queryForObject("SELECT delay_history->0->>'ownerUserId' FROM fr_eng_plan_node WHERE id=?",String.class,id)).isEqualTo(Long.toString(assigned.userId()));
        progress.save(engineer,projectId,id,new EngProgressService.DelayCommand("继续复核",engineer.userId(),date.plusDays(3),"重新排水"),key());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(delay_history) FROM fr_eng_plan_node WHERE id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void dailyMessagesAreDeduplicatedAndStopAfterCompletion(){
        plans.savePlan(engineer,projectId,input(today.minusDays(3)),key());
        reminders.scanAt(today);reminders.scanAt(today);
        Integer count=jdbc.queryForObject("SELECT count(*) FROM pf_message WHERE sender_domain='fr-eng-progress' AND object_id=?",Integer.class,projectId);
        assertThat(count).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT dedupe_key) FROM pf_message WHERE sender_domain='fr-eng-progress' AND object_id=?",Integer.class,projectId)).isEqualTo(count);
        jdbc.update("UPDATE fr_eng_plan_node SET status='DONE',completed_at=? WHERE project_id=?",today,projectId);
        reminders.scanAt(today.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_message WHERE sender_domain='fr-eng-progress' AND object_id=?",Integer.class,projectId)).isEqualTo(count);
    }
    @Test void aProjectManagersNameDoesNotBecomeAnUnrecordedDelayAssignment() {
        var directory=cn.iocoder.yudao.module.franchise.test.FakeSystemDirectory.INSTANCE;
        long managerId=directory.createUser("原项目经理","13600000111",engineer.teamId());
        var manager=fixture.postFor(managerId,engineer.orgId(),"project-manager",engineer.teamId());
        long assignedId=directory.createUser("现场工程师","13600000222",engineer.teamId());
        fixture.postFor(assignedId,engineer.orgId(),"engineer",engineer.teamId());
        jdbc.update("UPDATE fr_eng_project SET manager_user_id=?,assigned_engineer_user_id=? WHERE id=?",managerId,assignedId,projectId);
        currentPost.use(manager);
        LocalDate date=EngProgress.today();
        plans.savePlan(manager,projectId,input(date.minusDays(3)),key());
        plans.savePlan(manager,projectId,input(date.plusDays(2)),key());
        long nodeId=node(2);
        var before=(List<Map<String,Object>>)projects.detail(manager,projectId).get("nodes");
        assertThat(before).anySatisfy(row->assertThat(row).containsEntry("id",nodeId)
                .containsEntry("delayOwnerUserId",null).containsEntry("delayOwnerName","").containsEntry("recoveryDate",null));
        progress.save(manager,projectId,nodeId,new EngProgressService.DelayCommand("雨水积压",assignedId,date.plusDays(3),"排水后复核"),key());
        var after=(List<Map<String,Object>>)projects.detail(manager,projectId).get("nodes");
        assertThat(after).anySatisfy(row->assertThat(row).containsEntry("id",nodeId).containsEntry("delayOwnerUserId",assignedId)
                .containsEntry("delayOwnerName","现场工程师").containsEntry("recoveryDate",date.plusDays(3)));
    }
    @Test void cameraBindingPlaybackAndIsolationKeepAddressesOutOfListsAndAudit(){
        String url="https://video.example.com/site/live.m3u8?token=secret";
        var cmd=new EngCameraService.CameraCommand(null,"东侧施工区","入口立杆",url,true);String key=key();
        var rows=cameras.save(engineer,projectId,cmd,key);cameras.save(engineer,projectId,cmd,key);
        assertThat(rows).hasSize(1);assertThat(rows.toString()).doesNotContain("secret").doesNotContain("stream_url");
        long camera=Long.parseLong(String.valueOf(rows.get(0).get("id")));
        assertThat(cameras.playback(engineer,projectId,camera).get("url")).isEqualTo(url);
        var stranger=fixture.post(engineer.orgId(),"engineer",engineer.teamId());
        assertThatThrownBy(()->cameras.playback(stranger,projectId,camera)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        var other=fixture.store();var cross=fixture.post(other.orgId(),"engineer",other.engineeringTeam());
        assertThatThrownBy(()->cameras.list(cross,projectId)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        cameras.save(engineer,projectId,new EngCameraService.CameraCommand(camera,"东侧施工区","入口立杆","",false),key());
        assertThatThrownBy(()->cameras.playback(engineer,projectId,camera)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT stream_url FROM fr_eng_camera WHERE id=?",String.class,camera)).isEqualTo(url);
    }
}
