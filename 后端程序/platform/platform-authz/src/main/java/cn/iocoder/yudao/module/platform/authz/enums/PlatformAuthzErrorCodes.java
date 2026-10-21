package cn.iocoder.yudao.module.platform.authz.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 平台授权错误码，段 1-103-000-xxx。对象无权访问沿用 RESOURCE_FORBIDDEN。
 */
public interface PlatformAuthzErrorCodes {

    ErrorCode POST_REQUIRED = new ErrorCode(1_103_000_000, "当前账号没有有效的工作身份");
    ErrorCode POST_SELECTION_REQUIRED = new ErrorCode(1_103_000_001, "请选择工作身份");
    ErrorCode POST_NOT_USABLE = new ErrorCode(1_103_000_002, "工作身份无效或已被撤销，请重新选择");
    ErrorCode POST_HEADER_INVALID = new ErrorCode(1_103_000_003, "工作身份参数格式错误");

}
