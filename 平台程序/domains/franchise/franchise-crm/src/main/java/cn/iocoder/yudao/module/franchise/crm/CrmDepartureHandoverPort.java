package cn.iocoder.yudao.module.franchise.crm;

import cn.iocoder.yudao.module.platform.org.DepartureHandoverPort;
import cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * M01-T04：客户域离职交接 Port——客户归属即责任人，扫描/批量转移走
 * {@link CustomerService#countInFlightByOwner} / {@link CustomerService#bulkTransferByOwner}
 * （只改 owner/teamId，历史跟进流水与经办记录保留）。
 *
 * <p>线索（待转）不在此 Port 内：待转线索经 {@code LeadService.listPending} 对全线负责人与老板
 * 可见，离职员工的线索天然进负责人待分配池（M01.md 场景 3 的「待分配」语义），无需转移。
 * 设计委托无独立责任人字段、随客户走（客户转移后委托查询权限自动跟随），由清单层如实标注。
 */
@Component
public class CrmDepartureHandoverPort implements DepartureHandoverPort {

    @Resource
    private CustomerService customers;

    @Override
    public String domain() {
        return "franchise-crm";
    }

    @Override
    public String label() {
        return "客户";
    }

    @Override
    public long scanInFlight(long fromUserId, long orgId) {
        return customers.countInFlightByOwner(fromUserId, orgId);
    }

    /** 域级接收资格（Codex R1 P1-1）：无 sales/sales-lead 岗位者（如财务、工程岗）不得接收客户 */
    @Override
    public void validateReceiver(long toUserId, long orgId) {
        if (customers.findReceiverPost(toUserId, orgId) == null) {
            throw exception(PlatformOrgErrorCodes.HANDOVER_TARGET_ROLE_MISMATCH);
        }
    }

    @Override
    public int bulkTransfer(long fromUserId, long toUserId, long orgId, long operatorUserId) {
        return customers.bulkTransferByOwner(fromUserId, toUserId, orgId, operatorUserId);
    }
}
