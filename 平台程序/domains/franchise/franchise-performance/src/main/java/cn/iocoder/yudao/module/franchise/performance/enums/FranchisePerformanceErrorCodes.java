package cn.iocoder.yudao.module.franchise.performance.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 绩效提成经营错误码，段 1-116（加盟域依次排段：…content=1_114、engineering=1_115）。规则来源：任务书 19 §六。
 * 000 兼作：严格角色越界（持 commission 码但非 boss 办 policy 等）与复核本人。
 */
public interface FranchisePerformanceErrorCodes {

    ErrorCode PERFORMANCE_FORBIDDEN = new ErrorCode(1_116_000_000, "当前岗位无权办理本操作（越权或复核本人周期）");
    ErrorCode PERF_INPUT_INVALID = new ErrorCode(1_116_000_001, "提交内容不合法：{}");
    ErrorCode GOAL_OR_SNAPSHOT_LOCKED = new ErrorCode(1_116_000_002, "目标已随归档锁定或经营快照签名未变化");
    ErrorCode CYCLE_ACTION_INVALID = new ErrorCode(1_116_000_003, "周期操作前置不满足（退回条件/申诉阻断归档/修订签名未变）");
    ErrorCode COMMISSION_POLICY_LOCKED = new ErrorCode(1_116_000_004, "提成规则已生成应付，整行锁定不可修改");
    ErrorCode PERF_TASK_INVALID = new ErrorCode(1_116_000_005, "任务操作前置不满足（归档禁改/改派校验/补填条件）");
    ErrorCode COMMISSION_RELEASE_INVALID = new ErrorCode(1_116_000_006, "本阶段尚未生成提成应付");
    ErrorCode COMMISSION_NOT_ELIGIBLE = new ErrorCode(1_116_000_007, "{}净实收尚未足额，不能办理本操作");
    ErrorCode PERF_PERIOD_INVALID = new ErrorCode(1_116_000_008, "周期或日期不合法（period 格式/due 越月/快照须为过去月）");

}
