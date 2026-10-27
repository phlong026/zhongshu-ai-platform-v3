package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Capture;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Lead;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 客资。员工在 Web 与小程序都调本接口（/admin-api），按请求头 X-Post-Id 指定的工作身份办理。
 */
@Tag(name = "加盟协同 - 客资")
@RestController
@RequestMapping("/franchise/leads")
public class LeadController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final LeadService leadService;
    private final CurrentPost currentPost;

    public LeadController(LeadService leadService, CurrentPost currentPost) {
        this.leadService = leadService;
        this.currentPost = currentPost;
    }

    public record RevisionReq(long revision) {
    }

    public record InvalidReq(String note, long revision) {
    }

    @PostMapping
    @Operation(summary = "登记客资：新手机号直接建客户；同手机号原客户本人有权查看则关联，否则等负责人关联")
    @PreAuthorize("@post.has('franchise:lead:capture')")
    public CommonResult<Capture> capture(@RequestBody CaptureCommand req,
                                          @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(Capture.of(leadService.capture(currentPost.require(), req, key)));
    }

    @GetMapping("/pending")
    @Operation(summary = "待关联客资：老板看全部，负责人看管理范围，登记人看自己的")
    @PreAuthorize("@post.has('franchise:lead:handle')")
    public CommonResult<List<Lead>> pending() {
        return success(leadService.listPending(currentPost.require()).stream().map(Lead::of).toList());
    }

    @PostMapping("/{id}/link")
    @Operation(summary = "关联同手机号原客户")
    @PreAuthorize("@post.has('franchise:lead:handle')")
    public CommonResult<Lead> link(@PathVariable long id, @RequestBody RevisionReq req,
                                  @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(Lead.of(leadService.link(currentPost.require(), id, req.revision(), key)));
    }

    @PostMapping("/{id}/invalid")
    @Operation(summary = "作废待关联客资")
    @PreAuthorize("@post.has('franchise:lead:handle')")
    public CommonResult<Lead> invalidate(@PathVariable long id, @RequestBody InvalidReq req,
                                          @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(Lead.of(leadService.invalidate(currentPost.require(), id, req.note(), req.revision(), key)));
    }

}
