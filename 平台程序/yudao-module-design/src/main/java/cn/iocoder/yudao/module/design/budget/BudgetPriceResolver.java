package cn.iocoder.yudao.module.design.budget;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_PRICE_CONFLICT;

/**
 * 统一取价解析器（2026-10-20 地区报价配置）。
 *
 * <p>所有用户测算、目录可用性、后台补选都必须走这里，避免出现多套取价口径。</p>
 *
 * <p>取价顺序：</p>
 * <ul>
 *   <li>平台地区：本地区账号覆盖价 → 账号通用价（历史兼容） → 本地区 PUBLISHED 基准价 → MISSING</li>
 *   <li>用户地区：本配置项价（已保存） → 未配置时武汉 PUBLISHED 基准价（WUHAN_FALLBACK） → MISSING</li>
 * </ul>
 */
public final class BudgetPriceResolver {
    /** 自定义地区的默认基准地区（产品口径：武汉公布价）。 */
    public static final String BASELINE_REGION_CODE = "WUHAN";
    private final JdbcTemplate jdbc;

    public BudgetPriceResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Region(long id, String code, String name, int version, boolean custom,
                         String status, BigDecimal coefficient, Integer priceVersion, Instant configuredAt) {
        public boolean configured() { return !custom || configuredAt != null; }
    }

    public record Price(Long unitPriceCents, String source, Long accountPriceId, Integer accountPriceVersion,
                        Long priceVersionId, Integer priceVersion, Integer basePriceVersion,
                        Long basePriceCents, BigDecimal coefficient, String freeReason,
                        String reason, String updatedAt, String effectiveAt, String expiresAt) {
        public Price(Long unitPriceCents, String source, Long accountPriceId, Integer accountPriceVersion,
                     Long priceVersionId, Integer priceVersion, Integer basePriceVersion,
                     Long basePriceCents, BigDecimal coefficient, String freeReason) {
            this(unitPriceCents, source, accountPriceId, accountPriceVersion, priceVersionId, priceVersion,
                    basePriceVersion, basePriceCents, coefficient, freeReason, null, null, null, null);
        }
        public Price(Long unitPriceCents, String source, Long accountPriceId, Integer accountPriceVersion,
                     Long priceVersionId, Integer priceVersion, Integer basePriceVersion,
                     Long basePriceCents, BigDecimal coefficient, String freeReason,
                     String reason, String updatedAt) {
            this(unitPriceCents, source, accountPriceId, accountPriceVersion, priceVersionId, priceVersion,
                    basePriceVersion, basePriceCents, coefficient, freeReason, reason, updatedAt, null, null);
        }

        /** 是否使用了用户自己的价格（用户配置/账号覆盖/武汉兜底），用于正式报价门禁。 */
        public boolean userPriced() {
            return switch (source) {
                case "PLATFORM_OVERRIDE", "GLOBAL_OVERRIDE", "PROFILE_COPIED", "PROFILE_COEFFICIENT",
                     "PROFILE_MANUAL", "WUHAN_FALLBACK" -> true;
                default -> false;
            };
        }
    }

    /** 解析地区：平台地区优先，其次本人自定义地区；不存在返回参数错误。 */
    public Region region(long userId, String regionCode) {
        if (regionCode == null || regionCode.length() > 32 || !regionCode.matches("[A-Za-z0-9_-]+")) {
            throw exception(BUDGET_INPUT_INVALID);
        }
        var platform = jdbc.queryForList("SELECT id, code, name, version FROM budget_region "
                + "WHERE tenant_id = 0 AND code = ? AND enabled = TRUE AND deleted = FALSE", regionCode);
        if (!platform.isEmpty()) {
            var row = platform.get(0);
            return new Region(number(row, "id"), (String) row.get("code"), (String) row.get("name"),
                    ((Number) row.get("version")).intValue(), false, "ACTIVE", null, null, null);
        }
        var custom = jdbc.queryForList("SELECT id, code, name, version, status, coefficient, price_version, configured_at "
                + "FROM budget_user_region WHERE tenant_id = 0 AND user_id = ? AND code = ? AND deleted = FALSE", userId, regionCode);
        if (custom.isEmpty()) throw exception(BUDGET_INPUT_INVALID);
        var row = custom.get(0);
        return new Region(number(row, "id"), (String) row.get("code"), (String) row.get("name"),
                ((Number) row.get("version")).intValue(), true, (String) row.get("status"),
                decimal(row.get("coefficient")), row.get("price_version") == null ? null : ((Number) row.get("price_version")).intValue(),
                instant(row.get("configured_at")));
    }

