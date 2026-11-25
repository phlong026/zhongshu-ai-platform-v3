package cn.iocoder.yudao.module.platform.announcement;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cn.iocoder.yudao.module.platform.announcement.AnnouncementModel.*;

@Service
public class AnnouncementService {
    private static final Set<String> STATES = Set.of("DRAFT", "PUBLISHED", "WITHDRAWN");
    private final JdbcTemplate jdbc;
    private final AnnouncementAccess access;

    public AnnouncementService(JdbcTemplate jdbc, AnnouncementAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public Options options() {
        var actor = access.admin(false);
        var brands = jdbc.query("SELECT id::text,name FROM pf_map_brand WHERE tenant_id=? ORDER BY name,id",
                (r, n) -> new Brand(r.getString("id"), r.getString("name")), actor.tenantId());
        return new Options(actor.canWrite(), brands);
    }

    @Transactional(readOnly = true)
    public Page<Summary> adminList(String keyword, String status, String brandId, int pageNo, int pageSize) {
        var actor = access.admin(false);
        String state = clean(status);
        check(state.isEmpty() || STATES.contains(state), "公告状态无效");
        StringBuilder where = new StringBuilder("a.tenant_id=?");
        List<Object> args = new ArrayList<>(List.of(actor.tenantId()));
        if (!state.isEmpty()) { where.append(" AND a.status=?"); args.add(state); }
        if (!clean(brandId).isEmpty()) {
            long brand = brandId(brandId);
            requireBrand(actor.tenantId(), brand);
            where.append(" AND (a.visibility='ALL' OR EXISTS(SELECT 1 FROM pf_announcement_brand ab WHERE ab.tenant_id=a.tenant_id AND ab.announcement_id=a.id AND ab.brand_id=?))");
            args.add(brand);
        }
        return page(where, args, keyword, pageNo, pageSize);
    }

    @Transactional(readOnly = true)
    public Page<Summary> appList(String keyword, int pageNo, int pageSize) {
        var actor = access.reader();
        StringBuilder where = new StringBuilder("a.tenant_id=? AND a.status='PUBLISHED'");
        List<Object> args = new ArrayList<>(List.of(actor.tenantId()));
        visible(where, args, actor);
        return page(where, args, keyword, pageNo, pageSize);
    }

    public Detail adminDetail(String id) {
        return detail(id, access.admin(false).tenantId());
    }

    @Transactional(readOnly = true)
    public Detail appDetail(String id) {
        var actor = access.reader();
        StringBuilder where = new StringBuilder("a.tenant_id=? AND a.id=? AND a.status='PUBLISHED'");
        List<Object> args = new ArrayList<>(List.of(actor.tenantId(), id));
        visible(where, args, actor);
        var rows = jdbc.query(select() + " WHERE " + where, this::map, args.toArray());
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    @Transactional
    public Detail create(Save input) {
        var actor = access.admin(true);
        Save value = validate(input, actor.tenantId());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO pf_announcement(id,tenant_id,title,summary,content,visibility,pinned,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), value.title(), value.summary(), value.content(), value.visibility(),
                value.pinned(), actor.userId(), actor.userId());
        saveBrands(id, actor.tenantId(), value.brandIds());
        Detail result = detail(id, actor.tenantId());
        audit(actor, "CREATE", result);
        return result;
    }

    @Transactional
    public Detail update(String id, Save input) {
        var actor = access.admin(true);
        var current = lock(id, actor.tenantId(), input == null ? null : input.expectedRevision());
        if ("PUBLISHED".equals(current.status())) throw new ServiceException(409, "请先撤回已发布公告再修改");
        Save value = validate(input, actor.tenantId());
        jdbc.update("""
                UPDATE pf_announcement SET title=?,summary=?,content=?,visibility=?,pinned=?,status='DRAFT',
                    revision=revision+1,updated_by=?,updated_at=now() WHERE id=? AND tenant_id=?
                """, value.title(), value.summary(), value.content(), value.visibility(), value.pinned(),
                actor.userId(), id, actor.tenantId());
        saveBrands(id, actor.tenantId(), value.brandIds());
        Detail result = detail(id, actor.tenantId());
        audit(actor, "UPDATE", result);
        return result;
    }

    @Transactional
    public Detail publish(String id, Action action) {
        var actor = access.admin(true);
        var current = lock(id, actor.tenantId(), action == null ? null : action.expectedRevision());
        if ("PUBLISHED".equals(current.status())) throw new ServiceException(409, "该公告已发布，请刷新页面");
        // Validate the actual persisted scope again, including references to existing tenant brands.
        validate(new Save(current.title(), current.summary(), current.content(), current.visibility(),
                current.brandIds(), current.pinned(), current.revision()), actor.tenantId());
        jdbc.update("""
                UPDATE pf_announcement SET status='PUBLISHED',published_at=now(),revision=revision+1,
                    updated_by=?,updated_at=now() WHERE id=? AND tenant_id=?
                """, actor.userId(), id, actor.tenantId());
        Detail result = detail(id, actor.tenantId());
        audit(actor, "PUBLISH", result);
        return result;
    }

    @Transactional
    public Detail withdraw(String id, Action action) {
        var actor = access.admin(true);
        var current = lock(id, actor.tenantId(), action == null ? null : action.expectedRevision());
        if (!"PUBLISHED".equals(current.status())) throw new ServiceException(409, "仅已发布公告可以撤回");
        jdbc.update("""
                UPDATE pf_announcement SET status='WITHDRAWN',revision=revision+1,updated_by=?,updated_at=now()
                WHERE id=? AND tenant_id=?
                """, actor.userId(), id, actor.tenantId());
        Detail result = detail(id, actor.tenantId());
        audit(actor, "WITHDRAW", result);
        return result;
    }

    private Page<Summary> page(StringBuilder where, List<Object> args, String keyword, int pageNo, int pageSize) {
        check(pageNo >= 1 && pageNo <= 1_000_000 && pageSize >= 1 && pageSize <= 100, "分页参数无效");
        String query = clean(keyword);
        check(query.length() <= 100, "搜索关键词不能超过100字");
        if (!query.isEmpty()) {
            where.append(" AND strpos(lower(concat_ws(' ',a.title,a.summary)),lower(?))>0");
            args.add(query);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM pf_announcement a WHERE " + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args);
        paged.add(pageSize);
        paged.add((pageNo - 1L) * pageSize);
        var rows = jdbc.query(select() + " WHERE " + where
                        + " ORDER BY a.pinned DESC,a.published_at DESC NULLS LAST,a.created_at DESC,a.id DESC LIMIT ? OFFSET ?",
                this::map, paged.toArray());
        return new Page<>(rows.stream().map(Detail::asSummary).toList(), total);
    }

    private static void visible(StringBuilder where, List<Object> args, AnnouncementAccess.Reader actor) {
        if (actor.allBrands()) return;
        if (actor.brandId() == null) {
            where.append(" AND a.visibility='ALL'");
        } else {
            where.append(" AND (a.visibility='ALL' OR EXISTS(SELECT 1 FROM pf_announcement_brand ab WHERE ab.tenant_id=a.tenant_id AND ab.announcement_id=a.id AND ab.brand_id=?))");
            args.add(actor.brandId());
        }
    }

    private Detail lock(String id, long tenant, Long revision) {
        check(revision != null && revision >= 0, "请提供公告当前版本");
        var versions = jdbc.queryForList("SELECT revision FROM pf_announcement WHERE id=? AND tenant_id=? FOR UPDATE", Long.class, id, tenant);
        if (versions.isEmpty()) throw notFound();
        if (!versions.get(0).equals(revision)) throw new ServiceException(409, "公告已被其他人更新，请刷新后重试");
        return detail(id, tenant);
    }

    private Detail detail(String id, long tenant) {
        var rows = jdbc.query(select() + " WHERE a.id=? AND a.tenant_id=?", this::map, id, tenant);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    private static String select() {
        return """
                SELECT a.*,
                    ARRAY(SELECT ab.brand_id::text FROM pf_announcement_brand ab
                          WHERE ab.tenant_id=a.tenant_id AND ab.announcement_id=a.id ORDER BY ab.brand_id) AS brand_ids
                FROM pf_announcement a
                """;
    }

    private Detail map(ResultSet r, int row) throws SQLException {
        var array = r.getArray("brand_ids");
        List<String> brands = new ArrayList<>();
        try { for (Object value : (Object[]) array.getArray()) brands.add(value.toString()); }
        finally { array.free(); }
        return new Detail(r.getString("id"), r.getString("title"), r.getString("summary"), r.getString("content"),
                r.getString("visibility"), List.copyOf(brands), r.getBoolean("pinned"), r.getString("status"),
                r.getLong("revision"), time(r, "published_at"), time(r, "created_at"), time(r, "updated_at"));
    }

    private static String time(ResultSet r, String column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }

    private Save validate(Save input, long tenant) {
        check(input != null, "请填写公告内容");
        String title = text(input.title(), 100, "标题", true);
        String summary = text(input.summary(), 200, "摘要", false);
        String content = text(input.content(), 20_000, "正文", true);
        String visibility = clean(input.visibility());
        check(Set.of("ALL", "BRANDS").contains(visibility), "请选择公告可见范围");
        List<String> requested = input.brandIds() == null ? List.of() : input.brandIds();
        check(requested.size() <= 200, "选择的品牌数量过多");
        check(!"ALL".equals(visibility) || requested.isEmpty(), "全品牌公告不能同时指定品牌");
        check(!"BRANDS".equals(visibility) || !requested.isEmpty(), "请至少选择一个品牌");
        Set<String> brands = new LinkedHashSet<>();
        for (String item : requested) {
            long id = brandId(item);
            requireBrand(tenant, id);
            brands.add(Long.toString(id));
        }
        return new Save(title, summary, content, visibility, List.copyOf(brands), input.pinned(), input.expectedRevision());
    }

    private void requireBrand(long tenant, long id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pf_map_brand WHERE tenant_id=? AND id=?)", Boolean.class, tenant, id))) {
            throw new ServiceException(400, "所选品牌不存在或不属于当前平台");
        }
    }

    private void saveBrands(String id, long tenant, List<String> brands) {
        jdbc.update("DELETE FROM pf_announcement_brand WHERE announcement_id=? AND tenant_id=?", id, tenant);
        for (String brand : brands) jdbc.update("INSERT INTO pf_announcement_brand(tenant_id,announcement_id,brand_id) VALUES(?,?,?)", tenant, id, Long.parseLong(brand));
    }

    private void audit(AnnouncementAccess.Reader actor, String action, Detail result) {
        jdbc.update("""
                INSERT INTO pf_announcement_audit(tenant_id,announcement_id,actor_id,post_id,action,revision,snapshot)
                VALUES(?,?,?,?,?,?,?::jsonb)
                """, actor.tenantId(), result.id(), actor.userId(), actor.postId(), action, result.revision(), JsonUtils.toJsonString(result));
    }

    private static long brandId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException error) { throw new ServiceException(400, "品牌编号无效"); }
    }

    private static String text(String value, int max, String label, boolean required) {
        String result = clean(value);
        check((!required || !result.isEmpty()) && result.length() <= max, label + (required ? "不能为空且" : "") + "不能超过" + max + "字");
        return result;
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static void check(boolean valid, String message) { if (!valid) throw new ServiceException(400, message); }
    private static ServiceException notFound() { return new ServiceException(404, "公告不存在或当前不可见"); }
}
