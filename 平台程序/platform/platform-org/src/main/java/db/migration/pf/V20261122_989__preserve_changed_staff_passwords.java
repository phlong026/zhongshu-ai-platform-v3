package db.migration.pf;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs before the immutable 990 SQL: old SMS/admin/profile resets changed the hash but left
 * require_change=true. Only a valid BCrypt password different from every original STAFF
 * login-name password proves that this stale flag may be cleared. No password hash is rewritten.
 */
public class V20261122_989__preserve_changed_staff_passwords extends BaseJavaMigration {

    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./0-9A-Za-z]{53}");
    private static final Pattern OLD_STAFF_LOGIN = Pattern.compile("[a-z0-9]{1,61}");

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection(); // Flyway owns this connection and transaction.
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_schema=current_schema() AND table_name='pf_staff_password_policy'
                    AND column_name='initial_password_expires_at'),
                  to_regclass(quote_ident(current_schema()) || '.system_users') IS NOT NULL
                """)) {
            result.next();
            // On a database that already ran 990, the original hash may be irrecoverable and new
            // random initial credentials may exist. Never guess or clear their activation policy.
            if (result.getBoolean(1) || !result.getBoolean(2)) return;
        }

        try (var candidates = connection.prepareStatement("""
                SELECT policy.tenant_id,policy.user_id,users.password
                FROM pf_staff_password_policy policy
                JOIN system_users users ON users.tenant_id=policy.tenant_id AND users.id=policy.user_id
                WHERE policy.require_change=TRUE AND EXISTS (
                  SELECT 1 FROM pf_franchise_account account
                  WHERE account.tenant_id=policy.tenant_id AND account.user_id=policy.user_id
                    AND account.account_type='STAFF')
                ORDER BY policy.tenant_id,policy.user_id
                FOR UPDATE OF policy,users
                """);
             var names = connection.prepareStatement("""
                     SELECT login_name FROM pf_franchise_account
                     WHERE tenant_id=? AND user_id=? AND account_type='STAFF'
                     """);
             var preserve = connection.prepareStatement("""
                     UPDATE pf_staff_password_policy SET require_change=FALSE
                     WHERE tenant_id=? AND user_id=? AND require_change=TRUE
                     """)) {
            candidates.setFetchSize(100);
            try (var rows = candidates.executeQuery()) {
                while (rows.next()) {
                    long tenantId = rows.getLong(1), userId = rows.getLong(2);
                    String hash = rows.getString(3);
                    names.setLong(1, tenantId);
                    names.setLong(2, userId);
                    List<String> logins = new ArrayList<>();
                    try (var aliases = names.executeQuery()) {
                        while (aliases.next()) logins.add(aliases.getString(1));
                    }
                    if (!changedPassword(hash, logins)) continue;
                    preserve.setLong(1, tenantId);
                    preserve.setLong(2, userId);
                    preserve.executeUpdate();
                }
            }
        }
    }

    private static boolean changedPassword(String hash, List<String> logins) {
        if (hash == null || !BCRYPT.matcher(hash).matches() || logins.isEmpty()) return false;
        try {
            for (String login : logins) {
                if (login == null || !OLD_STAFF_LOGIN.matcher(login).matches()
                        || PASSWORDS.matches(login + "123", hash)) return false;
            }
            return true;
        } catch (IllegalArgumentException invalidHash) {
            // A malformed/unsupported hash is not evidence of a successful historical reset.
            return false;
        }
    }
}
