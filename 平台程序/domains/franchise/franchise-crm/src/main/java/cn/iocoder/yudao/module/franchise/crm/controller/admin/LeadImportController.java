package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.franchise.crm.LeadImportService;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * P1 批3：客资批量导入（Web /fr/customers，boss/owner）。B-only 装配。
 */
@Tag(name = "加盟协同 - 客资批量导入（B-only）")
@RestController
@RequestMapping("/franchise/lead-import")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Validated
public class LeadImportController {

    public record ImportRowReq(Integer line, String name, String phone, String need, String source) {
    }

    /**
     * 注意：rows 不做 @Size 校验——Bean Validation 会把整个被拒集合写入异常 message，
     * 全局异常处理器再打印该 message，等于把整批手机号写进日志（PII）。上限由 Service 权威校验。
     */
    public record ImportReq(@NotNull(message = "组织不能为空") Long orgId,
                           @NotBlank(message = "缺少批次号") String batchId,
                           List<ImportRowReq> rows) {
    }

    private final CurrentPost currentPost;
    private final LeadImportService importService;

    public LeadImportController(CurrentPost currentPost, LeadImportService importService) {
        this.currentPost = currentPost;
        this.importService = importService;
    }

    @PostMapping("/precheck")
    @Operation(summary = "客资导入预检（只读 dry-run）")
    @PreAuthorize("@post.has('franchise:lead:handle')")
    @ApiAccessLog(enable = false)
    public CommonResult<LeadImportService.ImportResult> precheck(@Valid @RequestBody ImportReq req) {
        long orgId = scopeOrg(req.orgId());
        return success(importService.precheck(currentPost.require(), req.batchId(), rows(req.rows())));
    }

    @PostMapping
    @Operation(summary = "客资导入提交（逐行独立事务、部分成功）")
    @PreAuthorize("@post.has('franchise:lead:handle')")
    @ApiAccessLog(requestEnable = false)
    public CommonResult<LeadImportService.ImportResult> submit(@Valid @RequestBody ImportReq req) {
        long orgId = scopeOrg(req.orgId());
        return success(importService.submit(currentPost.require(), req.batchId(), rows(req.rows())));
    }

    private long scopeOrg(Long orgId) {
        var post = currentPost.require();
        if (orgId == null || post.orgId() != orgId) {
            throw exception(ORG_SCOPE_FORBIDDEN);
        }
        return orgId;
    }

    private static List<LeadImportService.Row> rows(List<ImportRowReq> rows) {
        if (rows == null) return List.of();
        var result = new java.util.ArrayList<LeadImportService.Row>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row == null) throw new ServiceException(400, "第 " + (i + 1) + " 行数据为空");
            int line = row.line() == null || row.line() <= 0 ? i + 1 : row.line();
            result.add(new LeadImportService.Row(line, row.name(), row.phone(), row.need(), row.source()));
        }
        return result;
    }
}
