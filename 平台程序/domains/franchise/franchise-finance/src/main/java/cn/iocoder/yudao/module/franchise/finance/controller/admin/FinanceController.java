package cn.iocoder.yudao.module.franchise.finance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.finance.FinBankService;
import cn.iocoder.yudao.module.franchise.finance.FinFileService;
import cn.iocoder.yudao.module.franchise.finance.FinPaymentService;
import cn.iocoder.yudao.module.franchise.finance.FinPermissions;
import cn.iocoder.yudao.module.franchise.finance.FinProjectAccountService;
import cn.iocoder.yudao.module.franchise.finance.FinReceiptService;
import cn.iocoder.yudao.module.franchise.finance.FinRefundService;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankConfirmDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankFlowDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinBankFlowMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinFileDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPaymentMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinPayableMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptRevisionDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinReceiptRevisionMapper;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinRefundMapper;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 财务账簿与到账（B1）。权限逐接口挂 @post.has；组织范围在 Service 内校验。
 * 封存逻辑（verify/review/filing）不提供接口——回归断言见需求文档 AC8。
 */
@Tag(name = "加盟 - 财务账簿")
@RestController
@RequestMapping("/franchise/finance")
@Validated
public class FinanceController {

    @Resource
    private FinReceiptService receiptService;
    @Resource
    private FinRefundService refundService;
    @Resource
    private FinPaymentService paymentService;
    @Resource
    private FinBankService bankService;
    @Resource
    private FinProjectAccountService accountService;
    @Resource
    private FinFileService fileService;
    @Resource
    private CurrentPost currentPost;
    @Resource
    private FinReceiptMapper receiptMapper;
    @Resource
    private FinReceiptRevisionMapper revisionMapper;
    @Resource
    private FinPayableMapper payableMapper;
    @Resource
    private FinPaymentMapper paymentMapper;
    @Resource
    private FinRefundMapper refundMapper;
    @Resource
    private FinBankFlowMapper flowMapper;

    // ===== 实收 =====

    public record ReceiptRegisterCommand(String sourceType, String sourceRef, String name, Long amountCents,
                                         LocalDate receivedDate, String method, String transactionNo,
                                         Long voucherFileId, Long projectId, Long customerId,
                                         Long designCommissionId, String designStage) {
    }

    @PostMapping("/receipts")
    @Operation(summary = "登记实收（登记即生效；design 来源走委托五项门禁）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerReceipt(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReceiptRegisterCommand cmd) {
        PostSnapshot post = currentPost.require();
        long id = receiptService.register(post, idempotencyKey, new FinReceiptService.RegisterCommand(
                cmd.sourceType(), cmd.sourceRef(), cmd.name(), cmd.amountCents() == null ? 0 : cmd.amountCents(),
                cmd.receivedDate(), cmd.method(), cmd.transactionNo(), cmd.voucherFileId(),
                cmd.projectId(), cmd.customerId(), cmd.designCommissionId(), cmd.designStage()));
        return success(Map.of("receiptId", id));
    }

    @GetMapping("/receipts")
    @Operation(summary = "实收列表（本公司；status 可选）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinReceiptDO>> receipts(@RequestParam(value = "status", required = false) String status) {
        return success(receiptMapper.selectByCompany(currentPost.require().orgId(), status));
    }

    @PostMapping("/receipts/{id}/revision")
    @Operation(summary = "修订实收金额（财务直接改+全留痕；baseRevisionVersion 乐观锁）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> revision(@PathVariable("id") long id,
                                                      @Valid @RequestBody RevisionCommand cmd) {
        int next = receiptService.revision(currentPost.require(), id,
                cmd.baseRevisionVersion(), cmd.newAmountCents(), cmd.note());
        return success(Map.of("revisionVersion", next));
    }

    public record RevisionCommand(Integer baseRevisionVersion, Long newAmountCents, String note) {
    }

