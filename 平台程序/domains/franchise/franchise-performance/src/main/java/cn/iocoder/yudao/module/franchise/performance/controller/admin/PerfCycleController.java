package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.franchise.performance.content.PerfCycleService;
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

/** 周期复核与申诉（F10）：submit→review→appeal-resolve。 */
@Tag(name = "加盟 - 绩效提成（复核）")
@RestController
@RequestMapping("/franchise/performance")
@Validated
public class PerfCycleController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private PerfCycleService cycles;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/cycles/submit")
    @Operation(summary = "提交复核（幂等；本人或 manage 代交；冻结签名+submission）")
    @PreAuthorize("@post.has('" + PerformancePermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> submit(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(cycles.submit(post(), str(body.get("period")),
                longOrNull(body.get("userId")) == null ? 0 : longOrNull(body.get("userId")),
                str(body.get("note")), requireIdem(idem)));
    }

    @PostMapping("/cycles/review")
    @Operation(summary = "复核（幂等，manage；不能复核本人 000；签名变化须退回）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> review(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(cycles.review(post(), str(body.get("period")),
                longOrNull(body.get("userId")) == null ? 0 : longOrNull(body.get("userId")),
                Boolean.TRUE.equals(body.get("approve")), str(body.get("note")), requireIdem(idem)));
    }

    @PostMapping("/cycles/appeal-resolve")
    @Operation(summary = "申诉裁决（幂等，manage=另一位老板；采纳→adjustments 生效+归档重开提示）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> appealResolve(@RequestBody Map<String, Object> body,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(cycles.appealResolve(post(), str(body.get("period")),
                longOrNull(body.get("userId")) == null ? 0 : longOrNull(body.get("userId")),
                Boolean.TRUE.equals(body.get("adopt")), str(body.get("sourceKey")),
                longOrNull(body.get("personUserId")), Boolean.TRUE.equals(body.get("excluded")),
                str(body.get("note")), requireIdem(idem)));
    }

    @GetMapping("/cycles")
    @Operation(summary = "周期列表（?period=）")
    @PreAuthorize("@post.has('" + PerformancePermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> list(@RequestParam String period) {
        return success(cycles.list(post(), period));
    }
}
