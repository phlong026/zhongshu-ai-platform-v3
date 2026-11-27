package db.callback;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/** Flyway's default callback scan covers both the maintenance runner and Spring auto-configuration. */
public final class DesignPostRetirementCompatibility implements Callback {
    private static final String RETIREMENT = "V20261127.1200__retire_design_posts.sql";
    private static final String COMPATIBILITY = "/db/migration/franchise/V20261127.1211__design_post_history.sql";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE && context.getMigrationInfo() != null
                && RETIREMENT.equals(context.getMigrationInfo().getScript());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try (var resource = getClass().getResourceAsStream(COMPATIBILITY)) {
            if (resource == null) throw new FlywayException("Design post compatibility migration is missing");
            try (var statement = context.getConnection().createStatement()) {
                statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | SQLException failure) {
            throw new FlywayException("Design post retirement compatibility failed", failure);
        }
    }

    @Override
    public String getCallbackName() {
        return "design-post-retirement-1200";
    }
}
