package cn.iocoder.yudao.module.commerce.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.commerce.orgpoint.DesignPointMigrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 存量个人点 → 公司池迁移（24 号任务书 B-16）：预检与执行。
 * 平台级操作走 yudao 权限码（复用公司池管理权限）。
 */
@Tag(name = "存量点数迁移")
@RestController
@RequestMapping("/design/v1/point-migration")
public class DesignPointMigrationController {

    @Resource
    private DesignPointMigrationService migrationService;

    @GetMapping("/dry-run")
    @Operation(summary = "迁移预检：可迁账号按组织聚合 + 阻塞清单（在途/未确认映射）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point:query')")
    public CommonResult<DesignPointMigrationService.MigrationReport> dryRun() {
        return success(migrationService.dryRun());
    }

    @PostMapping("/execute")
    @Operation(summary = "执行迁移（存在阻塞账号时整体拒绝；幂等可重跑；守恒校验）")
    @PreAuthorize("@ss.hasPermission('commerce:org-point:adjust')")
    public CommonResult<DesignPointMigrationService.MigrationResult> execute() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return success(migrationService.execute(userId == null ? "system" : String.valueOf(userId)));
    }
}
