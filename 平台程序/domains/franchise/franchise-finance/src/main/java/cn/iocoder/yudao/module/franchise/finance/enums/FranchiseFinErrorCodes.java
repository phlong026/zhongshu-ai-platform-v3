package cn.iocoder.yudao.module.franchise.finance.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 财务账簿错误码，段 1-102-000-xxx（加盟域自 1-100 起，模块依次排段）。
 * 消息面向财务，不含敏感凭据。
 */
public interface FranchiseFinErrorCodes {

    ErrorCode RECEIPT_NOT_FOUND = new ErrorCode(1_102_000_000, "收款记录不存在");
    ErrorCode RECEIPT_TXN_DUPLICATE = new ErrorCode(1_102_000_001, "该流水号已在本公司登记过，请核对原记录");
    ErrorCode RECEIPT_STATUS_CONFLICT = new ErrorCode(1_102_000_002, "收款状态已变化，请刷新后重试");
    ErrorCode RECEIPT_REVISION_STALE = new ErrorCode(1_102_000_003, "收款版本已变化，请刷新后重试");
    ErrorCode RECEIPT_NOT_VOIDABLE_SOURCE = new ErrorCode(1_102_000_004, "仅跨端预期应收可以撤销");
    ErrorCode REFUND_EXCEED_BALANCE = new ErrorCode(1_102_000_005, "退款金额超过该笔收款的剩余可退金额");
    ErrorCode REFUND_SOURCE_INVALID = new ErrorCode(1_102_000_006, "仅已登记的收款可以登记退款");
    ErrorCode REFUND_NOT_FOUND = new ErrorCode(1_102_000_007, "退款记录不存在");
    ErrorCode REFUND_ALREADY_REPLACED = new ErrorCode(1_102_000_008, "该退款已被更正，不能重复操作");
    ErrorCode TXN_NO_OCCUPIED = new ErrorCode(1_102_000_009, "该流水号已被其他记录占用");
    ErrorCode PAYMENT_EXCEED_PAYABLE = new ErrorCode(1_102_000_010, "累计付款超出应付金额");
    ErrorCode PAYMENT_PAYABLE_REQUIRED = new ErrorCode(1_102_000_011, "付款必须关联应付记录");
    ErrorCode PAYMENT_NOT_FOUND = new ErrorCode(1_102_000_012, "付款记录不存在");
    ErrorCode PAYMENT_NOT_FOUND_EXPENSE = new ErrorCode(1_102_000_030, "支出退回关联付款单不存在");
    ErrorCode EXPENSE_REFUND_EXCEED = new ErrorCode(1_102_000_031, "支出退回金额超过已付现金余额");
    ErrorCode PAYABLE_NOT_FOUND = new ErrorCode(1_102_000_013, "应付记录不存在");
    ErrorCode BANK_FLOW_NOT_FOUND = new ErrorCode(1_102_000_014, "银行流水不存在");
    ErrorCode BANK_FLOW_TAKEN = new ErrorCode(1_102_000_015, "该银行流水已被确认入账");
    ErrorCode BANK_CONFIRM_AMOUNT_MISMATCH = new ErrorCode(1_102_000_016, "流水金额与应收金额不一致");
    ErrorCode BANK_RECEIPT_NOT_PENDING = new ErrorCode(1_102_000_017, "该应收不处于待登记状态，无法确认到账");
    ErrorCode ORG_MISMATCH = new ErrorCode(1_102_000_018, "不能操作其他组织的财务记录");
    ErrorCode COMMISSION_NOT_APPROVED = new ErrorCode(1_102_000_019, "该委托费用尚未批准，不能登记收款");
    ErrorCode COMMISSION_STAGE_INVALID = new ErrorCode(1_102_000_020, "期款与委托费用方案不符");
    ErrorCode DESIGN_SOURCE_INVALID = new ErrorCode(1_102_000_021, "设计收款来源信息不完整");
    ErrorCode FIN_SOURCE_FORBIDDEN = new ErrorCode(1_102_000_022, "该来源类型不允许手工登记");
    ErrorCode BUSINESS_RECEIPT_IMMUTABLE = new ErrorCode(1_102_000_023, "业务回款由交易流管理，不能直接修订");
    ErrorCode REFERENCE_INVALID_FIN = new ErrorCode(1_102_000_024, "业务回款来源不存在");
    ErrorCode VOUCHER_INVALID_FIN = new ErrorCode(1_102_000_025, "凭证文件不存在、未完成或不属于当前组织");
    // M08-T05 / R04：业务发生日期与录入时间分别保存——正常归单归月日期必填拒空，未来日期拒绝（计划另记）
    ErrorCode BUSINESS_DATE_REQUIRED = new ErrorCode(1_102_000_026, "业务发生日期必填，不能用创建时间代填");
    ErrorCode BUSINESS_DATE_FUTURE = new ErrorCode(1_102_000_027, "业务发生日期不能是未来日期");

}
