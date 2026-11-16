package cn.iocoder.yudao.module.design.capability.billing;

import cn.iocoder.yudao.module.design.ai.NotePolishService;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.capability.ServiceDesignBillingPort;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.*;
import cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions;
import cn.iocoder.yudao.module.infra.zhongshu.api.PlatformCostPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Service billing entry: prepare can call runtime, execute joins only the receipt's short local transaction. */
@Service
@ConditionalOnProperty(name = "zhongshu.design.capability.enabled", havingValue = "true")
public class DurableServiceDesignBilling implements ServiceDesignBillingPort {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.iocoder.yudao.module.design.capability.OriginalDesignFlow originalFlow;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.iocoder.yudao.module.design.capability.CaseReferenceImportPort caseReferences;
    private final PlatformCostPort costs;
    private final DesignProjectService projects;
    private final BudgetService budgets;
    private final NotePolishService notes;
    private final ObjectMapper json = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public DurableServiceDesignBilling(PlatformCostPort costs,
                                      @org.springframework.beans.factory.annotation.Qualifier("capabilityDesignProjectService") DesignProjectService projects,
                                      @org.springframework.beans.factory.annotation.Qualifier("capabilityBudgetService") BudgetService budgets,
                                      NotePolishService notes) {
        this.costs=costs;
        this.projects=projects;
        this.budgets=budgets;
        this.notes=notes;
    }

    @Override public void requireReady() {
        costs.requireReady();
        // A reachable legacy polish route does not prove durable runtime receipt recovery exists.
        notes.serviceReceipt(0, "service-capability-readiness");
    }

