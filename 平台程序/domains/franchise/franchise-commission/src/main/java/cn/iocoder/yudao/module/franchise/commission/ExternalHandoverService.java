package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.commission.dal.*;
import cn.iocoder.yudao.module.franchise.crm.CustomerAccess;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerMapper;
import cn.iocoder.yudao.module.franchise.crm.workflow.CustomerWorkflowModel.Attachment;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.commission.ExternalHandoverModel.*;
import static cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO.*;

/** Business submits frozen external evidence; the designated engineer accepts and creates the real project atomically. */
@Service
public class ExternalHandoverService {
    private static final String AGGREGATE="fr-external-handover";
    private final JdbcTemplate jdbc;
    private final ExternalHandoverAccess access;
    private final ExternalCommissionService external;
    private final CommissionHandoverMapper handovers;
    private final CustomerMapper customers;
    private final FranchiseCommands commands;
    private final StaffPostService staff;
    private final SystemDirectory directory;
    private final EngineeringPort engineering;
    private final EngineeringProjectContextPort projects;
    private final SaleContractQueryPort contracts;
    private final AssetAccessPort assets;
    private final UnifiedStageAccountPort stageAccounts;
    private final StoreServiceAccountPort accounts;
    private final ObjectProvider<AssetEvidenceQueryPort> evidence;
    public ExternalHandoverService(JdbcTemplate jdbc, ExternalHandoverAccess access, ExternalCommissionService external,
            CommissionHandoverMapper handovers, CustomerMapper customers, FranchiseCommands commands, StaffPostService staff,
            SystemDirectory directory, EngineeringPort engineering, EngineeringProjectContextPort projects,
            SaleContractQueryPort contracts, UnifiedStageAccountPort stageAccounts, AssetAccessPort assets, StoreServiceAccountPort accounts,
            ObjectProvider<AssetEvidenceQueryPort> evidence) {
        this.jdbc=jdbc; this.access=access; this.external=external; this.handovers=handovers; this.customers=customers;
        this.commands=commands; this.staff=staff; this.directory=directory; this.engineering=engineering; this.projects=projects;
        this.contracts=contracts; this.stageAccounts=stageAccounts; this.assets=assets; this.accounts=accounts; this.evidence=evidence;
    }

    @Transactional(rollbackFor=Exception.class)
    public View get(PostSnapshot post,long commissionId) {
        var subject=access.readable(post,commissionId); var commission=subject.commission();
        var rows=access.packets(commissionId,post.orgId()).stream().filter(h -> access.canReadPacket(post,subject,h)).toList();
        var current=rows.stream().filter(h -> Objects.equals(h.getVersion(),commission.getHandoverVersion())).findFirst().orElse(null);
        boolean submit=CustomerAccess.canWrite(post,subject.customer()) && commission.getExternalResultVersion()!=null
                && Set.of(STATUS_EXTERNAL_READY,STATUS_HANDOVER_RETURNED).contains(commission.getStatus());
        boolean decide=access.canDecide(post,subject,current);
        var actions=new ArrayList<String>();
        if (submit) actions.add("external-packet-submit");
        if (decide) actions.addAll(List.of("external-packet-return","external-packet-receive"));
        var context=projects.findBySourceCommission(post.orgId(),commissionId);
        Map<Long,String> names=new HashMap<>();
        if (!rows.isEmpty()) directory.usersByIds(rows.stream().map(CommissionHandoverDO::getManagerUserId).distinct().toList())
                .forEach(u -> names.put(u.userId(),u.name()));
        var packets=rows.stream().map(h -> packet(h,names.getOrDefault(h.getManagerUserId(),""))).toList();
        Packet currentPacket=packets.stream().filter(h -> Objects.equals(h.version(),commission.getHandoverVersion())).findFirst().orElse(null);
        CustomerPaymentPlanDraftPort.Snapshot currentTerms=null;
        if (currentPacket!=null && currentPacket.paymentPlan()!=null && currentPacket.paymentPlan().contractId()!=null
                && ("PENDING".equals(currentPacket.status()) || "ACCEPTED".equals(currentPacket.status()))
                && (CustomerAccess.canWrite(post,subject.customer()) || currentPacket.managerUserId().equals(Long.toString(post.userId())))) {
            var actual=terms(post,subject.customer().getId(),null);
            if (ContractVersion.of(currentPacket.paymentPlan()).equals(ContractVersion.of(actual))) currentTerms=actual;
        }
        if (decide && !validConstructionTerms(currentTerms)) actions.remove("external-packet-receive");
        SubmissionTerms submissionTerms=null;
        if (submit) {
            var request=external.requests(commissionId,post.orgId()).stream()
                    .filter(r -> Objects.equals(r.version(),commission.getExternalRequestVersion())).findFirst()
                    .orElseThrow(() -> conflict("当前委托版本不存在"));
            submissionTerms=new SubmissionTerms(terms(post,subject.customer().getId(),request.paymentPlan()));
        }
        return new View(Long.toString(commissionId),subject.customer().getId().toString(),subject.customer().getName(),
                "EXTERNAL",commission.getStatus(),commission.getRevision(),commission.getExternalResultVersion(),
                context==null?null:Long.toString(context.projectId()),submit,decide,List.copyOf(actions),
                currentPacket,packets,submissionTerms,currentTerms);
    }

