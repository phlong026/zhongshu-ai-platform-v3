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
    ErrorCode PROFILE_AVATAR_INVALID = new ErrorCode(1_101_000_012, "头像地址格式不正确");

}
