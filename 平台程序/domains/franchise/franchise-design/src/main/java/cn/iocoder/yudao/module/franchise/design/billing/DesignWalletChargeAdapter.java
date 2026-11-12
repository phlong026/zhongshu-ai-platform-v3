package cn.iocoder.yudao.module.franchise.design.billing;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.franchise.design.remote.RemoteDesignChargePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.wallet.WalletChargeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** B retail pricing, reservations and cumulative charge adapter. A costs never enter the price calculation. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Slf4j
public class DesignWalletChargeAdapter implements RemoteDesignChargePort {
    private static final String BIZ_TYPE="DESIGN_CAPABILITY";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletChargeService wallet;
    private final ObjectMapper json=new ObjectMapper();

    public DesignWalletChargeAdapter(DataSource source,PlatformTransactionManager transactions,WalletChargeService wallet) {
        this.jdbc=new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(15);
        this.tx=new TransactionTemplate(transactions);
        this.tx.setTimeout(15);
        this.wallet=wallet;
    }

    @Override public void requireReady() {
        execute("readiness", () -> {
            jdbc.queryForList("SELECT charged_points,settlement_version FROM pf_wallet_charge LIMIT 0");
            jdbc.queryForList("SELECT tenant_id,settlement_version FROM fr_design_billing LIMIT 0");
            // HQ must be able to start and configure the first price. Paid quote remains fail-closed per tenant.
            jdbc.queryForList("SELECT tenant_id,operation,resolution,version,prompt_points,image_points,usage_points FROM fr_design_price LIMIT 0");
            return null;
        });
    }

    @Override public PlatformPriceSnapshot quote(PostSnapshot actor,Operation operation,Object payload) {
        requireTransaction();
        if (!operation.paid() || !operation.payloadType().isInstance(payload)) throw new IllegalArgumentException("Invalid billable operation");
        return execute("quote:"+operation, () -> {
            int count=count(operation,payload);
            var rule=rule(TenantContextHolder.getRequiredTenantId(),operation,resolution(operation,payload));
            if (isGeneration(operation) && (count<1 || count>number(rule,"max_image_units") || number(rule,"max_prompt_units")!=1)
                    || !isGeneration(operation) && (number(rule,"max_usage_units")!=1 || count!=0)) {
                throw new IllegalStateException("PLATFORM_DESIGN_PRICE_CAPACITY_NOT_CONFIGURED");
            }
            return snapshot(rule,count);
        });
    }

    @Override public void reserve(long commandId,PostSnapshot actor,PlatformPriceSnapshot snapshot) {
        requireTransaction();
        long tenant=TenantContextHolder.getRequiredTenantId();
        execute("reserve:"+commandId, () -> {
            var existing=jdbc.queryForList("SELECT * FROM fr_design_billing WHERE command_id=? FOR UPDATE",commandId);
            if (!existing.isEmpty()) { check(existing.get(0),actor,snapshot); if (number(existing.get(0),"tenant_id")!=tenant) conflict(); return null; }
            long priceId=Long.parseLong(snapshot.priceId());
            int count=snapshot.requestedImageCount();
            var rule=jdbc.queryForMap("SELECT * FROM fr_design_price WHERE tenant_id=? AND id=? AND version=? AND active=TRUE FOR SHARE",tenant,priceId,snapshot.version());
            if (count<0 || count>number(rule,"max_image_units") || !snapshot.equals(snapshot(rule,count))) conflict();
            Operation operation=Operation.valueOf((String)rule.get("operation"));
            if (isGeneration(operation)!=(count>0)) conflict();
            long promptCap=isGeneration(operation)?number(rule,"max_prompt_units"):0;
            long usageCap=isGeneration(operation)?0:number(rule,"max_usage_units");
            if (isGeneration(operation) && promptCap!=1 || !isGeneration(operation) && usageCap!=1) conflict();
            int inserted=jdbc.update("""
                    INSERT INTO fr_design_billing(command_id,tenant_id,org_id,actor_id,actor_post_id,operation,price_id,price_version,
                        prompt_points,image_points,usage_points,max_prompt_units,max_image_units,max_usage_units,reserved_points)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """,commandId,tenant,actor.orgId(),actor.userId(),actor.postId(),operation.name(),snapshot.priceId(),snapshot.version(),
                    snapshot.promptUnitPoints(),snapshot.imageUnitPoints(),snapshot.usageUnitPoints(),promptCap,count,usageCap,snapshot.maximumPoints());
            var stored=lock(commandId);
            check(stored,actor,snapshot);
            if (number(stored,"tenant_id")!=tenant) conflict();
            if (inserted==1 && snapshot.maximumPoints()>0) {
                var charge=wallet.reserve(BIZ_TYPE,Long.toString(commandId),snapshot.maximumPoints(),"design:"+commandId);
                // Bind the reserved charge to the same B org and actor before allowing the transaction to commit.
                Integer bound=jdbc.queryForObject("""
                        SELECT count(*) FROM pf_wallet_charge c JOIN pf_wallet w ON w.id=c.wallet_id AND w.tenant_id=c.tenant_id
                        WHERE c.id=? AND c.tenant_id=? AND w.org_id=? AND c.actor_id=?
                        """,Integer.class,Long.parseLong(charge.id()),tenant,actor.orgId(),actor.userId());
                if (bound==null || bound!=1) conflict();
                jdbc.update("UPDATE fr_design_billing SET charge_id=? WHERE command_id=?",charge.id(),commandId);
            }
            return null;
        });
    }

