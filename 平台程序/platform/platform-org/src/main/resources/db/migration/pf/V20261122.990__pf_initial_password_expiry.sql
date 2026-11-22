-- Random initial passwords are delivered once, expire after 48 hours, and require a first change.
ALTER TABLE pf_staff_password_policy ADD COLUMN initial_password_expires_at TIMESTAMPTZ
    DEFAULT (CURRENT_TIMESTAMP + INTERVAL '48 hours');

-- Existing staff passwords may have been derived from a public login name. Retire only the
-- initial state, never a user's chosen password. A later BOSS assignment does not make an
-- earlier predictable staff credential safe, so any STAFF alias qualifies.
-- Also disable the stored hash so rolling back application code cannot restore a weak initial credential.
DO $retire$
BEGIN
    IF to_regclass('system_users') IS NOT NULL THEN
        EXECUTE $update$
            UPDATE system_users users SET password = '!retired-initial-password'
            FROM pf_staff_password_policy policy
            WHERE users.tenant_id = policy.tenant_id AND users.id = policy.user_id
              AND policy.require_change = TRUE AND EXISTS (
                SELECT 1 FROM pf_franchise_account account
                WHERE account.tenant_id = policy.tenant_id AND account.user_id = policy.user_id
                  AND account.account_type = 'STAFF'
              )
        $update$;
    END IF;
END
$retire$;

UPDATE pf_staff_password_policy policy
SET initial_password_expires_at = TIMESTAMPTZ '1970-01-01 00:00:00+00'
WHERE policy.require_change = TRUE AND EXISTS (
    SELECT 1 FROM pf_franchise_account account
    WHERE account.tenant_id = policy.tenant_id AND account.user_id = policy.user_id
      AND account.account_type = 'STAFF'
);
COMMENT ON COLUMN pf_staff_password_policy.initial_password_expires_at IS
    '初始凭据有效期；改密或官方微信启用后清空；存量员工初始凭据已退役，可微信登录或验证手机号/管理员重置';
