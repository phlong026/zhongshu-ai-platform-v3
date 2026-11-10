package cn.iocoder.yudao.module.identity.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * identity 模块错误码
 *
 * 使用 1-070-000-000 段；常量字段名即架构文档 §7.6 的稳定字符串标识，
 * 由合同测试锁定字段名与数值码的对应关系，禁止改名或复用数值。
 */
public interface ErrorCodeConstants {

    // ========== 授权码 1-070-000-000 ==========
    ErrorCode ACCESS_CODE_INVALID = new ErrorCode(1_070_000_000, "授权码无效");
    ErrorCode ACCESS_CODE_EXPIRED = new ErrorCode(1_070_000_001, "授权码已过期");
    ErrorCode ACCESS_CODE_DISABLED = new ErrorCode(1_070_000_002, "授权码已停用");
    ErrorCode ACCESS_CODE_ALREADY_CONSUMED = new ErrorCode(1_070_000_003, "授权码已被使用");
    ErrorCode ACCESS_CODE_SECRET_ALREADY_EXPOSED = new ErrorCode(1_070_000_004, "授权码完整明文已交付，不能再次交付");
    ErrorCode WECHAT_IDENTITY_ALREADY_BOUND = new ErrorCode(1_070_000_005, "微信身份已绑定其他账号");

    // ========== 技术型授权（09 钱包会话桥接）==========
    ErrorCode ACCESS_GRANT_TECHNICAL = new ErrorCode(1_070_000_006, "该授权为系统技术授权，不可在此解绑；如需断开请停用对应账号");

    // ========== 开通申请（设计小程序 2026-11-04 申请-开通链路）==========
    // 注意：1_070_000_006 已被 ACCESS_GRANT_TECHNICAL 占用，ALREADY_PENDING 顺延到 010；
    // 007（ALREADY_ACTIVE）必须保持原值——小程序前端 pages/auth/index.js 按 1070000007 判定"已开通"。
    ErrorCode ACTIVATION_REQUEST_ALREADY_PENDING = new ErrorCode(1_070_000_010, "已提交过开通申请，请等待管理员处理");
    ErrorCode ACTIVATION_REQUEST_ALREADY_ACTIVE = new ErrorCode(1_070_000_007, "账号已开通使用权限，无需再申请");
    ErrorCode ACTIVATION_REQUEST_NOT_FOUND = new ErrorCode(1_070_000_008, "开通申请不存在");
    ErrorCode ACTIVATION_REQUEST_ALREADY_HANDLED = new ErrorCode(1_070_000_009, "该申请已被处理，请刷新后重试");

}
