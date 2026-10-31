package cn.iocoder.yudao.module.franchise.finance.todo;

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

import java.util.List;

/**
 * 财务域待办投影（实时查询，状态变化即消失）：跨端到账待确认仅 finance 系岗位可见。
 * key 前缀 fr-fin:（与 franchise 域既有待办区分）；无推送派送，纯实时计算。
 */
@Component
public class FinanceTodoProvider implements TodoProvider {

    public static final String DOMAIN = "fr-fin";
    private static final String COMMISSION_PAGE = "/pages/collaboration/index";

    @Resource
    private FinBankFlowMapper flowMapper;
    @Resource
    private FinBankConfirmMapper confirmMapper;

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        // 到账待确认：仅 finance/finance-lead；待匹配流水即事项
        if (!FranchiseRoles.FINANCE.equals(post.roleCode()) && !FranchiseRoles.FINANCE_LEAD.equals(post.roleCode())) {
            return List.of();
        }
        return flowMapper.selectByCompanyAndStatus(post.orgId(), "待匹配").stream()
                .filter(flow -> confirmMapper.selectCount(
                        new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<FinBankConfirmDO>()
                                .eq(FinBankConfirmDO::getBankFlowId, flow.getId())) == 0)
                .map(flow -> new TodoItem(DOMAIN, DOMAIN + ":bank-confirm:" + flow.getId(),
                        "跨端到账待确认 · " + flow.getBankRef(), "待匹配",
                        flow.getAmountCents(), true, null, COMMISSION_PAGE, flow.getCreateTime()))
                .toList();
    }

}
