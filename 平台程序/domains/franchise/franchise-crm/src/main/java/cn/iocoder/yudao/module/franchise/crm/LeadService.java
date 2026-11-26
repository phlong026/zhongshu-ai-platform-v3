package cn.iocoder.yudao.module.franchise.crm;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;

/**
 * 客资：登记即建客户或关联原客户（小程序 growth.js capture）；原客户登记人无权看到时等负责人关联（linkExisting）。
 */
@Service
@Slf4j
public class LeadService {

    public static final String AGGREGATE = "lead";
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    /** 登记结果：CREATED 新建客户；LINKED 关联到登记人可见的原客户；LINK_REQUIRED 等负责人关联 */
    public enum CaptureOutcome { CREATED, LINKED, LINK_REQUIRED }

    public record CaptureCommand(String name, String phone, String need, String source,
                                 String province, String city, String district, String town, String village,
                                 String regionCode, String addressDetail) {
        public CaptureCommand(String name, String phone, String need, String source) {
            this(name,phone,need,source,null,null,null,null,null,null,null);
        }
        CrmAddress address() { return CrmAddress.normalize(province,city,district,town,village,regionCode,addressDetail); }
    }

    public record CaptureResult(long leadId, Long customerId, CaptureOutcome outcome) {
    }

    private final LeadMapper leadMapper;
    private final CustomerMapper customerMapper;
    private final FranchiseCommands commands;
    private final JdbcTemplate jdbcTemplate;
    /** 老客户再次咨询要进八步时间线；只有装配了八步服务的进程才写。 */
    @jakarta.annotation.Resource
    private org.springframework.beans.factory.ObjectProvider<cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowService> workflows;

