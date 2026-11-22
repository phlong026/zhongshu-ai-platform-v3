package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.auth.AdminUserPasswordPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.AUTH_LOGIN_BAD_CREDENTIALS;

/** All password entry points share the user-level initial state; normal passwords are never rotated here. */
@Service
public class StaffInitialPasswordPolicy implements AdminUserPasswordPolicy {
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(10);
    private final JdbcTemplate jdbc;

    public StaffInitialPasswordPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void validateLogin(AdminUserDO user) {
        // One database snapshot: WeChat retirement changes both fields in one transaction. A request that
        // matched the old hash before retirement must not become valid merely because require_change is now false.
        var state = jdbc.query("""
                SELECT policy.require_change,users.password FROM pf_staff_password_policy policy
                JOIN system_users users ON users.tenant_id=policy.tenant_id AND users.id=policy.user_id
                WHERE policy.tenant_id=? AND policy.user_id=?
                """, (rs, row) -> new LoginState(rs.getBoolean(1), rs.getString(2)), user.getTenantId(), user.getId());
        if (!state.isEmpty() && state.get(0).requireChange()) {
            throw new ServiceException(400, "请在小程序完成首次改密，或使用微信登录；初始密码过期可通过验证手机号或联系管理员重置");
        }
        if (!state.isEmpty() && !Objects.equals(state.get(0).password(), user.getPassword())) {
            throw exception(AUTH_LOGIN_BAD_CREDENTIALS);
        }
    }

    private record LoginState(boolean requireChange, String password) { }

    @Override
    public void beforePasswordChange(AdminUserDO user) {
        lock(user.getTenantId(), user.getId());
    }

    @Override
    public void passwordChanged(AdminUserDO user) {
        clear(user.getTenantId(), user.getId());
    }

    /** Invoked only after successful official WeChat identity and active-post checks, for first and later logins. */
    @Transactional(rollbackFor = Exception.class)
    public void retireAfterWechatLogin(long userId) {
        long tenant = TenantContextHolder.getRequiredTenantId();
        List<Boolean> state = lock(tenant, userId);
        if (state.isEmpty() || !state.get(0)) return;
        // No plaintext is returned. Password login can later be restored through the existing verified reset flow.
        int changed = jdbc.update("UPDATE system_users SET password=? WHERE tenant_id=? AND id=? AND status=0 AND deleted=0",
                PASSWORDS.encode(InitialAccountPassword.generate()), tenant, userId);
        if (changed != 1) throw new ServiceException(403, "账号状态已变化，请重新登录");
        clear(tenant, userId);
    }

    private List<Boolean> lock(long tenant, long userId) {
        // Same order as first-password login and headquarters resets: policy -> system_users.
        return jdbc.query("SELECT require_change FROM pf_staff_password_policy WHERE tenant_id=? AND user_id=? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), tenant, userId);
    }

    private void clear(long tenant, long userId) {
        jdbc.update("UPDATE pf_staff_password_policy SET require_change=FALSE,initial_password_expires_at=NULL WHERE tenant_id=? AND user_id=?",
                tenant, userId);
    }
}
