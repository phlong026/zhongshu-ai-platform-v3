package cn.iocoder.yudao.module.franchise.commission.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.commission.CommissionService;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.CompleteCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.Detail;
import cn.iocoder.yudao.module.franchise.commission.controller.admin.vo.CommissionRespVos.Commission;
import cn.iocoder.yudao.module.franchise.commission.controller.admin.vo.CommissionRespVos.CommissionDetail;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.DispatchCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeeDecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeedbackCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.SubmitCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.DecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.PacketCommand;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.COMMISSION_STATE_INVALID;
import static cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController.IDEMPOTENCY_HEADER;

/**
 * 设计委托：提交/重提、费用审批、设计完成、成果下发、修改意见、收图移交与工程接收。
 * 写命令可带 Idempotency-Key；详情返回 allowedActions 与显示状态。
 */
@Tag(name = "加盟协同 - 设计委托")
@RestController
@RequestMapping("/franchise")
public class CommissionController {

    private final CommissionService commissionService;
    private final HandoverService handoverService;
    private final CurrentPost currentPost;

    public CommissionController(CommissionService commissionService, HandoverService handoverService,
                                CurrentPost currentPost) {
        this.commissionService = commissionService;
        this.handoverService = handoverService;
        this.currentPost = currentPost;
    }

    public record SubmitReq(String text, String scope, String attachment, java.math.BigDecimal total,
                            java.math.BigDecimal first, String recipient, Integer budgetVersion,
                            Integer planVersion, Integer facadeVersion, boolean confirmed,
                            String mode,Integer feeRuleVersion,Long totalCents,Long firstCents,Long attachmentAssetId,java.util.Map<String,Object> designBrief,String paymentMode) {
    }

    public record SimulatedPayReq(String channel,boolean confirmed) { }
    @PostMapping("/commissions/{commissionId}/simulated-payment")
    public CommonResult<CommissionDetail> simulatePay(@PathVariable long commissionId,@RequestBody SimulatedPayReq req,@RequestHeader("Idempotency-Key") String key){
        return success(CommissionDetail.of(commissionService.simulatePay(currentPost.require(),commissionId,new CommissionService.SimulatePayCommand(req.channel(),req.confirmed()),key)));
    }
    @GetMapping("/commissions/{commissionId}/simulated-payment-receipt")
    public CommonResult<cn.iocoder.yudao.module.franchise.commission.CommissionSimulatedPayments.Order> simulatedReceipt(@PathVariable long commissionId){return success(commissionService.paymentReceipt(currentPost.require(),commissionId));}
    @PostMapping("/commissions/{id}/historical-settlement")
    @Operation(summary = "发起历史模拟委托的正式财务结算")
    @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<Boolean> prepareHistoricalSettlement(@PathVariable long id,
            @RequestBody CommissionService.HistoricalSettlementCommand command,
            @RequestHeader(IDEMPOTENCY_HEADER) String key) {
        commissionService.prepareHistoricalSettlement(currentPost.require(), id, command, key);
        return success(true);
    }

    public record FeeDecisionReq(boolean approve, String note) {
    }

    public record CompleteReq(String note, boolean confirmed) {
    }

    public record DispatchReq(String note,String recipient,String drawingVersion,Long resultAssetId,
                              Long drawingAssetId,Long reviewAssetId,boolean confirmed) {
    }

    public record FeedbackReq(String note) {
    }

    public record PacketReq(int dispatchSeq,Long confirmationAssetId,LocalDate confirmationDate,
                            String note, long managerUserId, boolean confirmed) {
    }

    public record DecisionReq(String reason, boolean confirmed) {
    }

    public record BudgetView(int version,java.math.BigDecimal width,java.math.BigDecimal depth,Integer floors,
                             java.math.BigDecimal area,String payload) { }
    public record ContextResp(long customerId,Long commissionId,BudgetView latestBudget,
                              CommissionService.FeeOffer feeOffer,CommissionDetail commission,java.util.List<String> allowedActions,String customerName,String city,boolean canPrepareBudget,java.util.Map<String,Object> customerInfo,java.util.Map<String,String> schemeBasis) { }
    @GetMapping("/customers/{customerId}/commission-context")
    public CommonResult<ContextResp> context(@PathVariable long customerId) {
        var c=commissionService.context(currentPost.require(),customerId);
        var budget=c.latestBudget();
        var view=budget==null?null:new BudgetView(budget.getVersion(),budget.getWidth(),budget.getDepth(),budget.getFloors(),budget.getArea(),budget.getPayload());
        return success(new ContextResp(c.customerId(),c.commissionId(),view,c.feeOffer(),
                c.commission()==null?null:CommissionDetail.of(c.commission()),c.allowedActions(),c.customerName(),c.city(),c.canPrepareBudget(),c.customerInfo(),c.schemeBasis()));
    }

    @PostMapping("/customers/{customerId}/commission")
    @Operation(summary = "旧设计委托提交回执兼容；新委托请使用外部登记")
    @PreAuthorize("@post.has('franchise:commission:submit')")
    public CommonResult<CommissionDetail> submit(@PathVariable long customerId, @Valid @RequestBody SubmitReq req,
                                       @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(CommissionDetail.of(commissionService.submit(currentPost.require(), customerId,
                new SubmitCommand(req.text(), req.scope(), req.attachment(), req.totalCents()==null?req.total():java.math.BigDecimal.valueOf(req.totalCents(),2),
                        req.firstCents()==null?req.first():java.math.BigDecimal.valueOf(req.firstCents(),2), req.recipient(),
                        req.budgetVersion(), req.planVersion(), req.facadeVersion(), req.confirmed(),req.mode(),req.feeRuleVersion(),req.attachmentAssetId(),req.designBrief(),req.paymentMode()), key)));
    }

