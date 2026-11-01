package cn.iocoder.yudao.module.franchise.sales.todo;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.TodoProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO.STATUS_WAIT_SIGN;

/**
 * 交易流待办投影（B9 任务，fr-sale: 前缀）：
 * contract-sign=write 岗（sales/sales-lead/boss）；stage-pending=collect 岗；
 * appPath 按岗位返回可访问路径（finance 系→finance-desk；sales 系/boss→交易流合同页，不放宽财务权限）。
 * 回款逾期提醒不建（蓝本无回款到期日，D25）。
 */
@Component
public class SalesTodoProvider implements TodoProvider {

    public static final String DOMAIN = "fr-sale";
    private static final String FLOWS_PAGE = "/pages/admin/finance-desk/index?view=flows";

    private final SaleContractMapper contracts;
    private final SalePaymentStageMapper stages;

    public SalesTodoProvider(SaleContractMapper contracts, SalePaymentStageMapper stages) {
        this.contracts = contracts;
        this.stages = stages;
    }

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        List<TodoItem> items = new ArrayList<>();
        boolean isWritePost = FranchiseRoles.has(post, FranchiseRoles.SALES) || FranchiseRoles.isBoss(post);
        boolean isCollectPost = isWritePost
                || FranchiseRoles.FINANCE.equals(post.roleCode()) || FranchiseRoles.FINANCE_LEAD.equals(post.roleCode());
        if (isWritePost) {
            contracts.selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<SaleContractDO>()
                            .eq(SaleContractDO::getCompanyId, post.orgId())
                            .eq(SaleContractDO::getStatus, STATUS_WAIT_SIGN))
                    .forEach(c -> items.add(new TodoItem(DOMAIN, DOMAIN + ":contract-sign:" + c.getId(),
                            "合同待签 · 客户#" + c.getCustomerId(), "待签订", c.getTotalCents(), true, null,
                            "/pages/workflow/index?view=contract&id=" + c.getCustomerId(), c.getCreateTime())));
        }
        if (isCollectPost) {
            boolean financePost = FranchiseRoles.FINANCE.equals(post.roleCode())
                    || FranchiseRoles.FINANCE_LEAD.equals(post.roleCode());
            stages.selectPendingByOrg(post.orgId()).stream()
                    .filter(s -> contracts.selectById(s.getContractId()) != null
                            && cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO.STATUS_SIGNED
                                    .equals(contracts.selectById(s.getContractId()).getStatus()))
                    .forEach(s -> {
                        String appPath = financePost
                                ? FLOWS_PAGE
                                : "/pages/workflow/index?view=contract&id=" + s.getCustomerId();
                        items.add(new TodoItem(DOMAIN, DOMAIN + ":stage-pending:" + s.getId(),
                                "回款待收 · " + ("first".equals(s.getStage()) ? "首款" : "尾款")
                                        + " · 客户#" + s.getCustomerId(),
                                "待收款", s.getAmountCents(), true, null, appPath, s.getCreateTime()));
                    });
        }
        return items;
    }
}
