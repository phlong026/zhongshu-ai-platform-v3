package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.platform.org.DepartureHandoverPort;
import org.springframework.stereotype.Component;

/**
 * 财务待办按组织向有效 finance/finance-lead 岗位共享，没有个人独占的办理负责人。
 * payer 是真实垫付人，operator/registrar 等是历史经办人；离职交接不得改写这些资金主体。
 */
@Component
public class FinanceDepartureHandoverPort implements DepartureHandoverPort {

    @Override public String domain() { return "franchise-finance"; }
    @Override public String label() { return "财务账款"; }
    @Override public boolean organizationShared() { return true; }
    @Override public long scanInFlight(long fromUserId, long orgId) { return 0; }
    @Override public void validateReceiver(long toUserId, long orgId) { }
    @Override public int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId) { return 0; }
}
