package cn.iocoder.yudao.module.franchise.content.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 内容创作获客错误码，段 1-114（加盟域依次排段：finance=1_102、crm=1_110、commission=1_111、
 * design=1_112、sales=1_113）。规则来源：任务书 16 §六。
 */
public interface FranchiseContentErrorCodes {

    ErrorCode CONTENT_FORBIDDEN = new ErrorCode(1_114_000_000, "当前岗位无内容创作权限");
    ErrorCode INPUT_INVALID = new ErrorCode(1_114_000_001, "提交内容不合法（长度/取值/素材类型超出允许范围）");
    ErrorCode AI_QUOTA_EXCEEDED = new ErrorCode(1_114_000_002, "本组织今日该能力用量已达上限，请明日再试或联系总部提额");
    ErrorCode PUBLICATION_URL_TAKEN = new ErrorCode(1_114_000_003, "该发布链接已被其他发布记录使用");
    ErrorCode TASK_NOT_FOUND = new ErrorCode(1_114_000_004, "生成任务不存在或不属于当前组织");
    ErrorCode WORK_INVALID = new ErrorCode(1_114_000_005, "作品不存在、未完成或不属于当前组织");
    ErrorCode CONTENT_PROVIDER_UNAVAILABLE = new ErrorCode(1_114_000_006, "内容生成服务暂不可用，请稍后重试或联系管理员");

}
