package cn.iocoder.yudao.module.franchise.engineering;

/** 工程全周期权限码（阶段 2 / 856 矩阵）：query/write/terms/collect 四码分离；write 之上服务层另校验 owner。 */
public final class EngineeringPermissions {

    public static final String QUERY = "franchise:engineering:query";
    public static final String WRITE = "franchise:engineering:write";
    /** 材料采购金额（财务线与老板；已付款后金额/凭据锁死只能补证） */
    public static final String TERMS = "franchise:engineering:terms";
    /** 工程收款登记（仅门店财务/财务负责人；boss 代登记不做，待决①） */
    public static final String COLLECT = "franchise:engineering:collect";

    private EngineeringPermissions() {
    }
}
