package cn.iocoder.yudao.module.franchise.sales.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 业务交易流错误码，段 1-113（加盟域依次排段：finance=1_102、crm=1_110、commission=1_111、design=1_112）。
 */
public interface FranchiseSalesErrorCodes {

    ErrorCode QUOTE_TOTAL_INVALID = new ErrorCode(1_113_000_001, "报价明细不合法（行金额/边界/合计超出允许范围）");
    ErrorCode STAGE_ALREADY_PAID = new ErrorCode(1_113_000_002, "该期回款已办理且内容不一致，请刷新后核对");
    ErrorCode DELIVERY_NODE_ORDER = new ErrorCode(1_113_000_003, "交付节点须按顺序登记");
    ErrorCode REFERENCE_INVALID = new ErrorCode(1_113_000_004, "引用对象不存在或不属于本组织/该客户");
    ErrorCode CONTRACT_NOT_SIGNED = new ErrorCode(1_113_000_005, "合同尚未签订，不能办理回款");
    ErrorCode CONTRACT_DUPLICATE = new ErrorCode(1_113_000_006, "该客户已有一份有效主合同（作废后方可重签）");
    ErrorCode DELIVERY_DUPLICATE = new ErrorCode(1_113_000_007, "该合同已存在交付记录，请在原交付上继续办理");

}
