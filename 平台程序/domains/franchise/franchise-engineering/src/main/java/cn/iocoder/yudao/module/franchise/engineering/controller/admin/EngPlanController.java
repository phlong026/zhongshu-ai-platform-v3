package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.franchise.engineering.content.EngPlanService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngProjectController.*;

/** 施工计划链：开工准备（约定即固化）→ 实际开工（START 门禁）→ 计划保存/顺移 → 节点完成/撤回/更正。 */
@Tag(name = "加盟 - 工程全周期（计划）")
@RestController
@RequestMapping("/franchise/engineering")
@Validated
public class EngPlanController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private EngPlanService plans;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/{id}/prepare")
    @Operation(summary = "开工准备：合同+凭据+说明+人员+三期约定固化（M07-T06：签约后/基础完成后/封顶后，与统一合同对齐）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> prepare(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(plans.prepare(post(), id, new EngPlanService.PrepareCommand(
                decimal(body.get("total")), decimal(body.get("signAfter")), decimal(body.get("baseDone")),
                decimal(body.get("capDone")), longOrNull(body.get("contractAssetId")),
                str(body.get("note")), str(body.get("workers")), nodeInputs(body.get("nodes"))), requireIdem(idem)));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "实际开工（START 期累计已核算门禁 007）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> start(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(plans.start(post(), id, new EngPlanService.NodeRecordCommand(
                localDate(body.get("date")), str(body.get("note")), longOrNull(body.get("fileAssetId")), false),
                requireIdem(idem)));
    }

    @PutMapping("/{id}/plan")
    @Operation(summary = "保存计划（整体提交；日期齐全+不重叠 002；已完成节点跳过=改期入口之一）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> savePlan(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(plans.savePlan(post(), id, nodeInputs(body.get("nodes")), requireIdem(idem)));
    }

    @PostMapping("/{id}/plan/reschedule")
    @Operation(summary = "整体改期（◆顺移算法+材料 required_date 随移+版本快照）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> reschedule(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(plans.reschedule(post(), id, new EngPlanService.RescheduleCommand(
                integer(body.get("nodeIndex")), localDate(body.get("newStart")), str(body.get("note"))),
                requireIdem(idem)));
    }

    @GetMapping("/{id}/plan/history")
    @Operation(summary = "计划版本快照列表")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> history(@PathVariable long id) {
        return success(plans.history(post(), id));
    }

    @PostMapping("/{id}/nodes/{nodeId}/complete")
    @Operation(summary = "节点完成（材料门禁 007+completion 记录自动落；无收款门禁）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> complete(@PathVariable long id, @PathVariable long nodeId,
                                                      @RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(plans.completeNode(post(), id, nodeId, new EngPlanService.NodeRecordCommand(
                localDate(body.get("date")), str(body.get("note")), longOrNull(body.get("fileAssetId")),
                Boolean.TRUE.equals(body.get("noMaterials"))), requireIdem(idem)));
    }

    @PostMapping("/{id}/nodes/{nodeId}/reopen")
    @Operation(summary = "撤回节点完成（最近完成+下节点无记录+未收封顶款 003）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> reopen(@PathVariable long id, @PathVariable long nodeId,
                                                    @RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(plans.reopenNode(post(), id, nodeId, str(body.get("reason")), requireIdem(idem)));
    }

    @PostMapping("/{id}/nodes/{nodeId}/correct")
    @Operation(summary = "更正节点完成（日期链约束含封顶完成≤封顶款收款日 003）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> correct(@PathVariable long id, @PathVariable long nodeId,
                                                     @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(plans.correctNode(post(), id, nodeId, new EngPlanService.NodeCorrectCommand(
                localDate(body.get("completedAt")), str(body.get("note")),
                longOrNull(body.get("fileAssetId")), str(body.get("reason"))), requireIdem(idem)));
    }

    static List<EngPlanService.NodeInput> nodeInputs(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(x -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) x;
            return new EngPlanService.NodeInput(
                    integer(m.get("nodeIndex")) == null ? 0 : integer(m.get("nodeIndex")),
                    str(m.get("name")), localDate(m.get("startDate")), localDate(m.get("endDate")));
        }).toList();
    }
}
