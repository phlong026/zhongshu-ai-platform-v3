package cn.iocoder.yudao.module.franchise.performance.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.performance.PerformancePermissions;
import cn.iocoder.yudao.module.franchise.performance.content.PerfDashboardService;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 经营看板（P1）：跨域只读聚合（客资/工程/设计），财务与部门口径由既有端点承担。 */
@Tag(name = "加盟 - 绩效提成（经营看板）")
@RestController
@RequestMapping("/franchise/performance")
@Validated
public class PerfDashboardController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private PerfDashboardService dashboard;

    @GetMapping("/dashboard")
    @Operation(summary = "经营看板跨域指标（?period=YYYY-MM；新增客户/线索/承接工程/服务委托数）")
    @PreAuthorize("@post.has('" + PerformancePermissions.OPERATE + "')")
    public CommonResult<Map<String, Object>> dashboard(@RequestParam String period) {
        PostSnapshot post = currentPost.require();
        return success(dashboard.dashboard(post, period));
    }
}
