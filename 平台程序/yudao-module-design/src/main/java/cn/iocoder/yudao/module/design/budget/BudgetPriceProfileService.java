package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetCatalogRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppMyPriceRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;

/**
 * 地区报价配置（用户私有）：武汉基准价 × 系数带入，逐项可改，保存即新版本。
 *
 * <p>边界：配置只属于创建账号；管理端没有读取/代改明细的入口，也不能用于平台正式报价。</p>
 */
@Service
public class BudgetPriceProfileService {
    private static final long MAX_CENTS = 100_000_000L;
    private static final int MAX_PROFILES = 20;
    private static final Map<String, String> UNIT_LABELS = Map.of(
            "SQM", "平方米", "METER", "米", "PIECE", "个", "SET", "套", "HOUSEHOLD", "户", "ITEM", "项");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditPort auditPort;
    private final BudgetPriceResolver prices;
    private final BudgetPriceBookService priceBooks;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBaselineSource(AiDesignBaselineSource source) {
        prices.setBaselineSource(source);
        priceBooks.setBaselineSource(source);
    }

    public BudgetPriceProfileService(DataSource dataSource, PlatformTransactionManager transactionManager, AuditPort auditPort) {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
        this.auditPort = auditPort;
        this.prices = new BudgetPriceResolver(jdbc);
        this.priceBooks = new BudgetPriceBookService(dataSource, transactionManager, auditPort);
    }

    public record CopyResult(AppBudgetCatalogRespVO.MyRegion profile, int updatedCount, int preservedManualCount, int missingCount) {}

    private record OptionRow(long itemId, String itemCode, String itemName, String category, long optionId,
                             String optionCode, String optionLabel, String selectionGroup, String unit, int sortOrder) {}

    public List<AppBudgetCatalogRespVO.MyRegion> list(long userId) {
        int total = totalStandardOptions();
        var rows = jdbc.queryForList("SELECT r.*, "
                + "(SELECT count(*) FROM budget_account_price a WHERE a.tenant_id = r.tenant_id AND a.account_id = r.user_id "
                + "AND a.region_scope = 'USER' AND a.region_code = r.code AND a.status = 'ACTIVE' AND a.deleted = FALSE) AS item_count, "
                + "(SELECT count(*) FROM budget_account_price a WHERE a.tenant_id = r.tenant_id AND a.account_id = r.user_id "
                + "AND a.region_scope = 'USER' AND a.region_code = r.code AND a.status = 'ACTIVE' AND a.deleted = FALSE "
                + "AND a.price_source = 'MANUAL') AS manual_count "
                + "FROM budget_user_region r WHERE r.tenant_id = 0 AND r.user_id = ? AND r.deleted = FALSE AND r.status = 'ACTIVE' "
                + "ORDER BY r.id DESC", userId);
        return rows.stream().map(row -> view(row, total)).toList();
    }

    public AppBudgetCatalogRespVO.MyRegion get(long userId, String code) {
        Map<String, Object> row = requireHeader(userId, code, false);
        return view(row, totalStandardOptions());
    }