    @Transactional(rollbackFor=Exception.class)
    public View submit(PostSnapshot post,long commissionId,Submit command,String key) {
        Submit input=normalize(command); String action="external-packet-submit";
        var subject=lock(post,commissionId,key,true); var commission=subject.commission();
        var mutation=new Mutation(action,commissionId,input);
        if (replay(post,key,mutation)) return get(post,commissionId);
        revision(input.revision(),commission.getRevision());
        if (!Set.of(STATUS_EXTERNAL_READY,STATUS_HANDOVER_RETURNED).contains(commission.getStatus())) throw conflict("请先登记外部成果或等待工程退回后再提交");
        if (!Objects.equals(commission.getExternalResultVersion(),input.resultVersion())) throw exception(STATE_VERSION_CONFLICT);
        var result=external.results(commissionId,post.orgId()).stream().filter(r -> r.version()==input.resultVersion())
                .findFirst().orElseThrow(() -> conflict("当前外部成果不存在"));
        if (!Objects.equals(commission.getExternalRequestVersion(),result.data().requestVersion())) throw exception(STATE_VERSION_CONFLICT);
        var request=external.requests(commissionId,post.orgId()).stream().filter(r -> r.version()==result.data().requestVersion())
                .findFirst().orElseThrow(() -> conflict("成果对应的委托版本不存在"));
        requireManager(post,Long.parseLong(input.managerUserId()));
        external.requireHistoricalSettlement(post,commission);
        var files=new ArrayList<>(result.attachments()); files.add(new Attachment(input.confirmationAssetId(),"客户确认凭据"));
        external.validateFiles(post,subject.customer().getId(),files);
        var plan=terms(post,subject.customer().getId(),request.paymentPlan());
        // Older clients showed the frozen design request. Accept their confirmation only if it still
        // identifies the same construction contract and installment version that will be handed over.
        var checked=input.checkedContract()==null?ContractVersion.of(request.paymentPlan()):input.checkedContract();
        if (!checked.equals(ContractVersion.of(plan))) throw conflict("施工合同或付款计划已变化，请重新核对后移交");
        Integer prior=handovers.selectMaxVersion(commissionId); int version=prior==null?1:prior+1;
        jdbc.update("""
                INSERT INTO fr_commission_handover(id,company_id,commission_id,version,dispatch_seq,source_mode,
                  external_result_version,result_file,drawing_file,review_file,confirmation_file,confirmation_asset_id,
                  drawing_version,confirmation_date,note,manager_user_id,status,submitted_by_user_id,payment_plan_snapshot)
                VALUES(?,?,?,?,0,'EXTERNAL',?,?,?,?,?,?,?,?,?,?,'PENDING',?,CAST(? AS jsonb))
                """,IdWorker.getId(),post.orgId(),commissionId,version,input.resultVersion(),result.data().resultAssetId(),
                result.data().drawingAssetId(),result.data().reviewAssetId(),input.confirmationAssetId(),Long.parseLong(input.confirmationAssetId()),
                result.data().drawingVersion(),input.confirmationDate(),input.note(),Long.parseLong(input.managerUserId()),post.userId(),
                plan==null?null:JsonUtils.toJsonString(plan));
        updateCommission(commission,STATUS_HANDOVER_SUBMITTED,version);
        commands.record(post,AGGREGATE,commissionId,commission.getRevision()+1,action,mutation,key);
        return get(post,commissionId);
    }

