package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.franchise.engineering.content.EngFollowupService;
import cn.iocoder.yudao.module.franchise.engineering.content.EngMaterialService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngProjectController.*;

/** 材料链：计划（计划单分组）→ 调整 → terms（独立码）→ 到货/质检/补发/更正 → 去向 → 催办 → 汇总。 */
@Tag(name = "加盟 - 工程全周期（材料）")
@RestController
@RequestMapping("/franchise/engineering")
@Validated
public class EngMaterialController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private EngMaterialService materials;
    @Resource
    private EngFollowupService followups;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/{id}/materials")
    @Operation(summary = "材料计划登记（每次提交一个计划单分组，返回 materialPlanId）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> submitPlan(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        List<EngMaterialService.PlanLine> lines = planLines(body.get("lines"));
        return success(materials.submitPlan(post(), id, new EngMaterialService.PlanCommand(
                longOrNull(body.get("nodeId")), localDate(body.get("due")), lines, str(body.get("note"))),
                requireIdem(idem)));
    }

    @PutMapping("/materials/{materialId}")
    @Operation(summary = "需用数量调整（0=取消；不得少于已到货；节点完成后不得加大 004）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> adjust(@PathVariable long materialId, @RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(materials.adjust(post(), materialId, new EngMaterialService.AdjustCommand(
                decimal(body.get("quantity")), str(body.get("reason"))), requireIdem(idem)));
    }

    @PostMapping("/supplies/{supplyId}/terms")
    @Operation(summary = "采购金额（terms 码；经 PayablePort 生成 finance 应付；已付款后拒改 004）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.TERMS + "')")
    public CommonResult<Map<String, Object>> terms(@PathVariable long supplyId, @RequestBody Map<String, Object> body,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(materials.terms(post(), supplyId, new EngMaterialService.TermsCommand(
                str(body.get("supplier")), decimal(body.get("unitPrice")), decimal(body.get("freight")),
                str(body.get("contact")), localDate(body.get("promiseDate")), str(body.get("basis"))),
                requireIdem(idem)));
    }

    @PostMapping("/supplies/{supplyId}/arrival")
    @Operation(summary = "到货确认（流水唯一 005；◆质检/差异自动补发/补发到货回填三种形态）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> arrival(@PathVariable long supplyId, @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        Map<String, Object> resolution = body.get("qualityResolution") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        return success(materials.arrival(post(), supplyId, new EngMaterialService.ArrivalCommand(
                longOrNull(body.get("parentReceiptId")), decimal(body.get("expected")), decimal(body.get("actual")),
                str(body.get("quality")), str(body.get("transactionNo")), longOrNull(body.get("fileAssetId")),
                localDate(body.get("occurredAt")), str(body.get("note")), resolution), requireIdem(idem)));
    }

    @PostMapping("/supplies/arrival-correction")
    @Operation(summary = "实到更正（留痕 corrections；累计与节点边界 004）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> arrivalCorrection(@RequestBody Map<String, Object> body,
                                                               @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(materials.arrivalCorrection(post(),
                longOrNull(body.get("receiptId")),
                new EngMaterialService.ArrivalCorrectionCommand(decimal(body.get("actual")), str(body.get("reason"))),
                requireIdem(idem)));
    }

    @PostMapping("/{id}/movements")
    @Operation(summary = "◆材料去向（领用耗用/报损/退回/余料结转；q≤合格到货-已分配）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> movement(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(materials.movement(post(), id, new EngMaterialService.MovementCommand(
                longOrNull(body.get("materialId")), str(body.get("kind")), decimal(body.get("quantity")),
                str(body.get("note")), longOrNull(body.get("receiverUserId"))), requireIdem(idem)));
    }

    @PostMapping("/{id}/material-followups")
    @Operation(summary = "材料催到货（material_plan_id 维度，同日同内容幂等）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<List<Map<String, Object>>> materialFollowup(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(followups.materialFollowup(post(), id, new EngFollowupService.MaterialFollowupCommand(
                longOrNull(body.get("materialPlanId")) == null ? 0 : longOrNull(body.get("materialPlanId")),
                localDate(body.get("promiseDate")), str(body.get("note"))), requireIdem(idem)));
    }

    @GetMapping("/{id}/materials/summary")
    @Operation(summary = "计划级汇总（到货+付款派生态；?materialPlanId=）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> summary(@PathVariable long id,
                                                     @RequestParam(required = false) Long materialPlanId) {
        return success(materials.summary(post(), id, materialPlanId));
    }

    private static List<EngMaterialService.PlanLine> planLines(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(x -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) x;
            return new EngMaterialService.PlanLine(str(m.get("name")), str(m.get("spec")),
                    str(m.get("unit")), decimal(m.get("quantity")));
        }).toList();
    }
}