    /** 解析某选项的有效单价；缺价返回 unitPriceCents=null 的 Price，不按 0 计算。 */
    public Price price(long userId, Region region, long optionId, Instant at) {
        if (region.custom()) return customPrice(userId, region, optionId, at);
        Price override = activeOverride(userId, "PLATFORM", region.code(), optionId, "PLATFORM_OVERRIDE");
        if (override != null) return withBaseline(override, published(region.id(), optionId, at, "PLATFORM_BASELINE"));
        Price global = activeOverride(userId, "GLOBAL", null, optionId, "GLOBAL_OVERRIDE");
        if (global != null) return withBaseline(global, published(region.id(), optionId, at, "PLATFORM_BASELINE"));
        return published(region.id(), optionId, at, "PLATFORM_BASELINE");
    }

    /**
     * 目录可用性：自定义地区未保存配置时看武汉是否有价；已保存配置只看配置行。
     * 平台地区看本地区基准价或账号覆盖价。
     */
    public boolean available(long userId, Region region, long optionId, Instant at) {
        if (region.custom()) {
            if (activeOverride(userId, "USER", region.code(), optionId, "PROFILE_MANUAL") != null) return true;
            // 历史账号通用价继续作为兜底（旧 T14 数据），但地区配置行优先。
            if (activeOverride(userId, "GLOBAL", null, optionId, "GLOBAL_OVERRIDE") != null) return true;
            if (!region.configured()) {
                Long baseId = baselineRegionId();
                return baseId != null && published(baseId, optionId, at, "PLATFORM_BASELINE").unitPriceCents() != null;
            }
            return false;
        }
        return activeOverride(userId, "PLATFORM", region.code(), optionId, "PLATFORM_OVERRIDE") != null
                || activeOverride(userId, "GLOBAL", null, optionId, "GLOBAL_OVERRIDE") != null
                || published(region.id(), optionId, at, "PLATFORM_BASELINE").unitPriceCents() != null;
    }

    /** 平台地区当前有效基准价（用于展示基准价，不参与覆盖判断）。 */
    public Price regionBaseline(long regionId, long optionId, Instant at) {
        return published(regionId, optionId, at, "PLATFORM_BASELINE");
    }

    /** 配置页/展示用：武汉当前有效基准价（与自定义地区无关）。 */
    public Price baseline(String optionId, Instant at) {
        Long baseId = baselineRegionId();
        if (baseId == null) return new Price(null, "MISSING", null, null, null, null, null, null, null, null);
        return published(baseId, BudgetInputs.positiveId(optionId), at, "PLATFORM_BASELINE");
    }

    private Long baselineRegionId() {
        var rows = jdbc.queryForList("SELECT id FROM budget_region WHERE tenant_id = 0 AND code = ? AND enabled = TRUE AND deleted = FALSE",
                BASELINE_REGION_CODE);
        return rows.isEmpty() ? null : number(rows.get(0), "id");
    }

    /** 自定义地区配置行（含已停用/历史状态，调用方自行判断 status）。 */
    public List<Map<String, Object>> userRows(long userId, String regionCode) {
        return jdbc.queryForList("SELECT * FROM budget_account_price WHERE tenant_id = 0 AND account_id = ? "
                + "AND region_scope = 'USER' AND region_code = ? AND status = 'ACTIVE' AND deleted = FALSE", userId, regionCode);
    }

