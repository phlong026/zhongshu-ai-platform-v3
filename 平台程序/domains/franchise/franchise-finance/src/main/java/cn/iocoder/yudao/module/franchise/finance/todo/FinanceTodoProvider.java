package cn.iocoder.yudao.module.franchise.finance.todo;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankConfirmDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankConfirmMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankFlowDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankFlowMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.TodoProvider;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 财务域待办投影（实时查询，状态变化即消失）：跨端到账待确认 + 异常资金待关联（M08-T08 批次#34），
 * 仅 finance 系岗位可见。key 前缀 fr-fin:（与 franchise 域既有待办区分）；无推送派送，纯实时计算。
 */
@Component
public class FinanceTodoProvider implements TodoProvider {

    public static final String DOMAIN = "fr-fin";
    private static final String FLOWS_PAGE = "/pages/admin/finance-desk/index?view=flows";
    /** 对账视图（平台台账 tab 顶部四口径卡）：异常资金待关联的办理入口 */
    private static final String RECONCILE_PAGE = FLOWS_PAGE + "&tab=ledger";

    @Resource
    private FinBankFlowMapper flowMapper;
    @Resource
    private FinBankConfirmMapper confirmMapper;
    @Resource
    private FinAbnormalFundMapper abnormalFundMapper;

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        // 到账待确认 + 异常资金待关联：仅 finance/finance-lead
        if (!FranchiseRoles.FINANCE.equals(post.roleCode()) && !FranchiseRoles.FINANCE_LEAD.equals(post.roleCode())) {
            return List.of();
        }
        List<TodoItem> todos = new ArrayList<>(flowMapper.selectByCompanyAndStatus(post.orgId(), "待匹配").stream()
                .filter(flow -> confirmMapper.selectCount(
                        new LambdaQueryWrapperX<FinBankConfirmDO>()
                                .eq(FinBankConfirmDO::getBankFlowId, flow.getId())) == 0)
                .map(flow -> new TodoItem(DOMAIN, DOMAIN + ":bank-confirm:" + flow.getId(),
                        "跨端到账待确认 · " + flow.getBankRef(), "待匹配",
                        flow.getAmountCents(), true, null, FLOWS_PAGE, flow.getCreateTime()))
                .toList());
        // M08-T08：已核待关联的异常资金逐条投影（关联/作废后即消失）；待归月日期待核子集包含其中
        for (FinAbnormalFundDO fund : abnormalFundMapper.selectByCompany(
                post.orgId(), FinAbnormalFundDO.STATUS_PENDING_LINK, false)) {
            String title = "异常资金待关联 · " + fund.getFundDirection()
                    + (fund.getCounterparty() == null ? "" : " · " + fund.getCounterparty())
                    + (fund.getBusinessDate() == null ? " · 日期待核" : "");
            todos.add(new TodoItem(DOMAIN, DOMAIN + ":abnormal-link:" + fund.getId(), title, "待关联",
                    fund.getAmountCents(), true, null, RECONCILE_PAGE, fund.getCreateTime()));
        }
        return todos;
    }

}