    @Override public void settle(long commandId,PostSnapshot actor,PlatformPriceSnapshot snapshot,Settlement facts) {
        requireTransaction();
        execute("settle:"+commandId, () -> {
            var row=lock(commandId);
            check(row,actor,snapshot);
            if (!isGeneration(Operation.valueOf((String)row.get("operation")))) conflict();
            if (facts.requestedCount()!=number(row,"max_image_units") || facts.cumulativeImageUnits()!=facts.validResultCount()
                    || facts.cumulativePromptUnits()>number(row,"max_prompt_units") || facts.cumulativeUsageUnits()>number(row,"max_usage_units")) conflict();
            apply(row,snapshot,facts.version(),facts.cumulativePromptUnits(),facts.cumulativeImageUnits(),facts.cumulativeUsageUnits(),
                    facts.terminal(),write(facts));
            return null;
        });
    }

    @Override public void completeSynchronous(long commandId,PostSnapshot actor,PlatformPriceSnapshot snapshot,Operation operation) {
        requireTransaction();
        execute("complete:"+commandId, () -> {
            var row=lock(commandId);
            check(row,actor,snapshot);
            if (!operation.name().equals(row.get("operation")) || isGeneration(operation)) conflict();
            apply(row,snapshot,1,0,0,1,true,"SUCCESS:"+operation);
            return null;
        });
    }

    @Override public void reject(long commandId,PostSnapshot actor,PlatformPriceSnapshot snapshot,int errorCode) {
        requireTransaction();
        execute("reject:"+commandId, () -> {
            var row=lock(commandId);
            check(row,actor,snapshot);
            if (number(row,"charged_points")!=0) conflict();
            apply(row,snapshot,1,0,0,0,true,"REJECTED:"+errorCode);
            return null;
        });
    }

    private void apply(Map<String,Object> row,PlatformPriceSnapshot snapshot,long version,long prompt,long images,long usage,boolean terminal,String fingerprint) {
        long previous=number(row,"settlement_version");
        if (version<previous) conflict();
        if (version==previous) {
            if (!Objects.equals(fingerprint,row.get("settlement_fingerprint"))) conflict();
            return;
        }
        if (prompt<number(row,"prompt_units") || images<number(row,"image_units") || usage<number(row,"usage_units")
                || prompt>number(row,"max_prompt_units") || images>number(row,"max_image_units") || usage>number(row,"max_usage_units")
                || Boolean.TRUE.equals(row.get("terminal"))) conflict();
        long points=retail(snapshot,prompt,images,usage);
        if (row.get("charge_id")!=null) wallet.settleToCumulativeForTask(number(row,"tenant_id"),number(row,"org_id"),BIZ_TYPE,
                row.get("command_id").toString(),points,terminal,version);
        else if (snapshot.maximumPoints()!=0) throw new IllegalStateException("PLATFORM_DESIGN_RESERVATION_MISSING");
        jdbc.update("""
                UPDATE fr_design_billing SET charged_points=?,refunded_points=?,settlement_version=?,settlement_fingerprint=?,
                    prompt_units=?,image_units=?,usage_units=?,terminal=?,updated_at=now() WHERE command_id=?
                """,points,terminal?snapshot.maximumPoints()-points:0,version,fingerprint,prompt,images,usage,terminal,row.get("command_id"));
    }

