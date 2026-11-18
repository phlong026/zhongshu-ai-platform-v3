package cn.iocoder.yudao.module.franchise.commission.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 加盟协同域 · 设计委托错误码，段 1-111-000-xxx。并发改动沿用 STATE_VERSION_CONFLICT。
 */
public interface FranchiseCommissionErrorCodes {
    ErrorCode SUBMISSION_VERSION_STALE = new ErrorCode(1_111_000_021, "预算或设计计价规则已更新，请刷新后重新确认");

    ErrorCode COMMISSION_NOT_FOUND = new ErrorCode(1_111_000_000, "委托不存在或无权访问");
    ErrorCode COMMISSION_FORBIDDEN = new ErrorCode(1_111_000_001, "当前岗位不能办理该委托");
    ErrorCode COMMISSION_STATE_INVALID = new ErrorCode(1_111_000_002, "委托当前状态不允许该操作，请刷新");
    ErrorCode COMMISSION_ALREADY_EXISTS = new ErrorCode(1_111_000_003, "该客户已有委托，不能重复提交");
    ErrorCode POLICY_INPUT_INVALID = new ErrorCode(1_111_000_004, "请填写总额、首款（0 < 首款 < 总额，最多两位小数）、收款方和交付范围");
    ErrorCode POLICY_NOT_FOUND = new ErrorCode(1_111_000_005, "本门店尚未配置标准设计费用");
    ErrorCode SUBMISSION_INPUT_INVALID = new ErrorCode(1_111_000_006, "请填写需求说明与交付范围，并引用最近一版参考预算");
    ErrorCode SUBMISSION_AMOUNT_INVALID = new ErrorCode(1_111_000_007, "费用须满足 0 < 首款 < 总额 ≤ 1 亿元，最多两位小数");
    ErrorCode SUBMISSION_BUDGET_REQUIRED = new ErrorCode(1_111_000_008, "请先保存该客户的参考预算再提交委托");
    ErrorCode DESIGN_ORG_REQUIRED = new ErrorCode(1_111_000_009, "未找到总部组织，请联系平台配置");
    ErrorCode BOSS_ABSENT = new ErrorCode(1_111_000_010, "费用与标准不一致需老板审批，本门店没有可审批的老板，请先配置");
    ErrorCode FEE_DECISION_FORBIDDEN = new ErrorCode(1_111_000_011, "委托费用由本门店老板审批");
    ErrorCode FIRST_PAYMENT_NOT_READY = new ErrorCode(1_111_000_012, "当前费用模式的完成资金条件未满足，请核对设计费净付与核收");
    ErrorCode TAIL_PAYMENT_NOT_READY = new ErrorCode(1_111_000_013, "设计费尚未两侧足额结清，不能下发成果");
    ErrorCode PROGRESS_INPUT_INVALID = new ErrorCode(1_111_000_014, "请填写说明与交付清单并勾选确认");
    ErrorCode FEEDBACK_INPUT_INVALID = new ErrorCode(1_111_000_015, "请填写修改意见");
    ErrorCode HANDOVER_INPUT_INVALID = new ErrorCode(1_111_000_016, "请上传四份文件、填写图纸版本与交底说明，客户确认日期不能晚于今天");
    ErrorCode HANDOVER_TARGET_INVALID = new ErrorCode(1_111_000_017, "接收人须为本门店在岗工程负责人，且不能是提交人本人");
    ErrorCode HANDOVER_NOT_FOUND = new ErrorCode(1_111_000_018, "移交记录不存在或无权处理");
    ErrorCode HANDOVER_DECISION_FORBIDDEN = new ErrorCode(1_111_000_019, "移交由被指定的工程负责人本人核查");
    ErrorCode HANDOVER_RETURN_REASON_REQUIRED = new ErrorCode(1_111_000_020, "退回请填写原因");

    ErrorCode SIMULATION_FORBIDDEN = new ErrorCode(1_111_000_022, "当前组织未开放模拟付款，请刷新后按正式流程办理");
}
