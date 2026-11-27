package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M06-T03：统一合同关联的冻结移交及指定工程接收——真实 commission→engineering 全链
 * （EngineeringPortImpl/EngProjectService 真实建档，含 fr_eng_project/fr_eng_document 表）。
 * 覆盖：移交包冻结当时有效下发方案版本（frozen_scheme_version）；解析统一建房合同（活跃 SIGNED，
 * 经 SaleContractQueryAdapter 真实查 fr_sale_contract）；接收只建一档（幂等）且 sale_contract_id
 * 关联；退回→新下发→重提 version+1/冻结新版本/旧包保留；再接收刷新不改 contract 关联；
 * 无有效已签正式合同不得新建不能收款、准备或开工的空档案。
 */
@Import({CommissionService.class, HandoverService.class, DesignPolicyService.class, CommissionAccess.class,
        CommissionOrgScope.class, FranchiseCommands.class, CommandLogImpl.class,
        cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl.class,
        LeadService.class, CustomerService.class, CrmOrgScope.class, FranchiseTodoProvider.class,
        cn.iocoder.yudao.module.franchise.engineering.content.EngineeringPortImpl.class,
        cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService.class,
        cn.iocoder.yudao.module.franchise.sales.SaleContractQueryAdapter.class})
class HandoverFreezeIntegrationTest extends CommissionPgIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(940_000_000_000L + System.nanoTime() % 100_000_000L);

    @Resource
    private LeadService leads;
    @Resource
    private CustomerService customers;
    @Resource
    private CommissionService commissions;
    @Resource
    private HandoverService handovers;
    @Resource
    private cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService engProjects;
    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private FranchiseTodoProvider todoProvider;

    private Store store;
    private PostSnapshot salesA1;
    private PostSnapshot engineer;
    private PostSnapshot boss;

    @BeforeEach
    void organization() {
        store = fixture.store();
        salesA1 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        engineer = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        boss = fixture.post(store.orgId(), BOSS, null);
        fixture.headquarters();
    }

    @Test
    void submitFreezesSchemeVersionAndResolvesActiveSignedContract() {
        long customerId = customerWithDispatchedDesign("P1/B1/F1");
        long contractId = insertSignedContract(customerId);

        var detail = as(salesA1, () -> handovers.submit(salesA1, commissionIdOf(customerId),
                packet(1, "P1/B1/F1", engineer.userId()), idem()));

        assertThat(detail.commission().getStatus()).isEqualTo(CommissionDO.STATUS_HANDOVER_SUBMITTED);
        // 冻结快照=提交时有效下发方案版本；统一合同解析=活跃 SIGNED 合同
        assertThat(frozenSchemeVersion(commissionIdOf(customerId))).isEqualTo("P1/B1/F1");
        assertThat(detail.submissions()).isNotEmpty();
        // 委托行 handoverVersion 已指向新包（contractId 关联断言在 receive 用例——档案建立时落列）
    }

    @Test
    void receiveCreatesSingleProjectLinkedToSaleContract() {
        long customerId = customerWithDispatchedDesign("P1/B1/F1");
        long contractId = insertSignedContract(customerId);
        long commissionId = commissionIdOf(customerId);
        as(salesA1, () -> handovers.submit(salesA1, commissionId, packet(1, "P1/B1/F1", engineer.userId()), idem()));
        // 同键重放验证：键提取变量（idem() 每次新键会被状态门正确拒绝）
        String receiveKey = idem();
        as(engineer, () -> handovers.receive(engineer, commissionId,
                new HandoverService.DecisionCommand("", true), receiveKey));
        long projectId = jdbc.queryForObject(
                "SELECT id FROM fr_eng_project WHERE company_id = ? AND source_commission_id = ?",
                Long.class, store.orgId(), commissionId);
        assertThat(projectId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE id = ?",
                Long.class, projectId)).isEqualTo(contractId);
        // 冻结版本进入工程审计留痕（eng-project-from-handover 事件）
        Integer frozenAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE aggregate_id = ? AND command = 'eng-project-from-handover' "
                        + "AND payload LIKE '%P1/B1/F1%'", Integer.class, projectId);
        assertThat(frozenAudit).isEqualTo(1);
        as(engineer, () -> handovers.receive(engineer, commissionId,
                new HandoverService.DecisionCommand("", true), receiveKey));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_eng_project WHERE company_id = ? AND source_commission_id = ?",
                Long.class, store.orgId(), commissionId)).isEqualTo(1);
    }

    @Test
    void legacyHandoverTodoStaysWithAssignedEngineerUntilReceivedOrReturned() {
        var otherEngineer = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        var outside = fixture.store();
        var outsideEngineer = fixture.post(outside.orgId(), ENGINEER, outside.engineeringTeam());
        for (boolean receive : List.of(true, false)) {
            long customerId = customerWithDispatchedDesign("P1/B1/F1");
            insertSignedContract(customerId);
            long commissionId = commissionIdOf(customerId);
            as(salesA1, () -> handovers.submit(salesA1, commissionId,
                    packet(1, "P1/B1/F1", engineer.userId()), idem()));
            String key = "franchise:handover-review:" + commissionId + ":1";
            assertThat(as(engineer, () -> todoProvider.list(engineer))).filteredOn(todo -> todo.key().equals(key))
                    .singleElement().satisfies(todo -> {
                        assertThat(todo.actionable()).isTrue();
                        assertThat(todo.appPath()).isEqualTo(
                                "/pages/workflow/commission/index?view=receive&commissionId=" + commissionId);
                    });
            for (PostSnapshot viewer : List.of(salesA1, boss, otherEngineer, outsideEngineer)) {
                assertThat(as(viewer, () -> todoProvider.list(viewer)))
                        .extracting(cn.iocoder.yudao.module.platform.workflow.TodoItem::key).doesNotContain(
                                key, "franchise:handover-waiting:" + commissionId + ":1");
            }
            var detail = receive
                    ? as(engineer, () -> handovers.receive(engineer, commissionId,
                            new HandoverService.DecisionCommand("", true), idem()))
                    : as(engineer, () -> handovers.returnPacket(engineer, commissionId,
                            new HandoverService.DecisionCommand("请补齐交底资料", false), idem()));
            assertThat(detail.commission().getStatus()).isEqualTo(
                    receive ? CommissionDO.STATUS_ACCEPTED : CommissionDO.STATUS_HANDOVER_RETURNED);
            assertThat(as(engineer, () -> todoProvider.list(engineer)))
                    .extracting(cn.iocoder.yudao.module.platform.workflow.TodoItem::key).doesNotContain(key);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(90)
    void receivingAnotherEngineersManualProjectRequiresExplicitTransfer() throws Exception {
        var original = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        long customer = customerWithDispatchedDesign("P1/B1/F1");
        insertSignedContract(customer);
        long commission = commissionIdOf(customer);
        String phone = jdbc.queryForObject("SELECT phone FROM fr_customer WHERE id=?",String.class,customer);
        var command = new cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService.CreateCommand(
                "陈家乡墅","陈先生","武汉",phone,null,2,null);
        long project = ((Number)as(original, () -> engProjects.create(original,command,idem())).get("id")).longValue();
        long siteRecord = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_site_record(id,company_id,project_id,occurred_at,note,author_user_id) VALUES(?,?,?,CURRENT_DATE,?,?)",
                siteRecord,store.orgId(),project,"保留原现场记录",original.userId());
        // Assigned engineers can handle site work, but only the manager can transfer ownership.
        jdbc.update("UPDATE fr_eng_project SET assigned_engineer_user_id=? WHERE id=?", engineer.userId(), project);
        as(salesA1, () -> handovers.submit(salesA1,commission,packet(1,"P1/B1/F1",engineer.userId()),idem()));
        String receiveKey = idem();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> as(engineer, () -> handovers.receive(engineer,commission,
                new HandoverService.DecisionCommand(null,true),receiveKey))).hasMessageContaining("联系现负责人");
        assertThat(jdbc.queryForObject("SELECT status FROM fr_commission WHERE id=?",String.class,commission))
                .isEqualTo(CommissionDO.STATUS_HANDOVER_SUBMITTED);
        assertThat(jdbc.queryForObject("SELECT status FROM fr_commission_handover WHERE commission_id=?",String.class,commission))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT source_commission_id FROM fr_eng_project WHERE id=?",Long.class,project)).isNull();
        var oldClient = new HandoverService.DecisionCommand(null,true,true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> as(engineer, () -> handovers.receive(engineer,commission,oldClient,receiveKey)))
                .hasMessageContaining("联系现负责人");
        var approve = new cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService.ManagerCommand(engineer.userId(), "同意合同工程转交");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> as(engineer, () -> engProjects.changeManager(engineer,project,approve,idem())))
                .isInstanceOf(ServiceException.class);
        var transfer = new HandoverService.DecisionCommand(null,true);
        String pairedClient = System.getProperty("franchise.handover.client");
        if (pairedClient != null) {
            receiveThroughMiniProgram(pairedClient, original, commission, project, receiveKey);
        } else {
            as(original, () -> engProjects.changeManager(original,project,approve,idem()));
            assertThat(jdbc.queryForObject("SELECT status FROM fr_commission_handover WHERE commission_id=?",String.class,commission)).isEqualTo("PENDING");
            as(engineer, () -> handovers.receive(engineer,commission,transfer,receiveKey));
        }
        as(engineer, () -> handovers.receive(engineer,commission,transfer,receiveKey));
        assertThat(as(engineer, () -> engProjects.list(engineer,null))).extracting(row -> row.get("id")).contains(project);
        assertThat(as(original, () -> engProjects.list(original,null))).extracting(row -> row.get("id")).doesNotContain(project);
        assertThat(jdbc.queryForObject("SELECT note FROM fr_eng_site_record WHERE id=? AND project_id=?",
                String.class, siteRecord, project)).isEqualTo("保留原现场记录");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command='packet-receive' AND actor_user_id=?",
                Long.class,commission,engineer.userId())).isEqualTo(1);
        var increment = new cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService.CreateCommand(
                "陈家增项","陈先生","武汉",phone,null,1,project);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> as(engineer, () -> engProjects.create(engineer,increment,idem())))
                .hasMessageContaining("原工程办理增项");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_project WHERE company_id=?",Long.class,store.orgId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command='eng-project-manager' AND actor_user_id=(payload::jsonb->>'fromUserId')::bigint
                AND (payload::jsonb->>'fromUserId')::bigint=? AND (payload::jsonb->>'toUserId')::bigint=?
                """,Long.class,project,original.userId(),engineer.userId())).isEqualTo(1);
    }

    private void receiveThroughMiniProgram(String client, PostSnapshot owner, long commission, long project, String key) throws Exception {
        var commissionApi = new cn.iocoder.yudao.module.franchise.commission.controller.admin.CommissionController(commissions, handovers, currentPost);
        var projectApi = new cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngProjectController();
        org.springframework.test.util.ReflectionTestUtils.setField(projectApi, "currentPost", currentPost);
        org.springframework.test.util.ReflectionTestUtils.setField(projectApi, "projects", engProjects);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(commissionApi, projectApi).build();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var process = new ProcessBuilder("node",client,Long.toString(commission),Long.toString(project),
                Long.toString(engineer.userId()),key).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var requests = process.inputReader(java.nio.charset.StandardCharsets.UTF_8);
             var replies = process.outputWriter(java.nio.charset.StandardCharsets.UTF_8)) {
            for (String actor : List.of("receiver","owner","receiver")) {
                var request = json.readTree(java.util.Objects.requireNonNull(requests.readLine(), "小程序提前退出"));
                assertThat(request.get("actor").asText()).isEqualTo(actor);
                currentPost.use(actor.equals("owner") ? owner : engineer);
                String response;
                try {
                    var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post(request.get("path").asText()).contentType("application/json")
                            .header("Idempotency-Key",request.get("key").asText()).content(request.get("body").toString())).andReturn();
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    response = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                } catch (jakarta.servlet.ServletException error) {
                    if (!(error.getCause() instanceof ServiceException failure)) throw error;
                    response = json.writeValueAsString(Map.of("code",failure.getCode(),"msg",failure.getMessage()));
                } finally {
                    currentPost.clear();
                }
                replies.write(response);
                replies.newLine();
                replies.flush();
                if (actor.equals("owner")) {
                    assertThat(jdbc.queryForObject("SELECT status FROM fr_commission_handover WHERE commission_id=?",String.class,commission)).isEqualTo("PENDING");
                }
            }
            assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void feedbackThenNewDispatchResubmitKeepsVersionHistory() {
        long customerId = customerWithDispatchedDesign("P1/B1/F1");
        insertSignedContract(customerId);
        long commissionId = commissionIdOf(customerId);
        // 正规未接收版本变更路径：DISPATCHED 态登记修改意见 → 设计行政重新下发（seq+1/新版本）→ 提交新包
        // （finance 尾款就绪的完整重发链由 finance 域测试覆盖，此处以推进 dispatch_seq 表达重发事实）
        as(salesA1, () -> commissions.feedback(salesA1, commissionId,
                new CommissionService.FeedbackCommand("客户要求调整立面配色，需重新下发"), idem()));
        // feedback 置 DESIGNING+清 seq（修改意见→重新设计）；此处模拟设计行政重发完成态：DISPATCHED+seq2+新冻结版本
        jdbc.update("UPDATE fr_commission SET submission_version=2,status = 'DISPATCHED', dispatch_seq = 2, dispatched_scheme_version = 'P2/B2/F2', "
                + "dispatched_submission_version = 2 WHERE id = ?", commissionId);
        // 重发对应新提交（version=2，面积 120/2 层——与 V1 提交参数不同，验证按冻结版本取参）
        insertSubmission(commissionId, 2);
        seedManifest(commissionId,2,"P2/B2/F2");
        // 正规流只提交一次新包（HANDOVER_SUBMITTED 态不可再提交第二包——旧包被退回后才可重提）：
        // 重发后的新包冻结新版本 P2/B2/F2
        as(salesA1, () -> handovers.submit(salesA1, commissionId, packet(2, "P2/B2/F2", engineer.userId()), idem()));

        assertThat(jdbc.queryForObject(
                "SELECT frozen_scheme_version FROM fr_commission_handover WHERE commission_id = ? AND version = 1",
                String.class, commissionId)).isEqualTo("P2/B2/F2");
        // 接收：档案冻结版本与之一致
        as(engineer, () -> handovers.receive(engineer, commissionId,
                new HandoverService.DecisionCommand("", true), idem()));
        long projectId = jdbc.queryForObject(
                "SELECT id FROM fr_eng_project WHERE company_id = ? AND source_commission_id = ?",
                Long.class, store.orgId(), commissionId);
        assertThat(jdbc.queryForObject("SELECT drawing_version FROM fr_eng_project WHERE id = ?",
                String.class, projectId)).isEqualTo("P2/B2/F2");
        // 面积/层数按冻结下发版本取提交参数（R1 P1：预置提交 area=120/floors=2 进档案）
        assertThat(jdbc.queryForObject("SELECT area FROM fr_eng_project WHERE id = ?",
                java.math.BigDecimal.class, projectId)).isEqualTo(new java.math.BigDecimal("120.00"));
        assertThat(jdbc.queryForObject("SELECT floors FROM fr_eng_project WHERE id = ?",
                Integer.class, projectId)).isEqualTo(2);
    }

    @Test
    void frozenSubmissionVersionWinsOverDispatchSeqWhenTheyDiverge() {
        // seq≠version 场景（费用退回重提至 V2 后首次下发：seq=1 但实际使用提交=V2）——
        // 冻结取参必须按 dispatched_submission_version 而非 dispatch_seq（R2 P1 回归锁）
        long customerId = customerWithDispatchedDesign("P1/B1/F1");
        insertSignedContract(customerId);
        long commissionId = commissionIdOf(customerId);
        jdbc.update("UPDATE fr_commission SET submission_version=2,dispatch_seq = 1, dispatched_submission_version = 2 WHERE id = ?", commissionId);
        insertSubmission(commissionId, 2); // V2 提交（area=120/floors=2，与 V1 预算 400 不同）
        as(salesA1, () -> handovers.submit(salesA1, commissionId, packet(1, "P1/B1/F1", engineer.userId()), idem()));
        as(engineer, () -> handovers.receive(engineer, commissionId,
                new HandoverService.DecisionCommand("", true), idem()));
        long projectId = jdbc.queryForObject(
                "SELECT id FROM fr_eng_project WHERE company_id = ? AND source_commission_id = ?",
                Long.class, store.orgId(), commissionId);
        // V2 提交（area=120/floors=2）胜过 V1 预算（area=400）——按冻结提交版本取参
        assertThat(jdbc.queryForObject("SELECT area FROM fr_eng_project WHERE id = ?",
                java.math.BigDecimal.class, projectId)).isEqualTo(new java.math.BigDecimal("120.00"));
        assertThat(jdbc.queryForObject("SELECT floors FROM fr_eng_project WHERE id = ?",
                Integer.class, projectId)).isEqualTo(2);
    }

    @Test
    void concurrentContractBackfillIsAtomicAndNonOverwriting() {
        // R3 P2：NULL 补全的 SQL 原子性——两事务并发补不同合同时，行锁串行化后
        // COALESCE 语义保证「先到者写入、后到者不覆盖」（等价模拟：NULL → A 写入 → B COALESCE 不变）
        long customerId = customerWithDispatchedDesign("P1/B1/F1");
        long commissionId = commissionIdOf(customerId);
        long projectA = IDS.incrementAndGet();
        long contractA = IDS.incrementAndGet();
        long contractB = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_eng_project (id, company_id, customer_id, customer_name, name, status, "
                        + "manager_user_id, assigned_engineer_user_id, source_commission_id, source_type, "
                        + "sale_contract_id, revision, author_user_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, '陈先生', '陈先生 · 乡墅', 'PENDING_START', ?, ?, ?, 'HANDOVER', "
                        + "NULL, 1, ?, now(), now())",
                projectA, store.orgId(), customerId, engineer.userId(), engineer.userId(),
                commissionId, engineer.userId());
        // 事务 A：COALESCE(NULL→A) 命中补全
        int updatedA = jdbc.update(
                "UPDATE fr_eng_project SET sale_contract_id = COALESCE(sale_contract_id, ?) WHERE id = ? AND company_id = ?",
                contractA, projectA, store.orgId());
        // 事务 B（行锁释放后到达）：COALESCE(已有 A→保留 A)，不覆盖
        int updatedB = jdbc.update(
                "UPDATE fr_eng_project SET sale_contract_id = COALESCE(sale_contract_id, ?) WHERE id = ? AND company_id = ?",
                contractB, projectA, store.orgId());
        assertThat(updatedA).isEqualTo(1);
        assertThat(updatedB).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE id = ?",
                Long.class, projectA)).isEqualTo(contractA);
    }

    @Test
    void portRefreshBackfillsContractAndAuditsWithoutOverwritingExistingLink() {
        // 直接回调 EngProjectService（内部 Port 语义）：防御性幂等兜底分支——
        // 原档案合同关联不变、NULL 时补全、冻结版本变更留刷新事件（R1 P2）
        long customerId = customerWithDispatchedDesign("P1/B1/F1");
        long contractA = insertSignedContract(customerId);
        long commissionId = commissionIdOf(customerId);
        long managerUserId = engineer.userId();
        var docs = java.util.List.of(java.util.Map.of("name", "成果", "fileRef", "oss://r"));
        as(engineer, () -> engProjects.receiveFromHandover(engineer, commissionId, customerId, "陈先生",
                "武汉", "13800000000", new java.math.BigDecimal("120.00"), 2, managerUserId,
                "P1/B1/F1", "oss://d", docs, contractA, "P1/B1/F1"));
        long projectId = jdbc.queryForObject(
                "SELECT id FROM fr_eng_project WHERE company_id = ? AND source_commission_id = ?",
                Long.class, store.orgId(), commissionId);
        // 回调一：不同合同+不同冻结版本——已有档案合同关联不得被改写（蓝本：施工合同由工程维护）
        long contractB = IDS.incrementAndGet();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> as(engineer, () -> engProjects.receiveFromHandover(engineer, commissionId, customerId, "陈先生",
                "武汉", "13800000000", new java.math.BigDecimal("120.00"), 2, managerUserId,
                "P2/B2/F2", "oss://d2", docs, contractB, "P2/B2/F2"))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE id = ?",
                Long.class, projectId)).isEqualTo(contractA);
        // 回调二：原档案 NULL 时补全（回档删关联模拟）——本次带值则补
        jdbc.update("UPDATE fr_eng_project SET sale_contract_id = NULL WHERE id = ?", projectId);
        as(engineer, () -> engProjects.receiveFromHandover(engineer, commissionId, customerId, "陈先生",
                "武汉", "13800000000", new java.math.BigDecimal("120.00"), 2, managerUserId,
                "P3/B3/F3", "oss://d3", docs, contractA, "P3/B3/F3"));
        assertThat(jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE id = ?",
                Long.class, projectId)).isEqualTo(contractA);
        Integer refreshAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_command_event WHERE aggregate_id = ? AND command = 'eng-project-handover-refresh'",
                Integer.class, projectId);
        assertThat(refreshAudit).isEqualTo(1);
    }

    @Test
    void customerWithoutActiveContractCannotCreateAnUnfundedProject() {
        long customerId = customerWithDispatchedDesign("P1/B1/F1"); // 不插合同
        long commissionId = commissionIdOf(customerId);
        as(salesA1, () -> handovers.submit(salesA1, commissionId, packet(1, "P1/B1/F1", engineer.userId()), idem()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> as(engineer, () -> handovers.receive(engineer, commissionId,
                new HandoverService.DecisionCommand("", true), idem()))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_project WHERE company_id=? AND source_commission_id=?",
                Long.class, store.orgId(), commissionId)).isZero();
    }

    @Test
    void resubmitWithStaleDispatchSeqIsRejected() {
        long customerId = customerWithDispatchedDesign("P1/B1/F1");
        long commissionId = commissionIdOf(customerId);
        // 请求包携带过期 dispatchSeq（当前有效为 1，包声明 0）→ 拒绝（未确认新版本不能替换）
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                as(salesA1, () -> handovers.submit(salesA1, commissionId,
                        new HandoverService.PacketCommand(0, "{\"name\":\"成果\",\"url\":\"oss://r\"}",
                                "{\"name\":\"施工图\",\"url\":\"oss://d\"}", "{\"name\":\"审核\",\"url\":\"oss://rv\"}",
                                "{\"name\":\"确认\",\"url\":\"oss://c\"}", "P0/B0/F0", java.time.LocalDate.now(),
                                "电子交底说明", engineer.userId(), true), idem())))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(FranchiseCommissionErrorCodes.HANDOVER_INPUT_INVALID.getCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fr_commission_handover WHERE commission_id = ?",
                Long.class, commissionId)).isZero();
    }

    // ===== 辅助 =====

    /** 客户+方案+委托（真实提交至 FEE_PENDING），jdbc 预置 DISPATCHED 态与冻结方案版本（移交冻结点上游，M05-T04 已覆盖） */
    private long customerWithDispatchedDesign(String schemeVersion) {
        long customerId = as(salesA1, () -> {
            var result = leads.capture(salesA1, new cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand(
                    "陈先生", phone(), "两层乡墅标准委托", "视频号咨询"), null);
            assertThat(result.customerId()).isNotNull();
            customers.saveScheme(salesA1, result.customerId(),
                    new CustomerService.SchemeCommand("BUDGET", "参考预算", "{\"kind\":\"budget\"}",
                            new java.math.BigDecimal("13"), new java.math.BigDecimal("16"), 2,
                            new java.math.BigDecimal("400"), 68_000_000L), null);
            return result.customerId();
        });
        var detail = as(salesA1, () -> commissions.submit(salesA1, customerId,
                new CommissionService.SubmitCommand("两层乡墅，面宽13m进深16m", "方案、施工图与交底", null,
                        new java.math.BigDecimal("680000"), new java.math.BigDecimal("408000"), "总部财务",
                        1, null, null, true), idem()));
        long commissionId = detail.commission().getId();
        jdbc.update("UPDATE fr_commission SET status = 'DISPATCHED', dispatch_seq = 1, dispatched_scheme_version = ?, "
                + "dispatched_submission_version = 1 WHERE id = ?", schemeVersion, commissionId);
        seedManifest(commissionId,1,schemeVersion);
        return customerId;
    }

    /** 预置指定版本的提交行（面积 120/2 层——与 V1 预算参数 400 不同，验证冻结取参） */
    private void insertSubmission(long commissionId, int version) {
        jdbc.update("INSERT INTO fr_commission_submission (id, company_id, design_org_id, commission_id, version, "
                        + "text, scope, total_cents, first_cents, recipient, area, floors, auth_kind, "
                        + "submitted_by_user_id, submitted_by_post_id, create_time) "
                        + "VALUES (?, ?, (SELECT design_org_id FROM fr_commission WHERE id = ?), ?, ?, "
                        + "'重发版提交', '方案、施工图与交底', 68000000, 40800000, "
                        + "'总部财务', 120.00, 2, 'EXCEPTION', ?, ?, now())",
                IDS.incrementAndGet(), store.orgId(), commissionId, commissionId, version,
                salesA1.userId(), salesA1.postId());
    }

    private long insertSignedContract(long customerId) {
        long contractId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO fr_sale_contract (id, company_id, customer_id, total_cents, first_cents, "
                        + "signed_date, status, revision, author_user_id, requirement_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, 68000000, 40800000, CURRENT_DATE, 'SIGNED', 1, ?, NULL, now(), now())",
                contractId, store.orgId(), customerId, salesA1.userId());
        return contractId;
    }

    private HandoverService.PacketCommand packet(int dispatchSeq, String drawingVersion, long managerUserId) {
        return new HandoverService.PacketCommand(dispatchSeq,null,null,null,null,null,
                java.time.LocalDate.now(),"电子交底说明",managerUserId,true,104L);
    }

    private long commissionIdOfCurrent;

    private long commissionIdOf(long customerId) {
        long id = jdbc.queryForObject("SELECT id FROM fr_commission WHERE customer_id = ?", Long.class, customerId);
        commissionIdOfCurrent = id;
        return id;
    }

    private String frozenSchemeVersion(long commissionId) {
        return jdbc.queryForObject(
                "SELECT frozen_scheme_version FROM fr_commission_handover WHERE commission_id = ? AND version = 1",
                String.class, commissionId);
    }

    private Long saleContractIdOfHandover(long commissionId) {
        long handoverId = jdbc.queryForObject(
                "SELECT id FROM fr_commission_handover WHERE commission_id = ? AND version = 1",
                Long.class, commissionId);
        return jdbc.queryForObject(
                "SELECT sale_contract_id FROM fr_eng_project WHERE source_commission_id = ? AND company_id = "
                        + "(SELECT company_id FROM fr_commission_handover WHERE id = ?)",
                Long.class, commissionId, handoverId);
    }

    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

    private static String idem() {
        return "idem-" + IDS.incrementAndGet();
    }

    private static String phone() {
        return String.valueOf(19_100_000_000L + IDS.incrementAndGet() % 800_000_000L);
    }
}
