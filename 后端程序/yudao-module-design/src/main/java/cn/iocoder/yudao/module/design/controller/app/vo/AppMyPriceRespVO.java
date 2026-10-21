package cn.iocoder.yudao.module.design.controller.app.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 我的当地单价 / 地区报价配置。
 * 金额单位为分；平台地区 baselinePriceCents 是本地基准价，自定义地区是武汉基准价。
 * priceSource：ACCOUNT_OVERRIDE/DEFAULT/MISSING（平台地区）；PROFILE_COPIED/PROFILE_COEFFICIENT/
 * PROFILE_MANUAL/WUHAN_FALLBACK/MISSING（自定义地区配置）。
 */
public record AppMyPriceRespVO(String optionId, String code, String label, String selectionGroup, String unit,
                               Long baselinePriceCents, Long myPriceCents, String priceSource, String reason,
                               String updatedAt, Integer baselinePriceVersion, String unitLabel) {

    public AppMyPriceRespVO(String optionId, String code, String label, String selectionGroup, String unit,
                            Long baselinePriceCents, Long myPriceCents, String priceSource, String reason,
                            String updatedAt) {
        this(optionId, code, label, selectionGroup, unit, baselinePriceCents, myPriceCents, priceSource, reason,
                updatedAt, null, null);
    }

    public record Items(String regionCode, String regionName, boolean custom, Profile profile, List<Group> groups) {
        public Items(String regionCode, String regionName, List<Group> groups) {
            this(regionCode, regionName, false, null, groups);
        }
    }

    /** 用户报价配置头；仅用户本人可见。 */
    public record Profile(String baseRegionCode, BigDecimal coefficient, Integer priceVersion, String configuredAt,
                          int itemCount, int manualCount, int missingCount, int totalCount, int version, String status,
                          int legacyGlobalCount, int baseVersion, int latestBaseVersion, boolean baseStale) {}

    public record Group(String itemId, String itemCode, String itemName, String category,
                        List<AppMyPriceRespVO> options) {}
}
