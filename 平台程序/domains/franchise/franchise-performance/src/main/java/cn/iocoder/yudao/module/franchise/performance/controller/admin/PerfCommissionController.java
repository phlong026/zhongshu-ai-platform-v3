package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.franchise.performance.content.PerfCommissionService;
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
import static cn.iocoder.yudao.module.franchise.performance.controller.admin.PerfCommon.*;

/** 回款提成（3.9）：policy（严格 boss）/release（finance 族）/reconcile（严格 boss）/对账行。 */
@Tag(name = "加盟 - 绩效提成（提成）")
@RestController
@RequestMapping("/franchise/performance")
@Validated
public class PerfCommissionController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private PerfCommissionService commissions;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @GetMapping("/commission/rows")
    @Operation(summary = "对账行（每策略×两阶段五态状态机+paid/outstanding）")
    @PreAuthorize("@post.has('" + PerformancePermissions.COMMISSION + "')")
    public CommonResult<List<Map<String, Object>>> rows() {
        return success(commissions.rows(post()));
    }

    @PostMapping("/commission/policy")
    @Operation(summary = "提成规则（幂等；服务层严格 boss——owner 持码越界 000；对半下取整；已有 release 硬锁 004）")
    @PreAuthorize("@post.has('" + PerformancePermissions.COMMISSION + "')")
    public CommonResult<Map<String, Object>> policy(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(commissions.policy(post(),
                longOrNull(body.get("projectId")) == null ? 0 : longOrNull(body.get("projectId")),
                longOrNull(body.get("totalCents")) == null ? 0 : longOrNull(body.get("totalCents")),
                longOrNull(body.get("assigneeUserId")) == null ? 0 : longOrNull(body.get("assigneeUserId")),
                str(body.get("assigneeName")), str(body.get("evidenceRef")), str(body.get("note")),
                requireIdem(idem)));
    }

    @PostMapping("/commission/release")
    @Operation(summary = "生成提成应付（幂等；服务层 finance 族；净实收足额 007；agreementRef 兜底标识）")
    @PreAuthorize("@post.has('" + PerformancePermissions.COMMISSION + "')")
    public CommonResult<Map<String, Object>> release(@RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(commissions.release(post(),
                longOrNull(body.get("projectId")) == null ? 0 : longOrNull(body.get("projectId")),
                str(body.get("stage")), requireIdem(idem)));
    }

    @PostMapping("/commission/reconcile")
    @Operation(summary = "人工核对（幂等；服务层严格 boss；刷新收款签名；仍须足额 007）")
    @PreAuthorize("@post.has('" + PerformancePermissions.COMMISSION + "')")
    public CommonResult<Map<String, Object>> reconcile(@RequestBody Map<String, Object> body,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(commissions.reconcile(post(),
                longOrNull(body.get("projectId")) == null ? 0 : longOrNull(body.get("projectId")),
                str(body.get("stage")), str(body.get("evidenceRef")), str(body.get("note")),
                requireIdem(idem)));
    }
}
