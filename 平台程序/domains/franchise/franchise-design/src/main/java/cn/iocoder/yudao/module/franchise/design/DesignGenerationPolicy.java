package cn.iocoder.yudao.module.franchise.design;

/** Internal management keeps reference data and budgets; new drawing generation is external. */
public final class DesignGenerationPolicy {
    public static final int RETIRED = 1_112_100_004;
    private DesignGenerationPolicy() { }
    public static void rejectGeneration() {
        throw new cn.iocoder.yudao.framework.common.exception.ServiceException(RETIRED,
                "平面与立面设计已改由外部工具完成，请在客户跟进中上传成果");
    }
}
