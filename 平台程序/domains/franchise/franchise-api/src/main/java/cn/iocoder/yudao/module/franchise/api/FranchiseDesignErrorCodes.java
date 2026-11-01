package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 设计赋能接入层错误码（B2 板块 A），段 1-112（加盟域模块依次排段：finance=1_102、crm=1_110、
 * commission=1_111、design=1_112）。定义在 franchise-api 使 Port 实现方（yudao-module-design）与
 * 消费方（franchise-design）共用同一契约；design/commerce 自身错误原码透传不改码。
 */
public interface FranchiseDesignErrorCodes {

    /** BUDGET 回写缺宽/深/层/面积或超范围（DB CHECK 硬约束的显式前置校验） */
    ErrorCode SCHEME_SIZE_REQUIRED = new ErrorCode(1_112_000_001,
            "保存参考预算需要面宽/进深/层数/建筑面积（客户或本次请求提供）");
    /** 客户不存在/跨组织/无权访问（跟随 CRM 统一处理语义，不区分暴露） */
    ErrorCode CUSTOMER_NOT_ACCESSIBLE = new ErrorCode(1_112_000_002, "客户不存在或不可访问");
    /** kind 与结果类型交叉（PLAN 引纯立面版本/FACADE 引纯平面版本） */
    ErrorCode SCHEME_KIND_MISMATCH = new ErrorCode(1_112_000_003, "所选结果与保存类型不匹配");
    /** 服务账号被停用（identity 管理端停用；恢复=管理端显式启用，本批无惰性复活） */
    ErrorCode SERVICE_ACCOUNT_INACTIVE = new ErrorCode(1_112_000_004,
            "设计服务账号已停用，请联系管理员");
}
