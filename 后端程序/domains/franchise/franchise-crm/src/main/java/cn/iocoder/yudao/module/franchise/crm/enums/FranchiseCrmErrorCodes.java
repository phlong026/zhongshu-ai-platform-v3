package cn.iocoder.yudao.module.franchise.crm.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 加盟协同域 · 客户错误码，段 1-201-000-xxx（加盟协同域自 1-200 起）。
 */
public interface FranchiseCrmErrorCodes {

    ErrorCode ACTOR_NOT_SALES = new ErrorCode(1_201_000_000, "请由业务员登记客资");
    ErrorCode LEAD_INVALID = new ErrorCode(1_201_000_001, "请填写称呼、有效手机号及咨询需求");
    /** 不存在或无权查看统一返回此码，避免泄露他人客户是否存在 */
    ErrorCode CUSTOMER_NOT_FOUND = new ErrorCode(1_201_000_002, "客户不存在或无权访问");
    ErrorCode FOLLOW_UP_INVALID = new ErrorCode(1_201_000_003, "请填写跟进内容");

}
