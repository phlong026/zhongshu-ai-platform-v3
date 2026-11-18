package cn.iocoder.yudao.module.platform.workflow.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 平台待办与审批错误码，段 1-102-000-xxx；
 * 员工消息（基-2）走新排段 1-105-000-xxx（1_102 段已被 franchise-finance 占至 024，同段双登记不可续用；11 文档 §五.1）。
 */
public interface PlatformWorkflowErrorCodes {

    ErrorCode APPROVAL_NOT_FOUND = new ErrorCode(1_102_000_000, "审批事项不存在");
    ErrorCode APPROVAL_NOT_PENDING = new ErrorCode(1_102_000_001, "该审批已办理或已失效，请刷新");
    ErrorCode APPROVAL_SELF_FORBIDDEN = new ErrorCode(1_102_000_002, "不能审批本人提交的事项");
    ErrorCode APPROVER_NOT_QUALIFIED = new ErrorCode(1_102_000_003, "当前身份无权审批该事项");
    ErrorCode APPROVAL_NOTE_REQUIRED = new ErrorCode(1_102_000_004, "请填写审批意见");

    ErrorCode MESSAGE_SNAPSHOT_EXPIRED = new ErrorCode(1_105_000_001, "消息列表已过期，请刷新后重试");
    ErrorCode MESSAGE_NOT_FOUND = new ErrorCode(1_105_000_000, "消息不存在或无权访问");

}
