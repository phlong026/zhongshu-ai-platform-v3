package cn.iocoder.yudao.module.franchise.commission.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.commission.DesignPolicyService;
import cn.iocoder.yudao.module.franchise.commission.DesignPolicyService.PolicyCommand;
import cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController.IDEMPOTENCY_HEADER;

/**
 * 标准设计费用：门店老板配置，版本只追加；委托费用与当前有效标准一致时免审批。
 */
@Tag(name = "加盟协同 - 标准设计费用")
@RestController
@RequestMapping("/franchise/design-policies")
public class DesignPolicyController {

    private final DesignPolicyService policyService;
    private final CurrentPost currentPost;

    public DesignPolicyController(DesignPolicyService policyService, CurrentPost currentPost) {
        this.policyService = policyService;
        this.currentPost = currentPost;
    }

    public record PolicyReq(java.math.BigDecimal total, java.math.BigDecimal first,
                            String scope, String recipient) {
    }

    @GetMapping("/current")
    @Operation(summary = "本门店当前有效的标准设计费用")
    @PreAuthorize("@post.has('franchise:design-policy:manage')")
    public CommonResult<DesignPolicyDO> current() {
        return success(policyService.getCurrent(currentPost.require()));
    }

    @PostMapping
    @Operation(summary = "保存标准设计费用（每次保存一个新版本并作为当前有效标准）")
    @PreAuthorize("@post.has('franchise:design-policy:manage')")
    public CommonResult<DesignPolicyDO> save(@Valid @RequestBody PolicyReq req,
                                             @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(policyService.save(currentPost.require(),
                new PolicyCommand(req.total(), req.first(), req.scope(), req.recipient()), key));
    }

}
