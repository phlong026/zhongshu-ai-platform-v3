package cn.iocoder.yudao.module.franchise.design;

/** Franchise users may read frozen results and link them to customer files. */
public final class DesignGenerationPolicy {
    public static final int RETIRED = 1_112_100_004;
    private DesignGenerationPolicy() { }
    public static void rejectGeneration() {
        throw retired();
    }
    public static cn.iocoder.yudao.framework.common.exception.ServiceException retired() {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(RETIRED,
                "设计生成与快速预算已停用，可查看历史成果或在客户跟进中上传外部成果");
    }
}
