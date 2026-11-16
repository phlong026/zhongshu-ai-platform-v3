package cn.iocoder.yudao.module.franchise.api.capability;

import java.util.Map;
import java.util.Set;
import static cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;

/** Closed routes for the migrated design app. A path is a resource name, never an outbound URL. */
public record DesignFlowCall(String method, String path, Map<String,String> query,
                             Map<String,Object> body, String idempotencyKey) {
    public record Accepted(String projectId,String jobId,java.util.List<String> allowedActions) { }
    public DesignFlowCall {
        query=query==null?Map.of():Map.copyOf(query);
        body=body==null?Map.of():java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(body));
        if(method==null || path==null || path.length()>200 || path.contains("..") || path.contains("?")
                || !path.startsWith("/") || path.contains("//") || (path.contains(":")&&!path.endsWith("/prices:copy")) || query.size()>16 || body.size()>32) throw new IllegalArgumentException("Invalid design resource");
        if(!Set.of("GET","POST","PUT","PATCH","DELETE").contains(method)) throw new IllegalArgumentException("Invalid design method");
    }
    public Operation operation() {
        if(method.equals("POST") && path.matches("/design-projects/[1-9][0-9]*/flat-jobs")) return Operation.FLOW_FLAT_JOB;
        if(method.equals("POST") && path.matches("/design-projects/[1-9][0-9]*/elevation-jobs")) return Operation.FLOW_ELEVATION_JOB;
        if(method.equals("POST") && path.matches("/design-projects/[1-9][0-9]*/revision-requests")) return Operation.FLOW_REVISION;
        if(method.equals("POST") && (path.matches("/budget-drafts/[1-9][0-9]*/estimates")
                || path.matches("/design-projects/[1-9][0-9]*/budget-estimates/itemized"))) return Operation.FLOW_BUDGET;
        boolean read=method.equals("GET");
        boolean accepted=read && (path.matches("/cases(?:/(?:styles|recommendation|generation-candidates|[1-9][0-9]*(?:/generation-candidate)?))?")
                || path.equals("/favorites") || path.matches("/design-projects(?:/[1-9][0-9]*(?:/(?:result-versions|budget-inputs|budget-estimates|budget-quotes))?)?")
                || path.matches("/budget-drafts(?:/[1-9][0-9]*)?")
                || path.matches("/budget/(?:regions|options|my-prices|my-regions(?:/[A-Za-z0-9_-]{1,32})?)")
                || path.matches("/budget-estimates/(?:independent|[1-9][0-9]*(?:/quotes(?:/[1-9][0-9]*)?)?)"));
        accepted |= method.equals("POST") && (path.equals("/design-projects") || path.equals("/budget-drafts")
                || path.equals("/budget/my-regions") || path.matches("/budget-drafts/[1-9][0-9]*/import")
                || path.matches("/budget-estimates/[1-9][0-9]*/save")
                || path.matches("/design-projects/[1-9][0-9]*/(?:flat-selections|elevation-selections)")
                || path.matches("/budget/my-regions/[A-Za-z0-9_-]{1,32}/prices:copy"));
        accepted |= method.equals("PUT") && (path.matches("/budget-drafts/[1-9][0-9]*(?:/name)?")
                || path.matches("/cases/[1-9][0-9]*/favorite") || path.matches("/budget/my-prices/[1-9][0-9]*")
                || path.matches("/budget/my-regions/[A-Za-z0-9_-]{1,32}/prices"));
        accepted |= method.equals("PATCH") && (path.matches("/budget/my-regions/[A-Za-z0-9_-]{1,32}") || path.matches("/design-projects/[1-9][0-9]*/name"));
        accepted |= method.equals("DELETE") && (path.matches("/cases/[1-9][0-9]*/favorite") || path.matches("/budget/my-prices/[1-9][0-9]*") || path.matches("/design-projects/[1-9][0-9]*"));
        if(!accepted) throw new IllegalArgumentException("Unsupported design resource");
        return read && !(path.matches("/design-projects/[1-9][0-9]*") && query.containsKey("jobId")) ? Operation.FLOW_READ : Operation.FLOW_WRITE;
    }
    public long resourceId() { return Long.parseLong(path.split("/")[2]); }
    public int count() {
        Object value=body.get("count"); if(value==null && operation()==Operation.FLOW_REVISION)return 2;
        if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||!Set.of(1,2,4).contains(n.intValue()))throw new IllegalArgumentException("Invalid count");
        return n.intValue();
    }
    public String text(String key) { Object value=body.get(key);return value==null?null:String.valueOf(value); }
    public DesignFlowCall withKey(String key) { return new DesignFlowCall(method,path,query,body,key); }
}
