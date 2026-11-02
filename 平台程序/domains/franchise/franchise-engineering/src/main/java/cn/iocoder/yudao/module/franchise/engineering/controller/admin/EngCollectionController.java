package cn.iocoder.yudao.module.franchise.engineering.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.engineering.EngineeringPermissions;
import cn.iocoder.yudao.module.franchise.engineering.content.EngCollectionService;
import cn.iocoder.yudao.module.franchise.engineering.content.EngFollowupService;
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
import static cn.iocoder.yudao.module.franchise.engineering.controller.admin.EngProjectController.*;

/** 收款：两期汇总/登记（collect 码；TOPPING 收款侧前置+累计门禁+写通 finance）/催收回访。 */
@Tag(name = "加盟 - 工程全周期（收款）")
@RestController
@RequestMapping("/franchise/engineering")
@Validated
public class EngCollectionController {

    @Resource
    private CurrentPost currentPost;
    @Resource
    private EngCollectionService collections;
    @Resource
    private EngFollowupService followups;

    private PostSnapshot post() {
        return currentPost.require();
    }

    @GetMapping("/{id}/collections")
    @Operation(summary = "两期汇总（expected/confirmed/remaining+约定 due）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<Map<String, Object>> summary(@PathVariable long id) {
        return success(Map.of("stages", collections.summary(post(), id),
                "records", collections.list(post(), id)));
    }

    @PostMapping("/{id}/collections")
    @Operation(summary = "收款登记（collect 码；TOPPING 须封顶完成；本期累计≤应收 006；写通 finance）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.COLLECT + "')")
    public CommonResult<Map<String, Object>> register(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(collections.register(post(), id, new EngCollectionService.CollectCommand(
                str(body.get("stage")), decimal(body.get("amount")), str(body.get("transactionNo")),
                localDate(body.get("occurredAt")), longOrNull(body.get("fileAssetId"))), requireIdem(idem)));
    }

    @PostMapping("/{id}/dunning")
    @Operation(summary = "催收回访（stage 维度，同日同内容幂等；TOPPING 须封顶完成）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.WRITE + "')")
    public CommonResult<Map<String, Object>> dunning(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idem) {
        return success(Map.of("followups", followups.dunning(post(), id, new EngFollowupService.DunningCommand(
                str(body.get("stage")), localDate(body.get("promiseDate")), str(body.get("note"))), requireIdem(idem))));
    }

    @GetMapping("/{id}/followups")
    @Operation(summary = "催办记录列表（收款+材料双维度）")
    @PreAuthorize("@post.has('" + EngineeringPermissions.QUERY + "')")
    public CommonResult<List<Map<String, Object>>> followupList(@PathVariable long id) {
        return success(followups.list(post(), id));
    }
}
