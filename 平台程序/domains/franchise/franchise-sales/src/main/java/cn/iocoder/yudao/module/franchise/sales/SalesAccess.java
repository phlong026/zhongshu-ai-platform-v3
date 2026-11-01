package cn.iocoder.yudao.module.franchise.sales;

import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.sales.enums.FranchiseSalesErrorCodes.REFERENCE_INVALID;

/**
 * 交易流对象访问契约（B2 板块 B，Codex R1/R2 定稿）：不复用 CRM CustomerAccess
 * （其拒绝 boss 写客户、拒绝 finance 读客户——交易流语义为组织内协作）。
 * query=组织内可见；write=门店业务岗或 boss；collect=write∪finance 系；
 * 对象级校验=客户存在且 company_id==post.orgId()（不做负责人收窄，同板块 A D18 语义）。
 */
@Component
public class SalesAccess {

    private final CustomerSchemePort customerSchemes;

    public SalesAccess(CustomerSchemePort customerSchemes) {
        this.customerSchemes = customerSchemes;
    }

    /** 客户存在且属于当前组织（query/write/collect 公共前置）；不存在/跨组织 → REFERENCE_INVALID */
    public void requireOrgCustomer(PostSnapshot post, long customerId) {
        try {
            customerSchemes.requireAccessible(post, customerId);
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException e) {
            // CRM 侧统一不区分暴露（1_110_000_000/1_110_000_001）→ 交易流统一 1_113_000_004
            if (e.getCode() == 1_110_000_000 || e.getCode() == 1_110_000_001) {
                throw exception(REFERENCE_INVALID);
            }
            throw e;
        }
    }
}
