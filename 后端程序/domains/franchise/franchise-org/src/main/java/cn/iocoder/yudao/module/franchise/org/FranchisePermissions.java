package cn.iocoder.yudao.module.franchise.org;

/**
 * 加盟协同域权限码；菜单与岗位授权见迁移 V20261022.801。控制器一律用 {@code @post.has(...)} 校验。
 */
public interface FranchisePermissions {

    String CUSTOMER_QUERY = "franchise:customer:query";
    String CUSTOMER_CREATE = "franchise:customer:create";
    String CUSTOMER_FOLLOW = "franchise:customer:follow";
    String FEE_POLICY_MANAGE = "franchise:design-fee-policy:manage";
    String COMMISSION_QUERY = "franchise:commission:query";
    String COMMISSION_SUBMIT = "franchise:commission:submit";
    String COMMISSION_APPROVE_FEE = "franchise:commission:approve-fee";
    String COMMISSION_COMPLETE = "franchise:commission:complete";
    String COMMISSION_DISPATCH = "franchise:commission:dispatch";
    String COMMISSION_FEEDBACK = "franchise:commission:feedback";
    String COMMISSION_HANDOVER = "franchise:commission:handover";
    String COMMISSION_RECEIVE = "franchise:commission:receive";
    String PAYMENT_REGISTER = "franchise:design-payment:register";
    String PAYMENT_REVIEW = "franchise:design-payment:review";
    String RECEIPT_REGISTER = "franchise:design-receipt:register";
    String RECEIPT_VERIFY = "franchise:design-receipt:verify";
    String TODO_QUERY = "franchise:todo:query";

}
