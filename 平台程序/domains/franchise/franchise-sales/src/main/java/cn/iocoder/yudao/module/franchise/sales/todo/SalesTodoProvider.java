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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public SalesTodoProvider(SaleContractMapper contracts, SalePaymentStageMapper stages, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.contracts = contracts;
        this.stages = stages;
        this.jdbc = jdbc;
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
        java.util.Map<Long,String> names = new java.util.HashMap<>();
        if (isWritePost) jdbc.queryForList("SELECT id,name FROM fr_customer WHERE company_id=?", post.orgId())
                .forEach(row -> names.put(((Number)row.get("id")).longValue(), String.valueOf(row.get("name"))));
        if (isWritePost) {
            contracts.selectList(new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<SaleContractDO>()
                            .eq(SaleContractDO::getCompanyId, post.orgId())
                            .eq(SaleContractDO::getStatus, STATUS_WAIT_SIGN))
                    .forEach(c -> items.add(new TodoItem(DOMAIN, DOMAIN + ":contract-sign:" + c.getId(),
                            "合同待签 · " + names.getOrDefault(c.getCustomerId(), "客户资料待补充"), "待签订", c.getTotalCents(), true, null,
                            "/pages/workflow/index?view=contract&id=" + c.getCustomerId(), c.getCreateTime())));
        }
        // 财务办理到账核实由 CollectionDeclarationTodoProvider 提供，未发生收款的合同阶段保留在应收查询。
        if (isCollectPost && !java.util.Set.of(FranchiseRoles.FINANCE, FranchiseRoles.FINANCE_LEAD).contains(post.roleCode())) {
            boolean financePost = FranchiseRoles.FINANCE.equals(post.roleCode())
                    || FranchiseRoles.FINANCE_LEAD.equals(post.roleCode());
            stages.selectOwingByOrg(post.orgId()).stream()
                    .filter(s -> contracts.selectById(s.getContractId()) != null
                            && cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO.STATUS_SIGNED
                                    .equals(contracts.selectById(s.getContractId()).getStatus()))
                    .forEach(s -> {
                        String appPath = financePost
                                ? FLOWS_PAGE
                                : "/pages/workflow/index?view=contract&id=" + s.getCustomerId();
                        items.add(new TodoItem(DOMAIN, DOMAIN + ":stage-pending:" + s.getId(),
                                "回款待收 · " + (s.getLabel()==null || s.getLabel().isBlank()?stageLabel(s.getStage()):s.getLabel())
                                        + " · " + names.getOrDefault(s.getCustomerId(), "客户资料待补充"),
                                "待收款", s.getAmountCents(), true, null, appPath, s.getCreateTime()));
                    });
        }
        return items;
    }
    /** M06-T04 三期显示文案 */
    private static String stageLabel(String stage) {
        return switch (stage) {
            case "SIGN_AFTER" -> "签约后";
            case "BASE_DONE" -> "基础完成后";
            case "CAP_DONE" -> "封顶后";
            case "first" -> "首款";
            case "tail" -> "尾款";
            default -> stage;
        };
    }

}
