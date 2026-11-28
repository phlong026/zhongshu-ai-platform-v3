package cn.iocoder.yudao.module.franchise.sales.controller.admin;

import cn.iocoder.yudao.module.franchise.sales.dal.*;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.sales.SaleContractService;
import cn.iocoder.yudao.module.franchise.sales.SaleDeliveryService;
import cn.iocoder.yudao.module.franchise.sales.SaleQuoteService;
import cn.iocoder.yudao.module.franchise.sales.SaleRequirementService;
import cn.iocoder.yudao.module.franchise.sales.SalesAccess;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleContractDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleDeliveryDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleQuoteDO;
import cn.iocoder.yudao.module.franchise.sales.dal.SaleRequirementDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 业务交易流（B2 板块 B，R1-R14）：全部 /admin-api/franchise/sales/**。
 * query=查看；write=办理（需求/方案升级/报价/合同/交付）；collect=回款。
 */
@Tag(name = "加盟 - 业务交易流")
@RestController
@RequestMapping("/franchise/sales")
@Validated
public class SalesController {

    private final SaleRequirementService requirements;
    private final SaleQuoteService quotes;
    private final SaleContractService contracts;
    private final SaleDeliveryService deliveries;
    private final SalesAccess access;
    private final cn.iocoder.yudao.module.franchise.api.CustomerSchemePort customerSchemes;
    private final CurrentPost currentPost;
    private final cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageMapper stages;

    public SalesController(SaleRequirementService requirements, SaleQuoteService quotes,
                           SaleContractService contracts, SaleDeliveryService deliveries,
                           SalesAccess access, cn.iocoder.yudao.module.franchise.api.CustomerSchemePort customerSchemes,
                           CurrentPost currentPost,
                           cn.iocoder.yudao.module.franchise.sales.dal.SalePaymentStageMapper stages) {
        this.requirements = requirements;
        this.quotes = quotes;
        this.contracts = contracts;
        this.deliveries = deliveries;
        this.access = access;
        this.customerSchemes = customerSchemes;
        this.currentPost = currentPost;
        this.stages = stages;
    }

    private PostSnapshot post() {
        return currentPost.require();
    }

    // ========== 需求（R1/R2，write） ==========

    public record RequirementReq(Long customerId, BigDecimal width, BigDecimal depth, Integer floors,
                                 String style, String prompt, String note) {
    }

    @PostMapping("/requirements")
    @Operation(summary = "需求登记（一客户多版本，只追加）")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> createRequirement(@RequestBody RequirementReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        var created = requirements.create(post(), new SaleRequirementService.RequirementCommand(
                req.customerId(), req.width(), req.depth(), req.floors(), req.style(), req.prompt(), req.note()), idem);
        return success(Map.of("requirementId", created.getId(), "version", created.getVersion()));
    }

    @GetMapping("/requirements")
    @Operation(summary = "需求列表（版本倒序）")
    @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<List<SaleRequirementDO>> requirements(@RequestParam("customerId") long customerId) {
        return success(requirements.listByCustomer(post(), customerId));
    }

    // ========== 报价（R4-R6，write/query） ==========

    public record QuoteReq(Long customerId, Long schemeId, Map<String, Object> items,
                           String priceVersion, String address,
                           BigDecimal width, BigDecimal depth, Integer floors, BigDecimal area) {
    }

    @PostMapping("/quotes")
    @Operation(summary = "创建报价（服务端计算金额，无审批）")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> createQuote(@RequestBody QuoteReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        var created = quotes.create(post(), new SaleQuoteService.QuoteCommand(
                req.customerId(), req.schemeId(), req.items(), req.priceVersion(), req.address(),
                req.width(), req.depth(), req.floors(), req.area()), idem);
        return success(Map.of("quoteId", created.getId(), "totalCents", created.getTotalCents(),
                "budgetSchemeId", created.getBudgetSchemeId(), "budgetVersion", created.getBudgetVersion(),
                "items", cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(created.getItems(), Map.class)));
    }

