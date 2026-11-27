package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverMapper;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionMapper;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.franchise.crm.dal.LeadDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.TodoProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 加盟协同域待办（M2 设计 §10）：按当前岗位即时计算，不落库。
 * key 格式「franchise:事项:编号[:版本]」；可办理（actionable=true）排在前。
 */
@Component
public class FranchiseTodoProvider implements TodoProvider {

    private static final ZoneId CUSTOMER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String COMMISSION_PAGE = "/pages/workflow/commission/index";
    private static final String GROWTH_PAGE = "/pages/growth/index";

    private final CustomerService customers;
    private final LeadService leads;
    private final CommissionService commissions;
    private final CommissionAccess access;
    private final CommissionSubmissionMapper submissionMapper;
    private final CommissionHandoverMapper handoverMapper;

    public FranchiseTodoProvider(CustomerService customers, LeadService leads, CommissionService commissions,
                                 CommissionAccess access, CommissionSubmissionMapper submissionMapper,
                                 CommissionHandoverMapper handoverMapper) {
        this.customers = customers;
        this.leads = leads;
        this.commissions = commissions;
        this.access = access;
        this.submissionMapper = submissionMapper;
        this.handoverMapper = handoverMapper;
    }

    @Override
    public String domain() {
        return FranchiseRoles.DOMAIN;
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        List<TodoItem> items = new ArrayList<>();
        if (FranchiseRoles.has(post, FranchiseRoles.SALES)) {
            customerTodos(post, items);
            staleCustomerTodos(post, items);
        }
        pendingLeadTodos(post, items);
        commissionTodos(post, items);
        return items;
    }

    /** 提醒业务负责人：新客户登记两天没人首次跟进，或跟进中 15 天没有新记录也没约下次（约了日期的由到期待办提醒）。 */
    private void staleCustomerTodos(PostSnapshot post, List<TodoItem> items) {
        if (!post.lead() || !FranchiseRoles.atStore(post)) return;
        for (CustomerDO customer : customers.listStale(post, LocalDate.now(CUSTOMER_ZONE))) {
            boolean fresh = customer.getLastFollowAt() == null;
            items.add(item("stale:" + customer.getId(), "「" + customer.getName() + "」"
                            + (fresh ? "登记 2 天仍未首次跟进" : "超过 15 天没有跟进记录"),
                    fresh ? "REGISTERED" : "FOLLOWING", null, true, flowPage(customer),
                    fresh ? customer.getCreateTime() : customer.getLastFollowAt()));
        }
    }

    // ===================== 业务线客户待办 =====================

    /** 客户跟进一律进八步页的当前步骤；升级前“记录跟进”的客户工作区入口不再作为待办落点。 */
    private static String flowPage(CustomerDO customer) {
        return "/pages/customer-flow/index?customerId=" + customer.getId();
    }

    private void customerTodos(PostSnapshot post, List<TodoItem> items) {
        for (CustomerDO customer : customers.listAwaitingFirstFollow(post)) {
            items.add(item("first-follow:" + customer.getId(), "「" + customer.getName() + "」待首次跟进",
                    "REGISTERED", null, true, flowPage(customer), customer.getCreateTime()));
        }
        LocalDate today = LocalDate.now(CUSTOMER_ZONE);
        for (CustomerDO customer : customers.listDueVisits(post, today)) {
            items.add(item("visit:" + customer.getId() + ":" + customer.getNextVisitDate(),
                    "「" + customer.getName() + "」约定跟进已到期", "FOLLOWING", null, true,
                    flowPage(customer), atStartOfDay(customer.getNextVisitDate())));
        }
        for (CustomerDO customer : customers.listReconsulted(post)) {
            items.add(item("reconsult:" + customer.getId(), "「" + customer.getName() + "」流失客户再次咨询，待确认是否恢复",
                    "LOST", null, true, followupPage(customer), customer.getUpdateTime()));
        }
        // 设计岗位退役后委托走外部委托登记（八步 05/06 步入口），不再按参考预算催办内部设计委托。
    }

    // ===================== 待关联客资 =====================

