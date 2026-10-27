package cn.iocoder.yudao.module.franchise.commission.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.commission.CommissionService;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.CompleteCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.Detail;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.DispatchCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeeDecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeedbackCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.SubmitCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.DecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.PacketCommand;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
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
                            Integer planVersion, Integer facadeVersion, boolean confirmed) {
    }

    public record FeeDecisionReq(boolean approve, String note) {
    }

    public record CompleteReq(String note, boolean confirmed) {
    }

    public record DispatchReq(String note, String recipient, boolean confirmed) {
    }

    public record FeedbackReq(String note) {
    }

    public record PacketReq(int dispatchSeq, String resultFile, String drawingFile, String reviewFile,
                            String confirmationFile, String drawingVersion, LocalDate confirmationDate,
                            String note, long managerUserId, boolean confirmed) {
    }

    public record DecisionReq(String reason, boolean confirmed) {
    }

    @PostMapping("/customers/{customerId}/commission")
    @Operation(summary = "提交 / 重提设计委托：费用与当前有效标准一致直接进入设计，否则发起老板审批")
    @PreAuthorize("@post.has('franchise:commission:submit')")
    public CommonResult<Detail> submit(@PathVariable long customerId, @Valid @RequestBody SubmitReq req,
                                       @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(commissionService.submit(currentPost.require(), customerId, new SubmitCommand(
                req.text(), req.scope(), req.attachment(), req.total(), req.first(), req.recipient(),
                req.budgetVersion(), req.planVersion(), req.facadeVersion(), req.confirmed()), key));
    }

    @GetMapping("/commissions")
    @Operation(summary = "委托列表：业务按客户经办范围，老板全店，总部设计行政按设计组织，接收人含本人")
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<PageResult<CommissionDO>> page(@Valid PageParam page,
                                                       @RequestParam(required = false) String keyword) {
        return success(commissionService.page(currentPost.require(), page, keyword));
    }

    @GetMapping("/commissions/{id}")
    @Operation(summary = "委托详情（含提交版本、设计记录、移交版本、费用审批、allowedActions 与显示状态）")
    @PreAuthorize("@post.has('franchise:commission:query')")
    public CommonResult<Detail> get(@PathVariable long id) {
        return success(commissionService.get(currentPost.require(), id));
    }

    @PostMapping("/commissions/{id}/fee-decision")
    @Operation(summary = "老板审批委托费用（批准进入设计，退回待修改重提）")
    @PreAuthorize("@post.has('franchise:commission:fee-decide')")
    public CommonResult<Detail> feeDecide(@PathVariable long id, @Valid @RequestBody FeeDecisionReq req,
                                          @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(commissionService.feeDecide(currentPost.require(), id,
                new FeeDecisionCommand(req.approve(), req.note()), key));
    }

    @PostMapping("/commissions/{id}/commands/{action}")
    @Operation(summary = "委托命令：design-complete / design-dispatch / commission-feedback / packet-submit / packet-return / packet-receive")
    @PreAuthorize("@post.has('franchise:commission:submit') or @post.has('franchise:commission:design') "
            + "or @post.has('franchise:commission:receive')")
    public CommonResult<Detail> command(@PathVariable long id, @PathVariable String action,
                                        @RequestBody java.util.Map<String, Object> body,
                                        @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        var post = currentPost.require();
        Detail result = switch (action) {
            case "design-complete" -> commissionService.complete(post, id,
                    new CompleteCommand(text(body), bool(body, "confirmed")), key);
            case "design-dispatch" -> commissionService.dispatch(post, id,
                    new DispatchCommand(text(body), str(body, "recipient"), bool(body, "confirmed")), key);
            case "commission-feedback" -> commissionService.feedback(post, id, new FeedbackCommand(text(body)), key);
            case "packet-submit" -> handoverService.submit(post, id, packet(body), key);
            case "packet-return" -> handoverService.returnPacket(post, id,
                    new DecisionCommand(str(body, "reason"), false), key);
            case "packet-receive" -> handoverService.receive(post, id,
                    new DecisionCommand(null, bool(body, "confirmed")), key);
            default -> throw exception(COMMISSION_STATE_INVALID);
        };
        return success(result);
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

    private static PacketCommand packet(java.util.Map<String, Object> body) {
        Object date = body.get("confirmationDate");
        return new PacketCommand(
                body.get("dispatchSeq") == null ? 0 : Integer.parseInt(String.valueOf(body.get("dispatchSeq"))),
                str(body, "resultFile"), str(body, "drawingFile"), str(body, "reviewFile"),
                str(body, "confirmationFile"), str(body, "drawingVersion"),
                date == null ? null : LocalDate.parse(String.valueOf(date)),
                str(body, "note"),
                body.get("managerUserId") == null ? 0 : Long.parseLong(String.valueOf(body.get("managerUserId"))),
                bool(body, "confirmed"));
    }

}
