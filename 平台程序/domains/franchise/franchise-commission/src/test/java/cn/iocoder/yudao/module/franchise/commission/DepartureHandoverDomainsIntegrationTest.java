package cn.iocoder.yudao.module.franchise.commission;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionMapper;
import cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes;
import cn.iocoder.yudao.module.franchise.crm.CrmDepartureHandoverPort;
import cn.iocoder.yudao.module.franchise.crm.CrmOrgScope;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringDepartureHandoverPort;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringOrgScope;
import cn.iocoder.yudao.module.franchise.engineering.content.EngFollowupService;
import cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService;
import cn.iocoder.yudao.module.franchise.finance.FinanceDepartureHandoverPort;
import cn.iocoder.yudao.module.franchise.finance.todo.FinanceTodoProvider;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverPort;
import cn.iocoder.yudao.module.platform.org.DepartureHandoverService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static cn.iocoder.yudao.module.platform.org.DepartureHandoverService.*;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.*;
import static org.assertj.core.api.Assertions.*;

/** 完整业务装配：真实四域 Port、真实业务表和办理服务；不以测试 WorkPort 代替业务验收。 */
@Import({DepartureHandoverService.class, CrmDepartureHandoverPort.class,
        CommissionDepartureHandoverPort.class, EngineeringDepartureHandoverPort.class, FinanceDepartureHandoverPort.class,
        LeadService.class, CustomerService.class, CrmOrgScope.class, CommissionAccess.class, CommissionOrgScope.class,
        EngineeringOrgScope.class, EngProjectService.class, EngFollowupService.class, FinanceTodoProvider.class,
        FranchiseCommands.class, CommandLogImpl.class})
class DepartureHandoverDomainsIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong PHONES = new AtomicLong(18_000_000_000L + System.nanoTime() % 900_000_000L);
    @Resource private DepartureHandoverService departures;
    @Resource private List<DepartureHandoverPort> ports;
    @Resource private StaffPostService staffPosts;
    @Resource private LeadService leads;
    @Resource private CustomerService customers;
    @Resource private CommissionAccess commissionAccess;
    @Resource private CommissionMapper commissions;
    @Resource private EngineeringDepartureHandoverPort engineering;
    @Resource private EngFollowupService followups;
    @Resource private FinanceTodoProvider financeTodos;
    @Resource private JdbcTemplate jdbc;
    private Store store;
    private PostSnapshot source, sourceEngineer, receiver, receiverEngineer, receiverFinance, boss;

    @BeforeEach
    void organization() {
        store = fixture.store();
        source = fixture.post(store.orgId(), SALES, store.salesTeamA());
        sourceEngineer = fixture.postFor(source.userId(), store.orgId(), ENGINEER, store.engineeringTeam());
        fixture.postFor(source.userId(), store.orgId(), FINANCE, null);
        receiver = fixture.post(store.orgId(), SALES, store.salesTeamB());
        receiverEngineer = fixture.postFor(receiver.userId(), store.orgId(), PROJECT_MANAGER, store.engineeringTeam());
        receiverFinance = fixture.postFor(receiver.userId(), store.orgId(), FINANCE, null);
        boss = fixture.post(store.orgId(), BOSS, null);
    }

    @Test
    void realPortsPermitEmptySingleRevokeAndDistinguishSharedFinance() {
        assertThat(ports).extracting(DepartureHandoverPort::domain).containsExactlyInAnyOrder(
                "franchise-crm", "franchise-commission", "franchise-engineering", "franchise-finance");
        as(boss, () -> { departures.revokePost(source.postId(), boss.userId()); return null; });
        assertThat(staffPosts.findActivePost(source.userId(), source.postId())).isEmpty();
        assertThat(staffPosts.findActivePost(source.userId(), sourceEngineer.postId())).isPresent();
        var result = as(boss, () -> departures.emergencyRevoke(store.orgId(), source.userId(), boss.userId()));
        assertThat(item(result, "franchise-finance").status()).isEqualTo(ST_ORGANIZATION_SHARED);
        assertThat(result.items()).noneMatch(i -> ST_NOT_INTEGRATED.equals(i.status()));
        assertThat(departures.listPending(store.orgId())).isEmpty();
    }

    @Test
    void eachPersonalDomainBlocksSingleRevokeWithItsRealResponsibilityQuery() {
        for (String domain : List.of("franchise-crm", "franchise-commission", "franchise-engineering")) {
            var employee = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
            if (domain.equals("franchise-crm")) {
                var sales = fixture.postFor(employee.userId(), store.orgId(), SALES, store.salesTeamA());
                customer(sales);
            } else if (domain.equals("franchise-commission")) {
                packet(customer(receiver), employee.userId(), boss.userId());
            } else {
                project(store.orgId(), employee.userId(), employee.userId(), "ARCHIVED");
            }
            assertError(() -> as(boss, () -> { departures.revokePost(employee.postId(), boss.userId()); return null; }),
                    STAFF_HANDOVER_REQUIRED);
            assertThat(staffPosts.findActivePost(employee.userId(), employee.postId())).isPresent();
        }
    }

    @Test
    void emergencyThenBackfillMovesCurrentResponsibilitiesAndKeepsHistoryAndSharedFinance() {
        long customer = customer(source);
        as(source, () -> customers.follow(source, customer, new CustomerService.FollowCommand("原业务跟进", null), null));
        long customerRevision = number("SELECT revision FROM fr_customer WHERE id=?", customer);
        long commission = packet(customer, source.userId(), boss.userId());
        long project = project(store.orgId(), source.userId(), source.userId(), "IN_PROGRESS");
        long delayed = node(project, 1, "IN_PROGRESS", source.userId());
        long done = node(project, 2, "DONE", source.userId());
        String delayHistory = jdbc.queryForObject("SELECT delay_history::text FROM fr_eng_plan_node WHERE id=?", String.class, delayed);
        long archive = project(store.orgId(), source.userId(), boss.userId(), "ARCHIVED");
        jdbc.update("""
                INSERT INTO fr_eng_closure(id,company_id,project_id,delivered_at,recipient,file_asset_id,delivered_by,
                  settled_at,settled_by,archived_at,archived_by)
                VALUES(?,?,?,CURRENT_DATE,'原客户',1,?,CURRENT_DATE,?,now(),?)
                """, id(), store.orgId(), archive, source.userId(), source.userId(), source.userId());
        jdbc.update("INSERT INTO fr_eng_collection_term(id,company_id,project_id,stage,amount_cents) VALUES(?,?,?,'SIGN_AFTER',10000)",
                id(), store.orgId(), archive);
        long bankFlow = id();
        jdbc.update("INSERT INTO fr_fin_bank_flow(id,company_id,bank_ref,amount_cents,bank_date) VALUES(?,?,?,10000,CURRENT_DATE)",
                bankFlow, store.orgId(), "departure-" + bankFlow);

        var other = fixture.store();
        var otherSales = fixture.postFor(source.userId(), other.orgId(), SALES, other.salesTeamA());
        long otherCustomer = customer(otherSales);
        long otherCommission = packet(otherCustomer, source.userId(), boss.userId());
        long otherProject = project(other.orgId(), source.userId(), source.userId(), "IN_PROGRESS");

        var emergency = as(boss, () -> departures.emergencyRevoke(store.orgId(), source.userId(), boss.userId()));
        assertThat(item(emergency, "franchise-crm").inFlight()).isEqualTo(1);
        assertThat(item(emergency, "franchise-commission").inFlight()).isEqualTo(1);
        assertThat(item(emergency, "franchise-engineering").inFlight()).isEqualTo(2);
        assertThat(item(emergency, "franchise-finance").status()).isEqualTo(ST_ORGANIZATION_SHARED);
        assertThat(departures.listPending(store.orgId())).extracting(PendingRow::domain)
                .containsExactlyInAnyOrder("franchise-crm", "franchise-commission", "franchise-engineering");
        assertThat(staffPosts.findActivePost(source.userId(), otherSales.postId())).isPresent();
        assertThat(as(receiverFinance, () -> financeTodos.list(receiverFinance)))
                .anyMatch(todo -> todo.key().equals("fr-fin:bank-confirm:" + bankFlow));

        var result = as(boss, () -> departures.handover(store.orgId(), source.userId(), receiver.userId(), boss.userId()));
        assertThat(result.revokedPostIds()).isEmpty(); // 已撤岗来源仍可补交接。
        assertThat(item(result, "franchise-crm").transferred()).isEqualTo(1);
        assertThat(item(result, "franchise-commission").transferred()).isEqualTo(1);
        assertThat(item(result, "franchise-engineering").transferred()).isEqualTo(2);
        assertThat(item(result, "franchise-finance").status()).isEqualTo(ST_ORGANIZATION_SHARED);
        assertThat(departures.listPending(store.orgId())).isEmpty();
        assertThat(number("SELECT owner_user_id FROM fr_customer WHERE id=?", customer)).isEqualTo(receiver.userId());
        assertThat(number("SELECT revision FROM fr_customer WHERE id=?", customer)).isEqualTo(customerRevision + 1);
        assertThat(number("SELECT author_user_id FROM fr_customer_follow WHERE customer_id=?", customer)).isEqualTo(source.userId());
        assertThat(number("SELECT manager_user_id FROM fr_commission_handover WHERE commission_id=? AND version=2", commission)).isEqualTo(receiver.userId());
        assertThat(jdbc.queryForMap("SELECT manager_user_id,submitted_by_user_id,decided_by_user_id FROM fr_commission_handover WHERE commission_id=? AND version=1", commission))
                .containsAllEntriesOf(Map.of("manager_user_id", source.userId(), "submitted_by_user_id", boss.userId(), "decided_by_user_id", source.userId()));
        assertThat(number("SELECT submitted_by_user_id FROM fr_commission_handover WHERE commission_id=? AND version=2", commission)).isEqualTo(boss.userId());
        assertThat(number("SELECT revision FROM fr_commission WHERE id=?", commission)).isEqualTo(2);
        assertThat(as(boss, () -> commissions.updateByRevision(commission, 1, u -> u.set(CommissionDO::getStatus, "ACCEPTED")))).isZero();
        assertThat(commissionAccess.isHandoverManager(receiverEngineer, store.orgId(), receiver.userId())).isTrue();
        assertThat(commissionAccess.isHandoverManager(sourceEngineer, store.orgId(), receiver.userId())).isFalse();
        assertThat(jdbc.queryForMap("SELECT manager_user_id,assigned_engineer_user_id,author_user_id,revision FROM fr_eng_project WHERE id=?", project))
                .containsAllEntriesOf(Map.of("manager_user_id", receiver.userId(), "assigned_engineer_user_id", receiver.userId(),
                        "author_user_id", source.userId(), "revision", 1L));
        assertThat(number("SELECT delay_owner_user_id FROM fr_eng_plan_node WHERE id=?", delayed)).isEqualTo(receiver.userId());
        assertThat(jdbc.queryForObject("SELECT delay_history::text FROM fr_eng_plan_node WHERE id=?", String.class, delayed)).isEqualTo(delayHistory);
        assertThat(number("SELECT delay_owner_user_id FROM fr_eng_plan_node WHERE id=?", done)).isEqualTo(source.userId());
        assertThat(number("SELECT responsible_user_id FROM fr_eng_plan_node WHERE id=?", done)).isEqualTo(source.userId());
        assertThat(number("SELECT assigned_engineer_user_id FROM fr_eng_project WHERE id=?", archive)).isEqualTo(boss.userId());
        assertThat(number("SELECT archived_by FROM fr_eng_closure WHERE project_id=?", archive)).isEqualTo(source.userId());
        assertThat(as(receiverEngineer, () -> followups.dunning(receiverEngineer, archive,
                new EngFollowupService.DunningCommand("SIGN_AFTER", null, "交接后催收"), null)))
                .anyMatch(row -> receiver.userId() == ((Number) row.get("authorUserId")).longValue());
        assertThat(number("SELECT owner_user_id FROM fr_customer WHERE id=?", otherCustomer)).isEqualTo(source.userId());
        assertThat(number("SELECT manager_user_id FROM fr_commission_handover WHERE commission_id=? AND version=2", otherCommission)).isEqualTo(source.userId());
        assertThat(number("SELECT manager_user_id FROM fr_eng_project WHERE id=?", otherProject)).isEqualTo(source.userId());
        assertThat(number("SELECT COUNT(*) FROM fr_fin_bank_confirm WHERE bank_flow_id=?", bankFlow)).isZero();
        assertThat(as(receiverFinance, () -> financeTodos.list(receiverFinance)))
                .anyMatch(todo -> todo.key().equals("fr-fin:bank-confirm:" + bankFlow));
    }

    @Test
    void handoverCannotTurnTheOriginalSubmitterIntoTheirOwnEngineeringApprover() {
        long customer = customer(source);
        long commission = packet(customer, source.userId(), receiver.userId());
        assertError(() -> as(boss, () -> departures.handover(store.orgId(), source.userId(), receiver.userId(), boss.userId())),
                FranchiseCommissionErrorCodes.HANDOVER_TARGET_INVALID);
        assertThat(number("SELECT owner_user_id FROM fr_customer WHERE id=?", customer)).isEqualTo(source.userId());
        assertThat(number("SELECT revision FROM fr_customer WHERE id=?", customer)).isEqualTo(1);
        assertThat(number("SELECT revision FROM fr_commission WHERE id=?", commission)).isEqualTo(1);
        assertThat(number("SELECT manager_user_id FROM fr_commission_handover WHERE commission_id=? AND version=2", commission)).isEqualTo(source.userId());
        assertThat(staffPosts.findActivePost(source.userId(), source.postId())).isPresent();
    }

    @Test
    void orphanDelayRequiresItsCurrentOwnerAndRollsBackEarlierDomainTransfers() {
        long customer = customer(source);
        var owner = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        fixture.postFor(owner.userId(), store.orgId(), SALES, store.salesTeamB());
        long project = project(store.orgId(), owner.userId(), null, "IN_PROGRESS");
        long node = node(project, 1, "IN_PROGRESS", source.userId());
        String history = jdbc.queryForObject("SELECT delay_history::text FROM fr_eng_plan_node WHERE id=?", String.class, node);
        assertThat(engineering.scanInFlight(source.userId(), store.orgId())).isEqualTo(1);
        assertThatThrownBy(() -> as(boss, () -> departures.handover(store.orgId(), source.userId(), receiver.userId(), boss.userId())))
                .hasMessageContaining("该项目现负责人");
        assertThat(number("SELECT owner_user_id FROM fr_customer WHERE id=?", customer)).isEqualTo(source.userId());
        assertThat(number("SELECT revision FROM fr_customer WHERE id=?", customer)).isEqualTo(1);
        assertThat(number("SELECT delay_owner_user_id FROM fr_eng_plan_node WHERE id=?", node)).isEqualTo(source.userId());
        assertThat(staffPosts.findActivePost(source.userId(), source.postId())).isPresent();

        var result = as(boss, () -> departures.handover(store.orgId(), source.userId(), owner.userId(), boss.userId()));
        assertThat(item(result, "franchise-engineering").transferred()).isEqualTo(1);
        assertThat(number("SELECT manager_user_id FROM fr_eng_project WHERE id=?", project)).isEqualTo(owner.userId());
        assertThat(jdbc.queryForObject("SELECT assigned_engineer_user_id FROM fr_eng_project WHERE id=?", Long.class, project)).isNull();
        assertThat(number("SELECT delay_owner_user_id FROM fr_eng_plan_node WHERE id=?", node)).isEqualTo(owner.userId());
        assertThat(jdbc.queryForObject("SELECT delay_history::text FROM fr_eng_plan_node WHERE id=?", String.class, node)).isEqualTo(history);
    }

    @Test
    void salesOnlyReceiverCannotTakeEngineeringOrPendingDesignResponsibility() {
        long customer = customer(source);
        packet(customer, source.userId(), boss.userId());
        project(store.orgId(), source.userId(), null, "IN_PROGRESS");
        var salesOnly = fixture.post(store.orgId(), SALES, store.salesTeamB());
        assertError(() -> as(boss, () -> departures.handover(store.orgId(), source.userId(), salesOnly.userId(), boss.userId())),
                HANDOVER_TARGET_ROLE_MISMATCH);
        assertThat(number("SELECT owner_user_id FROM fr_customer WHERE id=?", customer)).isEqualTo(source.userId());
        assertThat(staffPosts.findActivePost(source.userId(), source.postId())).isPresent();
    }

    private long customer(PostSnapshot sales) {
        return as(sales, () -> leads.capture(sales,
                new LeadService.CaptureCommand("交接客户", String.valueOf(PHONES.incrementAndGet()), "自建房咨询", "到店"), null)).customerId();
    }

    private long packet(long customer, long manager, long submitter) {
        long commission = id();
        long org = number("SELECT company_id FROM fr_customer WHERE id=?", customer);
        jdbc.update("""
                INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version,handover_version)
                VALUES(?,?,?,?,?,'HANDOVER_SUBMITTED',1,2)
                """, commission, org, fixture.headquarters(), customer, "交接客户");
        for (int version = 1; version <= 2; version++) {
            jdbc.update("""
                    INSERT INTO fr_commission_handover(id,company_id,commission_id,version,dispatch_seq,result_file,drawing_file,
                      review_file,confirmation_file,drawing_version,confirmation_date,note,manager_user_id,status,submitted_by_user_id,decided_by_user_id)
                    VALUES(?,?,?,?,1,'result','drawing','review','confirm','V1',CURRENT_DATE,'历史移交',?,?,?,?)
                    """, id(), org, commission, version, manager, version == 1 ? "ACCEPTED" : "PENDING", submitter,
                    version == 1 ? manager : null);
        }
        return commission;
    }

    private long project(long org, long manager, Long assigned, String status) {
        long id = id();
        jdbc.update("""
                INSERT INTO fr_eng_project(id,company_id,source_type,manager_user_id,assigned_engineer_user_id,name,status,author_user_id)
                VALUES(?,?,'MANUAL',?,?,?,?,?)
                """, id, org, manager, assigned, "交接工程-" + id, status, manager);
        return id;
    }

    private long node(long project, int index, String status, long delayOwner) {
        long id = id();
        jdbc.update("""
                INSERT INTO fr_eng_plan_node(id,company_id,project_id,node_index,name,status,delay_owner_user_id,responsible_user_id,delay_history)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb)
                """, id, store.orgId(), project, index, "交接节点", status, delayOwner,
                "DONE".equals(status) ? delayOwner : null,
                "[{\"actorUserId\":" + delayOwner + ",\"ownerUserId\":" + delayOwner + ",\"ownerName\":\"原工程师\"}]");
        return id;
    }

    private static HandoverItem item(HandoverResult result, String domain) {
        return result.items().stream().filter(i -> i.domain().equals(domain)).findFirst().orElseThrow();
    }
    private long number(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static long id() { return IdUtil.getSnowflakeNextId(); }
    private <T> T as(PostSnapshot post, Supplier<T> action) {
        currentPost.use(post);
        try { return action.get(); } finally { currentPost.clear(); }
    }
}
