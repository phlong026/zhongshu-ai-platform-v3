package cn.iocoder.yudao.module.franchise.crm.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 加盟协同域 · 客资与客户错误码，段 1-110-000-xxx。并发改动沿用 STATE_VERSION_CONFLICT。
 */
public interface FranchiseCrmErrorCodes {

    ErrorCode CUSTOMER_NOT_FOUND = new ErrorCode(1_110_000_000, "客户不存在或无权访问");
    ErrorCode CUSTOMER_FORBIDDEN = new ErrorCode(1_110_000_001, "当前岗位不能办理该客户");
    ErrorCode STORE_SALES_POST_REQUIRED = new ErrorCode(1_110_000_002, "请以加盟门店的业务岗位办理");
    ErrorCode LEAD_INPUT_INVALID = new ErrorCode(1_110_000_003, "请填写称呼、11 位手机号、咨询需求和获客来源");
    ErrorCode LEAD_DUPLICATE = new ErrorCode(1_110_000_004, "本门店已有该手机号的待关联客资，请由负责人处理");
    ErrorCode LEAD_NOT_FOUND = new ErrorCode(1_110_000_005, "客资不存在或无权处理");
    ErrorCode LEAD_NOT_PENDING = new ErrorCode(1_110_000_006, "客资已处理，请刷新");
    ErrorCode LEAD_LINK_TARGET_FORBIDDEN = new ErrorCode(1_110_000_007, "同手机号原客户不在你的管理范围，请交对应团队负责人或老板关联");
    ErrorCode NOTE_REQUIRED = new ErrorCode(1_110_000_008, "请填写内容或原因（500 字以内）");
    ErrorCode VISIT_DATE_INVALID = new ErrorCode(1_110_000_009, "预约回访日期不能早于今天");
    ErrorCode CUSTOMER_INPUT_INVALID = new ErrorCode(1_110_000_010, "请填写客户称呼，其余信息不超过长度限制");
    ErrorCode SCHEME_INVALID = new ErrorCode(1_110_000_011, "请填写方案名称；预算须有有效的面宽、进深、层数和面积");
    ErrorCode TRANSFER_TARGET_INVALID = new ErrorCode(1_110_000_012, "接手人须为本组织在岗业务人员，负责人只能转给本人管理团队的人");
    ErrorCode LEAD_IMPORT_FORBIDDEN = new ErrorCode(1_110_000_013, "仅老板/负责人可发起客资导入");

}
