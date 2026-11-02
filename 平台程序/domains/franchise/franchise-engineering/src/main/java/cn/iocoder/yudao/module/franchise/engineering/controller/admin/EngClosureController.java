package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.franchise.engineering.content.EngClosureService;
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
import static cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngProjectController.*;

/** 尾段三段：deliver（全累计收款门禁）→ settle（返回结算屏汇总）→ archive（前置链 009）。 */
@Tag(name = "加盟 - 工程全周期（尾段）")
@RestController
@RequestMapping("/franchise/engineering")
@Validated
public class EngClosureController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private EngClosureService closures;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @PostMapping("/{id}/deliver")
    @Operation(summary = "工程交付（开工+全节点+材料齐+全累计收款门禁 007）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> deliver(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(closures.deliver(post(), id, new EngClosureService.DeliverCommand(
                localDate(body.get("date")), str(body.get("recipient")),
                longOrNull(body.get("ownerAssetId")), longOrNull(body.get("fileAssetId")), str(body.get("note"))),
                requireIdem(idem)));
    }

    @PostMapping("/{id}/settle")
    @Operation(summary = "确认项目结算（前置交付 009；返回结算屏汇总）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> settle(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(closures.settle(post(), id, new EngClosureService.SettleCommand(
                localDate(body.get("date")), str(body.get("note")), longOrNull(body.get("assetId"))), requireIdem(idem)));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "归档项目资料（前置交付+结算 009）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> archive(@PathVariable long id,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(closures.archive(post(), id, requireIdem(idem)));
    }

    @GetMapping("/{id}/settle-summary")
    @Operation(summary = "结算屏汇总（材料聚合+项目费用 0 占位+两期收款）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> settleSummary(@PathVariable long id) {
        return success(closures.settleSummary(post(), id));
    }
}
