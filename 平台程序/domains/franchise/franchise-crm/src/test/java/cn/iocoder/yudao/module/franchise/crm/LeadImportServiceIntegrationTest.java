package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@TestPropertySource(properties = "platform.legacy-design-bridge.enabled=false")
@Import({LeadService.class, CustomerService.class, cn.iocoder.yudao.module.franchise.crm.CrmOrgScope.class,
        cn.iocoder.yudao.module.franchise.org.FranchiseCommands.class, CommandLogImpl.class,
        LeadImportService.class})
class LeadImportServiceIntegrationTest extends FranchisePgIntegrationTest {

    private static final AtomicLong PHONES =
            new AtomicLong(13_800_000_000L + System.nanoTime() % 100_000_000L);

    @Resource
    private LeadImportService importService;
    @Resource
    private LeadService leadService;

    private long orgId;
    private PostSnapshot boss;

    /** 同一测试方法内复用同一手机号（跨方法不冲突）。 */
    private static String phone() {
        return String.valueOf(PHONES.incrementAndGet()).substring(0, 11);
    }

    private static String batchId() {
        return "b" + System.nanoTime();
    }

    private static LeadImportService.Row row(int line, String name, String phone) {
        return new LeadImportService.Row(line, name, phone, "装修需求", "门店自然流量");
    }

    private int pendingCount() {
        return leadService.listPending(boss).size();
    }

    @BeforeEach
    void setUp() {
        var store = fixture.store();
        orgId = store.orgId();
        boss = fixture.post(orgId, FranchiseRoles.BOSS, null);
        currentPost.use(boss);
    }

