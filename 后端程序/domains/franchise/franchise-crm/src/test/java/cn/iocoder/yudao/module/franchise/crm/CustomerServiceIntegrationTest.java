package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.FollowUpDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.franchise.test.FranchiseIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({CustomerServiceImpl.class, CustomerPolicy.class, FranchiseCrmConfiguration.class})
class CustomerServiceIntegrationTest extends FranchiseIntegrationTest {

    @Resource
    private CustomerService customerService;
    @Resource
    private CommandLog commandLog;

    private long store;
    private long otherStore;
    private long teamA;
    private long teamB;
    private PostSnapshot salesA;
    private PostSnapshot salesB;

    @BeforeEach
    void setUp() {
        store = newId();
        otherStore = newId();
        teamA = newId();
        teamB = newId();
        salesA = new PostSnapshot(newId(), newId(), store, ORG_STORE, SALES, teamA, false, Set.of());
        salesB = new PostSnapshot(newId(), newId(), store, ORG_STORE, SALES, teamB, false, Set.of());
    }

    // ===================== 客资登记 =====================

    @Test
    void newPhoneCreatesACustomerOwnedByTheRegistrar() {
        LeadCaptureResult result = customerService.captureLead(as(salesA), lead("13800000001"));

        assertThat(result.linkedExisting()).isFalse();
        CustomerDO customer = customerService.getCustomer(salesA, result.customerId()).customer();
        assertThat(customer.getOwnerUserId()).isEqualTo(salesA.userId());
        assertThat(customer.getTeamId()).isEqualTo(teamA);
        assertThat(customer.getCompanyId()).isEqualTo(store);
        assertThat(commandLog.listByAggregate("franchise", "customer", result.customerId()))
                .extracting(e -> e.getCommand()).containsExactly("lead-capture");
    }

    @Test
    void samePhoneInTheSameStoreLinksToTheOriginalCustomerAndKeepsItsOwner() {
        long customerId = customerService.captureLead(as(salesA), lead("13800000002")).customerId();

        LeadCaptureResult again = customerService.captureLead(as(salesB), lead("13800000002"));

        assertThat(again.linkedExisting()).isTrue();
        assertThat(again.customerId()).isEqualTo(customerId);
        assertThat(again.customerOwnerUserId()).isEqualTo(salesA.userId());
        CustomerDetail detail = customerService.getCustomer(as(salesA), customerId);
        assertThat(detail.customer().getRevision()).isEqualTo(2L);
        assertThat(detail.leads()).extracting(LeadDO::getLinkKind).containsExactly("NEW_CUSTOMER", "EXISTING_CUSTOMER");
        assertThat(detail.leads()).extracting(LeadDO::getRegistrarUserId).containsExactly(salesA.userId(), salesB.userId());
    }

    @Test
    void samePhoneInAnotherStoreIsASeparateCustomer() {
        long first = customerService.captureLead(as(salesA), lead("13800000003")).customerId();
        PostSnapshot otherSales = post(otherStore, ORG_STORE, SALES);

        LeadCaptureResult other = customerService.captureLead(as(otherSales), lead("13800000003"));

        assertThat(other.linkedExisting()).isFalse();
        assertThat(other.customerId()).isNotEqualTo(first);
    }

    @Test
    void onlySalesPostsCaptureLeadsAndInputIsValidated() {
        assertError(() -> customerService.captureLead(as(post(store, ORG_STORE, FINANCE)), lead("13800000004")), ACTOR_NOT_SALES);
        assertError(() -> customerService.captureLead(as(salesA), new LeadInput("", "13800000004", "需求", null)), LEAD_INVALID);
        assertError(() -> customerService.captureLead(as(salesA), new LeadInput("张先生", "2380000000", "需求", null)), LEAD_INVALID);
        assertError(() -> customerService.captureLead(as(salesA), new LeadInput("张先生", "13800000004", " ", null)), LEAD_INVALID);
        // 业务负责人继承业务员身份，可以登记
        PostSnapshot lead = lead(store, SALES_LEAD, teamA, Set.of(teamA));
        assertThat(customerService.captureLead(as(lead), lead("13800000004")).linkedExisting()).isFalse();
    }

    // ===================== 跟进 =====================

