package cn.iocoder.yudao.module.design.controller.app.vo;

import java.math.BigDecimal;
import java.util.List;

/** Public catalog deliberately has no prices, quantities, conversion rules or internal references. */
public record AppBudgetCatalogRespVO(List<Item> items) {
    public record Region(String code, String name) {}

    /** 用户本人可见的报价配置头；仅 App 控制器返回，管理端不暴露明细。 */
    public record MyRegion(String code, String name, BigDecimal coefficient, int priceVersion,
                           String configuredAt, String status, int version,
                           int itemCount, int manualCount, int missingCount, int totalCount,
                           int baseVersion, int latestBaseVersion) {}
    public record Item(String itemId, String code, String category, String name, String source,
                       List<Option> options) {}
    public record Option(String optionId, String code, String label, String selectionGroup,
                         String availability) {}
}