    @Transactional(rollbackFor=Exception.class)
    public View returnPacket(PostSnapshot post,long commissionId,Decision command,String key) { return decide(post,commissionId,command,key,false); }
    @Transactional(rollbackFor=Exception.class)
    public View receive(PostSnapshot post,long commissionId,Decision command,String key) { return decide(post,commissionId,command,key,true); }

    private View decide(PostSnapshot post,long commissionId,Decision command,String key,boolean accept) {
        if (command==null || command.revision()==null || command.handoverVersion()<1) throw invalid("请提供移交版本");
        String reason=text(command.reason(),500,!accept,"退回原因");
        if (accept && !command.confirmed()) throw invalid("请核对移交资料并确认接收");
        Decision input=new Decision(command.revision(),command.handoverVersion(),reason,command.confirmed());
        String action=accept?"external-packet-receive":"external-packet-return";
        var subject=lock(post,commissionId,key,false); var commission=subject.commission();
        var mutation=new Mutation(action,commissionId,input);
        if (replay(post,key,mutation)) return get(post,commissionId);
        revision(input.revision(),commission.getRevision());
        var packet=access.packet(post,subject,input.handoverVersion());
        if (!access.canDecide(post,subject,packet)) throw conflict("该移交不是当前待接收版本，或未分配给当前工程接收人");
        if (accept) {
            external.requireHistoricalSettlement(post,commission);
            requireEvidence(packet);
        }
        int changed=jdbc.update("UPDATE fr_commission_handover SET status=?,decision_note=?,decided_by_user_id=?,decided_at=CURRENT_TIMESTAMP"
                +" WHERE id=? AND company_id=? AND status='PENDING'",accept?"ACCEPTED":"RETURNED",reason,post.userId(),packet.getId(),post.orgId());
        if (changed!=1) throw exception(STATE_VERSION_CONFLICT);
        updateCommission(commission,accept?STATUS_ACCEPTED:STATUS_HANDOVER_RETURNED,packet.getVersion());
        if (accept) {
            var result=external.results(commissionId,post.orgId()).stream().filter(r -> r.version()==packet.getExternalResultVersion()).findFirst().orElseThrow(() -> conflict("移交成果不存在"));
            var request=external.requests(commissionId,post.orgId()).stream().filter(r -> r.version()==result.data().requestVersion()).findFirst().orElseThrow(() -> conflict("移交委托不存在"));
            var customer=subject.customer();
            var docs=files(packet).stream().map(f -> new EngineeringPort.HandoverDocument(f.title(),f.assetId(),
                    "DRAWING".equals(f.slot())?"DRAWING":"DOCUMENT",packet.getDrawingVersion())).toList();
            var frozen=storedPlan(packet);
            if (!validConstructionTerms(frozen)) throw conflict("请退回业务补齐已签施工合同及各期有效付款约定，再提交工程接收");
            var expected=ContractVersion.of(frozen);
            Long contractId=frozen==null?null:frozen.contractId();
            requireCurrentContract(post,customer.getId(),expected);
            engineering.receiveFromHandover(post,new EngineeringPort.HandoverProject(commissionId,customer.getId(),customer.getName(),
                    customer.getCity(),customer.getPhone(),request.data().area(),request.data().floors(),packet.getManagerUserId(),
                    packet.getDrawingVersion(),packet.getDrawingFile(),docs,packet.getNote(),
                    contractId,null));
            // Engineering now holds the project lock followed by its contract lock. Recheck after
            // any concurrent plan amendment and roll back the entire acceptance on disagreement.
            requireCurrentContract(post,customer.getId(),expected);
        }
        commands.record(post,AGGREGATE,commissionId,commission.getRevision()+1,action,mutation,key);
        return get(post,commissionId);
    }

