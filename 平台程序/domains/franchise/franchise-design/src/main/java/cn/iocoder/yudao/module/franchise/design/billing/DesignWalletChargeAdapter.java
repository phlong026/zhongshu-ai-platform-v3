package cn.iocoder.yudao.module.franchise.design.billing;

import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.design.remote.RemoteDesignChargePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** B design charging through the sole company point pool. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Slf4j
public class DesignWalletChargeAdapter implements RemoteDesignChargePort {
    private static final String REF_TYPE = "design_command";
    private static final String ORIGIN = "BRIDGE";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OrgPointPort points;

    public DesignWalletChargeAdapter(DataSource dataSource, PlatformTransactionManager transactionManager,
                                     OrgPointPort points) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(15);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(15);
        this.points = points;
    }

    @Override
    public void requireReady() {
        execute("readiness", () -> {
            jdbc.queryForList("SELECT org_id,balance,status FROM org_point_account LIMIT 0");
            jdbc.queryForList("SELECT operation_id,biz_key,price_snapshot FROM org_point_ledger LIMIT 0");
            jdbc.queryForList("SELECT org_id,actor_user_id,actor_post_id,subject,operation_id FROM design_billing_binding LIMIT 0");
            rejectUnsafeLegacyCommands();
            return null;
        });
    }

    /**
     * The OrgPoint adapter cannot safely finish a reservation whose snapshot is not one of its four subjects.
     * This checks only the database being started; the former shared source database still needs a separate,
     * read-only cutover audit before deployment.
     */
    private void rejectUnsafeLegacyCommands() {
        Boolean legacyBillingExists = jdbc.queryForObject(
                "SELECT to_regclass('fr_design_billing') IS NOT NULL", Boolean.class);
        String safeLegacyPredicate = Boolean.TRUE.equals(legacyBillingExists)
                ? """
                   AND NOT (
                       EXISTS (SELECT 1 FROM fr_design_billing b WHERE b.command_id=c.command_id AND b.terminal=true)
                       AND (c.state='REJECTED' OR (c.state='SUCCEEDED' AND (c.job_id IS NULL OR c.settled_terminal=true)))
                   )
                   """
                : "";
        Long unsafe = jdbc.queryForObject("""
                SELECT count(*) FROM fr_design_remote_command c
                WHERE c.price_snapshot IS NOT NULL
                  AND COALESCE(c.price_snapshot->>'priceId','') NOT IN
                      ('ai_flat','ai_elevation','budget_estimate','note_polish')
                """ + safeLegacyPredicate, Long.class);
        if (unsafe != null && unsafe > 0) {
            throw new IllegalStateException("PLATFORM_DESIGN_LEGACY_IN_FLIGHT_REQUIRES_MANUAL_CUTOVER:" + unsafe);
        }
    }

    @Override
    public void assertBudgetAllowed(PostSnapshot actor) {
        points.assertSubjectAllowed(actor.orgId(), actor.userId(), "budget_estimate");
    }

    @Override
    public PlatformPriceSnapshot quote(PostSnapshot actor, Operation operation, Object payload) {
        requireTransaction();
        if (!operation.paid() || !operation.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException("Invalid billable operation");
        }
        return execute("quote:" + operation, () -> {
            String subject = subject(operation);
            int quantity = quantity(operation, payload);
            long maximum = points.quoteCredits(subject, quantity);
            String version=null;
            if(payload instanceof cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall call) {
                var quoted=points.quotePrice(subject,quantity);
                Object raw=call.body().get("companyPrice");
                if(raw==null && call.body().get("priceConfirmation") instanceof java.util.Map<?,?> confirmation)raw=confirmation.get("companyPrice");
                if(!(raw instanceof java.util.Map<?,?> expected)||!subject.equals(expected.get("subject"))
                        ||!String.valueOf(quantity).equals(String.valueOf(expected.get("quantity")))
                        ||!String.valueOf(quoted.credits()).equals(String.valueOf(expected.get("credits")))
                        ||!quoted.priceVersion().equals(expected.get("priceVersion")))
                    throw new cn.iocoder.yudao.framework.common.exception.ServiceException(1072000016,"计价规则已更新，请重新确认价格");
                maximum=quoted.credits();version=quoted.priceVersion();
            }
            boolean generation = generation(operation);
            // OrgPoint reserve owns the authoritative price/version snapshot. The command snapshot only freezes
            // routing and requested quantity, so this domain never recreates an independent price ledger.
            return new PlatformPriceSnapshot(subject, 1, 0, 0, 0, maximum,
                    generation ? quantity : 0, 0, generation ? 0 : 1,version);
        });
    }

    @Override
    public void reserve(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot) {
        requireTransaction();
        execute("reserve:" + commandId, () -> {
            String subject = validateSnapshot(snapshot);
            String bizKey = bizKey(commandId, subject);
            var reserve=new OrgPointPort.ReserveCommand(
                    bizKey, actor.orgId(), actor.userId(), actor.postId(), subject,
                    reservedQuantity(snapshot), REF_TYPE, commandId);
            long operationId=snapshot.companyPriceVersion()==null?points.reserve(reserve):points.reserveConfirmed(reserve,snapshot.maximumPoints(),snapshot.companyPriceVersion());
            points.bindOperation(ORIGIN, actor.orgId(), actor.userId(), actor.postId(),
                    REF_TYPE, commandId, subject, operationId, bizKey);
            if (binding(commandId, actor, subject) != operationId) {
                conflict();
            }
            return null;
        });
    }

    @Override
    public void settle(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, Settlement facts) {
        requireTransaction();
        execute("settle:" + commandId, () -> {
            String subject = validateSnapshot(snapshot);
            long operationId = binding(commandId, actor, subject);
            if (!generationSubject(subject)
                    || facts.requestedCount() != snapshot.requestedImageCount()
                    || facts.cumulativeImageUnits() != facts.validResultCount()) {
                conflict();
            }
            // Non-terminal provider progress remains a command fact. The company pool receives one terminal only.
            if (!facts.terminal()) {
                return null;
            }
            if (facts.validResultCount() == 0) {
                points.release(operationId);
            } else {
                points.settle(operationId, facts.validResultCount());
            }
            return null;
        });
    }

    @Override
    public void completeSynchronous(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot,
                                    Operation operation) {
        requireTransaction();
        execute("complete:" + commandId, () -> {
            String subject = validateSnapshot(snapshot);
            if (!subject.equals(subject(operation)) || generation(operation)) {
                conflict();
            }
            points.settle(binding(commandId, actor, subject), 1);
            return null;
        });
    }

    @Override
    public void reject(long commandId, PostSnapshot actor, PlatformPriceSnapshot snapshot, int errorCode) {
        requireTransaction();
        execute("reject:" + commandId, () -> {
            String subject = validateSnapshot(snapshot);
            points.release(binding(commandId, actor, subject));
            return null;
        });
    }

    private long binding(long commandId, PostSnapshot actor, String subject) {
        OrgPointPort.PoolBinding binding = points.findBinding(REF_TYPE, commandId, subject);
        if (binding == null || binding.orgId() != actor.orgId() || binding.actorUserId() != actor.userId()
                || !Objects.equals(binding.actorPostId(), actor.postId())) {
            conflict();
        }
        return binding.operationId();
    }

    private static String validateSnapshot(PlatformPriceSnapshot snapshot) {
        String subject = snapshot.priceId();
        boolean generation = generationSubject(subject);
        if (!List.of("ai_flat", "ai_elevation", "budget_estimate", "note_polish").contains(subject)
                || snapshot.version() != 1 || snapshot.requestedPromptUnits() != 0
                || generation == (snapshot.requestedUsageUnits() != 0)
                || generation == (snapshot.requestedImageCount() == 0)) {
            conflict();
        }
        return subject;
    }

    private static long reservedQuantity(PlatformPriceSnapshot snapshot) {
        return generationSubject(snapshot.priceId()) ? snapshot.requestedImageCount() : 1;
    }

    private static int quantity(Operation operation, Object payload) {
        int value = switch (operation) {
            case FLOW_FLAT_JOB,FLOW_ELEVATION_JOB,FLOW_REVISION -> ((cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload).count();
            case CREATE_FLAT_JOB -> ((FlatJob) payload).command().count();
            case CREATE_ELEVATION_JOB -> ((ElevationJob) payload).command().count();
            case CREATE_REVISION_REQUEST -> ((Revision) payload).command().count() == null
                    ? 2 : ((Revision) payload).command().count();
            default -> 1;
        };
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid requested quantity");
        }
        return value;
    }

    private static String subject(Operation operation) {
        return switch (operation) {
            case CREATE_FLAT_JOB,FLOW_FLAT_JOB -> "ai_flat";
            case CREATE_ELEVATION_JOB, CREATE_REVISION_REQUEST,FLOW_ELEVATION_JOB,FLOW_REVISION -> "ai_elevation";
            case CREATE_BUDGET_ESTIMATE,FLOW_BUDGET -> "budget_estimate";
            case NOTE_POLISH -> "note_polish";
            default -> throw new IllegalArgumentException("Unsupported billable operation: " + operation);
        };
    }

    private static boolean generation(Operation operation) {
        return operation==Operation.FLOW_FLAT_JOB || operation==Operation.FLOW_ELEVATION_JOB || operation==Operation.FLOW_REVISION || operation == Operation.CREATE_FLAT_JOB || operation == Operation.CREATE_ELEVATION_JOB
                || operation == Operation.CREATE_REVISION_REQUEST;
    }

    private static boolean generationSubject(String subject) {
        return "ai_flat".equals(subject) || "ai_elevation".equals(subject);
    }

    private static String bizKey(long commandId, String subject) {
        return "design:command:" + commandId + ":" + subject.toLowerCase(Locale.ROOT);
    }

    private <T> T execute(String operation, Supplier<T> action) {
        try {
            return transaction.execute(status -> action.get());
        } catch (RuntimeException error) {
            log.warn("[designOrgPoint][operation={}] 设计公司积分计费失败", operation, error);
            throw error;
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("DESIGN_BILLING_REQUIRES_COMMAND_TRANSACTION");
        }
    }

    private static void conflict() {
        throw new IllegalStateException("PLATFORM_DESIGN_BILLING_CONFLICT");
    }
}
