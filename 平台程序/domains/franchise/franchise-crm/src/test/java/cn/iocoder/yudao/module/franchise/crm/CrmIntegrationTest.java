package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.TransferCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.UpdateCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureOutcome;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureResult;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper.FollowFilter;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerFollowDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FakeSystemDirectory;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "platform.legacy-design-bridge.enabled=false")
@Import({LeadService.class, LeadImportService.class, CustomerService.class, CrmOrgScope.class, FranchiseCommands.class, CommandLogImpl.class})
class CrmIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong PHONES = new AtomicLong(13_000_000_000L + System.nanoTime() % 900_000_000L);

    @Resource
    private LeadService leads;
    @Resource
    private LeadImportService imports;
    @Resource
    private CustomerService customers;

    private Store store;
    private PostSnapshot salesA1;
    private PostSnapshot salesA2;
    private PostSnapshot salesB;
    private PostSnapshot leadA;
    private PostSnapshot leadWholeLine;
    private PostSnapshot boss;
    private PostSnapshot engineer;
    private PostSnapshot otherStoreSales;

    @BeforeEach
    void organization() {
        store = fixture.store();
        salesA1 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        salesA2 = fixture.post(store.orgId(), SALES, store.salesTeamA());
        salesB = fixture.post(store.orgId(), SALES, store.salesTeamB());
        leadA = fixture.post(store.orgId(), SALES_LEAD, store.salesTeamA());
        leadWholeLine = fixture.post(store.orgId(), SALES_LEAD, null);
        boss = fixture.post(store.orgId(), BOSS, null);
        engineer = fixture.post(store.orgId(), ENGINEER, store.engineeringTeam());
        Store other = fixture.store();
        otherStoreSales = fixture.post(other.orgId(), SALES, other.salesTeamA());
    }

    // ===================== 客资 =====================

    @Test
    void capturingANewPhoneCreatesACustomerOwnedByTheRegistrar() {
        CaptureResult result = capture(salesA1, phone());

        assertThat(result.outcome()).isEqualTo(CaptureOutcome.CREATED);
        CustomerDO customer = as(salesA1, () -> customers.get(salesA1, result.customerId()).customer());
        assertThat(customer.getOwnerUserId()).isEqualTo(salesA1.userId());
        assertThat(customer.getTeamId()).isEqualTo(store.salesTeamA());
        assertThat(customer.getStatus()).isEqualTo(CustomerDO.STATUS_REGISTERED);
        assertThat(as(salesA1, () -> customers.listAwaitingFirstFollow(salesA1))).extracting(CustomerDO::getId).contains(customer.getId());
    }

    @Test
    void sameTeamColleagueCapturingTheSamePhoneLinksTheOriginalCustomerAndKeepsItsOwner() {
        String phone = phone();
        CaptureResult first = capture(salesA1, phone);
        CaptureResult second = capture(salesA2, phone);

        assertThat(second.outcome()).isEqualTo(CaptureOutcome.LINKED);
        assertThat(second.customerId()).isEqualTo(first.customerId());
        assertThat(as(salesA1, () -> customers.get(salesA1, first.customerId()).customer().getOwnerUserId())).isEqualTo(salesA1.userId());
    }

    @Test
    void otherTeamCapturingTheSamePhoneWaitsForAManagerToLink() {
        String phone = phone();
        CaptureResult original = capture(salesA1, phone);
        CaptureResult pending = capture(salesB, phone);

        assertThat(pending.outcome()).isEqualTo(CaptureOutcome.LINK_REQUIRED);
        assertThat(pending.customerId()).isNull();
        assertError(() -> capture(salesA2, phone), LEAD_DUPLICATE);

        // 业务一组负责人管不到业务二组登记的客资；不分团队的负责人和老板可以
        assertError(() -> as(leadA, () -> leads.link(leadA, pending.leadId(), 1, null)), LEAD_NOT_FOUND);
        assertThat(as(leadWholeLine, () -> leads.listPending(leadWholeLine))).extracting(LeadDO::getId).contains(pending.leadId());
        assertThat(as(salesB, () -> leads.listPending(salesB))).extracting(LeadDO::getId).containsExactly(pending.leadId());
        LeadDO linked = as(boss, () -> leads.link(boss, pending.leadId(), 1, null));

        assertThat(linked.getStatus()).isEqualTo(LeadDO.STATUS_CONVERTED);
        assertThat(linked.getConversion()).isEqualTo(LeadDO.CONVERSION_LINKED);
        assertThat(linked.getCustomerId()).isEqualTo(original.customerId());
        assertError(() -> as(boss, () -> leads.link(boss, pending.leadId(), 2, null)), LEAD_NOT_PENDING);
    }

    @Test
    void registrarCanInvalidateTheirPendingLead() {
        String phone = phone();
        capture(salesA1, phone);
        CaptureResult pending = capture(salesB, phone);

        assertError(() -> as(salesA2, () -> leads.invalidate(salesA2, pending.leadId(), "重复咨询", 1, null)), LEAD_NOT_FOUND);
        LeadDO invalid = as(salesB, () -> leads.invalidate(salesB, pending.leadId(), "客户表示已在别处登记", 1, null));

        assertThat(invalid.getStatus()).isEqualTo(LeadDO.STATUS_INVALID);
        assertThat(capture(salesB, phone).outcome()).isEqualTo(CaptureOutcome.LINK_REQUIRED);
    }

    @Test
    void onlyStoreSalesPostsCanCaptureAndInputIsValidated() {
        assertError(() -> capture(boss, phone()), STORE_SALES_POST_REQUIRED);
        assertError(() -> capture(engineer, phone()), STORE_SALES_POST_REQUIRED);
        assertError(() -> capture(salesA1, "1234"), LEAD_INPUT_INVALID);
        assertError(() -> as(salesA1, () -> leads.capture(salesA1, new CaptureCommand("陈先生", phone(), " ", "视频号"), null)),
                LEAD_INPUT_INVALID);
    }

    @Test
    void storesAreIsolatedEvenForTheSamePhone() {
        String phone = phone();
        CaptureResult mine = capture(salesA1, phone);
        CaptureResult theirs = capture(otherStoreSales, phone);

        assertThat(theirs.outcome()).isEqualTo(CaptureOutcome.CREATED);
        assertThat(theirs.customerId()).isNotEqualTo(mine.customerId());
        assertError(() -> as(otherStoreSales, () -> customers.get(otherStoreSales, mine.customerId())), CUSTOMER_NOT_FOUND);
        assertError(() -> as(otherStoreSales, () -> customers.follow(otherStoreSales, mine.customerId(), follow("越权"), null)),
                CUSTOMER_NOT_FOUND);
    }

    // ===================== 可见与可办理 =====================

    @Test
    void visibilityFollowsOwnerTeamAndBossRules() {
        long id = capture(salesA1, phone()).customerId();

        assertError(() -> as(salesB, () -> customers.get(salesB, id)), CUSTOMER_NOT_FOUND);
        assertError(() -> as(salesA2, () -> customers.get(salesA2, id)), CUSTOMER_NOT_FOUND);
        assertError(() -> as(engineer, () -> customers.get(engineer, id)), CUSTOMER_NOT_FOUND);
        assertThat(as(leadA, () -> customers.get(leadA, id))).isNotNull();
        assertThat(as(leadWholeLine, () -> customers.get(leadWholeLine, id))).isNotNull();
        assertThat(as(boss, () -> customers.get(boss, id))).isNotNull();

        // 老板只看不办；负责人可以替团队成员办理
        assertError(() -> as(boss, () -> customers.follow(boss, id, follow("老板跟进"), null)), CUSTOMER_FORBIDDEN);
        assertThat(as(leadA, () -> customers.follow(leadA, id, follow("负责人协助跟进"), null)).follows()).hasSize(1);

        assertThat(as(salesA1, () -> customers.page(salesA1, page(), null)).getList()).extracting(CustomerDO::getId).contains(id);
        assertThat(as(salesA2, () -> customers.page(salesA2, page(), null)).getList()).extracting(CustomerDO::getId).doesNotContain(id);
        assertThat(as(leadA, () -> customers.page(leadA, page(), null)).getList()).extracting(CustomerDO::getId).contains(id);
        assertThat(as(otherStoreSales, () -> customers.page(otherStoreSales, page(), null)).getList())
                .extracting(CustomerDO::getId).doesNotContain(id);
    }

    // ===================== 跟进、编辑、方案 =====================

    @Test
    void followingMovesToFollowingAndSchedulesARevisit() {
        long id = capture(salesA1, phone()).customerId();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));

        assertError(() -> as(salesA1, () -> customers.follow(salesA1, id, new FollowCommand("约时间", today.minusDays(1)), null)),
                VISIT_DATE_INVALID);
        var detail = as(salesA1, () -> customers.follow(salesA1, id, new FollowCommand("客户想看两层方案", today), null));

        assertThat(detail.customer().getStatus()).isEqualTo(CustomerDO.STATUS_FOLLOWING);
        assertThat(detail.customer().getRevision()).isEqualTo(2L);
        assertThat(detail.follows()).extracting(CustomerFollowDO::getNote).containsExactly("客户想看两层方案");
        assertThat(as(salesA1, () -> customers.listDueVisits(salesA1, today))).extracting(CustomerDO::getId).contains(id);
        assertThat(as(salesA1, () -> customers.listAwaitingFirstFollow(salesA1))).extracting(CustomerDO::getId).doesNotContain(id);
    }

    @Test
    void replayingTheSameIdempotencyKeyDoesNotFollowTwice() {
        long id = capture(salesA1, phone()).customerId();

        as(salesA1, () -> customers.follow(salesA1, id, follow("第一次沟通"), "follow-1"));
        var replay = as(salesA1, () -> customers.follow(salesA1, id, follow("第一次沟通"), "follow-1"));

        assertThat(replay.follows()).hasSize(1);
        assertThat(replay.customer().getRevision()).isEqualTo(2L);
    }

    @jakarta.annotation.Resource private org.springframework.jdbc.core.JdbcTemplate followJdbc;

    @Test
    void leadImportPrecheckAndSubmitUseTheSamePublishedSourceRule() {
        // CRM's isolated test profile does not run the Content module's migrations.
        // Keep the fixture compatible with the full publication table if present.
        followJdbc.execute("""
            CREATE TABLE IF NOT EXISTS fr_content_publication (
              id bigint PRIMARY KEY, company_id bigint, author_user_id bigint, work_id bigint,
              title text, account_id text, platform text, status text, published_url text, published_at timestamptz)
            """);
        long published=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long earlierDraft=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        for (long id : List.of(published, earlierDraft)) {
            boolean live=id==published;
            followJdbc.update("""
                INSERT INTO fr_content_publication (id,company_id,author_user_id,work_id,title,account_id,platform,status,published_url,published_at)
                VALUES(?,?,?,?,?,'fixture-account','douyin',?,?,CASE WHEN ? THEN now() ELSE NULL END)
                """,id,boss.orgId(),boss.userId(),id,"合成视频",live?"published":"publishing",
                    live?"https://example.invalid/work/"+id:null,live);
        }
        String existingPhone=phone();
        capture(salesA1,existingPhone);
        String fresh=phone();
        var rows=List.of(new LeadImportService.Row(1,"合成客户甲",fresh,"咨询", "内容发布:"+published),
                new LeadImportService.Row(2,"合成客户乙",existingPhone,"咨询","内容发布:"+earlierDraft),
                new LeadImportService.Row(3,"合成客户丙",phone(),"咨询","内容发布:"+earlierDraft));
        String batch="publication-precheck-"+published;
        var precheck=as(boss,()->imports.precheck(boss,batch,rows));
        assertThat(precheck.rows()).extracting(LeadImportService.RowResult::status).containsExactly("OK","FAILED","FAILED");
        assertThat(precheck.rows().get(1).message()).contains("已发布的视频");
        assertThat(followJdbc.queryForObject("SELECT count(*) FROM fr_customer WHERE company_id=? AND phone=?",Long.class,boss.orgId(),fresh)).isZero();
        var submitted=as(boss,()->imports.submit(boss,batch,rows));
        assertThat(submitted.created()).isEqualTo(1);
        assertThat(submitted.failed()).isEqualTo(2);
        // The successful row stays replayable even if its source later changes state.
        followJdbc.update("UPDATE fr_content_publication SET status='failed' WHERE id=?",published);
        var retried=as(boss,()->imports.submit(boss,batch,rows));
        assertThat(retried.skipped()).isEqualTo(1);
        assertThat(retried.created()).isZero();
    }

    @Test
    void oldDefaultDateCanReplayAcrossMidnightButChangedContentCannot() {
        long id=capture(salesA1,phone()).customerId();
        var command=new FollowCommand("回访",null);
        as(salesA1,()->customers.follow(salesA1,id,command,"cross-day"));
        var yesterday=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1);
        String old=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(new FollowCommand("回访",null,"FOLLOW",yesterday));
        followJdbc.update("UPDATE pf_command_event SET occurred_at=?,payload=?,payload_hash=? WHERE aggregate_id=? AND command='customer-follow'",
                java.sql.Timestamp.from(yesterday.atTime(12,0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()),
                old,cn.hutool.crypto.digest.DigestUtil.sha256Hex(old),id);
        assertThat(as(salesA1,()->customers.follow(salesA1,id,command,"cross-day")).follows()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->as(salesA1,()->customers.follow(salesA1,id,new FollowCommand("不同内容",null),"cross-day"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void twoFieldFollowReplaysOnlyTheOriginalCustomerAndDefaultKind() {
        long id=capture(salesA1,phone()).customerId(), other=capture(salesA1,phone()).customerId();
        var command=new FollowCommand("回访",null);
        as(salesA1,()->customers.follow(salesA1,id,command,"two-fields"));
        record OldCommand(String note, LocalDate nextVisitDate) {}
        String old=cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(new OldCommand("回访",null));
        followJdbc.update("UPDATE pf_command_event SET payload=?,payload_hash=? WHERE aggregate_id=? AND command='customer-follow'",
                old,cn.hutool.crypto.digest.DigestUtil.sha256Hex(old),id);
        assertThat(as(salesA1,()->customers.follow(salesA1,id,command,"two-fields")).follows()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->as(salesA1,()->customers.follow(salesA1,other,command,"two-fields"))).isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->as(salesA1,()->customers.follow(salesA1,id,new FollowCommand("回访",null,"VISIT",null),"two-fields"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void editingWithAStaleRevisionIsRejected() {
        long id = capture(salesA1, phone()).customerId();
        UpdateCommand update = new UpdateCommand("陈先生", "chen-wx", "湖北省", "武汉市", "两层乡墅");

        as(salesA1, () -> customers.update(salesA1, id, update, 1, null));

        assertError(() -> as(salesA1, () -> customers.update(salesA1, id, update, 1, null)), STATE_VERSION_CONFLICT);
        assertError(() -> as(salesA1, () -> customers.update(salesA1, id, new UpdateCommand(" ", null, null, null, null), 2, null)),
                CUSTOMER_INPUT_INVALID);
    }

    @Test
    void schemesAreVersionedPerKindAndBudgetsNeedValidDimensions() {
        long id = capture(salesA1, phone()).customerId();

        assertError(() -> as(salesA1, () -> customers.saveScheme(salesA1, id,
                budget(BigDecimal.ZERO), null)), SCHEME_INVALID);
        assertError(() -> as(salesA1, () -> customers.saveScheme(salesA1, id,
                new SchemeCommand("QUOTE", "报价", null, null, null, null, null, null), null)), SCHEME_INVALID);
        assertThat(as(salesA1, () -> customers.listWritableWithBudget(salesA1))).extracting(CustomerDO::getId).doesNotContain(id);

        as(salesA1, () -> customers.saveScheme(salesA1, id, budget(new BigDecimal("12.5")), null));
        CustomerSchemeDO second = as(salesA1, () -> customers.saveScheme(salesA1, id, budget(new BigDecimal("13")), null));
        CustomerSchemeDO plan = as(salesA1, () -> customers.saveScheme(salesA1, id,
                new SchemeCommand("PLAN", "AI 平面方案", "{\"image\":\"a.png\"}", null, null, null, null, null), null));

        assertThat(second.getVersion()).isEqualTo(2);
        assertThat(plan.getVersion()).isEqualTo(1);
        assertThat(as(salesA1, () -> customers.latestScheme(id, CustomerSchemeDO.KIND_BUDGET)).getWidth()).isEqualByComparingTo("13");
        assertThat(as(salesA1, () -> customers.listWritableWithBudget(salesA1))).extracting(CustomerDO::getId).contains(id);
        assertThat(as(salesA2, () -> customers.listWritableWithBudget(salesA2))).extracting(CustomerDO::getId).doesNotContain(id);
    }

    @Test
    void verifiedItemizedBudgetKeepsAnIncompleteNullTotalAndProtectsCustomerOwnership() {
        long id=capture(salesA1,phone()).customerId();
        var partial=new CustomerService.SchemeSaveCommand("BUDGET","  分项预算  ","{\"itemizedBudget\":{\"model\":\"ITEMIZED_V1\",\"completeness\":\"INCOMPLETE\",\"totalCents\":null}}",null,null,2,new BigDecimal("240"),null,null);
        var saved=as(salesA1,()->customers.saveItemizedBudgetSnapshot(salesA1,id,partial));
        assertThat(saved.getTotalCents()).isNull();assertThat(saved.getWidth()).isNull();assertThat(saved.getDepth()).isNull();assertThat(saved.getTitle()).isEqualTo("分项预算");
        assertThat(as(salesA1,()->customers.latestScheme(id,"BUDGET")).getPayload()).contains("INCOMPLETE");
        assertError(()->as(otherStoreSales,()->customers.saveItemizedBudgetSnapshot(otherStoreSales,id,partial)),CUSTOMER_NOT_FOUND);
        assertError(()->as(salesA2,()->customers.saveItemizedBudgetSnapshot(salesA2,id,partial)),CUSTOMER_NOT_FOUND);
        var mismatch=new CustomerService.SchemeSaveCommand("BUDGET","分项预算",partial.payload(),null,null,2,new BigDecimal("240"),0L,null);
        assertError(()->as(salesA1,()->customers.saveItemizedBudgetSnapshot(salesA1,id,mismatch)),SCHEME_INVALID);
        var complete=new CustomerService.SchemeSaveCommand("BUDGET","分项预算","{\"itemizedBudget\":{\"model\":\"ITEMIZED_V1\",\"completeness\":\"COMPLETE\",\"totalCents\":6800000}}",null,null,2,new BigDecimal("240"),6800000L,null);
        var second=as(salesA1,()->customers.saveItemizedBudgetSnapshot(salesA1,id,complete));assertThat(second.getVersion()).isEqualTo(2);assertThat(second.getTotalCents()).isEqualTo(6800000L);
    }

    // ===================== 责任转交 =====================

    @Test
    void transferMovesOwnershipWithinTheManagersTeams() {
        long id = capture(salesA1, phone()).customerId();

        assertError(() -> as(salesA1, () -> customers.transfer(salesA1, id, new TransferCommand(salesA2.userId(), "调岗"), 1, null)),
                CUSTOMER_FORBIDDEN);
        assertError(() -> as(leadA, () -> customers.transfer(leadA, id, new TransferCommand(salesB.userId(), "调岗"), 1, null)),
                TRANSFER_TARGET_INVALID);
        assertError(() -> as(leadA, () -> customers.transfer(leadA, id, new TransferCommand(engineer.userId(), "调岗"), 1, null)),
                TRANSFER_TARGET_INVALID);

        var moved = as(leadA, () -> customers.transfer(leadA, id, new TransferCommand(salesA2.userId(), "张业务离岗"), 1, null));

        assertThat(moved.customer().getOwnerUserId()).isEqualTo(salesA2.userId());
        assertError(() -> as(salesA1, () -> customers.get(salesA1, id)), CUSTOMER_NOT_FOUND);
        assertThat(as(salesA2, () -> customers.get(salesA2, id))).isNotNull();

        var crossTeam = as(boss, () -> customers.transfer(boss, id, new TransferCommand(salesB.userId(), "老板调整分工"), 2, null));
        assertThat(crossTeam.customer().getTeamId()).isEqualTo(store.salesTeamB());
    }

    @Test
    void crmEntitiesAreTenantIgnored() {
        for (Class<?> entity : List.of(LeadDO.class, CustomerDO.class, CustomerFollowDO.class, CustomerSchemeDO.class)) {
            assertThat(entity.isAnnotationPresent(TenantIgnore.class)).as(entity.getSimpleName()).isTrue();
        }
    }


    // ===================== 批4：客资表格筛选与接手人候选 =====================

    @Test
    void customerPageFiltersByStatusAndOwnerWithinVisibility() {
        long a = capture(salesA1, phone()).customerId();
        long b = capture(salesB, phone()).customerId();

        var all = as(boss, () -> customers.page(boss, page(), null, null, null));
        assertThat(all.getList()).extracting(CustomerDO::getId).contains(a, b);

        var filtered = as(boss, () -> customers.page(boss, page(), null,
                CustomerDO.STATUS_REGISTERED, salesA1.userId()));
        assertThat(filtered.getList()).extracting(CustomerDO::getId).contains(a).doesNotContain(b);

        // 可见范围仍先生效：salesB 指定 owner=salesA1 也看不到 A 团队的客户
        var invisible = as(salesB, () -> customers.page(salesB, page(), null, null, salesA1.userId()));
        assertThat(invisible.getList()).isEmpty();
    }

    @Test
    void transferCandidatesRespectRoleTeamActiveStatusAndCurrentOwner() {
        long customerId = capture(salesA2, phone()).customerId();
        var bossCandidates = as(boss, () -> customers.transferCandidates(boss, customerId));
        assertThat(bossCandidates).extracting(CustomerService.TransferCandidate::userId)
                .contains(salesA1.userId(), salesB.userId(), leadA.userId(), leadWholeLine.userId())
                // 当前负责人不是合法接手人；工程/老板/跨组织销售也不是
                .doesNotContain(salesA2.userId(), engineer.userId(), boss.userId(), otherStoreSales.userId());

        // 销售主管只能看到自己管理团队的候选（且排除当前负责人 salesA2）
        var leadCandidates = as(leadA, () -> customers.transferCandidates(leadA, customerId));
        assertThat(leadCandidates).extracting(CustomerService.TransferCandidate::userId)
                .containsExactly(salesA1.userId());

        // 停用账号不得成为接手人
        FakeSystemDirectory.INSTANCE.setUserActive(salesA1.userId(), false);
        try {
            assertThat(as(boss, () -> customers.transferCandidates(boss, customerId)))
                    .extracting(CustomerService.TransferCandidate::userId)
                    .doesNotContain(salesA1.userId());
        } finally {
            FakeSystemDirectory.INSTANCE.setUserActive(salesA1.userId(), true);
        }

        // 可读 ≠ 可转让：sales 能读自己经办的客户，但候选端点应与 transfer 一样拒绝
        long ownCustomerId = capture(salesA1, phone()).customerId();
        assertError(() -> as(salesA1, () -> customers.transferCandidates(salesA1, ownCustomerId)),
                CUSTOMER_FORBIDDEN);

        // 跨组织 customerId：不可读即不可枚举候选（与 transfer 的 requireReadable 同源）
        long otherCustomerId = capture(otherStoreSales, phone()).customerId();
        assertError(() -> as(boss, () -> customers.transferCandidates(boss, otherCustomerId)),
                CUSTOMER_NOT_FOUND);

        // 与 transfer 同源：转给当前负责人 / 转给自己都必须拒绝
        assertError(() -> as(boss, () -> customers.transfer(boss, customerId,
                new TransferCommand(salesA2.userId(), "转给当前负责人"), 1, null)), TRANSFER_TARGET_INVALID);
        assertError(() -> as(leadA, () -> customers.transfer(leadA, customerId,
                new TransferCommand(leadA.userId(), "转给自己"), 1, null)), TRANSFER_TARGET_INVALID);
    }


    @Test
    void ownerOptionsCoverDistinctOwnersInVisibleScope() {
        capture(salesA1, phone());
        capture(salesB, phone());
        var bossOptions = as(boss, () -> customers.ownerOptions(boss));
        assertThat(bossOptions).extracting(CustomerService.OwnerOption::userId)
                .contains(salesA1.userId(), salesB.userId());
        // 业务员只看得到自己的客资 → 名册里只有自己
        var salesOptions = as(salesA1, () -> customers.ownerOptions(salesA1));
        assertThat(salesOptions).extracting(CustomerService.OwnerOption::userId)
                .containsExactly(salesA1.userId());
    }

    @Test
    void dualPostCandidateUsesTheSameCanonicalPostAsTransferValidation() {
        long userId = FakeSystemDirectory.INSTANCE.newUser();
        fixture.postFor(userId, store.orgId(), SALES, store.salesTeamB());
        fixture.postFor(userId, store.orgId(), SALES_LEAD, store.salesTeamA());

        // 规范岗位是 sales(B)（sales 优先），A 团队主管看到候选也会被转让校验拒绝 → 候选不得出现
        long candidateCustomerId = capture(salesA1, phone()).customerId();
        assertThat(as(leadA, () -> customers.transferCandidates(leadA, candidateCustomerId)))
                .extracting(CustomerService.TransferCandidate::userId)
                .doesNotContain(userId);

        var bossRow = as(boss, () -> customers.transferCandidates(boss, candidateCustomerId)).stream()
                .filter(c -> c.userId() == userId).findFirst().orElseThrow();
        assertThat(bossRow.roleCode()).isEqualTo(SALES);
        assertThat(bossRow.teamId()).isEqualTo(store.salesTeamB());

        // 实际转让与候选一致：A 主管转给该人（规范岗位在 B）必须被拒；老板可以转（B 团队）
        long customerId = capture(salesA1, phone()).customerId();
        assertError(() -> as(leadA, () -> customers.transfer(leadA, customerId,
                new TransferCommand(userId, "调岗"), 1, null)), TRANSFER_TARGET_INVALID);
        var moved = as(boss, () -> customers.transfer(boss, customerId,
                new TransferCommand(userId, "老板调整"), 1, null));
        assertThat(moved.customer().getOwnerUserId()).isEqualTo(userId);
        assertThat(moved.customer().getTeamId()).isEqualTo(store.salesTeamB());
    }

    @Test
    void ownerOptionsKeepInactiveOwnersOfHistoricCustomers() {
        capture(salesA1, phone());
        FakeSystemDirectory.INSTANCE.setUserActive(salesA1.userId(), false);
        try {
            var options = as(boss, () -> customers.ownerOptions(boss));
            assertThat(options).extracting(CustomerService.OwnerOption::userId)
                    .contains(salesA1.userId());
        } finally {
            FakeSystemDirectory.INSTANCE.setUserActive(salesA1.userId(), true);
        }
    }

    // ===================== 工具 =====================

    @Test
    void regionKeywordSearchKeepsTheOriginalOwnerAndOrganizationScope() {
        long mine = capture(salesA1, phone()).customerId();
        long colleague = capture(salesA2, phone()).customerId();
        long anotherStore = capture(otherStoreSales, phone()).customerId();
        UpdateCommand region = new UpdateCommand("地区检索客户", "", "湖北省", "宜昌市", "测试地区搜索");
        as(salesA1, () -> customers.update(salesA1, mine, region, 1, null));
        as(salesA2, () -> customers.update(salesA2, colleague, region, 1, null));
        as(otherStoreSales, () -> customers.update(otherStoreSales, anotherStore, region, 1, null));

        assertThat(as(salesA1, () -> customers.page(salesA1, page(), "宜昌")).getList())
                .extracting(CustomerDO::getId).containsExactly(mine);
        assertThat(as(boss, () -> customers.page(boss, page(), "湖北")).getList())
                .extracting(CustomerDO::getId).contains(mine, colleague).doesNotContain(anotherStore);
        assertThat(as(salesA1, () -> customers.page(salesA1, page(), "不存在的地区")).getList()).isEmpty();
    }

    @Test
    void capturePersistsTheFullAddressAndSamePhoneKeepsTheOriginalAddress() {
        var address=CrmRegionsTest.address();
        String phone=phone();
        var input=new CaptureCommand("地址客户",phone,"建两层","朋友介绍",address.province(),address.city(),
                address.district(),address.town(),address.village(),address.regionCode(),address.addressDetail());
        var created=as(salesA1,()->leads.capture(salesA1,input,"address-"+phone));
        var saved=as(salesA1,()->customers.get(salesA1,created.customerId()).customer());
        assertThat(saved.getRegionCode()).isEqualTo(address.regionCode());
        assertThat(saved.getTown()).isEqualTo(address.town());
        assertThat(saved.getVillage()).isEqualTo(address.village());
        assertThat(saved.getAddressDetail()).isEqualTo(address.addressDetail());
        assertThat(as(salesA1,()->customers.page(salesA1,page(),address.village())).getList())
                .extracting(CustomerDO::getId).contains(saved.getId());
        var linked=as(salesA2,()->leads.capture(salesA2,new CaptureCommand("地址客户",phone,"新需求","到店咨询",
                address.province(),address.city(),address.district(),address.town(),address.village(),address.regionCode(),"另一处地址"),null));
        assertThat(linked.customerId()).isEqualTo(saved.getId());
        assertThat(as(salesA1,()->customers.get(salesA1,saved.getId()).customer()).getAddressDetail()).isEqualTo(address.addressDetail());
        assertThat(as(salesA1,()->leads.capture(salesA1,input,"address-"+phone)).customerId()).isEqualTo(saved.getId());
    }

    @Test
    void editChangesTheDetailedAddressAndOldClientsDoNotEraseSavedVillage() {
        var address=CrmRegionsTest.address();
        var created=as(salesA1,()->leads.capture(salesA1,new CaptureCommand("编辑地址",phone(),"两层","朋友介绍",
                address.province(),address.city(),address.district(),address.town(),address.village(),address.regionCode(),address.addressDetail()),null));
        var edited=as(salesA1,()->customers.update(salesA1,created.customerId(),new UpdateCommand("编辑地址",null,
                address.province(),address.city(),"三层",address.district(),address.town(),address.village(),address.regionCode(),"二组8号"),1,null));
        assertThat(edited.customer().getAddressDetail()).isEqualTo("二组8号");
        var legacy=as(salesA1,()->customers.update(salesA1,created.customerId(),new UpdateCommand("姓名修改",null,
                address.province(),address.city(),"三层"),2,null));
        assertThat(legacy.customer().getVillage()).isEqualTo(address.village());
        assertThat(legacy.customer().getAddressDetail()).isEqualTo("二组8号");
        var response=cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Customer.of(legacy.customer());
        assertThat(response.regionCode()).isEqualTo(address.regionCode());
        assertThat(response.addressDetail()).isEqualTo("二组8号");
    }

    private CaptureResult capture(PostSnapshot post, String phone) {
        return as(post, () -> leads.capture(post, new CaptureCommand("陈先生", phone, "两层乡墅，先了解方案和费用", "视频号咨询"), null));
    }

    @Test
    void followFiltersApplyBeforePaginationAndRespectStoreAndOwnerVisibility() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        long overdue = capture(salesA1, phone()).customerId();
        as(salesA1, () -> customers.follow(salesA1, overdue, new FollowCommand("已电话联系", today), null));
        followJdbc.update("UPDATE fr_customer SET next_visit_date=?,update_time=now()-interval '1 day' WHERE id=?", today.minusDays(1), overdue);
        for (int i = 0; i < 50; i++) {
            long id = capture(salesA1, phone()).customerId();
            as(salesA1, () -> customers.follow(salesA1, id, follow("电话已沟通"), null));
        }
        long elsewhere = capture(otherStoreSales, phone()).customerId();
        var first = new PageParam().setPageNo(1).setPageSize(50);
        assertThat(as(salesA1, () -> customers.page(salesA1, first, null)).getList()).extracting(CustomerDO::getId).doesNotContain(overdue);
        for (var reader : List.of(salesA1, boss)) {
            var due = as(reader, () -> customers.page(reader, first, null, null, null, FollowFilter.DUE));
            assertThat(due.getTotal()).isEqualTo(1L);
            assertThat(due.getList()).extracting(CustomerDO::getId).containsExactly(overdue).doesNotContain(elsewhere);
        }
        assertThat(as(salesA2, () -> customers.page(salesA2, first, null, null, null, FollowFilter.DUE)).getList()).isEmpty();
        as(salesA1, () -> customers.follow(salesA1, overdue, new FollowCommand("约电话回访", today.plusDays(1), "FOLLOW", today), null));
        assertThat(as(boss, () -> customers.page(boss, first, null, null, null, FollowFilter.APPOINTMENTS)).getList()).extracting(CustomerDO::getId).containsExactly(overdue);
        assertThat(as(boss, () -> customers.page(boss, first, null, null, null, FollowFilter.VISITED)).getList()).isEmpty();
        as(salesA1, () -> customers.follow(salesA1, overdue, new FollowCommand("到店接待", null, "VISIT", today), null));
        as(otherStoreSales, () -> customers.follow(otherStoreSales, elsewhere, new FollowCommand("他店接待", null, "VISIT", today), null));
        var visited = as(boss, () -> customers.page(boss, first, null, null, null, FollowFilter.VISITED));
        assertThat(visited.getTotal()).isEqualTo(1L);
        assertThat(visited.getList()).extracting(CustomerDO::getId).containsExactly(overdue);
    }

    @Test
    void stablePageRefusesToAppendAfterSameTotalMembershipChangeBeyondTwoHundred() {
        long base = PHONES.addAndGet(1000);
        followJdbc.update("""
                INSERT INTO fr_customer (id, company_id, team_id, owner_user_id, name, phone, source, status, update_time)
                SELECT ? + seq, ?, ?, ?, '分页合成客户', (? + seq)::text, '测试', 'REGISTERED',
                       now() - seq * interval '1 second'
                FROM generate_series(1,251) AS seq
                """, base, store.orgId(), store.salesTeamA(), salesA1.userId(), base);
        long otherCustomer = capture(otherStoreSales, phone()).customerId();
        var first = new PageParam().setPageNo(1).setPageSize(200);
        var fifth = new PageParam().setPageNo(5).setPageSize(50);
        var sixth = new PageParam().setPageNo(6).setPageSize(50);
        var initial = as(salesA1, () -> customers.stablePage(salesA1, first, null, null, null, null, null));
        assertThat(initial.changed()).isFalse();
        assertThat(initial.page().getTotal()).isEqualTo(251L);
        assertThat(initial.page().getList()).extracting(CustomerDO::getId).hasSize(200).doesNotContain(otherCustomer);
        var more = as(salesA1, () -> customers.stablePage(salesA1, fifth, null, null, null, null, initial.prefix()));
        assertThat(more.changed()).isFalse();
        assertThat(more.page().getList()).extracting(CustomerDO::getId).hasSize(50).startsWith(base + 201);
        var finalRow = as(salesA1, () -> customers.stablePage(salesA1, sixth, null, null, null, null, more.prefix()));
        assertThat(finalRow.page().getList()).extracting(CustomerDO::getId).containsExactly(base + 251);

        var wrongRole = as(boss, () -> customers.stablePage(boss, fifth, null, null, null, null, initial.prefix()));
        assertThat(wrongRole.changed()).isTrue();
        assertThat(wrongRole.page().getList()).isEmpty();
        var alien = as(otherStoreSales, () -> customers.stablePage(otherStoreSales, fifth, null, null, null, null, initial.prefix()));
        assertThat(alien.changed()).isTrue();
        assertThat(alien.page().getList()).isEmpty();

        followJdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", salesA2.userId(), base + 5);
        long replacement = capture(salesA2, phone()).customerId();
        followJdbc.update("UPDATE fr_customer SET owner_user_id=? WHERE id=?", salesA1.userId(), replacement);
        var legacy = as(salesA1, () -> customers.page(salesA1, fifth, null));
        assertThat(legacy.getTotal()).isEqualTo(251L);
        assertThat(legacy.getList()).extracting(CustomerDO::getId).startsWith(base + 201);
        var refused = as(salesA1, () -> customers.stablePage(salesA1, fifth, null, null, null, null, initial.prefix()));
        assertThat(refused.changed()).isTrue();
        assertThat(refused.page().getList()).isEmpty();
        var refreshed = as(salesA1, () -> customers.stablePage(salesA1, first, null, null, null, null, null));
        assertThat(refreshed.page().getList()).extracting(CustomerDO::getId).contains(replacement).doesNotContain(base + 5);
    }

    private static FollowCommand follow(String note) {
        return new FollowCommand(note, null);
    }

    private static SchemeCommand budget(BigDecimal width) {
        return new SchemeCommand("BUDGET", "参考预算", "{}", width, new BigDecimal("16"), 2, new BigDecimal("400"), 68_000_000L);
    }

    private static PageParam page() {
        return new PageParam().setPageNo(1).setPageSize(100);
    }

    private static String phone() {
        return String.valueOf(PHONES.incrementAndGet());
    }

    /** 以某个工作身份执行：组织范围过滤按当前工作身份生效 */
    private <T> T as(PostSnapshot post, java.util.function.Supplier<T> action) {
        currentPost.use(post);
        try {
            return action.get();
        } finally {
            currentPost.clear();
        }
    }

}
