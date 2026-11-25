package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.platform.authz.service.OrgDirectoryService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;
import io.swagger.v3.oas.annotations.tags.Tag;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** 沿用组织开通权限，老板及普通员工不能读取平台组织目录。 */
@RestController
@Tag(name="平台组织管理")
@RequestMapping("/platform/org/directory")
@PreAuthorize("@ss.hasPermission('platform:org:onboard')")
public class OrgDirectoryController {
    @Resource private OrgDirectoryService service;

    @GetMapping
    public CommonResult<PageResult<OrgDirectoryService.Organization>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String orgType,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return success(service.page(keyword, orgType, pageNo, pageSize, includeDeleted));
    }

    @GetMapping("/{orgId}")
    public CommonResult<OrgDirectoryService.Detail> detail(@PathVariable long orgId) {
        return success(service.detail(orgId));
    }

    @GetMapping("/{orgId}/members")
    public CommonResult<PageResult<Map<String,Object>>> members(@PathVariable long orgId,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return success(service.members(orgId,pageNo,pageSize));
    }
    @GetMapping("/{orgId}/points")
    public CommonResult<Map<String,Object>> points(@PathVariable long orgId) { return success(service.pointSummary(orgId)); }
    @GetMapping("/{orgId}/ledger")
    public CommonResult<PageResult<Map<String,Object>>> ledger(@PathVariable long orgId,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return success(service.ledger(orgId,pageNo,pageSize));
    }
    @GetMapping("/{orgId}/generations")
    public CommonResult<PageResult<Map<String,Object>>> generations(@PathVariable long orgId,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return success(service.generations(orgId,pageNo,pageSize));
    }
    @GetMapping("/{orgId}/audits")
    public CommonResult<PageResult<Map<String,Object>>> audits(@PathVariable long orgId,
            @RequestParam(defaultValue="1") int pageNo,@RequestParam(defaultValue="20") int pageSize) {
        return success(service.audits(orgId,pageNo,pageSize));
    }
    public record LifecycleRequest(String action,String confirmedName,String reason,String requestKey,String expectedAuditId) {
        public LifecycleRequest(String action,String confirmedName,String reason,String requestKey) { this(action,confirmedName,reason,requestKey,null); }
    }
    @PostMapping("/{orgId}/lifecycle")
    @PreAuthorize("@ss.hasPermission('platform:org:onboard') && @ss.hasRole('super_admin')")
    public CommonResult<OrgDirectoryService.Detail> lifecycle(@PathVariable long orgId,@RequestBody LifecycleRequest body) {
        return success(service.lifecycle(orgId,body.action(),body.confirmedName(),body.reason(),body.requestKey(),SecurityFrameworkUtils.getLoginUserId(),body.expectedAuditId()));
    }
}
