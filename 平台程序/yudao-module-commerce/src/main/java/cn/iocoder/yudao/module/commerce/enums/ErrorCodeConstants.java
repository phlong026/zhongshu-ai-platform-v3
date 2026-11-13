package cn.iocoder.yudao.module.commerce.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * commerce 模块错误码
 *
 * 使用 1-072-000-000 段；常量字段名即架构文档 §7.6 的稳定字符串标识。
 */
public interface ErrorCodeConstants {

    ErrorCode POINTS_INSUFFICIENT = new ErrorCode(1_072_000_000, "设计点余额不足");
    ErrorCode PRICE_RULE_CHANGED = new ErrorCode(1_072_000_001, "计价规则已更新，请确认新价格后重试");
    ErrorCode PAYMENT_ORDER_STATE_CONFLICT = new ErrorCode(1_072_000_002, "充值订单状态不允许该操作");
    ErrorCode PAYMENT_PAID_CREDIT_PENDING = new ErrorCode(1_072_000_003, "支付成功，设计点到账处理中");
    ErrorCode REFUND_POINTS_ALREADY_USED = new ErrorCode(1_072_000_004, "到账后已发生点数消耗，无法受理整单退款");
    ErrorCode REFUND_POLICY_NOT_ENABLED = new ErrorCode(1_072_000_005, "P0 仅支持整单全额退款，不支持部分退款");

    // ========== 公司积分池（24 号任务书批 A，错误码 1_072_000_010 起） ==========
    ErrorCode ORG_NOT_ENABLED = new ErrorCode(1_072_000_010, "公司未开通设计能力，请联系平台开通");
    ErrorCode ORG_POINT_INSUFFICIENT = new ErrorCode(1_072_000_011, "公司积分池余额不足，请联系管理员充值");
    ErrorCode ORG_MEMBER_QUOTA_EXCEEDED = new ErrorCode(1_072_000_012, "本月用量已达额度上限，额度由公司管理员设置");
    ErrorCode ORG_POINT_SUSPENDED = new ErrorCode(1_072_000_013, "公司积分账户已停用");
    ErrorCode ORG_POINT_REPLAY_CONFLICT = new ErrorCode(1_072_000_014, "幂等键已被不同参数的请求占用");
    ErrorCode ORG_SUBJECT_FORBIDDEN = new ErrorCode(1_072_000_015, "当前岗位未被授权使用该功能，请联系公司管理员");

}
