package cn.iocoder.yudao.module.franchise.finance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.finance.FinBankService;
import cn.iocoder.yudao.module.franchise.finance.FinFileService;
import cn.iocoder.yudao.module.franchise.finance.FinPaymentService;
import cn.iocoder.yudao.module.franchise.finance.FinPermissions;
import cn.iocoder.yudao.module.franchise.finance.FinProjectAccountService;
import cn.iocoder.yudao.module.franchise.finance.FinReceiptService;
import cn.iocoder.yudao.module.franchise.finance.FinAbnormalFundService;
import cn.iocoder.yudao.module.franchise.finance.FinReconciliationService;
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
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundDO;
import cn.iocoder.yudao.module.franchise.finance.dal.FinAbnormalFundMapper;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
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
    @Resource private cn.iocoder.yudao.module.franchise.finance.DesignFeeActionService designFeeActions;
    @Resource private cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService projectLedger;

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
    @Resource
    private FinAbnormalFundService abnormalFundService;
    @Resource
    private FinAbnormalFundMapper abnormalFundMapper;
    @Resource
    private FinReconciliationService reconciliationService;

    // ===== Web 财务核对页：只读分页 + 组合筛选（P0 批2；旧 List 端点保持兼容小程序） =====
    // 逐表日期口径按 DO 实证：receipts=receivedDate、payments=payDate、refunds=date、abnormal-funds=businessDate；
    // FinPayableDO 无业务日期/到期日字段，payables 不提供任何日期或到期日参数，也不承诺导出列。

    @GetMapping("/receipts/page")
    @Operation(summary = "实收分页（只读：期间/项目/类型/状态/keyword 组合筛选）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<PageResult<FinReceiptDO>> receiptsPage(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "receivedDateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedDateFrom,
            @RequestParam(value = "receivedDateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedDateTo,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "method", required = false) String method,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "keyword", required = false) String keyword) {
        PageParam page = pageParam(pageNo, pageSize);
        validateRange(receivedDateFrom, receivedDateTo);
        return success(receiptMapper.selectPage(page, currentPost.require().orgId(), status,
                receivedDateFrom, receivedDateTo, projectId, method, sourceType, keyword));
    }

    @GetMapping("/payments/page")
    @Operation(summary = "付款分页（只读：期间/项目/状态/keyword 组合筛选）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<PageResult<FinPaymentDO>> paymentsPage(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "payDateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payDateFrom,
            @RequestParam(value = "payDateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payDateTo,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        PageParam page = pageParam(pageNo, pageSize);
        validateRange(payDateFrom, payDateTo);
        return success(paymentMapper.selectPage(page, currentPost.require().orgId(), status,
                payDateFrom, payDateTo, projectId, keyword));
    }

    @GetMapping("/refunds/page")
    @Operation(summary = "退款分页（只读：期间/原收款单/方向/状态/keyword 组合筛选）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<PageResult<FinRefundDO>> refundsPage(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(value = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(value = "receiptId", required = false) Long receiptId,
            @RequestParam(value = "refundDirection", required = false) String refundDirection,
            @RequestParam(value = "keyword", required = false) String keyword) {
        PageParam page = pageParam(pageNo, pageSize);
        validateRange(dateFrom, dateTo);
        validateRefundDirection(refundDirection);
        return success(refundMapper.selectPage(page, currentPost.require().orgId(), status,
                dateFrom, dateTo, receiptId, refundDirection, keyword));
    }

    @GetMapping("/payables/page")
    @Operation(summary = "应付分页（只读：项目/状态/keyword 组合筛选；DO 无到期日字段，不提供日期筛选）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<PageResult<FinPayableDO>> payablesPage(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "keyword", required = false) String keyword) {
        PageParam page = pageParam(pageNo, pageSize);
        return success(payableMapper.selectPage(page, currentPost.require().orgId(), status, projectId, keyword));
    }

    @GetMapping("/abnormal-funds/page")
    @Operation(summary = "异常资金分页（只读：状态/日期待核/业务日期区间/keyword 组合筛选）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<PageResult<FinAbnormalFundDO>> abnormalFundsPage(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "datePendingOnly", defaultValue = "false") boolean datePendingOnly,
            @RequestParam(value = "businessDateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDateFrom,
            @RequestParam(value = "businessDateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDateTo,
            @RequestParam(value = "keyword", required = false) String keyword) {
        PageParam page = pageParam(pageNo, pageSize);
        validateRange(businessDateFrom, businessDateTo);
        return success(abnormalFundMapper.selectPage(page, currentPost.require().orgId(), status,
                datePendingOnly, businessDateFrom, businessDateTo, keyword));
    }

    /** 分页参数校验真实落到方法参数：1 ≤ pageNo、1 ≤ pageSize ≤ 100（需求 §三.1，非 PageParam 默认 200 上限）。 */
    private static PageParam pageParam(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST);
        }
        PageParam page = new PageParam();
        page.setPageNo(pageNo);
        page.setPageSize(pageSize);
        return page;
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST);
        }
    }

    private static void validateRefundDirection(String direction) {
        if (direction != null && !"client".equals(direction) && !"expense".equals(direction)) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST);
        }
    }

    // ===== 实收 =====

    public record ReceiptRegisterCommand(String sourceType, String sourceRef, String name, Long amountCents,
                                         LocalDate receivedDate, String method, String transactionNo,
                                         Long voucherFileId, Long projectId, Long customerId,
                                         Long designCommissionId, String designStage, Long originPaymentId) {
    }

    @PostMapping("/receipts")
    @Operation(summary = "登记实收（登记即生效；design 来源走委托五项门禁）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerReceipt(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReceiptRegisterCommand cmd) {
        PostSnapshot post = currentPost.require();
        long id = receiptService.register(post, idempotencyKey, new FinReceiptService.RegisterCommand(
                cmd.sourceType(), cmd.sourceRef(), cmd.name(), cmd.amountCents() == null ? 0 : cmd.amountCents(),
                cmd.receivedDate(), cmd.method(), cmd.transactionNo(), cmd.voucherFileId(),
                cmd.projectId(), cmd.customerId(), cmd.designCommissionId(), cmd.designStage(),cmd.originPaymentId()));
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
                                                      @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
                                                      @Valid @RequestBody RevisionCommand cmd) {
        int next = receiptService.revision(currentPost.require(), id,
                cmd.baseRevisionVersion(), cmd.newAmountCents(), cmd.note(),idempotencyKey);
        return success(Map.of("revisionVersion", next));
    }

    public record RevisionCommand(Integer baseRevisionVersion, Long newAmountCents, String note) {
    }

    @DeleteMapping("/receipts/{id}")
    @Operation(summary="作废设计核收（保留流水身份和原始审计；有有效退款先更正）")
    @PreAuthorize("@post.has('" + FinPermissions.REFUND_VOID + "')")
    public CommonResult<Boolean> voidDesignReceipt(@PathVariable("id") long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,@Valid @RequestBody VoidCommand cmd) {
        receiptService.voidDesign(currentPost.require(),id,cmd.reason(),idempotencyKey);
        return success(true);
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

    public record VoidCommand(@NotBlank String reason) {
    }

    @GetMapping("/revisions")
    @Operation(summary = "修订历史（审计留痕查询；限本组织收款，跨组织 ORG_MISMATCH）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinReceiptRevisionDO>> revisions(@RequestParam("receiptId") long receiptId) {
        PostSnapshot post = currentPost.require();
        FinReceiptDO receipt = receiptMapper.selectById(receiptId);
        // 跨组织修订历史不可读（评审基线强制清单 #4：原实现无 company 过滤）
        if (receipt == null || receipt.getCompanyId() != post.orgId()) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.ORG_MISMATCH);
        }
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
    public CommonResult<Map<String, Object>> registerPayable(@RequestHeader("Idempotency-Key") String idempotencyKey,
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
    public CommonResult<Map<String, Object>> registerPayment(@RequestHeader("Idempotency-Key") String idempotencyKey,
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
    public CommonResult<Boolean> voidPayment(@PathVariable("id") long id,@RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey, @Valid @RequestBody VoidCommand cmd) {
        paymentService.void_(currentPost.require(), id, cmd.reason(),idempotencyKey);
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
            rows = rows.stream().filter(r -> sameReceiptId(r.getReceiptId(), receiptId)).toList();
        }
        return success(rows);
    }

    static boolean sameReceiptId(Long stored, Long requested) {
        return requested == null || java.util.Objects.equals(stored, requested);
    }

    @PostMapping("/refunds")
    @Operation(summary = "登记退款（线下事实，登记即已核实；可携 replaces_id 走更正链）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerRefund(@RequestHeader("Idempotency-Key") String idempotencyKey,
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
    public CommonResult<Boolean> voidRefund(@PathVariable("id") long id,@RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey, @Valid @RequestBody VoidCommand cmd) {
        refundService.void_(currentPost.require(), id, cmd.reason(),idempotencyKey);
        return success(true);
    }

    // ===== 支出退回（M08-T08：供应商/劳务退回，冲减已付现金） =====

    @PostMapping("/expense-refunds")
    @Operation(summary = "登记支出退回（关联原付款单；金额≤该付款单剩余可退回额度）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerExpenseRefund(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExpenseRefundCommand cmd) {
        long id = refundService.registerExpense(currentPost.require(), idempotencyKey,
                new FinRefundService.ExpenseRefundCommand(cmd.paymentId(),
                        cmd.amountCents() == null ? 0 : cmd.amountCents(), cmd.reason(), cmd.date(),
                        cmd.transactionNo(), cmd.voucherFileId(), cmd.replacesId()));
        return success(Map.of("refundId", id));
    }

    public record ExpenseRefundCommand(@NotNull Long paymentId, @NotNull Long amountCents, String reason, LocalDate date,
                                       String transactionNo, Long voucherFileId, Long replacesId) {
    }

    // ===== 异常资金登记（M08-T08/R05：独立留痕不凑平，不进有效账；日期待核单列待归月） =====

    @GetMapping("/abnormal-funds")
    @Operation(summary = "异常资金列表（本公司；datePendingOnly=true 单列日期待核/待归月）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<List<FinAbnormalFundDO>> abnormalFunds(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "datePendingOnly", required = false, defaultValue = "false") boolean datePendingOnly) {
        return success(abnormalFundMapper.selectByCompany(currentPost.require().orgId(), status, datePendingOnly));
    }

    public record AbnormalFundCommand(@NotBlank String fundDirection, @NotNull Long amountCents, String counterparty,
                                      Long relatedProjectId, Long relatedCommissionId, String stageOrCategory,
                                      LocalDate businessDate, String method, String transactionNo,
                                      String reason, Long voucherFileId) {
    }

    @PostMapping("/abnormal-funds")
    @Operation(summary = "登记异常资金（原单缺失/超上限/日期凭据待核的真实资金事实，留痕不凑平）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Map<String, Object>> registerAbnormalFund(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AbnormalFundCommand cmd) {
        long id = abnormalFundService.register(currentPost.require(), idempotencyKey,
                new FinAbnormalFundService.RegisterCommand(cmd.fundDirection(),
                        cmd.amountCents() == null ? 0 : cmd.amountCents(), cmd.counterparty(),
                        cmd.relatedProjectId(), cmd.relatedCommissionId(), cmd.stageOrCategory(),
                        cmd.businessDate(), cmd.method(), cmd.transactionNo(), cmd.reason(), cmd.voucherFileId()));
        return success(Map.of("fundId", id));
    }

    public record AbnormalLinkCommand(@NotBlank String linkedType, @NotNull Long linkedId) {
    }

    @PostMapping("/abnormal-funds/{id}/link")
    @Operation(summary = "异常资金转正关联（仅标注不改有效账；CAS 防并发双关联）")
    @PreAuthorize("@post.has('" + FinPermissions.REGISTER + "')")
    public CommonResult<Boolean> linkAbnormalFund(@PathVariable("id") long id,
                                                  @Valid @RequestBody AbnormalLinkCommand cmd) {
        abnormalFundService.link(currentPost.require(), id, cmd.linkedType(),
                cmd.linkedId() == null ? 0 : cmd.linkedId());
        return success(true);
    }

    @DeleteMapping("/abnormal-funds/{id}")
    @Operation(summary = "作废异常资金（误录撤销留痕；幂等）")
    @PreAuthorize("@post.has('" + FinPermissions.REFUND_VOID + "')")
    public CommonResult<Boolean> voidAbnormalFund(@PathVariable("id") long id, @Valid @RequestBody VoidCommand cmd) {
        abnormalFundService.void_(currentPost.require(), id, cmd.reason());
        return success(true);
    }

    // ===== 对账视图（M08-T08 批次#34：毛额/退款/净额/异常四口径） =====

    @GetMapping("/reconciliation")
    @Operation(summary = "对账视图（按月四口径：毛额/退款/净额/异常资金含待归月单列）")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<FinReconciliationService.MonthReconciliation> reconciliation(
            @RequestParam("month") String month) {
        return success(reconciliationService.monthReconciliation(currentPost.require().orgId(), month));
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
    public CommonResult<Map<String, Object>> confirmBank(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @Valid @RequestBody BankConfirmCommand cmd) {
        long id = bankService.confirm(currentPost.require(), idempotencyKey, cmd.bankFlowId(), cmd.receiptId());
        return success(Map.of("confirmId", id));
    }

    public record BankConfirmCommand(String idempotencyKey, Long bankFlowId, Long receiptId) {
    }

    // ===== 项目账与设计费净额 =====

    @GetMapping("/projects")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<PageResult<cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Project>> financeProjects(
            @RequestParam(value="pageNo",defaultValue="1") int pageNo,@RequestParam(value="pageSize",defaultValue="10") int pageSize,
            @RequestParam(value="keyword",required=false) String keyword) {
        pageParam(pageNo,pageSize);
        return success(projectLedger.projects(currentPost.require().orgId(),pageNo,pageSize,keyword));
    }

    @GetMapping("/design-fees")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<PageResult<cn.iocoder.yudao.module.franchise.finance.DesignFeeActionService.Item>> designFees(
            @RequestParam(value="pageNo",defaultValue="1") int pageNo,@RequestParam(value="pageSize",defaultValue="10") int pageSize) {
        pageParam(pageNo,pageSize);
        return success(designFeeActions.page(currentPost.require().orgId(),pageNo,pageSize));
    }

    @GetMapping("/projects/{projectId}/ledger")
    @PreAuthorize("@post.has('" + FinPermissions.QUERY + "')")
    public CommonResult<cn.iocoder.yudao.module.franchise.finance.FinProjectLedgerService.Ledger> projectLedger(
            @PathVariable("projectId") long id,@RequestParam(value="pageNo",defaultValue="1") int pageNo,
            @RequestParam(value="pageSize",defaultValue="10") int pageSize) {
        pageParam(pageNo,pageSize);
        return success(projectLedger.page(currentPost.require().orgId(),id,pageNo,pageSize));
    }

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
