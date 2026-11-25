package cn.iocoder.yudao.module.platform.identity;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.platform.identity.enums.PlatformIdentityErrorCodes.*;

/** 完整加盟商账号与密码认证；匿名入口不提供人员或账号候选。 */
@Service
@Slf4j
public class FranchiseAccountAuthService {

    private static final int MAX_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final BCryptPasswordEncoder STRONG_ENCODER = new BCryptPasswordEncoder(10);
    private static final String DUMMY_PASSWORD_HASH = STRONG_ENCODER.encode(UUID.randomUUID().toString());
    private static final String ACTIVE_ACCOUNT_SELECT = """
            SELECT a.account_id,a.org_id,a.user_id,a.login_name,a.org_name,valid_post.preferred_post_id
            FROM pf_franchise_account a
            JOIN system_users u ON u.id=a.user_id AND u.tenant_id=a.tenant_id AND u.status=0 AND u.deleted=0
            JOIN system_dept d ON d.id=a.org_id AND d.tenant_id=a.tenant_id AND d.status=0 AND d.deleted=0
            JOIN pf_org_unit org ON org.dept_id=a.org_id AND org.org_id=a.org_id AND org.kind='ORG' AND org.status='ACTIVE'
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
                              long preferredPostId) {}
    private static final RowMapper<AccountRow> ACCOUNT_ROW_MAPPER = (rs, row) -> new AccountRow(
            rs.getString("account_id"), rs.getLong("org_id"), rs.getLong("user_id"), rs.getString("login_name"),
            rs.getString("org_name"), rs.getLong("preferred_post_id"));

    public MatchResult matches(String query) {
        // Compatibility response for older clients; never query by name, alias, or prefix.
        return new MatchResult(List.of(), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginResult login(String accountId, String password, String newPassword) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        return authenticate(account(accountId, tenantId), tenantId, password, newPassword);
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginResult loginByName(String loginName, String password, String newPassword) {
        long tenantId = TenantContextHolder.getRequiredTenantId();
        String normalized = AccountPasswordRules.normalizeLoginName(loginName);
        List<AccountRow> rows = normalized == null ? List.of() : jdbc.query(ACTIVE_ACCOUNT_SELECT
                + " WHERE a.tenant_id=? AND a.login_name=?", ACCOUNT_ROW_MAPPER, tenantId, normalized);
        try {
            return authenticate(rows.isEmpty() ? null : rows.get(0), tenantId, password, newPassword);
        } catch (ServiceException error) {
            if (!Objects.equals(error.getCode(), ACCOUNT_LOGIN_LOCKED.getCode())) throw error;
            // Public names must not reveal which accounts exist after repeated invalid passwords.
            // Keep the actual Redis lock and match the password work used for absent accounts.
            if (AccountPasswordRules.validBcryptInput(password)) STRONG_ENCODER.matches(password, DUMMY_PASSWORD_HASH);
            throw exception(ACCOUNT_CREDENTIAL_INVALID);
        }
    }

    private LoginResult authenticate(AccountRow account, long tenantId, String password, String newPassword) {
        if (account == null) {
            // Missing and unusable accounts follow the same password work/error shape as invalid credentials.
            if (AccountPasswordRules.validBcryptInput(password)) STRONG_ENCODER.matches(password, DUMMY_PASSWORD_HASH);
            throw exception(ACCOUNT_CREDENTIAL_INVALID);
        }
        ensureNotLocked(tenantId, account.userId());
        PasswordState policy = lockPasswordPolicy(tenantId, account.userId());
        // policy 行串行化同一 userId 的首次改密；加锁后重读 hash，防止并发覆盖新密码。
        AdminUserDO user = adminUserService.getUser(account.userId());
        if (user == null || !Objects.equals(user.getTenantId(), tenantId)
                || !CommonStatusEnum.ENABLE.getStatus().equals(user.getStatus())
                || !AccountPasswordRules.validBcryptInput(password)
                || !adminUserService.isPasswordMatch(password, user.getPassword())) {
            recordFailure(tenantId, account.userId());
            throw exception(ACCOUNT_CREDENTIAL_INVALID);
        }
        if (policy.requireChange()) {
            if (!policy.initialUsable()) throw exception(ACCOUNT_INITIAL_PASSWORD_EXPIRED);
            if (newPassword == null) {
                return new LoginResult(true, null, null, null, account.accountId(), account.loginName(), account.orgName());
            }
            if (!AccountPasswordRules.validNewPassword(newPassword)
                    || adminUserService.isPasswordMatch(newPassword, user.getPassword())) {
                throw exception(ACCOUNT_NEW_PASSWORD_INVALID);
            }
            // 新账号与首次改密统一使用 bcrypt cost 10，避免沿用底座较低的默认 cost。
            adminUserMapper.updateById(new AdminUserDO().setId(account.userId()).setPassword(STRONG_ENCODER.encode(newPassword)));
            jdbc.update("UPDATE pf_staff_password_policy SET require_change=FALSE,initial_password_expires_at=NULL WHERE tenant_id=? AND user_id=?",
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

    private record PasswordState(boolean requireChange, Instant expiresAt) {
        boolean initialUsable() { return expiresAt != null && expiresAt.isAfter(Instant.now()); }
    }

    private PasswordState lockPasswordPolicy(long tenantId, long userId) {
        List<PasswordState> values = jdbc.query("SELECT require_change,initial_password_expires_at FROM pf_staff_password_policy "
                        + "WHERE tenant_id=? AND user_id=? FOR UPDATE",
                (rs, row) -> new PasswordState(rs.getBoolean(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()), tenantId, userId);
        return values.isEmpty() ? new PasswordState(false, null) : values.get(0);
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