    @Test
    void ownerAndManagingLeadFollowUpWithOptimisticLocking() {
        long customerId = customerService.captureLead(as(salesA), lead("13800000005")).customerId();
        customerService.addFollowUp(as(salesA), customerId, 1L, "电话沟通户型", LocalDate.of(2026, 10, 30));
        PostSnapshot managingLead = lead(store, SALES_LEAD, teamA, Set.of(teamA));
        customerService.addFollowUp(as(managingLead), customerId, 2L, "负责人回访", null);

        CustomerDetail detail = customerService.getCustomer(as(salesA), customerId);
        assertThat(detail.customer().getRevision()).isEqualTo(3L);
        assertThat(detail.followUps()).extracting(FollowUpDO::getNote).containsExactly("电话沟通户型", "负责人回访");
        assertThat(detail.followUps().get(0).getNextVisitDate()).isEqualTo(LocalDate.of(2026, 10, 30));

        assertError(() -> customerService.addFollowUp(as(salesA), customerId, 2L, "过期版本", null),
                ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT);
        assertError(() -> customerService.addFollowUp(as(salesA), customerId, 3L, "  ", null), FOLLOW_UP_INVALID);
    }

    @Test
    void othersCannotSeeOrFollowSomeoneElsesCustomer() {
        long customerId = customerService.captureLead(as(salesA), lead("13800000006")).customerId();

        assertError(() -> customerService.addFollowUp(as(salesB), customerId, 1L, "抢跟进", null), CUSTOMER_NOT_FOUND);
        assertError(() -> customerService.getCustomer(as(salesB), customerId), CUSTOMER_NOT_FOUND);
        assertError(() -> customerService.getCustomer(as(post(otherStore, ORG_STORE, BOSS)), customerId), CUSTOMER_NOT_FOUND);
        // 老板能看但不能跟进（跟进属于业务条线）
        PostSnapshot boss = post(store, ORG_STORE, BOSS);
        assertThat(customerService.getCustomer(as(boss), customerId).customer().getId()).isEqualTo(customerId);
        assertError(() -> customerService.addFollowUp(as(boss), customerId, 1L, "老板跟进", null), CUSTOMER_NOT_FOUND);
    }

    // ===================== 列表可见范围 =====================

    @Test
    void customerListFollowsTheVisibilityRules() {
        long a = customerService.captureLead(as(salesA), lead("13800000007")).customerId();
        long b = customerService.captureLead(as(salesB), lead("13800000008")).customerId();
        long elsewhere = customerService.captureLead(as(post(otherStore, ORG_STORE, SALES)), lead("13800000009")).customerId();

        assertThat(visible(salesA)).contains(a).doesNotContain(b, elsewhere);
        assertThat(visible(lead(store, SALES_LEAD, teamB, Set.of(teamB)))).contains(b).doesNotContain(a, elsewhere);
        assertThat(visible(lead(store, SALES_LEAD, newId(), Set.of()))).doesNotContain(a, b);
        // 未分团队的业务负责人管理全店业务
        assertThat(visible(new PostSnapshot(newId(), newId(), store, ORG_STORE, SALES_LEAD, null, true, Set.of())))
                .contains(a, b).doesNotContain(elsewhere);
        assertThat(visible(post(store, ORG_STORE, BOSS))).contains(a, b).doesNotContain(elsewhere);
        assertThat(visible(post(store, ORG_STORE, OWNER))).contains(a, b);
        assertThat(visible(post(store, ORG_STORE, FINANCE))).isEmpty();
        assertThat(visible(post(newId(), ORG_HEADQUARTERS, FranchiseRoles.HQ_DESIGN_ADMIN))).isEmpty();
    }

    @Test
    void listCanBeSearchedByNameOrPhone() {
        long id = customerService.captureLead(as(salesA), new LeadInput("欧阳先生", "13800000010", "两层乡墅", null)).customerId();

        assertThat(customerService.pageCustomers(as(salesA), new CustomerQuery(1, 20, "欧阳")).getList())
                .extracting(CustomerDO::getId).containsExactly(id);
        assertThat(customerService.pageCustomers(as(salesA), new CustomerQuery(1, 20, "00000010")).getList())
                .extracting(CustomerDO::getId).containsExactly(id);
    }

    @Test
    void entitiesAreTenantIgnored() {
        for (Class<?> c : List.of(CustomerDO.class, LeadDO.class, FollowUpDO.class)) {
            assertThat(c.isAnnotationPresent(TenantIgnore.class)).as(c.getSimpleName()).isTrue();
        }
    }

    private List<Long> visible(PostSnapshot post) {
        return customerService.pageCustomers(as(post), new CustomerQuery(1, 100, null)).getList().stream()
                .map(CustomerDO::getId).toList();
    }

    private static LeadInput lead(String phone) {
        return new LeadInput("陈先生", phone, "三层乡墅新建", "直接来访");
    }

    private static void assertError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode()).isEqualTo(expected.getCode());
    }

}
