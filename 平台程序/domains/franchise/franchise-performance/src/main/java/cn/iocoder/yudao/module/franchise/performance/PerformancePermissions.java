package cn.iocoder.yudao.module.franchise.performance;

/** 绩效提成经营权限码（阶段 3 / 857 矩阵）：query/manage/commission/operate 四码分离。 */
public final class PerformancePermissions {

    /** 任务本人/团队范围（全员） */
    public static final String QUERY = "franchise:performance:query";
    /** 派发/改派/归档/目标/复核/申诉裁决（boss/owner） */
    public static final String MANAGE = "franchise:performance:manage";
    /** 回款提成（owner 可看不可改；服务层：policy·reconcile 仅 boss、release 仅 finance 族） */
    public static final String COMMISSION = "franchise:performance:commission";
    /** 经营视图/快照/月末对账（boss/owner/finance/finance-lead） */
    public static final String OPERATE = "franchise:performance:operate";

    private PerformancePermissions() {
    }
}
