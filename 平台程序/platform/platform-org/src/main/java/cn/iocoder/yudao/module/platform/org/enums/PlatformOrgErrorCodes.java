package cn.iocoder.yudao.module.platform.org.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 平台组织与任职错误码，段 1-101-000-xxx（平台能力层自 1-100 起，每个平台模块一段）。
 */
public interface PlatformOrgErrorCodes {

    ErrorCode ORG_TYPE_NOT_REGISTERED = new ErrorCode(1_101_000_000, "组织类型未注册");
    ErrorCode ORG_NOT_FOUND = new ErrorCode(1_101_000_001, "组织不存在");
    ErrorCode ORG_DISABLED = new ErrorCode(1_101_000_002, "组织已停用");
    ErrorCode TEAM_NOT_IN_ORG = new ErrorCode(1_101_000_003, "团队不属于该组织");
    ErrorCode POST_TEMPLATE_NOT_FOUND = new ErrorCode(1_101_000_004, "岗位未注册");
    ErrorCode POST_NOT_ALLOWED_FOR_ORG = new ErrorCode(1_101_000_005, "该岗位不能用于此类组织");
    ErrorCode POST_ALREADY_ACTIVE = new ErrorCode(1_101_000_006, "该员工在本组织已有此岗位");
    ErrorCode POST_NOT_FOUND = new ErrorCode(1_101_000_007, "任职不存在或已撤销");
    ErrorCode USER_NOT_FOUND = new ErrorCode(1_101_000_008, "员工账号不存在");
    ErrorCode ROLE_NOT_CONFIGURED = new ErrorCode(1_101_000_009, "岗位尚未配置对应角色");
    ErrorCode USER_NOT_IN_ORG = new ErrorCode(1_101_000_010, "该员工不属于本组织");

    // ===== 员工本人资料（/platform/profile，08 文档基-3）=====
    ErrorCode PROFILE_NICKNAME_INVALID = new ErrorCode(1_101_000_011, "姓名须为 1-20 个字");
    ErrorCode STAFF_GLOBAL_RESUME_FORBIDDEN = new ErrorCode(1_101_000_013, "该账号已被平台管理员停用，组织管理员无法启用，请联系平台管理员");
    ErrorCode STAFF_RESUME_NEED_ASSIGN = new ErrorCode(1_101_000_014, "该员工在本组织的任职已撤销，如需恢复请重新指派岗位");
    ErrorCode POST_TEAM_CONFLICT = new ErrorCode(1_101_000_015, "该员工已有此岗位但归属其他部门，如需调整请先撤销原任职");
    ErrorCode ORG_NAME_TAKEN = new ErrorCode(1_101_000_016, "该组织名称已开通为其他类型的组织，请更换名称");
    ErrorCode PROFILE_AVATAR_INVALID = new ErrorCode(1_101_000_012, "头像地址格式不正确");

    // ===== M01-T04 离职交接 =====
    ErrorCode HANDOVER_TARGET_NOT_IN_ORG = new ErrorCode(1_101_000_017, "接收人在该组织没有有效岗位，无法交接");
    ErrorCode HANDOVER_TARGET_DISABLED = new ErrorCode(1_101_000_018, "接收人账号已停用或不存在，无法交接");
    ErrorCode HANDOVER_SOURCE_NO_POST = new ErrorCode(1_101_000_019, "该员工在本组织已无有效任职，且没有待交接的在途事项，无法发起交接");
    ErrorCode HANDOVER_TARGET_SELF = new ErrorCode(1_101_000_020, "不能将工作交接给本人");
    ErrorCode HANDOVER_TARGET_ROLE_MISMATCH = new ErrorCode(1_101_000_021, "接收人在该组织没有可承接该类业务的岗位，无法交接");
    ErrorCode HANDOVER_TRANSFER_INCOMPLETE = new ErrorCode(1_101_000_022, "部分在途对象未能转移，本次交接已整体回滚，请重试");
    ErrorCode STAFF_MOBILE_AMBIGUOUS = new ErrorCode(1_101_000_023, "该手机号对应多个员工账号，请指定员工后重试");
    ErrorCode STAFF_USER_MOBILE_MISMATCH = new ErrorCode(1_101_000_024, "指定员工与手机号不匹配");
    ErrorCode STORE_PROFILE_NAME_TAKEN = new ErrorCode(1_101_000_025, "组织名称已被使用，请更换名称");
    ErrorCode STAFF_RESERVED_ROLE = new ErrorCode(1_101_000_026, "老板和门店负责人身份由总部维护，请联系总部办理交接或撤权");
    ErrorCode STAFF_LAST_MANAGER = new ErrorCode(1_101_000_027, "不能移除本组织最后一个有效管理岗位，请先安排接任管理人员");

}
