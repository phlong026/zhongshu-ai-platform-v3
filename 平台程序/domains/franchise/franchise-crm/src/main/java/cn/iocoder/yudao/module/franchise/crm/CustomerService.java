package cn.iocoder.yudao.module.franchise.crm;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.CommissionQueryPort;
import cn.iocoder.yudao.module.franchise.api.SignedCustomerQuery;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerFollowDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerFollowMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final Set<String> SALES_STAGES = Set.of("NEW", "CONTACTED", "PROPOSAL", "QUOTED", "SIGNED");
    private static final Set<String> FOLLOW_OUTCOMES = Set.of("UNREACHED", "AWAITING_REPLY", "ARRIVED", "DEFERRED");

    public record Detail(CustomerDO customer, List<CustomerFollowDO> follows, List<CustomerSchemeDO> schemes) {
    }

    public record FollowCommand(String note, LocalDate nextVisitDate, String contactKind, LocalDate occurredOn,
                                @JsonInclude(JsonInclude.Include.NON_NULL) FollowChanges changes) {
        public FollowCommand(String note, LocalDate nextVisitDate, String contactKind, LocalDate occurredOn) {
            this(note, nextVisitDate, contactKind, occurredOn, null);
        }

        public FollowCommand(String note, LocalDate nextVisitDate) {
            this(note, nextVisitDate, null, null);
        }
    }

    public record FollowChanges(String salesStage, String outcome, boolean clearReminder) {}

    private record LegacyFollow(String note, LocalDate nextVisitDate) {}
    private record FollowRequest(long customerId,FollowCommand follow) {}

    public record UpdateCommand(String name, String wechat, String province, String city, String summary,
                                String district, String town, String village, String regionCode, String addressDetail) {
        public UpdateCommand(String name, String wechat, String province, String city, String summary) {
            this(name,wechat,province,city,summary,null,null,null,null,null);
        }
    }

    public record SchemeCommand(String kind, String title, String payload, BigDecimal width, BigDecimal depth,
                                Integer floors, BigDecimal area, Long totalCents) {
    }

    /** 内部方案命令（B2 板块 A / P-9 DTO 分离）：携带 designProjectId 与按 kind 保留的显式尺寸；旧 HTTP 接口的 SchemeCommand 不含关联列 */
    public record SchemeSaveCommand(String kind, String title, String payload, BigDecimal width, BigDecimal depth,
                                    Integer floors, BigDecimal area, Long totalCents, Long designProjectId) {
    }

    public record TransferCommand(long targetUserId, String reason) {
    }

    @Resource
    private ObjectProvider<CommissionQueryPort> commissionQueries;

    @Resource
    private ObjectProvider<SignedCustomerQuery> signedCustomerQueries;

    private void enrichCustomerProgress(PostSnapshot post, List<CustomerDO> rows) {
        if (rows.isEmpty()) return;
        List<Long> customerIds = rows.stream().map(CustomerDO::getId).toList();
        var signedQuery = signedCustomerQueries.getIfAvailable();
        if (signedQuery != null) {
            Set<Long> signed = signedQuery.signedCustomers(post.orgId(), customerIds);
            rows.forEach(row -> row.setContractSigned(signed.contains(row.getId())));
        }
        var commissionQuery = commissionQueries.getIfAvailable();
        if (commissionQuery == null) return;
        Map<Long, String> states = commissionQuery.customerDesignStatuses(post.orgId(), customerIds);
        rows.forEach(row -> row.setDesignStatus(states.get(row.getId())));
    }

    private final CustomerMapper customerMapper;
    private final CustomerFollowMapper followMapper;
    private final CustomerSchemeMapper schemeMapper;
    private final StaffPostService staffPostService;
    private final SystemDirectory systemDirectory;
    private final FranchiseCommands commands;

    public CustomerService(CustomerMapper customerMapper, CustomerFollowMapper followMapper, CustomerSchemeMapper schemeMapper,
                           StaffPostService staffPostService, SystemDirectory systemDirectory, FranchiseCommands commands) {
        this.customerMapper = customerMapper;
        this.followMapper = followMapper;
        this.schemeMapper = schemeMapper;
        this.staffPostService = staffPostService;
        this.systemDirectory = systemDirectory;
        this.commands = commands;
    }

    // ===================== 查询 =====================

    public Detail get(PostSnapshot post, long customerId) {
        CustomerDO customer = requireReadable(post, customerId);
        enrichCustomerProgress(post, List.of(customer));
        return new Detail(customer, followMapper.selectByCustomer(customerId), schemeMapper.selectByCustomer(customerId));
    }

    public PageResult<CustomerDO> page(PostSnapshot post, PageParam page, String keyword) {
        PageResult<CustomerDO> result = customerMapper.selectPage(page, CustomerAccess.visibleTo(post), keyword);
        enrichCustomerProgress(post, result.getList());
        return result;
    }

    /** Web 客资表格：在可见范围内追加状态/负责人筛选。 */
    public PageResult<CustomerDO> page(PostSnapshot post, PageParam page, String keyword,
                                       String status, Long ownerUserId) {
        return page(post, page, keyword, status, ownerUserId, null);
    }

    public PageResult<CustomerDO> page(PostSnapshot post, PageParam page, String keyword,
                                       String status, Long ownerUserId, CustomerMapper.FollowFilter followFilter) {
        PageResult<CustomerDO> result = customerMapper.selectPage(page, CustomerAccess.visibleTo(post), keyword, status, ownerUserId, followFilter);
        enrichCustomerProgress(post, result.getList());
        return result;
    }

    /** 负责人筛选名册：可见范围内出现过的负责人（含已停用账号，历史客资仍需按原负责人筛）。 */
    public List<OwnerOption> ownerOptions(PostSnapshot post) {
        List<Long> ids = customerMapper.selectOwnerIds(CustomerAccess.visibleTo(post));
        if (ids.isEmpty()) return List.of();
        Map<Long, SystemDirectory.UserBrief> users = systemDirectory.usersByIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(SystemDirectory.UserBrief::userId, u -> u));
        return ids.stream()
                .map(id -> {
                    SystemDirectory.UserBrief u = users.get(id);
                    // 用户已删除/尚未填写昵称时 name 为 null：统一空串，排序与前端展示都不再空指针。
                    return new OwnerOption(id, u == null || u.name() == null ? "" : u.name());
                })
                .sorted(java.util.Comparator.comparing(OwnerOption::name))
                .toList();
    }

    public record OwnerOption(long userId, String name) {
    }

    /**
     * 接手人候选：与 transfer 的校验完全同源——先按 {@link #salesPostOf} 选出规范岗位
     * （sales 优先于 sales-lead），再用该岗位的团队判断授权范围，避免"看到的候选点下去被拒"。
     */
    public List<TransferCandidate> transferCandidates(PostSnapshot post, long customerId) {
        // 与 transfer 同源：先把 transfer 的门禁原样过一遍（可读 ≠ 可转让），
        // 否则主管调岗后旧团队客户仍可读并返回候选，但每次转让都会被拒。
        CustomerDO customer = requireReadable(post, customerId);
        if (!CustomerAccess.canTransfer(post, customer)) {
            throw exception(CUSTOMER_FORBIDDEN);
        }
        java.util.LinkedHashMap<Long, TransferCandidate> byUser = new java.util.LinkedHashMap<>();
        for (String role : List.of("sales", "sales-lead")) {
            for (StaffPostDO postRow : staffPostService.listActivePostsInOrg(post.orgId(), role)) {
                long userId = postRow.getUserId();
                if (byUser.containsKey(userId) || userId == post.userId()
                        || userId == customer.getOwnerUserId()) continue;
                StaffPostDO canonical = salesPostOf(post, userId);
                if (canonical == null) continue;
                if (!isBoss(post) && !CustomerAccess.teamAccess(post, canonical.getTeamId())) continue;
                byUser.put(userId, new TransferCandidate(userId, null, canonical.getRoleCode(),
                        canonical.getTeamId(), null));
            }
        }
        if (byUser.isEmpty()) return List.of();
        Map<Long, SystemDirectory.UserBrief> users = systemDirectory.usersByIds(byUser.keySet()).stream()
                .collect(java.util.stream.Collectors.toMap(SystemDirectory.UserBrief::userId, u -> u));
        List<TransferCandidate> out = new java.util.ArrayList<>(byUser.size());
        for (TransferCandidate c : byUser.values()) {
            SystemDirectory.UserBrief user = users.get(c.userId());
            if (user == null || !user.active()) continue;
            out.add(new TransferCandidate(c.userId(), user.name(), c.roleCode(), c.teamId(),
                    c.teamId() == null ? "" : systemDirectory.deptName(c.teamId())));
        }
        return out;
    }

    public record TransferCandidate(long userId, String name, String roleCode, Long teamId, String teamName) {
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
        String kind = command.contactKind() == null ? "FOLLOW" : command.contactKind();
        if (!Set.of("FOLLOW", "VISIT").contains(kind)) throw exception(VISIT_DATE_INVALID);
        FollowChanges changes = command.changes();
        String stage = changes == null ? null : changes.salesStage();
        String outcome = changes == null ? null : changes.outcome();
        boolean clearReminder = changes != null && changes.clearReminder();
        boolean visit = "VISIT".equals(kind);
        boolean reached = !"UNREACHED".equals(outcome);
        if ((stage != null && !SALES_STAGES.contains(stage))
                || (outcome != null && !FOLLOW_OUTCOMES.contains(outcome))
                || (visit && !reached)
                || (!visit && "ARRIVED".equals(outcome))
                || (clearReminder && command.nextVisitDate() != null)) {
            throw exception(FOLLOW_INPUT_INVALID);
        }
        String note = outcome != null && StrUtil.isBlank(command.note()) ? "" : CrmInputs.note(command.note());
        FollowCommand input = new FollowCommand(note, command.nextVisitDate(), kind, command.occurredOn(), changes);
        var fingerprint = new FollowRequest(customerId,input);
        var previous = commands.previous(post,idempotencyKey,"customer-follow",customerId);
        if (previous.isPresent()) {
            var event = previous.get();
            // Older clients omitted occurredOn; previous servers hashed their local date into the command.
            Object legacy = null;
            var stored = JsonUtils.parseObject(event.getPayload(), Map.class);
            if (changes == null && !stored.containsKey("follow")) {
                if (!stored.containsKey("contactKind") && !stored.containsKey("occurredOn")) {
                    if ("FOLLOW".equals(kind) && input.occurredOn() == null)
                        legacy = new LegacyFollow(input.note(), input.nextVisitDate());
                } else {
                    var original = JsonUtils.parseObject(event.getPayload(), FollowCommand.class);
                    legacy = input;
                    if (input.occurredOn() == null && original.occurredOn() != null && event.getOccurredAt() != null) {
                        var instant = event.getOccurredAt().toInstant();
                        if (List.of(java.time.ZoneId.systemDefault(), java.time.ZoneId.of("Asia/Shanghai"), java.time.ZoneOffset.UTC)
                                .stream().anyMatch(zone -> instant.atZone(zone).toLocalDate().equals(original.occurredOn()))) {
                            legacy = new FollowCommand(input.note(), input.nextVisitDate(), kind, original.occurredOn());
                        }
                    }
                }
            }
            commands.replayed(post,idempotencyKey,fingerprint,legacy);
            return get(post,customerId);
        }
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        LocalDate occurred=input.occurredOn()==null?today:input.occurredOn();
        if(occurred.isAfter(today)||occurred.isBefore(LocalDate.of(2000,1,1))) throw exception(VISIT_DATE_INVALID);
        if (input.nextVisitDate() != null && input.nextVisitDate().isBefore(today)) throw exception(VISIT_DATE_INVALID);
        followMapper.insert(new CustomerFollowDO().setCompanyId(post.orgId()).setCustomerId(customerId)
                .setNote(input.note()).setNextVisitDate(input.nextVisitDate()).setContactKind(kind).setOccurredOn(occurred)
                .setOutcome(outcome)
                .setAuthorUserId(post.userId()).setAuthorPostId(post.postId()));
        long revision = customer.getRevision();
        Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> u
                .set(reached, CustomerDO::getStatus, CustomerDO.STATUS_FOLLOWING)
                .set(stage != null, CustomerDO::getSalesStage, stage)
                .set(stage == null && reached && "NEW".equals(customer.getSalesStage()), CustomerDO::getSalesStage, "CONTACTED")
                .set(visit, CustomerDO::getVisited, true)
                .set(CustomerDO::getLastFollowAt, OffsetDateTime.now())
                .set(input.nextVisitDate() != null || clearReminder, CustomerDO::getNextVisitDate, input.nextVisitDate())));
        commands.record(post, AGGREGATE, customerId, revision + 1, "customer-follow", fingerprint, idempotencyKey);
        return get(post, customerId);
    }

    public record BudgetConfirmation(long schemeId,String note) {}
    @Transactional(rollbackFor=Exception.class)
    public Detail confirmBudget(PostSnapshot post,long customerId,BudgetConfirmation input,String key) {
        var customer=customerMapper.lockById(customerId);
        if(customer==null||!CustomerAccess.canWrite(post,customer)) throw exception(CUSTOMER_FORBIDDEN);
        if(StrUtil.isBlank(input.note())||input.note().length()>500) throw exception(SCHEME_INVALID);
        if(commands.replayed(post,key,input).isPresent()) return get(post,customerId);
        var budget=latestScheme(customerId,"BUDGET");
        if(budget==null||budget.getId()!=input.schemeId()||budget.getConfirmedAt()!=null) throw exception(SCHEME_INVALID);
        schemeMapper.updateById(new CustomerSchemeDO().setId(budget.getId()).setConfirmedAt(OffsetDateTime.now()).setConfirmedByUserId(post.userId()).setConfirmationNote(input.note().trim()));
        long revision=customer.getRevision();
        Revisions.requireUpdated(customerMapper.updateByRevision(customerId,revision,u->{}));
        commands.record(post,AGGREGATE,customerId,revision+1,"customer-budget-confirm",input,key);
        return get(post,customerId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Detail update(PostSnapshot post, long customerId, UpdateCommand command, long revision, String idempotencyKey) {
        CustomerDO customer = requireWritable(post, customerId);
        UpdateCommand input = normalize(command);
        boolean replaceAddress = command.district()!=null || command.town()!=null || command.village()!=null
                || command.regionCode()!=null || command.addressDetail()!=null
                || !java.util.Objects.equals(input.province(),customer.getProvince())
                || !java.util.Objects.equals(input.city(),customer.getCity());
        if (commands.replayed(post, idempotencyKey, input).isEmpty()) {
            Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> u
                    .set(CustomerDO::getName, input.name())
                    .set(CustomerDO::getWechat, input.wechat())
                    .set(CustomerDO::getProvince, input.province())
                    .set(CustomerDO::getCity, input.city())
                    .set(replaceAddress, CustomerDO::getDistrict, input.district())
                    .set(replaceAddress, CustomerDO::getTown, input.town())
                    .set(replaceAddress, CustomerDO::getVillage, input.village())
                    .set(replaceAddress, CustomerDO::getRegionCode, input.regionCode())
                    .set(replaceAddress, CustomerDO::getAddressDetail, input.addressDetail())
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

    /**
     * B 端 AI 结果回写（B2 板块 A / P-3+P-9，franchise-design 经 CustomerSchemePort 调用）：
     * 客户行锁内重读完整客户（含 revision）并复核权限 → 锁内读取 latest 分配版本 →
     * 以锁内 revision 完成方案插入+客户推进+命令事件（防不同键并发同版本号撞唯一约束/持旧 revision 回滚）。
     * 幂等由调用方（advisory lock + replay/record）承担，本方法不走 FranchiseCommands 幂等分支
     * （saveScheme 的重放按全量命令校验却只记录 {kind,version}，同键重试哈希不匹配——Codex R1 认定的缺陷）。
     * 显式尺寸按 kind 保留（PLAN/FACADE 允许携带，旧 normalize 会清空）；范围校验与 BUDGET 同规。
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomerSchemeDO saveSchemeInternal(PostSnapshot post, long customerId, SchemeSaveCommand command) {
        return saveSchemeSnapshot(post,customerId,command,false);
    }

    /** Internal-only verified itemized snapshot; public manual scheme validation is unchanged. */
    @Transactional(rollbackFor=Exception.class)
    public CustomerSchemeDO saveItemizedBudgetSnapshot(PostSnapshot post,long customerId,SchemeSaveCommand command) {
        return saveSchemeSnapshot(post,customerId,command,true);
    }

    private static SchemeSaveCommand normalizeItemizedSnapshot(SchemeSaveCommand command) {
        if(!CustomerSchemeDO.KIND_BUDGET.equals(command.kind()))throw exception(SCHEME_INVALID);
        var validated=normalizeInternal(new SchemeSaveCommand("PLAN",command.title(),command.payload(),command.width(),command.depth(),command.floors(),command.area(),command.totalCents(),command.designProjectId()));
        JsonNode root;
        try {root=JsonUtils.parseObject(command.payload(),JsonNode.class);}
        catch(RuntimeException invalid){throw exception(SCHEME_INVALID);}
        if(root==null)throw exception(SCHEME_INVALID);
        var snapshot=root.path("itemizedBudget");
        if(!snapshot.path("model").asText().equals("ITEMIZED_V1")||!Set.of("COMPLETE","INCOMPLETE").contains(snapshot.path("completeness").asText())
                ||snapshot.path("completeness").asText().equals("COMPLETE")!= (validated.totalCents()!=null)
                ||(validated.totalCents()!=null&&!snapshot.path("totalCents").asText().equals(validated.totalCents().toString())))throw exception(SCHEME_INVALID);
        return new SchemeSaveCommand("BUDGET",validated.title(),validated.payload(),validated.width(),validated.depth(),validated.floors(),validated.area(),validated.totalCents(),validated.designProjectId());
    }

    private CustomerSchemeDO saveSchemeSnapshot(PostSnapshot post,long customerId,SchemeSaveCommand command,boolean itemized) {
        CustomerDO customer = customerMapper.lockById(customerId);
        if (customer == null || !CustomerAccess.canRead(post, customer) && !CustomerAccess.canWrite(post, customer)) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        if (!CustomerAccess.canWrite(post, customer)) {
            throw exception(CUSTOMER_FORBIDDEN);
        }
        SchemeSaveCommand input=itemized?normalizeItemizedSnapshot(command):normalizeInternal(command);
        CustomerSchemeDO latest = schemeMapper.selectLatest(customerId, input.kind());
        CustomerSchemeDO scheme = new CustomerSchemeDO().setCompanyId(post.orgId()).setCustomerId(customerId)
                .setKind(input.kind()).setVersion(latest == null ? 1 : latest.getVersion() + 1)
                .setTitle(input.title()).setPayload(input.payload())
                .setWidth(input.width()).setDepth(input.depth()).setFloors(input.floors()).setArea(input.area())
                .setTotalCents(input.totalCents()).setAuthorUserId(post.userId())
                .setDesignProjectId(input.designProjectId());
        schemeMapper.insert(scheme);
        long revision = customer.getRevision();
        boolean sentQuote = itemized && JsonUtils.parseObject(input.payload(), JsonNode.class).hasNonNull("sentQuote");
        Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> u
                .set(sentQuote && !"SIGNED".equals(customer.getSalesStage()), CustomerDO::getSalesStage, "QUOTED")
                .set(sentQuote, CustomerDO::getStatus, CustomerDO.STATUS_FOLLOWING)));
        var eventPayload = new java.util.LinkedHashMap<String, Object>();
        eventPayload.put("kind", input.kind());
        eventPayload.put("version", scheme.getVersion());
        if (input.designProjectId() != null) {
            eventPayload.put("designProjectId", input.designProjectId());
        }
        commands.record(post, AGGREGATE, customerId, revision + 1, "customer-scheme-ai", eventPayload, null);
        return scheme;
    }

    private static SchemeSaveCommand normalizeInternal(SchemeSaveCommand c) {
        String kind = StrUtil.trim(c.kind());
        String title = StrUtil.trim(c.title());
        if (kind == null || !SCHEME_KINDS.contains(kind) || StrUtil.isBlank(title) || title.length() > 128
                || tooLong(c.payload(), PAYLOAD_MAX) || c.totalCents() != null && c.totalCents() < 0) {
            throw exception(SCHEME_INVALID);
        }
        // PLAN/FACADE：显式尺寸按 kind 保留，但给定时必须合法（与 BUDGET 同规，防脏数据绕过 CHECK）
        if (!CustomerSchemeDO.KIND_BUDGET.equals(kind)) {
            if ((c.width() != null && !positive(c.width(), "999.99"))
                    || (c.depth() != null && !positive(c.depth(), "999.99"))
                    || (c.area() != null && !positive(c.area(), "99999.99"))
                    || c.floors() != null && (c.floors() < 1 || c.floors() > 20)) {
                throw exception(SCHEME_INVALID);
            }
            return new SchemeSaveCommand(kind, title, c.payload(), c.width(), c.depth(), c.floors(), c.area(),
                    c.totalCents(), c.designProjectId());
        }
        if (!positive(c.width(), "999.99") || !positive(c.depth(), "999.99") || !positive(c.area(), "99999.99")
                || c.floors() == null || c.floors() < 1 || c.floors() > 20) {
            throw exception(SCHEME_INVALID);
        }
        return new SchemeSaveCommand(kind, title, c.payload(), c.width(), c.depth(), c.floors(), c.area(),
                c.totalCents(), c.designProjectId());
    }

    /** 交易流对象校验（板块 B SalesAccess 契约）：存在+同组织，不收窄岗位 */
    public CustomerDO requireAccessible(PostSnapshot post, long customerId) {
        CustomerDO customer = customerMapper.selectById(customerId);
        if (customer == null || customer.getCompanyId() != post.orgId()) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        return customer;
    }

    /**
     * 方案升级 reference→formal（B2 板块 B / R3，幂等责任方=crm）：
     * 指纹={schemeId, targetStage:'formal'} 稳定（不依赖当前状态）；已 formal 幂等返回；
     * 授权=调用方已过 sales write 门+此处组织校验（boss 正向可用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomerSchemeDO promoteSchemeToFormal(PostSnapshot post, long schemeId, String idempotencyKey) {
        CustomerSchemeDO scheme = schemeMapper.selectById(schemeId);
        if (scheme == null || scheme.getCompanyId() != post.orgId()) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        var input = Map.of("schemeId", schemeId, "targetStage", "formal");
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return schemeMapper.selectById(schemeId);
        }
        if (!"formal".equals(scheme.getStage())) {
            schemeMapper.updateById(new CustomerSchemeDO().setId(schemeId).setStage("formal"));
            scheme.setStage("formal");
        }
        // revision 用雪花（审计惯例）：promote 与 save-to-customer 同聚合同 version，(聚合,revision) 唯一约束会撞
        commands.record(post, "fr_customer_scheme", schemeId, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                "scheme-promote-formal", input, idempotencyKey);
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
                    || target.getUserId() == post.userId()
                    || !isBoss(post) && !CustomerAccess.teamAccess(post, target.getTeamId())) {
                throw exception(TRANSFER_TARGET_INVALID);
            }
            Revisions.requireUpdated(customerMapper.updateByRevision(customerId, revision, u -> u
                    .set(CustomerDO::getOwnerUserId, target.getUserId())
                    .set(CustomerDO::getTeamId, target.getTeamId())));
            // 指纹与 replayed 的 input 同构（S1-11b：原 Map{fromUserId,toUserId,reason} 与 TransferCommand
            // 序列化不一致，同键同内容重放误报 IDEMPOTENCY_KEY_REUSED；payload=请求为域惯例，fromUserId
            // 可由转交前 revision 的 customer 行追溯）
            commands.record(post, AGGREGATE, customerId, revision + 1, "customer-transfer", input, idempotencyKey);
        }
        return get(post, customerId);
    }

    /** 接手人在本组织的有效业务岗位（业务员优先）；S1-11c：任职有效≠账号有效，停用账号不得被设为接手人 */
    private StaffPostDO salesPostOf(PostSnapshot post, long userId) {
        return staffPostService.listActivePosts(userId).stream()
                .filter(p -> p.getOrgId() == post.orgId())
                .filter(p -> SALES.equals(p.getRoleCode()) || SALES_LEAD.equals(p.getRoleCode()))
                .filter(p -> systemDirectory.usersByIds(java.util.List.of(p.getUserId())).stream()
                        .findFirst().map(SystemDirectory.UserBrief::active).orElse(false))
                .min((a, b) -> Boolean.compare(SALES_LEAD.equals(a.getRoleCode()), SALES_LEAD.equals(b.getRoleCode())))
                .orElse(null);
    }

    private static UpdateCommand normalize(UpdateCommand c) {
        String name = StrUtil.trim(c.name());
        String wechat = StrUtil.trimToNull(c.wechat());
        String province = StrUtil.trimToNull(c.province());
        String city = StrUtil.trimToNull(c.city());
        String summary = StrUtil.trimToNull(c.summary());
        if (StrUtil.isBlank(name) || name.length() > 64 || tooLong(wechat, 64) || tooLong(province, 64)
                || tooLong(city, 64) || tooLong(summary, 500)) {
            throw exception(CUSTOMER_INPUT_INVALID);
        }
        var address=CrmAddress.normalize(province,city,c.district(),c.town(),c.village(),c.regionCode(),c.addressDetail());
        return new UpdateCommand(name,wechat,address.province(),address.city(),summary,address.district(),
                address.town(),address.village(),address.regionCode(),address.addressDetail());
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


    /**
     * M01-T04：批量离职交接——将 fromUserId 在 orgId 内的全部在途客户转移给 toUserId。
     * fr_customer 无终态（CHECK 约束仅允许 REGISTERED/FOLLOWING，均为在途），全员可转；
     * 显式 set（Codex R1 P2-3：接收岗位无团队时 team_id 须写 NULL，实体 NOT_NULL 策略会跳列），
     * 逐行推进 revision（Codex R1 P2-2：堵旧版本并发写绕过）；历史跟进流水与经办记录不变。
     * @return 转移的客户数
     */
    @Transactional(rollbackFor = Exception.class)
    public int bulkTransferByOwner(long fromUserId, long toUserId, long orgId, long operatorUserId) {
        var receiverPost = findReceiverPost(toUserId, orgId);
        if (receiverPost == null) return 0;
        return customerMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CustomerDO>()
                .eq(CustomerDO::getOwnerUserId, fromUserId)
                .eq(CustomerDO::getCompanyId, orgId)
                .set(CustomerDO::getOwnerUserId, toUserId)
                .set(CustomerDO::getTeamId, receiverPost.getTeamId())
                .setSql("revision = revision + 1")
                .set(CustomerDO::getUpdateTime, OffsetDateTime.now()));
    }

    /**
     * M01-T04：域级接收资格（Codex R1 P1-1）——接收人在本组织须有可承接客户的岗位
     * （sales / sales-lead 继承线；boss/engineer/finance 无 canWrite 资格）。
     * @return 合格岗位（转移客户随其团队），无合格岗位返回 null
     */
    public StaffPostDO findReceiverPost(long toUserId, long orgId) {
        return staffPostService.listActivePosts(toUserId).stream()
                .filter(p2 -> p2.getOrgId() == orgId)
                .filter(p2 -> SALES.equals(p2.getRoleCode()) || SALES_LEAD.equals(p2.getRoleCode()))
                .findFirst().orElse(null);
    }

    /**
     * M01-T04：离职交接在途扫描——该负责人在本组织名下客户数（只读；无终态过滤，见 bulkTransferByOwner）。
     */
    public long countInFlightByOwner(long ownerUserId, long orgId) {
        return customerMapper.selectCount(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<CustomerDO>()
                .eq(CustomerDO::getOwnerUserId, ownerUserId)
                .eq(CustomerDO::getCompanyId, orgId));
    }


}
