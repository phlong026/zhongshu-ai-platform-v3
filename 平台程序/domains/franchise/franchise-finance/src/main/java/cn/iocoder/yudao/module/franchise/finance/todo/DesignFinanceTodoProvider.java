package cn.iocoder.yudao.module.franchise.finance.todo;

import cn.iocoder.yudao.module.franchise.finance.DesignFeeActionService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.TodoProvider;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** 设计财务待办来自双方实时净额；下发后资金回退只报异常，不改历史下发事实。 */
@Component
public class DesignFinanceTodoProvider implements TodoProvider {
    private final DesignFeeActionService fees;
    public DesignFinanceTodoProvider(DesignFeeActionService fees) { this.fees=fees; }
    public String domain() { return "fr-design-fin"; }
    public List<TodoItem> list(PostSnapshot post) {
        if(!List.of("finance","finance-lead").contains(post.roleCode())) return List.of();
        var result=new ArrayList<TodoItem>();
        for(int page=1;;page++) {
            var batch=fees.page(post.orgId(),page,100);
            for(var fee:batch.getList()) {
                if(fee.allowedActions().isEmpty() && !fee.financialException()) continue;
                result.add(new TodoItem(domain(),domain()+":"+fee.payableId(),
                        (fee.financialException()?"设计资金异常":"设计费"+fee.stage()+"待办理")+" · "+fee.commissionId(),
                        fee.financialException()?"资金异常":"待办理",fee.expectedCents(),true,null,
                        "/pages/admin/finance-desk/index?view=flows&tab=ledger",null));
            }
            if((long)page*100>=batch.getTotal()) break;
        }
        return result;
    }
}
