package cn.iocoder.yudao.module.franchise.content.content;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** User-registered publishing identities; this directory does not assert external OAuth authorization. */
@Service
public class ContentPublisherAccountService {
    private final JdbcTemplate jdbc;

    public ContentPublisherAccountService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Account(long id, long companyId, long ownerUserId, String platform, String displayName,
                          String identifier, boolean enabled, OffsetDateTime createTime, OffsetDateTime updateTime) { }

    public List<Account> list(PostSnapshot post, String platform) {
        String filter = platform == null || platform.isBlank() ? null : platform(platform);
        String sql = "SELECT * FROM fr_content_publisher_account WHERE company_id=? AND owner_user_id=?";
        if (filter != null) sql += " AND platform=?";
        sql += " ORDER BY enabled DESC, update_time DESC, id DESC";
        return filter == null ? jdbc.query(sql, this::map, post.orgId(), post.userId())
                : jdbc.query(sql, this::map, post.orgId(), post.userId(), filter);
    }

    @Transactional(rollbackFor = Exception.class)
    public Account create(PostSnapshot post, Map<String, Object> body) {
        if (body == null) throw invalid("请填写发布账号信息");
        String platform = platform(body.get("platform"));
        String name = text(body.get("displayName"), 64, "账号名称");
        String identifier = text(body.get("identifier"), 100, "平台账号标识");
        long id = IdWorker.getId();
        int inserted = jdbc.update("INSERT INTO fr_content_publisher_account "
                        + "(id, company_id, owner_user_id, platform, display_name, identifier, enabled) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE) ON CONFLICT (company_id, platform, identifier) DO NOTHING",
                id, post.orgId(), post.userId(), platform, name, identifier);
        if (inserted > 0) return requireOwned(post, id, false);
        List<Account> existing = jdbc.query("SELECT * FROM fr_content_publisher_account "
                        + "WHERE company_id=? AND owner_user_id=? AND platform=? AND identifier=?",
                this::map, post.orgId(), post.userId(), platform, identifier);
        if (existing.isEmpty()) throw unavailable();
        return existing.get(0); // Duplicate tap cannot create a second identity or reactivate a disabled one.
    }

    @Transactional(rollbackFor = Exception.class)
    public Account update(PostSnapshot post, long id, Map<String, Object> body) {
        Account existing = requireOwned(post, id, true);
        if (body == null || body.isEmpty()) throw invalid("请选择要修改的账号信息");
        if (body.containsKey("platform") && !existing.platform().equals(platform(body.get("platform"))))
            throw invalid("更换平台请新增账号，已有发布记录将保留原账号");
        if (body.containsKey("identifier") && !existing.identifier().equals(text(body.get("identifier"), 100, "平台账号标识")))
            throw invalid("更换平台账号标识请新增账号，已有发布记录将保留原账号");
        String name = body.containsKey("displayName") ? text(body.get("displayName"), 64, "账号名称") : existing.displayName();
        boolean enabled = existing.enabled();
        if (body.containsKey("enabled")) {
            if (!(body.get("enabled") instanceof Boolean value)) throw invalid("账号启用状态无效");
            enabled = value;
        }
        if (!body.containsKey("displayName") && !body.containsKey("enabled")) throw invalid("请选择要修改的账号信息");
        jdbc.update("UPDATE fr_content_publisher_account SET display_name=?, enabled=?, update_time=now() "
                        + "WHERE id=? AND company_id=? AND owner_user_id=?",
                name, enabled, id, post.orgId(), post.userId());
        return requireOwned(post, id, false);
    }

    /** Shared row lock makes disabling an account serialize with a new publication using it. */
    public Account requireUsable(long companyId, long userId, long id, String platform) {
        List<Account> rows = jdbc.query("SELECT * FROM fr_content_publisher_account "
                        + "WHERE id=? AND company_id=? AND owner_user_id=? AND enabled=TRUE AND platform=? FOR SHARE",
                this::map, id, companyId, userId, platform);
        if (rows.isEmpty()) throw unavailable();
        return rows.get(0);
    }

    private Account requireOwned(PostSnapshot post, long id, boolean lock) {
        List<Account> rows = jdbc.query("SELECT * FROM fr_content_publisher_account "
                        + "WHERE id=? AND company_id=? AND owner_user_id=?" + (lock ? " FOR UPDATE" : ""),
                this::map, id, post.orgId(), post.userId());
        if (rows.isEmpty()) throw unavailable();
        return rows.get(0);
    }

    private Account map(ResultSet row, int ignored) throws SQLException {
        return new Account(row.getLong("id"), row.getLong("company_id"), row.getLong("owner_user_id"),
                row.getString("platform"), row.getString("display_name"), row.getString("identifier"),
                row.getBoolean("enabled"), row.getObject("create_time", OffsetDateTime.class),
                row.getObject("update_time", OffsetDateTime.class));
    }

    private static String platform(Object value) {
        String platform = value == null ? "" : value.toString().trim();
        if (!Set.of("douyin", "channels").contains(platform)) throw invalid("请选择抖音或视频号平台");
        return platform;
    }

    private static String text(Object value, int max, String label) {
        if (!(value instanceof String)) throw invalid("请填写" + label);
        String text = value.toString().trim();
        if (text.isEmpty() || text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
            throw invalid(label + "须为 1–" + max + " 个字符，不能包含换行或控制字符");
        return text;
    }

    private static ServiceException invalid(String message) {
        return new ServiceException(1114000001, message);
    }

    private static ServiceException unavailable() {
        return new ServiceException(1114000014, "发布账号不存在、已停用或不属于你，请刷新账号列表后重试");
    }
}