    public AppBudgetCatalogRespVO.MyRegion create(long userId, Map<String, Object> body) {
        fields(body, "name", "coefficient", "copyFrom");
        String name = profileName(body.get("name"));
        BigDecimal coefficient = coefficient(body.get("coefficient"), BigDecimal.ONE);
        String copyFrom = optionalCode(body.get("copyFrom"), "WUHAN");
        return tx.execute(status -> {
            long active = Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM budget_user_region "
                    + "WHERE tenant_id = 0 AND user_id = ? AND status = 'ACTIVE' AND deleted = FALSE", Long.class, userId)).orElse(0L);
            if (active >= MAX_PROFILES) throw profileError("最多创建 " + MAX_PROFILES + " 个地区报价配置，请先归档不用的配置");
            var existing = jdbc.queryForList("SELECT * FROM budget_user_region WHERE tenant_id = 0 AND user_id = ? "
                    + "AND name = ? AND status = 'ACTIVE' AND deleted = FALSE", userId, name);
            if (!existing.isEmpty()) return view(existing.get(0), totalStandardOptions());

            String code = "u" + userId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            long id = IdWorker.getId();
            boolean fromWuhan = "WUHAN".equals(copyFrom);
            BudgetPriceBookService.Snapshot base = fromWuhan ? priceBooks.latest(BudgetPriceResolver.BASELINE_REGION_CODE) : null;
            int baseVersion = base == null ? 0 : base.version();
            if (!fromWuhan) {
                baseVersion = Optional.ofNullable(jdbc.queryForObject("SELECT base_version FROM budget_user_region "
                        + "WHERE tenant_id = 0 AND user_id = ? AND code = ? AND status = 'ACTIVE' AND deleted = FALSE",
                        Integer.class, userId, copyFrom)).orElse(0);
            }
            try {
                jdbc.update("INSERT INTO budget_user_region (id, user_id, code, name, base_region_code, base_version, coefficient, "
                                + "price_version, configured_at, status, version, tenant_id, creator, updater) "
                                + "VALUES (?, ?, ?, ?, 'WUHAN', ?, ?, 1, now(), 'ACTIVE', 1, 0, ?, ?)",
                        id, userId, code, name, baseVersion, coefficient, String.valueOf(userId), String.valueOf(userId));
            } catch (DuplicateKeyException duplicate) {
                throw profileError("地区名称已存在（可能已归档），请换一个名称");
            }
            int copied;
            if (fromWuhan) {
                copied = copyFromWuhan(userId, code, coefficient, 1, base.prices());
            } else {
                copied = copyFromProfile(userId, code, copyFrom, 1);
            }
            audit(userId, "CREATE", code, Map.of("coefficient", coefficient.stripTrailingZeros().toPlainString(),
                    "copiedCount", copied, "copyFrom", copyFrom));
            return view(requireHeader(userId, code, false), totalStandardOptions());
        });
    }

    public AppBudgetCatalogRespVO.MyRegion update(long userId, String code, Map<String, Object> body) {
        fields(body, "name", "coefficient", "status", "expectedVersion");
        return tx.execute(status -> {
            Map<String, Object> row = requireHeader(userId, code, true);
            int expected = expectedVersion(body.get("expectedVersion"));
            if (((Number) row.get("version")).intValue() != expected) throw exception(STATE_VERSION_CONFLICT);
            String name = body.containsKey("name") ? profileName(body.get("name")) : (String) row.get("name");
            BigDecimal coefficient = body.containsKey("coefficient") ? coefficient(body.get("coefficient"), null)
                    : decimal(row.get("coefficient"));
            String nextStatus = body.containsKey("status") ? status(body.get("status")) : (String) row.get("status");
            if ("ARCHIVED".equals(nextStatus) && "ACTIVE".equals(row.get("status")) && !body.containsKey("name")) {
                name = archiveName(name);
            }
            try {
                jdbc.update("UPDATE budget_user_region SET name = ?, coefficient = ?, status = ?, version = version + 1, "
                                + "updater = ?, update_time = now() WHERE tenant_id = 0 AND user_id = ? AND code = ? AND deleted = FALSE",
                        name, coefficient, nextStatus, String.valueOf(userId), userId, code);
            } catch (DuplicateKeyException duplicate) {
                throw profileError("地区名称已存在，请换一个名称");
            }
            audit(userId, "UPDATE", code, Map.of("status", nextStatus,
                    "coefficient", coefficient.stripTrailingZeros().toPlainString()));
            return view(requireHeader(userId, code, false), totalStandardOptions());
        });
    }

    /** 按系数重算：ALL 覆盖手工价；COEFFICIENT_ONLY 保留手工价。 */
    public CopyResult copy(long userId, String code, Map<String, Object> body) {
        fields(body, "coefficient", "mode");
        BigDecimal coefficient = coefficient(body.get("coefficient"), null);
        String mode = optionalCode(body.get("mode"), "COEFFICIENT_ONLY");
        if (!Set.of("ALL", "COEFFICIENT_ONLY").contains(mode)) throw exception(BUDGET_PRICE_PROFILE_INVALID);
        return tx.execute(status -> {
            Map<String, Object> header = requireHeader(userId, code, true);
            int nextVersion = ((Number) header.get("price_version")).intValue() + 1;
            BudgetPriceBookService.Snapshot baseBook = priceBooks.latest(BudgetPriceResolver.BASELINE_REGION_CODE);
            Map<Long, BudgetPriceBookService.SnapshotPrice> basePrices = baseBook.prices();
            Map<Long, Map<String, Object>> existing = userRows(userId, code);
            int updated = 0, preserved = 0, missing = 0;
            for (OptionRow option : standardOptions()) {
                Map<String, Object> row = existing.get(option.optionId());
                if ("COEFFICIENT_ONLY".equals(mode) && row != null && "MANUAL".equals(row.get("price_source"))) {
                    preserved++;
                    continue;
                }
                BudgetPriceBookService.SnapshotPrice base = basePrices.get(option.optionId());
                if (base == null || base.unitPriceCents() <= 0) { missing++; continue; }
                long cents = coefficientPrice(base.unitPriceCents(), coefficient);
                writeRow(userId, code, option.optionId(), cents,
                        coefficient.compareTo(BigDecimal.ONE) == 0 ? "COPIED" : "COEFFICIENT",
                        base.unitPriceCents(), base.priceVersion(), coefficient, nextVersion);
                updated++;
            }
            jdbc.update("UPDATE budget_user_region SET coefficient = ?, base_version = ?, price_version = ?, configured_at = now(), "
                            + "version = version + 1, updater = ?, update_time = now() WHERE tenant_id = 0 AND user_id = ? AND code = ?",
                    coefficient, baseBook.version(), nextVersion, String.valueOf(userId), userId, code);
            audit(userId, "COPY", code, Map.of("coefficient", coefficient.stripTrailingZeros().toPlainString(),
                    "mode", mode, "updatedCount", updated, "preservedManualCount", preserved, "missingCount", missing));
            return new CopyResult(get(userId, code), updated, preserved, missing);
        });
    }

    /** 配置页批量保存：服务端对系数价重算，不信任客户端金额。 */
    public AppBudgetCatalogRespVO.MyRegion save(long userId, String code, Map<String, Object> body) {
        fields(body, "expectedVersion", "coefficient", "items");
        Object rawItems = body.get("items");
        if (!(rawItems instanceof List<?> items) || items.size() > 200) throw exception(BUDGET_PRICE_PROFILE_INVALID);
        BigDecimal coefficient = coefficient(body.get("coefficient"), null);
        return tx.execute(status -> {
            Map<String, Object> header = requireHeader(userId, code, true);
            int expected = expectedVersion(body.get("expectedVersion"));
            if (((Number) header.get("price_version")).intValue() != expected) throw exception(BUDGET_PRICE_PROFILE_STALE);
            int nextVersion = expected + 1;
            Map<Long, BudgetPriceBookService.SnapshotPrice> basePrices = basePrices(header);
            Map<Long, Map<String, Object>> existing = userRows(userId, code);
            Set<Long> seen = new HashSet<>();
            int manual = 0, generated = 0, missing = 0;
            for (Object raw : items) {
                if (!(raw instanceof Map<?, ?> item)) throw exception(BUDGET_PRICE_PROFILE_INVALID);
                long optionId = positiveId(item.get("optionId"));
                if (!seen.add(optionId)) throw exception(BUDGET_PRICE_PROFILE_INVALID);
                OptionRow option = standardOption(optionId);
                if (option == null) throw exception(BUDGET_PRICE_PROFILE_INVALID);
                String source = String.valueOf(item.get("source") == null ? "MANUAL" : item.get("source"));
                if (!Set.of("MANUAL", "COEFFICIENT", "COPIED", "MISSING").contains(source)) {
                    throw exception(BUDGET_PRICE_PROFILE_INVALID);
                }
                Map<String, Object> previous = existing.get(optionId);
                Long baseCents = previous == null ? null : nullableNumber(previous, "base_price_cents");
                Integer baseVersion = previous == null ? null : nullableInteger(previous, "base_price_version");
                BudgetPriceBookService.SnapshotPrice basePrice = basePrices.get(optionId);
                if (baseCents == null && basePrice != null) { baseCents = basePrice.unitPriceCents(); baseVersion = basePrice.priceVersion(); }
                if ("MISSING".equals(source)) {
                    removeRow(userId, code, optionId);
                    missing++;
                    continue;
                }
                if ("MANUAL".equals(source)) {
                    long cents = cents(item.get("unitPriceCents"));
                    writeRow(userId, code, optionId, cents, "MANUAL", baseCents, baseVersion, coefficient, nextVersion);
                    manual++;
                } else {
                    if (baseCents == null || baseCents <= 0) { removeRow(userId, code, optionId); missing++; continue; }
                    long cents = coefficientPrice(baseCents, coefficient);
                    writeRow(userId, code, optionId, cents,
                            coefficient.compareTo(BigDecimal.ONE) == 0 ? "COPIED" : "COEFFICIENT",
                            baseCents, baseVersion, coefficient, nextVersion);
                    generated++;
                }
            }
            jdbc.update("UPDATE budget_user_region SET coefficient = ?, price_version = ?, configured_at = now(), "
                            + "version = version + 1, updater = ?, update_time = now() WHERE tenant_id = 0 AND user_id = ? AND code = ?",
                    coefficient, nextVersion, String.valueOf(userId), userId, code);
            audit(userId, "SAVE", code, Map.of("coefficient", coefficient.stripTrailingZeros().toPlainString(),
                    "manualCount", manual, "generatedCount", generated, "missingCount", missing));
            return get(userId, code);
        });
    }

    /** 配置明细：武汉基准 + 本地区价 + 来源。 */
    public AppMyPriceRespVO.Items listPrices(long userId, String regionCode) {
        BudgetPriceResolver.Region region = prices.region(userId, regionCode);
        if (!region.custom()) throw exception(BUDGET_PRICE_PROFILE_INVALID);
        AppBudgetCatalogRespVO.MyRegion header = get(userId, regionCode);
        Map<Long, BudgetPriceBookService.SnapshotPrice> basePrices = basePrices(header.baseVersion());
        Map<Long, Map<String, Object>> rows = userRows(userId, regionCode);
        List<AppMyPriceRespVO.Group> groups = new ArrayList<>();
        long currentItem = -1;
        List<AppMyPriceRespVO> options = null;
        String itemCode = null, itemName = null, category = null;
        for (OptionRow option : standardOptions()) {
            if (option.itemId() != currentItem) {
                if (options != null) groups.add(new AppMyPriceRespVO.Group(String.valueOf(currentItem), itemCode, itemName, category, options));
                currentItem = option.itemId();
                itemCode = option.itemCode();
                itemName = option.itemName();
                category = option.category();
                options = new ArrayList<>();
            }
            BudgetPriceBookService.SnapshotPrice base = basePrices.get(option.optionId());
            Map<String, Object> row = rows.get(option.optionId());
            Long baseCents = base == null ? null : base.unitPriceCents();
            Integer baseVersion = base == null ? null : base.priceVersion();
            Long myPrice = null;
            String source;
            String reason = null;
            String updatedAt = null;
            if (row != null) {
                baseCents = nullableNumber(row, "base_price_cents") == null ? baseCents : nullableNumber(row, "base_price_cents");
                baseVersion = nullableInteger(row, "base_price_version") == null ? baseVersion : nullableInteger(row, "base_price_version");
                myPrice = nullableNumber(row, "unit_price_cents");
                source = switch ((String) row.get("price_source")) {
                    case "COPIED" -> "PROFILE_COPIED";
                    case "COEFFICIENT" -> "PROFILE_COEFFICIENT";
                    default -> "PROFILE_MANUAL";
                };
                reason = (String) row.get("reason");
                updatedAt = instantText(row.get("update_time"));
            } else {
                source = !region.configured() && base != null ? "WUHAN_FALLBACK" : "MISSING";
            }
            options.add(new AppMyPriceRespVO(String.valueOf(option.optionId()), option.optionCode(), option.optionLabel(),
                    option.selectionGroup(), option.unit(), baseCents, myPrice, source, reason, updatedAt,
                    baseVersion, UNIT_LABELS.getOrDefault(option.unit(), option.unit())));
        }
        if (options != null) groups.add(new AppMyPriceRespVO.Group(String.valueOf(currentItem), itemCode, itemName, category, options));
        int latestBaseVersion = priceBooks.latestVersion(BudgetPriceResolver.BASELINE_REGION_CODE);
        int legacyGlobalCount = Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM budget_account_price "
                + "WHERE tenant_id = 0 AND account_id = ? AND region_scope = 'GLOBAL' AND region_code = '' "
                + "AND status = 'ACTIVE' AND deleted = FALSE", Integer.class, userId)).orElse(0);
        return new AppMyPriceRespVO.Items(region.code(), region.name(), true,
                new AppMyPriceRespVO.Profile("WUHAN", header.coefficient(), header.priceVersion(), header.configuredAt(),
                        header.itemCount(), header.manualCount(), header.missingCount(), header.totalCount(),
                        header.version(), header.status(), legacyGlobalCount, header.baseVersion(), latestBaseVersion,
                        header.baseVersion() < latestBaseVersion), List.copyOf(groups));
    }

    /** 单项改价（配置页也用批量保存；此接口保留给快捷改价/兼容旧客户端）。 */
    public AppMyPriceRespVO upsertOne(long userId, String regionCode, long optionId, Map<String, Object> body) {
        if (body == null || !Set.of("unitPriceCents", "reason").containsAll(body.keySet()) || !body.containsKey("unitPriceCents")) {
            throw exception(BUDGET_PRICE_PROFILE_INVALID);
        }
        long cents = cents(body.get("unitPriceCents"));
        String reason = body.get("reason") == null ? null : text(body.get("reason"), 500);
        return tx.execute(status -> {
            Map<String, Object> header = requireHeader(userId, regionCode, true);
            OptionRow option = standardOption(optionId);
            if (option == null) throw exception(BUDGET_PRICE_PROFILE_INVALID);
            Map<String, Object> previous = userRows(userId, regionCode).get(optionId);
            Long baseCents = previous == null ? null : nullableNumber(previous, "base_price_cents");
            Integer baseVersion = previous == null ? null : nullableInteger(previous, "base_price_version");
            BudgetPriceBookService.SnapshotPrice basePrice = basePrices(header).get(optionId);
            if (baseCents == null && basePrice != null) { baseCents = basePrice.unitPriceCents(); baseVersion = basePrice.priceVersion(); }
            writeRow(userId, regionCode, optionId, cents, "MANUAL", baseCents, baseVersion,
                    decimal(header.get("coefficient")), ((Number) header.get("price_version")).intValue() + 1);
            jdbc.update("UPDATE budget_account_price SET reason = ? WHERE tenant_id = 0 AND account_id = ? "
                            + "AND region_scope = 'USER' AND region_code = ? AND option_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                    reason, userId, regionCode, optionId);
            bumpHeader(userId, regionCode, decimal(header.get("coefficient")),
                    ((Number) header.get("price_version")).intValue() + 1);
            audit(userId, "SET_ONE", regionCode, Map.of("optionId", String.valueOf(optionId), "source", "MANUAL"));
            return optionView(userId, regionCode, option, "MANUAL");
        });
    }

    /** 恢复系数价；武汉也无基准价时删除配置行，回到 MISSING。 */
    public AppMyPriceRespVO resetOne(long userId, String regionCode, long optionId) {
        return tx.execute(status -> {
            Map<String, Object> header = requireHeader(userId, regionCode, true);
            OptionRow option = standardOption(optionId);
            if (option == null) throw exception(BUDGET_PRICE_PROFILE_INVALID);
            Map<String, Object> previous = userRows(userId, regionCode).get(optionId);
            Long baseCents = previous == null ? null : nullableNumber(previous, "base_price_cents");
            Integer baseVersion = previous == null ? null : nullableInteger(previous, "base_price_version");
            BudgetPriceBookService.SnapshotPrice basePrice = basePrices(header).get(optionId);
            if (baseCents == null && basePrice != null) { baseCents = basePrice.unitPriceCents(); baseVersion = basePrice.priceVersion(); }
            BigDecimal coefficient = decimal(header.get("coefficient"));
            if (baseCents == null || baseCents <= 0) {
                removeRow(userId, regionCode, optionId);
            } else {
                writeRow(userId, regionCode, optionId, coefficientPrice(baseCents, coefficient),
                        coefficient.compareTo(BigDecimal.ONE) == 0 ? "COPIED" : "COEFFICIENT",
                        baseCents, baseVersion, coefficient, ((Number) header.get("price_version")).intValue() + 1);
            }
            bumpHeader(userId, regionCode, coefficient, ((Number) header.get("price_version")).intValue() + 1);
            audit(userId, "RESET_ONE", regionCode, Map.of("optionId", String.valueOf(optionId)));
            return optionView(userId, regionCode, option, null);
        });
    }

    private AppMyPriceRespVO optionView(long userId, String regionCode, OptionRow option, String sourceHint) {
        Map<String, Object> header = requireHeader(userId, regionCode, false);
        BudgetPriceBookService.SnapshotPrice base = basePrices(header).get(option.optionId());
        Map<String, Object> row = userRows(userId, regionCode).get(option.optionId());
        Long baseCents = base == null ? null : base.unitPriceCents();
        Integer baseVersion = base == null ? null : base.priceVersion();
        Long myPrice = null;
        String source;
        String reason = null;
        String updatedAt = null;
        if (row != null) {
            baseCents = nullableNumber(row, "base_price_cents") == null ? baseCents : nullableNumber(row, "base_price_cents");
            baseVersion = nullableInteger(row, "base_price_version") == null ? baseVersion : nullableInteger(row, "base_price_version");
            myPrice = nullableNumber(row, "unit_price_cents");
            source = switch ((String) row.get("price_source")) {
                case "COPIED" -> "PROFILE_COPIED";
                case "COEFFICIENT" -> "PROFILE_COEFFICIENT";
                default -> "PROFILE_MANUAL";
            };
            reason = (String) row.get("reason");
            updatedAt = instantText(row.get("update_time"));
        } else {
            BudgetPriceResolver.Region region = prices.region(userId, regionCode);
            source = !region.configured() && base != null ? "WUHAN_FALLBACK" : "MISSING";
        }
        return new AppMyPriceRespVO(String.valueOf(option.optionId()), option.optionCode(), option.optionLabel(),
                option.selectionGroup(), option.unit(), baseCents, myPrice, source, reason, updatedAt,
                baseVersion, UNIT_LABELS.getOrDefault(option.unit(), option.unit()));
    }

    private int copyFromWuhan(long userId, String code, BigDecimal coefficient, int profileVersion,
                              Map<Long, BudgetPriceBookService.SnapshotPrice> basePrices) {
        int copied = 0;
        for (OptionRow option : standardOptions()) {
            BudgetPriceBookService.SnapshotPrice price = basePrices.get(option.optionId());
            if (price == null || price.unitPriceCents() <= 0) continue;
            writeRow(userId, code, option.optionId(), coefficientPrice(price.unitPriceCents(), coefficient),
                    coefficient.compareTo(BigDecimal.ONE) == 0 ? "COPIED" : "COEFFICIENT",
                    price.unitPriceCents(), price.priceVersion(), coefficient, profileVersion);
            copied++;
        }
        return copied;
    }

    private int copyFromProfile(long userId, String code, String sourceCode, int profileVersion) {
        var sourceHeader = jdbc.queryForList("SELECT * FROM budget_user_region WHERE tenant_id = 0 AND user_id = ? "
                + "AND code = ? AND status = 'ACTIVE' AND deleted = FALSE", userId, sourceCode);
        if (sourceHeader.isEmpty()) throw profileError("要复制的报价配置不存在或已归档");
        BigDecimal coefficient = decimal(sourceHeader.get(0).get("coefficient"));
        Map<Long, Map<String, Object>> rows = userRows(userId, sourceCode);
        int copied = 0;
        for (OptionRow option : standardOptions()) {
            Map<String, Object> row = rows.get(option.optionId());
            if (row == null) continue;
            writeRow(userId, code, option.optionId(), nullableNumber(row, "unit_price_cents"), (String) row.get("price_source"),
                    nullableNumber(row, "base_price_cents"), nullableInteger(row, "base_price_version"), coefficient, profileVersion);
            copied++;
        }
        return copied;
    }

    private void writeRow(long userId, String code, long optionId, long cents, String source,
                          Long baseCents, Integer baseVersion, BigDecimal coefficient, int profileVersion) {
        String cleanSource = switch (source == null ? "MANUAL" : source) {
            case "COPIED", "COEFFICIENT", "MANUAL" -> source;
            default -> "MANUAL";
        };
        int updated = jdbc.update("UPDATE budget_account_price SET unit_price_cents = ?, price_source = ?, "
                        + "base_price_cents = ?, base_price_version = ?, coefficient = ?, profile_version = ?, "
                        + ("MANUAL".equals(cleanSource) ? "" : "reason = NULL, ")
                        + "version = version + 1, updater = ?, update_time = now() "
                        + "WHERE tenant_id = 0 AND account_id = ? AND region_scope = 'USER' AND region_code = ? "
                        + "AND option_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                cents, cleanSource, baseCents, baseVersion, coefficient, profileVersion, String.valueOf(userId),
                userId, code, optionId);
        if (updated == 0) {
            jdbc.update("INSERT INTO budget_account_price (id, account_id, option_id, unit_price_cents, reason, status, "
                            + "version, tenant_id, creator, updater, region_scope, region_code, price_source, "
                            + "base_price_cents, base_price_version, coefficient, profile_version) "
                            + "VALUES (?, ?, ?, ?, NULL, 'ACTIVE', 1, 0, ?, ?, 'USER', ?, ?, ?, ?, ?, ?)",
                    IdWorker.getId(), userId, optionId, cents, String.valueOf(userId), String.valueOf(userId),
                    code, cleanSource, baseCents, baseVersion, coefficient, profileVersion);
        }
    }

    private void removeRow(long userId, String code, long optionId) {
        jdbc.update("UPDATE budget_account_price SET status = 'REMOVED', version = version + 1, updater = ?, update_time = now() "
                        + "WHERE tenant_id = 0 AND account_id = ? AND region_scope = 'USER' AND region_code = ? "
                        + "AND option_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                String.valueOf(userId), userId, code, optionId);
    }

    private void bumpHeader(long userId, String code, BigDecimal coefficient, int priceVersion) {
        jdbc.update("UPDATE budget_user_region SET coefficient = ?, price_version = ?, configured_at = now(), "
                        + "version = version + 1, updater = ?, update_time = now() WHERE tenant_id = 0 AND user_id = ? AND code = ?",
                coefficient, priceVersion, String.valueOf(userId), userId, code);
    }

    private Map<String, Object> requireHeader(long userId, String code, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM budget_user_region WHERE tenant_id = 0 AND user_id = ? AND code = ? "
                + "AND deleted = FALSE" + (lock ? " FOR UPDATE" : ""), userId, code);
        if (rows.isEmpty()) throw profileError("报价配置不存在或无权访问");
        return rows.get(0);
    }

    private AppBudgetCatalogRespVO.MyRegion view(Map<String, Object> row, int total) {
        long userId = ((Number) row.get("user_id")).longValue();
        String code = (String) row.get("code");
        int itemCount = row.get("item_count") == null ? count(userId, code) : ((Number) row.get("item_count")).intValue();
        int manualCount = row.get("manual_count") == null ? manualCount(userId, code) : ((Number) row.get("manual_count")).intValue();
        int baseVersion = row.get("base_version") == null ? 0 : ((Number) row.get("base_version")).intValue();
        int latestBaseVersion = priceBooks.latestVersion(BudgetPriceResolver.BASELINE_REGION_CODE);
        return new AppBudgetCatalogRespVO.MyRegion(code, (String) row.get("name"),
                decimal(row.get("coefficient")), ((Number) row.get("price_version")).intValue(),
                instantText(row.get("configured_at")), (String) row.get("status"), ((Number) row.get("version")).intValue(),
                itemCount, manualCount, Math.max(0, total - itemCount), total, baseVersion, latestBaseVersion);
    }

    private int count(long userId, String code) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM budget_account_price WHERE tenant_id = 0 "
                + "AND account_id = ? AND region_scope = 'USER' AND region_code = ? AND status = 'ACTIVE' AND deleted = FALSE",
                Integer.class, userId, code)).orElse(0);
    }

    private int manualCount(long userId, String code) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM budget_account_price WHERE tenant_id = 0 "
                + "AND account_id = ? AND region_scope = 'USER' AND region_code = ? AND status = 'ACTIVE' AND deleted = FALSE "
                + "AND price_source = 'MANUAL'", Integer.class, userId, code)).orElse(0);
    }

    private int totalStandardOptions() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM budget_item i JOIN budget_option o "
                + "ON o.tenant_id = i.tenant_id AND o.item_id = i.id AND o.enabled = TRUE AND o.deleted = FALSE "
                + "WHERE i.tenant_id = 0 AND i.source = 'STANDARD' AND i.enabled = TRUE AND i.public_selectable = TRUE "
                + "AND i.deleted = FALSE", Integer.class)).orElse(0);
    }

    private List<OptionRow> standardOptions() {
        return jdbc.query("SELECT i.id AS item_id, i.code AS item_code, i.name AS item_name, i.category, "
                        + "o.id AS option_id, o.code AS option_code, o.label AS option_label, o.selection_group, o.unit, o.sort_order "
                        + "FROM budget_item i JOIN budget_option o ON o.tenant_id = i.tenant_id AND o.item_id = i.id "
                        + "AND o.enabled = TRUE AND o.deleted = FALSE "
                        + "WHERE i.tenant_id = 0 AND i.source = 'STANDARD' AND i.enabled = TRUE AND i.public_selectable = TRUE "
                        + "AND i.deleted = FALSE ORDER BY i.sort_order, i.id, o.sort_order, o.id",
                (rs, index) -> new OptionRow(rs.getLong("item_id"), rs.getString("item_code"), rs.getString("item_name"),
                        rs.getString("category"), rs.getLong("option_id"), rs.getString("option_code"),
                        rs.getString("option_label"), rs.getString("selection_group"), rs.getString("unit"), rs.getInt("sort_order")));
    }

    private OptionRow standardOption(long optionId) {
        var rows = jdbc.query("SELECT i.id AS item_id, i.code AS item_code, i.name AS item_name, i.category, "
                        + "o.id AS option_id, o.code AS option_code, o.label AS option_label, o.selection_group, o.unit, o.sort_order "
                        + "FROM budget_item i JOIN budget_option o ON o.tenant_id = i.tenant_id AND o.item_id = i.id "
                        + "WHERE i.tenant_id = 0 AND i.source = 'STANDARD' AND i.enabled = TRUE AND i.public_selectable = TRUE "
                        + "AND i.deleted = FALSE AND o.enabled = TRUE AND o.deleted = FALSE AND o.id = ?",
                (rs, index) -> new OptionRow(rs.getLong("item_id"), rs.getString("item_code"), rs.getString("item_name"),
                        rs.getString("category"), rs.getLong("option_id"), rs.getString("option_code"),
                        rs.getString("option_label"), rs.getString("selection_group"), rs.getString("unit"), rs.getInt("sort_order")),
                optionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 配置所锁定的武汉基准版本；0/缺失时回落到最新版本。 */
    private Map<Long, BudgetPriceBookService.SnapshotPrice> basePrices(Map<String, Object> header) {
        int version = header.get("base_version") == null ? 0 : ((Number) header.get("base_version")).intValue();
        return basePrices(version);
    }

    private Map<Long, BudgetPriceBookService.SnapshotPrice> basePrices(int version) {
        return priceBooks.atVersion(BudgetPriceResolver.BASELINE_REGION_CODE, version).prices();
    }

    private Map<Long, Map<String, Object>> userRows(long userId, String code) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT * FROM budget_account_price WHERE tenant_id = 0 AND account_id = ? "
                + "AND region_scope = 'USER' AND region_code = ? AND status = 'ACTIVE' AND deleted = FALSE", userId, code)) {
            result.put(((Number) row.get("option_id")).longValue(), row);
        }
        return result;
    }

    private long coefficientPrice(long baseCents, BigDecimal coefficient) {
        long result = BigDecimal.valueOf(baseCents).multiply(coefficient).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (result < 1) throw profileError("系数过小，按基准价计算后低于 0.01 元");
        if (result > MAX_CENTS) throw profileError("按系数计算后单价超过 100 万元");
        return result;
    }

    private void audit(long userId, String action, String code, Map<String, Object> detail) {
        auditPort.record(AuditEventMessage.builder().eventType("BUDGET_PRICE_PROFILE_CHANGED")
                .actorType(AuditEventMessage.ActorType.USER).actorId(String.valueOf(userId)).action(action)
                .bizType("budget_user_region").bizId(code).result(AuditEventMessage.AuditResult.SUCCESS)
                .tenantId(0L).detail(detail).build());
    }

    private static void fields(Map<String, Object> body, String... allowed) {
        if (body == null || !Set.of(allowed).containsAll(body.keySet())) throw exception(BUDGET_PRICE_PROFILE_INVALID);
    }

    private static String profileName(Object value) {
        if (!(value instanceof String raw)) throw exception(BUDGET_PRICE_PROFILE_INVALID);
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 20) throw profileError("地区名称须为 1~20 个字符");
        return name;
    }

    private static BigDecimal coefficient(Object value, BigDecimal fallback) {
        if (value == null) {
            if (fallback == null) throw exception(BUDGET_PRICE_PROFILE_INVALID);
            return fallback;
        }
        if (!(value instanceof Number || value instanceof String)) throw exception(BUDGET_PRICE_PROFILE_INVALID);
        try {
            BigDecimal result = new BigDecimal(value.toString()).setScale(4, RoundingMode.HALF_UP);
            if (result.compareTo(new BigDecimal("0.5")) < 0 || result.compareTo(new BigDecimal("2.0")) > 0) {
                throw profileError("系数须在 0.50～2.00 之间");
            }
            return result;
        } catch (NumberFormatException invalid) {
            throw exception(BUDGET_PRICE_PROFILE_INVALID);
        }
    }

    private static String status(Object value) {
        if (!(value instanceof String result) || !Set.of("ACTIVE", "ARCHIVED").contains(result)) {
            throw exception(BUDGET_PRICE_PROFILE_INVALID);
        }
        return result;
    }

    private static String optionalCode(Object value, String fallback) {
        if (value == null) return fallback;
        if (!(value instanceof String result) || !result.matches("[A-Za-z0-9_-]{1,32}")) {
            throw exception(BUDGET_PRICE_PROFILE_INVALID);
        }
        return result;
    }

    private static int expectedVersion(Object value) {
        return (int) positive(value, 1, Integer.MAX_VALUE - 1);
    }

    private static long positiveId(Object value) {
        return positive(value, 1, Long.MAX_VALUE);
    }

    private static long cents(Object value) {
        long result = positive(value, 1, MAX_CENTS);
        return result;
    }

    private static long positive(Object value, long min, long max) {
        if (value instanceof String text) {
            if (!text.matches("[0-9]{1,19}")) throw exception(BUDGET_PRICE_PROFILE_INVALID);
            value = text;
        } else if (!(value instanceof Number) || value instanceof Double || value instanceof Float) {
            throw exception(BUDGET_PRICE_PROFILE_INVALID);
        }
        long result;
        try { result = new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException invalid) { throw exception(BUDGET_PRICE_PROFILE_INVALID); }
        if (result < min || result > max) throw exception(BUDGET_PRICE_PROFILE_INVALID);
        return result;
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.trim()) || result.length() > max) {
            throw exception(BUDGET_PRICE_PROFILE_INVALID);
        }
        return result;
    }

    private static String archiveName(String name) {
        String suffix = "（已归档 " + LocalDate.now() + "）";
        int keep = Math.max(1, 100 - suffix.length());
        String base = name.length() <= keep ? name : name.substring(0, keep);
        return base + suffix;
    }

    private static ServiceException profileError(String message) {
        return new ServiceException(BUDGET_PRICE_PROFILE_INVALID.getCode(), message);
    }

    private static Long nullableNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInteger(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).intValue();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static String instantText(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant().toString();
        return value.toString();
    }
}