    @GetMapping("/quotes")
    @Operation(summary = "报价列表")
    @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<List<SaleQuoteDO>> quotes(@RequestParam("customerId") long customerId) {
        return success(quotes.listByCustomer(post(), customerId));
    }

    @GetMapping("/quotes/{quoteId}/export-sheet")
    @Operation(summary = "报价导出规范票据 JSON（冻结快照）")
    @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<Map<String, Object>> exportSheet(@PathVariable("quoteId") long quoteId) {
        return success(quotes.exportSheet(post(), quoteId));
    }

    // ========== 合同（R7-R10，write） ==========

    /** M06-T04：三期金额（签约后必填>0；基础完成后/封顶后≥0 可 0=未约定；Σ=totalCents） */
    public record ContractReq(Long customerId, Long totalCents, Long signAfterCents,
                              Long baseDoneCents, Long capDoneCents,
                              LocalDate signedDate, Long assetId, Long requirementId,
                              List<cn.iocoder.yudao.module.franchise.sales.PaymentPlanRules.Input> paymentStages) {
        public ContractReq(Long customerId, Long totalCents, Long signAfterCents, Long baseDoneCents,
                           Long capDoneCents, LocalDate signedDate, Long assetId, Long requirementId) {
            this(customerId,totalCents,signAfterCents,baseDoneCents,capDoneCents,signedDate,assetId,requirementId,null);
        }
    }