    @Autowired
    public LeadService(LeadMapper leadMapper, CustomerMapper customerMapper, FranchiseCommands commands,
                       JdbcTemplate jdbcTemplate) {
        this.leadMapper = leadMapper;
        this.customerMapper = customerMapper;
        this.commands = commands;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Compatibility constructor for focused domain tests that do not exercise publication attribution. */
    public LeadService(LeadMapper leadMapper, CustomerMapper customerMapper, FranchiseCommands commands) {
        this(leadMapper, customerMapper, commands, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public CaptureResult capture(PostSnapshot post, CaptureCommand command, String idempotencyKey) {
        if (!atStore(post) || !has(post, SALES)) {
            throw exception(STORE_SALES_POST_REQUIRED);
        }
        CaptureCommand input = normalize(command);
        validatePublicationSource(post, input.source());
        var replay = commands.replayed(post, idempotencyKey, input);
        if (replay.isPresent()) {
            return resultOf(requireLead(replay.get().getAggregateId()));
        }
        if (leadMapper.existsPending(input.phone())) {
            throw exception(LEAD_DUPLICATE);
        }
        LeadDO lead = new LeadDO().setId(IdWorker.getId()).setCompanyId(post.orgId()).setTeamId(post.teamId())
                .setName(input.name()).setPhone(input.phone()).setNeed(input.need()).setSource(input.source())
                .setRegistrarUserId(post.userId()).setRegistrarPostId(post.postId()).setRevision(1L);
        input.address().apply(lead);
        CustomerDO existing = customerMapper.selectByPhone(input.phone());
        CustomerDO linked = null;
        try {
            if (existing == null) {
                CustomerDO customer = newCustomer(post, input, lead.getId());
                customerMapper.insert(customer);
                commands.record(post, CustomerService.AGGREGATE, customer.getId(), 1, "customer-create", lead.getId(), null);
                lead.setStatus(LeadDO.STATUS_CONVERTED).setConversion(LeadDO.CONVERSION_DIRECT).setCustomerId(customer.getId());
            } else if (CustomerAccess.canAutoLink(post, existing)) {
                lead.setStatus(LeadDO.STATUS_CONVERTED).setConversion(LeadDO.CONVERSION_EXISTING).setCustomerId(existing.getId());
                linked = existing;
            } else {
                lead.setStatus(LeadDO.STATUS_LINK_REQUIRED);
            }
            leadMapper.insert(lead);
        } catch (DuplicateKeyException e) { // 同一手机号被并发登记
            log.warn("[capture][company={} user={}] 同手机号客资并发登记", post.orgId(), post.userId());
            throw exception(STATE_VERSION_CONFLICT);
        }
        commands.record(post, AGGREGATE, lead.getId(), 1, "lead-capture", input, idempotencyKey);
        consult(post, linked, lead);
        return resultOf(lead);
    }

    /** 待关联客资：老板看全部；负责人看管理范围；登记人看自己登记的 */
    public List<LeadDO> listPending(PostSnapshot post) {
        return leadMapper.selectPending(query -> {
            if (isBoss(post)) {
                return;
            }
            if (SALES_LEAD.equals(post.roleCode()) && post.teamId() == null) {
                return;
            }
            if (SALES_LEAD.equals(post.roleCode())) {
                query.and(q -> q.eq(LeadDO::getRegistrarUserId, post.userId()).or().in(LeadDO::getTeamId, post.managedTeamIds()));
                return;
            }
            query.eq(LeadDO::getRegistrarUserId, post.userId());
        });
    }

    /** 负责人或老板把待关联客资关联到同手机号原客户 */
    @Transactional(rollbackFor = Exception.class)
    public LeadDO link(PostSnapshot post, long leadId, long revision, String idempotencyKey) {
        LeadDO lead = requireLead(leadId);
        if (!CustomerAccess.canHandleLead(post, lead)) {
            throw exception(LEAD_NOT_FOUND);
        }
        if (commands.replayed(post, idempotencyKey, revision).isPresent()) {
            return lead;
        }
        requirePending(lead);
        CustomerDO customer = customerMapper.selectByPhone(lead.getPhone());
        if (customer == null || !CustomerAccess.canLinkTo(post, customer)) {
            throw exception(LEAD_LINK_TARGET_FORBIDDEN);
        }
        Revisions.requireUpdated(leadMapper.updateByRevision(leadId, revision, u -> u
                .set(LeadDO::getStatus, LeadDO.STATUS_CONVERTED)
                .set(LeadDO::getConversion, LeadDO.CONVERSION_LINKED)
                .set(LeadDO::getCustomerId, customer.getId())));
        commands.record(post, AGGREGATE, leadId, revision + 1, "lead-link", revision, idempotencyKey);
        consult(post, customer, leadMapper.selectById(leadId));
        return leadMapper.selectById(leadId);
    }

    /** 作废待关联客资：登记人本人或能关联它的人（新流程中登记人本身无权处理原客户，所以加上负责人与老板） */
    @Transactional(rollbackFor = Exception.class)
    public LeadDO invalidate(PostSnapshot post, long leadId, String note, long revision, String idempotencyKey) {
        LeadDO lead = requireLead(leadId);
        boolean registrar = lead.getRegistrarUserId() == post.userId();
        if (!registrar && !CustomerAccess.canHandleLead(post, lead)) {
            throw exception(LEAD_NOT_FOUND);
        }
        String reason = CrmInputs.note(note);
        if (commands.replayed(post, idempotencyKey, reason).isPresent()) {
            return lead;
        }
        requirePending(lead);
        Revisions.requireUpdated(leadMapper.updateByRevision(leadId, revision, u -> u
                .set(LeadDO::getStatus, LeadDO.STATUS_INVALID)
                .set(LeadDO::getNote, reason)));
        commands.record(post, AGGREGATE, leadId, revision + 1, "lead-invalid", reason, idempotencyKey);
        return leadMapper.selectById(leadId);
    }

    /**
     * 客资关联到老客户（登记人可见时自动关联，或负责人关联）：在客户的八步时间线上留一条“再次咨询”，
     * 并把下次跟进日期提到今天；已流失的客户只记下再次咨询，等负责人恢复。
     */
    private void consult(PostSnapshot post, CustomerDO customer, LeadDO lead) {
        if (workflows == null || customer == null || lead == null) return;
        var service = workflows.getIfAvailable();
        if (service != null) service.recordConsult(post, customer.getId(), lead.getId(), lead.getNeed(), lead.getSource());
    }

    /** A publication source is accepted only when it resolves to a real, published record in this organization. */
    void validatePublicationSource(PostSnapshot post, String source) {
        final String prefix = "内容发布:";
        if (source == null || !source.startsWith(prefix)) return;
        String rawId = source.substring(prefix.length());
        if (!rawId.matches("\\d{1,19}")) throw invalidPublicationSource();
        final long publicationId;
        try { publicationId = Long.parseLong(rawId); }
        catch (NumberFormatException ex) { throw invalidPublicationSource(); }
        Boolean published = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM fr_content_publication "
                        + "WHERE company_id = ? AND id = ? AND status = 'published')",
                Boolean.class, post.orgId(), publicationId);
        if (!Boolean.TRUE.equals(published)) throw invalidPublicationSource();
    }

    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalidPublicationSource() {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(
                LEAD_INPUT_INVALID.getCode(), "所选视频未发布或不可用，请重新选择已发布的视频来源");
    }

    private static CaptureCommand normalize(CaptureCommand c) {
        String name = StrUtil.trim(c.name());
        String phone = StrUtil.trim(c.phone());
        String need = StrUtil.trim(c.need());
        String source = StrUtil.trim(c.source());
        if (StrUtil.isBlank(name) || name.length() > 64 || phone == null || !PHONE.matcher(phone).matches()
                || StrUtil.isBlank(need) || need.length() > 500 || StrUtil.isBlank(source) || source.length() > 64) {
            throw exception(LEAD_INPUT_INVALID);
        }
        var address=c.address();
        return new CaptureCommand(name,phone,need,source,address.province(),address.city(),address.district(),
                address.town(),address.village(),address.regionCode(),address.addressDetail());
    }

    private static CustomerDO newCustomer(PostSnapshot post, CaptureCommand input, long leadId) {
        return input.address().apply(new CustomerDO().setId(IdWorker.getId()).setCompanyId(post.orgId()).setTeamId(post.teamId())
                .setOwnerUserId(post.userId()).setName(input.name()).setPhone(input.phone()).setSource(input.source())
                .setSummary(input.need()).setStatus(CustomerDO.STATUS_REGISTERED).setOriginLeadId(leadId).setRevision(1L));
    }

    private LeadDO requireLead(long leadId) {
        LeadDO lead = leadMapper.selectById(leadId);
        if (lead == null) {
            throw exception(LEAD_NOT_FOUND);
        }
        return lead;
    }

    private static void requirePending(LeadDO lead) {
        if (!LeadDO.STATUS_LINK_REQUIRED.equals(lead.getStatus())) {
            throw exception(LEAD_NOT_PENDING);
        }
    }

    private static CaptureResult resultOf(LeadDO lead) {
        CaptureOutcome outcome = switch (lead.getStatus()) {
            case LeadDO.STATUS_LINK_REQUIRED -> CaptureOutcome.LINK_REQUIRED;
            default -> LeadDO.CONVERSION_DIRECT.equals(lead.getConversion()) ? CaptureOutcome.CREATED : CaptureOutcome.LINKED;
        };
        return new CaptureResult(lead.getId(), lead.getCustomerId(), outcome);
    }

    // ===================== P1 批3：客资批量导入（Web /fr/customers） =====================

    /**
     * 导入路径：新建客户（owner=操作者）+ CONVERTED(direct) lead。@Transactional 独立事务。
     *
     * <p>组织与 owner 一律从 post 派生（不接受调用方传入），并在此校验 boss/owner——
     * 这是写事务入口的第二道防线（控制器已校验一次）。
     */
    public record ImportNewCustomerResult(long leadId, Long customerId) { }

    @Transactional(rollbackFor = Exception.class)
    public ImportNewCustomerResult importNewCustomer(PostSnapshot post, String name,
                                                      String phone, String need, String source,
                                                      String idempotencyKey) {
        long orgId = requireImportOperator(post);
        var replay = commands.replayed(post, idempotencyKey,
                new CaptureCommand(name, phone, need, source));
        if (replay.isPresent()) {
            LeadDO existing = requireLead(replay.get().getAggregateId());
            return new ImportNewCustomerResult(existing.getId(), existing.getCustomerId());
        }
        CaptureCommand input = normalize(new CaptureCommand(name, phone, need, source));
        validatePublicationSource(post, input.source());
        long leadId = IdWorker.getId();
        CustomerDO customer = new CustomerDO().setId(IdWorker.getId()).setCompanyId(orgId)
                .setTeamId(post.teamId()).setOwnerUserId(post.userId()).setName(input.name())
                .setPhone(input.phone()).setSource(input.source()).setSummary(input.need())
                .setStatus(CustomerDO.STATUS_REGISTERED).setOriginLeadId(leadId).setRevision(1L);
        customerMapper.insert(customer);
        LeadDO lead = new LeadDO().setId(leadId).setCompanyId(orgId).setTeamId(post.teamId())
                .setName(input.name()).setPhone(input.phone()).setNeed(input.need()).setSource(input.source())
                .setRegistrarUserId(post.userId()).setRegistrarPostId(post.postId()).setRevision(1L)
                .setStatus(LeadDO.STATUS_CONVERTED).setConversion(LeadDO.CONVERSION_DIRECT)
                .setCustomerId(customer.getId());
        leadMapper.insert(lead);
        commands.record(post, CustomerService.AGGREGATE, customer.getId(), 1, "customer-create",
                phone, null);
        commands.record(post, AGGREGATE, leadId, 1, "lead-import", input, idempotencyKey);
        return new ImportNewCustomerResult(leadId, customer.getId());
    }

    /**
     * 导入路径：同手机号已存在客户 → 仅创建 LINK_REQUIRED lead（不覆盖客户/不自动关联）。
     * @Transactional 独立事务；org/owner 从 post 派生并校验 boss/owner。
     */
    public record ImportLinkRequiredResult(long leadId) { }

    @Transactional(rollbackFor = Exception.class)
    public ImportLinkRequiredResult importLinkRequiredLead(PostSnapshot post, String name,
                                                            String phone, String need, String source,
                                                            String idempotencyKey) {
        long orgId = requireImportOperator(post);
        var replay = commands.replayed(post, idempotencyKey,
                new CaptureCommand(name, phone, need, source));
        if (replay.isPresent()) {
            return new ImportLinkRequiredResult(replay.get().getAggregateId());
        }
        CaptureCommand input = normalize(new CaptureCommand(name, phone, need, source));
        validatePublicationSource(post, input.source());
        LeadDO lead = new LeadDO().setId(IdWorker.getId()).setCompanyId(orgId).setTeamId(post.teamId())
                .setName(input.name()).setPhone(input.phone()).setNeed(input.need()).setSource(input.source())
                .setRegistrarUserId(post.userId()).setRegistrarPostId(post.postId()).setRevision(1L)
                .setStatus(LeadDO.STATUS_LINK_REQUIRED);
        leadMapper.insert(lead);
        commands.record(post, AGGREGATE, lead.getId(), 1, "lead-import", input, idempotencyKey);
        return new ImportLinkRequiredResult(lead.getId());
    }

    /** 导入写入口防线：仅 boss/owner，且返回其组织（组织不接受外部传入）。 */
    private static long requireImportOperator(PostSnapshot post) {
        if (post == null || !FranchiseRoles.isBoss(post)) {
            throw exception(LEAD_IMPORT_FORBIDDEN);
        }
        return post.orgId();
    }

}
