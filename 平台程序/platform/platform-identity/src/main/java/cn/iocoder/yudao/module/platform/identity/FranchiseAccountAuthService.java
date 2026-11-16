package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.platform.org.AccountDisplay;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;

/** 加盟商账号检索与密码认证；认证主体始终是现有 system_users.id。 */
@Service
@Slf4j
public class FranchiseAccountAuthService {

    private static final int MATCH_LIMIT = 20;
    private static final int MAX_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final BCryptPasswordEncoder STRONG_ENCODER = new BCryptPasswordEncoder(10);
    private static final String ACTIVE_ACCOUNT_SELECT = """
            SELECT a.account_id,a.org_id,a.user_id,a.login_name,a.org_name,a.owner_name,
              valid_post.preferred_post_id,valid_post.role_code,u.nickname,a.brand,a.province,a.city,a.area
            FROM pf_franchise_account a
            JOIN system_users u ON u.id=a.user_id AND u.tenant_id=a.tenant_id AND u.status=0 AND u.deleted=0
            JOIN pf_org_unit org ON org.dept_id=a.org_id AND org.kind='ORG' AND org.status='ACTIVE'
            JOIN LATERAL (SELECT p.id AS preferred_post_id,p.role_code FROM pf_staff_post p
              WHERE p.user_id=a.user_id AND p.org_id=a.org_id AND p.status='ACTIVE'
              ORDER BY p.id LIMIT 1) valid_post ON TRUE
            """;
    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count >= tonumber(ARGV[2]) then
              redis.call('SET', KEYS[2], '1', 'PX', ARGV[1])
              redis.call('DEL', KEYS[1])
            end
            return count
            """, Long.class);

    @Resource private JdbcTemplate jdbc;
    @Resource private AdminUserService adminUserService;
    @Resource private AdminUserMapper adminUserMapper;
    @Resource private TokenIssuer tokenIssuer;
    @Resource private StringRedisTemplate redis;

    public record AccountMatch(String accountId, String loginName, String orgName, String ownerName,
                               String personName, String roleCode, String roleName, String shortOrgName) {}
    public record MatchResult(List<AccountMatch> items, boolean hasMore) {}
    public record LoginResult(boolean requirePasswordChange, Long userId, TokenIssuer.IssuedToken token,
                              Long preferredPostId, String accountId, String loginName, String orgName) {}
    private record AccountRow(String accountId, long orgId, long userId, String loginName, String orgName,
                              String ownerName, long preferredPostId, AccountDisplay display) {}
    private static final RowMapper<AccountRow> ACCOUNT_ROW_MAPPER = (rs, row) -> new AccountRow(
            rs.getString("account_id"), rs.getLong("org_id"), rs.getLong("user_id"), rs.getString("login_name"),
            rs.getString("org_name"), rs.getString("owner_name"), rs.getLong("preferred_post_id"),
            AccountDisplay.of(rs.getString("nickname") == null || rs.getString("nickname").isBlank() ? rs.getString("owner_name") : rs.getString("nickname"),
                    rs.getString("role_code"), rs.getString("brand"), rs.getString("province"), rs.getString("city"), rs.getString("area"), rs.getString("org_name")));

    public MatchResult matches(String query) {
        String normalized = AccountPasswordRules.normalizeQuery(query);
        if (normalized == null) return new MatchResult(List.of(), false);
        long tenantId = TenantContextHolder.getRequiredTenantId();
        List<AccountRow> formal = jdbc.query(ACTIVE_ACCOUNT_SELECT
                        + " WHERE a.tenant_id=? AND a.login_name=? ORDER BY a.login_name LIMIT 1",
                ACCOUNT_ROW_MAPPER, tenantId, normalized);
        if (!formal.isEmpty()) return matchResult(formal);

        List<AccountRow> exactAliases = jdbc.query(ACTIVE_ACCOUNT_SELECT + """
                        WHERE a.tenant_id=? AND EXISTS (SELECT 1 FROM pf_franchise_account_alias exact_alias
                          WHERE exact_alias.tenant_id=a.tenant_id AND exact_alias.account_id=a.account_id
                            AND exact_alias.alias=?)
                        ORDER BY a.login_name LIMIT 21
                        """, ACCOUNT_ROW_MAPPER, tenantId, normalized);
        if (!exactAliases.isEmpty()) return matchResult(exactAliases);

        List<AccountRow> rows = jdbc.query(ACTIVE_ACCOUNT_SELECT + """
                        WHERE a.tenant_id=? AND (a.login_name LIKE ? OR EXISTS (
                          SELECT 1 FROM pf_franchise_account_alias prefix_alias
                          WHERE prefix_alias.tenant_id=a.tenant_id AND prefix_alias.account_id=a.account_id
                            AND prefix_alias.alias LIKE ?))
                        ORDER BY a.login_name LIMIT 21
                        """, ACCOUNT_ROW_MAPPER, tenantId, normalized + "%", normalized + "%");
        return matchResult(rows);
    }

    private static MatchResult matchResult(List<AccountRow> rows) {
        List<AccountMatch> visible = rows.stream().limit(MATCH_LIMIT)
                .map(row -> new AccountMatch(row.accountId(), row.loginName(), row.orgName(), row.ownerName(), row.display().personName(), row.display().roleCode(), row.display().roleName(), row.display().shortOrgName())).toList();
        return new MatchResult(visible, rows.size() > MATCH_LIMIT);
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginResult login(String accountId, String password, String newPassword) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        AccountRow account = account(accountId, tenantId);
        if (account == null) throw exception(ACCOUNT_CREDENTIAL_INVALID);
        ensureNotLocked(tenantId, account.userId());
        boolean requireChange = lockPasswordPolicy(tenantId, account.userId());
        // policy 行串行化同一 userId 的首次改密；加锁后重读 hash，防止并发覆盖新密码。
        AdminUserDO user = adminUserService.getUser(account.userId());
        if (user == null || !Objects.equals(user.getTenantId(), tenantId)
                || !CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())
                || !AccountPasswordRules.validBcryptInput(password)
                || !adminUserService.isPasswordMatch(password, user.getPassword())) {
            recordFailure(tenantId, account.userId());
            throw exception(ACCOUNT_CREDENTIAL_INVALID);
        }
        if (requireChange) {
            if (newPassword == null) {
                return new LoginResult(true, null, null, null, account.accountId(), account.loginName(), account.orgName());
            }
            if (!AccountPasswordRules.validNewPassword(newPassword)
                    || adminUserService.isPasswordMatch(newPassword, user.getPassword())) {
                throw exception(ACCOUNT_NEW_PASSWORD_INVALID);
            }
            // 新账号与首次改密统一使用 bcrypt cost 10，避免沿用底座较低的默认 cost。
            adminUserMapper.updateById(new AdminUserDO().setId(account.userId()).setPassword(STRONG_ENCODER.encode(newPassword)));
            jdbc.update("UPDATE pf_staff_password_policy SET require_change=FALSE WHERE tenant_id=? AND user_id=?",
                    tenantId, account.userId());
        }
        clearFailures(tenantId, account.userId());
        return new LoginResult(false, account.userId(), tokenIssuer.issue(account.userId()), account.preferredPostId(),
                account.accountId(), account.loginName(), account.orgName());
    }

    private AccountRow account(String accountId, long tenantId) {
        if (!validAccountId(accountId)) return null;
        try {
            return jdbc.queryForObject(ACTIVE_ACCOUNT_SELECT
                            + " WHERE a.tenant_id=? AND a.account_id=?",
                    ACCOUNT_ROW_MAPPER, tenantId, accountId);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private boolean lockPasswordPolicy(long tenantId, long userId) {
        List<Boolean> values = jdbc.query("SELECT require_change FROM pf_staff_password_policy "
                        + "WHERE tenant_id=? AND user_id=? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), tenantId, userId);
        return !values.isEmpty() && Boolean.TRUE.equals(values.get(0));
    }

    private void ensureNotLocked(long tenantId, long userId) {
        if (Boolean.TRUE.equals(redis.hasKey(lockKey(tenantId, userId)))) throw exception(ACCOUNT_LOGIN_LOCKED);
    }

    private void recordFailure(long tenantId, long userId) {
        Long count = redis.execute(RECORD_FAILURE_SCRIPT,
                List.of(failureKey(tenantId, userId), lockKey(tenantId, userId)),
                String.valueOf(FAILURE_WINDOW.toMillis()), String.valueOf(MAX_FAILURES));
        if (count != null && count >= MAX_FAILURES) {
            log.warn("[accountLogin][tenantId={} userId={}] 密码连续失败，账号临时锁定", tenantId, userId);
        }
    }

    private void clearFailures(long tenantId, long userId) {
        redis.delete(List.of(failureKey(tenantId, userId), lockKey(tenantId, userId)));
    }

    private static boolean validAccountId(String accountId) {
        if (accountId == null || accountId.length() != 36) return false;
        try {
            return UUID.fromString(accountId).toString().equalsIgnoreCase(accountId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String failureKey(long tenantId, long userId) { return "platform:account-login:fail:" + tenantId + ":" + userId; }
    private static String lockKey(long tenantId, long userId) { return "platform:account-login:lock:" + tenantId + ":" + userId; }
}