    @PostMapping("/contracts")
    @Operation(summary = "创建待签合同及自定义付款计划")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> createContract(@RequestBody ContractReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        if (req.totalCents() == null || req.customerId() == null
                || (req.paymentStages() == null && (req.signAfterCents() == null || req.baseDoneCents() == null || req.capDoneCents() == null))) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400,
                    "请填写客户、合同总额和付款节点"));
        }
        var created = contracts.create(post(), new SaleContractService.ContractCommand(
                req.customerId(), req.totalCents(), req.signAfterCents()==null?0:req.signAfterCents(),
                req.baseDoneCents()==null?0:req.baseDoneCents(), req.capDoneCents()==null?0:req.capDoneCents(),
                req.signedDate(), req.assetId(), req.requirementId(),req.paymentStages()), idem);
        return success(Map.of("contractId", created.getId(), "status", created.getStatus(),
                "paymentPlanVersion",created.getPaymentPlanVersion(),
                "stages", contracts.stageSummary(post(), created.getId())));
    }

    @GetMapping("/contracts/{contractId}/payment-plan")
    @PreAuthorize("@post.has('franchise:sale:query') or @post.has('franchise:fin:query')")
    public CommonResult<?> paymentPlanDetail(@PathVariable long contractId) {
        return success(contracts.paymentPlanDetail(post(),contractId));
    }

    @PostMapping("/contracts/{contractId}/payment-plan")
    @Operation(summary = "签约前核对付款计划，签署施工合同后锁定")
    @PreAuthorize("@post.has('franchise:sale:write') or @post.has('franchise:fin:register')")
    public CommonResult<?> paymentPlan(@PathVariable long contractId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody SaleContractService.PaymentPlanCommand command) {
        var contract=contracts.replacePaymentPlan(post(),contractId,command,key);
        return success(Map.of("contract",contract,"stages",contracts.stageSummary(post(),contractId)));
    }

    @GetMapping("/contracts/{contractId}/payment-plan/versions")
    @PreAuthorize("@post.has('franchise:sale:query') or @post.has('franchise:fin:query')")
    public CommonResult<?> paymentPlanHistory(@PathVariable long contractId) {
        return success(contracts.paymentPlanHistory(post(),contractId));
    }

    @GetMapping("/contracts")
    @Operation(summary = "合同列表（响应附 stages 回款计划）")
    @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<List<Map<String, Object>>> contractsList(@RequestParam("customerId") long customerId) {
        PostSnapshot post = post();
        access.requireOrgCustomer(post, customerId);
        // M06-T02：响应附三期统一视图（各期约定额/累计分笔/余额/状态），与 create/stage-summary 同契约
        List<Map<String, Object>> rows = contracts.listByCustomer(post, customerId).stream()
                .map(c -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                    row.put("contract", c);
                    row.put("stages", contracts.stageSummary(post, c.getId()));
                    row.put("declarations", contracts.contractCollectionDeclarations(post, c.getId()));
                    return row;
                })
                .toList();
        return success(rows);
    }

    public record ContractActionReq(LocalDate signedDate, Long assetId, String reason, Long revision) {
    }

    @PostMapping("/contracts/{contractId}/sign")
    @Operation(summary = "签订合同")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> sign(@PathVariable("contractId") long contractId,
            @RequestBody ContractActionReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        var contract = contracts.sign(post(), contractId,
                new SaleContractService.SignCommand(req.signedDate(), req.assetId()), req.revision(), idem);
        return success(contractEvidenceResult(contract));
    }

    @PostMapping("/contracts/{contractId}/evidence")
    @Operation(summary = "补录或更正已签合同凭证，保留变更原因及前后版本")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> supplementEvidence(@PathVariable long contractId,
            @RequestBody SaleContractService.EvidenceCommand req, @RequestHeader("Idempotency-Key") String key) {
        return success(contractEvidenceResult(contracts.supplementEvidence(post(), contractId, req, key)));
    }

    @PostMapping("/contracts/{contractId}/assets/{assetId}/download-tickets")
    @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<cn.iocoder.yudao.module.franchise.api.AssetAccessPort.DownloadUrlView> contractDownload(
            @PathVariable long contractId, @PathVariable long assetId) {
        return success(contracts.evidenceDownload(post(), contractId, assetId));
    }

    private static Map<String,Object> contractEvidenceResult(SaleContractDO contract) {
        Map<String,Object> result = new java.util.LinkedHashMap<>();
        result.put("contractId", contract.getId().toString()); result.put("status", contract.getStatus());
        result.put("revision", contract.getRevision()); result.put("signedDate", contract.getSignedDate());
        result.put("assetId", contract.getAssetId() == null ? null : contract.getAssetId().toString());
        return result;
    }

    @PostMapping("/contracts/{contractId}/void")
    @Operation(summary = "作废合同（PENDING 计划软删，PAID 不动）")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> voidContract(@PathVariable("contractId") long contractId,
            @RequestBody ContractActionReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        var contract = contracts.void_(post(), contractId, req.reason(), req.revision(), idem);
        return success(Map.of("contractId", contract.getId(), "status", contract.getStatus()));
    }

    @GetMapping("/financial-stages") @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> financialStages(){return success(contracts.financialStages(post()));}
    @PostMapping("/stages/{stage}/allocate-receipt/{receipt}") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> allocate(@PathVariable long stage,@PathVariable long receipt,@RequestHeader("Idempotency-Key") String key){contracts.allocateReceipt(post(),stage,receipt,key);return success(true);}
    @PostMapping("/contracts/{id}/change-amounts") @PreAuthorize("@post.has('franchise:sale:write') or @post.has('franchise:fin:register')")
    public CommonResult<?> changeAmounts(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody SaleContractService.ContractChange cmd){contracts.changeAmounts(post(),id,cmd,key);return success(true);}

    @GetMapping("/collection-declarations")
    @PreAuthorize("@post.has('franchise:fin:query')")
    public CommonResult<?> declarations(){return success(contracts.collectionDeclarations(post()));}
    public record DeclarationReturn(String reason){}
    @PostMapping("/collection-declarations/{id}/return") @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> returnDeclaration(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody DeclarationReturn cmd){contracts.returnDeclaration(post(),id,cmd.reason(),key);return success(true);}
    @PostMapping("/collection-declarations/{id}/confirm")
    @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<?> confirmDeclaration(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) SaleContractService.DeclarationConfirmation confirmation){
        return success(contracts.confirmDeclaration(post(),id,confirmation,key));
    }

    // ========== 回款（R11，collect） ==========

    /** M06-T04：分笔核销（amountCents 必填>0，≤期剩余可收额） */
    public record CollectReq(Long amountCents, String method, String transactionNo, LocalDate receivedDate,Long voucherFileId,String accountName) {
        public CollectReq(Long amountCents,String method,String transactionNo,LocalDate receivedDate){this(amountCents,method,transactionNo,receivedDate,null,null);}
    }

    @PostMapping("/stages/{stageId}/collect")
    @Operation(summary = "办理回款（写通 B1 账簿；幂等三层）")
    @PreAuthorize("@post.has('franchise:sale:collect')")
    public CommonResult<Map<String, Object>> collect(@PathVariable("stageId") long stageId,
            @RequestBody CollectReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        if (req.amountCents() == null || req.amountCents() <= 0) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, "amountCents 必填且大于 0"));
        }
        return success(contracts.collect(post(), stageId, new SaleContractService.CollectCommand(
                req.amountCents(), req.method(), req.transactionNo(), req.receivedDate(),req.voucherFileId(),req.accountName()), idem));
    }

    @GetMapping("/stages/{stageId}/collect/registration-result")
    @PreAuthorize("@post.has('franchise:fin:register')")
    public CommonResult<Map<String,String>> collectionRegistrationResult(@PathVariable("stageId") long stageId,
            @RequestParam("key") String key) {
        return success(contracts.collectionRegistrationResult(post(),stageId,key));
    }

    @GetMapping("/contracts/{contractId}/stage-summary")
    @Operation(summary = "三期收付统一视图（M06-T04 契约冻结：各期约定额/累计分笔/余额）")
    @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<List<Map<String, Object>>> stageSummary(@PathVariable("contractId") long contractId) {
        return success(contracts.stageSummary(post(), contractId));
    }

    // ========== 方案升级（R3，write） ==========

    @PostMapping("/schemes/{schemeId}/promote")
    @Operation(summary = "方案升级 reference→formal（crm 侧幂等，指纹={schemeId,targetStage}）")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> promoteScheme(@PathVariable("schemeId") long schemeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        var snapshot = customerSchemes.promoteToFormal(post(), schemeId, idem);
        return success(Map.of("schemeId", snapshot.schemeId(), "stage", snapshot.stage()));
    }

    // ========== 交付验收（R12-R14，write/query） ==========

    public record DeliveryReq(Long contractId, Long requirementId) {
    }

    @PostMapping("/deliveries")
    @Operation(summary = "旧交付创建已退役，仅重放原成功请求")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> createDelivery(@RequestBody DeliveryReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        if (req.contractId() == null) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, "contractId 必填"));
        }
        var created = deliveries.create(post(), new SaleDeliveryService.DeliveryCommand(
                req.contractId(), req.requirementId()), idem);
        return success(Map.of("deliveryId", created.getId()));
    }

    public record DeliveryAdvanceReq(String node) {
    }

    @PostMapping("/deliveries/{deliveryId}/advance")
    @Operation(summary = "旧交付推进已退役，仅重放原成功请求")
    @PreAuthorize("@post.has('franchise:sale:write')")
    public CommonResult<Map<String, Object>> advanceDelivery(@PathVariable("deliveryId") long deliveryId,
            @RequestBody DeliveryAdvanceReq req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        requireIdem(idem);
        var delivery = deliveries.advance(post(), deliveryId, req.node(), idem);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("deliveryId", delivery.getId());
        result.put("designDeliveredAt", delivery.getDesignDeliveredAt());
        result.put("designConfirmedAt", delivery.getDesignConfirmedAt());
        result.put("constructionDeliveredAt", delivery.getConstructionDeliveredAt());
        result.put("constructionAcceptedAt", delivery.getConstructionAcceptedAt());
        return success(result);  // Map.of 不允许 null 值（未推进节点为 null）
    }

    @GetMapping("/deliveries")
    @Operation(summary = "历史交付查询（按合同，只读）")
    @PreAuthorize("@post.has('franchise:sale:query')")
    public CommonResult<SaleDeliveryDO> delivery(@RequestParam("contractId") long contractId) {
        return success(deliveries.requireOfOrg(post(), contractId));
    }

    private static void requireIdem(String idem) {
        if (idem == null || idem.isBlank()) {
            throw exception(new cn.iocoder.yudao.framework.common.exception.ErrorCode(400, "请提供 Idempotency-Key"));
        }
        cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.normalize(idem);
    }
}
