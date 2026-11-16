package cn.iocoder.yudao.module.platform.wallet.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** Company-point error contract retained from the original commerce implementation. */
final class OrgPointErrors {

    static final ErrorCode ORG_NOT_ENABLED =
            new ErrorCode(1_072_000_010, "公司尚未开通积分账户，请联系平台开通");
    static final ErrorCode ORG_POINT_INSUFFICIENT =
            new ErrorCode(1_072_000_011, "公司积分池余额不足，请联系管理员充值");
    // 保留历史错误码空位 012，避免将旧额度语义重用为其他错误。
    static final ErrorCode ORG_POINT_SUSPENDED =
            new ErrorCode(1_072_000_013, "公司积分账户已停用");
    static final ErrorCode ORG_POINT_REPLAY_CONFLICT =
            new ErrorCode(1_072_000_014, "幂等键已被不同参数的请求占用");
    static final ErrorCode ORG_SUBJECT_FORBIDDEN =
            new ErrorCode(1_072_000_015, "当前岗位未被授权使用该功能，请联系公司管理员");
    static final ErrorCode ORG_PRICE_CONFLICT =
            new ErrorCode(1_072_000_016, "价格已被他人修改或已停用，请刷新后重试");
    static final ErrorCode ORG_MEMBER_QUOTA_DISABLED =
            new ErrorCode(1_072_000_017, "员工月度额度已停用，使用仅受公司积分余额和业务权限控制");

    private OrgPointErrors() {
    }
}
