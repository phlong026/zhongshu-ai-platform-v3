package cn.iocoder.yudao.module.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.identity.mapping.DesignIdentityMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 旧设计账号身份映射（24 号任务书 B-15）：生成候选 / 人工确认 / 未匹配清单 / 映射报告。
 * 平台级操作走 yudao 权限码（无 pf 岗位上下文）。
 */
@Tag(name = "旧设计账号身份映射")
@RestController
@RequestMapping("/design/v1/identity-mapping")
public class DesignIdentityMappingController {

    @Resource
    private DesignIdentityMappingService mappingService;

    public record ConfirmReqVO(@NotNull(message = "员工不能为空") Long userId,
                               @NotNull(message = "组织不能为空") Long orgId,
                               @NotBlank(message = "确认必须填写依据说明") String note) {
    }

    public record RejectReqVO(@NotBlank(message = "请填写驳回原因") String note) {
    }

    @PostMapping("/generate")
    @Operation(summary = "扫描已兑换记录生成候选映射（幂等，不覆盖人工结论）")
    @PreAuthorize("@ss.hasPermission('identity:identity-mapping:manage')")
    public CommonResult<DesignIdentityMappingService.GenerateResult> generate() {
        return success(mappingService.generate());
    }

    @GetMapping
    @Operation(summary = "映射清单（可按状态/组织筛选）")
    @PreAuthorize("@ss.hasPermission('identity:identity-mapping:query')")
    public CommonResult<List<DesignIdentityMappingService.MappingRow>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return success(mappingService.list(status, orgId, page, size));
    }

    @GetMapping("/report")
    @Operation(summary = "映射报告（含未匹配过渡清单）")
    @PreAuthorize("@ss.hasPermission('identity:identity-mapping:query')")
    public CommonResult<DesignIdentityMappingService.MappingReport> report() {
        return success(mappingService.report());
    }

    @PostMapping("/{accountId}/confirm")
    @Operation(summary = "人工确认账号归属（员工+组织）")
    @PreAuthorize("@ss.hasPermission('identity:identity-mapping:manage')")
    public CommonResult<Boolean> confirm(@PathVariable("accountId") long accountId,
                                         @Valid @RequestBody ConfirmReqVO req) {
        mappingService.confirm(accountId, req.userId(), req.orgId(), req.note(), operator());
        return success(true);
    }

    @PostMapping("/{accountId}/reject")
    @Operation(summary = "驳回（无法归属，进入过渡清单；保留 grant 只读续期）")
    @PreAuthorize("@ss.hasPermission('identity:identity-mapping:manage')")
    public CommonResult<Boolean> reject(@PathVariable("accountId") long accountId,
                                        @Valid @RequestBody RejectReqVO req) {
        mappingService.reject(accountId, req.note(), operator());
        return success(true);
    }

    private String operator() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? "system" : String.valueOf(userId);
    }
}