    public record CommissionSummary(Commission commission,long commissionId,String displayStatus,java.util.List<String> allowedActions) { }
    @GetMapping("/commissions")
    @Operation(summary = "委托列表：业务按客户经办范围，老板全店，总部设计行政按设计组织，接收人含本人")
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<PageResult<CommissionSummary>> page(@Valid PageParam page,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String statuses) {
        var post=currentPost.require();
        var result=commissionService.page(post,page,keyword,statuses);
        return success(new PageResult<>(result.getList().stream().map(c->new CommissionSummary(Commission.of(c),c.getId(),
                commissionService.displayStatus(c),commissionService.allowedActions(post,c))).toList(),result.getTotal()));
    }

    @GetMapping("/commissions/workbench-counts")
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<java.util.Map<String,Long>> workbenchCounts() {
        return success(commissionService.workbenchCounts(currentPost.require()));
    }

    @GetMapping("/commissions/workbench-recent")
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<java.util.List<CommissionService.RecentHandling>> workbenchRecent() {
        return success(commissionService.workbenchRecent(currentPost.require()));
    }

    @GetMapping("/commissions/{id}")
    @Operation(summary = "委托详情（含提交版本、设计记录、移交版本、费用审批、allowedActions 与显示状态）")
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<CommissionDetail> get(@PathVariable long id) {
        return success(CommissionDetail.of(commissionService.get(currentPost.require(), id)));
    }
    @GetMapping("/commissions/{id}/handover-receivers")
    public CommonResult<java.util.List<HandoverService.Receiver>> receivers(@PathVariable long id) {
        return success(handoverService.receivers(currentPost.require(),id));
    }

    @PostMapping("/commissions/{id}/fee-decision")
    @Operation(summary = "旧费用审批回执兼容；不再发起内部设计费用")
    @PreAuthorize("@post.has('franchise:commission:fee-decide')")
    public CommonResult<CommissionDetail> feeDecide(@PathVariable long id, @Valid @RequestBody FeeDecisionReq req,
                                          @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(CommissionDetail.of(commissionService.feeDecide(currentPost.require(), id,
                new FeeDecisionCommand(req.approve(), req.note()), key)));
    }

    @PostMapping("/commissions/{id}/commands/{action}")
    @Operation(summary = "办理原移交及读取旧命令回执", description = "原移交：packet-submit / packet-return / packet-receive；设计命令仅保留已成功回执")
    @PreAuthorize("@post.has('franchise:commission:submit') or @post.has('franchise:commission:design') "
            + "or @post.has('franchise:commission:receive')")
    public CommonResult<CommissionDetail> command(@PathVariable long id, @PathVariable String action,
                                        @RequestBody java.util.Map<String, Object> body,
                                        @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        var post = currentPost.require();
        Detail result = switch (action) {
            case "design-complete" -> commissionService.complete(post, id,
                    new CompleteCommand(text(body), bool(body, "confirmed")), key);
            case "design-dispatch" -> commissionService.dispatch(post, id,
                    new DispatchCommand(text(body), str(body, "recipient"), bool(body, "confirmed"),str(body,"drawingVersion"),
                            number(body,"resultAssetId"),number(body,"drawingAssetId"),number(body,"reviewAssetId")), key);
            case "commission-feedback" -> commissionService.feedback(post, id, new FeedbackCommand(text(body)), key);
            case "packet-submit" -> handoverService.submit(post, id, packet(body), key);
            case "packet-return" -> handoverService.returnPacket(post, id,
                    new DecisionCommand(str(body, "reason"), false), key);
            case "packet-receive" -> handoverService.receive(post, id,
                    new DecisionCommand(null, bool(body, "confirmed"), bool(body, "confirmManagerTransfer")), key);
            default -> throw exception(COMMISSION_STATE_INVALID);
        };
        return success(CommissionDetail.of(result));
    }

    private static String text(java.util.Map<String, Object> body) {
        return str(body, "note");
    }

    private static String str(java.util.Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(java.util.Map<String, Object> body, String key) {
        return Boolean.TRUE.equals(body.get(key));
    }
    private static Long number(java.util.Map<String,Object> body,String key) {
        Object value=body.get(key);return value==null?null:Long.valueOf(String.valueOf(value));
    }

    private static PacketCommand packet(java.util.Map<String, Object> body) {
        Object date = body.get("confirmationDate");
        return new PacketCommand(
                body.get("dispatchSeq") == null ? 0 : Integer.parseInt(String.valueOf(body.get("dispatchSeq"))),
                str(body, "resultFile"), str(body, "drawingFile"), str(body, "reviewFile"),
                str(body, "confirmationFile"), str(body, "drawingVersion"),
                date == null ? null : LocalDate.parse(String.valueOf(date)),
                str(body, "note"),
                body.get("managerUserId") == null ? 0 : Long.parseLong(String.valueOf(body.get("managerUserId"))),
                bool(body, "confirmed"),number(body,"confirmationAssetId"));
    }

}
