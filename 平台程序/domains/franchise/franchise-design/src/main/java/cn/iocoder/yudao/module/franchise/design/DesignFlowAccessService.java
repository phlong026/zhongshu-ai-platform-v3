package cn.iocoder.yudao.module.franchise.design;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.api.capability.*;
import cn.iocoder.yudao.module.franchise.design.publiccase.PublicCaseAccessService;
import cn.iocoder.yudao.module.franchise.design.remote.*;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;
import java.math.BigDecimal;
import java.util.*;

/** B identity/company boundary. Domain inputs, budget formulas and public projections stay in the original services. */
@Service
public class DesignFlowAccessService {
    private final DesignAccessService access;
    private final RemoteDesignGateway remote;
    private final PublicCaseAccessService cases;
    private final RemoteReferenceReader references;
    private final AssetAccessPort assets;
    private final OrgPointPort points;
    public DesignFlowAccessService(DesignAccessService access,@Nullable RemoteDesignGateway remote,
            @Nullable PublicCaseAccessService cases,@Nullable RemoteReferenceReader references,
            @Nullable AssetAccessPort assets,OrgPointPort points) {
        this.access=access;this.remote=remote;this.cases=cases;this.references=references;this.assets=assets;this.points=points;
    }
    public OrgPointPort.PriceQuote quote(PostSnapshot post,String subject,int quantity) {
        if(Set.of("ai_flat","ai_elevation").contains(subject)) DesignGenerationPolicy.rejectGeneration();
        if(remote==null)throw unavailable();
        if(!Set.of("ai_flat","ai_elevation","budget_estimate").contains(subject)
                ||(!Set.of(1,2,4).contains(quantity))||("budget_estimate".equals(subject)&&quantity!=1))throw new IllegalArgumentException("Invalid design price query");
        points.assertSubjectAllowed(post.orgId(),post.userId(),subject);
        return points.quotePrice(subject,quantity);
    }
    public Object job(PostSnapshot post,long id,boolean cancel,String key) {
        if(cancel)access.cancelAiJob(post,id,key);
        var value=access.getAiJob(post,id);var row=new LinkedHashMap<String,Object>();
        row.put("jobId",String.valueOf(value.jobId()));row.put("projectId",value.projectId());row.put("status",value.status());row.put("phase",value.phase());
        row.put("requestedCount",value.requestedCount());row.put("acceptedCount",value.acceptedCount());row.put("progress",value.progress());row.put("imageOptions",value.imageOptions());
        row.put("allowedActions",value.allowedActions());row.put("createdAt",value.createdAt());row.put("finishedAt",value.finishedAt());
        if(remote!=null)row.putAll(remote.jobPointView(post,id));
        return row;
    }
    public Object invoke(PostSnapshot post,DesignFlowCall original) {
        var operation=original.operation();String path=original.path(),method=original.method();var q=original.query();
        if(Set.of(DesignCapabilityContract.Operation.FLOW_FLAT_JOB, DesignCapabilityContract.Operation.FLOW_ELEVATION_JOB,
                DesignCapabilityContract.Operation.FLOW_REVISION).contains(operation)) DesignGenerationPolicy.rejectGeneration();
        if ("POST".equals(method) && (path.endsWith("/flat-selections") || path.endsWith("/elevation-selections"))) {
            throw DesignGenerationPolicy.retired();
        }
        if(path.equals("/cases"))return access.listCases(post,new DesignServicePort.CaseQuery(q.get("styleCode"),integer(q,"floorCount",null),decimal(q,"faceWidth"),decimal(q,"depth"),q.get("keyword"),q.get("cursor"),integer(q,"limit",20),q.get("sourceType"),integer(q,"minArea",null),integer(q,"maxArea",null)));
        if(path.equals("/cases/styles"))return access.listCaseStyles(post);
        if(path.equals("/cases/generation-candidates") || path.endsWith("/generation-candidate") || path.equals("/cases/recommendation")) {
            if(cases==null)throw unavailable();
            return cases.generation(post,path,q);
        }
        if(path.matches("/cases/[1-9][0-9]*"))return access.getCaseDetail(post,original.resourceId()).fields();
        if(path.endsWith("/favorite")) {
            if(method.equals("PUT"))access.favoriteCase(post,original.resourceId(),original.idempotencyKey());
            else access.unfavoriteCase(post,original.resourceId(),original.idempotencyKey());
            return true;
        }
        if(path.equals("/favorites"))return access.listFavorites(post,q.get("cursor"),integer(q,"limit",20));
        if(remote==null)throw unavailable();
        DesignFlowCall call=translate(post,original);
        Object result=remote.call(post,operation,operation.mutating()?call.idempotencyKey():null,call);
        if(method.equals("GET")&&path.matches("/design-projects(?:/[1-9][0-9]*)?"))return projectBilling(post,result);
        return result;
    }
    private Object projectBilling(PostSnapshot post,Object result) {
        if(!(result instanceof Map<?,?> value))throw new IllegalStateException("Invalid design project projection");
        var rows=new ArrayList<Map<?,?>>();
        if(value.containsKey("projectId"))rows.add(value);
        else if(value.get("list") instanceof List<?> list)for(Object item:list){if(!(item instanceof Map<?,?> row))throw new IllegalStateException("Invalid design project row");rows.add(row);}
        var ids=new LinkedHashSet<Long>();rows.forEach(row->ids.add(Long.parseLong(String.valueOf(row.get("projectId")))));
        var spent=remote.projectPointsSpent(post,ids);
        var output=new LinkedHashMap<Object,Object>(value);
        java.util.function.Function<Map<?,?>,Map<Object,Object>> copy=row->{var next=new LinkedHashMap<Object,Object>(row);next.put("pointsSpent",spent.get(Long.parseLong(String.valueOf(row.get("projectId")))));return next;};
        if(value.containsKey("projectId"))return copy.apply(value);
        output.put("list",rows.stream().map(copy).toList());return output;
    }
    private DesignFlowCall translate(PostSnapshot post,DesignFlowCall call) {
        boolean project=call.path().equals("/design-projects")&&call.method().equals("POST");
        boolean elevation=call.operation()==DesignCapabilityContract.Operation.FLOW_ELEVATION_JOB;
        if(!project&&!elevation)return call;
        var b=new LinkedHashMap<>(call.body());
        @SuppressWarnings("unchecked") var inputs=(Map<String,Object>)b.get("requirementInputs");
        Long sketch=b.get("sketchAssetId")==null?null:Long.valueOf(String.valueOf(b.get("sketchAssetId")));
        if(sketch==null&&RemoteReferenceReader.referenceIds(inputs).isEmpty())return call;
        if(references==null||assets==null)throw unavailable();
        var cmd=new DesignServicePort.DesignProjectCreate(String.valueOf(b.getOrDefault("sourceType","SELF_UPLOAD")),
                b.get("refCaseId")==null?null:Long.valueOf(String.valueOf(b.get("refCaseId"))),sketch,
                inputs==null?Map.of():inputs,String.valueOf(b.getOrDefault("line",elevation?"ELEVATION":"FLAT")),call.idempotencyKey());
        var result=references.translate(post,cmd,assets,new AssetAccessPort.Actor(post.orgId(),access.serviceAccount(post),post.userId(),post.postId()));
        b.put("requirementInputs",result.requirementInputs());
        if(sketch!=null)b.put("sketchAssetId",String.valueOf(result.sketchAssetId()));
        return new DesignFlowCall(call.method(),call.path(),call.query(),b,call.idempotencyKey());
    }
    public DesignServicePort.UploadTicketView upload(PostSnapshot post,DesignServicePort.UploadTicketCreate cmd) {
        if(!Set.of("USER_SKETCH","DESIGN_REFERENCE").contains(cmd.assetType())||!Set.of("image/jpeg","image/png").contains(cmd.mimeType())
                ||cmd.sizeBytes()<1||cmd.sizeBytes()>("USER_SKETCH".equals(cmd.assetType())?10L:20L)*1024*1024)throw new IllegalArgumentException("Invalid design image");
        return access.createUploadTicket(post,new DesignServicePort.UploadTicketCreate("DESIGN_REFERENCE",cmd.mimeType(),cmd.sizeBytes(),cmd.sha256()));
    }
    private static Integer integer(Map<String,String> q,String name,Integer fallback){return q.get(name)==null?fallback:Integer.valueOf(q.get(name));}
    private static BigDecimal decimal(Map<String,String> q,String name){return q.get(name)==null?null:new BigDecimal(q.get(name));}
    private static RuntimeException unavailable(){return new cn.iocoder.yudao.framework.common.exception.ServiceException(503,"完整设计能力服务尚未配置，请联系平台管理员");}
}