    @Transactional(rollbackFor=Exception.class)
    public AssetAccessPort.DownloadUrlView download(PostSnapshot post,long commissionId,int version,String slot) {
        CommissionDeliverableAccessPort.Slot kind=switch(slot) {
            case "RESULT" -> CommissionDeliverableAccessPort.Slot.EXT_RESULT;
            case "DRAWING" -> CommissionDeliverableAccessPort.Slot.EXT_DRAWING;
            case "REVIEW" -> CommissionDeliverableAccessPort.Slot.EXT_REVIEW;
            case "CONFIRMATION" -> CommissionDeliverableAccessPort.Slot.EXT_CONFIRM;
            default -> throw invalid("移交文件槽位无效");
        };
        var ref=access.requireReadable(post,commissionId,version,kind);
        var ticket=assets.createCommissionDownloadUrl(new AssetAccessPort.Actor(post.orgId(),accounts.requireServiceAccount(post.orgId()),post.userId(),post.postId()),commissionId,version,kind);
        commands.record(post,"fr-external-handover-read",ref.assetId(),IdWorker.getId(),"external-handover-download",
                Map.of("commissionId",commissionId,"version",version,"slot",slot,"assetId",ref.assetId()),"handover-download:"+UUID.randomUUID());
        return ticket;
    }

