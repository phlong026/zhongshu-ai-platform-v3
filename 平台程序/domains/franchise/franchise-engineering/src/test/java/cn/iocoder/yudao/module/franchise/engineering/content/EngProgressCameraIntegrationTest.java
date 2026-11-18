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

@Import({EngProjectService.class,EngPlanService.class,EngProgressService.class,EngCameraService.class,EngProgressReminder.class,
    cn.iocoder.yudao.module.franchise.engineering.todo.EngTodoProvider.class,
    FranchiseCommands.class,CommandLogImpl.class,EngMaterialAndPlanGuardIntegrationTest.Config.class,
    cn.iocoder.yudao.module.franchise.finance.FinPaymentService.class,cn.iocoder.yudao.module.franchise.finance.FinRefundService.class})
class EngProgressCameraIntegrationTest extends FranchisePgIntegrationTest {
    @Resource EngProjectService projects;
    @Resource EngPlanService plans;
    @Resource EngProgressService progress;
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
        plans.savePlan(engineer,projectId,input(today.minusDays(3)),key());
        var cmd=new EngProgressService.DelayCommand("连续降雨",engineer.userId(),today.plusDays(2),"排水后增加班组");
        String key=key();progress.save(engineer,projectId,node(2),cmd,key);progress.save(engineer,projectId,node(2),cmd,key);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(delay_history) FROM fr_eng_plan_node WHERE id=?",Integer.class,node(2))).isEqualTo(1);
        assertThatThrownBy(()->progress.save(engineer,projectId,node(2),new EngProgressService.DelayCommand("降雨",99999L,today,"排水"),key())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        plans.savePlan(engineer,projectId,input(today.plusDays(1)),key());
        assertThat(jdbc.queryForObject("SELECT baseline_end_date FROM fr_eng_plan_node WHERE id=?",LocalDate.class,node(2))).isEqualTo(today.minusDays(3));
        assertThat(todos.list(engineer)).anyMatch(t->t.key().equals("fr-eng:delay:"+node(2))&&t.title().contains("延期3天"));
        jdbc.update("UPDATE fr_eng_plan_node SET status='DONE',completed_at=? WHERE id=?",today,node(2));
        assertThat(todos.list(engineer)).noneMatch(t->t.key().equals("fr-eng:delay:"+node(2)));
        assertThat(jdbc.queryForObject("SELECT delay_reason FROM fr_eng_plan_node WHERE id=?",String.class,node(2))).isEqualTo("连续降雨");
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
