package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;

/**
 * 平台基准价版本（2026-10-20）。
 *
 * <p>版本是某一地区“当前生效 PUBLISHED 基准价”的批量快照；用户新建地区报价配置时
 * 引用最新版本，之后配置独立，可显式同步到新版本。</p>
 */
@Service
public class BudgetPriceBookService {
    private static final int MAX_NOTE = 200;

    private final JdbcTemplate jdbc;
    private AiDesignBaselineSource baselineSource;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBaselineSource(AiDesignBaselineSource source) { baselineSource = source; }
    private final TransactionTemplate tx;
    private final AuditPort auditPort;

    public BudgetPriceBookService(DataSource dataSource, PlatformTransactionManager transactionManager, AuditPort auditPort) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.auditPort = auditPort;
    }

    public record SnapshotPrice(long unitPriceCents, int priceVersion, String priceId) {}

    public record Snapshot(int version, int itemCount, String note, String createdAt, String createdBy,
                           Map<Long, SnapshotPrice> prices) {}

    public record VersionView(int version, int itemCount, String note, String createdAt, String createdBy, boolean current) {}

    public record PriceBook(int version, int itemCount, String note, String createdAt, boolean dirty,
                            List<VersionView> versions) {}

    /** 最新版本；不存在则按当前生效价自动建立 v1（地区首次使用/测试种子）。 */
    public Snapshot latest(String regionCode) {
        if (baselineSource != null) return tx.execute(status -> {
            Region locked = region(regionCode, true);
            Map<Long, SnapshotPrice> current = currentItems(locked.id());
            var rows = latestRow(locked.id());
            Snapshot previous = rows.isEmpty() ? new Snapshot(0, 0, null, null, null, Map.of()) : snapshot(rows.get(0));
            if (!dirty(previous, current)) return previous;
            // Only an immutable history for user configurations; the baseline always comes from A's API.
            return createVersion(locked, previous.version() + 1, "AI设计后台发布价", current, 0L);
        });
        Region region = region(regionCode, false);
        var rows = latestRow(region.id());
        if (!rows.isEmpty()) return snapshot(rows.get(0));
        return tx.execute(status -> {
            Region locked = region(regionCode, true);
            var afterLock = latestRow(locked.id());
            if (!afterLock.isEmpty()) return snapshot(afterLock.get(0));
            Map<Long, SnapshotPrice> current = currentItems(locked.id());
            if (current.isEmpty()) return new Snapshot(0, 0, null, null, null, Map.of());
            return createVersion(locked, 1, "初始版本", current, 0L);
        });
    }

    public int latestVersion(String regionCode) {
        return latest(regionCode).version();
    }

    /** 指定版本的价目；版本不存在/为 0 时回落到最新版本。 */
    public Snapshot atVersion(String regionCode, int version) {
        if (version <= 0) return latest(regionCode);
        Region region = region(regionCode, false);
        var rows = jdbc.queryForList("SELECT * FROM budget_price_book_version WHERE tenant_id = 0 AND region_id = ? AND version = ?",
                region.id(), version);
        if (rows.isEmpty()) return latest(regionCode);
        return snapshot(rows.get(0));
    }

    public PriceBook current(String regionCode) {
        Snapshot latest = latest(regionCode);
        Map<Long, SnapshotPrice> current = currentItems(region(regionCode, false).id());
        boolean dirty = dirty(latest, current);
        return new PriceBook(latest.version(), latest.itemCount(), latest.note(), latest.createdAt(), dirty, versions(regionCode));
    }

    /** 管理员把当前生效价快照为新版本；expectedVersion 防止并发/重复发布。 */
    public PriceBook publish(long actor, String regionCode, Map<String, Object> body) {
        fields(body, "expectedVersion", "note");
        int expected = integer(body.get("expectedVersion"), 0, Integer.MAX_VALUE - 1);
        String note = text(body.get("note"), MAX_NOTE);
        tx.executeWithoutResult(status -> {
            Region region = region(regionCode, true);
            var rows = latestRow(region.id());
            int currentVersion = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("version")).intValue();
            if (expected != currentVersion) throw exception(BUDGET_PRICE_BOOK_STALE);
            Map<Long, SnapshotPrice> current = currentItems(region.id());
            if (current.isEmpty()) throw exception(BUDGET_PRICE_BOOK_EMPTY);
            Snapshot created = createVersion(region, currentVersion + 1, note, current, actor);
            audit(actor, region.code(), created.version(), current.size(), note);
        });
        return current(regionCode);
    }

    public List<VersionView> history(String regionCode) {
        return versions(regionCode);
    }

    private Snapshot createVersion(Region region, int version, String note, Map<Long, SnapshotPrice> prices, long actor) {
        String items = JsonUtils.toJsonString(itemsObject(prices));
        Timestamp now = jdbc.queryForObject("SELECT now()", Timestamp.class);
        jdbc.update("INSERT INTO budget_price_book_version (id, tenant_id, region_id, region_code, version, note, items, item_count, created_by, created_at) "
                        + "VALUES (?, 0, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)",
                IdWorker.getId(), region.id(), region.code(), version, note, items, prices.size(), actor, now);
        jdbc.update("UPDATE budget_region SET price_book_version = ?, updater = ?, update_time = now() WHERE tenant_id = 0 AND id = ?",
                version, String.valueOf(actor), region.id());
        return new Snapshot(version, prices.size(), note, now.toInstant().toString(), String.valueOf(actor), Map.copyOf(prices));
    }

    private Snapshot snapshot(Map<String, Object> row) {
        String json = String.valueOf(row.get("items"));
        Map<Long, SnapshotPrice> prices = parseItems(json);
        Timestamp createdAt = (Timestamp) row.get("created_at");
        return new Snapshot(((Number) row.get("version")).intValue(), ((Number) row.get("item_count")).intValue(),
                (String) row.get("note"), createdAt == null ? null : createdAt.toInstant().toString(),
                row.get("created_by") == null ? null : String.valueOf(row.get("created_by")), Map.copyOf(prices));
    }

    private Map<Long, SnapshotPrice> parseItems(String json) {
        Map<String, Map<String, Object>> raw = JsonUtils.parseObject(json, new TypeReference<Map<String, Map<String, Object>>>() {});
        if (raw == null) return Map.of();
        Map<Long, SnapshotPrice> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            long optionId = Long.parseLong(entry.getKey());
            Map<String, Object> value = entry.getValue();
            if (value == null || !(value.get("unitPriceCents") instanceof Number cents)
                    || !(value.get("priceVersion") instanceof Number priceVersion) || value.get("priceId") == null) {
                throw exception(BUDGET_PRICE_BOOK_INVALID);
            }
            result.put(optionId, new SnapshotPrice(cents.longValue(), priceVersion.intValue(), String.valueOf(value.get("priceId"))));
        }
        return result;
    }

    private Map<String, Object> itemsObject(Map<Long, SnapshotPrice> prices) {
        Map<String, Object> result = new TreeMap<>();
        prices.forEach((optionId, price) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("unitPriceCents", price.unitPriceCents());
            value.put("priceVersion", price.priceVersion());
            value.put("priceId", price.priceId());
            result.put(String.valueOf(optionId), value);
        });
        return result;
    }

    private Map<Long, SnapshotPrice> currentItems(long regionId) {
        if (baselineSource != null) {
            String code = jdbc.queryForObject("SELECT code FROM budget_region WHERE tenant_id = 0 AND id = ? AND deleted = FALSE", String.class, regionId);
            Map<Long, SnapshotPrice> sourced = new LinkedHashMap<>();
            baselineSource.published(code, java.time.Instant.now()).forEach((option, price) -> sourced.put(option,
                new SnapshotPrice(price.cents(), price.version(), String.valueOf(price.priceId()))));
            return sourced;
        }
        Map<Long, SnapshotPrice> result = new LinkedHashMap<>();
        var rows = jdbc.queryForList("SELECT id, option_id, unit_price_cents, version FROM budget_item_price "
                + "WHERE tenant_id = 0 AND region_id = ? AND status = 'PUBLISHED' AND deleted = FALSE "
                + "AND effective_at <= now() AND (expires_at IS NULL OR expires_at > now()) ORDER BY id", regionId);
        for (var row : rows) {
            long optionId = ((Number) row.get("option_id")).longValue();
            if (result.containsKey(optionId)) throw exception(BUDGET_PRICE_CONFLICT);
            result.put(optionId, new SnapshotPrice(((Number) row.get("unit_price_cents")).longValue(),
                    ((Number) row.get("version")).intValue(), String.valueOf(row.get("id"))));
        }
        return result;
    }

    private boolean dirty(Snapshot latest, Map<Long, SnapshotPrice> current) {
        if (latest.version() <= 0) return !current.isEmpty();
        Map<Long, Long> left = new TreeMap<>();
        latest.prices().forEach((optionId, price) -> left.put(optionId, price.unitPriceCents()));
        Map<Long, Long> right = new TreeMap<>();
        current.forEach((optionId, price) -> right.put(optionId, price.unitPriceCents()));
        return !left.equals(right);
    }

    private List<VersionView> versions(String regionCode) {
        Region region = region(regionCode, false);
        int current = latest(regionCode).version();
        return jdbc.queryForList("SELECT version, item_count, note, created_at, created_by FROM budget_price_book_version "
                        + "WHERE tenant_id = 0 AND region_id = ? ORDER BY version DESC LIMIT 20", region.id())
                .stream().map(row -> new VersionView(((Number) row.get("version")).intValue(),
                        ((Number) row.get("item_count")).intValue(), (String) row.get("note"),
                        row.get("created_at") == null ? null : ((Timestamp) row.get("created_at")).toInstant().toString(),
                        row.get("created_by") == null ? null : String.valueOf(row.get("created_by")),
                        ((Number) row.get("version")).intValue() == current)).toList();
    }

    private List<Map<String, Object>> latestRow(long regionId) {
        return jdbc.queryForList("SELECT * FROM budget_price_book_version WHERE tenant_id = 0 AND region_id = ? "
                + "ORDER BY version DESC LIMIT 1", regionId);
    }

    private Region region(String regionCode, boolean lock) {
        if (regionCode == null || regionCode.length() > 32 || !regionCode.matches("[A-Za-z0-9_-]+")) {
            throw exception(BUDGET_PRICE_BOOK_INVALID);
        }
        var rows = jdbc.queryForList("SELECT id, code, name, price_book_version FROM budget_region "
                + "WHERE tenant_id = 0 AND code = ? AND deleted = FALSE" + (lock ? " FOR UPDATE" : ""), regionCode);
        if (rows.isEmpty()) throw exception(BUDGET_PRICE_BOOK_INVALID);
        var row = rows.get(0);
        return new Region(((Number) row.get("id")).longValue(), (String) row.get("code"), (String) row.get("name"),
                row.get("price_book_version") == null ? 0 : ((Number) row.get("price_book_version")).intValue());
    }

    private record Region(long id, String code, String name, int version) {}

    private void audit(long actor, String regionCode, int version, int itemCount, String note) {
        auditPort.record(AuditEventMessage.builder().eventType("BUDGET_PRICE_BOOK_CHANGED")
                .actorType(AuditEventMessage.ActorType.ADMIN).actorId(String.valueOf(actor)).action("PUBLISH")
                .bizType("budget_price_book_version").bizId(regionCode + ":" + version)
                .result(AuditEventMessage.AuditResult.SUCCESS).tenantId(0L)
                .detail(Map.of("version", version, "itemCount", itemCount, "note", note)).build());
    }

    private static void fields(Map<String, Object> body, String... allowed) {
        if (body == null || !Set.of(allowed).containsAll(body.keySet())) throw exception(BUDGET_PRICE_BOOK_INVALID);
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.trim()) || result.length() > max) {
            throw exception(BUDGET_PRICE_BOOK_INVALID);
        }
        return result;
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number number) || number instanceof Double || number instanceof Float) {
            throw exception(BUDGET_PRICE_BOOK_INVALID);
        }
        long result;
        try { result = new java.math.BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException invalid) { throw exception(BUDGET_PRICE_BOOK_INVALID); }
        if (result < min || result > max) throw exception(BUDGET_PRICE_BOOK_INVALID);
        return (int) result;
    }
}
