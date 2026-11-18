package cn.iocoder.yudao.module.franchise.engineering.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 工程全周期错误码，段 1-115（加盟域依次排段：…sales=1_113、content=1_114）。规则来源：任务书 17 §六。
 * 000 兼作：非本工程负责人（owner 校验失败）与交付后/归档后动作白名单越界。
 */
public interface FranchiseEngineeringErrorCodes {

    ErrorCode ENGINEERING_FORBIDDEN = new ErrorCode(1_115_000_000,
            "当前岗位无权办理本工程操作（非负责人本人或超出交付/归档后可办范围）");
    ErrorCode ENGINEERING_INPUT_INVALID = new ErrorCode(1_115_000_001, "提交内容不合法：{}");
    ErrorCode PLAN_INVALID = new ErrorCode(1_115_000_002, "施工计划不合法（日期缺失/重叠/已完成节点不可改期）");
    ErrorCode NODE_ACTION_INVALID = new ErrorCode(1_115_000_003, "节点操作前置不满足（撤回/更正条件或封顶日期链）");
    ErrorCode MATERIAL_INVALID = new ErrorCode(1_115_000_004, "材料操作不合法（调整越界或已付款锁定）");
    ErrorCode RECEIPT_DUPLICATE = new ErrorCode(1_115_000_005, "收货流水号已登记，请查看原收货记录");
    ErrorCode COLLECTION_EXCEEDED = new ErrorCode(1_115_000_006, "本次金额超过本期尚未登记的应收金额");
    ErrorCode GATE_NOT_MET = new ErrorCode(1_115_000_007, "门禁未满足（资金或材料条件未收齐）");
    ErrorCode PROJECT_DUPLICATE = new ErrorCode(1_115_000_008, "同名项目已存在，请从项目列表继续办理");
    ErrorCode CLOSURE_ORDER_INVALID = new ErrorCode(1_115_000_009, "尾段顺序不正确（交付→结算→归档）");
    ErrorCode MANAGER_INVALID = new ErrorCode(1_115_000_010, "负责人不在岗或岗位不符，请重新选择本公司在岗工程人员");
    ErrorCode HANDOVER_TRANSFER_REQUIRED = new ErrorCode(1_115_000_011,
            "合同已关联工程「{}」，现由他人负责。请确认接任该工程负责人后再接收，或退回移交。");

}
