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

    private static final String CUSTOMERS_PAGE = "/pages/customers/index";
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
        }
        pendingLeadTodos(post, items);
        commissionTodos(post, items);
        return items;
    }

    // ===================== 业务线客户待办 =====================

    private void customerTodos(PostSnapshot post, List<TodoItem> items) {
        for (CustomerDO customer : customers.listAwaitingFirstFollow(post)) {
            items.add(item("first-follow:" + customer.getId(), "「" + customer.getName() + "」首次跟进",
                    "REGISTERED", null, true, CUSTOMERS_PAGE, customer.getCreateTime()));
        }
        LocalDate today = LocalDate.now();
        for (CustomerDO customer : customers.listDueVisits(post, today)) {
            items.add(item("visit:" + customer.getId() + ":" + customer.getNextVisitDate(),
                    "「" + customer.getName() + "」预约回访已到期", "FOLLOWING", null, true,
                    CUSTOMERS_PAGE, atStartOfDay(customer.getNextVisitDate())));
        }
        Set<Long> withCommission = Set.copyOf(commissions.listVisible(post).stream()
                .map(CommissionDO::getCustomerId).toList());
        customers.listWritableWithBudget(post).stream()
                .filter(customer -> !withCommission.contains(customer.getId()))
                .forEach(customer -> items.add(item("submit-commission:" + customer.getId(),
                        "「" + customer.getName() + "」已存参考预算，待提交设计委托", "BUDGET_READY",
                        null, true, COMMISSION_PAGE + "?view=submit&customerId=" + customer.getId(), customer.getUpdateTime())));
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
        boolean designAdminLine = FranchiseRoles.DESIGN_ADMIN.equals(post.roleCode());
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
            if(commission.getDispatchSeq()!=null && !commissions.readyToDispatch(commission)) {
                items.add(item("design-finance-anomaly:"+key+":"+commission.getSubmissionVersion(),
                        "「"+customer+"」下发后设计资金净额不足，请核对退款/退回",commission.getStatus(),null,
                        false,commissionPage(commission.getId(), "progress"),since));
            }
            var simulated=commissions.simulatedPayment(commission);
            if(simulated!=null&&!simulated.paid()){
                if(access.canWrite(post,commission))items.add(item("simulation-pay:"+key+":"+commission.getSubmissionVersion(),"「"+customer+"」委托待模拟付款登记","SIMULATION_PENDING",null,true,commissionPage(commission.getId(),"pay"),since));
                continue;
            }
            switch (commission.getStatus()) {
                case CommissionDO.STATUS_FEE_PENDING -> {
                    if (designAdminLine) {
                        break; // 设计行政看不到费用审批（字段裁剪同口径）
                    }
                    if (FranchiseRoles.atStore(post) && FranchiseRoles.isBoss(post)) {
                        items.add(item("fee-approve:" + key, "「" + customer + "」委托费用待审批",
                                "FEE_PENDING", latestTotalCents(commission.getId()), true, commissionPage(commission.getId(), "progress"), since));
                    } else {
                        items.add(item("fee-pending:" + key, "「" + customer + "」委托费用审批中",
                                "FEE_PENDING", null, false, commissionPage(commission.getId(), "progress"), since));
                    }
                }
                case CommissionDO.STATUS_FEE_RETURNED -> {
                    if (access.canWrite(post, commission)) {
                        items.add(item("fee-returned:" + key + ":" + commission.getSubmissionVersion(),
                                "「" + customer + "」委托费用被退回，待修改重提", "FEE_RETURNED", null,
                                true, commissionPage(commission.getId(), "submit"), since));
                    }
                }
                case CommissionDO.STATUS_DESIGNING -> {
                    if (designAdminLine && access.isDesignAdminOf(post, commission)
                            && commissions.readyToComplete(commission)) {
                        items.add(item("design-complete:" + key + ":" + commission.getSubmissionVersion(),
                                "「" + customer + "」首款已就绪，确认设计完成", "DESIGNING", null,
                                true, commissionPage(commission.getId(), "complete"), since));
                    }
                }
                case CommissionDO.STATUS_DESIGN_COMPLETED -> {
                    if (designAdminLine && access.isDesignAdminOf(post, commission)
                            && commissions.readyToDispatch(commission)) {
                        items.add(item("design-dispatch:" + key + ":" + commission.getSubmissionVersion(),
                                "「" + customer + "」尾款已就绪，下发成果", "DESIGN_COMPLETED", null,
                                true, commissionPage(commission.getId(), "dispatch"), since));
                    } else if (!commissions.readyToDispatch(commission) && access.canWrite(post, commission)) {
                        items.add(item("tail-follow:" + key, "「" + customer + "」设计已完成，跟进尾款支付",
                                "DESIGN_COMPLETED", null, true, commissionPage(commission.getId(), "progress"), since));
                    }
                }
                case CommissionDO.STATUS_DISPATCHED -> {
                    if (access.canWrite(post, commission)) {
                        items.add(item("handover-prepare:" + key, "「" + customer + "」成果已下发，收图并移交",
                                "DISPATCHED", null, true, commissionPage(commission.getId(), "handover"), since));
                    }
                }
                case CommissionDO.STATUS_HANDOVER_SUBMITTED -> handoverReviewTodo(post, commission, items);
                case CommissionDO.STATUS_HANDOVER_RETURNED -> {
                    if (access.canWrite(post, commission)) {
                        items.add(item("handover-returned:" + key + ":" + commission.getHandoverVersion(),
                                "「" + customer + "」移交被退回，待补充资料", "HANDOVER_RETURNED", null,
                                true, commissionPage(commission.getId(), "handover"), since));
                    }
                }
                default -> {
                    // ACCEPTED 无待办
                }
            }
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

    private Long latestTotalCents(long commissionId) {
        List<CommissionSubmissionDO> submissions = submissionMapper.selectByCommission(commissionId);
        return submissions.isEmpty() ? null : submissions.get(0).getTotalCents();
    }

    private static OffsetDateTime atStartOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(java.time.ZoneOffset.ofHours(8)).toOffsetDateTime();
    }

    private static TodoItem item(String key, String title, String status, Long amountCents,
                                 boolean actionable, String appPath, OffsetDateTime since) {
        return new TodoItem(FranchiseRoles.DOMAIN, FranchiseRoles.DOMAIN + ":" + key, title, status,
                amountCents, actionable, null, appPath, since);
    }

}
