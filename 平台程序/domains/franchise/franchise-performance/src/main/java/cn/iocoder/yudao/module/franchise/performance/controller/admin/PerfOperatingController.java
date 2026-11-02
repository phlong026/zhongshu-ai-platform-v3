package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.franchise.performance.content.PerfOperatingService;
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

/** 经营视图（3.10）：analytics/快照归档/月末对账。 */
@Tag(name = "加盟 - 绩效提成（经营）")
@RestController
@RequestMapping("/franchise/performance")
@Validated
public class PerfOperatingController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private PerfOperatingService operating;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @GetMapping("/operating/analytics")
    @Operation(summary = "实时经营分析（?period=；近6月趋势/收支构成/应收应付/回款率/资金覆盖）")
    @PreAuthorize("@post.has('" + PerformancePermissions.OPERATE + "')")
    public CommonResult<Map<String, Object>> analytics(@RequestParam String period) {
        return success(operating.analytics(post(), period));
    }

    @PostMapping("/operating/snapshot")
    @Operation(summary = "经营快照归档（幂等；只允许过去月 008；签名未变 002；变化 version+1 supersedes）")
    @PreAuthorize("@post.has('" + PerformancePermissions.OPERATE + "')")
    public CommonResult<Map<String, Object>> snapshot(@RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(operating.snapshot(post(), str(body.get("period")), str(body.get("note")),
                requireIdem(idem)));
    }

    @GetMapping("/operating/snapshots")
    @Operation(summary = "快照列表（?period=）")
    @PreAuthorize("@post.has('" + PerformancePermissions.OPERATE + "')")
    public CommonResult<Map<String, Object>> snapshots(@RequestParam String period) {
        return success(operating.snapshotView(post(), period));
    }

    @PostMapping("/operating/reconcile")
    @Operation(summary = "月末对账（幂等；按月覆盖写；口径=fr_fin 当月已登记回款/已核实付退）")
    @PreAuthorize("@post.has('" + PerformancePermissions.OPERATE + "')")
    public CommonResult<Map<String, Object>> reconcile(@RequestBody Map<String, Object> body,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(operating.reconcile(post(), str(body.get("period")), requireIdem(idem)));
    }

    @GetMapping("/operating/reconciliations")
    @Operation(summary = "对账记录列表")
    @PreAuthorize("@post.has('" + PerformancePermissions.OPERATE + "')")
    public CommonResult<List<Map<String, Object>>> reconciliations() {
        return success(operating.reconciliationList(post()));
    }
}