    @PostMapping("/receipts/{id}/void-pending")
    @Operation(summary = "撤销跨端预期应收（限财务负责人；服务端 roleCode 校验）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Boolean> voidPending(@PathVariable("id") long id, @Valid @RequestBody VoidCommand cmd) {
        PostSnapshot post = currentPost.require();
        if (!"finance-lead".equals(post.roleCode())) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.ORG_MISMATCH);
        }
        receiptService.voidPending(post, id, cmd.reason());
        return success(true);
    }

    public record VoidCommand(String reason) {
    }

    @GetMapping("/revisions")
    @Operation(summary = "修订历史（审计留痕查询）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinReceiptRevisionDO>> revisions(@RequestParam("receiptId") long receiptId) {
        return success(revisionMapper.selectByReceipt(receiptId));
    }

    // ===== 应付与付款 =====

    @GetMapping("/payables")
    @Operation(summary = "应付列表")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinPayableDO>> payables(@RequestParam(value = "status", required = false) String status) {
        return success(payableMapper.selectByCompany(currentPost.require().orgId(), status));
    }

    @PostMapping("/payables")
    @Operation(summary = "登记应付")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerPayable(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                             @Valid @RequestBody PaymentServicePayableCommand cmd) {
        long id = paymentService.registerPayable(currentPost.require(), idempotencyKey, new FinPaymentService.PayableCommand(
                currentPost.require().orgId(), cmd.projectId(), cmd.stage(),
                cmd.totalCents() == null ? 0 : cmd.totalCents(), cmd.recipient(), cmd.agreementRef()));
        return success(Map.of("payableId", id));
    }

    public record PaymentServicePayableCommand(Long projectId, String stage, Long totalCents,
                                               String recipient, String agreementRef) {
    }

    @GetMapping("/payments")
    @Operation(summary = "付款列表（本公司；status 可选）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinPaymentDO>> payments(@RequestParam(value = "status", required = false) String status) {
        return success(paymentMapper.selectByCompany(currentPost.require().orgId(), status));
    }

    @PostMapping("/payments")
    @Operation(summary = "付款登记（登记即已核实；I-2 累计校验）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerPayment(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                             @Valid @RequestBody PaymentCommand cmd) {
        long id = paymentService.register(currentPost.require(), idempotencyKey, new FinPaymentService.PaymentCommand(
                cmd.payableId(), cmd.amountCents() == null ? 0 : cmd.amountCents(),
                cmd.payDate(), cmd.transactionNo(), cmd.voucherFileId()));
        return success(Map.of("paymentId", id));
    }

    public record PaymentCommand(Long payableId, Long amountCents, LocalDate payDate,
                                 String transactionNo, Long voucherFileId) {
    }

    @DeleteMapping("/payments/{id}")
    @Operation(summary = "作废付款（仅 finance-lead；释放额度留痕）")
    @PreAuthorize("@post.has('" + FinPermissions.PAYMENT_VOID + "')")
    public CommonResult<Boolean> voidPayment(@PathVariable("id") long id, @Valid @RequestBody VoidCommand cmd) {
        paymentService.void_(currentPost.require(), id, cmd.reason());
        return success(true);
    }

    // ===== 退款（线下事实登记制） =====

    @GetMapping("/refunds")
    @Operation(summary = "退款列表（本公司）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinRefundDO>> refunds(@RequestParam(value = "receiptId", required = false) Long receiptId) {
        Long orgId = currentPost.require().orgId();
        List<FinRefundDO> rows = refundMapper.selectByCompany(orgId);
        if (receiptId != null) {
            rows = rows.stream().filter(r -> r.getReceiptId() == receiptId).toList();
        }
        return success(rows);
    }

    @PostMapping("/refunds")
    @Operation(summary = "登记退款（线下事实，登记即已核实；可携 replaces_id 走更正链）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerRefund(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                            @Valid @RequestBody RefundCommand cmd) {
        long id = refundService.register(currentPost.require(), idempotencyKey, new FinRefundService.RegisterCommand(
                cmd.receiptId(), cmd.amountCents(), cmd.reason(), cmd.date(),
                cmd.transactionNo(), cmd.voucherFileId(), cmd.replacesId()));
        return success(Map.of("refundId", id));
    }

    public record RefundCommand(Long receiptId, Long amountCents, String reason, LocalDate date,
                                String transactionNo, Long voucherFileId, Long replacesId) {
    }

    @DeleteMapping("/refunds/{id}")
    @Operation(summary = "作废退款（finance-lead/boss/owner；void_reason 必填）")
    @PreAuthorize("@post.has('" + FinPermissions.REFUND_VOID + "')")
    public CommonResult<Boolean> voidRefund(@PathVariable("id") long id, @Valid @RequestBody VoidCommand cmd) {
        refundService.void_(currentPost.require(), id, cmd.reason());
        return success(true);
    }

    // ===== 到账核对 =====

    @GetMapping("/bank-flows")
    @Operation(summary = "银行流水列表（待匹配在前）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinBankFlowDO>> bankFlows(@RequestParam(value = "status", required = false) String status) {
        return success(flowMapper.selectByCompanyAndStatus(currentPost.require().orgId(), status));
    }

    @PostMapping("/bank-confirms")
    @Operation(summary = "确认到账（唯一入账；金额一致门禁；失败尝试独立留痕）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> confirmBank(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                         @Valid @RequestBody BankConfirmCommand cmd) {
        long id = bankService.confirm(currentPost.require(), idempotencyKey != null ? idempotencyKey : cmd.idempotencyKey(), cmd.bankFlowId(), cmd.receiptId());
        return success(Map.of("confirmId", id));
    }

    public record BankConfirmCommand(String idempotencyKey, Long bankFlowId, Long receiptId) {
    }

    // ===== 项目账与设计费净额 =====

    @GetMapping("/project-accounts/{projectId}")
    @Operation(summary = "项目账聚合（应收/实收/退款净额/已付）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<FinProjectAccountService.ProjectAccount> projectAccount(@PathVariable("projectId") long projectId) {
        PostSnapshot post = currentPost.require();
        return success(accountService.projectAccount(post.orgId(), projectId));
    }

    @GetMapping("/design-fee-progress")
    @Operation(summary = "设计费净额（总部侧 source_type=design 实时聚合）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<FinProjectAccountService.DesignFeeProgress> designFeeProgress(@RequestParam("commissionId") long commissionId) {
        return success(accountService.designFeeProgress(currentPost.require().orgId(), commissionId));
    }

    // ===== 凭证 =====

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传财务凭证")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        long id = fileService.upload(currentPost.require(), file);
        return success(Map.of("fileId", id));
    }

    @GetMapping("/files/{id}")
    @Operation(summary = "凭证下载")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public ResponseEntity<byte[]> download(@PathVariable("id") long id) {
        PostSnapshot post = currentPost.require();
        FinFileDO file = fileService.get(post.orgId(), id);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok().header("Content-Disposition", "attachment; filename*=UTF-8''" + file.getFileName())
                    .body(java.nio.file.Files.readAllBytes(fileService.resolve(file)));
        } catch (java.io.IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

}