    @Test
    void precheckIsReadOnlyAndReportsOk() {
        var p = phone();
        var result = importService.precheck(boss, batchId(), List.of(row(1, "张三", p)));
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("OK"));
        assertThat(pendingCount()).isZero();
    }

    @Test
    void newPhoneCreatesCustomerWithOperatorAsOwner() {
        var result = importService.submit(boss, batchId(), List.of(row(1, "张三", phone())));
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.rows().get(0).customerId()).isNotNull();
    }

    @Test
    void samePhoneExistingCustomerCreatesLinkRequiredLead() {
        String p = phone();
        importService.submit(boss, batchId(), List.of(row(1, "张三", p)));
        var result2 = importService.submit(boss, batchId(), List.of(row(1, "李四", p)));
        assertThat(result2.failed()).isZero();
        assertThat(result2.rows()).singleElement().satisfies(r ->
                assertThat(r.note()).contains("已存在客户"));
    }

    @Test
    void samePhoneExistingPendingLeadIsSkipped() {
        String p = phone();
        importService.submit(boss, batchId(), List.of(row(1, "张三", p)));
        importService.submit(boss, batchId(), List.of(row(1, "李四", p)));
        var result3 = importService.submit(boss, batchId(), List.of(row(1, "王五", p)));
        assertThat(result3.skipped()).isEqualTo(1);
    }

    @Test
    void retrySameOperatorAllSkipped() {
        String p = phone();
        String batch = batchId();
        var first = importService.submit(boss, batch, List.of(row(1, "张三", p)));
        assertThat(first.created()).isEqualTo(1);
        // 同一批次重试：replay 命中 → SKIPPED，不重复建客户/待关联
        var second = importService.submit(boss, batch, List.of(row(1, "张三", p)));
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
    }

    @Test
    void differentOperatorSameBatchCreatesPendingLinkNotSkip() {
        var owner = fixture.post(orgId, FranchiseRoles.OWNER, null);
        String p = phone();
        String batch = batchId();
        importService.submit(boss, batch, List.of(row(1, "张三", p)));
        // 幂等键按用户隔离：换操作者不是 replay；同号已有客户 → 需求要求落待关联（不跳过）
        var second = importService.submit(owner, batch, List.of(row(1, "张三", p)));
        assertThat(second.created()).isEqualTo(1);
        assertThat(second.skipped()).isZero();
        assertThat(second.rows().get(0).action()).isEqualTo(LeadImportService.ACTION_LINK_REQUIRED);
    }

    @Test
    void sameNameNewBatchStillCreatesPendingLink() {
        String p = phone();
        importService.submit(boss, batchId(), List.of(row(1, "张三", p)));
        // 新批次 + 同名：不得用"同名"捷径跳过，必须按需求落待关联
        var second = importService.submit(boss, batchId(), List.of(row(1, "张三", p)));
        assertThat(second.created()).isEqualTo(1);
        assertThat(second.rows().get(0).action()).isEqualTo(LeadImportService.ACTION_LINK_REQUIRED);
    }

    @Test
    void crossOrgPhoneIsolationCreatesSeparateCustomer() {
        var otherOrg = fixture.store();
        var otherBoss = fixture.post(otherOrg.orgId(), FranchiseRoles.BOSS, null);
        String p = phone();
        importService.submit(boss, batchId(), List.of(row(1, "张三", p)));
        currentPost.use(otherBoss);
        var resultB = importService.submit(otherBoss, batchId(),
                List.of(row(1, "李四B", p)));
        assertThat(resultB.created()).isEqualTo(1);
        // B 组织未命中 A 组织客户：note 为 null（无"已存在客户"备注）且 customerId 为新建
        assertThat(resultB.rows().get(0).note()).isNull();
        assertThat(resultB.rows().get(0).customerId()).isNotNull();
    }

    @Test
    void batchCapRejectsEmptyAndOverLimit() {
        var empty = catchThrowableOfType(() -> importService.precheck(boss, batchId(), List.of()), ServiceException.class);
        assertThat(empty).isNotNull();
        assertThat(empty.getCode()).isEqualTo(400);
        var rows = new ArrayList<LeadImportService.Row>();
        for (int i = 1; i <= 501; i++) rows.add(row(i, "员工" + i, String.valueOf(139_000_000_00L + i)));
        var over = catchThrowableOfType(() -> importService.submit(boss, batchId(), rows), ServiceException.class);
        assertThat(over).isNotNull();
        assertThat(over.getCode()).isEqualTo(400);
    }

    @Test
    void invalidRowIsRejected() {
        var result = importService.precheck(boss, batchId(), List.of(row(1, "", phone())));
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(0).code()).isEqualTo(400);
    }

    @Test
    void submitPerRowTransactionFailureDoesNotAffectOtherRows() {
        var result = importService.submit(boss, batchId(), List.of(
                row(1, "张三", phone()),
                row(2, "", phone()),
                row(3, "李四", phone())));
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void crossOrgPendingLeadDoesNotSkip() {
        var otherOrg = fixture.store();
        var otherBoss = fixture.post(otherOrg.orgId(), FranchiseRoles.BOSS, null);
        String p = phone();
        // A 组织：同号客户 + 待关联客资
        importService.submit(boss, batchId(), List.of(row(1, "张三", p)));
        importService.submit(boss, batchId(), List.of(row(1, "李四", p)));
        assertThat(importService.precheck(boss, batchId(), List.of(row(1, "王五", p)))
                .rows().get(0).status()).isEqualTo("SKIPPED");
        // B 组织同号导入不得因 A 的待关联客资被跳过（组织隔离）
        currentPost.use(otherBoss);
        var resultB = importService.submit(otherBoss, batchId(),
                List.of(row(1, "王五B", p)));
        assertThat(resultB.created()).isEqualTo(1);
        assertThat(resultB.skipped()).isZero();
    }

    @Test
    void salesCannotImport() {
        var sales = fixture.post(orgId, FranchiseRoles.SALES, null);
        var ex = catchThrowableOfType(() -> importService.precheck(sales, batchId(),
                List.of(row(1, "张三", phone()))), ServiceException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_110_000_013);
    }

    @Test
    void longBatchIdKeepsRowKeysDistinct() {
        // Codex r2 P1 回归：64 字符 batchId 曾被 scopedKey 截断导致同批多行撞键。
        String batch = "b" + "x".repeat(63);
        var result = importService.submit(boss, batch, List.of(
                row(1, "张三", phone()),
                row(2, "李四", phone())));
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isZero();
    }

    @Test
    void sameKeyDifferentPayloadWritesNothing() {
        String p = phone();
        String batch = batchId();
        importService.submit(boss, batch, List.of(row(1, "张三", p)));
        int before = leadService.listPending(boss).size();
        // 同批次同行的内容被改过：幂等键复用必须拒绝，且不得写入任何新数据
        var second = importService.submit(boss, batch, List.of(row(1, "张三改", p)));
        assertThat(second.failed()).isEqualTo(1);
        assertThat(second.created()).isZero();
        assertThat(leadService.listPending(boss).size()).isEqualTo(before);
    }

    @Test
    void directWriteEntryRejectsSales() {
        var sales = fixture.post(orgId, FranchiseRoles.SALES, null);
        var ex = catchThrowableOfType(() -> leadService.importNewCustomer(sales,
                "张三", phone(), "需求", "来源", "lead-import:direct:1"), ServiceException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(1_110_000_013);
    }

    @Test
    void duplicateLineNumbersAreRejectedPerRow() {
        var rows = List.of(row(1, "张三", phone()), row(1, "李四", phone()));
        // 预检同样拒绝重复行号（不进入业务分支）
        var pre = importService.precheck(boss, batchId(), rows);
        assertThat(pre.failed()).isEqualTo(1);
        assertThat(pre.rows().get(1).code()).isEqualTo(400);
        // 提交：第一行成功，第二行行错，不因键碰撞产生额外写入
        var result = importService.submit(boss, batchId(), rows);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(1).code()).isEqualTo(400);
    }
}
