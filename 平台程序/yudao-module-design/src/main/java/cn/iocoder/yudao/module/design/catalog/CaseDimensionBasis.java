package cn.iocoder.yudao.module.design.catalog;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

/** Dimensions are comparable only when their meaning has been explicitly recorded. */
public enum CaseDimensionBasis {
    UNKNOWN, SITE, BUILDING_OUTLINE;

    public static CaseDimensionBasis parse(Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) return UNKNOWN;
        if (!(value instanceof String text)) throw invalid();
        try {
            return valueOf(text.strip());
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static ServiceException invalid() {
        return new ServiceException(400, "尺寸口径只能为 UNKNOWN、SITE 或 BUILDING_OUTLINE");
    }
}
