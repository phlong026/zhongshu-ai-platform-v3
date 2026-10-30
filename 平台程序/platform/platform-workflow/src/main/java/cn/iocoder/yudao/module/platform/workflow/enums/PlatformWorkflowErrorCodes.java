package cn.iocoder.yudao.module.platform.workflow.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 平台待办与审批错误码，段 1-102-000-xxx。
 */
public interface PlatformWorkflowErrorCodes {

    ErrorCode APPROVAL_NOT_FOUND = new ErrorCode(1_102_000_000, "审批事项不存在");
    ErrorCode APPROVAL_NOT_PENDING = new ErrorCode(1_102_000_001, "该审批已办理或已失效，请刷新");
    ErrorCode APPROVAL_SELF_FORBIDDEN = new ErrorCode(1_102_000_002, "不能审批本人提交的事项");
    ErrorCode APPROVER_NOT_QUALIFIED = new ErrorCode(1_102_000_003, "当前身份无权审批该事项");
    ErrorCode APPROVAL_NOTE_REQUIRED = new ErrorCode(1_102_000_004, "请填写审批意见");

}
