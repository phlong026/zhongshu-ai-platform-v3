package cn.iocoder.yudao.module.franchise.crm;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerFollowDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerFollowMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;

/**
 * 客户：查看、跟进与预约回访、编辑、参考方案与预算版本、责任转交（小程序 business.js write / reassign）。
 * 每个写命令推进客户 revision 并记一条命令事件；追加型命令（跟进、方案）以读取时的版本做乐观锁。
 */
@Service
public class CustomerService {

    public static final String AGGREGATE = "customer";
    private static final int PAYLOAD_MAX = 64 * 1024;
    private static final Set<String> SCHEME_KINDS = Set.of(CustomerSchemeDO.KIND_BUDGET, CustomerSchemeDO.KIND_PLAN, CustomerSchemeDO.KIND_FACADE);

    public record Detail(CustomerDO customer, List<CustomerFollowDO> follows, List<CustomerSchemeDO> schemes) {
    }

    public record FollowCommand(String note, LocalDate nextVisitDate) {
    }

    public record UpdateCommand(String name, String wechat, String province, String city, String summary) {
    }

    public record SchemeCommand(String kind, String title, String payload, BigDecimal width, BigDecimal depth,
                                Integer floors, BigDecimal area, Long totalCents) {
    }

    public record TransferCommand(long targetUserId, String reason) {
    }

    private final CustomerMapper customerMapper;
    private final CustomerFollowMapper followMapper;
    private final CustomerSchemeMapper schemeMapper;
    private final StaffPostService staffPostService;
    private final FranchiseCommands commands;

    public CustomerService(CustomerMapper customerMapper, CustomerFollowMapper followMapper, CustomerSchemeMapper schemeMapper,
                           StaffPostService staffPostService, FranchiseCommands commands) {
        this.customerMapper = customerMapper;
        this.followMapper = followMapper;
        this.schemeMapper = schemeMapper;
        this.staffPostService = staffPostService;
        this.commands = commands;
    }

    // ===================== 查询 =====================

    public Detail get(PostSnapshot post, long customerId) {
        CustomerDO customer = requireReadable(post, customerId);
        return new Detail(customer, followMapper.selectByCustomer(customerId), schemeMapper.selectByCustomer(customerId));
    }

    public PageResult<CustomerDO> page(PostSnapshot post, PageParam page, String keyword) {
        return customerMapper.selectPage(page, CustomerAccess.visibleTo(post), keyword);
    }

    public CustomerDO requireReadable(PostSnapshot post, long customerId) {
        CustomerDO customer = customerMapper.selectById(customerId);
        if (customer == null || !CustomerAccess.canRead(post, customer)) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        return customer;
    }

    /** 当前岗位可办理的客户；可见但不能办理时报「不能办理」，不可见时报「不存在」 */
    public CustomerDO requireWritable(PostSnapshot post, long customerId) {
        CustomerDO customer = customerMapper.selectById(customerId);
        if (customer == null || !CustomerAccess.canRead(post, customer) && !CustomerAccess.canWrite(post, customer)) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        if (!CustomerAccess.canWrite(post, customer)) {
            throw exception(CUSTOMER_FORBIDDEN);
        }
        return customer;
    }

    public CustomerSchemeDO latestScheme(long customerId, String kind) {
        return schemeMapper.selectLatest(customerId, kind);
    }

    /** 当前岗位可办理、待首次跟进的客户 */
    public List<CustomerDO> listAwaitingFirstFollow(PostSnapshot post) {
        return customerMapper.selectVisible(CustomerAccess.writableBy(post).andThen(
                q -> q.eq(CustomerDO::getStatus, CustomerDO.STATUS_REGISTERED)));
    }

    /** 当前岗位可办理、预约回访已到期（含今天）的客户 */
    public List<CustomerDO> listDueVisits(PostSnapshot post, LocalDate today) {
        return customerMapper.selectDueVisits(CustomerAccess.writableBy(post), today);
    }

    /** 当前岗位可办理、已保存参考预算的客户（供业务域判断是否该提交委托） */
    public List<CustomerDO> listWritableWithBudget(PostSnapshot post) {
        List<CustomerDO> writable = customerMapper.selectVisible(CustomerAccess.writableBy(post));
        Set<Long> withBudget = Set.copyOf(schemeMapper.selectCustomerIdsWithBudget(writable.stream().map(CustomerDO::getId).toList()));
        return writable.stream().filter(c -> withBudget.contains(c.getId())).toList();
    }

    // ===================== 命令 =====================