    public static long retailPoints(PlatformPriceSnapshot snapshot,Settlement facts) {
        return retail(snapshot,facts.cumulativePromptUnits(),facts.cumulativeImageUnits(),facts.cumulativeUsageUnits());
    }
    private static long retail(PlatformPriceSnapshot snapshot,long prompt,long images,long usage) {
        long amount=Math.addExact(Math.addExact(Math.multiplyExact(prompt,snapshot.promptUnitPoints()),
                Math.multiplyExact(images,snapshot.imageUnitPoints())),Math.multiplyExact(usage,snapshot.usageUnitPoints()));
        if (amount>snapshot.maximumPoints()) throw new IllegalArgumentException("Platform design price exceeds reserved maximum");
        return amount;
    }
    private static PlatformPriceSnapshot snapshot(Map<String,Object> rule,int count) {
        boolean generation=isGeneration(Operation.valueOf((String)rule.get("operation")));
        long total=generation?Math.addExact(Math.multiplyExact(number(rule,"prompt_points"),number(rule,"max_prompt_units")),Math.multiplyExact(number(rule,"image_points"),count))
                :Math.multiplyExact(number(rule,"usage_points"),number(rule,"max_usage_units"));
        return new PlatformPriceSnapshot(rule.get("id").toString(),number(rule,"version"),number(rule,"prompt_points"),
                number(rule,"image_points"),number(rule,"usage_points"),total,count,
                generation?number(rule,"max_prompt_units"):0,generation?0:number(rule,"max_usage_units"));
    }
    private Map<String,Object> rule(long tenant,Operation operation,String resolution) {
        var rows=jdbc.queryForList("SELECT * FROM fr_design_price WHERE tenant_id=? AND operation=? AND resolution=? AND active=TRUE AND unit='B_WALLET_POINTS'",tenant,operation.name(),resolution);
        if (rows.size()!=1) throw new IllegalStateException("PLATFORM_DESIGN_PRICE_NOT_CONFIGURED:"+operation+":"+resolution);
        return rows.get(0);
    }
    private Map<String,Object> lock(long id) { return jdbc.queryForMap("SELECT * FROM fr_design_billing WHERE command_id=? FOR UPDATE",id); }
    private static void check(Map<String,Object> row,PostSnapshot actor,PlatformPriceSnapshot snapshot) {
        if (number(row,"org_id")!=actor.orgId() || number(row,"actor_id")!=actor.userId() || number(row,"actor_post_id")!=actor.postId()
                || !snapshot.priceId().equals(row.get("price_id")) || snapshot.version()!=number(row,"price_version")
                || snapshot.promptUnitPoints()!=number(row,"prompt_points") || snapshot.imageUnitPoints()!=number(row,"image_points")
                || snapshot.usageUnitPoints()!=number(row,"usage_points") || snapshot.maximumPoints()!=number(row,"reserved_points")
                || snapshot.requestedImageCount()!=number(row,"max_image_units") || snapshot.requestedPromptUnits()!=number(row,"max_prompt_units")
                || snapshot.requestedUsageUnits()!=number(row,"max_usage_units")) conflict();
    }
    private static int count(Operation operation,Object payload) {
        return switch(operation) {
            case CREATE_FLAT_JOB -> ((FlatJob)payload).command().count();
            case CREATE_ELEVATION_JOB -> ((ElevationJob)payload).command().count();
            case CREATE_REVISION_REQUEST -> ((Revision)payload).command().count()==null?2:((Revision)payload).command().count();
            default -> 0;
        };
    }
    private static String resolution(Operation operation,Object payload) {
        String value=switch(operation) {
            case CREATE_FLAT_JOB -> ((FlatJob)payload).command().resolution();
            case CREATE_ELEVATION_JOB -> ((ElevationJob)payload).command().resolution();
            case CREATE_REVISION_REQUEST -> ((Revision)payload).command().resolution();
            default -> "";
        };
        if (!isGeneration(operation)) return "";
        if (value==null || value.isBlank()) return "2K";
        String normalized=value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("2K","4K").contains(normalized)) throw new IllegalArgumentException("Invalid resolution");
        return normalized;
    }
    private static boolean isGeneration(Operation operation) {
        return operation==Operation.CREATE_FLAT_JOB || operation==Operation.CREATE_ELEVATION_JOB || operation==Operation.CREATE_REVISION_REQUEST;
    }
    private static long number(Map<String,Object> row,String column) { return ((Number)row.get(column)).longValue(); }
    private <T> T execute(String operation,Supplier<T> action) {
        try { return tx.execute(status -> action.get()); }
        catch(RuntimeException error) { log.warn("[designWallet][operation={}] 设计计价或结算失败",operation,error); throw error; }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException error) { throw new IllegalArgumentException("Invalid settlement",error); }
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("DESIGN_BILLING_REQUIRES_COMMAND_TRANSACTION");
    }
    private static void conflict() { throw new IllegalStateException("PLATFORM_DESIGN_BILLING_CONFLICT"); }
}
