package cn.iocoder.yudao.module.franchise.crm.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.Detail;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Customer;
import cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.CustomerDetail;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.FollowCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.TransferCommand;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.UpdateCommand;
import cn.iocoder.yudao.module.franchise.crm.dal.CustomerDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.franchise.crm.controller.admin.LeadController.IDEMPOTENCY_HEADER;

/**
 * 客户：查看、跟进与预约回访、编辑、参考方案与预算版本、责任转交。
 * 写接口带 revision（编辑、转交）防止覆盖他人改动；可带 Idempotency-Key 防止重复提交。
 */
@Tag(name = "加盟协同 - 客户")
@RestController
@RequestMapping("/franchise/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final CurrentPost currentPost;

    public CustomerController(CustomerService customerService, CurrentPost currentPost) {
        this.customerService = customerService;
        this.currentPost = currentPost;
    }

    public record UpdateReq(String name, String wechat, String province, String city, String summary, long revision) {
    }

    public record TransferReq(long targetUserId, String reason, long revision) {
    }

    @GetMapping
    @Operation(summary = "客户列表：业务员看本人经办，负责人看管理团队，老板看全部")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<PageResult<Customer>> page(@Valid PageParam page, @RequestParam(required = false) String keyword) {
        return success(cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(
                customerService.page(currentPost.require(), page, keyword), Customer.class));
    }

    @GetMapping("/{id}")
    @Operation(summary = "客户详情（含跟进记录、方案与预算版本）")
    @PreAuthorize("@post.has('franchise:customer:query')")
    public CommonResult<CustomerDetail> get(@PathVariable long id) {
        return success(CustomerDetail.of(customerService.get(currentPost.require(), id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑客户基本信息（手机号不可改）")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<CustomerDetail> update(@PathVariable long id, @RequestBody UpdateReq req,
                                       @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        UpdateCommand command = new UpdateCommand(req.name(), req.wechat(), req.province(), req.city(), req.summary());
        return success(CustomerDetail.of(customerService.update(currentPost.require(), id, command, req.revision(), key)));
    }

    @PostMapping("/{id}/follows")
    @Operation(summary = "登记跟进，可预约回访日期")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<CustomerDetail> follow(@PathVariable long id, @RequestBody FollowCommand req,
                                            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(CustomerDetail.of(customerService.follow(currentPost.require(), id, req, key)));
    }

    @PostMapping("/{id}/schemes")
    @Operation(summary = "保存参考方案或预算版本（BUDGET / PLAN / FACADE）")
    @PreAuthorize("@post.has('franchise:customer:write')")
    public CommonResult<cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Scheme> saveScheme(@PathVariable long id, @RequestBody SchemeCommand req,
                                                     @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(cn.iocoder.yudao.module.franchise.crm.controller.admin.vo.CrmRespVos.Scheme.of(
                customerService.saveScheme(currentPost.require(), id, req, key)));
    }

    @PostMapping("/{id}/transfer")
    @Operation(summary = "责任转交（人员变动时由老板或业务负责人办理）")
    @PreAuthorize("@post.has('franchise:customer:transfer')")
    public CommonResult<CustomerDetail> transfer(@PathVariable long id, @RequestBody TransferReq req,
                                         @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        return success(CustomerDetail.of(customerService.transfer(currentPost.require(), id,
                new TransferCommand(req.targetUserId(), req.reason()), req.revision(), key)));
    }

}
