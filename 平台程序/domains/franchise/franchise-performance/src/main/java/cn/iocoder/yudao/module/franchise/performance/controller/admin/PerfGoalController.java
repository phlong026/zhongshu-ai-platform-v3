package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.franchise.performance.content.PerfGoalService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.performance.controller.admin.PerfCommon.*;

/** 月度目标（3.7）：三级设置/查询；归档期拒改（002）。 */
@Tag(name = "加盟 - 绩效提成（目标）")
@RestController
@RequestMapping("/franchise/performance")
@Validated
public class PerfGoalController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private PerfGoalService goals;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @GetMapping("/goals")
    @Operation(summary = "目标查询（?period=；含锁定态）")
    @PreAuthorize("@post.has('" + PerformancePermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> get(@RequestParam String period) {
        return success(goals.get(post(), period));
    }

    @PutMapping("/goals")
    @Operation(summary = "三级目标保存（幂等，manage；公司级/部门级/人员部门级+旧标量兼容）")
    @PreAuthorize("@post.has('" + PerformancePermissions.MANAGE + "')")
    public CommonResult<Map<String, Object>> save(@RequestBody Map<String, Object> body,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(goals.save(post(), new PerfGoalService.GoalCommand(
                str(body.get("period")), centsOrNull(body.get("sales")), centsOrNull(body.get("collections")),
                intOrNull(body.get("effective")), intOrNull(body.get("customers")), intOrNull(body.get("nodes")),
                map(body.get("departmentGoals")), map(body.get("staffDepartmentGoals")),
                map(body.get("legacyStaff"))), requireIdem(idem)));
    }
}
