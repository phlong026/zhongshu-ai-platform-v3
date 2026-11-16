package cn.iocoder.yudao.module.design.capability;

import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.budget.*;
import cn.iocoder.yudao.module.design.controller.app.*;
import cn.iocoder.yudao.module.design.controller.app.vo.*;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;

/** Reuses the original public projections, validation and services. Never exposes a consumer token. */
@Component
@ConditionalOnProperty(name="zhongshu.design.capability.enabled",havingValue="true")
public class OriginalDesignFlow {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private CaseReferenceImportPort caseReferences;
    private final ApplicationContext context;
    private final ObjectMapper json;
    private final Validator validator;
    private final DesignProjectService projects;
    private final AssetService assets;
    private final BudgetService legacyBudgets;
    private final BudgetDraftService drafts;
    private final ItemizedBudgetService estimates;
    public OriginalDesignFlow(ApplicationContext context,ObjectMapper json,Validator validator,DataSource source,
            PlatformTransactionManager transactions,AuditPort audit,CapabilityServiceAccountGuard guard,
            @Qualifier("capabilityDesignProjectService") DesignProjectService projects,
            @Qualifier("capabilityAssetService") AssetService assets,
            @Qualifier("capabilityBudgetService") BudgetService legacyBudgets) {
        this.context=context;this.json=json;this.validator=validator;this.projects=projects;this.assets=assets;
        this.legacyBudgets=legacyBudgets;
        drafts=new BudgetDraftService(source,transactions,projects,guard);
        // Service billing is performed by the durable capability receipt; a consumer wallet must never run here.
        estimates=new ItemizedBudgetService(source,transactions,projects,audit,true);
        new DirectFieldAccessor(estimates).setPropertyValue("drafts",drafts);
    }
    private <T> T controller(T controller,long owner) {
        var access=new DirectFieldAccessor(controller);
        for(var field:controller.getClass().getDeclaredFields()) {
            if(!field.isAnnotationPresent(jakarta.annotation.Resource.class))continue;
            Class<?> type=field.getType();Object bean;
            if(type==IdentitySessionPort.class) bean=(IdentitySessionPort)token->Optional.of(new IdentitySessionPort.SessionContext(owner,"service-design","",false));
            else if(type==DesignProjectService.class)bean=projects;
            else if(type==AssetService.class)bean=assets;
            else if(type==BudgetDraftService.class)bean=drafts;
            else if(type==ItemizedBudgetService.class)bean=estimates;
            else if(type==BudgetService.class)bean=legacyBudgets;
            else if(type==UsagePricingPort.class)bean=null;
            else bean=context.getBean(type);
            access.setPropertyValue(field.getName(),bean);
        }
        return controller;
    }
    private <T> T dto(Map<String,Object> body,Class<T> type) {
        T value=json.convertValue(body,type);
        if(!validator.validate(value).isEmpty())throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400,"设计输入不符合原业务规则");
        return value;
    }
    private static Integer integer(Map<String,String> q,String key,Integer fallback){return q.get(key)==null?fallback:Integer.valueOf(q.get(key));}
    private static java.math.BigDecimal decimal(Map<String,String> q,String key){return q.get(key)==null?null:new java.math.BigDecimal(q.get(key));}
    private static boolean bool(Map<String,String> q,String key,boolean fallback){return q.get(key)==null?fallback:Boolean.parseBoolean(q.get(key));}
    public Object invoke(long owner,DesignFlowCall call) {
        Object result=original(owner,call);
        return caseReferences==null?result:caseReferences.publicResult(owner,result);
    }
    private Object original(long owner,DesignFlowCall call) {
        call.operation(); // Closed resource/method whitelist, including direct in-process callers.
        String method=call.method(),path=call.path();var q=call.query();var b=new LinkedHashMap<>(call.body());b.remove("companyPrice");String key=call.idempotencyKey();
        String[] parts=path.split("/");String id=parts.length>2?parts[2]:null;
        if(path.startsWith("/cases") || path.equals("/favorites")) {
            var c=controller(new AppCaseController(),owner);
            if(path.equals("/cases"))return c.getCasePage(q.get("sourceType"),q.get("styleCode"),integer(q,"floorCount",null),integer(q,"minArea",null),integer(q,"maxArea",null),decimal(q,"faceWidth"),decimal(q,"depth"),q.get("keyword"),q.get("cursor"),integer(q,"limit",20)).getData();
            if(path.equals("/cases/styles"))return c.getCaseStyles().getData();
            if(path.equals("/cases/recommendation"))return c.getRecommendedCase(integer(q,"floorCount",null),integer(q,"area",null)).getData();
            if(path.equals("/cases/generation-candidates"))return c.getGenerationCandidates(integer(q,"floorCount",2),decimal(q,"faceWidth"),decimal(q,"depth"),q.getOrDefault("dimensionBasis","UNKNOWN"),null).getData();
            if(path.endsWith("/generation-candidate"))return c.getGenerationCandidate(Long.parseLong(id),Long.parseLong(q.get("caseVersionId")),null).getData();
            if(path.equals("/favorites"))return c.getFavorites(q.get("cursor"),integer(q,"limit",20),null).getData();
            if(path.endsWith("/favorite"))return method.equals("PUT")?c.favorite(id,null).getData():c.unfavorite(id,null).getData();
            return c.getCase(id).getData();
        }
        if(path.startsWith("/budget-drafts")) {
            var c=controller(new AppBudgetDraftController(),owner);
            if(parts.length==2)return method.equals("GET")?c.list(q.get("cursor"),integer(q,"limit",20),null).getData():c.create(key,null).getData();
            if(path.endsWith("/import"))return c.importProject(id,b,null).getData();
            if(path.endsWith("/name"))return c.rename(id,b,null).getData();
            if(path.endsWith("/estimates"))return c.createEstimate(id,b,key,null).getData();
            return method.equals("GET")?c.get(id,null).getData():c.save(id,b,null).getData();
        }
        if(path.startsWith("/budget/my-prices")) {
            var c=controller(new AppAccountPriceController(),owner);
            if(parts.length==3)return c.myPrices(q.get("regionCode"),null).getData();
            return method.equals("DELETE")?c.resetMyPrice(parts[3],q.get("regionCode"),null).getData():c.setMyPrice(parts[3],q.get("regionCode"),b,null).getData();
        }
        if(path.startsWith("/budget/")) {
            var c=controller(new AppBudgetCatalogController(),owner);
            if(path.equals("/budget/regions"))return c.regions(null).getData();
            if(path.equals("/budget/options"))return c.options(q.get("regionCode"),null).getData();
            if(path.equals("/budget/my-regions"))return method.equals("GET")?c.myRegions(null).getData():c.createMyRegion(b,null).getData();
            if(path.endsWith("/prices:copy"))return c.copyMyRegionPrices(parts[3],b,null).getData();
            if(path.endsWith("/prices"))return c.saveMyRegionPrices(parts[3],b,null).getData();
            return method.equals("GET")?c.myRegion(parts[3],null).getData():c.updateMyRegion(parts[3],b,null).getData();
        }
        if(path.startsWith("/budget-estimates") || path.contains("/budget-")) {
            var c=controller(new AppBudgetController(),owner);
            if(path.equals("/budget-estimates/independent"))return c.independentHistory(q.get("draftId"),bool(q,"savedOnly",false),q.get("cursor"),integer(q,"limit",20),null).getData();
            if(path.endsWith("/budget-inputs"))return c.getBudgetInputs(id,q.get("resultVersionId"),null).getData();
            if(path.endsWith("/itemized"))return c.createItemizedEstimate(id,b,key,null).getData();
            if(path.endsWith("/budget-estimates"))return c.getBudgetHistory(id,q.containsKey("savedOnly")?bool(q,"savedOnly",false):null,q.get("cursor"),integer(q,"limit",null),null).getData();
            if(path.endsWith("/budget-quotes"))return c.getBudgetQuotes(id,null).getData();
            if(path.endsWith("/save"))return c.saveBudget(id,key,null).getData();
            if(path.endsWith("/quotes"))return c.independentQuotes(id,null).getData();
            if(path.contains("/quotes/"))return c.exactBudgetQuote(id,parts[4],null).getData();
            return c.getBudget(id,q.get("revisionId"),null).getData();
        }
        if(path.startsWith("/design-projects")) {
            var c=controller(new AppDesignProjectController(),owner);
            if(parts.length==2)return method.equals("GET")?c.getProjectPage(q.get("cursor"),integer(q,"limit",20),null).getData():c.createProject(dto(b,AppDesignProjectCreateReqVO.class),null,key).getData();
            if(path.endsWith("/result-versions"))return c.getResultVersions(id,null).getData();
            if(path.endsWith("/name"))return c.renameProject(id,dto(b,AppProjectRenameReqVO.class),null).getData();
            if(path.endsWith("/flat-selections"))return c.selectFlat(id,dto(b,AppSelectionCreateReqVO.class),null).getData();
            if(path.endsWith("/elevation-selections"))return c.selectElevation(id,dto(b,AppSelectionCreateReqVO.class),null).getData();
            if(method.equals("DELETE"))return c.deleteProject(id,null).getData();
            return c.getProject(id,q.get("jobId"),null).getData();
        }
        throw new IllegalArgumentException("Unsupported design resource");
    }
}
