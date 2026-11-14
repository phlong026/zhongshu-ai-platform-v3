package cn.iocoder.yudao.module.franchise.org.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import cn.iocoder.yudao.module.franchise.org.point.CompanyPointAccess;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "加盟 - 公司积分池")
@RestController
@RequestMapping("/franchise/company-points")
public class CompanyPointController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private CompanyPointAccess access;

    @PostMapping("/activate")
    @Operation(summary = "老板为当前公司开通零余额积分池")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<OrgPointPort.PoolSummary> activate() {
        return success(access.activate(currentPost.require()));
    }

    @GetMapping("/summary")
    @Operation(summary = "查看当前公司的积分余额和当月总用量")
    public CommonResult<OrgPointPort.PoolSummary> summary() {
        return success(access.summary(currentPost.require()));
    }

    @GetMapping("/my-usage")
    @Operation(summary = "查看本人当月积分用量和流水（无员工月度额度）")
    public CommonResult<OrgPointPort.MyUsage> myUsage(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(access.myUsage(currentPost.require(), month, page, size));
    }

    @GetMapping("/ledger")
    @Operation(summary = "老板查看当前公司的积分流水")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<List<OrgPointPort.LedgerEntry>> ledger(
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(access.ledger(currentPost.require(), actorUserId, subject, month, page, size));
    }

    @GetMapping("/operations")
    @Operation(summary = "老板查看当前公司的任务计费状态")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<List<OrgPointPort.OperationView>> operations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return success(access.operations(currentPost.require(), page, size));
    }

    @GetMapping("/member-permissions")
    @Operation(summary = "老板查看员工业务权限")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<List<OrgPointPort.MemberPermission>> memberPermissions() {
        return success(access.memberPermissions(currentPost.require()));
    }

    @PutMapping("/member-permissions/{userId}")
    @Operation(summary = "老板设置当前公司员工的业务权限；空数组表示全部禁用")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<Boolean> setMemberPermission(@PathVariable long userId,
                                                     @RequestBody PermissionBody body) {
        access.setMemberPermission(currentPost.require(), userId,
                body == null ? null : body.allowedSubjects());
        return success(true);
    }

    @DeleteMapping("/member-permissions/{userId}")
    @Operation(summary = "老板清除员工权限限制，恢复全部业务可用")
    @PreAuthorize("@post.has('platform:org:manage')")
    public CommonResult<Boolean> clearMemberPermission(@PathVariable long userId) {
        access.clearMemberPermission(currentPost.require(), userId);
        return success(true);
    }

    public record PermissionBody(List<String> allowedSubjects) {
    }
}