    private void pendingLeadTodos(PostSnapshot post, List<TodoItem> items) {
        for (LeadDO lead : leads.listPending(post)) {
            boolean handleable = CustomerAccess.canHandleLead(post, lead);
            String title = handleable ? "「" + lead.getName() + "」客资待关联原客户"
                    : "「" + lead.getName() + "」客资等待负责人关联";
            items.add(item("lead-link:" + lead.getId() + ":" + lead.getRevision(), title,
                    "LINK_REQUIRED", null, handleable, GROWTH_PAGE, lead.getCreateTime()));
        }
    }

    // ===================== 委托待办 =====================

    private void commissionTodos(PostSnapshot post, List<TodoItem> items) {
        for (CommissionDO commission : commissions.listVisible(post)) {
            String key = Long.toString(commission.getId());
            OffsetDateTime since = commission.getUpdateTime();
            String customer = commission.getCustomerName();
            if (ExternalCommissionService.external(commission)) {
                if (access.canWrite(post, commission) && Set.of(CommissionDO.STATUS_EXTERNAL_PENDING,
                        CommissionDO.STATUS_EXTERNAL_READY, CommissionDO.STATUS_HANDOVER_RETURNED).contains(commission.getStatus())) {
                    String title = CommissionDO.STATUS_EXTERNAL_PENDING.equals(commission.getStatus()) ? "外部设计成果待登记"
                            : CommissionDO.STATUS_HANDOVER_RETURNED.equals(commission.getStatus()) ? "工程退回，待补充移交资料" : "外部成果已登记，待移交工程";
                    items.add(item("external-entrust:"+key+":"+commission.getRevision(), "「"+customer+"」"+title,
                            commission.getStatus(),null,true,"/pages/external-entrust/index?customerId="+commission.getCustomerId(),since));
                }
                if (CommissionDO.STATUS_HANDOVER_SUBMITTED.equals(commission.getStatus())) handoverReviewTodo(post,commission,items);
                continue;
            }
            // 设计岗位退役（V20261127.1201）：LEGACY 委托不可再推进、无人审批/下发，
            // 除可 convert-legacy 转外部流程外不再产生任何待办；external 分支保留。
        }
    }

    private void handoverReviewTodo(PostSnapshot post, CommissionDO commission, List<TodoItem> items) {
        Integer version = commission.getHandoverVersion();
        CommissionHandoverDO handover = handoverMapper.selectByCommission(commission.getId()).stream()
                .filter(h -> version != null && version.equals(h.getVersion()))
                .findFirst().orElse(null);
        if (handover == null) {
            return;
        }
        if (access.isHandoverManager(post, commission.getCompanyId(), handover.getManagerUserId())) {
            items.add(item("handover-review:" + commission.getId() + ":" + version,
                    "「" + commission.getCustomerName() + "」移交资料待核查接收", "HANDOVER_SUBMITTED",
                    null, true, ExternalCommissionService.external(commission) ? externalHandoverPage(commission.getId())
                            : commissionPage(commission.getId(), "receive"), handover.getCreateTime()));
        } else if (access.canWrite(post, commission)) {
            items.add(item("handover-waiting:" + commission.getId() + ":" + version,
                    "「" + commission.getCustomerName() + "」移交审查中", "HANDOVER_SUBMITTED",
                    null, false, ExternalCommissionService.external(commission) ? externalHandoverPage(commission.getId())
                            : commissionPage(commission.getId(), "progress"), handover.getCreateTime()));
        }
    }

    private static String commissionPage(long commissionId, String view) {
        return COMMISSION_PAGE + "?view=" + view + "&commissionId=" + commissionId;
    }

    private static String externalHandoverPage(long commissionId) {
        return "/pages/external-handover/index?commissionId="+commissionId;
    }

    private static String followupPage(CustomerDO customer) {
        return "/pages/workflow/index?view=customer&id=" + customer.getId() + "&tab=1";
    }

    private static OffsetDateTime atStartOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(CUSTOMER_ZONE).toOffsetDateTime();
    }

    private static TodoItem item(String key, String title, String status, Long amountCents,
                                 boolean actionable, String appPath, OffsetDateTime since) {
        return new TodoItem(FranchiseRoles.DOMAIN, FranchiseRoles.DOMAIN + ":" + key, title, status,
                amountCents, actionable, null, appPath, since);
    }

}
