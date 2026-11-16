package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.CustomerSchemePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;

/** Imports the owned immutable provider snapshot. The browser cannot supply amounts or dimensions. */
@Service
public class DesignItemizedArchiveService {
    public record Input(long customerId,long budgetId,Long revisionId) { }
    private final CustomerSchemePort customers;private final FranchiseCommands commands;private final DesignFlowAccessService flow;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    public DesignItemizedArchiveService(CustomerSchemePort customers,FranchiseCommands commands,DesignFlowAccessService flow,DataSource source,PlatformTransactionManager manager,ObjectMapper json){
        this.customers=customers;this.commands=commands;this.flow=flow;this.jdbc=new JdbcTemplate(source);this.tx=new TransactionTemplate(manager);this.json=json;
    }
    public Object save(PostSnapshot post,Input input,String key) {
        if(input.customerId()<1||input.budgetId()<1||(input.revisionId()!=null&&input.revisionId()<1)||key==null||!key.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid archive reference");
        customers.requireWritable(post,input.customerId());
        var fingerprint=new LinkedHashMap<String,Object>();fingerprint.put("operation","itemized-budget-save");fingerprint.put("input",input);
        var previous=commands.replayed(post,key,fingerprint);if(previous.isPresent())return Map.of("schemeId",previous.get().getAggregateId(),"version",previous.get().getRevision(),"replayed",true);
        Object read=flow.invoke(post,new DesignFlowCall("GET","/budget-estimates/"+input.budgetId(),input.revisionId()==null?Map.of():Map.of("revisionId",input.revisionId().toString()),Map.of(),null));
        JsonNode budget=json.valueToTree(read);
        if(!"ITEMIZED_V1".equals(budget.path("model").asText())||!String.valueOf(input.budgetId()).equals(budget.path("budgetId").asText()))throw new IllegalStateException("Invalid budget snapshot");
        if(input.revisionId()!=null&&!input.revisionId().toString().equals(budget.path("revisionId").asText()))throw new IllegalStateException("Invalid budget revision");
        String completeness=budget.path("completeness").asText();
        if(!Set.of("COMPLETE","INCOMPLETE").contains(completeness))throw new IllegalStateException("Invalid completeness");
        Long total=budget.hasNonNull("totalCents")?new BigDecimal(budget.path("totalCents").asText()).longValueExact():null;
        if(("COMPLETE".equals(completeness)!=(total!=null))||(total!=null&&total<0))throw new IllegalStateException("Invalid budget amount");
        var values=budget.path("inputSummary");var payload=new LinkedHashMap<String,Object>();payload.putAll(fingerprint);payload.put("kind","BUDGET");payload.put("itemizedBudget",budget);
        var save=new CustomerSchemePort.SchemeSave("BUDGET","AI设计·分项预算",cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(payload),decimal(values,"faceWidth"),decimal(values,"depth"),values.hasNonNull("floorCount")?values.path("floorCount").asInt():null,decimal(values,"buildingArea"),total,budget.hasNonNull("projectId")?Long.valueOf(budget.path("projectId").asText()):null);
        return tx.execute(status->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?,?)",Object.class,Long.hashCode(post.orgId()),Objects.hash(post.userId(),key));
            var replay=commands.replayed(post,key,fingerprint);if(replay.isPresent())return Map.of("schemeId",replay.get().getAggregateId(),"version",replay.get().getRevision(),"replayed",true);
            var result=customers.saveItemizedBudgetSnapshot(post,input.customerId(),save);
            commands.record(post,"fr_customer_scheme",result.schemeId(),result.version(),"itemized-budget-save",fingerprint,key);
            return Map.of("schemeId",result.schemeId(),"version",result.version(),"replayed",false);
        });
    }
    private static BigDecimal decimal(JsonNode values,String key){return values.hasNonNull(key)?new BigDecimal(values.path(key).asText()):null;}
}