    /** 覆盖价仍保留被覆盖基准价的版本号与生效窗口，供审计对账（T14 合同）。 */
    private static Price withBaseline(Price override, Price baseline) {
        return new Price(override.unitPriceCents(), override.source(), override.accountPriceId(), override.accountPriceVersion(),
                baseline.priceVersionId(), baseline.priceVersion(), baseline.basePriceVersion(), baseline.basePriceCents(),
                null, override.unitPriceCents() != null && override.unitPriceCents() == 0 ? baseline.freeReason() : null,
                override.reason(), override.updatedAt(), baseline.effectiveAt(), baseline.expiresAt());
    }

    private Price customPrice(long userId, Region region, long optionId, Instant at) {
        var rows = jdbc.queryForList("SELECT * FROM budget_account_price WHERE tenant_id = 0 AND account_id = ? "
                + "AND region_scope = 'USER' AND region_code = ? AND option_id = ? "
                + "AND status = 'ACTIVE' AND deleted = FALSE", userId, region.code(), optionId);
        if (!rows.isEmpty()) {
            var row = rows.get(0);
            String source = switch ((String) row.get("price_source")) {
                case "COPIED" -> "PROFILE_COPIED";
                case "COEFFICIENT" -> "PROFILE_COEFFICIENT";
                default -> "PROFILE_MANUAL";
            };
            return new Price(nullableNumber(row, "unit_price_cents"), source, number(row, "id"),
                    ((Number) row.get("version")).intValue(), null, null,
                    nullableInteger(row, "base_price_version"), nullableNumber(row, "base_price_cents"),
                    decimal(row.get("coefficient")), null, (String) row.get("reason"), instantText(row.get("update_time")));
        }
        // 历史账号通用价（T14 旧数据）继续兜底；地区配置行优先。
        Price global = activeOverride(userId, "GLOBAL", null, optionId, "GLOBAL_OVERRIDE");
        if (global != null) return global;
        // 未保存过配置的自定义地区：按武汉价兜底，并在快照明确标记；保存过配置后缺项就是 MISSING。
        if (!region.configured()) {
            Long baseId = baselineRegionId();
            if (baseId != null) {
                Price fallback = published(baseId, optionId, at, "WUHAN_FALLBACK");
                if (fallback.unitPriceCents() != null) return fallback;
            }
        }
        return new Price(null, "MISSING", null, null, null, null, null, null, null, null);
    }

    private Price activeOverride(long userId, String scope, String regionCode, long optionId, String source) {
        String code = regionCode == null ? "" : regionCode;
        var rows = jdbc.queryForList("SELECT * FROM budget_account_price WHERE tenant_id = 0 AND account_id = ? "
                + "AND region_scope = ? AND region_code = ? AND option_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                userId, scope, code, optionId);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        return new Price(nullableNumber(row, "unit_price_cents"), source, number(row, "id"),
                ((Number) row.get("version")).intValue(), null, null,
                nullableInteger(row, "base_price_version"), nullableNumber(row, "base_price_cents"),
                decimal(row.get("coefficient")), null, (String) row.get("reason"), instantText(row.get("update_time")));
    }

    private Price published(long regionId, long optionId, Instant at, String source) {
        var rows = jdbc.queryForList("SELECT id, unit_price_cents, free_reason, version, effective_at, expires_at FROM budget_item_price "
                + "WHERE tenant_id = 0 AND region_id = ? AND option_id = ? AND status = 'PUBLISHED' AND deleted = FALSE "
                + "AND effective_at <= ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY id LIMIT 2",
                regionId, optionId, Timestamp.from(at), Timestamp.from(at));
        if (rows.size() > 1) throw exception(BUDGET_PRICE_CONFLICT);
        if (rows.isEmpty()) return new Price(null, source, null, null, null, null, null, null, null, null);
        var row = rows.get(0);
        return new Price(nullableNumber(row, "unit_price_cents"), source, null, null, number(row, "id"),
                ((Number) row.get("version")).intValue(), ((Number) row.get("version")).intValue(),
                nullableNumber(row, "unit_price_cents"), null, (String) row.get("free_reason"),
                null, null, instantText(row.get("effective_at")), instantText(row.get("expires_at")));
    }

    private static Long nullableNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInteger(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).intValue();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static String instantText(Object value) {
        Instant instant = instant(value);
        return instant == null ? null : instant.toString();
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(value.toString());
    }
}