    @Transactional(rollbackFor = Exception.class)
    public Detail follow(PostSnapshot post, long customerId, FollowCommand command, String idempotencyKey) {
        CustomerDO customer = requireWritable(post, customerId);
        FollowCommand input = new FollowCommand(CrmInputs.note(command.note()), command.nextVisitDate());
        if (commands.replayed(post, idempotencyKey, input).isEmpty()) {
            if (input.nextVisitDate() != null && input.nextVisitDate().isBefore(LocalDate.now())) {
                throw exception(VISIT_DATE_INVALID);
            }
            followMapper.insert(new CustomerFollowDO().setCompanyId(post.orgId()).setCustomerId(customerId)
                    .setNote(input.note()).setNextVisitDate(input.nextVisitDate())
                    .setAuthorUserId(post.userId()).setAuthorPostId(post.postId()));
            long revision = customer.getRevision();
            Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> u
                    .set(CustomerDO::getStatus, CustomerDO.STATUS_FOLLOWING)
                    .set(CustomerDO::getLastFollowAt, OffsetDateTime.now())
                    .set(CustomerDO::getNextVisitDate, input.nextVisitDate())));
            commands.record(post, AGGREGATE, customerId, revision + 1, "customer-follow", input, idempotencyKey);
        }
        return get(post, customerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Detail update(PostSnapshot post, long customerId, UpdateCommand command, long revision, String idempotencyKey) {
        requireWritable(post, customerId);
        UpdateCommand input = normalize(command);
        if (commands.replayed(post, idempotencyKey, input).isEmpty()) {
            Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> u
                    .set(CustomerDO::getName, input.name())
                    .set(CustomerDO::getWechat, input.wechat())
                    .set(CustomerDO::getProvince, input.province())
                    .set(CustomerDO::getCity, input.city())
                    .set(CustomerDO::getSummary, input.summary())));
            commands.record(post, AGGREGATE, customerId, revision + 1, "customer-update", input, idempotencyKey);
        }
        return get(post, customerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public CustomerSchemeDO saveScheme(PostSnapshot post, long customerId, SchemeCommand command, String idempotencyKey) {
        CustomerDO customer = requireWritable(post, customerId);
        SchemeCommand input = normalize(command);
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return latestScheme(customerId, input.kind());
        }
        CustomerSchemeDO latest = latestScheme(customerId, input.kind());
        CustomerSchemeDO scheme = new CustomerSchemeDO().setCompanyId(post.orgId()).setCustomerId(customerId)
                .setKind(input.kind()).setVersion(latest == null ? 1 : latest.getVersion() + 1)
                .setTitle(input.title()).setPayload(input.payload())
                .setWidth(input.width()).setDepth(input.depth()).setFloors(input.floors()).setArea(input.area())
                .setTotalCents(input.totalCents()).setAuthorUserId(post.userId());
        schemeMapper.insert(scheme);
        long revision = customer.getRevision();
        Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> {
        }));
        commands.record(post, AGGREGATE, customerId, revision + 1, "customer-scheme",
                Map.of("kind", input.kind(), "version", scheme.getVersion()), idempotencyKey);
        return scheme;
    }

    /** 责任转交（人员变动时由老板或业务负责人办理）；客户团队随接手人改变 */
    @Transactional(rollbackFor = Exception.class)
    public Detail transfer(PostSnapshot post, long customerId, TransferCommand command, long revision, String idempotencyKey) {
        CustomerDO customer = requireReadable(post, customerId);
        if (!CustomerAccess.canTransfer(post, customer)) {
            throw exception(CUSTOMER_FORBIDDEN);
        }
        String reason = CrmInputs.note(command.reason());
        TransferCommand input = new TransferCommand(command.targetUserId(), reason);
        if (commands.replayed(post, idempotencyKey, input).isEmpty()) {
            StaffPostDO target = salesPostOf(post, input.targetUserId());
            if (target == null || Objects.equals(customer.getOwnerUserId(), target.getUserId())
                    || !isBoss(post) && !CustomerAccess.teamAccess(post, target.getTeamId())) {
                throw exception(TRANSFER_TARGET_INVALID);
            }
            Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> u
                    .set(CustomerDO::getOwnerUserId, target.getUserId())
                    .set(CustomerDO::getTeamId, target.getTeamId())));
            commands.record(post, AGGREGATE, customerId, revision + 1, "customer-transfer",
                    Map.of("fromUserId", customer.getOwnerUserId(), "toUserId", target.getUserId(), "reason", reason),
                    idempotencyKey);
        }
        return get(post, customerId);
    }

    /** 接手人在本组织的有效业务岗位（业务员优先） */
    private StaffPostDO salesPostOf(PostSnapshot post, long userId) {
        return staffPostService.listActivePosts(userId).stream()
                .filter(p -> p.getOrgId() == post.orgId())
                .filter(p -> SALES.equals(p.getRoleCode()) || SALES_LEAD.equals(p.getRoleCode()))
                .min((a, b) -> Boolean.compare(SALES_LEAD.equals(a.getRoleCode()), SALES_LEAD.equals(b.getRoleCode())))
                .orElse(null);
    }

    private static UpdateCommand normalize(UpdateCommand c) {
        String name = StrUtil.trim(c.name());
        String wechat = StrUtil.trimToNull(c.wechat());
        String province = StrUtil.trimToNull(c.province());
        String city = StrUtil.trimToNull(c.city());
        String summary = StrUtil.trimToNull(c.summary());
        if (StrUtil.isBlank(name) || name.length() > 64 || tooLong(wechat, 64) || tooLong(province, 32)
                || tooLong(city, 32) || tooLong(summary, 500)) {
            throw exception(CUSTOMER_INPUT_INVALID);
        }
        return new UpdateCommand(name, wechat, province, city, summary);
    }

    private static SchemeCommand normalize(SchemeCommand c) {
        String kind = StrUtil.trim(c.kind());
        String title = StrUtil.trim(c.title());
        if (kind == null || !SCHEME_KINDS.contains(kind) || StrUtil.isBlank(title) || title.length() > 128
                || tooLong(c.payload(), PAYLOAD_MAX) || c.totalCents() != null && c.totalCents() < 0) {
            throw exception(SCHEME_INVALID);
        }
        if (!CustomerSchemeDO.KIND_BUDGET.equals(kind)) {
            return new SchemeCommand(kind, title, c.payload(), null, null, null, null, c.totalCents());
        }
        if (!positive(c.width(), "999.99") || !positive(c.depth(), "999.99") || !positive(c.area(), "99999.99")
                || c.floors() == null || c.floors() < 1 || c.floors() > 20) {
            throw exception(SCHEME_INVALID);
        }
        return c;
    }

    private static boolean positive(BigDecimal value, String max) {
        return value != null && value.signum() > 0 && value.compareTo(new BigDecimal(max)) <= 0 && value.stripTrailingZeros().scale() <= 2;
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

}