    @Override public Prepared prepare(ServiceScope scope, Operation operation, Object payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SERVICE_PREPARATION_MUST_RUN_OUTSIDE_TRANSACTION");
        }
        validate(operation,payload);
        refreshReferences(scope,operation,payload);
        var preparation=costs.prepare(subject(scope),product(operation,payload),count(operation,payload),fingerprint(operation,payload));
        if (operation==Operation.NOTE_POLISH && preparation.resultJson()==null) {
            if (!"PREPARED".equals(preparation.state())) {
                return recoverPreparation(scope,operation,payload)
                        .orElseThrow(() -> new IllegalStateException("SERVICE_PREPARATION_UNKNOWN_RECONCILE_RECEIPT"));
            }
            Note note=(Note)payload;
            NotePolishService.serviceRequestHash(note.note(), note.line());
            if (costs.beginExternal(preparation.id())) {
                String result=notes.polishForService(scope.serviceAccountId(),note.note(),note.line(),preparation.taskKey());
                costs.saveExternalResult(preparation.id(),write(result));
            }
        }
        return new Prepared(preparation.id());
    }

    @Override public Optional<Prepared> recoverPreparation(ServiceScope scope, Operation operation, Object payload) {
        return recoverPreparation(scope, operation, payload, true);
    }

    @Override public Optional<Prepared> recoverPreparation(ServiceScope scope, Operation operation, Object payload, boolean allowDispatch) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SERVICE_RECOVERY_MUST_RUN_OUTSIDE_TRANSACTION");
        }
        validate(operation,payload);
        var preparation=costs.recover(subject(scope),fingerprint(operation,payload));
        if (preparation.isEmpty()) return allowDispatch ? Optional.of(prepare(scope,operation,payload)) : Optional.empty();
        var stored=preparation.get();
        if (!allowDispatch && "PREPARED".equals(stored.state())) return Optional.empty();
        if (allowDispatch) refreshReferences(scope,operation,payload);
        if (operation==Operation.NOTE_POLISH && stored.resultJson()==null) {
            if ("PREPARED".equals(stored.state())) return allowDispatch ? Optional.of(prepare(scope,operation,payload)) : Optional.empty();
            if ("EXTERNAL_FAILED".equals(stored.state()) || "REJECTED".equals(stored.state())) {
                throw new cn.iocoder.yudao.framework.common.exception.ServiceException(429,"润色服务已明确拒绝该请求");
            }
            var receipt=notes.serviceReceipt(scope.serviceAccountId(),stored.taskKey());
            Note note=(Note)payload;
            if ("NOT_FOUND".equals(receipt.status())) {
                if (!allowDispatch) return Optional.empty();
                // The durable runtime lookup proved no record. Reuse the very same receipt key;
                // runtime's atomic claim also fences a delayed original sender.
                costs.authorizeExternalRecovery(stored.id());
                String result=notes.polishForService(scope.serviceAccountId(),note.note(),note.line(),stored.taskKey());
                costs.saveExternalResult(stored.id(),write(result));
                return Optional.of(new Prepared(stored.id()));
            }
            if (!"NOT_FOUND".equals(receipt.status())
                    && !NotePolishService.serviceRequestHash(note.note(),note.line()).equals(receipt.requestHash())) {
                throw new IllegalStateException("SERVICE_POLISH_RECEIPT_FINGERPRINT_CONFLICT");
            }
            if ("FAILED".equals(receipt.status())) {
                costs.recordExternalFailure(stored.id(),write(receipt));
                throw new cn.iocoder.yudao.framework.common.exception.ServiceException(429,"润色服务已明确拒绝该请求");
            }
            if (!"SUCCEEDED".equals(receipt.status())) return Optional.empty();
            costs.saveExternalResult(stored.id(),write(receipt.polished()));
        }
        return Optional.of(new Prepared(stored.id()));
    }

    @Override public Object execute(ServiceScope scope, Operation operation, Object payload, Prepared prepared) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SERVICE_EXECUTION_REQUIRES_RECEIPT_TRANSACTION");
        }
        validate(operation,payload);
        String fingerprint=fingerprint(operation,payload);
        costs.requirePrepared(subject(scope),prepared.preparationId(),fingerprint);
        String taskKey="svc:"+prepared.preparationId();
        return switch (operation) {
            case FLOW_FLAT_JOB,FLOW_ELEVATION_JOB,FLOW_REVISION -> {
                var call=(cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload;
                if(caseReferences!=null&&operation==Operation.FLOW_ELEVATION_JOB)call=caseReferences.mapped(scope.serviceAccountId(),call);
                long project=call.resourceId();long job;
                if(operation==Operation.FLOW_FLAT_JOB)job=projects.createFlatJob(scope.serviceAccountId(),project,call.count(),taskKey,null,
                        GenerationImageOptions.normalize("FLAT",call.text("resolution"),call.text("orientation"))).jobId();
                else if(operation==Operation.FLOW_ELEVATION_JOB) {
                    var config=new LinkedHashMap<String,Object>();
                    if(call.body().get("requirementInputs") instanceof java.util.Map<?,?> inputs) {
                        inputs.forEach((name,value)->config.put(String.valueOf(name),value));
                        config.putIfAbsent("referenceAssetIds",java.util.List.of());
                        config.put("referenceCaseId",call.body().get("refCaseId")==null?"":String.valueOf(call.body().get("refCaseId")));
                    }
                    for(String name:java.util.List.of("styleCode","roofType","material","color","prompt"))if(call.text(name)!=null&&!call.text(name).isBlank())config.put(name,call.text(name));
                    config.put("count",call.count());
                    job=projects.createElevationJob(scope.serviceAccountId(),project,call.count(),taskKey,config,null,
                            GenerationImageOptions.normalize("ELEVATION",call.text("resolution"),call.text("orientation"))).jobId();
                } else {
                    var options=GenerationImageOptions.normalize("ELEVATION",call.text("resolution"),call.text("orientation"));
                    @SuppressWarnings("unchecked") var updates=(java.util.Map<String,Object>)call.body().get("configUpdates");
                    job=projects.createRevisionRequestReceipt(scope.serviceAccountId(),project,call.text("reason"),updates,call.count(),taskKey,null,options.resolution(),options.orientation()).newJobId();
                }
                yield new cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall.Accepted(String.valueOf(project),String.valueOf(job),java.util.List.of("POLL_JOB"));
            }
            case FLOW_BUDGET -> {
                if(originalFlow==null)throw new IllegalStateException("Original itemized budget service is unavailable");
                Object result=originalFlow.invoke(scope.serviceAccountId(),((cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload).withKey(taskKey));
                costs.finishUsage(prepared.preparationId(),write(result));
                yield result;
            }
            case CREATE_FLAT_JOB -> {
                FlatJob input=(FlatJob)payload;
                var cmd=input.command();
                var options=GenerationImageOptions.normalize("FLAT",cmd.resolution(),cmd.orientation());
                var result=projects.createFlatJob(scope.serviceAccountId(),input.projectId(),cmd.count(),taskKey,null,options);
                yield new DesignServicePort.JobSubmitted(result.jobId(),!result.created());
            }
            case CREATE_ELEVATION_JOB -> {
                ElevationJob input=(ElevationJob)payload;
                var cmd=input.command();
                var config=new LinkedHashMap<String,Object>();
                put(config,"styleCode",cmd.styleCode()); put(config,"roofType",cmd.roofType());
                put(config,"material",cmd.material()); put(config,"color",cmd.color());
                var result=projects.createElevationJob(scope.serviceAccountId(),input.projectId(),cmd.count(),taskKey,config,null,
                        GenerationImageOptions.normalize("ELEVATION",cmd.resolution(),cmd.orientation()));
                yield new DesignServicePort.JobSubmitted(result.jobId(),!result.created());
            }
            case CREATE_REVISION_REQUEST -> {
                Revision input=(Revision)payload;
                var cmd=input.command();
                var options=GenerationImageOptions.normalize("ELEVATION",cmd.resolution(),cmd.orientation());
                var result=projects.createRevisionRequestReceipt(scope.serviceAccountId(),input.projectId(),cmd.reason(),cmd.configUpdates(),
                        cmd.count()==null?2:cmd.count(),taskKey,null,options.resolution(),options.orientation());
                yield new DesignServicePort.RevisionSubmitted(result.requestId(),result.newJobId(),result.replayed());
            }
            case CREATE_BUDGET_ESTIMATE -> {
                Budget input=(Budget)payload;
                var cmd=input.command();
                var result=budgets.createServiceEstimate(costs,subject(scope),fingerprint,input.projectId(),cmd.regionCode(),
                        cmd.structureType(),cmd.materialGrade(),cmd.buildingArea(),cmd.resultVersionId());
                var response=new DesignServicePort.EstimateCreated(result.estimateId(),result.totalLowCents(),result.totalHighCents(),result.ruleVersionId(),false);
                costs.finishUsage(prepared.preparationId(),write(response));
                yield response;
            }
            case NOTE_POLISH -> {
                var stored=costs.recover(subject(scope),fingerprint).orElseThrow();
                if (stored.resultJson()==null) throw new IllegalStateException("SERVICE_PREPARATION_UNKNOWN_RECONCILE_RECEIPT");
                String result=readText(stored.resultJson());
                costs.finishUsage(prepared.preparationId(),stored.resultJson());
                yield result;
            }
            default -> throw new IllegalArgumentException("Operation is not billable");
        };
    }

    @Override public String completeRecoveredPolish(ServiceScope scope, Note payload, Prepared prepared) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SERVICE_RECOVERY_REQUIRES_RECEIPT_TRANSACTION");
        }
        if (!scope.receiptId().equals(prepared.preparationId())) throw new IllegalStateException("SERVICE_PREPARATION_IDENTITY_CONFLICT");
        String fingerprint=fingerprint(Operation.NOTE_POLISH,payload);
        var stored=costs.recover(subject(scope),fingerprint).orElseThrow(() -> new IllegalStateException("SERVICE_PREPARATION_MISSING"));
        String result=readText(stored.resultJson());
        costs.finishRecoveredPolish(subject(scope),fingerprint,stored.resultJson());
        return result;
    }

    @Override public Optional<Rejection> resolveFailure(ServiceScope scope, Operation operation, Object payload, RuntimeException failure) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SERVICE_FAILURE_REQUIRES_RECEIPT_TRANSACTION");
        }
        // The decision is made from durable dispatch state; no external request runs under this lock.
        if (!costs.rejectPrepared(subject(scope), fingerprint(operation,payload))) return Optional.empty();
        if (failure instanceof cn.iocoder.yudao.framework.common.exception.ServiceException error) {
            return Optional.of(new Rejection(error.getCode(), error.getMessage()));
        }
        return Optional.of(new Rejection(failure instanceof IllegalArgumentException ? 400 : 503,
                failure instanceof IllegalArgumentException ? "设计请求参数无效" : "设计服务暂不可用"));
    }

    @Override public Settlement settlement(ServiceScope scope,long jobId) {
        costs.requireOwner(subject(scope),jobId);
        var facts=costs.facts(jobId);
        return new Settlement(jobId,facts.version(),facts.status(),facts.terminal(),facts.requestedCount(),facts.validResultCount(),
                facts.reserved(),facts.charged(),facts.refunded(),facts.promptUnits(),facts.imageUnits(),facts.usageUnits());
    }

    private static PlatformCostPort.Subject subject(ServiceScope scope) {
        return new PlatformCostPort.Subject(scope.callerAppId(),scope.orgId(),scope.tenantId(),scope.serviceAccountId(),scope.receiptId(),scope.requestKey());
    }
    private static void validate(Operation operation,Object payload) {
        if (!operation.paid() || !operation.payloadType().isInstance(payload)) throw new IllegalArgumentException("Invalid billable operation payload");
        if(payload instanceof cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall call && call.operation()!=operation)throw new IllegalArgumentException("Mismatched design flow billing");
    }
    private void refreshReferences(ServiceScope scope,Operation operation,Object payload) {
        if(caseReferences==null)return;
        if(payload instanceof cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall flow && operation!=Operation.FLOW_BUDGET) {
            if(operation==Operation.FLOW_ELEVATION_JOB&&flow.body().containsKey("refCaseId")) {
                if(flow.body().get("refCaseId")!=null)caseReferences.prepare(scope.serviceAccountId(),flow);
            } else caseReferences.refreshProject(scope.serviceAccountId(),flow.resourceId());
        } else if(payload instanceof FlatJob flat)caseReferences.refreshProject(scope.serviceAccountId(),flat.projectId());
        else if(payload instanceof ElevationJob elev)caseReferences.refreshProject(scope.serviceAccountId(),elev.projectId());
        else if(payload instanceof Revision revision)caseReferences.refreshProject(scope.serviceAccountId(),revision.projectId());
    }
    private static int count(Operation operation,Object payload) {
        return switch (operation) {
            case FLOW_FLAT_JOB,FLOW_ELEVATION_JOB,FLOW_REVISION -> ((cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload).count();
            case CREATE_FLAT_JOB -> ((FlatJob)payload).command().count();
            case CREATE_ELEVATION_JOB -> ((ElevationJob)payload).command().count();
            case CREATE_REVISION_REQUEST -> ((Revision)payload).command().count()==null?2:((Revision)payload).command().count();
            default -> 0;
        };
    }
    private static String product(Operation operation,Object payload) {
        return switch (operation) {
            case FLOW_FLAT_JOB -> "FLAT:"+GenerationImageOptions.normalize("FLAT",((cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload).text("resolution"),((cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload).text("orientation")).resolution();
            case FLOW_ELEVATION_JOB,FLOW_REVISION -> "ELEVATION:"+GenerationImageOptions.normalize("ELEVATION",((cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload).text("resolution"),((cn.iocoder.yudao.module.franchise.api.capability.DesignFlowCall)payload).text("orientation")).resolution();
            case FLOW_BUDGET -> "BUDGET_ESTIMATE";
            case CREATE_FLAT_JOB -> "FLAT:"+GenerationImageOptions.normalize("FLAT",((FlatJob)payload).command().resolution(),((FlatJob)payload).command().orientation()).resolution();
            case CREATE_ELEVATION_JOB -> "ELEVATION:"+GenerationImageOptions.normalize("ELEVATION",((ElevationJob)payload).command().resolution(),((ElevationJob)payload).command().orientation()).resolution();
            case CREATE_REVISION_REQUEST -> "ELEVATION:"+GenerationImageOptions.normalize("ELEVATION",((Revision)payload).command().resolution(),((Revision)payload).command().orientation()).resolution();
            case CREATE_BUDGET_ESTIMATE -> "BUDGET_ESTIMATE";
            case NOTE_POLISH -> "NOTE_POLISH";
            default -> throw new IllegalArgumentException("Operation is not billable");
        };
    }
    private String fingerprint(Operation operation,Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((operation.name()+"\n"+write(payload)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Invalid billing result",error); }
    }
    private String readText(String value) {
        try { return json.readValue(value,String.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Stored billing result is invalid",error); }
    }
    private static void put(java.util.Map<String,Object> config,String key,Object value) { if (value!=null) config.put(key,value); }
}
