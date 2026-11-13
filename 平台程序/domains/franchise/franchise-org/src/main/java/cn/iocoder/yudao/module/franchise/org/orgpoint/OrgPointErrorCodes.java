package cn.iocoder.yudao.module.franchise.org.orgpoint;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** 公司积分池视图错误码（1_109 段，franchise-org 域）。 */
public interface OrgPointErrorCodes {

    ErrorCode ORG_POINT_FORBIDDEN = new ErrorCode(1_109_000_001, "当前岗位无权查看公司积分数据");
}
