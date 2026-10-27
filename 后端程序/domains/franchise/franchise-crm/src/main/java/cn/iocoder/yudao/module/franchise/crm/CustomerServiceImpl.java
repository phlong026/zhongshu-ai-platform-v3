package cn.iocoder.yudao.module.franchise.crm;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.crm.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.command.CommandLog;
import cn.iocoder.yudao.module.platform.command.Revisions;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.*;

@Service
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    private static final Pattern MOBILE = Pattern.compile("1\\d{10}");
    private static final String AGGREGATE = "customer";

    @Resource
    private CustomerMapper customerMapper;
    @Resource
    private LeadMapper leadMapper;
    @Resource
    private FollowUpMapper followUpMapper;
    @Resource
    private CustomerPolicy policy;
    @Resource
    private CommandLog commandLog;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeadCaptureResult captureLead(PostSnapshot actor, LeadInput in) {
        if (!policy.isSales(actor)) {
            throw exception(ACTOR_NOT_SALES);
        }
        String name = StrUtil.trim(in.name());
        String need = StrUtil.trim(in.need());
        if (StrUtil.isBlank(name) || name.length() > 32 || in.phone() == null || !MOBILE.matcher(in.phone()).matches()
                || StrUtil.isBlank(need) || need.length() > 500) {
            throw exception(LEAD_INVALID);
        }
        String source = StrUtil.isBlank(in.source()) ? "直接来访" : StrUtil.maxLength(in.source().trim(), 29);

        CustomerDO existing = customerMapper.selectByPhone(actor.orgId(), in.phone());
        CustomerDO customer;
        String linkKind;
        try {
            if (existing != null) {
                Revisions.requireUpdated(customerMapper.bumpRevision(existing.getId(), existing.getRevision()));
                customer = existing.setRevision(existing.getRevision() + 1);
                linkKind = LeadDO.LINK_EXISTING_CUSTOMER;
            } else {
                customer = new CustomerDO().setCompanyId(actor.orgId()).setTeamId(actor.teamId())
                        .setOwnerUserId(actor.userId()).setName(name).setPhone(in.phone()).setNeed(need)
                        .setSource(source).setRevision(1L);
                customerMapper.insert(customer);
                linkKind = LeadDO.LINK_NEW_CUSTOMER;
            }
        } catch (DuplicateKeyException e) { // 同一手机号被并发登记
            log.warn("[captureLead][orgId={}] 同手机号客户并发登记冲突", actor.orgId());
            throw exception(STATE_VERSION_CONFLICT);
        }
        LeadDO lead = new LeadDO().setCompanyId(actor.orgId()).setTeamId(actor.teamId())
                .setRegistrarUserId(actor.userId()).setRegistrarPostId(actor.postId()).setCustomerId(customer.getId())
                .setLinkKind(linkKind).setName(name).setPhone(in.phone()).setNeed(need).setSource(source);
        leadMapper.insert(lead);
        record(actor, customer, existing == null ? "lead-capture" : "lead-link", Map.of("leadId", lead.getId(), "linkKind", linkKind));
        return new LeadCaptureResult(lead.getId(), customer.getId(), existing != null, customer.getOwnerUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long addFollowUp(PostSnapshot actor, long customerId, long expectedRevision, String note, LocalDate nextVisitDate) {
        CustomerDO customer = load(customerId);
        if (customer == null || !policy.canHandle(actor, customer)) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        if (StrUtil.isBlank(note) || note.trim().length() > 1000) {
            throw exception(FOLLOW_UP_INVALID);
        }
        Revisions.requireUpdated(customerMapper.bumpRevision(customerId, expectedRevision));
        FollowUpDO followUp = new FollowUpDO().setCompanyId(customer.getCompanyId()).setCustomerId(customerId)
                .setAuthorUserId(actor.userId()).setAuthorPostId(actor.postId())
                .setNote(note.trim()).setNextVisitDate(nextVisitDate);
        followUpMapper.insert(followUp);
        record(actor, customer.setRevision(expectedRevision + 1), "follow-up", Map.of("followUpId", followUp.getId()));
        return followUp.getId();
    }

    @Override
    public CustomerDetail getCustomer(PostSnapshot actor, long customerId) {
        CustomerDO customer = load(customerId);
        if (customer == null || !policy.canRead(actor, customer)) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        return new CustomerDetail(customer, leadMapper.selectByCustomer(customerId), followUpMapper.selectByCustomer(customerId));
    }

    @Override
    public PageResult<CustomerDO> pageCustomers(PostSnapshot actor, CustomerQuery query) {
        LambdaQueryWrapperX<CustomerDO> where = new LambdaQueryWrapperX<CustomerDO>()
                .eq(CustomerDO::getCompanyId, actor.orgId()); // 组织范围规则之外再显式限定
        if (!policy.isStoreManager(actor)) {
            if (!policy.isSales(actor)) {
                return PageResult.empty();
            }
            boolean leadOfWholeStore = FranchiseRoles.SALES_LEAD.equals(actor.roleCode()) && actor.teamId() == null;
            if (!leadOfWholeStore) {
                boolean lead = FranchiseRoles.SALES_LEAD.equals(actor.roleCode()) && !actor.managedTeamIds().isEmpty();
                where.and(w -> {
                    w.eq(CustomerDO::getOwnerUserId, actor.userId());
                    if (lead) {
                        w.or().in(CustomerDO::getTeamId, actor.managedTeamIds());
                    }
                });
            }
        }
        if (StrUtil.isNotBlank(query.keyword())) {
            String kw = query.keyword().trim();
            where.and(w -> w.like(CustomerDO::getName, kw).or().like(CustomerDO::getPhone, kw));
        }
        where.orderByDesc(CustomerDO::getUpdateTime).orderByDesc(CustomerDO::getId);
        PageParam page = new PageParam();
        page.setPageNo(Math.max(1, query.pageNo()));
        page.setPageSize(Math.min(100, Math.max(1, query.pageSize())));
        return customerMapper.selectPage(page, where);
    }

    @Override
    public CustomerDO requireHandleable(PostSnapshot actor, long customerId) {
        CustomerDO customer = load(customerId);
        if (customer == null || !policy.canHandle(actor, customer)) {
            throw exception(CUSTOMER_NOT_FOUND);
        }
        return customer;
    }

    private CustomerDO load(long customerId) {
        return customerMapper.selectById(customerId);
    }

    private void record(PostSnapshot actor, CustomerDO customer, String command, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        commandLog.append(new CommandEventDO().setDomain(FranchiseRoles.DOMAIN).setAggregateType(AGGREGATE)
                .setAggregateId(customer.getId()).setRevision(customer.getRevision()).setCommand(command)
                .setOrgId(actor.orgId()).setActorUserId(actor.userId()).setActorPostId(actor.postId())
                .setActorRoleCode(actor.roleCode()).setPayload(JsonUtils.toJsonString(body)));
    }

}
