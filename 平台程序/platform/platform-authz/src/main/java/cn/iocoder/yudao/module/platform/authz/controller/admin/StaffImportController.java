package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.service.StaffAccountOnboardService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes.ORG_SCOPE_FORBIDDEN;

/**
 * P1 批2：员工批量导入（预检 + 提交）。B-only 装配（旧融合 legacy/absent 零 bean）。
 *
 * <p>权限与组织范围与 StaffOrgAdminController 的 createStaff 同源：
 * platform:org:manage + 当前岗位组织校验。
 */
@Tag(name = "平台 - 员工批量导入（B-only）")
@RestController
@RequestMapping("/platform/org/staff-import")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Validated
public class StaffImportController {

    public record ImportRowReq(Integer line, String name, String mobile, String roleCode, String teamName) {
    }

    public record ImportReq(@NotNull(message = "组织不能为空") Long orgId,
                           @Size(max = 500, message = "单批须为 1-500 行") List<ImportRowReq> rows) {
    }

    private final CurrentPost currentPost;
    private final StaffImportService importService;
    private final StaffAccountOnboardService staffAccounts;

    public StaffImportController(CurrentPost currentPost, StaffImportService importService,
                                 StaffAccountOnboardService staffAccounts) {
        this.currentPost = currentPost;
        this.importService = importService;
        this.staffAccounts = staffAccounts;
    }

    @PostMapping("/precheck")
    @Operation(summary = "员工导入预检（只读 dry-run；逐行错误，不入库）")
    @PreAuthorize("@post.has('platform:org:manage')")
    @ApiAccessLog(enable = false)
    public CommonResult<StaffImportService.ImportResult> precheck(@Valid @RequestBody ImportReq req) {
        long orgId = scopeOrg(req.orgId());
        return success(importService.precheck(orgId, rows(req.rows())));
    }

    @PostMapping
    @Operation(summary = "员工导入提交（逐行独立事务、部分成功；预检结果不作提交依据）")
    @PreAuthorize("@post.has('platform:org:manage')")
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<StaffImportService.ImportResult> submit(@Valid @RequestBody ImportReq req) {
        long orgId = scopeOrg(req.orgId());
        PostSnapshot post = currentPost.require();
        if ("store".equals(post.orgType())) {
            // 不包批次事务：每次调用真实代理的 open 都独立提交，失败行不会撤回此前成功行。
            return success(importService.submit(orgId, rows(req.rows()), post.userId(), (row, teamId) -> {
                var account = staffAccounts.open(orgId, row.userId(), row.name(), row.mobile(), row.roleCode(),
                        teamId, post.userId(), post.postId());
                return new StaffImportService.CreatedStaff(account.postId(), account.userId(),
                        new StaffImportService.AccountDelivery(account.accountId(), account.name(), account.orgName(),
                                account.loginName(), account.aliases(), account.initialPassword(), account.reusedUser()));
            }));
        }
        return success(importService.submit(orgId, rows(req.rows()), post.userId()));
    }

    private long scopeOrg(Long orgId) {
        PostSnapshot post = currentPost.require();
        if (orgId == null || post.orgId() != orgId) {
            throw exception(ORG_SCOPE_FORBIDDEN);
        }
        if ("store".equals(post.orgType()) && !List.of("boss", "owner").contains(post.roleCode())) {
            throw exception(ORG_SCOPE_FORBIDDEN).setMessage("员工账号导入仅允许门店老板或负责人操作");
        }
        return orgId;
    }

    private static List<StaffImportService.Row> rows(List<ImportRowReq> rows) {
        if (rows == null) {
            return List.of();
        }
        List<StaffImportService.Row> result = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            ImportRowReq row = rows.get(i);
            if (row == null) {
                throw new ServiceException(400, "第 " + (i + 1) + " 行数据为空");
            }
            result.add(new StaffImportService.Row(row.line(), row.name(), row.mobile(),
                    row.roleCode(), row.teamName()));
        }
        return result;
    }
}