    private ExternalHandoverAccess.Subject lock(PostSnapshot post,long id,String key,boolean write) {
        var subject=write?access.writable(post,id):access.readable(post,id);
        String normalized=commands.requireIdempotencyKey(key);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),normalized));
        customers.lockById(subject.customer().getId());
        jdbc.queryForList("SELECT id FROM fr_commission WHERE id=? AND company_id=? FOR UPDATE",Long.class,id,post.orgId());
        return write?access.writable(post,id):access.readable(post,id);
    }
    private boolean replay(PostSnapshot post,String key,Mutation input) {
        var result=commands.replayed(post,key,input);
        if (result.isEmpty()) return false;
        if (result.get().getAggregateId()!=input.commissionId() || !input.action().equals(result.get().getCommand())) throw exception(IDEMPOTENCY_KEY_REUSED);
        return true;
    }
    private void updateCommission(CommissionDO row,String status,int handoverVersion) {
        if (jdbc.update("UPDATE fr_commission SET status=?,handover_version=?,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=? AND company_id=? AND revision=?",
                status,handoverVersion,row.getId(),row.getCompanyId(),row.getRevision())!=1) throw exception(STATE_VERSION_CONFLICT);
    }
    private void requireManager(PostSnapshot actor,long target) {
        boolean active=directory.usersByIds(List.of(target)).stream().anyMatch(u -> u.userId()==target && u.active());
        boolean engineer=List.of(FranchiseRoles.ENGINEER,FranchiseRoles.PROJECT_MANAGER).stream()
                .flatMap(role -> staff.listActivePostsInOrg(actor.orgId(),role).stream()).anyMatch(p -> p.getUserId()==target);
        if (target==actor.userId() || !active || !engineer) throw invalid("请选择同公司在岗且非本人的工程接收人");
    }
    private void requireEvidence(CommissionHandoverDO packet) {
        var query=evidence.getIfAvailable(); if (query==null) throw new ServiceException(503,"资料验收服务暂不可用");
        Set<Long> ids=new LinkedHashSet<>(); files(packet).forEach(f -> ids.add(Long.parseLong(f.assetId())));
        var owners=query.acceptedOwners(ExternalCommissionService.tenant(),ids,Set.of("BUSINESS_DOCUMENT"));
        if (ids.stream().anyMatch(id -> !Objects.equals(owners.get(id),packet.getCompanyId()))) throw invalid("移交资料未验收或已失效，请退回业务补充");
    }
    private void requireCurrentContract(PostSnapshot post,long customerId,ContractVersion expected) {
        var current=contracts.findPaymentPlan(post,customerId);
        var actual=current==null?new ContractVersion(null,null):new ContractVersion(Long.toString(current.contractId()),current.version());
        if (current==null || !actual.equals(expected) || current.stages().isEmpty() || current.totalCents()<=0
                || current.stages().stream().mapToLong(SaleContractQueryPort.PaymentStage::amountCents).sum()!=current.totalCents()
                || current.stages().stream().anyMatch(s -> s.amountCents()<0 || s.amountCents()>0
                    && !SaleContractQueryPort.hasAgreedDueCondition(s.trigger(),s.nodeIndex(),s.dueDate()))) {
            throw conflict("本包的施工合同或付款计划已变化，请退回业务重新移交");
        }
    }
    private static boolean validConstructionTerms(CustomerPaymentPlanDraftPort.Snapshot plan) {
        return plan!=null && "CONTRACT".equals(plan.source()) && plan.contractId()!=null && plan.contractPlanVersion()!=null
                && plan.contractPlanVersion()>0 && plan.stages()!=null && !plan.stages().isEmpty() && plan.totalCents()>0
                && plan.stages().stream().mapToLong(CustomerPaymentPlanDraftPort.Stage::amountCents).sum()==plan.totalCents()
                && plan.stages().stream().allMatch(s -> s.amountCents()>=0 && (s.amountCents()==0
                    || SaleContractQueryPort.hasAgreedDueCondition(s.trigger(),s.nodeIndex(),s.dueDate())));
    }
    private CustomerPaymentPlanDraftPort.Snapshot terms(PostSnapshot post,long customerId,CustomerPaymentPlanDraftPort.Snapshot proposal) {
        // Stabilize the signed version, its stage amounts and net receipts for this snapshot.
        jdbc.queryForList("SELECT id FROM fr_sale_contract WHERE company_id=? AND customer_id=? AND NOT deleted AND status IN ('WAIT_SIGN','SIGNED') FOR UPDATE",
                Long.class,post.orgId(),customerId);
        var contract=contracts.findPaymentPlan(post,customerId);
        if (contract==null) return proposal!=null && "DRAFT".equals(proposal.source())?proposal:null;
        var stages=contract.stages().stream().map(stage -> new CustomerPaymentPlanDraftPort.Stage(stage.label(),
                stage.amountCents(),stage.ratioBps(),stage.dueDate(),stage.trigger(),stage.nodeIndex(),stage.sequence(),
                stageAccounts.stageCollectedCents(post,contract.contractId(),stage.key()))).toList();
        return new CustomerPaymentPlanDraftPort.Snapshot(customerId,post.orgId(),"CONTRACT",contract.revision(),
                contract.totalCents(),contract.contractId(),contract.version(),stages);
    }
    private CustomerPaymentPlanDraftPort.Snapshot storedPlan(CommissionHandoverDO row) {
        String plan=jdbc.queryForObject("SELECT payment_plan_snapshot::text FROM fr_commission_handover WHERE id=? AND company_id=?",String.class,row.getId(),row.getCompanyId());
        return plan==null?null:JsonUtils.parseObject(plan,CustomerPaymentPlanDraftPort.Snapshot.class);
    }
    private Packet packet(CommissionHandoverDO row,String name) {
        return new Packet(row.getVersion(),row.getExternalResultVersion(),row.getStatus(),row.getDrawingVersion(),row.getConfirmationDate(),
                row.getNote(),row.getManagerUserId().toString(),name,files(row),storedPlan(row),
                row.getSubmittedByUserId().toString(),row.getCreateTime(),row.getDecisionNote(),row.getDecidedByUserId()==null?null:row.getDecidedByUserId().toString(),row.getDecidedAt());
    }
    private static List<File> files(CommissionHandoverDO row) {
        return List.of(new File("RESULT",row.getResultFile(),"外部设计成果"),new File("DRAWING",row.getDrawingFile(),"外部施工图"),
                new File("REVIEW",row.getReviewFile(),"专业审核依据"),new File("CONFIRMATION",row.getConfirmationAssetId().toString(),"客户确认凭据"));
    }
    private static Submit normalize(Submit value) {
        if (value==null || value.revision()==null || value.resultVersion()<1 || !value.confirmed()) throw invalid("请核对当前成果并确认提交移交");
        if (value.confirmationDate()==null || value.confirmationDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw invalid("客户确认日期须为已发生的日期");
        return new Submit(value.revision(),value.resultVersion(),id(value.confirmationAssetId()),value.confirmationDate(),
                text(value.note(),1000,true,"交底说明"),id(value.managerUserId()),true,value.checkedContract());
    }
    private static String id(String value) {
        try { if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.toString(Long.parseLong(value)); }
        catch (NumberFormatException invalid) { throw invalid("资料或接收人编号无效"); }
    }
    private static void revision(Long expected,long actual) { if (expected==null || expected<0) throw invalid("版本无效"); if (expected!=actual) throw exception(STATE_VERSION_CONFLICT); }
    private static String text(String value,int max,boolean required,String label) { String clean=value==null?"":value.trim(); if (clean.length()>max || required&&clean.isBlank()) throw invalid(label+"必填且不能超过"+max+"字"); return clean; }
    private static ServiceException invalid(String message) { return new ServiceException(400,message); }
    private static ServiceException conflict(String message) { return new ServiceException(409,message); }
}
